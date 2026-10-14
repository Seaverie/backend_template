"""Outbox, webhooks, notifications, imports, tasks, QR codes, delivery pings, dead letters, maintenance.

Background jobs are invoked directly (the same functions Celery runs). Outbound HTTP is intercepted
with a mock transport and a fake resolver, so the SSRF guard is exercised with controlled DNS answers.
"""

from __future__ import annotations

import hashlib
import hmac
import io
import json
import uuid
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
import segno
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from httpx import AsyncClient
from sqlalchemy import text

from app.container import Container
from app.integrations.fcm import FcmSender
from app.integrations.http import SafeHttpClient
from app.workers import jobs
from tests.integration.conftest import P, TenantCtx, auth

NewTenant = Callable[..., Awaitable[TenantCtx]]
NewTable = Callable[..., Awaitable[str]]
NewUser = Callable[..., Awaitable[tuple[str, str]]]


class Net:
    """Fake network: DNS answers by host keyword, scripted HTTP responses, recorded requests."""

    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []
        self.responses: list[httpx.Response] = []
        self.dns: dict[str, list[str]] = {}

    async def resolve(self, host: str, port: int) -> list[str]:
        for key, addrs in self.dns.items():
            if key in host:
                return addrs
        return ["93.184.216.34"]

    def handler(self, req: httpx.Request) -> httpx.Response:
        self.requests.append(req)
        return self.responses.pop(0) if self.responses else httpx.Response(200, json={"ok": True})

    def client(self) -> SafeHttpClient:
        return SafeHttpClient(transport=httpx.MockTransport(self.handler), resolver=self.resolve)


@pytest.fixture
def net(container: Container) -> Any:
    n = Net()
    n.dns = {"internal": ["10.0.0.5"], "localhost": ["127.0.0.1"]}
    orig_w, orig_n = container.webhooks.http, container.notifications.http
    container.webhooks.http = n.client()
    container.notifications.http = n.client()
    yield n
    container.webhooks.http, container.notifications.http = orig_w, orig_n
    container.notifications._sender = None


async def order_shop(client: AsyncClient, t: TenantCtx, stock: int = 10) -> str:
    wh = (await client.post(f"{P}/warehouses", headers=t.h, json={"code": "W", "name": "W"})).json()["id"]
    b = (await client.post(f"{P}/warehouses/{wh}/bins", headers=t.h, json={"code": "B"})).json()["id"]
    v = (
        await client.post(
            f"{P}/products",
            headers=t.h,
            json={
                "name": "Mug",
                "status": "active",
                "variants": [{"sku": "MUG", "name": "Mug", "price": "8.00", "currency": "USD"}],
            },
        )
    ).json()["variants"][0]["id"]
    await client.post(
        f"{P}/stock/adjustments",
        headers=t.h,
        json={"batch_key": f"seed-{uuid.uuid4()}", "lines": [{"variant_id": v, "bin_id": b, "delta": stock}]},
    )
    return str(v)


async def place(client: AsyncClient, t: TenantCtx, variant: str, qty: int = 1) -> Any:
    return await client.post(
        f"{P}/orders",
        headers={**t.h, "Idempotency-Key": f"o-{uuid.uuid4()}"},
        json={"items": [{"variant_id": variant, "qty": qty}]},
    )


async def outbox(container: Container, t: TenantCtx) -> list[Any]:
    async with container.db.tenant_session(uuid.UUID(t.id)) as s:
        return list(
            (
                await s.execute(
                    text("SELECT event_type, payload, published_at FROM outbox_events ORDER BY created_at")
                )
            ).all()
        )


def verify_sig(body: bytes, header: str, secret: str) -> bool:
    parts = dict(p.split("=", 1) for p in header.split(","))
    expected = hmac.new(secret.encode(), f"{parts['t']}.".encode() + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, parts["v1"])


# -- outbox -------------------------------------------------------------------------------------------------


async def test_outbox_is_transactional(
    client: AsyncClient, container: Container, new_tenant: NewTenant
) -> None:
    t = await new_tenant()
    v = await order_shop(client, t, stock=1)
    assert (await place(client, t, v)).status_code == 201
    assert (await place(client, t, v)).status_code == 409  # out of stock: rolled back...
    events = [e.event_type for e in await outbox(container, t)]
    assert events == ["order.created"]  # ...and so is its event
    other = await new_tenant()
    assert await outbox(container, other) == []


# -- webhooks -----------------------------------------------------------------------------------------------


async def test_webhook_url_validation(client: AsyncClient, net: Net, new_tenant: NewTenant) -> None:
    t = await new_tenant()
    for url in (
        "http://hooks.example.com/x",
        "https://internal.corp/x",
        "https://localhost/x",
        "https://user:pw@hooks.example.com/x",
        "https://hooks.example.com:8080/x",
        "ftp://x.example.com",
    ):
        r = await client.post(
            f"{P}/webhooks", headers=t.h, json={"url": url, "event_types": ["order.created"]}
        )
        assert r.status_code == 422, url
    r = await client.post(
        f"{P}/webhooks", headers=t.h, json={"url": "https://hooks.example.com/x", "event_types": ["nope"]}
    )
    assert r.status_code == 422


async def test_webhook_signed_delivery_retry_and_isolation(
    client: AsyncClient, container: Container, net: Net, new_tenant: NewTenant
) -> None:
    a, b = await new_tenant(), await new_tenant()
    r = await client.post(
        f"{P}/webhooks",
        headers=a.h,
        json={
            "url": "https://hooks.example.com/tenant-a",
            "event_types": ["order.created", "order.status_changed"],
        },
    )
    assert r.status_code == 201, r.text
    secret, hook_id = r.json()["signing_secret"], r.json()["id"]
    assert secret.startswith("whsec_")
    assert "signing_secret" not in (await client.get(f"{P}/webhooks", headers=a.h)).json()["items"][0]
    await client.post(
        f"{P}/webhooks",
        headers=b.h,
        json={"url": "https://hooks.example.com/tenant-b", "event_types": ["order.created"]},
    )

    v = await order_shop(client, a)
    oid = (await place(client, a, v)).json()["id"]
    assert await jobs.relay_outbox(container) >= 1
    assert all(e.published_at is not None for e in await outbox(container, a))

    net.responses = [httpx.Response(500)]
    out = await jobs.dispatch_webhooks(container)
    assert out.get("pending") == 1
    req = net.requests[-1]
    assert req.url.path == "/tenant-a" and req.headers["host"] == "hooks.example.com"
    body = req.content
    event = json.loads(body)
    assert (
        event["type"] == "order.created" and event["data"]["order_id"] == oid and event["tenant_id"] == a.id
    )
    assert req.headers["x-tenantcore-event-id"] == event["id"]
    assert verify_sig(body, req.headers["x-tenantcore-signature"], secret)

    deliveries = (await client.get(f"{P}/webhooks/{hook_id}/deliveries", headers=a.h)).json()["items"]
    assert deliveries[0]["status"] == "pending" and deliveries[0]["last_status_code"] == 500
    assert datetime.fromisoformat(deliveries[0]["next_attempt_at"]) > datetime.now(UTC)  # backed off
    assert await jobs.dispatch_webhooks(container) == {}  # not due yet
    async with container.db.tenant_session(uuid.UUID(a.id)) as s:
        await s.execute(text("UPDATE webhook_deliveries SET next_attempt_at = now() - interval '1 second'"))
        await s.commit()
    assert (await jobs.dispatch_webhooks(container)).get("succeeded") == 1
    again = net.requests[-1]
    assert again.headers["x-tenantcore-event-id"] == event["id"]  # same event id on retry: receivers dedupe
    # Tenant B's endpoint never saw tenant A's event.
    assert all(r.url.path != "/tenant-b" for r in net.requests)


async def test_webhook_dead_letter_gone_and_rebinding(
    client: AsyncClient, container: Container, net: Net, new_tenant: NewTenant, platform_token: str
) -> None:
    t = await new_tenant()
    r = await client.post(
        f"{P}/webhooks",
        headers=t.h,
        json={"url": "https://flaky.example.com/x", "event_types": ["order.created"]},
    )
    hook = r.json()["id"]
    v = await order_shop(client, t)
    await place(client, t, v)
    await jobs.relay_outbox(container)
    async with container.db.tenant_session(uuid.UUID(t.id)) as s:
        await s.execute(
            text("UPDATE webhook_deliveries SET attempts = :n"),
            {"n": container.settings.webhook_max_attempts},
        )
        await s.commit()
    net.responses = [httpx.Response(503)]
    assert (await jobs.dispatch_webhooks(container)).get("dead") == 1
    dls = (await client.get(f"{P}/platform/dead-letters", headers=auth(platform_token))).json()["items"]
    mine = [d for d in dls if d["tenant_id"] == t.id and d["source"] == "webhook"]
    assert len(mine) == 1
    # Requeue from the dead-letter queue: the delivery becomes pending again and succeeds.
    assert (
        await client.post(f"{P}/platform/dead-letters/{mine[0]['id']}/requeue", headers=auth(platform_token))
    ).status_code == 202
    assert (await jobs.dispatch_webhooks(container)).get("succeeded") == 1

    # 410 Gone disables the webhook.
    await place(client, t, v)
    await jobs.relay_outbox(container)
    net.responses = [httpx.Response(410)]
    await jobs.dispatch_webhooks(container)
    hooks = (await client.get(f"{P}/webhooks", headers=t.h)).json()["items"]
    assert hooks[0]["is_active"] is False and "410" in hooks[0]["disabled_reason"]

    # DNS rebinding after registration: the send-time check blocks it and disables the hook.
    await client.patch(f"{P}/webhooks/{hook}", headers=t.h, json={"is_active": True})
    await place(client, t, v)
    await jobs.relay_outbox(container)
    sent_before = len(net.requests)
    net.dns["flaky"] = ["169.254.169.254"]
    assert (await jobs.dispatch_webhooks(container)).get("dead") == 1
    assert len(net.requests) == sent_before  # nothing was sent
    hooks = (await client.get(f"{P}/webhooks", headers=t.h)).json()["items"]
    assert hooks[0]["is_active"] is False and "blocked" in hooks[0]["disabled_reason"]


# -- notifications ------------------------------------------------------------------------------------------


def service_account() -> str:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    )
    return json.dumps(
        {
            "type": "service_account",
            "project_id": "tc-test",
            "client_email": "tc@tc-test.iam.gserviceaccount.com",
            "private_key": pem.decode(),
        }
    )


async def test_push_notifications(
    client: AsyncClient, container: Container, net: Net, new_tenant: NewTenant, new_user: NewUser
) -> None:
    t = await new_tenant()
    role = (
        await client.post(
            f"{P}/roles",
            headers=t.h,
            json={"name": "dispatch", "permissions": [{"resource": "orders", "action": "read"}]},
        )
    ).json()["id"]
    _, staff = await new_user(t, [role])
    tok_staff = "fcm_staff_token_" + "a" * 40
    r = await client.post(
        f"{P}/devices", headers=auth(staff), json={"platform": "android", "token": tok_staff}
    )
    assert r.status_code == 201 and tok_staff not in r.text
    await client.post(
        f"{P}/devices", headers=t.h, json={"platform": "ios", "token": "fcm_admin_token_" + "b" * 40}
    )
    key = (
        await client.post(f"{P}/api-keys", headers=t.h, json={"name": "k", "scopes": ["orders:read"]})
    ).json()["api_key"]
    assert (
        await client.post(
            f"{P}/devices", headers={"X-API-Key": key}, json={"platform": "web", "token": "x" * 30}
        )
    ).status_code == 403
    r = await client.post(
        f"{P}/notification-rules",
        headers=t.h,
        json={
            "name": "new order",
            "event_type": "order.created",
            "target_role_ids": [role],
            "include_tenant_admins": True,
            "title_template": "New order $number",
            "body_template": "Total $total $currency ${__class__}",
        },
    )
    assert r.status_code == 201, r.text

    container.notifications._sender = FcmSender(net.client(), service_account(), None)
    net.responses = [
        httpx.Response(200, json={"access_token": "ya29.test", "expires_in": 3600}),
        httpx.Response(200, json={"name": "projects/tc-test/messages/1"}),
        httpx.Response(404, json={"error": {"details": [{"errorCode": "UNREGISTERED"}]}}),
    ]
    v = await order_shop(client, t)
    order = (await place(client, t, v, 2)).json()
    await jobs.relay_outbox(container)
    out = await jobs.dispatch_notifications(container)
    assert out == {"succeeded": 1, "dead": 1}
    token_req, first_send = net.requests[0], net.requests[1]
    assert token_req.headers["host"] == "oauth2.googleapis.com"
    assert first_send.headers["authorization"] == "Bearer ya29.test"
    msg = json.loads(first_send.content)["message"]
    assert msg["notification"]["title"] == f"New order {order['number']}"
    assert msg["notification"]["body"] == "Total 16.00 USD ${__class__}"  # unknown placeholders stay literal
    # The token FCM reported as unregistered was deactivated.
    async with container.db.tenant_session(uuid.UUID(t.id)) as s:
        flags = sorted((await s.execute(text("SELECT is_active FROM system_user_devices"))).scalars())
    assert flags == [False, True]


# -- imports & tasks ---------------------------------------------------------------------------------------


async def upload(
    client: AsyncClient,
    t: TenantCtx,
    table: str,
    content: bytes,
    name: str = "rows.csv",
    mode: str = "all_or_nothing",
    headers: dict[str, str] | None = None,
) -> Any:
    return await client.post(
        f"{P}/import/excel/{table}",
        headers={**t.h, **(headers or {})},
        params={"mode": mode},
        files={"file": (name, content, "application/octet-stream")},
    )


async def test_csv_import_end_to_end(
    client: AsyncClient, container: Container, new_tenant: NewTenant, new_table: NewTable
) -> None:
    table = await new_table(
        [
            {"name": "sku", "type": "string", "nullable": False},
            {"name": "qty", "type": "integer"},
            {"name": "price", "type": "numeric"},
        ]
    )
    t = await new_tenant()
    csv_ok = b"sku,qty,price\nA-1,3,9.99\nA-2,,1.50\n"
    r = await upload(client, t, table, csv_ok, headers={"Idempotency-Key": "upload-0001"})
    assert r.status_code == 202, r.text
    task_id = r.json()["id"]
    r2 = await upload(client, t, table, csv_ok, headers={"Idempotency-Key": "upload-0001"})
    assert r2.status_code == 200 and r2.json()["id"] == task_id  # same submission, same task
    assert (await container.imports.run(uuid.UUID(t.id), uuid.UUID(task_id))).value == "succeeded"
    task = (await client.get(f"{P}/tasks/{task_id}", headers=t.h)).json()
    assert task["status"] == "succeeded" and task["result"]["inserted"] == 2
    rows = (await client.get(f"{P}/data/{table}", headers=t.h, params={"sort": "sku"})).json()["items"]
    assert [(x["sku"], x["qty"], x["price"]) for x in rows] == [("A-1", 3, "9.99"), ("A-2", None, "1.50")]
    # Re-running (duplicate message, retry) is a no-op; even forcing re-execution inserts nothing twice.
    assert (await container.imports.run(uuid.UUID(t.id), uuid.UUID(task_id))).value == "succeeded"
    async with container.db.tenant_session(uuid.UUID(t.id)) as s:
        await s.execute(text("UPDATE system_tasks SET status = 'queued' WHERE id = :i"), {"i": task_id})
        await s.commit()
    await container.imports.run(uuid.UUID(t.id), uuid.UUID(task_id))
    task = (await client.get(f"{P}/tasks/{task_id}", headers=t.h)).json()
    assert task["result"]["inserted"] == 0 and task["result"]["already_present"] == 2
    assert len((await client.get(f"{P}/data/{table}", headers=t.h)).json()["items"]) == 2
    events = [e.event_type for e in await outbox(container, t)]
    assert "import.completed" in events
    other = await new_tenant()
    assert (await client.get(f"{P}/tasks/{task_id}", headers=other.h)).status_code == 404


async def test_import_modes_and_validation(
    client: AsyncClient, container: Container, new_tenant: NewTenant, new_table: NewTable, new_user: NewUser
) -> None:
    table = await new_table(
        [
            {"name": "sku", "type": "string", "nullable": False},
            {"name": "qty", "type": "integer"},
            {"name": "secret", "type": "text"},
        ]
    )
    t = await new_tenant()
    bad = b"sku,qty\nOK-1,1\n,2\nOK-2,notanumber\nOK-3,4\n"
    tid = (await upload(client, t, table, bad)).json()["id"]
    assert (await container.imports.run(uuid.UUID(t.id), uuid.UUID(tid))).value == "failed"
    task = (await client.get(f"{P}/tasks/{tid}", headers=t.h)).json()
    assert task["status"] == "failed" and "nothing was imported" in task["error"]
    assert (await client.get(f"{P}/data/{table}", headers=t.h)).json()["items"] == []
    tid = (await upload(client, t, table, bad, mode="skip_invalid")).json()["id"]
    await container.imports.run(uuid.UUID(t.id), uuid.UUID(tid))
    res = (await client.get(f"{P}/tasks/{tid}", headers=t.h)).json()["result"]
    assert (
        res["inserted"] == 2
        and res["invalid_rows"] == 2
        and sorted(e["row"] for e in res["errors"]) == [3, 4]
    )

    for content, name, code in (
        (b"sku,nope\nx,1\n", "a.csv", 422),  # unknown column
        (b"qty\n1\n", "a.csv", 422),  # required column missing
        (b"\xff\xfe\x00bad", "a.csv", 422),  # not UTF-8
        (b"PK\x03\x04garbage", "a.xlsx", 422),  # corrupt workbook
        (b"sku\nx\n", "a.exe", 422),  # extension
        (b"", "a.csv", 422),
    ):
        assert (await upload(client, t, table, content, name)).status_code == code, (content, name)

    role = (
        await client.post(
            f"{P}/roles",
            headers=t.h,
            json={
                "name": "importer",
                "permissions": [
                    {"resource": "imports", "action": "create"},
                    {"resource": f"data.{table}", "action": "create", "writable_fields": ["sku", "qty"]},
                ],
            },
        )
    ).json()["id"]
    _, tok = await new_user(t, [role])
    tt = TenantCtx(t.id, t.slug, "", tok, "")
    assert (await upload(client, tt, table, b"sku,secret\nx,y\n")).status_code == 403  # field not writable
    tid = (await upload(client, tt, table, b"sku,qty\nP-1,1\n")).json()["id"]
    assert (await client.get(f"{P}/tasks/{tid}", headers=auth(tok))).status_code == 200
    _, stranger = await new_user(t, [role])
    assert (await client.get(f"{P}/tasks/{tid}", headers=auth(stranger))).status_code == 404  # not their task


async def test_xlsx_import_and_large_upload_limit(
    client: AsyncClient, container: Container, new_tenant: NewTenant, new_table: NewTable
) -> None:
    from openpyxl import Workbook

    table = await new_table(
        [
            {"name": "name", "type": "string"},
            {"name": "seen", "type": "timestamp"},
            {"name": "n", "type": "integer"},
        ]
    )
    t = await new_tenant()
    wb = Workbook()
    ws = wb.active
    ws.append(["name", "seen", "n"])
    ws.append(["alpha", datetime(2026, 1, 2, 3, 4, 5), 7])
    buf = io.BytesIO()
    wb.save(buf)
    tid = (await upload(client, t, table, buf.getvalue(), "sheet.xlsx")).json()["id"]
    assert (await container.imports.run(uuid.UUID(t.id), uuid.UUID(tid))).value == "succeeded"
    row = (await client.get(f"{P}/data/{table}", headers=t.h)).json()["items"][0]
    assert row["name"] == "alpha" and row["n"] == 7 and row["seen"].startswith("2026-01-02T03:04:05")
    # Uploads above the global 1 MiB body limit are allowed on the import path only.
    big = b"name,n\n" + b"".join(f"row{i},{i}\n".encode() for i in range(120_000))
    assert len(big) > container.settings.max_request_bytes
    r = await upload(client, t, table, big)
    assert r.status_code == 202
    assert (
        await client.post(
            f"{P}/data/{table}",
            headers={**t.h, "Content-Type": "application/json"},
            content=b'{"name":"' + b"x" * len(big) + b'"}',
        )
    ).status_code == 413


# -- QR, pings, platform ops -------------------------------------------------------------------------------


async def test_qrcode(client: AsyncClient, new_tenant: NewTenant) -> None:
    t = await new_tenant()
    r = await client.get(
        f"{P}/qrcode/generate", headers=t.h, params={"data": "https://shop.example/o/123", "scale": 4}
    )
    assert (
        r.status_code == 200
        and r.headers["content-type"] == "image/png"
        and r.content[:8] == b"\x89PNG\r\n\x1a\n"
    )
    expected = io.BytesIO()
    segno.make("https://shop.example/o/123", error="m", micro=False).save(
        expected, kind="png", scale=4, border=4
    )
    assert r.content == expected.getvalue()
    svg = await client.get(
        f"{P}/qrcode/generate", headers=t.h, params={"data": "<script>alert(1)</script>", "format": "svg"}
    )
    assert svg.headers["content-type"] == "image/svg+xml" and b"<script" not in svg.content
    assert (
        await client.get(f"{P}/qrcode/generate", headers=t.h, params={"data": "x" * 1025})
    ).status_code == 422
    assert (
        await client.get(f"{P}/qrcode/generate", headers=t.h, params={"data": "x", "format": "gif"})
    ).status_code == 422
    assert (await client.get(f"{P}/qrcode/generate", params={"data": "x"})).status_code == 401


async def test_delivery_pings(client: AsyncClient, container: Container, new_tenant: NewTenant) -> None:
    t, other = await new_tenant(), await new_tenant()
    v = await order_shop(client, t)
    oid = (await place(client, t, v)).json()["id"]
    now = datetime.now(UTC)
    url = f"{P}/orders/{oid}/delivery-pings"
    r = await client.post(
        url,
        headers=t.h,
        json={"lat": "11.556400", "lng": "104.928200", "accuracy_m": 12, "recorded_at": now.isoformat()},
    )
    assert r.status_code == 201, r.text
    for bad in (
        {"lat": "91", "lng": "0", "recorded_at": now.isoformat()},
        {"lat": "1", "lng": "1", "recorded_at": (now + timedelta(hours=1)).isoformat()},
        {"lat": "1", "lng": "1", "recorded_at": "2026-01-01T00:00:00"},
    ):
        assert (await client.post(url, headers=t.h, json=bad)).status_code == 422, bad
    assert (
        await client.post(url, headers=other.h, json={"lat": "1", "lng": "1", "recorded_at": now.isoformat()})
    ).status_code == 404
    items = (await client.get(url, headers=t.h)).json()["items"]
    assert items[0]["lat"] == "11.556400" and items[0]["accuracy_m"] == 12
    assert (await client.get(url, headers=other.h)).json()["items"] == []
    assert "delivery.ping" in [e.event_type for e in await outbox(container, t)]


async def test_maintenance_and_stale_tasks(
    client: AsyncClient, container: Container, new_tenant: NewTenant, new_table: NewTable
) -> None:
    table = await new_table([{"name": "a", "type": "text"}])
    t = await new_tenant()
    tid = (await upload(client, t, table, b"a\nx\n")).json()["id"]
    async with container.db.tenant_session(uuid.UUID(t.id)) as s:
        await s.execute(
            text("UPDATE system_tasks SET updated_at = now() - interval '1 hour' WHERE id = :i"), {"i": tid}
        )
        await s.commit()
    # The runtime role cannot UPDATE system_files (by design); age the file as the owner/platform.
    async with container.db.ddl_session() as s:
        await s.execute(text("SELECT set_config('app.is_platform','on',true)"))
        await s.execute(
            text("UPDATE system_files SET expires_at = now() - interval '1 second' WHERE tenant_id = :t"),
            {"t": t.id},
        )
        await s.commit()
    stale = await jobs.stale_tasks(container)
    assert ("import", uuid.UUID(t.id), uuid.UUID(tid)) in stale
    assert all(
        x[2] != uuid.UUID(tid) for x in await jobs.stale_tasks(container)
    )  # claimed: not returned twice
    counts = await jobs.cleanup(container)
    assert counts["files"] >= 1
    # The file is gone, so the import now fails cleanly instead of crashing.
    assert (await container.imports.run(uuid.UUID(t.id), uuid.UUID(tid))).value == "failed"


async def test_slow_query_diagnostics(
    client: AsyncClient, platform_token: str, new_tenant: NewTenant
) -> None:
    r = await client.get(f"{P}/platform/diagnostics/slow-queries", headers=auth(platform_token))
    assert r.status_code == 200 and "seq_scan_heavy_tables" in r.json()
    t = await new_tenant()
    assert (await client.get(f"{P}/platform/diagnostics/slow-queries", headers=t.h)).status_code == 401


def test_backoff_has_jitter_and_cap() -> None:
    from app.services.webhooks import BACKOFF_CAP_S, backoff_s

    samples = [backoff_s(3) for _ in range(50)]
    assert all(60 <= s <= 180 for s in samples) and len(set(samples)) > 1
    assert backoff_s(50) <= BACKOFF_CAP_S * 1.5


def test_template_rendering_is_safe() -> None:
    from app.services.notifications import render

    out = render("Hi $name {0.__class__} ${payload} $missing", {"name": "Ann", "payload": {"a": 1}}, 100)
    assert out == "Hi Ann {0.__class__} ${payload} $missing"
    assert len(render("$x", {"x": "y" * 500}, 1000)) == 200
