"""Returns (RMA) with a fixed, validated state machine.

    requested --approve--> approved --receive--> received --refund--> refunded --close--> closed
        \\--reject--> rejected                         \\--close (no refund)--> closed

* Only shipped (stock-committed) orders can be returned, and per line no more than
  ``qty - qty_returned - qty in other open RMAs``.
* ``receive`` restocks the lines flagged ``restock`` into ``restock_bin_id`` through the ledger
  (``return_restock`` movements, idempotent per RMA) and increments ``qty_returned``.
* ``refund`` calls the payment provider outside the database transaction with the idempotency key
  ``rma:<id>``, so a retried refund never pays out twice. The state change is then committed.
* Every transition appends an ``rma_events`` row (append-only) and an audit log entry.
"""

from __future__ import annotations

import uuid
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import Conflict, NotFound, ValidationFailed
from app.db.session import Database
from app.models import (
    Action,
    AppOrder,
    AppOrderItem,
    MovementKind,
    OrderStockState,
    RmaEvent,
    RmaItem,
    RmaRequest,
    RmaStatus,
    WarehouseBin,
)
from app.services import ledger
from app.services.audit import AuditContext, record
from app.services.authz import Principal
from app.services.inventory import new_number
from app.services.payments import PaymentService

TRANSITIONS: dict[str, tuple[set[RmaStatus], RmaStatus]] = {
    "approve": ({RmaStatus.requested}, RmaStatus.approved),
    "reject": ({RmaStatus.requested}, RmaStatus.rejected),
    "receive": ({RmaStatus.approved}, RmaStatus.received),
    "refund": ({RmaStatus.received}, RmaStatus.refunded),
    "close": ({RmaStatus.received, RmaStatus.refunded}, RmaStatus.closed),
}
OPEN = (RmaStatus.requested, RmaStatus.approved)


@dataclass(frozen=True, slots=True)
class RmaLine:
    order_item_id: uuid.UUID
    qty: int
    restock: bool


class RmaService:
    def __init__(self, db: Database, payments: PaymentService) -> None:
        self._db = db
        self._payments = payments

    def _tx(self, p: Principal) -> AbstractAsyncContextManager[AsyncSession]:
        return self._db.tenant_session(p.tenant_id, user_id=p.user_id)

    async def create(
        self, p: Principal, order_id: uuid.UUID, *, lines: list[RmaLine], reason: str, ctx: AuditContext
    ) -> RmaRequest:
        p.require("rma", Action.create)
        if not 1 <= len(lines) <= 100 or len({ln.order_item_id for ln in lines}) != len(lines):
            raise ValidationFailed("1-100 distinct order lines are required")
        async with self._tx(p) as s:
            order = (
                await s.execute(select(AppOrder).where(AppOrder.id == order_id).with_for_update())
            ).scalar_one_or_none()
            if order is None:
                raise NotFound()
            if order.stock_state != OrderStockState.committed:
                raise Conflict("Only shipped orders can be returned.")
            items = {
                i.id: i
                for i in (
                    await s.execute(select(AppOrderItem).where(AppOrderItem.order_id == order_id))
                ).scalars()
            }
            pending = dict(
                (
                    await s.execute(
                        select(RmaItem.order_item_id, func.sum(RmaItem.qty))
                        .join(RmaRequest, RmaRequest.id == RmaItem.rma_id)
                        .where(RmaRequest.order_id == order_id, RmaRequest.status.in_(OPEN))
                        .group_by(RmaItem.order_item_id)
                    )
                ).all()
            )
            for ln in lines:
                item = items.get(ln.order_item_id)
                if item is None:
                    raise ValidationFailed("Unknown order line.")
                returnable = item.qty - item.qty_returned - int(pending.get(item.id, 0))
                if not 0 < ln.qty <= returnable:
                    raise ValidationFailed(
                        "Return quantity exceeds what can still be returned.",
                        details={"order_item_id": str(item.id), "returnable": returnable},
                    )
            rma = RmaRequest(
                tenant_id=p.tenant_id,
                number=new_number("RMA"),
                order_id=order_id,
                reason=reason,
                created_by=p.user_id,
            )
            s.add(rma)
            await s.flush()
            s.add_all(
                RmaItem(
                    tenant_id=p.tenant_id,
                    rma_id=rma.id,
                    order_item_id=ln.order_item_id,
                    qty=ln.qty,
                    restock=ln.restock,
                )
                for ln in lines
            )
            s.add(
                RmaEvent(
                    tenant_id=p.tenant_id,
                    rma_id=rma.id,
                    to_status=RmaStatus.requested.value,
                    actor_id=p.user_id,
                    note=reason,
                )
            )
            await record(
                s, ctx, tenant_id=p.tenant_id, action="rma.create", resource="rma", resource_id=str(rma.id)
            )
            await s.commit()
        return rma

    async def transition(
        self,
        p: Principal,
        rma_id: uuid.UUID,
        action: str,
        *,
        note: str | None,
        restock_bin_id: uuid.UUID | None,
        refund_amount: Decimal | None,
        ctx: AuditContext,
    ) -> RmaRequest:
        if action not in TRANSITIONS:
            raise ValidationFailed("Unknown RMA action.")
        p.require("rma", Action.manage)
        sources, target = TRANSITIONS[action]

        if action == "refund":
            # Validate first; then call the provider outside any transaction; then commit the state.
            async with self._tx(p) as s:
                rma = await self._load(s, rma_id, lock=False)
                if rma.status not in sources:
                    raise Conflict(f"Cannot {action} an RMA that is {rma.status.value}.")
                if refund_amount is None or refund_amount <= 0:
                    raise ValidationFailed("refund_amount must be positive.")
                ceiling = await self._returned_value(s, rma_id)
                if refund_amount > ceiling:
                    raise ValidationFailed(
                        "Refund exceeds the value of the returned items.",
                        details={"max_refund": str(ceiling)},
                    )
                order_id = rma.order_id
            await self._payments.refund_order(
                p, order_id, refund_amount, idempotency_key=f"rma:{rma_id}", ctx=ctx
            )

        async with self._tx(p) as s:
            rma = await self._load(s, rma_id, lock=True)
            if rma.status not in sources:
                if action == "refund" and rma.status == RmaStatus.refunded:
                    return rma  # concurrent/retried refund already recorded
                raise Conflict(f"Cannot {action} an RMA that is {rma.status.value}.")
            detail: dict[str, Any] = {}
            if action == "receive":
                detail = await self._receive(s, p, rma, restock_bin_id)
            if action == "refund" and refund_amount is not None:
                rma.refund_amount = refund_amount
                detail = {"refund_amount": str(refund_amount)}
            old = rma.status
            rma.status = target
            s.add(
                RmaEvent(
                    tenant_id=p.tenant_id,
                    rma_id=rma.id,
                    from_status=old.value,
                    to_status=target.value,
                    actor_id=p.user_id,
                    note=note,
                    detail=detail,
                )
            )
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action=f"rma.{action}",
                resource="rma",
                resource_id=str(rma_id),
                detail={"from": old.value, "to": target.value, **detail},
            )
            await s.commit()
        return rma

    async def _load(self, s: Any, rma_id: uuid.UUID, *, lock: bool) -> RmaRequest:
        stmt = select(RmaRequest).where(RmaRequest.id == rma_id)
        if lock:
            stmt = stmt.with_for_update()
        rma = (await s.execute(stmt)).scalar_one_or_none()
        if rma is None:
            raise NotFound()
        return rma  # type: ignore[no-any-return]

    async def _returned_value(self, s: Any, rma_id: uuid.UUID) -> Decimal:
        value = (
            await s.execute(
                select(func.coalesce(func.sum(RmaItem.qty * AppOrderItem.unit_price), 0))
                .join(AppOrderItem, AppOrderItem.id == RmaItem.order_item_id)
                .where(RmaItem.rma_id == rma_id)
            )
        ).scalar_one()
        return Decimal(value)

    async def _receive(
        self, s: Any, p: Principal, rma: RmaRequest, restock_bin_id: uuid.UUID | None
    ) -> dict[str, Any]:
        rows = (
            await s.execute(
                select(RmaItem, AppOrderItem)
                .join(AppOrderItem, AppOrderItem.id == RmaItem.order_item_id)
                .where(RmaItem.rma_id == rma.id)
                .with_for_update(of=AppOrderItem)
            )
        ).all()
        restock = [(ri, oi) for ri, oi in rows if ri.restock]
        if restock:
            if restock_bin_id is None:
                raise ValidationFailed("restock_bin_id is required to restock returned items.")
            if (
                await s.execute(select(WarehouseBin.id).where(WarehouseBin.id == restock_bin_id))
            ).first() is None:
                raise ValidationFailed("Unknown restock bin.")
            await ledger.post(
                s,
                tenant_id=p.tenant_id,
                actor_id=p.user_id,
                batch_key=f"rma:{rma.id}:restock",
                lines=[
                    ledger.Line(
                        oi.variant_id,
                        restock_bin_id,
                        MovementKind.return_restock,
                        ri.qty,
                        reference_type="rma",
                        reference_id=rma.id,
                    )
                    for ri, oi in restock
                ],
            )
            rma.restock_bin_id = restock_bin_id
        for ri, oi in rows:
            if oi.qty_returned + ri.qty > oi.qty:
                raise Conflict("Returned quantity exceeds the shipped quantity.")
            oi.qty_returned += ri.qty
        return {"restocked_lines": len(restock)}

    async def get(self, p: Principal, rma_id: uuid.UUID) -> dict[str, Any]:
        p.require("rma", Action.read)
        async with self._tx(p) as s:
            rma = await self._load(s, rma_id, lock=False)
            items = (await s.execute(select(RmaItem).where(RmaItem.rma_id == rma_id))).scalars().all()
            events = (
                (
                    await s.execute(
                        select(RmaEvent).where(RmaEvent.rma_id == rma_id).order_by(RmaEvent.created_at)
                    )
                )
                .scalars()
                .all()
            )
        return {"rma": rma, "items": list(items), "events": list(events)}
