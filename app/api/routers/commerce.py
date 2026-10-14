"""Orders, order lifecycle configuration, returns (RMA), payments, and sales analytics."""

from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any, Literal

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field

from app.api.deps import audit_ctx, container, rate_limited
from app.api.serialize import pick
from app.container import Container
from app.models import StockEffect
from app.services.authz import Principal
from app.services.orders import CartLine, StatusSpec
from app.services.rma import RmaLine

router = APIRouter(tags=["commerce"])


class CartLineIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    variant_id: uuid.UUID
    qty: int = Field(..., gt=0, le=10_000)


class CheckoutIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    items: list[CartLineIn] = Field(..., min_length=1, max_length=100)
    customer: dict[str, Any] = Field(default_factory=dict)


class TransitionIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    to: str = Field(..., pattern=r"^[a-z][a-z0-9_]{0,31}$")
    note: str | None = Field(None, max_length=500)


class StatusIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Field(..., pattern=r"^[a-z][a-z0-9_]{0,31}$")
    label: str = Field(..., min_length=1, max_length=100)
    is_initial: bool = False
    is_terminal: bool = False
    stock_effect: StockEffect = StockEffect.none
    is_sale: bool = True


class LifecycleIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    statuses: list[StatusIn] = Field(..., min_length=1, max_length=30)
    transitions: list[tuple[str, str]] = Field(..., max_length=300)


class RmaLineIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    order_item_id: uuid.UUID
    qty: int = Field(..., gt=0, le=10_000)
    restock: bool = True


class RmaIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    items: list[RmaLineIn] = Field(..., min_length=1, max_length=100)
    reason: str = Field(..., min_length=1, max_length=500)


class RmaActionIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    action: Literal["approve", "reject", "receive", "refund", "close"]
    note: str | None = Field(None, max_length=2000)
    restock_bin_id: uuid.UUID | None = None
    refund_amount: Decimal | None = Field(None, gt=0, max_digits=14, decimal_places=2)


class PaymentIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    gateway_id: uuid.UUID | None = None


def _order(o: Any, status: str) -> dict[str, Any]:
    return {
        **pick(o, "id", "number", "stock_state", "currency", "total", "amount_paid", "amount_refunded", "customer",
               "placed_at"),
        "status": status,
    }


# -- lifecycle ----------------------------------------------------------------------------------------


@router.get("/order-lifecycle")
async def get_lifecycle(p: Principal = Depends(rate_limited()), c: Container = Depends(container)) -> dict[str, Any]:
    statuses, transitions = await c.orders.list_statuses(p)
    return {
        "statuses": [pick(x, "code", "label", "is_initial", "is_terminal", "stock_effect", "is_sale") for x in statuses],
        "transitions": [list(t) for t in transitions],
    }


@router.put("/order-lifecycle", status_code=204)
async def put_lifecycle(
    body: LifecycleIn, request: Request, p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> None:
    await c.orders.configure_lifecycle(
        p,
        [StatusSpec(x.code, x.label, x.is_initial, x.is_terminal, x.stock_effect, x.is_sale) for x in body.statuses],
        [(a, b) for a, b in body.transitions],
        audit_ctx(request, p),
    )


# -- orders ---------------------------------------------------------------------------------------------


@router.post("/orders", status_code=201)
async def checkout(
    body: CheckoutIn, request: Request, p: Principal = Depends(rate_limited("orders.write")),
    c: Container = Depends(container),
) -> Response:
    """Place an order. ``Idempotency-Key`` is required: a retried checkout never creates a second order."""

    async def handler() -> Response:
        order = await c.orders.checkout(
            p, lines=[CartLine(x.variant_id, x.qty) for x in body.items], customer=body.customer,
            ctx=audit_ctx(request, p),
        )
        detail = await c.orders.get(p, order.id)
        return JSONResponse(
            {**_order(detail["order"], detail["status"]),
             "items": [pick(i, "id", "variant_id", "sku", "name", "qty", "unit_price", "line_total") for i in detail["items"]]},
            status_code=201,
        )

    return await c.idempotency.run(request, p, required=True, handler=handler)


@router.get("/orders")
async def list_orders(
    status: str | None = Query(None, pattern=r"^[a-z][a-z0-9_]{0,31}$"),
    limit: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0, le=10_000),
    p: Principal = Depends(rate_limited()), c: Container = Depends(container),
) -> dict[str, Any]:
    return {"items": [_order(o, code) for o, code in await c.orders.list_orders(p, status=status, limit=limit, offset=offset)]}


@router.get("/orders/{order_id}")
async def get_order(order_id: uuid.UUID, p: Principal = Depends(rate_limited()), c: Container = Depends(container)) -> dict[str, Any]:
    d = await c.orders.get(p, order_id)
    return {
        **_order(d["order"], d["status"]),
        "items": [pick(i, "id", "variant_id", "sku", "name", "qty", "unit_price", "line_total", "qty_returned") for i in d["items"]],
        "history": [pick(h, "from_status", "to_status", "actor_id", "note", "created_at") for h in d["history"]],
    }


@router.post("/orders/{order_id}/transitions")
async def transition_order(
    order_id: uuid.UUID, body: TransitionIn, request: Request, p: Principal = Depends(rate_limited("orders.write")),
    c: Container = Depends(container),
) -> Response:
    async def handler() -> Response:
        await c.orders.transition(p, order_id, body.to, body.note, audit_ctx(request, p))
        d = await c.orders.get(p, order_id)
        return JSONResponse(_order(d["order"], d["status"]))

    return await c.idempotency.run(request, p, required=False, handler=handler)


@router.post("/orders/{order_id}/payments", status_code=201)
async def start_payment(
    order_id: uuid.UUID, body: PaymentIn, request: Request, p: Principal = Depends(rate_limited("payments.write")),
    c: Container = Depends(container),
) -> Response:
    """Start a payment with the tenant's (default or chosen) gateway. ``Idempotency-Key`` is required and
    is forwarded to the provider, so retries reach the same provider payment."""

    async def handler() -> Response:
        pay, client_secret = await c.payments.start(
            p, order_id, gateway_id=body.gateway_id, idempotency_key=request.headers["Idempotency-Key"],
            ctx=audit_ctx(request, p),
        )
        return JSONResponse(
            {**pick(pay, "id", "order_id", "gateway_id", "provider_ref", "amount", "currency", "status"),
             "client_secret": client_secret},
            status_code=201,
        )

    return await c.idempotency.run(request, p, required=True, handler=handler)


# -- RMA ------------------------------------------------------------------------------------------------


def _rma(d: dict[str, Any]) -> dict[str, Any]:
    return {
        **pick(d["rma"], "id", "number", "order_id", "status", "reason", "restock_bin_id", "refund_amount", "created_at"),
        "items": [pick(i, "order_item_id", "qty", "restock") for i in d["items"]],
        "events": [pick(e, "from_status", "to_status", "actor_id", "note", "detail", "created_at") for e in d["events"]],
    }


@router.post("/orders/{order_id}/returns", status_code=201)
async def create_rma(
    order_id: uuid.UUID, body: RmaIn, request: Request, p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    rma = await c.rma.create(
        p, order_id, lines=[RmaLine(x.order_item_id, x.qty, x.restock) for x in body.items], reason=body.reason,
        ctx=audit_ctx(request, p),
    )
    return _rma(await c.rma.get(p, rma.id))


@router.get("/returns/{rma_id}")
async def get_rma(rma_id: uuid.UUID, p: Principal = Depends(rate_limited()), c: Container = Depends(container)) -> dict[str, Any]:
    return _rma(await c.rma.get(p, rma_id))


@router.post("/returns/{rma_id}/actions")
async def rma_action(
    rma_id: uuid.UUID, body: RmaActionIn, request: Request, p: Principal = Depends(rate_limited("rma.write")),
    c: Container = Depends(container),
) -> dict[str, Any]:
    await c.rma.transition(
        p, rma_id, body.action, note=body.note, restock_bin_id=body.restock_bin_id, refund_amount=body.refund_amount,
        ctx=audit_ctx(request, p),
    )
    return _rma(await c.rma.get(p, rma_id))


# -- analytics ------------------------------------------------------------------------------------------


@router.get("/analytics/sales")
async def sales(
    date_from: datetime = Query(..., alias="from"),
    date_to: datetime = Query(..., alias="to"),
    group_by: Literal["day", "week", "month", "product"] = "day",
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0, le=10_000),
    p: Principal = Depends(rate_limited("analytics", 60)),
    c: Container = Depends(container),
) -> dict[str, Any]:
    rows = await c.analytics.sales(p, date_from=date_from, date_to=date_to, group_by=group_by, limit=limit, offset=offset)
    return {"group_by": group_by, "items": rows, "limit": limit, "offset": offset}
