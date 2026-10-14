"""Tenant lookup (cached) and provisioning."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Any

from sqlalchemy import select

from app.cache.redis import Cache, safe
from app.core.config import Settings
from app.db.session import Database
from app.models import AppRole, AppTenant, AppUser, TenantStatus
from app.security.passwords import hash_password, validate_password_policy
from app.services.audit import AuditContext, record
from app.services.orders import seed_default_statuses


@dataclass(frozen=True, slots=True)
class TenantInfo:
    id: uuid.UUID
    slug: str
    status: TenantStatus
    rate_limit_per_min: int

    @property
    def active(self) -> bool:
        return self.status == TenantStatus.active


class TenantService:
    def __init__(self, settings: Settings, db: Database, cache: Cache) -> None:
        self._s = settings
        self._db = db
        self._cache = cache

    def effective_rate_limit(self, configured: int | None) -> int:
        """Tenant limits are clamped to the platform maximum at read time as well as write time."""
        value = configured or self._s.rl_tenant_default_per_min
        return max(1, min(value, self._s.rl_tenant_max_per_min))

    def _info(self, t: AppTenant) -> TenantInfo:
        return TenantInfo(t.id, t.slug, t.status, self.effective_rate_limit(t.rate_limit_per_min))

    async def get(self, tenant_id: uuid.UUID) -> TenantInfo | None:
        key = self._cache.keys.tenant(str(tenant_id))
        ok, cached = await safe(self._cache.get_json(key), op="tenant.get")
        if ok and cached is not None:
            return TenantInfo(
                uuid.UUID(cached["id"]),
                cached["slug"],
                TenantStatus(cached["status"]),
                self.effective_rate_limit(cached["rl"]),
            )
        async with self._db.anonymous_session() as s:
            t = (await s.execute(select(AppTenant).where(AppTenant.id == tenant_id))).scalar_one_or_none()
        if t is None:
            return None
        info = self._info(t)
        if ok:
            payload: dict[str, Any] = {
                "id": str(t.id),
                "slug": t.slug,
                "status": t.status.value,
                "rl": t.rate_limit_per_min,
            }
            await safe(self._cache.set_json(key, payload, self._s.tenant_cache_ttl_s), op="tenant.set")
        return info

    async def get_by_slug(self, slug: str) -> TenantInfo | None:
        async with self._db.anonymous_session() as s:
            t = (await s.execute(select(AppTenant).where(AppTenant.slug == slug))).scalar_one_or_none()
        return None if t is None else self._info(t)

    async def invalidate(self, tenant_id: uuid.UUID) -> None:
        await safe(self._cache.delete(self._cache.keys.tenant(str(tenant_id))), op="tenant.invalidate")

    async def provision(
        self,
        *,
        admin_id: uuid.UUID,
        slug: str,
        name: str,
        owner_email: str,
        owner_password: str,
        rate_limit_per_min: int | None,
        ctx: AuditContext,
    ) -> tuple[AppTenant, AppUser]:
        """Create a tenant and its first tenant-admin user in a single transaction."""
        validate_password_policy(owner_password)
        if rate_limit_per_min is not None and rate_limit_per_min > self._s.rl_tenant_max_per_min:
            raise ValueError("rate limit exceeds platform maximum")
        pw_hash = await hash_password(owner_password)
        async with self._db.platform_session(admin_id) as s:
            tenant = AppTenant(slug=slug, name=name, rate_limit_per_min=rate_limit_per_min)
            s.add(tenant)
            await s.flush()
            # Explicitly enter the new tenant's context; RLS still governs every tenant-owned insert.
            await self._db.switch_tenant(s, tenant.id)
            user = AppUser(
                tenant_id=tenant.id, email=owner_email.lower(), password_hash=pw_hash, is_tenant_admin=True
            )
            s.add(user)
            s.add(
                AppRole(
                    tenant_id=tenant.id, name="tenant_admin", is_system=True, description="Full tenant access"
                )
            )
            await s.flush()
            await seed_default_statuses(s, tenant.id)
            await record(
                s,
                ctx,
                tenant_id=None,
                action="tenant.provision",
                resource="tenants",
                resource_id=str(tenant.id),
                detail={"slug": slug},
            )
            await record(
                s,
                ctx,
                tenant_id=tenant.id,
                action="user.create",
                resource="users",
                resource_id=str(user.id),
                detail={"tenant_admin": True},
            )
            await s.commit()
        return tenant, user

    async def update(
        self,
        *,
        admin_id: uuid.UUID,
        tenant_id: uuid.UUID,
        status: TenantStatus | None,
        rate_limit_per_min: int | None,
        ctx: AuditContext,
    ) -> AppTenant | None:
        if rate_limit_per_min is not None and rate_limit_per_min > self._s.rl_tenant_max_per_min:
            raise ValueError("rate limit exceeds platform maximum")
        async with self._db.platform_session(admin_id) as s:
            t = (
                await s.execute(select(AppTenant).where(AppTenant.id == tenant_id).with_for_update())
            ).scalar_one_or_none()
            if t is None:
                return None
            changes: dict[str, Any] = {}
            if status is not None and status != t.status:
                changes["status"] = [t.status.value, status.value]
                t.status = status
            if rate_limit_per_min is not None and rate_limit_per_min != t.rate_limit_per_min:
                changes["rate_limit_per_min"] = [t.rate_limit_per_min, rate_limit_per_min]
                t.rate_limit_per_min = rate_limit_per_min
            await record(
                s,
                ctx,
                tenant_id=None,
                action="tenant.update",
                resource="tenants",
                resource_id=str(tenant_id),
                detail=changes,
            )
            await s.commit()
        await self.invalidate(tenant_id)
        return t

    async def set_own_rate_limit(
        self, *, tenant_id: uuid.UUID, user_id: uuid.UUID, rate_limit_per_min: int, ctx: AuditContext
    ) -> int:
        """Tenant-admin live update, bounded by the platform maximum."""
        if not 1 <= rate_limit_per_min <= self._s.rl_tenant_max_per_min:
            raise ValueError(f"rate limit must be between 1 and {self._s.rl_tenant_max_per_min}")
        async with self._db.tenant_session(tenant_id, user_id=user_id) as s:
            t = (
                await s.execute(select(AppTenant).where(AppTenant.id == tenant_id).with_for_update())
            ).scalar_one()
            old = t.rate_limit_per_min
            t.rate_limit_per_min = rate_limit_per_min
            await record(
                s,
                ctx,
                tenant_id=tenant_id,
                action="tenant.rate_limit.update",
                resource="tenants",
                resource_id=str(tenant_id),
                detail={"old": old, "new": rate_limit_per_min},
            )
            await s.commit()
        await self.invalidate(tenant_id)
        return rate_limit_per_min
