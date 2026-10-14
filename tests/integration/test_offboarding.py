"""Tenant offboarding: request guards, freeze, encrypted archive, complete purge, cache scavenging,
crash-and-rerun idempotency, and isolation of every other tenant.

Row counts after the purge are checked through a superuser connection (which bypasses RLS) when
``TC_TEST_ADMIN_URL`` is reachable, so "zero rows" means zero rows, not "zero rows visible to me".
"""

from __future__ import annotations

import gzip
import hashlib
import json
import os
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable, Iterator
from pathlib import Path
from typing import Any

import asyncpg
import pytest
from httpx import AsyncClient
from sqlalchemy import text

from app.container import Container
from app.models import TaskStatus
from app.services.offboarding import LocalArchiveStore, tenant_scoped_tables
from tests.integration.conftest import PASSWORD, P, TenantCtx, auth
from tests.integration.test_async_ops import net  # noqa: F401 - fixture

NewTenant = Callable[..., Awaitable[TenantCtx]]
NewTable = Callable[..., Awaitable[str]]
ADMIN_URL = os.environ.get("TC_TEST_ADMIN_URL", "postgresql://postgres:postgres@127.0.0.1:5432/postgres")


@pytest.fixture
async def superuser() -> AsyncIterator[asyncpg.Connection]:
    try:
        conn = await asyncpg.connect(ADMIN_URL.rsplit("/", 1)[0] + "/tenantcore")
    except Exception as e:
        pytest.skip(f"no superuser connection: {type(e).__name__}")
    yield conn
    await conn.close()


@pytest.fixture
def archive_store(container: Container, tmp_path: Path) -> Iterator[Path]:
    old = container.offboarding.store
    container.offboarding.store = LocalArchiveStore(str(tmp_path), container.envelope)
    yield tmp_path
    container.offboarding.store = old


@pytest.fixture
def enqueued(monkeypatch: pytest.MonkeyPatch) -> list[tuple[str, Any, uuid.UUID]]:
    """Capture task messages instead of sending them to a broker."""
    sent: list[tuple[str, Any, uuid.UUID]] = []

    def fake(kind: str, tenant_id: Any, task_id: uuid.UUID) -> bool:
        sent.append((kind, tenant_id, task_id))
        return True

    monkeypatch.setattr("app.workers.tasks.enqueue", fake)
    return sent


async def _populate(client: AsyncClient, t: TenantCtx, table: str) -> dict[str, str]:
    """Create rows across many tables: dynamic data, users, API keys, webhooks, an import, audit."""
    r = await client.post(f"{P}/data/{table}", headers=t.h, json={"title": f"row of {t.slug}"})
    assert r.status_code == 201, r.text
    r = await client.post(
        f"{P}/users",
        headers=t.h,
        json={"email": f"s-{uuid.uuid4().hex[:6]}@x.example.com", "password": PASSWORD},
    )
    assert r.status_code == 201, r.text
    r = await client.post(
        f"{P}/api-keys", headers=t.h, json={"name": "svc", "scopes": [f"data.{table}:read"]}
    )
    assert r.status_code == 201, r.text
    key = r.json()["api_key"]
    assert (await client.get(f"{P}/data/{table}", headers={"X-API-Key": key})).status_code == 200  # cached
    r = await client.post(
        f"{P}/webhooks",
        headers=t.h,
        json={"url": "https://hooks.example.com/x", "event_types": ["data.created"]},
    )
    assert r.status_code == 201, r.text
    r = await client.post(
        f"{P}/import/excel/{table}", headers=t.h, files={"file": ("a.csv", b"title\nimported\n", "text/csv")}
    )
    assert r.status_code == 202, r.text
    return {"api_key": key}


async def _counts(conn: asyncpg.Connection, tenant_id: str, dynamic: str) -> dict[str, int]:
    tid = uuid.UUID(tenant_id)
    out = {
        t.name: await conn.fetchval(f"SELECT count(*) FROM {t.name} WHERE tenant_id = $1", tid)
        for t in tenant_scoped_tables()
    }
    out["app_tenants"] = await conn.fetchval("SELECT count(*) FROM app_tenants WHERE id = $1", tid)
    out[dynamic] = await conn.fetchval(
        f"SELECT count(*) FROM tenant_data.dt_{dynamic} WHERE tenant_id = $1", tid
    )
    return out


async def test_offboarding_erases_one_tenant_completely(
    client: AsyncClient,
    container: Container,
    platform_token: str,
    new_tenant: NewTenant,
    new_table: NewTable,
    superuser: asyncpg.Connection,
    archive_store: Path,
    enqueued: list[tuple[str, Any, uuid.UUID]],
    net: Any,  # noqa: F811
) -> None:
    table = await new_table([{"name": "title", "type": "string"}])
    victim, other = await new_tenant(), await new_tenant()
    vk = await _populate(client, victim, table)
    ok = await _populate(client, other, table)
    await container.imports.run(uuid.UUID(victim.id), uuid.UUID((await _task_ids(superuser, victim.id))[0]))
    before_other = await _counts(superuser, other.id, table)
    before = await _counts(superuser, victim.id, table)
    assert before["system_audit_logs"] > 0 and before[table] == 2 and before["tenant_api_keys"] == 1

    h, url = auth(platform_token), f"{P}/platform/tenants/{victim.id}/offboard"
    # Guards: tenant admins cannot call it; the slug must be typed back exactly.
    assert (await client.post(url, headers=victim.h, json={"confirm_slug": victim.slug})).status_code in (
        401,
        403,
    )
    assert (await client.post(url, headers=h, json={"confirm_slug": other.slug})).status_code == 422
    r = await client.post(url, headers=h, json={"confirm_slug": victim.slug})
    assert r.status_code == 202, r.text
    task_id = r.json()["id"]
    again = await client.post(url, headers=h, json={"confirm_slug": victim.slug})
    assert again.json()["id"] == task_id  # one erasure task per tenant
    assert enqueued[-1] == ("offboard", None, uuid.UUID(task_id))

    # Crash after the purge (during cache scavenging); the retried job must finish without redoing
    # completed work (one archive) and without failing on already-deleted rows.
    real = container.offboarding._scavenge
    calls = {"n": 0}

    async def boom(*a: Any) -> int:
        calls["n"] += 1
        raise ConnectionError("simulated crash")

    container.offboarding._scavenge = boom  # type: ignore[method-assign]
    with pytest.raises(ConnectionError):
        await container.offboarding.run(uuid.UUID(task_id))
    container.offboarding._scavenge = real  # type: ignore[method-assign]
    assert (await client.get(f"{P}/platform/tasks/{task_id}", headers=h)).json()["status"] == "running"
    assert await container.offboarding.run(uuid.UUID(task_id)) == TaskStatus.succeeded
    assert await container.offboarding.run(uuid.UUID(task_id)) == TaskStatus.succeeded  # no-op now

    task = (await client.get(f"{P}/platform/tasks/{task_id}", headers=h)).json()
    assert task["status"] == "succeeded" and task["attempts"] == 2

    # Every tenant-scoped row is gone, in shared tables and the dynamic table.
    after = await _counts(superuser, victim.id, table)
    assert {k: v for k, v in after.items() if v} == {}, after
    assert await _counts(superuser, other.id, table) == before_other

    # Credentials stop working: JWT, refresh token, API key (whose metadata was cached), login.
    assert (await client.get(f"{P}/data/{table}", headers=victim.h)).status_code == 401
    assert (await client.get(f"{P}/data/{table}", headers={"X-API-Key": vk["api_key"]})).status_code == 401
    assert (
        await client.post(f"{P}/auth/refresh", json={"refresh_token": victim.refresh_token})
    ).status_code == 401
    r = await client.post(
        f"{P}/auth/login", json={"tenant": victim.slug, "email": victim.owner_email, "password": PASSWORD}
    )
    assert r.status_code == 401
    # ...while the other tenant is untouched.
    assert (await client.get(f"{P}/data/{table}", headers=other.h)).status_code == 200
    assert (await client.get(f"{P}/data/{table}", headers={"X-API-Key": ok["api_key"]})).status_code == 200

    # No Redis key mentions the tenant any more.
    leftover = [k async for k in container.cache.client.scan_iter(match=f"*{victim.id}*", count=1000)]
    assert leftover == []

    # Exactly one archive, encrypted at rest, whose checksum and contents match the audit log.
    archives = await superuser.fetch(
        "SELECT * FROM tenant_archives WHERE tenant_id = $1", uuid.UUID(victim.id)
    )
    assert [a["kind"] for a in archives] == ["audit_logs"]
    a = archives[0]
    assert a["records"] == before["system_audit_logs"] and a["tenant_slug"] == victim.slug
    path = Path(a["location"].removeprefix("file://"))
    assert path.is_relative_to(archive_store) and oct(path.stat().st_mode & 0o777) == "0o600"
    blob = path.read_text()
    with pytest.raises(OSError):
        gzip.decompress(blob.encode())  # not plaintext gzip on disk
    key = str(path.relative_to(archive_store))
    data = await container.envelope.decrypt(blob, {"purpose": "tenant_archive", "id": key})
    assert hashlib.sha256(data).hexdigest() == a["sha256"]
    lines = [json.loads(x) for x in gzip.decompress(data).splitlines()]
    assert len(lines) == a["records"] and {x["tenant_id"] for x in lines} == {victim.id}

    # The erasure itself is recorded in the platform audit trail (no tenant id).
    rec = await superuser.fetchrow(
        "SELECT * FROM system_audit_logs WHERE action = 'tenant.erased' AND resource_id = $1", victim.id
    )
    assert rec is not None and rec["tenant_id"] is None
    detail = json.loads(rec["detail"]) if isinstance(rec["detail"], str) else rec["detail"]
    assert detail["rows_deleted"]["app_tenants"] == 1 and detail["archives"][0]["sha256"] == a["sha256"]


async def _task_ids(conn: asyncpg.Connection, tenant_id: str) -> list[str]:
    rows = await conn.fetch(
        "SELECT id FROM system_tasks WHERE tenant_id = $1 ORDER BY created_at", uuid.UUID(tenant_id)
    )
    return [str(r["id"]) for r in rows]


async def test_append_only_tables_stay_protected_outside_a_purge(
    container: Container, new_tenant: NewTenant
) -> None:
    """The purge exemption is narrow: platform context alone, or a purge flag naming a different
    tenant, still cannot delete audit rows."""
    t, other = await new_tenant(), await new_tenant()
    for flag in ("", other.id):
        async with container.db.ddl_session() as s:
            await s.execute(
                text(
                    "SELECT set_config('app.tenant_id', :t, true), set_config('app.is_platform','on',true),"
                    " set_config('app.purge_tenant', :f, true)"
                ),
                {"t": t.id, "f": flag},
            )
            with pytest.raises(Exception, match=r"append-only|append only"):
                await s.execute(text("DELETE FROM system_audit_logs WHERE tenant_id = :t"), {"t": t.id})
            await s.rollback()
    # TRUNCATE is refused even with a matching purge flag.
    async with container.db.ddl_session() as s:
        await s.execute(
            text("SELECT set_config('app.is_platform','on',true), set_config('app.purge_tenant', :t, true)"),
            {"t": t.id},
        )
        with pytest.raises(Exception, match=r"append-only|append only|permission denied"):
            await s.execute(text("TRUNCATE stock_movements"))
        await s.rollback()


async def test_offboarding_unknown_tenant_and_tenant_role_cannot_purge(
    client: AsyncClient, container: Container, platform_token: str, new_tenant: NewTenant
) -> None:
    r = await client.post(
        f"{P}/platform/tenants/{uuid.uuid4()}/offboard",
        headers=auth(platform_token),
        json={"confirm_slug": "x"},
    )
    assert r.status_code == 404
    # The runtime role has no DELETE on the tenant registry, platform context or not.
    t = await new_tenant()
    async with container.db.platform_session(uuid.uuid4(), tenant_id=uuid.UUID(t.id)) as s:
        await s.execute(text("SELECT set_config('app.purge_tenant', :t, true)"), {"t": t.id})
        with pytest.raises(Exception, match="permission denied"):
            await s.execute(text("DELETE FROM app_tenants WHERE id = :t"), {"t": t.id})
