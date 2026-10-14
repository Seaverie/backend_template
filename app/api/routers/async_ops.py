"""Webhooks, notification rules, devices, bulk imports, task status, QR codes, delivery tracking."""

from __future__ import annotations

import asyncio
import io
import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any, Literal

import segno
from fastapi import APIRouter, Depends, Path, Query, Request, UploadFile
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field

from app.api.deps import audit_ctx, container, rate_limited
from app.api.serialize import pick
from app.container import Container
from app.core.errors import PayloadTooLarge, ValidationFailed
from app.models import DevicePlatform
from app.services.authz import Principal

router = APIRouter(tags=["async"])


# -- webhooks -------------------------------------------------------------------------------------------


class WebhookIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: str = Field(..., min_length=10, max_length=2048)
    event_types: list[str] = Field(..., min_length=1, max_length=50)
    description: str | None = Field(None, max_length=200)


class WebhookPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: str | None = Field(None, min_length=10, max_length=2048)
    event_types: list[str] | None = Field(None, min_length=1, max_length=50)
    is_active: bool | None = None


def _hook(h: Any) -> dict[str, Any]:
    return pick(
        h,
        "id",
        "url",
        "description",
        "event_types",
        "is_active",
        "consecutive_failures",
        "disabled_reason",
        "secret_version",
        "created_at",
    )


@router.post("/webhooks", status_code=201)
async def create_webhook(
    body: WebhookIn,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    hook, secret = await c.webhooks.create(
        p, url=body.url, event_types=body.event_types, description=body.description, ctx=audit_ctx(request, p)
    )
    return {**_hook(hook), "signing_secret": secret}  # shown once


@router.get("/webhooks")
async def list_webhooks(
    p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    return {"items": [_hook(h) for h in await c.webhooks.list_hooks(p)]}


@router.patch("/webhooks/{webhook_id}")
async def patch_webhook(
    webhook_id: uuid.UUID,
    body: WebhookPatch,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    h = await c.webhooks.update(
        p,
        webhook_id,
        url=body.url,
        event_types=body.event_types,
        is_active=body.is_active,
        ctx=audit_ctx(request, p),
    )
    return _hook(h)


@router.post("/webhooks/{webhook_id}/rotate-secret")
async def rotate_webhook_secret(
    webhook_id: uuid.UUID,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, str]:
    return {"signing_secret": await c.webhooks.rotate_secret(p, webhook_id, audit_ctx(request, p))}


@router.delete("/webhooks/{webhook_id}", status_code=204)
async def delete_webhook(
    webhook_id: uuid.UUID,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> None:
    await c.webhooks.delete(p, webhook_id, audit_ctx(request, p))


@router.get("/webhooks/{webhook_id}/deliveries")
async def webhook_deliveries(
    webhook_id: uuid.UUID,
    limit: int = Query(50, ge=1, le=200),
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    rows = await c.webhooks.deliveries(p, webhook_id, limit)
    return {
        "items": [
            pick(
                d,
                "id",
                "event_id",
                "event_type",
                "status",
                "attempts",
                "next_attempt_at",
                "last_status_code",
                "last_error",
                "delivered_at",
                "created_at",
            )
            for d in rows
        ]
    }


@router.post("/webhook-deliveries/{delivery_id}/redeliver", status_code=202)
async def redeliver(
    delivery_id: uuid.UUID,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> None:
    await c.webhooks.redeliver(p, delivery_id, audit_ctx(request, p))


# -- notification rules & devices -------------------------------------------------------------------------


class RuleIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(..., min_length=1, max_length=100)
    event_type: str = Field(..., max_length=64)
    target_role_ids: list[uuid.UUID] = Field(default_factory=list, max_length=50)
    include_tenant_admins: bool = False
    title_template: str = Field(..., min_length=1, max_length=200)
    body_template: str = Field(..., min_length=1, max_length=1000)


class DeviceIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    platform: DevicePlatform
    token: str = Field(..., min_length=20, max_length=4096, pattern=r"^[A-Za-z0-9:_\-.]+$")


def _rule(r: Any) -> dict[str, Any]:
    return pick(
        r,
        "id",
        "name",
        "event_type",
        "target_role_ids",
        "include_tenant_admins",
        "title_template",
        "body_template",
        "is_active",
    )


@router.post("/notification-rules", status_code=201)
async def create_rule(
    body: RuleIn, request: Request, p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    r = await c.notifications.create_rule(p, ctx=audit_ctx(request, p), **body.model_dump())
    return _rule(r)


@router.get("/notification-rules")
async def list_rules(
    p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    return {"items": [_rule(r) for r in await c.notifications.list_rules(p)]}


@router.delete("/notification-rules/{rule_id}", status_code=204)
async def delete_rule(
    rule_id: uuid.UUID,
    request: Request,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> None:
    await c.notifications.delete_rule(p, rule_id, audit_ctx(request, p))


@router.post("/devices", status_code=201)
async def register_device(
    body: DeviceIn, p: Principal = Depends(rate_limited("devices", 30)), c: Container = Depends(container)
) -> dict[str, Any]:
    d = await c.notifications.register_device(p, body.platform, body.token)
    return pick(d, "id", "platform", "is_active", "last_seen_at")  # never echoes the token


@router.get("/devices")
async def list_devices(
    p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    return {
        "items": [
            pick(d, "id", "platform", "is_active", "last_seen_at")
            for d in await c.notifications.list_devices(p)
        ]
    }


@router.delete("/devices/{device_id}", status_code=204)
async def remove_device(
    device_id: uuid.UUID, p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> None:
    await c.notifications.remove_device(p, device_id)


# -- imports & tasks ------------------------------------------------------------------------------------


def _task(t: Any) -> dict[str, Any]:
    return pick(
        t,
        "id",
        "kind",
        "status",
        "progress",
        "result",
        "error",
        "attempts",
        "created_at",
        "started_at",
        "finished_at",
    )


@router.post("/import/excel/{table_name}", status_code=202)
async def import_file(
    request: Request,
    file: UploadFile,
    table_name: str = Path(..., pattern=r"^[a-z][a-z0-9_]{0,62}$"),
    mode: Literal["all_or_nothing", "skip_invalid"] = Query("all_or_nothing"),
    p: Principal = Depends(rate_limited("imports", 10)),
    c: Container = Depends(container),
) -> Response:
    """Upload a CSV or XLSX file for background import. Poll ``GET /tasks/{id}`` for the outcome."""
    data = await file.read(c.settings.import_max_bytes + 1)
    if len(data) > c.settings.import_max_bytes:
        raise PayloadTooLarge()
    task, created = await c.imports.submit(
        p,
        table_name,
        data=data,
        filename=file.filename or "upload",
        mode=mode,
        idempotency_key=request.headers.get("Idempotency-Key"),
        ctx=audit_ctx(request, p),
    )
    if created:
        from app.workers.tasks import enqueue

        await asyncio.to_thread(enqueue, "import", p.tenant_id, task.id)
    return JSONResponse(_task(task), status_code=202 if created else 200)


@router.get("/tasks/{task_id}")
async def get_task(
    task_id: uuid.UUID, p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    return _task(await c.imports.get_task(p, task_id))


# -- QR codes ---------------------------------------------------------------------------------------


@router.get("/qrcode/generate")
async def qrcode(
    data: str = Query(..., min_length=1, max_length=1024),
    format: Literal["png", "svg"] = Query("png"),
    scale: int = Query(8, ge=1, le=20),
    border: int = Query(4, ge=0, le=10),
    error: Literal["L", "M", "Q", "H"] = Query("M"),
    _: Principal = Depends(rate_limited("qrcode", 60)),
) -> Response:
    try:
        qr = segno.make(data, error=error.lower(), micro=False)
    except (ValueError, segno.DataOverflowError) as e:
        raise ValidationFailed("Data does not fit in a QR code at this error-correction level.") from e
    buf = io.BytesIO()
    if format == "png":
        qr.save(buf, kind="png", scale=scale, border=border)
        media = "image/png"
    else:
        qr.save(buf, kind="svg", scale=scale, border=border, xmldecl=False, svgns=True, nl=False)
        media = "image/svg+xml"
    return Response(buf.getvalue(), media_type=media, headers={"Content-Disposition": "inline"})


# -- delivery tracking ------------------------------------------------------------------------------------


class PingIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    lat: Decimal = Field(..., ge=-90, le=90, max_digits=9, decimal_places=6)
    lng: Decimal = Field(..., ge=-180, le=180, max_digits=9, decimal_places=6)
    accuracy_m: int | None = Field(None, ge=0, le=100_000)
    recorded_at: datetime


@router.post("/orders/{order_id}/delivery-pings", status_code=201)
async def record_ping(
    order_id: uuid.UUID,
    body: PingIn,
    p: Principal = Depends(rate_limited("pings", 120)),
    c: Container = Depends(container),
) -> dict[str, Any]:
    ping = await c.deliveries.record_ping(
        p, order_id, lat=body.lat, lng=body.lng, accuracy_m=body.accuracy_m, recorded_at=body.recorded_at
    )
    return pick(ping, "id", "order_id", "lat", "lng", "accuracy_m", "recorded_at")


@router.get("/orders/{order_id}/delivery-pings")
async def list_pings(
    order_id: uuid.UUID,
    since: datetime | None = None,
    limit: int = Query(100, ge=1, le=1000),
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    rows = await c.deliveries.pings(p, order_id, since=since, limit=limit)
    return {"items": [pick(x, "lat", "lng", "accuracy_m", "recorded_at", "user_id") for x in rows]}
