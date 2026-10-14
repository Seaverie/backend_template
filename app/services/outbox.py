"""Transactional outbox.

Domain services call :func:`emit` inside the same transaction as the state change, so an event
exists if and only if the change committed; no event is lost or published for a rolled-back
change. A relay (``relay_outbox`` worker job, every few seconds plus a nudge after commits) turns
unpublished events into webhook and notification deliveries and marks them published, claiming
rows with ``FOR UPDATE SKIP LOCKED`` so several relays can run.

Delivery is **at-least-once**: a crash between creating deliveries and marking an event published
is safe because deliveries are unique per (target, event), and receivers deduplicate on the event
id we send. Exactly-once delivery is not claimed.
"""

from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.dynamic.crud import to_json_value
from app.models import OutboxEvent

EVENT_TYPES = frozenset(
    {
        "order.created",
        "order.status_changed",
        "payment.succeeded",
        "payment.failed",
        "payment.refunded",
        "rma.status_changed",
        "data.created",
        "data.updated",
        "data.deleted",
        "import.completed",
        "import.failed",
        "delivery.ping",
    }
)


async def emit(
    session: AsyncSession, tenant_id: uuid.UUID, event_type: str, payload: dict[str, Any]
) -> uuid.UUID:
    if event_type not in EVENT_TYPES:
        raise ValueError(f"unknown event type {event_type!r}")
    event_id = uuid.uuid4()
    await session.execute(
        insert(OutboxEvent).values(
            id=event_id,
            tenant_id=tenant_id,
            event_type=event_type,
            payload={k: to_json_value(v) for k, v in payload.items()},
        )
    )
    return event_id
