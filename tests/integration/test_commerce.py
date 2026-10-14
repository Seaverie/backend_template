"""Inventory ledger, orders, purchase orders, returns, payments (sandbox) and analytics, end to end."""

from __future__ import annotations

import asyncio
import json
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

import pytest
from httpx import AsyncClient
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError

from app.container import Container
from app.integrations.payments import sign_payload
from tests.integration.conftest import P, TenantCtx, auth

NewTenant = Callable[..., Awaitable[TenantCtx]]
NewUser = Callable[..., Awaitable[tuple[str, str]]]
WHSEC = "sandbox_webhook_secret_0123456789abcdef"


@dataclass
class Shop:
    t: TenantCtx
    warehouse: str
    bin_a: str
    bin_b: str
    product: str
    variant: str
    price: str


def idem() -> dict[str, str]:
    return {"Idempotency-Key": f"k-{uuid.uuid4()}"}


@pytest.fixture
def make_shop(client: AsyncClient, new_tenant: NewTenant) -> Callable[..., Awaitable[Shop]]:
    async def make(stock: int = 10, price: str = "25.00") -> Shop:
        t = await new_tenant()
        r = await client.post(f"{P}/warehouses", headers=t.h, json={"code": "MAIN", "name": "Main"})
        assert r.status_code == 201, r.text
        wh = r.json()["id"]
        bins = []
        for code in ("A1", "B1"):
            r = await client.post(f"{P}/warehouses/{wh}/bins", headers=t.h, json={"code": code})
            assert r.status_code == 201, r.text
            bins.append(r.json()["id"])
        r = await client.post(
            f"{P}/products", headers=t.h,
            json={"name": "Oak Chair", "description": "Solid oak dining chair", "status": "active", "is_public": True,
                  "metadata": {"material": "oak", "color": "natural"},
                  "variants": [{"sku": "CHAIR-OAK", "name": "Oak chair", "price": price, "currency": "USD"}]},
        )
        assert r.status_code == 201, r.text
        prod, var = r.json()["id"], r.json()["variants"][0]["id"]
        if stock:
            r = await client.post(
                f"{P}/stock/adjustments", headers=t.h,
                json={"batch_key": f"init-{uuid.uuid4()}", "lines": [{"variant_id": var, "bin_id": bins[0], "delta": stock}]},
            )
            assert r.status_code == 200, r.text
        return Shop(t, wh, bins[0], bins[1], prod, var, price)

    return make


async def levels(client: AsyncClient, s: Shop) -> dict[str, int]:
    rows = (await client.get(f"{P}/stock/levels", headers=s.t.h, params={"variant_id": s.variant})).json()["items"]
    return {k: sum(r[k] for r in rows) for k in ("on_hand", "reserved", "available")}


async def checkout(client: AsyncClient, s: Shop, qty: int, headers: dict[str, str] | None = None) -> Any:
    return await client.post(
        f"{P}/orders", headers={**s.t.h, **(headers if headers is not None else idem())}, json={"items": [{"variant_id": s.variant, "qty": qty}]}
    )


# -- ledger ---------------------------------------------------------------------------------------------


async def test_adjustments_are_idempotent_and_validated(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop(stock=0)
    body = {"batch_key": "count-2026-10-14", "lines": [{"variant_id": s.variant, "bin_id": s.bin_a, "delta": 7}]}
    r1 = await client.post(f"{P}/stock/adjustments", headers=s.t.h, json=body)
    r2 = await client.post(f"{P}/stock/adjustments", headers=s.t.h, json=body)
    assert r1.json() == {"applied": True, "replayed": False}
    assert r2.json() == {"applied": False, "replayed": True}
    assert (await levels(client, s))["on_hand"] == 7
    body["lines"][0]["delta"] = 8  # same key, different content
    assert (await client.post(f"{P}/stock/adjustments", headers=s.t.h, json=body)).status_code == 409
    neg = {"batch_key": "shrink-0001", "lines": [{"variant_id": s.variant, "bin_id": s.bin_a, "delta": -8}]}
    r = await client.post(f"{P}/stock/adjustments", headers=s.t.h, json=neg)
    assert r.status_code == 409 and r.json()["error"]["code"] == "insufficient_stock"
    assert (await levels(client, s))["on_hand"] == 7


async def test_ledger_is_append_only(container: Container, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop()
    for stmt in ("UPDATE stock_movements SET delta_on_hand = 1000", "DELETE FROM stock_movements"):
        with pytest.raises(DBAPIError):
            async with container.db.tenant_session(uuid.UUID(s.t.id)) as sess:
                await sess.execute(text(stmt))
    # The CHECK constraint backs the application check: balances can never go negative.
    with pytest.raises(DBAPIError):
        async with container.db.tenant_session(uuid.UUID(s.t.id)) as sess:
            await sess.execute(text("UPDATE stock_balances SET reserved = on_hand + 1"))


async def test_no_oversell_under_concurrent_checkout(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop(stock=5)
    results = await asyncio.gather(*[checkout(client, s, 1) for _ in range(20)])
    codes = sorted(r.status_code for r in results)
    assert codes.count(201) == 5, codes
    assert all(c == 409 for c in codes if c != 201)
    assert await levels(client, s) == {"on_hand": 5, "reserved": 5, "available": 0}


async def test_concurrent_adjustments_cannot_go_negative(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop(stock=5)
    bodies = [
        {"batch_key": f"dmg-{i}-{uuid.uuid4().hex[:6]}", "lines": [{"variant_id": s.variant, "bin_id": s.bin_a, "delta": -3}]}
        for i in range(4)
    ]
    rs = await asyncio.gather(*[client.post(f"{P}/stock/adjustments", headers=s.t.h, json=b) for b in bodies])
    assert sorted(r.status_code for r in rs) == [200, 409, 409, 409]
    assert (await levels(client, s))["on_hand"] == 2


async def test_transfer_moves_only_available_stock(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop(stock=5)
    assert (await checkout(client, s, 4)).status_code == 201  # 4 of 5 reserved in bin A
    t = {"batch_key": f"mv-{uuid.uuid4()}", "variant_id": s.variant, "from_bin_id": s.bin_a, "to_bin_id": s.bin_b, "qty": 2}
    assert (await client.post(f"{P}/stock/transfers", headers=s.t.h, json=t)).status_code == 409
    t.update(qty=1, batch_key=f"mv-{uuid.uuid4()}")
    assert (await client.post(f"{P}/stock/transfers", headers=s.t.h, json=t)).status_code == 200
    assert await levels(client, s) == {"on_hand": 5, "reserved": 4, "available": 1}


# -- orders -----------------------------------------------------------------------------------------------


async def test_order_lifecycle_and_stock_effects(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop(stock=10, price="25.00")
    r = await checkout(client, s, 3)
    assert r.status_code == 201, r.text
    order = r.json()
    assert order["total"] == "75.00" and order["status"] == "pending" and order["stock_state"] == "reserved"
    assert await levels(client, s) == {"on_hand": 10, "reserved": 3, "available": 7}
    oid = order["id"]
    tr = f"{P}/orders/{oid}/transitions"
    assert (await client.post(tr, headers=s.t.h, json={"to": "shipped"})).status_code == 409  # not in the graph
    assert (await client.post(tr, headers=s.t.h, json={"to": "paid"})).status_code == 200
    r = await client.post(tr, headers=s.t.h, json={"to": "shipped"})
    assert r.status_code == 200 and r.json()["stock_state"] == "committed"
    assert await levels(client, s) == {"on_hand": 7, "reserved": 0, "available": 7}
    assert (await client.post(tr, headers=s.t.h, json={"to": "cancelled"})).status_code == 409
    hist = (await client.get(f"{P}/orders/{oid}", headers=s.t.h)).json()["history"]
    assert [h["to_status"] for h in hist] == ["pending", "paid", "shipped"]
    # Cancelling a pending order releases its reservation.
    r = await checkout(client, s, 2)
    assert (await client.post(f"{P}/orders/{r.json()['id']}/transitions", headers=s.t.h, json={"to": "cancelled"})).status_code == 200
    assert await levels(client, s) == {"on_hand": 7, "reserved": 0, "available": 7}


async def test_checkout_idempotency_and_server_side_prices(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop(stock=10, price="9.99")
    assert (await checkout(client, s, 1, headers={})).status_code == 422  # key required
    h = idem()
    r1, r2 = await checkout(client, s, 2, h), await checkout(client, s, 2, h)
    assert r1.json()["id"] == r2.json()["id"] and r2.headers.get("idempotent-replayed") == "true"
    assert (await levels(client, s))["reserved"] == 2
    r = await client.post(
        f"{P}/orders", headers={**s.t.h, **idem()},
        json={"items": [{"variant_id": s.variant, "qty": 1, "unit_price": "0.01"}]},
    )
    assert r.status_code == 422  # clients cannot set prices
    assert r1.json()["total"] == "19.98"


async def test_checkout_rejects_inactive_and_foreign_variants(
    client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]
) -> None:
    a, b = await make_shop(), await make_shop()
    r = await client.post(f"{P}/orders", headers={**a.t.h, **idem()}, json={"items": [{"variant_id": b.variant, "qty": 1}]})
    assert r.status_code == 422  # tenant B's variant is invisible to A
    await client.patch(f"{P}/products/{a.product}", headers=a.t.h, json={"status": "archived"})
    assert (await checkout(client, a, 1)).status_code == 422


async def test_custom_lifecycle_validation_and_ship_without_reservation(
    client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]
) -> None:
    s = await make_shop(stock=4)
    bad = [
        {"statuses": [{"code": "a", "label": "A", "is_initial": True}, {"code": "b", "label": "B", "is_initial": True}], "transitions": []},
        {"statuses": [{"code": "a", "label": "A", "is_initial": True, "stock_effect": "commit"}], "transitions": []},
        {"statuses": [{"code": "a", "label": "A", "is_initial": True}, {"code": "z", "label": "Z", "is_terminal": True}],
         "transitions": [["z", "a"]]},
        {"statuses": [{"code": "a", "label": "A", "is_initial": True}], "transitions": [["a", "nope"]]},
    ]
    for body in bad:
        assert (await client.put(f"{P}/order-lifecycle", headers=s.t.h, json=body)).status_code == 422, body
    good = {
        "statuses": [
            {"code": "new", "label": "New", "is_initial": True},
            {"code": "dispatched", "label": "Dispatched", "stock_effect": "commit"},
            {"code": "void", "label": "Void", "is_terminal": True, "is_sale": False},
        ],
        "transitions": [["new", "dispatched"], ["new", "void"]],
    }
    assert (await client.put(f"{P}/order-lifecycle", headers=s.t.h, json=good)).status_code == 204
    r = await checkout(client, s, 3)
    assert r.json()["status"] == "new" and r.json()["stock_state"] == "none"
    assert (await levels(client, s))["reserved"] == 0
    r = await client.post(f"{P}/orders/{r.json()['id']}/transitions", headers=s.t.h, json={"to": "dispatched"})
    assert r.json()["stock_state"] == "committed"
    assert await levels(client, s) == {"on_hand": 1, "reserved": 0, "available": 1}
    # Shipping more than available fails atomically (status and stock unchanged).
    r = await checkout(client, s, 2)
    oid = r.json()["id"]
    assert (await client.post(f"{P}/orders/{oid}/transitions", headers=s.t.h, json={"to": "dispatched"})).status_code == 409
    assert (await client.get(f"{P}/orders/{oid}", headers=s.t.h)).json()["status"] == "new"


async def test_order_permissions(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]], new_user: NewUser) -> None:
    s = await make_shop()
    r = await client.post(f"{P}/roles", headers=s.t.h, json={"name": "viewer", "permissions": [{"resource": "orders", "action": "read"}]})
    _, tok = await new_user(s.t, [r.json()["id"]])
    assert (await client.get(f"{P}/orders", headers=auth(tok))).status_code == 200
    r = await client.post(f"{P}/orders", headers={**auth(tok), **idem()}, json={"items": [{"variant_id": s.variant, "qty": 1}]})
    assert r.status_code == 403
    assert (await client.post(f"{P}/stock/adjustments", headers=auth(tok), json={
        "batch_key": "x" * 10, "lines": [{"variant_id": s.variant, "bin_id": s.bin_a, "delta": 1}]})).status_code == 403
    assert (await client.put(f"{P}/order-lifecycle", headers=auth(tok), json={"statuses": [], "transitions": []})).status_code in (403, 422)


# -- purchase orders ------------------------------------------------------------------------------------


async def test_purchase_order_receiving(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop(stock=0)
    r = await client.post(f"{P}/purchase-orders", headers=s.t.h, json={
        "supplier_name": "Woodworks", "warehouse_id": s.warehouse, "currency": "USD",
        "items": [{"variant_id": s.variant, "qty": 10, "unit_cost": "12.00"}]})
    assert r.status_code == 201, r.text
    po, item = r.json()["id"], r.json()["items"][0]["id"]
    rec = f"{P}/purchase-orders/{po}/receive"
    body = {"batch_key": "delivery-0001", "lines": [{"po_item_id": item, "qty": 4, "bin_id": s.bin_a}]}
    assert (await client.post(rec, headers=s.t.h, json=body)).status_code == 409  # still draft
    assert (await client.post(f"{P}/purchase-orders/{po}/status", headers=s.t.h, json={"status": "submitted"})).status_code == 200
    r = await client.post(rec, headers=s.t.h, json=body)
    assert r.json()["status"] == "partially_received" and r.json()["items"][0]["qty_received"] == 4
    r = await client.post(rec, headers=s.t.h, json=body)  # same delivery replayed
    assert r.json()["replayed"] is True and r.json()["items"][0]["qty_received"] == 4
    over = {"batch_key": "delivery-0002", "lines": [{"po_item_id": item, "qty": 7, "bin_id": s.bin_a}]}
    assert (await client.post(rec, headers=s.t.h, json=over)).status_code == 422
    other = await client.post(f"{P}/warehouses", headers=s.t.h, json={"code": "OTHER", "name": "Other"})
    ob = (await client.post(f"{P}/warehouses/{other.json()['id']}/bins", headers=s.t.h, json={"code": "X"})).json()["id"]
    wrong_bin = {"batch_key": "delivery-0003", "lines": [{"po_item_id": item, "qty": 1, "bin_id": ob}]}
    assert (await client.post(rec, headers=s.t.h, json=wrong_bin)).status_code == 422
    rest = {"batch_key": "delivery-0004", "lines": [{"po_item_id": item, "qty": 6, "bin_id": s.bin_b}]}
    assert (await client.post(rec, headers=s.t.h, json=rest)).json()["status"] == "received"
    assert (await levels(client, s))["on_hand"] == 10
    moves = (await client.get(f"{P}/stock/movements", headers=s.t.h, params={"variant_id": s.variant})).json()["items"]
    assert sorted(m["delta_on_hand"] for m in moves if m["kind"] == "receipt") == [4, 6]


# -- payments (sandbox provider) -------------------------------------------------------------------------


async def make_gateway(client: AsyncClient, t: TenantCtx) -> str:
    r = await client.post(f"{P}/payment-gateways", headers=t.h, json={
        "name": "sandbox", "provider": "sandbox", "secrets": {"webhook_secret": WHSEC}, "is_default": True})
    assert r.status_code == 201, r.text
    assert "secrets" not in r.json() and "secrets_enc" not in r.json() and WHSEC not in r.text
    return str(r.json()["id"])


async def send_webhook(client: AsyncClient, gw: str, event: dict[str, Any], secret: str = WHSEC) -> Any:
    body = json.dumps(event).encode()
    return await client.post(
        f"{P}/webhooks/payments/{gw}", content=body,
        headers={"X-Sandbox-Signature": sign_payload(body, secret), "Content-Type": "application/json"},
    )


async def paid_shipped_order(client: AsyncClient, s: Shop, gw: str, qty: int) -> tuple[str, dict[str, Any]]:
    oid = (await checkout(client, s, qty)).json()["id"]
    pay = (await client.post(f"{P}/orders/{oid}/payments", headers={**s.t.h, **idem()}, json={})).json()
    r = await send_webhook(client, gw, {"id": f"evt_{uuid.uuid4().hex}", "type": "payment.succeeded", "payment_ref": pay["provider_ref"]})
    assert r.status_code == 200, r.text
    for to in ("paid", "shipped"):
        assert (await client.post(f"{P}/orders/{oid}/transitions", headers=s.t.h, json={"to": to})).status_code == 200
    return oid, pay


async def test_payment_flow_and_webhook_security(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]], container: Container) -> None:
    s = await make_shop(price="40.00")
    gw = await make_gateway(client, s.t)
    oid = (await checkout(client, s, 2)).json()["id"]
    h = idem()
    r1 = await client.post(f"{P}/orders/{oid}/payments", headers={**s.t.h, **h}, json={})
    r2 = await client.post(f"{P}/orders/{oid}/payments", headers={**s.t.h, **h}, json={})
    assert r1.status_code == 201 and r1.json()["id"] == r2.json()["id"]
    pay = r1.json()
    assert pay["amount"] == "80.00" and pay["status"] == "pending"
    evt = {"id": "evt_0001", "type": "payment.succeeded", "payment_ref": pay["provider_ref"]}
    # Forged / tampered / unsigned webhooks are rejected and change nothing.
    assert (await send_webhook(client, gw, evt, secret="wrong_secret_wrong_secret_xx")).status_code == 422
    body = json.dumps(evt).encode()
    hdr = sign_payload(body, WHSEC)
    tampered = body.replace(b"evt_0001", b"evt_0002")
    assert (await client.post(f"{P}/webhooks/payments/{gw}", content=tampered, headers={"X-Sandbox-Signature": hdr})).status_code == 422
    assert (await client.post(f"{P}/webhooks/payments/{gw}", content=body)).status_code == 422
    assert (await client.post(f"{P}/webhooks/payments/{uuid.uuid4()}", content=body, headers={"X-Sandbox-Signature": hdr})).status_code == 404
    assert (await client.get(f"{P}/orders/{oid}", headers=s.t.h)).json()["amount_paid"] == "0.00"
    r = await send_webhook(client, gw, evt)
    assert r.json() == {"received": True, "duplicate": False}
    assert (await send_webhook(client, gw, evt)).json()["duplicate"] is True
    assert (await client.get(f"{P}/orders/{oid}", headers=s.t.h)).json()["amount_paid"] == "80.00"
    r = await client.post(f"{P}/orders/{oid}/payments", headers={**s.t.h, **idem()}, json={})
    assert r.status_code == 409  # fully paid
    # Secrets are stored encrypted, never in plaintext.
    async with container.db.tenant_session(uuid.UUID(s.t.id)) as sess:
        enc = (await sess.execute(text("SELECT secrets_enc FROM app_payment_gateways"))).scalar_one()
    assert WHSEC not in enc and json.loads(enc)["p"] == "local"


async def test_gateway_secret_validation(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop()
    for body in (
        {"name": "x", "provider": "stripe", "secrets": {"api_key": "nope", "webhook_secret": "whsec_abcdefghijk"}},
        {"name": "x", "provider": "stripe", "secrets": {"api_key": "sk_test_abcdefghijk"}},
        {"name": "x", "provider": "paypal", "secrets": {}},
        {"name": "x", "provider": "sandbox", "secrets": {"webhook_secret": WHSEC}, "public_config": {"url": {"x": 1}}},
    ):
        r = await client.post(f"{P}/payment-gateways", headers=s.t.h, json=body)
        assert r.status_code == 422, (body, r.text)


# -- returns ----------------------------------------------------------------------------------------------


async def test_rma_full_flow(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]], container: Container) -> None:
    s = await make_shop(stock=10, price="30.00")
    gw = await make_gateway(client, s.t)
    pending_oid = (await checkout(client, s, 1)).json()["id"]
    item_pending = (await client.get(f"{P}/orders/{pending_oid}", headers=s.t.h)).json()["items"][0]["id"]
    r = await client.post(f"{P}/orders/{pending_oid}/returns", headers=s.t.h,
                          json={"items": [{"order_item_id": item_pending, "qty": 1}], "reason": "x"})
    assert r.status_code == 409  # not shipped

    oid, _ = await paid_shipped_order(client, s, gw, 3)
    item = (await client.get(f"{P}/orders/{oid}", headers=s.t.h)).json()["items"][0]["id"]
    ret = f"{P}/orders/{oid}/returns"
    assert (await client.post(ret, headers=s.t.h, json={"items": [{"order_item_id": item, "qty": 4}], "reason": "too many"})).status_code == 422
    r = await client.post(ret, headers=s.t.h, json={"items": [{"order_item_id": item, "qty": 2}], "reason": "damaged"})
    assert r.status_code == 201
    rma = r.json()["id"]
    # Open RMA quantities count against what can still be returned.
    assert (await client.post(ret, headers=s.t.h, json={"items": [{"order_item_id": item, "qty": 2}], "reason": "again"})).status_code == 422
    act = f"{P}/returns/{rma}/actions"
    assert (await client.post(act, headers=s.t.h, json={"action": "receive"})).status_code == 409  # must approve first
    assert (await client.post(act, headers=s.t.h, json={"action": "approve"})).status_code == 200
    assert (await client.post(act, headers=s.t.h, json={"action": "receive"})).status_code == 422  # restock bin required
    before = await levels(client, s)
    r = await client.post(act, headers=s.t.h, json={"action": "receive", "restock_bin_id": s.bin_b})
    assert r.json()["status"] == "received"
    assert (await levels(client, s))["on_hand"] == before["on_hand"] + 2
    assert (await client.post(act, headers=s.t.h, json={"action": "refund", "refund_amount": "60.01"})).status_code == 422
    r = await client.post(act, headers=s.t.h, json={"action": "refund", "refund_amount": "60.00"})
    assert r.status_code == 200 and r.json()["status"] == "refunded"
    order = (await client.get(f"{P}/orders/{oid}", headers=s.t.h)).json()
    assert order["amount_refunded"] == "60.00" and order["items"][0]["qty_returned"] == 2
    # A second refund attempt is refused by the state machine...
    assert (await client.post(act, headers=s.t.h, json={"action": "refund", "refund_amount": "60.00"})).status_code == 409
    # ...and replaying the payment-level refund with the same key does not pay out again.
    from app.services.authz import FULL_GRANT, PermissionMatrix, Principal
    from app.services.audit import AuditContext

    principal = Principal("user", uuid.UUID(s.t.id), uuid.uuid4(), uuid.uuid4(), None, PermissionMatrix(True, True, {}))
    await container.payments.refund_order(principal, uuid.UUID(oid), Decimal("60.00"), idempotency_key=f"rma:{rma}",
                                          ctx=AuditContext("system", None, None, None))
    assert (await client.get(f"{P}/orders/{oid}", headers=s.t.h)).json()["amount_refunded"] == "60.00"
    assert FULL_GRANT is not None
    events = (await client.get(f"{P}/returns/{rma}", headers=s.t.h)).json()["events"]
    assert [e["to_status"] for e in events] == ["requested", "approved", "received", "refunded"]
    assert (await client.post(act, headers=s.t.h, json={"action": "close"})).json()["status"] == "closed"


# -- analytics & catalog ----------------------------------------------------------------------------------


async def test_sales_analytics(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop(stock=20, price="10.00")
    for qty in (1, 2):
        assert (await checkout(client, s, qty)).status_code == 201
    cancelled = (await checkout(client, s, 5)).json()["id"]
    await client.post(f"{P}/orders/{cancelled}/transitions", headers=s.t.h, json={"to": "cancelled"})
    now = datetime.now(UTC)
    params = {"from": (now - timedelta(days=1)).isoformat(), "to": (now + timedelta(days=1)).isoformat()}
    r = await client.get(f"{P}/analytics/sales", headers=s.t.h, params={**params, "group_by": "day"})
    assert r.status_code == 200, r.text
    rows = r.json()["items"]
    assert sum(x["orders"] for x in rows) == 2 and sum(Decimal(x["revenue"]) for x in rows) == Decimal("30.00")
    r = await client.get(f"{P}/analytics/sales", headers=s.t.h, params={**params, "group_by": "product"})
    assert r.json()["items"][0]["key"] == "CHAIR-OAK" and r.json()["items"][0]["units"] == 3
    other = await make_shop()
    assert (await client.get(f"{P}/analytics/sales", headers=other.t.h, params=params)).json()["items"] == []
    too_long = {"from": (now - timedelta(days=400)).isoformat(), "to": now.isoformat()}
    assert (await client.get(f"{P}/analytics/sales", headers=s.t.h, params=too_long)).status_code == 422
    naive = {"from": "2026-01-01T00:00:00", "to": "2026-02-01T00:00:00"}
    assert (await client.get(f"{P}/analytics/sales", headers=s.t.h, params=naive)).status_code == 422


async def test_product_search_and_public_catalog(client: AsyncClient, make_shop: Callable[..., Awaitable[Shop]]) -> None:
    s = await make_shop()
    await client.post(f"{P}/products", headers=s.t.h, json={
        "name": "Secret prototype", "status": "active", "is_public": False, "metadata": {"material": "oak"},
        "variants": [{"sku": "PROTO", "name": "Proto", "price": "1.00", "currency": "USD"}]})
    r = await client.get(f"{P}/products", headers=s.t.h, params={"q": "dining"})
    assert [x["name"] for x in r.json()["items"]] == ["Oak Chair"]
    r = await client.get(f"{P}/products", headers=s.t.h, params={"metadata": json.dumps({"material": "oak"})})
    assert len(r.json()["items"]) == 2
    assert (await client.get(f"{P}/products", headers=s.t.h, params={"metadata": "[1]"})).status_code == 422
    pub = await client.get(f"{P}/catalog/{s.t.slug}/products")
    assert pub.status_code == 200
    names = [x["name"] for x in pub.json()["items"]]
    assert names == ["Oak Chair"]
    assert "metadata" not in pub.json()["items"][0] and "is_public" not in pub.json()["items"][0]
    assert (await client.get(f"{P}/catalog/no-such-shop/products")).status_code == 404
