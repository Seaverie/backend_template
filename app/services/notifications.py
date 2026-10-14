"""Notification rules, device registration, and FCM delivery."""

from __future__ import annotations

import hashlib
import string
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import select

from app.core.config import Settings
from app.core.errors import Forbidden, NotFound, ValidationFailed
from app.core.logging import get_logger
from app.db.session import Database
from app.integrations.fcm import FcmError, FcmSender, PushMessage
from app.integrations.http import OutboundBlocked, OutboundError, SafeHttpClient
from app.models import (
    Action,
    AppNotificationRule,
    AppRole,
    DeliveryStatus,
    DevicePlatform,
    NotificationDelivery,
    SystemUserDevice,
)
from app.security.crypto import Envelope
from app.services.audit import AuditContext, record
from app.services.authz import Principal
from app.services.outbox import EVENT_TYPES
from app.services.webhooks import backoff_s

log = get_logger("notifications")
MAX_PLACEHOLDER_LEN = 200


def render(template: str, payload: dict[str, Any], limit: int) -> str:
    """``$name`` placeholders from top-level scalar payload fields only. No attribute access, no
    format specs, no code; unknown placeholders are left as-is."""
    values = {
        k: str(v)[:MAX_PLACEHOLDER_LEN]
        for k, v in payload.items()
        if isinstance(v, str | int | float | bool) and k.isidentifier()
    }
    return string.Template(template).safe_substitute(values)[:limit]


def _device_ctx(tenant_id: uuid.UUID, device_id: uuid.UUID) -> dict[str, str]:
    return {"tenant": str(tenant_id), "purpose": "push_token", "id": str(device_id)}


class NotificationService:
    def __init__(self, settings: Settings, db: Database, envelope: Envelope, http: SafeHttpClient) -> None:
        self._s = settings
        self._db = db
        self._env = envelope
        self.http = http
        self._sender: FcmSender | None = None

    def sender(self) -> FcmSender | None:
        if self._sender is None and self._s.fcm_service_account_json is not None:
            self._sender = FcmSender(
                self.http, self._s.fcm_service_account_json.get_secret_value(), self._s.fcm_project_id
            )
        return self._sender

    # -- rules ----------------------------------------------------------------------------------

    async def create_rule(
        self,
        p: Principal,
        *,
        name: str,
        event_type: str,
        target_role_ids: list[uuid.UUID],
        include_tenant_admins: bool,
        title_template: str,
        body_template: str,
        ctx: AuditContext,
    ) -> AppNotificationRule:
        p.require("notifications", Action.manage)
        if event_type not in EVENT_TYPES:
            raise ValidationFailed("Unknown event type.", details={"allowed": sorted(EVENT_TYPES)})
        if not target_role_ids and not include_tenant_admins:
            raise ValidationFailed("A rule needs at least one target.")
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            found = set(
                (await s.execute(select(AppRole.id).where(AppRole.id.in_(target_role_ids)))).scalars()
            )
            if found != set(target_role_ids):
                raise ValidationFailed("Unknown role id.")
            rule = AppNotificationRule(
                tenant_id=p.tenant_id,
                name=name,
                event_type=event_type,
                target_role_ids=target_role_ids,
                include_tenant_admins=include_tenant_admins,
                title_template=title_template,
                body_template=body_template,
            )
            s.add(rule)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="notification_rule.create",
                resource="notifications",
                resource_id=str(rule.id),
                detail={"event_type": event_type},
            )
            await s.commit()
        return rule

    async def list_rules(self, p: Principal) -> list[AppNotificationRule]:
        p.require("notifications", Action.read)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            return list(
                (await s.execute(select(AppNotificationRule).order_by(AppNotificationRule.name))).scalars()
            )

    async def delete_rule(self, p: Principal, rule_id: uuid.UUID, ctx: AuditContext) -> None:
        p.require("notifications", Action.manage)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            rule = (
                await s.execute(select(AppNotificationRule).where(AppNotificationRule.id == rule_id))
            ).scalar_one_or_none()
            if rule is None:
                raise NotFound()
            await s.delete(rule)
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="notification_rule.delete",
                resource="notifications",
                resource_id=str(rule_id),
            )
            await s.commit()

    # -- devices --------------------------------------------------------------------------------

    async def register_device(self, p: Principal, platform: DevicePlatform, token: str) -> SystemUserDevice:
        """Devices belong to the calling *user*; API keys cannot register devices."""
        if p.kind != "user":
            raise Forbidden("Devices are registered by signed-in users.")
        token_hash = hashlib.sha256(token.encode()).digest()
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            dev = (
                await s.execute(
                    select(SystemUserDevice)
                    .where(SystemUserDevice.token_hash == token_hash)
                    .with_for_update()
                )
            ).scalar_one_or_none()
            if dev is None:
                dev = SystemUserDevice(
                    id=uuid.uuid4(),
                    tenant_id=p.tenant_id,
                    user_id=p.user_id,
                    platform=platform,
                    token_hash=token_hash,
                    token_enc="",
                )
                s.add(dev)
            # A token moves to whichever user signed in on the device last.
            dev.user_id, dev.platform, dev.is_active = p.user_id, platform, True
            dev.token_enc = await self._env.encrypt_str(token, _device_ctx(p.tenant_id, dev.id))
            dev.last_seen_at = datetime.now(UTC)
            await s.commit()
        return dev

    async def list_devices(self, p: Principal) -> list[SystemUserDevice]:
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            return list(
                (
                    await s.execute(select(SystemUserDevice).where(SystemUserDevice.user_id == p.user_id))
                ).scalars()
            )

    async def remove_device(self, p: Principal, device_id: uuid.UUID) -> None:
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            dev = (
                await s.execute(
                    select(SystemUserDevice).where(
                        SystemUserDevice.id == device_id, SystemUserDevice.user_id == p.user_id
                    )
                )
            ).scalar_one_or_none()
            if dev is None:
                raise NotFound()
            await s.delete(dev)
            await s.commit()

    # -- delivery (worker side) -------------------------------------------------------------------

    async def deliver(self, tenant_id: uuid.UUID, delivery_id: uuid.UUID) -> DeliveryStatus | None:
        async with self._db.tenant_session(tenant_id) as s:
            d = (
                await s.execute(select(NotificationDelivery).where(NotificationDelivery.id == delivery_id))
            ).scalar_one_or_none()
            if d is None or d.status != DeliveryStatus.pending:
                return None
            dev = (
                await s.execute(select(SystemUserDevice).where(SystemUserDevice.id == d.device_id))
            ).scalar_one()
            attempts, title, body, data = d.attempts, d.title, d.body, dict(d.data)
            active, token_enc, device_id = dev.is_active, dev.token_enc, dev.id
        if not active:
            return await self._finish(tenant_id, delivery_id, DeliveryStatus.dead, "device inactive")
        sender = self.sender()
        if sender is None:
            return await self._finish(tenant_id, delivery_id, DeliveryStatus.dead, "FCM is not configured")
        token = await self._env.decrypt_str(token_enc, _device_ctx(tenant_id, device_id))
        try:
            await sender.send(PushMessage(token, title, body, {k: str(v) for k, v in data.items()}))
        except FcmError as e:
            if e.unregistered:
                await self._deactivate(tenant_id, device_id)
            if e.permanent or attempts >= self._s.notification_max_attempts:
                return await self._finish(tenant_id, delivery_id, DeliveryStatus.dead, str(e))
            return await self._finish(
                tenant_id, delivery_id, DeliveryStatus.pending, str(e), retry_in=backoff_s(attempts)
            )
        except (OutboundError, OutboundBlocked) as e:
            if attempts >= self._s.notification_max_attempts:
                return await self._finish(tenant_id, delivery_id, DeliveryStatus.dead, str(e))
            return await self._finish(
                tenant_id, delivery_id, DeliveryStatus.pending, str(e), retry_in=backoff_s(attempts)
            )
        return await self._finish(tenant_id, delivery_id, DeliveryStatus.succeeded, None)

    async def _deactivate(self, tenant_id: uuid.UUID, device_id: uuid.UUID) -> None:
        async with self._db.tenant_session(tenant_id) as s:
            dev = (
                await s.execute(select(SystemUserDevice).where(SystemUserDevice.id == device_id))
            ).scalar_one()
            dev.is_active = False
            await s.commit()

    async def _finish(
        self,
        tenant_id: uuid.UUID,
        delivery_id: uuid.UUID,
        status: DeliveryStatus,
        error: str | None,
        *,
        retry_in: float | None = None,
    ) -> DeliveryStatus:
        now = datetime.now(UTC)
        async with self._db.tenant_session(tenant_id) as s:
            d = (
                await s.execute(
                    select(NotificationDelivery)
                    .where(NotificationDelivery.id == delivery_id)
                    .with_for_update()
                )
            ).scalar_one()
            d.status, d.last_error = status, error[:500] if error else None
            if status == DeliveryStatus.succeeded:
                d.delivered_at = now
            if retry_in is not None:
                d.next_attempt_at = now + timedelta(seconds=retry_in)
            await s.commit()
        return status
