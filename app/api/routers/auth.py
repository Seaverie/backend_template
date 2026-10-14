from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, ConfigDict, EmailStr, Field

from app.api.deps import audit_ctx, client_ip, container, login_rate_limit, rate_limited
from app.container import Container
from app.core.errors import Forbidden
from app.core.logging import request_id_var
from app.services.authz import Principal

router = APIRouter(prefix="/auth", tags=["auth"])


class LoginIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    tenant: str = Field(..., pattern=r"^[a-z0-9][a-z0-9-]{1,62}$")
    email: EmailStr
    password: str = Field(..., min_length=1, max_length=256)


class RefreshIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    refresh_token: str = Field(..., min_length=20, max_length=4096)


class TokenOut(BaseModel):
    access_token: str
    refresh_token: str | None
    token_type: str
    expires_in: int


@router.post("/login", response_model=TokenOut)
async def login(body: LoginIn, request: Request, c: Container = Depends(container)) -> Any:
    await login_rate_limit(request, f"{body.tenant}:{body.email}", c)
    pair = await c.auth.login(
        tenant_slug=body.tenant,
        email=str(body.email),
        password=body.password,
        ip=client_ip(request),
        user_agent=request.headers.get("User-Agent"),
        request_id=request_id_var.get(),
    )
    return _pair(pair)


def _pair(pair: Any) -> dict[str, Any]:
    return {
        "access_token": pair.access_token,
        "refresh_token": pair.refresh_token,
        "token_type": pair.token_type,
        "expires_in": pair.expires_in,
    }


@router.post("/refresh", response_model=TokenOut)
async def refresh(body: RefreshIn, request: Request, c: Container = Depends(container)) -> Any:
    await login_rate_limit(request, f"refresh:{client_ip(request)}", c)
    return _pair(
        await c.auth.refresh(body.refresh_token, ip=client_ip(request), request_id=request_id_var.get())
    )


@router.post("/logout", status_code=204)
async def logout(
    request: Request, p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> None:
    if p.session_id is None:
        raise Forbidden("API keys have no session; revoke the key instead.")
    await c.auth.logout(tenant_id=p.tenant_id, user_id=p.user_id, sid=p.session_id, ctx=audit_ctx(request, p))


@router.get("/me")
async def me(p: Principal = Depends(rate_limited())) -> dict[str, Any]:
    return {
        "kind": p.kind,
        "tenant_id": str(p.tenant_id),
        "user_id": str(p.user_id),
        "is_tenant_admin": p.perms.is_admin,
        "scopes": sorted(p.scopes) if p.scopes is not None else None,
        "permissions": sorted(p.perms.grants) if not p.perms.is_admin else ["*"],
    }
