"""Provider configuration (payment gateways, map providers), payment webhooks, and geocoding."""

from __future__ import annotations

import uuid
from typing import Any, Literal

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, ConfigDict, Field

from app.api.deps import audit_ctx, client_ip, container, rate_limited
from app.api.serialize import pick
from app.container import Container
from app.core.errors import PayloadTooLarge, RateLimited
from app.services.authz import Principal
from app.services.ratelimit import Bucket

router = APIRouter(tags=["integrations"])
MAX_WEBHOOK_BYTES = 256 * 1024


class ProviderIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(..., min_length=1, max_length=100)
    provider: str = Field(..., pattern=r"^[a-z]{2,20}$")
    secrets: dict[str, str] = Field(..., max_length=10)
    public_config: dict[str, Any] = Field(default_factory=dict)
    is_default: bool = False


class SecretsIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    secrets: dict[str, str] = Field(..., max_length=10)


def _cfg(row: Any) -> dict[str, Any]:
    # Never includes secrets_enc.
    return pick(row, "id", "name", "provider", "public_config", "is_default", "is_active", "secret_version", "created_at")


def _kind(path_kind: str) -> Literal["payment", "map"]:
    return "payment" if path_kind == "payment-gateways" else "map"


for _path in ("payment-gateways", "map-providers"):

    def _make(path_kind: str) -> None:
        @router.post(f"/{path_kind}", status_code=201, name=f"create_{path_kind}")
        async def create(
            body: ProviderIn, request: Request, p: Principal = Depends(rate_limited()), c: Container = Depends(container)
        ) -> dict[str, Any]:
            row = await c.providers.create(
                p, _kind(path_kind), name=body.name, provider=body.provider, secrets=body.secrets,
                public_config=body.public_config, is_default=body.is_default, ctx=audit_ctx(request, p),
            )
            return _cfg(row)

        @router.get(f"/{path_kind}", name=f"list_{path_kind}")
        async def list_(p: Principal = Depends(rate_limited()), c: Container = Depends(container)) -> dict[str, Any]:
            return {"items": [_cfg(r) for r in await c.providers.list_configs(p, _kind(path_kind))]}

        @router.put(f"/{path_kind}/{{row_id}}/secrets", status_code=204, name=f"rotate_{path_kind}")
        async def rotate(
            row_id: uuid.UUID, body: SecretsIn, request: Request, p: Principal = Depends(rate_limited()),
            c: Container = Depends(container),
        ) -> None:
            await c.providers.rotate_secrets(p, _kind(path_kind), row_id, body.secrets, audit_ctx(request, p))

    _make(_path)


@router.post("/webhooks/payments/{gateway_id}")
async def payment_webhook(gateway_id: uuid.UUID, request: Request, c: Container = Depends(container)) -> dict[str, Any]:
    """Provider callbacks. No bearer auth: the HMAC signature over the raw body is the authentication."""
    decision = await c.limiter.check(
        [Bucket("platform", c.settings.rl_platform_per_min), Bucket(f"webhook:{gateway_id}", 600),
         Bucket(f"webhook:ip:{client_ip(request)}", 1200)]
    )
    if not decision.allowed:
        raise RateLimited(headers={"Retry-After": str(decision.retry_after_s)})
    payload = await request.body()
    if len(payload) > MAX_WEBHOOK_BYTES:
        raise PayloadTooLarge()
    return await c.payments.handle_webhook(gateway_id, payload, dict(request.headers))


@router.get("/maps/geocode")
async def geocode(
    q: str = Query(..., min_length=2, max_length=300),
    language: str | None = Query(None, pattern=r"^[a-z]{2}(-[A-Z]{2})?$"),
    p: Principal = Depends(rate_limited("maps", 120)),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return {"items": await c.maps.geocode(p, q, language)}


@router.get("/maps/reverse")
async def reverse(
    lat: float = Query(..., ge=-90, le=90),
    lng: float = Query(..., ge=-180, le=180),
    language: str | None = Query(None, pattern=r"^[a-z]{2}(-[A-Z]{2})?$"),
    p: Principal = Depends(rate_limited("maps", 120)),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return {"items": await c.maps.reverse(p, lat, lng, language)}
