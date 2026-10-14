"""Tenant-scoped administration: users, roles, tenant settings, audit log."""

from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, ConfigDict, EmailStr, Field
from sqlalchemy import select

from app.api.deps import audit_ctx, container, rate_limited
from app.container import Container
from app.core.errors import Forbidden, ValidationFailed
from app.models import Action, AppUser, RowScope, SystemAuditLog
from app.models.rbac import RESOURCE_REGEX
from app.services.authz import Principal
from app.services.rbac import PermissionSpec

router = APIRouter(tags=["tenant-admin"])


class UserCreateIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: EmailStr
    password: str = Field(..., min_length=12, max_length=256)
    full_name: str | None = Field(None, max_length=200)
    is_tenant_admin: bool = False


class UserPatchIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    is_active: bool | None = None
    is_tenant_admin: bool | None = None


class PermissionIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    resource: str = Field(..., pattern=RESOURCE_REGEX)
    action: Action
    row_scope: RowScope = RowScope.all
    readable_fields: list[str] | None = Field(None, max_length=100)
    writable_fields: list[str] | None = Field(None, max_length=100)


class RoleCreateIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(..., pattern=r"^[A-Za-z0-9 _.-]{1,100}$")
    description: str | None = Field(None, max_length=500)
    permissions: list[PermissionIn] = Field(..., min_length=1, max_length=200)


class UserRolesIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role_ids: list[uuid.UUID] = Field(..., max_length=50)


class RateLimitIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    rate_limit_per_min: int = Field(..., ge=1)


def _user_out(u: AppUser) -> dict[str, Any]:
    return {
        "id": str(u.id),
        "email": u.email,
        "full_name": u.full_name,
        "is_active": u.is_active,
        "is_tenant_admin": u.is_tenant_admin,
    }


@router.post("/users", status_code=201)
async def create_user(
    body: UserCreateIn,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    u = await c.rbac.create_user(
        p,
        email=str(body.email),
        password=body.password,
        full_name=body.full_name,
        is_tenant_admin=body.is_tenant_admin,
        ctx=audit_ctx(request, p),
    )
    return _user_out(u)


@router.get("/users")
async def list_users(
    p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    return {"items": [_user_out(u) for u in await c.rbac.list_users(p)]}


@router.patch("/users/{user_id}")
async def patch_user(
    user_id: uuid.UUID,
    body: UserPatchIn,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    u = await c.rbac.update_user(
        p, user_id, is_active=body.is_active, is_tenant_admin=body.is_tenant_admin, ctx=audit_ctx(request, p)
    )
    return _user_out(u)


@router.put("/users/{user_id}/roles", status_code=204)
async def set_user_roles(
    user_id: uuid.UUID,
    body: UserRolesIn,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> None:
    await c.rbac.set_user_roles(p, user_id, list(dict.fromkeys(body.role_ids)), audit_ctx(request, p))


@router.post("/roles", status_code=201)
async def create_role(
    body: RoleCreateIn,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    specs = [
        PermissionSpec(x.resource, x.action, x.row_scope, x.readable_fields, x.writable_fields)
        for x in body.permissions
    ]
    role = await c.rbac.create_role(
        p, name=body.name, description=body.description, permissions=specs, ctx=audit_ctx(request, p)
    )
    return {"id": str(role.id), "name": role.name}


@router.get("/roles")
async def list_roles(
    p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    return {"items": await c.rbac.list_roles(p)}


@router.put("/tenant/rate-limit")
async def set_rate_limit(
    body: RateLimitIn,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    if not (p.perms.is_admin and p.kind == "user"):
        raise Forbidden("Only tenant admins can change tenant rate limits.")
    try:
        value = await c.tenants.set_own_rate_limit(
            tenant_id=p.tenant_id,
            user_id=p.user_id,
            rate_limit_per_min=body.rate_limit_per_min,
            ctx=audit_ctx(request, p),
        )
    except ValueError as e:
        raise ValidationFailed(str(e)) from e
    return {"rate_limit_per_min": value, "platform_max_per_min": c.settings.rl_tenant_max_per_min}


@router.get("/audit-logs")
async def audit_logs(
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0, le=10_000),
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    p.require("audit_logs", Action.read)
    async with c.db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
        rows = (
            (
                await s.execute(
                    select(SystemAuditLog)
                    .order_by(SystemAuditLog.created_at.desc())
                    .limit(limit)
                    .offset(offset)
                )
            )
            .scalars()
            .all()
        )
    return {
        "items": [
            {
                "id": str(r.id),
                "actor_type": r.actor_type,
                "actor_id": str(r.actor_id) if r.actor_id else None,
                "action": r.action,
                "resource": r.resource,
                "resource_id": r.resource_id,
                "request_id": r.request_id,
                "detail": r.detail,
                "created_at": r.created_at.isoformat(),
            }
            for r in rows
        ]
    }
