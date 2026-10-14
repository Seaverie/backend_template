"""Orders: configurable lifecycle, checkout with stock reservation, transitions with stock effects.

Stock effects are driven by the *target* status's ``stock_effect`` and the order's ``stock_state``,
which records what has already happened to its stock, so every effect is applied exactly once:

    effect \\ state | none                    | reserved               | committed     | released
    reserve         | allocate + reserve      | no-op                  | no-op         | allocate + reserve
    commit          | allocate + ship         | ship reserved units    | no-op         | allocate + ship
    release         | no-op                   | release reservation    | 409 (use RMA) | no-op

All stock changes go through :mod:`app.services.ledger` (row locks, append-only ledger).
"""

from __future__ import annotations

import uuid
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import Conflict, NotFound, ValidationFailed
from app.db.session import Database
from app.models import (
    Action,
    AppOrder,
    AppOrderItem,
    AppOrderStatus,
    AppOrderStatusTransition,
    AppProduct,
    AppProductVariant,
    MovementKind,
    OrderAllocation,
    OrderStatusHistory,
    OrderStockState,
    ProductStatus,
    StockEffect,
)
from app.services import ledger
from app.services.audit import AuditContext, record
from app.services.authz import Principal
from app.services.inventory import new_number

MAX_ITEMS = 100

DEFAULT_STATUSES: list[dict[str, Any]] = [
    {
        "code": "pending",
        "label": "Pending",
        "is_initial": True,
        "stock_effect": StockEffect.reserve,
        "position": 0,
    },
    {"code": "paid", "label": "Paid", "stock_effect": StockEffect.none, "position": 1},
    {"code": "shipped", "label": "Shipped", "stock_effect": StockEffect.commit, "position": 2},
    {
        "code": "delivered",
        "label": "Delivered",
        "is_terminal": True,
        "stock_effect": StockEffect.none,
        "position": 3,
    },
    {
        "code": "cancelled",
        "label": "Cancelled",
        "is_terminal": True,
        "stock_effect": StockEffect.release,
        "is_sale": False,
        "position": 4,
    },
]
DEFAULT_TRANSITIONS = [
    ("pending", "paid"),
    ("pending", "cancelled"),
    ("paid", "shipped"),
    ("paid", "cancelled"),
    ("shipped", "delivered"),
]


async def seed_default_statuses(session: AsyncSession, tenant_id: uuid.UUID) -> None:
    rows = {d["code"]: AppOrderStatus(tenant_id=tenant_id, **d) for d in DEFAULT_STATUSES}
    session.add_all(rows.values())
    await session.flush()
    session.add_all(
        AppOrderStatusTransition(tenant_id=tenant_id, from_status_id=rows[a].id, to_status_id=rows[b].id)
        for a, b in DEFAULT_TRANSITIONS
    )


@dataclass(frozen=True, slots=True)
class CartLine:
    variant_id: uuid.UUID
    qty: int


@dataclass(frozen=True, slots=True)
class StatusSpec:
    code: str
    label: str
    is_initial: bool
    is_terminal: bool
    stock_effect: StockEffect
    is_sale: bool


class OrderService:
    def __init__(self, db: Database) -> None:
        self._db = db

    def _tx(self, p: Principal) -> AbstractAsyncContextManager[AsyncSession]:
        return self._db.tenant_session(p.tenant_id, user_id=p.user_id)

    # -- lifecycle configuration ----------------------------------------------------------------

    async def list_statuses(self, p: Principal) -> tuple[list[AppOrderStatus], list[tuple[str, str]]]:
        p.require("orders", Action.read)
        async with self._tx(p) as s:
            statuses = list(
                (await s.execute(select(AppOrderStatus).order_by(AppOrderStatus.position))).scalars()
            )
            by_id = {x.id: x.code for x in statuses}
            trans = (await s.execute(select(AppOrderStatusTransition))).scalars().all()
        return statuses, sorted((by_id[t.from_status_id], by_id[t.to_status_id]) for t in trans)

    async def configure_lifecycle(
        self, p: Principal, statuses: list[StatusSpec], transitions: list[tuple[str, str]], ctx: AuditContext
    ) -> None:
        """Replace the tenant's status set and transition graph atomically.

        Existing statuses are updated in place (matched by code). Statuses still used by orders cannot
        be removed. Validation: exactly one initial status; the initial status may only reserve or do
        nothing; terminal statuses have no outgoing transitions; every transition names known codes.
        """
        p.require("settings", Action.manage)
        codes = [x.code for x in statuses]
        if len(set(codes)) != len(codes):
            raise ValidationFailed("status codes must be unique")
        initial = [x for x in statuses if x.is_initial]
        if len(initial) != 1:
            raise ValidationFailed("exactly one initial status is required")
        if initial[0].stock_effect not in (StockEffect.none, StockEffect.reserve):
            raise ValidationFailed("the initial status may only reserve stock or have no stock effect")
        terminal = {x.code for x in statuses if x.is_terminal}
        for a, b in transitions:
            if a not in codes or b not in codes or a == b:
                raise ValidationFailed("transitions must connect two different known statuses")
            if a in terminal:
                raise ValidationFailed(f"terminal status {a!r} cannot have outgoing transitions")
        async with self._tx(p) as s:
            existing = {
                x.code: x for x in (await s.execute(select(AppOrderStatus).with_for_update())).scalars()
            }
            removed = set(existing) - set(codes)
            if removed:
                in_use = (
                    await s.execute(
                        select(func.count())
                        .select_from(AppOrder)
                        .where(AppOrder.status_id.in_([existing[c].id for c in removed]))
                    )
                ).scalar_one()
                if in_use:
                    raise Conflict("Statuses that are in use by orders cannot be removed.")
            await s.execute(delete(AppOrderStatusTransition))
            for c in removed:
                await s.delete(existing[c])
            # Clear the initial flag first so the partial unique index never sees two initials.
            for x in existing.values():
                x.is_initial = False
            await s.flush()
            rows: dict[str, AppOrderStatus] = {}
            for pos, spec in enumerate(statuses):
                row = existing.get(spec.code) or AppOrderStatus(tenant_id=p.tenant_id, code=spec.code)
                row.label, row.is_initial, row.is_terminal = spec.label, spec.is_initial, spec.is_terminal
                row.stock_effect, row.is_sale, row.position = spec.stock_effect, spec.is_sale, pos
                s.add(row)
                rows[spec.code] = row
            await s.flush()
            s.add_all(
                AppOrderStatusTransition(
                    tenant_id=p.tenant_id, from_status_id=rows[a].id, to_status_id=rows[b].id
                )
                for a, b in sorted(set(transitions))
            )
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="orders.lifecycle.configure",
                resource="settings",
                detail={"statuses": codes, "transitions": [f"{a}->{b}" for a, b in transitions]},
            )
            await s.commit()

    # -- checkout -------------------------------------------------------------------------------

    async def checkout(
        self, p: Principal, *, lines: list[CartLine], customer: dict[str, Any], ctx: AuditContext
    ) -> AppOrder:
        """Create an order. Prices come from the catalog (never from the client). If the initial status
        reserves stock, availability is checked and reserved under row locks in the same transaction."""
        p.require("orders", Action.create)
        if not 1 <= len(lines) <= MAX_ITEMS or any(ln.qty <= 0 or ln.qty > 10_000 for ln in lines):
            raise ValidationFailed(f"1-{MAX_ITEMS} lines with quantities 1-10000 are required")
        merged: dict[uuid.UUID, int] = {}
        for ln in lines:
            merged[ln.variant_id] = merged.get(ln.variant_id, 0) + ln.qty
        async with self._tx(p) as s:
            variants = {
                v.id: v
                for v in (
                    await s.execute(
                        select(AppProductVariant)
                        .join(AppProduct, AppProduct.id == AppProductVariant.product_id)
                        .where(
                            AppProductVariant.id.in_(merged),
                            AppProductVariant.is_active.is_(True),
                            AppProduct.status == ProductStatus.active,
                        )
                    )
                ).scalars()
            }
            missing = [str(v) for v in merged if v not in variants]
            if missing:
                raise ValidationFailed(
                    "Unknown or unavailable variants.", details={"variant_ids": missing[:20]}
                )
            currencies = {v.currency for v in variants.values()}
            if len(currencies) != 1:
                raise ValidationFailed("All items in an order must share one currency.")
            initial = (
                await s.execute(select(AppOrderStatus).where(AppOrderStatus.is_initial.is_(True)))
            ).scalar_one_or_none()
            if initial is None:
                raise Conflict("The order lifecycle has no initial status configured.")

            order = AppOrder(
                tenant_id=p.tenant_id,
                number=new_number("ORD"),
                status_id=initial.id,
                currency=currencies.pop(),
                total=Decimal("0"),
                customer=customer,
                created_by=p.user_id,
            )
            s.add(order)
            await s.flush()
            items: list[AppOrderItem] = []
            total = Decimal("0")
            for vid, qty in merged.items():
                v = variants[vid]
                line_total = v.price * qty
                total += line_total
                items.append(
                    AppOrderItem(
                        tenant_id=p.tenant_id,
                        order_id=order.id,
                        variant_id=vid,
                        sku=v.sku,
                        name=v.name,
                        qty=qty,
                        unit_price=v.price,
                        line_total=line_total,
                    )
                )
            s.add_all(items)
            order.total = total
            await s.flush()
            await self._apply_effect(s, p, order, items, initial.stock_effect, step=0)
            s.add(
                OrderStatusHistory(
                    tenant_id=p.tenant_id, order_id=order.id, to_status=initial.code, actor_id=p.user_id
                )
            )
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="order.create",
                resource="orders",
                resource_id=str(order.id),
                detail={"total": str(total), "lines": len(items)},
            )
            await s.commit()
        return order

    async def _apply_effect(
        self,
        s: AsyncSession,
        p: Principal,
        order: AppOrder,
        items: list[AppOrderItem],
        effect: StockEffect,
        *,
        step: int,
    ) -> None:
        state = order.stock_state
        key = f"order:{order.id}:{step}:{effect.value}"
        if effect == StockEffect.reserve and state in (OrderStockState.none, OrderStockState.released):
            lines = await self._allocate(s, p, items, MovementKind.reservation, order.id)
            await ledger.post(s, tenant_id=p.tenant_id, actor_id=p.user_id, batch_key=key, lines=lines)
            order.stock_state = OrderStockState.reserved
        elif effect == StockEffect.commit and state == OrderStockState.reserved:
            allocs = await self._allocations(s, items)
            lines = [
                ledger.Line(
                    a_item.variant_id, a.bin_id, MovementKind.shipment, -a.qty, -a.qty, "order", order.id
                )
                for a, a_item in allocs
            ]
            await ledger.post(s, tenant_id=p.tenant_id, actor_id=p.user_id, batch_key=key, lines=lines)
            order.stock_state = OrderStockState.committed
        elif effect == StockEffect.commit and state in (OrderStockState.none, OrderStockState.released):
            lines = await self._allocate(s, p, items, MovementKind.shipment, order.id)
            await ledger.post(s, tenant_id=p.tenant_id, actor_id=p.user_id, batch_key=key, lines=lines)
            order.stock_state = OrderStockState.committed
        elif effect == StockEffect.release and state == OrderStockState.reserved:
            allocs = await self._allocations(s, items)
            lines = [
                ledger.Line(a_item.variant_id, a.bin_id, MovementKind.release, 0, -a.qty, "order", order.id)
                for a, a_item in allocs
            ]
            await ledger.post(s, tenant_id=p.tenant_id, actor_id=p.user_id, batch_key=key, lines=lines)
            await s.execute(
                delete(OrderAllocation).where(OrderAllocation.order_item_id.in_([i.id for i in items]))
            )
            order.stock_state = OrderStockState.released
        elif effect == StockEffect.release and state == OrderStockState.committed:
            raise Conflict("Shipped stock cannot be released; create a return (RMA) instead.")

    async def _allocate(
        self,
        s: AsyncSession,
        p: Principal,
        items: list[AppOrderItem],
        kind: MovementKind,
        order_id: uuid.UUID,
    ) -> list[ledger.Line]:
        balances = await ledger.lock_variant_balances(s, (i.variant_id for i in items))
        lines: list[ledger.Line] = []
        for item in items:
            for bin_id, qty in ledger.allocate(balances, item.variant_id, item.qty):
                balances[(item.variant_id, bin_id)].reserved += qty  # keep later items' view consistent
                s.add(OrderAllocation(tenant_id=p.tenant_id, order_item_id=item.id, bin_id=bin_id, qty=qty))
                if kind == MovementKind.reservation:
                    lines.append(ledger.Line(item.variant_id, bin_id, kind, 0, qty, "order", order_id))
                else:
                    lines.append(ledger.Line(item.variant_id, bin_id, kind, -qty, 0, "order", order_id))
        await s.flush()
        return lines

    async def _allocations(
        self, s: AsyncSession, items: list[AppOrderItem]
    ) -> list[tuple[OrderAllocation, AppOrderItem]]:
        by_id = {i.id: i for i in items}
        rows = (
            (await s.execute(select(OrderAllocation).where(OrderAllocation.order_item_id.in_(list(by_id)))))
            .scalars()
            .all()
        )
        return [(a, by_id[a.order_item_id]) for a in rows]

    # -- transitions & reads --------------------------------------------------------------------

    async def transition(
        self, p: Principal, order_id: uuid.UUID, to_code: str, note: str | None, ctx: AuditContext
    ) -> AppOrder:
        p.require("orders", Action.update)
        async with self._tx(p) as s:
            order = (
                await s.execute(select(AppOrder).where(AppOrder.id == order_id).with_for_update())
            ).scalar_one_or_none()
            if order is None:
                raise NotFound()
            statuses = {x.id: x for x in (await s.execute(select(AppOrderStatus))).scalars()}
            current = statuses[order.status_id]
            target = next((x for x in statuses.values() if x.code == to_code), None)
            allowed = (
                target is not None
                and (
                    await s.execute(
                        select(AppOrderStatusTransition).where(
                            AppOrderStatusTransition.from_status_id == current.id,
                            AppOrderStatusTransition.to_status_id == target.id,
                        )
                    )
                ).first()
                is not None
            )
            if not allowed or target is None:
                raise Conflict(f"Transition {current.code} -> {to_code} is not allowed.")
            items = list(
                (await s.execute(select(AppOrderItem).where(AppOrderItem.order_id == order_id))).scalars()
            )
            step = (
                await s.execute(
                    select(func.count())
                    .select_from(OrderStatusHistory)
                    .where(OrderStatusHistory.order_id == order_id)
                )
            ).scalar_one()
            await self._apply_effect(s, p, order, items, target.stock_effect, step=step)
            order.status_id = target.id
            s.add(
                OrderStatusHistory(
                    tenant_id=p.tenant_id,
                    order_id=order_id,
                    from_status=current.code,
                    to_status=target.code,
                    actor_id=p.user_id,
                    note=note,
                )
            )
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="order.transition",
                resource="orders",
                resource_id=str(order_id),
                detail={"from": current.code, "to": target.code},
            )
            await s.commit()
        return order

    async def get(self, p: Principal, order_id: uuid.UUID) -> dict[str, Any]:
        p.require("orders", Action.read)
        async with self._tx(p) as s:
            order = (await s.execute(select(AppOrder).where(AppOrder.id == order_id))).scalar_one_or_none()
            if order is None:
                raise NotFound()
            status = (
                await s.execute(select(AppOrderStatus.code).where(AppOrderStatus.id == order.status_id))
            ).scalar_one()
            items = (
                (await s.execute(select(AppOrderItem).where(AppOrderItem.order_id == order_id)))
                .scalars()
                .all()
            )
            history = (
                (
                    await s.execute(
                        select(OrderStatusHistory)
                        .where(OrderStatusHistory.order_id == order_id)
                        .order_by(OrderStatusHistory.created_at)
                    )
                )
                .scalars()
                .all()
            )
        return {"order": order, "status": status, "items": list(items), "history": list(history)}

    async def list_orders(
        self, p: Principal, *, status: str | None, limit: int, offset: int
    ) -> list[tuple[AppOrder, str]]:
        p.require("orders", Action.read)
        stmt = select(AppOrder, AppOrderStatus.code).join(
            AppOrderStatus, AppOrderStatus.id == AppOrder.status_id
        )
        if status:
            stmt = stmt.where(AppOrderStatus.code == status)
        async with self._tx(p) as s:
            rows = (
                await s.execute(
                    stmt.order_by(AppOrder.placed_at.desc(), AppOrder.id).limit(limit).offset(offset)
                )
            ).all()
        return [(o, code) for o, code in rows]
