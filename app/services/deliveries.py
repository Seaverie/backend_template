"""Delivery tracking pings (courier GPS positions for an order)."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from sqlalchemy import select

from app.core.errors import NotFound, ValidationFailed
from app.db.session import Database
from app.models import Action, AppOrder, DeliveryTrackingPing
from app.services.authz import Principal
from app.services.outbox import emit

MAX_FUTURE = timedelta(minutes=5)
MAX_AGE = timedelta(hours=24)


class DeliveryService:
    def __init__(self, db: Database) -> None:
        self._db = db

    async def record_ping(
        self,
        p: Principal,
        order_id: uuid.UUID,
        *,
        lat: Decimal,
        lng: Decimal,
        accuracy_m: int | None,
        recorded_at: datetime,
    ) -> DeliveryTrackingPing:
        p.require("deliveries", Action.create)
        now = datetime.now(UTC)
        if recorded_at.tzinfo is None or not now - MAX_AGE <= recorded_at <= now + MAX_FUTURE:
            raise ValidationFailed("recorded_at must be timezone-aware and within the last 24 hours.")
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            if (await s.execute(select(AppOrder.id).where(AppOrder.id == order_id))).first() is None:
                raise NotFound()
            ping = DeliveryTrackingPing(
                tenant_id=p.tenant_id,
                order_id=order_id,
                user_id=p.user_id,
                lat=lat,
                lng=lng,
                accuracy_m=accuracy_m,
                recorded_at=recorded_at,
            )
            s.add(ping)
            await s.flush()
            await emit(
                s,
                p.tenant_id,
                "delivery.ping",
                {"order_id": order_id, "lat": lat, "lng": lng, "recorded_at": recorded_at},
            )
            await s.commit()
        return ping

    async def pings(
        self, p: Principal, order_id: uuid.UUID, *, since: datetime | None, limit: int
    ) -> list[DeliveryTrackingPing]:
        p.require("deliveries", Action.read)
        stmt = select(DeliveryTrackingPing).where(DeliveryTrackingPing.order_id == order_id)
        if since is not None:
            stmt = stmt.where(DeliveryTrackingPing.recorded_at > since)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            return list(
                (
                    await s.execute(stmt.order_by(DeliveryTrackingPing.recorded_at.desc()).limit(limit))
                ).scalars()
            )
