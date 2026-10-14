"""Background job implementations (plain async functions taking the service container).

Celery tasks in :mod:`app.workers.tasks` are thin wrappers around these, so the same code is tested
directly in integration tests and runs unchanged in workers.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import delete, select, text, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.container import Container
from app.core.errors import Conflict, NotFound
from app.core.logging import get_logger
from app.models import (
    AppNotificationRule,
    AppUser,
    AppUserRole,
    AppWebhook,
    DeliveryStatus,
    DeliveryTrackingPing,
    NotificationDelivery,
    OutboxEvent,
    SystemDeadLetter,
    SystemFile,
    SystemTask,
    SystemUserDevice,
    TaskStatus,
    WebhookDelivery,
)
from app.services.notifications import render
from app.services.tenant_db import SYSTEM_ADMIN

log = get_logger("jobs")


# -- outbox relay ---------------------------------------------------------------------------------------


async def relay_outbox(c: Container, *, batch: int | None = None) -> int:
    """Turn unpublished outbox events into webhook/notification deliveries. Returns events relayed."""
    batch = batch or c.settings.outbox_batch_size
    relayed = 0
    async with c.db.platform_session(SYSTEM_ADMIN) as claim:
        events = list(
            (
                await claim.execute(
                    select(OutboxEvent)
                    .where(OutboxEvent.published_at.is_(None))
                    .order_by(OutboxEvent.created_at)
                    .limit(batch)
                    .with_for_update(skip_locked=True)
                )
            ).scalars()
        )
        by_tenant: dict[uuid.UUID, list[OutboxEvent]] = {}
        for e in events:
            by_tenant.setdefault(e.tenant_id, []).append(e)
        for tenant_id, evs in by_tenant.items():
            try:
                await _fan_out(c, tenant_id, evs)
            except Exception as exc:  # leave this tenant's events unpublished; retried on the next run
                log.error("outbox.fan_out_failed", tenant=str(tenant_id), error_type=type(exc).__name__)
                continue
            now = datetime.now(UTC)
            for e in evs:
                e.published_at = now
            relayed += len(evs)
        await claim.commit()
    return relayed


async def _fan_out(c: Container, tenant_id: uuid.UUID, events: list[OutboxEvent]) -> None:
    types = {e.event_type for e in events}
    async with c.db.tenant_session(tenant_id) as s:
        hooks = list(
            (
                await s.execute(
                    select(AppWebhook).where(
                        AppWebhook.is_active.is_(True), AppWebhook.event_types.overlap(list(types))
                    )
                )
            ).scalars()
        )
        rules = list(
            (
                await s.execute(
                    select(AppNotificationRule).where(
                        AppNotificationRule.is_active.is_(True), AppNotificationRule.event_type.in_(types)
                    )
                )
            ).scalars()
        )
        for e in events:
            for h in hooks:
                if e.event_type in h.event_types:
                    await s.execute(
                        pg_insert(WebhookDelivery)
                        .values(
                            tenant_id=tenant_id,
                            webhook_id=h.id,
                            event_id=e.id,
                            event_type=e.event_type,
                            payload=e.payload,
                        )
                        .on_conflict_do_nothing(index_elements=["tenant_id", "webhook_id", "event_id"])
                    )
            for r in (r for r in rules if r.event_type == e.event_type):
                user_ids = set()
                if r.target_role_ids:
                    user_ids |= set(
                        (
                            await s.execute(
                                select(AppUserRole.user_id).where(AppUserRole.role_id.in_(r.target_role_ids))
                            )
                        ).scalars()
                    )
                if r.include_tenant_admins:
                    user_ids |= set(
                        (
                            await s.execute(select(AppUser.id).where(AppUser.is_tenant_admin.is_(True)))
                        ).scalars()
                    )
                if not user_ids:
                    continue
                active_users = set(
                    (
                        await s.execute(
                            select(AppUser.id).where(AppUser.id.in_(user_ids), AppUser.is_active.is_(True))
                        )
                    ).scalars()
                )
                devices = (
                    (
                        await s.execute(
                            select(SystemUserDevice.id).where(
                                SystemUserDevice.user_id.in_(active_users),
                                SystemUserDevice.is_active.is_(True),
                            )
                        )
                    )
                    .scalars()
                    .all()
                )
                title = render(r.title_template, e.payload, 200)
                body = render(r.body_template, e.payload, 1000)
                for d in devices:
                    await s.execute(
                        pg_insert(NotificationDelivery)
                        .values(
                            tenant_id=tenant_id,
                            rule_id=r.id,
                            event_id=e.id,
                            device_id=d,
                            title=title,
                            body=body,
                            data={"event_id": str(e.id), "event_type": e.event_type},
                        )
                        .on_conflict_do_nothing(
                            index_elements=["tenant_id", "rule_id", "event_id", "device_id"]
                        )
                    )
        await s.commit()


# -- delivery dispatch ------------------------------------------------------------------------------------


async def _claim(c: Container, model: Any, limit: int) -> list[tuple[uuid.UUID, uuid.UUID]]:
    """Lease due deliveries so concurrent dispatchers never send the same one at the same time."""
    now = datetime.now(UTC)
    async with c.db.platform_session(SYSTEM_ADMIN) as s:
        rows = list(
            (
                await s.execute(
                    select(model)
                    .where(model.status == DeliveryStatus.pending, model.next_attempt_at <= now)
                    .order_by(model.next_attempt_at)
                    .limit(limit)
                    .with_for_update(skip_locked=True)
                )
            ).scalars()
        )
        for r in rows:
            r.attempts += 1
            r.next_attempt_at = now + timedelta(
                seconds=c.settings.delivery_lease_s
            )  # lease; replaced on finish
        await s.commit()
    return [(r.tenant_id, r.id) for r in rows]


async def dispatch_webhooks(c: Container, *, limit: int = 100) -> dict[str, int]:
    out: dict[str, int] = {}
    for tenant_id, delivery_id in await _claim(c, WebhookDelivery, limit):
        try:
            status = await c.webhooks.deliver(tenant_id, delivery_id)
        except Exception as e:  # unexpected: the lease expires and the delivery is retried
            log.error("webhook.dispatch_error", delivery=str(delivery_id), error_type=type(e).__name__)
            continue
        if status is not None:
            out[status.value] = out.get(status.value, 0) + 1
            if status == DeliveryStatus.dead:
                await dead_letter(
                    c,
                    tenant_id=tenant_id,
                    source="webhook",
                    reference_id=str(delivery_id),
                    task_name=None,
                    error="webhook delivery exhausted or permanently failed",
                )
    return out


async def dispatch_notifications(c: Container, *, limit: int = 200) -> dict[str, int]:
    out: dict[str, int] = {}
    for tenant_id, delivery_id in await _claim(c, NotificationDelivery, limit):
        try:
            status = await c.notifications.deliver(tenant_id, delivery_id)
        except Exception as e:
            log.error("notification.dispatch_error", delivery=str(delivery_id), error_type=type(e).__name__)
            continue
        if status is not None:
            out[status.value] = out.get(status.value, 0) + 1
            if status == DeliveryStatus.dead:
                await dead_letter(
                    c,
                    tenant_id=tenant_id,
                    source="notification",
                    reference_id=str(delivery_id),
                    task_name=None,
                    error="notification delivery failed permanently",
                )
    return out


# -- dead letters & task bookkeeping ----------------------------------------------------------------------


async def dead_letter(
    c: Container,
    *,
    tenant_id: uuid.UUID | None,
    source: str,
    reference_id: str,
    task_name: str | None,
    error: str,
) -> None:
    async with c.db.platform_session(SYSTEM_ADMIN) as s:
        s.add(
            SystemDeadLetter(
                tenant_id=tenant_id,
                source=source,
                reference_id=reference_id[:100],
                task_name=task_name,
                error=error[:1000],
            )
        )
        await s.commit()


async def fail_task(c: Container, task_id: uuid.UUID, error: str) -> None:
    async with c.db.platform_session(SYSTEM_ADMIN) as s:
        task = (
            await s.execute(select(SystemTask).where(SystemTask.id == task_id).with_for_update())
        ).scalar_one_or_none()
        if task is not None and task.status not in (TaskStatus.succeeded, TaskStatus.failed):
            task.status, task.error, task.finished_at = TaskStatus.failed, error[:1000], datetime.now(UTC)
        await s.commit()


async def stale_tasks(
    c: Container, *, older_than_s: int = 120
) -> list[tuple[str, uuid.UUID | None, uuid.UUID]]:
    """Tasks whose queue message may have been lost (broker down at submit time, worker crash).
    Returned for re-enqueueing; task execution is idempotent, so a duplicate send is harmless."""
    cutoff = datetime.now(UTC) - timedelta(seconds=older_than_s)
    running_cutoff = datetime.now(UTC) - timedelta(seconds=c.settings.celery_task_time_limit_s + 60)
    async with c.db.platform_session(SYSTEM_ADMIN) as s:
        rows = (
            await s.execute(
                select(SystemTask.kind, SystemTask.tenant_id, SystemTask.id)
                .where(
                    ((SystemTask.status == TaskStatus.queued) & (SystemTask.updated_at < cutoff))
                    | ((SystemTask.status == TaskStatus.running) & (SystemTask.updated_at < running_cutoff))
                )
                .limit(500)
            )
        ).all()
        if rows:
            await s.execute(
                text("UPDATE system_tasks SET updated_at = now() WHERE id = ANY(:ids)"),
                {"ids": [r.id for r in rows]},
            )
        await s.commit()
    return [(r.kind, r.tenant_id, r.id) for r in rows]


async def requeue_dead_letter(c: Container, dead_letter_id: uuid.UUID) -> SystemDeadLetter:
    async with c.db.platform_session(SYSTEM_ADMIN) as s:
        dl = (
            await s.execute(
                select(SystemDeadLetter).where(SystemDeadLetter.id == dead_letter_id).with_for_update()
            )
        ).scalar_one_or_none()
        if dl is None:
            raise NotFound()
        if dl.requeued_at is not None:
            raise Conflict("Already requeued.")
        model: Any = {"webhook": WebhookDelivery, "notification": NotificationDelivery}.get(dl.source)
        if model is not None:
            await s.execute(
                update(model)
                .where(model.id == uuid.UUID(dl.reference_id))
                .values(status=DeliveryStatus.pending, attempts=0, next_attempt_at=datetime.now(UTC))
            )
        elif dl.source == "task":
            await s.execute(
                update(SystemTask)
                .where(SystemTask.id == uuid.UUID(dl.reference_id))
                .values(
                    status=TaskStatus.queued,
                    error=None,
                    finished_at=None,
                    updated_at=datetime(2000, 1, 1, tzinfo=UTC),
                )
            )
        dl.requeued_at = datetime.now(UTC)
        await s.commit()
    return dl


# -- maintenance -----------------------------------------------------------------------------------------


async def cleanup(c: Container) -> dict[str, int]:
    now = datetime.now(UTC)
    counts: dict[str, int] = {}
    async with c.db.platform_session(SYSTEM_ADMIN) as s:
        for name, stmt in (
            ("files", delete(SystemFile).where(SystemFile.expires_at < now)),
            (
                "pings",
                delete(DeliveryTrackingPing).where(
                    DeliveryTrackingPing.recorded_at < now - timedelta(days=c.settings.ping_retention_days)
                ),
            ),
            ("outbox", delete(OutboxEvent).where(OutboxEvent.published_at < now - timedelta(days=7))),
            (
                "webhook_deliveries",
                delete(WebhookDelivery).where(
                    WebhookDelivery.status != DeliveryStatus.pending,
                    WebhookDelivery.updated_at < now - timedelta(days=30),
                ),
            ),
            (
                "notification_deliveries",
                delete(NotificationDelivery).where(
                    NotificationDelivery.status != DeliveryStatus.pending,
                    NotificationDelivery.updated_at < now - timedelta(days=30),
                ),
            ),
        ):
            res = await s.execute(stmt)
            counts[name] = res.rowcount or 0  # type: ignore[attr-defined]
        await s.commit()
    return counts
