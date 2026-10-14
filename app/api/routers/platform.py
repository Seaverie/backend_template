"""Super-admin endpoints: platform auth, tenant provisioning, and dynamic schema (DDL)."""

from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, ConfigDict, EmailStr, Field
from sqlalchemy import select

from app.api.deps import (
    client_ip,
    container,
    login_rate_limit,
    platform_admin,
    platform_audit_ctx,
    platform_claims,
)
from app.container import Container
from app.core.errors import NotFound, ValidationFailed
from app.core.logging import request_id_var
from app.dynamic.schema import TableSpec
from app.models import AppTenant, TenantStatus
from app.security.tokens import TokenClaims
from app.services.authz import PlatformPrincipal

router = APIRouter(tags=["platform"])


class PlatformLoginIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: EmailStr
    password: str = Field(..., min_length=1, max_length=256)
    otp: str | None = Field(None, pattern=r"^[0-9]{6}$")


class MfaConfirmIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Field(..., pattern=r"^[0-9]{6}$")


class TenantCreateIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    slug: str = Field(..., pattern=r"^[a-z0-9][a-z0-9-]{1,62}$")
    name: str = Field(..., min_length=1, max_length=200)
    owner_email: EmailStr
    owner_password: str = Field(..., min_length=12, max_length=256)
    rate_limit_per_min: int | None = Field(None, ge=1)


class TenantPatchIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: TenantStatus | None = None
    rate_limit_per_min: int | None = Field(None, ge=1)


def _tenant_out(t: AppTenant) -> dict[str, Any]:
    return {
        "id": str(t.id),
        "slug": t.slug,
        "name": t.name,
        "status": t.status.value,
        "isolation_mode": t.isolation_mode.value,
        "rate_limit_per_min": t.rate_limit_per_min,
    }


@router.post("/platform/auth/login")
async def platform_login(
    body: PlatformLoginIn, request: Request, c: Container = Depends(container)
) -> dict[str, Any]:
    await login_rate_limit(request, f"platform:{body.email}", c)
    pair = await c.auth.platform_login(
        email=str(body.email),
        password=body.password,
        otp=body.otp,
        ip=client_ip(request),
        request_id=request_id_var.get(),
    )
    return {"access_token": pair.access_token, "token_type": pair.token_type, "expires_in": pair.expires_in}


@router.post("/platform/auth/logout", status_code=204)
async def platform_logout(
    claims: TokenClaims = Depends(platform_claims), c: Container = Depends(container)
) -> None:
    await c.auth.platform_logout(claims)


@router.post("/platform/auth/mfa/setup")
async def mfa_setup(
    claims: TokenClaims = Depends(platform_claims), c: Container = Depends(container)
) -> dict[str, str]:
    # The secret is returned once so it can be added to an authenticator app; it is stored encrypted.
    return await c.auth.mfa_setup(claims)


@router.post("/platform/auth/mfa/confirm")
async def mfa_confirm(
    body: MfaConfirmIn,
    request: Request,
    claims: TokenClaims = Depends(platform_claims),
    c: Container = Depends(container),
) -> dict[str, Any]:
    pair = await c.auth.mfa_confirm(claims, body.code, ip=client_ip(request), request_id=request_id_var.get())
    return {"access_token": pair.access_token, "token_type": pair.token_type, "expires_in": pair.expires_in}


@router.post("/platform/tenants", status_code=201)
async def create_tenant(
    body: TenantCreateIn,
    request: Request,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    try:
        tenant, owner = await c.tenants.provision(
            admin_id=admin.admin_id,
            slug=body.slug,
            name=body.name,
            owner_email=str(body.owner_email),
            owner_password=body.owner_password,
            rate_limit_per_min=body.rate_limit_per_min,
            ctx=platform_audit_ctx(request, admin),
        )
    except ValueError as e:
        raise ValidationFailed(str(e)) from e
    return {"tenant": _tenant_out(tenant), "owner_user_id": str(owner.id)}


@router.get("/platform/tenants")
async def list_tenants(
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0, le=100_000),
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    async with c.db.platform_session(admin.admin_id) as s:
        rows = (
            await s.execute(select(AppTenant).order_by(AppTenant.created_at).limit(limit).offset(offset))
        ).scalars()
        return {"items": [_tenant_out(t) for t in rows]}


@router.patch("/platform/tenants/{tenant_id}")
async def patch_tenant(
    tenant_id: uuid.UUID,
    body: TenantPatchIn,
    request: Request,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    try:
        t = await c.tenants.update(
            admin_id=admin.admin_id,
            tenant_id=tenant_id,
            status=body.status,
            rate_limit_per_min=body.rate_limit_per_min,
            ctx=platform_audit_ctx(request, admin),
        )
    except ValueError as e:
        raise ValidationFailed(str(e)) from e
    if t is None:
        raise NotFound()
    return _tenant_out(t)


@router.post("/system/tables", status_code=201)
async def create_table(
    spec: TableSpec,
    request: Request,
    dry_run: bool = Query(False),
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return await c.schema.create_table(
        spec, admin_id=admin.admin_id, dry_run=dry_run, ctx=platform_audit_ctx(request, admin)
    )


@router.get("/system/tables")
async def list_tables(
    _: PlatformPrincipal = Depends(platform_admin), c: Container = Depends(container)
) -> dict[str, Any]:
    tables = await c.schema.list_tables()
    return {
        "items": [
            {"id": str(t.id), "name": t.name, "crud_mode": t.crud_mode.value, "version": t.version}
            for t in tables
        ]
    }
