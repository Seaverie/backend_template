"""Catalog, warehouses, stock operations, and purchase orders."""

from __future__ import annotations

import secrets
import uuid
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import func, literal_column, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import Conflict, NotFound, ValidationFailed
from app.db.session import Database
from app.models import (
    Action,
    AppProduct,
    AppProductVariant,
    AppPurchaseOrder,
    AppWarehouse,
    MovementKind,
    PoItem,
    ProductStatus,
    PurchaseOrderStatus,
    StockBalance,
    StockMovement,
    WarehouseBin,
)
from app.services import ledger
from app.services.audit import AuditContext, record
from app.services.authz import Principal


def new_number(prefix: str) -> str:
    return f"{prefix}-{datetime.now(UTC):%Y%m%d}-{secrets.token_hex(4).upper()}"


async def _get(session: AsyncSession, model: Any, obj_id: uuid.UUID, *, lock: bool = False) -> Any:
    stmt = select(model).where(model.id == obj_id)
    if lock:
        stmt = stmt.with_for_update()
    obj = (await session.execute(stmt)).scalar_one_or_none()
    if obj is None:
        raise NotFound()
    return obj


@dataclass(frozen=True, slots=True)
class ReceiveLine:
    po_item_id: uuid.UUID
    qty: int
    bin_id: uuid.UUID


class InventoryService:
    def __init__(self, settings: Settings, db: Database) -> None:
        self._s = settings
        self._db = db

    def _tx(self, p: Principal) -> AbstractAsyncContextManager[AsyncSession]:
        return self._db.tenant_session(p.tenant_id, user_id=p.user_id)

    # -- warehouses & bins ----------------------------------------------------------------------

    async def create_warehouse(
        self, p: Principal, *, code: str, name: str, address: dict[str, Any], ctx: AuditContext
    ) -> AppWarehouse:
        p.require("inventory", Action.manage)
        async with self._tx(p) as s:
            w = AppWarehouse(tenant_id=p.tenant_id, code=code, name=name, address=address)
            s.add(w)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="warehouse.create",
                resource="inventory",
                resource_id=str(w.id),
            )
            await s.commit()
        return w

    async def create_bin(
        self, p: Principal, warehouse_id: uuid.UUID, *, code: str, ctx: AuditContext
    ) -> WarehouseBin:
        p.require("inventory", Action.manage)
        async with self._tx(p) as s:
            await _get(s, AppWarehouse, warehouse_id)
            b = WarehouseBin(tenant_id=p.tenant_id, warehouse_id=warehouse_id, code=code)
            s.add(b)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="bin.create",
                resource="inventory",
                resource_id=str(b.id),
            )
            await s.commit()
        return b

    async def list_warehouses(self, p: Principal) -> list[tuple[AppWarehouse, list[WarehouseBin]]]:
        p.require("inventory", Action.read)
        async with self._tx(p) as s:
            ws = (
                (await s.execute(select(AppWarehouse).order_by(AppWarehouse.code).limit(500))).scalars().all()
            )
            bins = (await s.execute(select(WarehouseBin).order_by(WarehouseBin.code))).scalars().all()
        by_w: dict[uuid.UUID, list[WarehouseBin]] = {}
        for b in bins:
            by_w.setdefault(b.warehouse_id, []).append(b)
        return [(w, by_w.get(w.id, [])) for w in ws]

    # -- products & variants --------------------------------------------------------------------

    async def create_product(
        self,
        p: Principal,
        *,
        name: str,
        description: str | None,
        status: ProductStatus,
        is_public: bool,
        metadata: dict[str, Any],
        variants: list[dict[str, Any]],
        ctx: AuditContext,
    ) -> tuple[AppProduct, list[AppProductVariant]]:
        p.require("products", Action.create)
        async with self._tx(p) as s:
            prod = AppProduct(
                tenant_id=p.tenant_id,
                name=name,
                description=description,
                status=status,
                is_public=is_public,
                metadata_=metadata,
            )
            s.add(prod)
            await s.flush()
            vs = [AppProductVariant(tenant_id=p.tenant_id, product_id=prod.id, **v) for v in variants]
            s.add_all(vs)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="product.create",
                resource="products",
                resource_id=str(prod.id),
                detail={"variants": [v.sku for v in vs]},
            )
            await s.commit()
        return prod, vs

    async def update_product(
        self, p: Principal, product_id: uuid.UUID, changes: dict[str, Any], ctx: AuditContext
    ) -> AppProduct:
        p.require("products", Action.update)
        async with self._tx(p) as s:
            prod = await _get(s, AppProduct, product_id, lock=True)
            for k, v in changes.items():
                setattr(prod, "metadata_" if k == "metadata" else k, v)
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="product.update",
                resource="products",
                resource_id=str(product_id),
                detail={"fields": sorted(changes)},
            )
            await s.commit()
        return prod  # type: ignore[no-any-return]

    async def add_variant(
        self, p: Principal, product_id: uuid.UUID, data: dict[str, Any], ctx: AuditContext
    ) -> AppProductVariant:
        p.require("products", Action.create)
        async with self._tx(p) as s:
            await _get(s, AppProduct, product_id)
            v = AppProductVariant(tenant_id=p.tenant_id, product_id=product_id, **data)
            s.add(v)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="variant.create",
                resource="products",
                resource_id=str(v.id),
            )
            await s.commit()
        return v

    async def search_products(
        self,
        p: Principal | None,
        tenant_id: uuid.UUID,
        *,
        q: str | None,
        metadata: dict[str, Any] | None,
        status: ProductStatus | None,
        public_only: bool,
        limit: int,
        offset: int,
    ) -> list[tuple[AppProduct, list[AppProductVariant]]]:
        """Full-text (``q``) and JSONB-containment (``metadata``) search. ``public_only`` is the
        anonymous catalog view: only active, public products and active variants."""
        if p is not None:
            p.require("products", Action.read)
        stmt = select(AppProduct)
        if q:
            tsq = func.plainto_tsquery(literal_column("'simple'::regconfig"), q)
            stmt = stmt.where(AppProduct.search.op("@@")(tsq)).order_by(
                func.ts_rank(AppProduct.search, tsq).desc()
            )
        if metadata:
            stmt = stmt.where(AppProduct.metadata_.contains(metadata))
        if public_only:
            stmt = stmt.where(AppProduct.is_public.is_(True), AppProduct.status == ProductStatus.active)
        elif status is not None:
            stmt = stmt.where(AppProduct.status == status)
        stmt = stmt.order_by(AppProduct.created_at.desc(), AppProduct.id).limit(limit).offset(offset)
        async with self._db.tenant_session(tenant_id, user_id=p.user_id if p else None) as s:
            prods = (await s.execute(stmt)).scalars().all()
            if not prods:
                return []
            vq = select(AppProductVariant).where(AppProductVariant.product_id.in_([x.id for x in prods]))
            if public_only:
                vq = vq.where(AppProductVariant.is_active.is_(True))
            variants = (await s.execute(vq.order_by(AppProductVariant.sku))).scalars().all()
        by_p: dict[uuid.UUID, list[AppProductVariant]] = {}
        for v in variants:
            by_p.setdefault(v.product_id, []).append(v)
        return [(x, by_p.get(x.id, [])) for x in prods]

    # -- stock ------------------------------------------------------------------------------------

    async def adjust(
        self,
        p: Principal,
        *,
        batch_key: str,
        lines: list[tuple[uuid.UUID, uuid.UUID, int]],
        note: str | None,
        ctx: AuditContext,
    ) -> bool:
        """Manual on-hand corrections: lines of (variant_id, bin_id, delta)."""
        p.require("inventory", Action.manage)
        async with self._tx(p) as s:
            replayed = await ledger.post(
                s,
                tenant_id=p.tenant_id,
                actor_id=p.user_id,
                batch_key=f"adjust:{batch_key}",
                lines=[ledger.Line(v, b, MovementKind.adjustment, d, note=note) for v, b, d in lines],
            )
            if not replayed:
                await record(
                    s,
                    ctx,
                    tenant_id=p.tenant_id,
                    action="stock.adjust",
                    resource="inventory",
                    resource_id=batch_key,
                    detail={"lines": len(lines)},
                )
            await s.commit()
        return replayed

    async def transfer(
        self,
        p: Principal,
        *,
        batch_key: str,
        variant_id: uuid.UUID,
        from_bin: uuid.UUID,
        to_bin: uuid.UUID,
        qty: int,
        ctx: AuditContext,
    ) -> bool:
        p.require("inventory", Action.manage)
        if from_bin == to_bin:
            raise ValidationFailed("source and destination bins must differ")
        async with self._tx(p) as s:
            # Transfers may only move *available* stock (never reserved units).
            bal = await ledger.lock_balances(s, p.tenant_id, [(variant_id, from_bin), (variant_id, to_bin)])
            if bal[(variant_id, from_bin)].available < qty:
                raise ledger.InsufficientStock()
            replayed = await ledger.post(
                s,
                tenant_id=p.tenant_id,
                actor_id=p.user_id,
                batch_key=f"transfer:{batch_key}",
                lines=[
                    ledger.Line(variant_id, from_bin, MovementKind.transfer, -qty),
                    ledger.Line(variant_id, to_bin, MovementKind.transfer, qty),
                ],
            )
            if not replayed:
                await record(
                    s,
                    ctx,
                    tenant_id=p.tenant_id,
                    action="stock.transfer",
                    resource="inventory",
                    resource_id=batch_key,
                )
            await s.commit()
        return replayed

    async def levels(
        self, p: Principal, *, variant_id: uuid.UUID | None, limit: int, offset: int
    ) -> list[StockBalance]:
        p.require("inventory", Action.read)
        stmt = select(StockBalance)
        if variant_id:
            stmt = stmt.where(StockBalance.variant_id == variant_id)
        async with self._tx(p) as s:
            return list(
                (
                    await s.execute(
                        stmt.order_by(StockBalance.variant_id, StockBalance.bin_id)
                        .limit(limit)
                        .offset(offset)
                    )
                ).scalars()
            )

    async def movements(
        self, p: Principal, *, variant_id: uuid.UUID | None, limit: int, offset: int
    ) -> list[StockMovement]:
        p.require("inventory", Action.read)
        stmt = select(StockMovement)
        if variant_id:
            stmt = stmt.where(StockMovement.variant_id == variant_id)
        async with self._tx(p) as s:
            return list(
                (
                    await s.execute(stmt.order_by(StockMovement.id.desc()).limit(limit).offset(offset))
                ).scalars()
            )

    # -- purchase orders ------------------------------------------------------------------------

    async def create_po(
        self,
        p: Principal,
        *,
        supplier_name: str,
        warehouse_id: uuid.UUID,
        currency: str,
        items: list[tuple[uuid.UUID, int, Decimal]],
        notes: str | None,
        ctx: AuditContext,
    ) -> AppPurchaseOrder:
        p.require("purchasing", Action.create)
        if len({v for v, _, _ in items}) != len(items):
            raise ValidationFailed("each variant may appear once per purchase order")
        async with self._tx(p) as s:
            await _get(s, AppWarehouse, warehouse_id)
            po = AppPurchaseOrder(
                tenant_id=p.tenant_id,
                number=new_number("PO"),
                supplier_name=supplier_name,
                warehouse_id=warehouse_id,
                currency=currency,
                notes=notes,
                created_by=p.user_id,
            )
            s.add(po)
            await s.flush()
            s.add_all(
                PoItem(tenant_id=p.tenant_id, po_id=po.id, variant_id=v, qty_ordered=q, unit_cost=c)
                for v, q, c in items
            )
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="po.create",
                resource="purchasing",
                resource_id=str(po.id),
            )
            await s.commit()
        return po

    async def get_po(self, p: Principal, po_id: uuid.UUID) -> tuple[AppPurchaseOrder, list[PoItem]]:
        p.require("purchasing", Action.read)
        async with self._tx(p) as s:
            po = await _get(s, AppPurchaseOrder, po_id)
            items = (await s.execute(select(PoItem).where(PoItem.po_id == po_id))).scalars().all()
        return po, list(items)

    _PO_TRANSITIONS = {
        PurchaseOrderStatus.draft: {PurchaseOrderStatus.submitted, PurchaseOrderStatus.cancelled},
        PurchaseOrderStatus.submitted: {PurchaseOrderStatus.cancelled},
    }

    async def set_po_status(
        self, p: Principal, po_id: uuid.UUID, to: PurchaseOrderStatus, ctx: AuditContext
    ) -> AppPurchaseOrder:
        p.require("purchasing", Action.update)
        async with self._tx(p) as s:
            po: AppPurchaseOrder = await _get(s, AppPurchaseOrder, po_id, lock=True)
            if to not in self._PO_TRANSITIONS.get(po.status, set()):
                raise Conflict(f"Cannot move a {po.status.value} purchase order to {to.value}.")
            old, po.status = po.status, to
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="po.status",
                resource="purchasing",
                resource_id=str(po_id),
                detail={"from": old.value, "to": to.value},
            )
            await s.commit()
        return po

    async def receive_po(
        self, p: Principal, po_id: uuid.UUID, *, batch_key: str, lines: list[ReceiveLine], ctx: AuditContext
    ) -> tuple[AppPurchaseOrder, bool]:
        """Receive (part of) a submitted PO into bins of its warehouse. Idempotent per batch_key."""
        p.require("purchasing", Action.manage)
        async with self._tx(p) as s:
            po: AppPurchaseOrder = await _get(s, AppPurchaseOrder, po_id, lock=True)
            items = {
                i.id: i
                for i in (
                    await s.execute(select(PoItem).where(PoItem.po_id == po_id).with_for_update())
                ).scalars()
            }
            key = f"po:{po_id}:receive:{batch_key}"
            if (
                await s.execute(select(StockMovement.id).where(StockMovement.batch_key == key).limit(1))
            ).first():
                return po, True  # already applied; no further checks needed
            if po.status not in (PurchaseOrderStatus.submitted, PurchaseOrderStatus.partially_received):
                raise Conflict("Only submitted purchase orders can be received.")
            bins = {
                b.id: b
                for b in (
                    await s.execute(
                        select(WarehouseBin).where(WarehouseBin.id.in_({ln.bin_id for ln in lines}))
                    )
                ).scalars()
            }
            movement_lines = []
            for ln in lines:
                item = items.get(ln.po_item_id)
                b = bins.get(ln.bin_id)
                if item is None or b is None or b.warehouse_id != po.warehouse_id:
                    raise ValidationFailed("Unknown PO item, or bin not in the PO's warehouse.")
                if ln.qty <= 0 or item.qty_received + ln.qty > item.qty_ordered:
                    raise ValidationFailed(
                        "Received quantity exceeds the outstanding quantity.",
                        details={"po_item_id": str(item.id)},
                    )
                item.qty_received += ln.qty
                movement_lines.append(
                    ledger.Line(
                        item.variant_id,
                        ln.bin_id,
                        MovementKind.receipt,
                        ln.qty,
                        reference_type="purchase_order",
                        reference_id=po.id,
                    )
                )
            replayed = await ledger.post(
                s, tenant_id=p.tenant_id, actor_id=p.user_id, batch_key=key, lines=movement_lines
            )
            po.status = (
                PurchaseOrderStatus.received
                if all(i.qty_received == i.qty_ordered for i in items.values())
                else PurchaseOrderStatus.partially_received
            )
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="po.receive",
                resource="purchasing",
                resource_id=str(po_id),
                detail={"lines": len(lines), "status": po.status.value},
            )
            await s.commit()
        return po, replayed
