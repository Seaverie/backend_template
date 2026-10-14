"""Tenant user and role management with privilege-escalation guards.

Rules:
  * Only tenant admins can create or modify tenant admins.
  * A non-admin can grant (via a role) only permissions they hold themselves, at the same or narrower
    row scope, with field lists that are subsets of their own.
  * Assigning a role to a user requires being able to delegate every permission in that role.
  * Every change bumps ``app_tenants.authz_version`` in the same transaction, which invalidates all
    cached permission matrices for the tenant.
  * Deactivating a user revokes all of their sessions.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import delete, func, select, update

from app.cache.redis import Cache, safe
from app.core.config import Settings
from app.core.errors import Conflict, Forbidden, NotFound, ValidationFailed
from app.db.session import Database
from app.models import (
    Action,
    AppPermission,
    AppRole,
    AppRolePermission,
    AppUser,
    AppUserRole,
    AuthSession,
    RowScope,
)
from app.security.identifiers import IDENTIFIER_RE
from app.security.passwords import hash_password, validate_password_policy
from app.services.audit import AuditContext, record
from app.services.authz import AuthzService, Principal
from app.services.schema import SchemaService


@dataclass(frozen=True, slots=True)
class PermissionSpec:
    resource: str
    action: Action
    row_scope: RowScope
    readable_fields: list[str] | None
    writable_fields: list[str] | None


def _subset(requested: list[str] | None, held: frozenset[str] | None) -> bool:
    if held is None:
        return True
    if requested is None:
        return False
    return set(requested) <= held


def ensure_can_delegate(principal: Principal, spec: PermissionSpec) -> None:
    if principal.perms.is_admin and principal.kind == "user":
        return
    g = principal.grant(spec.resource, spec.action)
    if g is None:
        raise Forbidden(f"You cannot grant {spec.resource}:{spec.action.value}.")
    if g.row_scope == RowScope.own and spec.row_scope == RowScope.all:
        raise Forbidden("You cannot grant a wider row scope than you hold.")
    if not _subset(spec.readable_fields, g.readable) or not _subset(spec.writable_fields, g.writable):
        raise Forbidden("You cannot grant access to fields you cannot access.")


class RbacService:
    def __init__(
        self, settings: Settings, db: Database, cache: Cache, authz: AuthzService, schema: SchemaService
    ) -> None:
        self._s = settings
        self._db = db
        self._cache = cache
        self._authz = authz
        self._schema = schema

    async def _validate_spec(self, spec: PermissionSpec) -> None:
        for fl in (spec.readable_fields, spec.writable_fields):
            if fl is not None and any(not IDENTIFIER_RE.fullmatch(f) for f in fl):
                raise ValidationFailed("Invalid field name in permission.")
        if spec.resource.startswith("data."):
            meta = await self._schema.get_table(spec.resource[5:])
            if meta is None:
                raise ValidationFailed("Unknown table in permission.", details={"resource": spec.resource})
            known = set(meta.field_map)
            for fl in (spec.readable_fields, spec.writable_fields):
                if fl is not None and not set(fl) <= known:
                    raise ValidationFailed(
                        "Unknown field in permission.", details={"resource": spec.resource}
                    )
            if spec.action == Action.manage:
                raise ValidationFailed("'manage' is not a data action.")
        else:
            if spec.action not in (Action.manage, Action.read):
                raise ValidationFailed("Non-data resources support 'read' and 'manage' only.")
            if (
                spec.readable_fields is not None
                or spec.writable_fields is not None
                or spec.row_scope != RowScope.all
            ):
                raise ValidationFailed("Field lists and row scope apply only to data resources.")

    async def create_user(
        self,
        principal: Principal,
        *,
        email: str,
        password: str,
        full_name: str | None,
        is_tenant_admin: bool,
        ctx: AuditContext,
    ) -> AppUser:
        principal.require("users", Action.manage)
        if is_tenant_admin and not (principal.perms.is_admin and principal.kind == "user"):
            raise Forbidden("Only tenant admins can create tenant admins.")
        try:
            validate_password_policy(password)
        except ValueError as e:
            raise ValidationFailed(str(e)) from e
        pw = await hash_password(password)
        async with self._db.tenant_session(principal.tenant_id, user_id=principal.user_id) as s:
            exists = (
                await s.execute(select(AppUser.id).where(func.lower(AppUser.email) == email.lower()))
            ).first()
            if exists:
                raise Conflict("A user with this email already exists.")
            user = AppUser(
                tenant_id=principal.tenant_id,
                email=email.lower(),
                password_hash=pw,
                full_name=full_name,
                is_tenant_admin=is_tenant_admin,
            )
            s.add(user)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=principal.tenant_id,
                action="user.create",
                resource="users",
                resource_id=str(user.id),
                detail={"tenant_admin": is_tenant_admin},
            )
            version = await self._authz.bump_version(s, principal.tenant_id)
            await s.commit()
        await self._authz.publish_version(principal.tenant_id, version)
        return user

    async def list_users(self, principal: Principal) -> list[AppUser]:
        if principal.grant("users", Action.read) is None:
            principal.require("users", Action.manage)
        async with self._db.tenant_session(principal.tenant_id, user_id=principal.user_id) as s:
            return list((await s.execute(select(AppUser).order_by(AppUser.created_at).limit(500))).scalars())

    async def update_user(
        self,
        principal: Principal,
        user_id: uuid.UUID,
        *,
        is_active: bool | None,
        is_tenant_admin: bool | None,
        ctx: AuditContext,
    ) -> AppUser:
        principal.require("users", Action.manage)
        revoked: list[uuid.UUID] = []
        async with self._db.tenant_session(principal.tenant_id, user_id=principal.user_id) as s:
            user = (
                await s.execute(select(AppUser).where(AppUser.id == user_id).with_for_update())
            ).scalar_one_or_none()
            if user is None:
                raise NotFound()
            actor_is_admin = principal.perms.is_admin and principal.kind == "user"
            if (user.is_tenant_admin or is_tenant_admin) and not actor_is_admin:
                raise Forbidden("Only tenant admins can modify tenant admins.")
            changes: dict[str, Any] = {}
            if is_tenant_admin is not None and is_tenant_admin != user.is_tenant_admin:
                changes["is_tenant_admin"] = is_tenant_admin
                user.is_tenant_admin = is_tenant_admin
            if is_active is not None and is_active != user.is_active:
                changes["is_active"] = is_active
                user.is_active = is_active
                if not is_active:
                    revoked = list(
                        (
                            await s.execute(
                                update(AuthSession)
                                .where(AuthSession.user_id == user_id, AuthSession.revoked_at.is_(None))
                                .values(revoked_at=datetime.now(UTC), revoked_reason="user_deactivated")
                                .returning(AuthSession.id)
                            )
                        ).scalars()
                    )
            await record(
                s,
                ctx,
                tenant_id=principal.tenant_id,
                action="user.update",
                resource="users",
                resource_id=str(user_id),
                detail=changes,
            )
            version = await self._authz.bump_version(s, principal.tenant_id)
            await s.commit()
        await self._authz.publish_version(principal.tenant_id, version)
        for sid in revoked:
            await safe(
                self._cache.client.set(
                    self._cache.keys.session_revoked(str(sid)), b"1", ex=self._s.session_revocation_ttl_s
                ),
                op="session.revoke",
            )
        return user

    async def create_role(
        self,
        principal: Principal,
        *,
        name: str,
        description: str | None,
        permissions: list[PermissionSpec],
        ctx: AuditContext,
    ) -> AppRole:
        principal.require("roles", Action.manage)
        for spec in permissions:
            await self._validate_spec(spec)
            ensure_can_delegate(principal, spec)
        async with self._db.tenant_session(principal.tenant_id, user_id=principal.user_id) as s:
            if (await s.execute(select(AppRole.id).where(AppRole.name == name))).first():
                raise Conflict("A role with this name already exists.")
            role = AppRole(tenant_id=principal.tenant_id, name=name, description=description)
            s.add(role)
            await s.flush()
            for spec in permissions:
                perm = AppPermission(
                    tenant_id=principal.tenant_id,
                    resource=spec.resource,
                    action=spec.action,
                    row_scope=spec.row_scope,
                    readable_fields=spec.readable_fields,
                    writable_fields=spec.writable_fields,
                )
                s.add(perm)
                await s.flush()
                s.add(
                    AppRolePermission(tenant_id=principal.tenant_id, role_id=role.id, permission_id=perm.id)
                )
            await record(
                s,
                ctx,
                tenant_id=principal.tenant_id,
                action="role.create",
                resource="roles",
                resource_id=str(role.id),
                detail={
                    "name": name,
                    "permissions": [
                        f"{p.resource}:{p.action.value}:{p.row_scope.value}" for p in permissions
                    ],
                },
            )
            version = await self._authz.bump_version(s, principal.tenant_id)
            await s.commit()
        await self._authz.publish_version(principal.tenant_id, version)
        return role

    async def list_roles(self, principal: Principal) -> list[dict[str, Any]]:
        principal.require("roles", Action.manage)
        async with self._db.tenant_session(principal.tenant_id, user_id=principal.user_id) as s:
            roles = (await s.execute(select(AppRole).order_by(AppRole.name))).scalars().all()
            perms = (
                await s.execute(
                    select(AppRolePermission.role_id, AppPermission).join(
                        AppPermission, AppPermission.id == AppRolePermission.permission_id
                    )
                )
            ).all()
        by_role: dict[uuid.UUID, list[dict[str, Any]]] = {}
        for role_id, p in perms:
            by_role.setdefault(role_id, []).append(
                {
                    "resource": p.resource,
                    "action": p.action.value,
                    "row_scope": p.row_scope.value,
                    "readable_fields": p.readable_fields,
                    "writable_fields": p.writable_fields,
                }
            )
        return [
            {
                "id": str(r.id),
                "name": r.name,
                "description": r.description,
                "is_system": r.is_system,
                "permissions": by_role.get(r.id, []),
            }
            for r in roles
        ]

    async def set_user_roles(
        self, principal: Principal, user_id: uuid.UUID, role_ids: list[uuid.UUID], ctx: AuditContext
    ) -> None:
        principal.require("users", Action.manage)
        async with self._db.tenant_session(principal.tenant_id, user_id=principal.user_id) as s:
            user = (
                await s.execute(select(AppUser).where(AppUser.id == user_id).with_for_update())
            ).scalar_one_or_none()
            if user is None:
                raise NotFound()
            if user.is_tenant_admin and not principal.perms.is_admin:
                raise Forbidden("Only tenant admins can modify tenant admins.")
            found = set((await s.execute(select(AppRole.id).where(AppRole.id.in_(role_ids)))).scalars())
            if found != set(role_ids):
                raise ValidationFailed("Unknown role id.")  # includes other tenants' roles (hidden by RLS)
            current = set(
                (await s.execute(select(AppUserRole.role_id).where(AppUserRole.user_id == user_id))).scalars()
            )
            touched = current ^ set(role_ids)
            if touched:
                perms = (
                    (
                        await s.execute(
                            select(AppPermission)
                            .join(AppRolePermission, AppRolePermission.permission_id == AppPermission.id)
                            .where(AppRolePermission.role_id.in_(touched))
                        )
                    )
                    .scalars()
                    .all()
                )
                for p in perms:
                    ensure_can_delegate(
                        principal,
                        PermissionSpec(
                            p.resource, p.action, p.row_scope, p.readable_fields, p.writable_fields
                        ),
                    )
            await s.execute(delete(AppUserRole).where(AppUserRole.user_id == user_id))
            for rid in role_ids:
                s.add(AppUserRole(tenant_id=principal.tenant_id, user_id=user_id, role_id=rid))
            await record(
                s,
                ctx,
                tenant_id=principal.tenant_id,
                action="user.roles.set",
                resource="users",
                resource_id=str(user_id),
                detail={"role_ids": [str(r) for r in role_ids]},
            )
            version = await self._authz.bump_version(s, principal.tenant_id)
            await s.commit()
        await self._authz.publish_version(principal.tenant_id, version)
