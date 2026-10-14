from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, ConfigDict, Field

from app.api.deps import audit_ctx, container, rate_limited
from app.container import Container
from app.models import TenantApiKey
from app.services.authz import Principal

router = APIRouter(prefix="/api-keys", tags=["api-keys"])


class ApiKeyCreateIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(..., min_length=1, max_length=100)
    scopes: list[str] = Field(..., min_length=1, max_length=50)
    expires_in_days: int | None = Field(None, ge=1, le=730)


class RotateIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    grace_seconds: int = Field(3600, ge=0, le=86_400)


def _out(k: TenantApiKey) -> dict[str, Any]:
    return {
        "id": str(k.id),
        "key_id": k.key_id,
        "name": k.name,
        "scopes": list(k.scopes),
        "created_at": k.created_at.isoformat() if k.created_at else None,
        "expires_at": k.expires_at.isoformat() if k.expires_at else None,
        "revoked_at": k.revoked_at.isoformat() if k.revoked_at else None,
        "rotated_from_id": str(k.rotated_from_id) if k.rotated_from_id else None,
    }


@router.post("", status_code=201)
async def create_key(
    body: ApiKeyCreateIn,
    request: Request,
    p: Principal = Depends(rate_limited("api_keys.write", 30)),
    c: Container = Depends(container),
) -> dict[str, Any]:
    raw, row = await c.api_keys.create(
        p, name=body.name, scopes=body.scopes, expires_in_days=body.expires_in_days, ctx=audit_ctx(request, p)
    )
    # The secret is returned exactly once and never stored or logged.
    return {**_out(row), "api_key": raw}


@router.get("")
async def list_keys(
    p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    return {"items": [_out(k) for k in await c.api_keys.list_keys(p)]}


@router.post("/{key_row_id}/revoke", status_code=204)
async def revoke_key(
    key_row_id: uuid.UUID,
    request: Request,
    p: Principal = Depends(rate_limited("api_keys.write", 30)),
    c: Container = Depends(container),
) -> None:
    await c.api_keys.revoke(p, key_row_id, audit_ctx(request, p))


@router.post("/{key_row_id}/rotate", status_code=201)
async def rotate_key(
    key_row_id: uuid.UUID,
    body: RotateIn,
    request: Request,
    p: Principal = Depends(rate_limited("api_keys.write", 30)),
    c: Container = Depends(container),
) -> dict[str, Any]:
    raw, row = await c.api_keys.rotate(p, key_row_id, grace_s=body.grace_seconds, ctx=audit_ctx(request, p))
    return {**_out(row), "api_key": raw}
