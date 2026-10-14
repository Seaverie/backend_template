"""Permission matrices, principals, and the versioned permission cache.

Cache consistency
-----------------
Every authorization change (roles, permissions, user activation, admin flag) increments
``app_tenants.authz_version`` *in the same transaction*. Permission matrices are cached under keys
that include that version, so a bump makes old entries unreachable. After commit the new version is
pushed to Redis with a set-if-greater script; if that push fails, the cached version key expires
after ``AUTHZ_VERSION_TTL_S`` and is reloaded from the database. That TTL is therefore the worst-case
staleness window for permission changes. With Redis healthy, changes apply on the next request.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from typing import Any, Literal

from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.cache.redis import Cache, safe
from app.core.config import Settings
from app.core.errors import Forbidden
from app.db.session import Database
from app.models import Action, AppPermission, AppRolePermission, AppUser, AppUserRole, RowScope

AUTHZ_VERSION_TTL_S = 30


@dataclass(frozen=True, slots=True)
class Grant:
    row_scope: RowScope
    readable: frozenset[str] | None  # None = all fields
    writable: frozenset[str] | None

    def can_read(self, f: str) -> bool:
        return self.readable is None or f in self.readable

    def can_write(self, f: str) -> bool:
        return self.writable is None or f in self.writable

    def to_json(self) -> dict[str, Any]:
        return {
            "s": self.row_scope.value,
            "r": None if self.readable is None else sorted(self.readable),
            "w": None if self.writable is None else sorted(self.writable),
        }

    @classmethod
    def from_json(cls, d: dict[str, Any]) -> Grant:
        return cls(
            RowScope(d["s"]),
            None if d["r"] is None else frozenset(d["r"]),
            None if d["w"] is None else frozenset(d["w"]),
        )


FULL_GRANT = Grant(RowScope.all, None, None)


def _union(a: frozenset[str] | None, b: frozenset[str] | None) -> frozenset[str] | None:
    return None if a is None or b is None else a | b


@dataclass(frozen=True, slots=True)
class PermissionMatrix:
    """Effective permissions of one user.

    For each (resource, action) at most one grant per row scope is kept (field lists unioned within a
    scope). When a user holds both an ``all`` and an ``own`` grant, :meth:`grant` returns the ``all``
    grant on its own. Combining the two would give the all-rows grant the own-rows grant's extra
    fields, which is an escalation. The trade-off is that a user can under-receive fields on rows
    they created.
    """

    user_active: bool
    is_admin: bool
    grants: dict[str, Grant] = field(default_factory=dict)  # "resource|action|scope" -> Grant

    def grant(self, resource: str, action: Action) -> Grant | None:
        if not self.user_active:
            return None
        if self.is_admin:
            return FULL_GRANT
        return self.grants.get(f"{resource}|{action.value}|all") or self.grants.get(
            f"{resource}|{action.value}|own"
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "a": self.user_active,
            "adm": self.is_admin,
            "g": {k: v.to_json() for k, v in self.grants.items()},
        }

    @classmethod
    def from_json(cls, d: dict[str, Any]) -> PermissionMatrix:
        return cls(d["a"], d["adm"], {k: Grant.from_json(v) for k, v in d["g"].items()})


def scope_allows(scopes: frozenset[str], resource: str, action: Action) -> bool:
    """API key scopes: ``<resource>:<action>``; ``data.*`` matches any data table, ``*`` any action."""
    for s in scopes:
        res, _, act = s.partition(":")
        if act not in ("*", action.value):
            continue
        if res == resource or (res == "data.*" and resource.startswith("data.")):
            return True
    return False


@dataclass(frozen=True, slots=True)
class Principal:
    kind: Literal["user", "api_key"]
    tenant_id: uuid.UUID
    user_id: uuid.UUID  # for API keys: the creating user, whose permissions bound the key
    actor_id: uuid.UUID  # user id, or the API key row id
    session_id: uuid.UUID | None
    perms: PermissionMatrix
    scopes: frozenset[str] | None = None  # API keys only

    def grant(self, resource: str, action: Action) -> Grant | None:
        g = self.perms.grant(resource, action)
        if g is None:
            return None
        if self.scopes is not None and not scope_allows(self.scopes, resource, action):
            return None
        return g

    def require(self, resource: str, action: Action) -> Grant:
        g = self.grant(resource, action)
        if g is None:
            raise Forbidden()
        return g

    @property
    def rate_key(self) -> str:
        return f"{self.kind}:{self.actor_id}"


@dataclass(frozen=True, slots=True)
class PlatformPrincipal:
    admin_id: uuid.UUID
    session_id: uuid.UUID


async def load_matrix_from_db(session: AsyncSession, user_id: uuid.UUID) -> PermissionMatrix:
    """Must run inside a tenant session: RLS confines every row to the current tenant."""
    user = (
        await session.execute(select(AppUser.is_active, AppUser.is_tenant_admin).where(AppUser.id == user_id))
    ).one_or_none()
    if user is None:
        return PermissionMatrix(False, False, {})
    rows = (
        await session.execute(
            select(
                AppPermission.resource,
                AppPermission.action,
                AppPermission.row_scope,
                AppPermission.readable_fields,
                AppPermission.writable_fields,
            )
            .join(AppRolePermission, AppRolePermission.permission_id == AppPermission.id)
            .join(AppUserRole, AppUserRole.role_id == AppRolePermission.role_id)
            .where(AppUserRole.user_id == user_id)
        )
    ).all()
    merged: dict[str, Grant] = {}
    for resource, action, scope, readable, writable in rows:
        key = f"{resource}|{action.value}|{scope.value}"
        g = Grant(
            scope,
            None if readable is None else frozenset(readable),
            None if writable is None else frozenset(writable),
        )
        prev = merged.get(key)
        merged[key] = (
            g
            if prev is None
            else Grant(scope, _union(prev.readable, g.readable), _union(prev.writable, g.writable))
        )
    return PermissionMatrix(bool(user.is_active), bool(user.is_tenant_admin), merged)


class AuthzService:
    def __init__(self, settings: Settings, db: Database, cache: Cache) -> None:
        self._s = settings
        self._db = db
        self._cache = cache

    async def authz_version(self, tenant_id: uuid.UUID) -> tuple[int, bool]:
        """Return (version, redis_ok)."""
        key = self._cache.keys.authz_version(str(tenant_id))
        ok, cached = await safe(self._cache.get_int(key), op="authz_version.get")
        if ok and cached is not None:
            return cached, True
        async with self._db.anonymous_session() as s:
            version = int(
                (
                    await s.execute(
                        text("SELECT authz_version FROM app_tenants WHERE id = :t"), {"t": tenant_id}
                    )
                ).scalar_one()
            )
        if ok:
            ok2, current = await safe(
                self._cache.set_if_greater(key, version, AUTHZ_VERSION_TTL_S), op="authz_version.set"
            )
            if ok2 and current is not None:
                version = int(current)
        return version, ok

    async def matrix(self, tenant_id: uuid.UUID, user_id: uuid.UUID) -> PermissionMatrix:
        version, redis_ok = await self.authz_version(tenant_id)
        key = self._cache.keys.perms(str(tenant_id), str(user_id), version)
        if redis_ok:
            ok, cached = await safe(self._cache.get_json(key), op="perms.get")
            if ok and cached is not None:
                return PermissionMatrix.from_json(cached)
        async with self._db.tenant_session(tenant_id, user_id=user_id) as s:
            m = await load_matrix_from_db(s, user_id)
        if redis_ok:
            await safe(self._cache.set_json(key, m.to_json(), self._s.perm_cache_ttl_s), op="perms.set")
        return m

    @staticmethod
    async def bump_version(session: AsyncSession, tenant_id: uuid.UUID) -> int:
        """Call inside the transaction that changes authorization state."""
        return int(
            (
                await session.execute(
                    text(
                        "UPDATE app_tenants SET authz_version = authz_version + 1"
                        " WHERE id = :t RETURNING authz_version"
                    ),
                    {"t": tenant_id},
                )
            ).scalar_one()
        )

    async def publish_version(self, tenant_id: uuid.UUID, version: int) -> None:
        """Call after commit. Failure is tolerated: the version key expires within AUTHZ_VERSION_TTL_S."""
        await safe(
            self._cache.set_if_greater(
                self._cache.keys.authz_version(str(tenant_id)), version, AUTHZ_VERSION_TTL_S
            ),
            op="authz_version.publish",
        )
