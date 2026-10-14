"""Tenant webhooks: configuration, signed delivery, durable retries.

Each delivery is a row (``webhook_deliveries``) with its own retry schedule, so retries survive worker
restarts and are visible to the tenant. Requests carry:

* ``X-TenantCore-Event-Id`` (receivers deduplicate on it; delivery is at-least-once)
* ``X-TenantCore-Event-Type``
* ``X-TenantCore-Signature: t=<unix>,v1=<hex HMAC-SHA256 of "<t>.<body>">``. Verify it, and reject
  timestamps older than 5 minutes.

Destinations are tenant-chosen, so every request is SSRF-checked when the webhook is created *and* on
every send: HTTPS, an allowed port, public addresses only, the validated IP pinned for the connection,
no redirects.
"""

from __future__ import annotations

import json
import random
import secrets
import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import select, update

from app.core.config import Settings
from app.core.errors import NotFound, ValidationFailed
from app.core.logging import get_logger
from app.db.session import Database
from app.integrations.http import OutboundBlocked, OutboundError, OutboundPolicy, SafeHttpClient
from app.integrations.payments import sign_payload
from app.models import Action, AppWebhook, DeliveryStatus, WebhookDelivery
from app.security.crypto import Envelope
from app.services.audit import AuditContext, record
from app.services.authz import Principal
from app.services.outbox import EVENT_TYPES

log = get_logger("webhooks")
BACKOFF_BASE_S = 30
BACKOFF_CAP_S = 6 * 3600


def backoff_s(attempt: int) -> float:
    """Exponential backoff with jitter: base * 2^(n-1), capped, multiplied by U(0.5, 1.5)."""
    raw = min(BACKOFF_CAP_S, BACKOFF_BASE_S * 2 ** max(0, attempt - 1))
    return float(raw * random.uniform(0.5, 1.5))  # noqa: S311 - jitter, not security


def _ctx(tenant_id: uuid.UUID, webhook_id: uuid.UUID) -> dict[str, str]:
    return {"tenant": str(tenant_id), "purpose": "webhook_secret", "id": str(webhook_id)}


def _validate_events(event_types: list[str]) -> list[str]:
    unknown = sorted(set(event_types) - EVENT_TYPES)
    if unknown or not event_types:
        raise ValidationFailed(
            "Unknown or empty event types.", details={"unknown": unknown, "allowed": sorted(EVENT_TYPES)}
        )
    return sorted(set(event_types))


class WebhookService:
    def __init__(self, settings: Settings, db: Database, envelope: Envelope, http: SafeHttpClient) -> None:
        self._s = settings
        self._db = db
        self._env = envelope
        self.http = http
        self.policy = OutboundPolicy(None, frozenset(settings.webhook_allowed_ports))

    async def _check_url(self, url: str) -> str:
        if len(url) > 2048:
            raise ValidationFailed("URL too long.")
        try:
            await self.http.validate_destination(url, policy=self.policy)
        except (OutboundBlocked, OutboundError) as e:
            raise ValidationFailed(f"Webhook URL rejected: {e}") from e
        return url

    # -- management -----------------------------------------------------------------------------

    async def create(
        self, p: Principal, *, url: str, event_types: list[str], description: str | None, ctx: AuditContext
    ) -> tuple[AppWebhook, str]:
        p.require("webhooks", Action.manage)
        url = await self._check_url(url)
        events = _validate_events(event_types)
        secret = "whsec_" + secrets.token_urlsafe(32)
        wid = uuid.uuid4()
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            hook = AppWebhook(
                id=wid,
                tenant_id=p.tenant_id,
                url=url,
                event_types=events,
                description=description,
                secret_enc=await self._env.encrypt_str(secret, _ctx(p.tenant_id, wid)),
            )
            s.add(hook)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="webhook.create",
                resource="webhooks",
                resource_id=str(wid),
                detail={"url": url, "events": events},
            )
            await s.commit()
        return hook, secret

    async def update(
        self,
        p: Principal,
        webhook_id: uuid.UUID,
        *,
        url: str | None,
        event_types: list[str] | None,
        is_active: bool | None,
        ctx: AuditContext,
    ) -> AppWebhook:
        p.require("webhooks", Action.manage)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            hook = (
                await s.execute(select(AppWebhook).where(AppWebhook.id == webhook_id).with_for_update())
            ).scalar_one_or_none()
            if hook is None:
                raise NotFound()
            if url is not None:
                hook.url = await self._check_url(url)
            if event_types is not None:
                hook.event_types = _validate_events(event_types)
            if is_active is not None:
                hook.is_active = is_active
                if is_active:
                    hook.consecutive_failures, hook.disabled_reason = 0, None
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="webhook.update",
                resource="webhooks",
                resource_id=str(webhook_id),
            )
            await s.commit()
        return hook

    async def rotate_secret(self, p: Principal, webhook_id: uuid.UUID, ctx: AuditContext) -> str:
        p.require("webhooks", Action.manage)
        secret = "whsec_" + secrets.token_urlsafe(32)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            hook = (
                await s.execute(select(AppWebhook).where(AppWebhook.id == webhook_id).with_for_update())
            ).scalar_one_or_none()
            if hook is None:
                raise NotFound()
            hook.secret_enc = await self._env.encrypt_str(secret, _ctx(p.tenant_id, webhook_id))
            hook.secret_version += 1
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="webhook.rotate_secret",
                resource="webhooks",
                resource_id=str(webhook_id),
            )
            await s.commit()
        return secret

    async def delete(self, p: Principal, webhook_id: uuid.UUID, ctx: AuditContext) -> None:
        p.require("webhooks", Action.manage)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            hook = (
                await s.execute(select(AppWebhook).where(AppWebhook.id == webhook_id))
            ).scalar_one_or_none()
            if hook is None:
                raise NotFound()
            await s.delete(hook)
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="webhook.delete",
                resource="webhooks",
                resource_id=str(webhook_id),
            )
            await s.commit()

    async def list_hooks(self, p: Principal) -> list[AppWebhook]:
        p.require("webhooks", Action.read)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            return list((await s.execute(select(AppWebhook).order_by(AppWebhook.created_at))).scalars())

    async def deliveries(self, p: Principal, webhook_id: uuid.UUID, limit: int) -> list[WebhookDelivery]:
        p.require("webhooks", Action.read)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            return list(
                (
                    await s.execute(
                        select(WebhookDelivery)
                        .where(WebhookDelivery.webhook_id == webhook_id)
                        .order_by(WebhookDelivery.created_at.desc())
                        .limit(limit)
                    )
                ).scalars()
            )

    async def redeliver(self, p: Principal, delivery_id: uuid.UUID, ctx: AuditContext) -> None:
        p.require("webhooks", Action.manage)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            res = await s.execute(
                update(WebhookDelivery)
                .where(WebhookDelivery.id == delivery_id)
                .values(
                    status=DeliveryStatus.pending,
                    attempts=0,
                    next_attempt_at=datetime.now(UTC),
                    last_error=None,
                )
            )
            if not res.rowcount:  # type: ignore[attr-defined]
                raise NotFound()
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="webhook.redeliver",
                resource="webhooks",
                resource_id=str(delivery_id),
            )
            await s.commit()

    # -- delivery (worker side) -------------------------------------------------------------------

    async def deliver(self, tenant_id: uuid.UUID, delivery_id: uuid.UUID) -> DeliveryStatus | None:
        """Send one claimed delivery and record the outcome. Returns the new status."""
        async with self._db.tenant_session(tenant_id) as s:
            d = (
                await s.execute(select(WebhookDelivery).where(WebhookDelivery.id == delivery_id))
            ).scalar_one_or_none()
            if d is None or d.status != DeliveryStatus.pending:
                return None
            hook = (await s.execute(select(AppWebhook).where(AppWebhook.id == d.webhook_id))).scalar_one()
            hook_active, url, secret_enc = hook.is_active, hook.url, hook.secret_enc
            body = json.dumps(
                {
                    "id": str(d.event_id),
                    "type": d.event_type,
                    "tenant_id": str(tenant_id),
                    "created_at": d.created_at.isoformat(),
                    "data": d.payload,
                },
                separators=(",", ":"),
                sort_keys=True,
            ).encode()
            attempts, event_id, event_type = d.attempts, d.event_id, d.event_type
        if not hook_active:
            return await self._finish(tenant_id, delivery_id, DeliveryStatus.dead, None, "webhook disabled")
        secret = await self._env.decrypt_str(secret_enc, _ctx(tenant_id, hook.id))
        headers = {
            "Content-Type": "application/json",
            "User-Agent": "TenantCore-Webhooks/1",
            "X-TenantCore-Event-Id": str(event_id),
            "X-TenantCore-Event-Type": event_type,
            "X-TenantCore-Signature": sign_payload(body, secret),
        }
        code: int | None = None
        error: str | None = None
        try:
            resp = await self.http.request("POST", url, policy=self.policy, content=body, headers=headers)
            code = resp.status_code
        except OutboundBlocked as e:
            return await self._finish(
                tenant_id, delivery_id, DeliveryStatus.dead, None, f"blocked: {e}", disable=True
            )
        except OutboundError as e:
            error = str(e)
        if code is not None and 200 <= code < 300:
            return await self._finish(tenant_id, delivery_id, DeliveryStatus.succeeded, code, None)
        if code == 410:  # receiver says the endpoint is gone
            return await self._finish(
                tenant_id, delivery_id, DeliveryStatus.dead, code, "410 Gone", disable=True
            )
        if attempts >= self._s.webhook_max_attempts:
            return await self._finish(
                tenant_id, delivery_id, DeliveryStatus.dead, code, error or f"HTTP {code}"
            )
        return await self._finish(
            tenant_id,
            delivery_id,
            DeliveryStatus.pending,
            code,
            error or f"HTTP {code}",
            retry_in=backoff_s(attempts),
        )

    async def _finish(
        self,
        tenant_id: uuid.UUID,
        delivery_id: uuid.UUID,
        status: DeliveryStatus,
        code: int | None,
        error: str | None,
        *,
        retry_in: float | None = None,
        disable: bool = False,
    ) -> DeliveryStatus:
        now = datetime.now(UTC)
        async with self._db.tenant_session(tenant_id) as s:
            d = (
                await s.execute(
                    select(WebhookDelivery).where(WebhookDelivery.id == delivery_id).with_for_update()
                )
            ).scalar_one()
            hook = (
                await s.execute(select(AppWebhook).where(AppWebhook.id == d.webhook_id).with_for_update())
            ).scalar_one()
            d.status, d.last_status_code, d.last_error = status, code, (error or None) and error[:500]
            if status == DeliveryStatus.succeeded:
                d.delivered_at = now
                hook.consecutive_failures = 0
            else:
                hook.consecutive_failures += 1
                if retry_in is not None:
                    d.next_attempt_at = now + timedelta(seconds=retry_in)
                if disable or hook.consecutive_failures >= self._s.webhook_disable_after_failures:
                    hook.is_active = False
                    hook.disabled_reason = (error or "too many consecutive failures")[:200]
            await s.commit()
        if status == DeliveryStatus.dead:
            log.warning("webhook.dead", tenant=str(tenant_id), delivery=str(delivery_id), error=error)
        return status
