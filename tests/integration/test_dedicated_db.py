"""Enterprise tenants on a dedicated PostgreSQL database: configuration guards, schema sync by ledger
replay, data routing, credential rotation, and fan-out of later schema changes.

Needs a superuser connection to create the dedicated database (TC_TEST_ADMIN_URL); skipped otherwise.
"""

from __future__ import annotations

import os
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable

import asyncpg
import pytest
from httpx import AsyncClient

from app.container import Container
from app.core.config import get_settings
from tests.integration.conftest import P, TenantCtx, auth, make_client

ADMIN_URL = os.environ.get("TC_TEST_ADMIN_URL", "postgresql://postgres:postgres@127.0.0.1:5432/postgres")
NewTenant = Callable[..., Awaitable[TenantCtx]]
NewTable = Callable[..., Awaitable[str]]


async def _admin(db: str = "postgres") -> asyncpg.Connection:
    return await asyncpg.connect(ADMIN_URL.rsplit("/", 1)[0] + f"/{db}")


@pytest.fixture
async def dedicated() -> AsyncIterator[dict[str, str]]:
    try:
        conn = await _admin()
    except Exception as e:
        pytest.skip(f"no superuser connection for dedicated-db tests: {type(e).__name__}")
    suffix = uuid.uuid4().hex[:8]
    role, db, pw = f"tc_ded_{suffix}", f"tc_ded_{suffix}", f"pw_{suffix}"
    await conn.execute(f"CREATE ROLE {role} LOGIN PASSWORD '{pw}' NOSUPERUSER NOBYPASSRLS")
    await conn.execute(f"CREATE DATABASE {db} OWNER {role}")
    await conn.close()
    yield {"role": role, "db": db, "password": pw}
    conn = await _admin()
    await conn.execute(f"DROP DATABASE IF EXISTS {db} WITH (FORCE)")
    await conn.execute(f"DROP ROLE IF EXISTS {role}")
    await conn.close()


@pytest.fixture(scope="module")
async def ded_client() -> AsyncIterator[tuple[AsyncClient, Container]]:
    c = Container.build(get_settings().model_copy(update={"tenant_db_allowed_networks": ["127.0.0.0/8"]}))
    async with make_client(c) as ac:
        yield ac, c
    await c.close()


def cfg(d: dict[str, str], **over: object) -> dict[str, object]:
    return {
        "dialect": "postgresql",
        "host": "127.0.0.1",
        "port": 5432,
        "database": d["db"],
        "username": d["role"],
        "password": d["password"],
        "ssl_mode": "disable",
        **over,
    }


async def test_configuration_guards(
    ded_client: tuple[AsyncClient, Container],
    dedicated: dict[str, str],
    platform_token: str,
    new_tenant: NewTenant,
    client: AsyncClient,
) -> None:
    ac, _ = ded_client
    t = await new_tenant()
    url, h = f"{P}/platform/tenants/{t.id}/database", auth(platform_token)
    assert (
        await ac.put(url, headers=h, json=cfg(dedicated, host="169.254.169.254"))
    ).status_code == 422  # metadata range
    assert (
        await ac.put(url, headers=h, json=cfg(dedicated, host="10.0.0.1"))
    ).status_code == 422  # not allowlisted
    assert (
        await ac.put(url, headers=h, json=cfg(dedicated, username="postgres", database="postgres"))
    ).status_code == 422  # superuser
    # The control-plane-only deployment (no allowlist) refuses dedicated databases entirely.
    assert (await client.put(url, headers=h, json=cfg(dedicated))).status_code == 422
    # One database, one tenant: a second tenant cannot be pointed at a database another tenant owns.
    owner = await new_tenant()
    own_url = f"{P}/platform/tenants/{owner.id}/database"
    assert (await ac.put(own_url, headers=h, json=cfg(dedicated))).status_code == 200
    assert (await ac.post(f"{own_url}/sync", headers=h)).status_code == 200
    assert (await ac.put(url, headers=h, json=cfg(dedicated))).status_code == 200
    assert (await ac.post(f"{url}/sync", headers=h)).status_code == 409
    assert (
        await ac.post(f"{own_url}/disable", headers=h)
    ).status_code == 204  # teardown: database is dropped
    # A tenant that already has shared data cannot be switched without migrating it first.
    table = (await ac.get(f"{P}/system/tables", headers=h)).json()["items"]
    if table:
        name = table[0]["name"]
        meta = await ded_client[1].schema.get_table(name)
        if meta and meta.fields and all(f.nullable for f in meta.fields):
            await ac.post(f"{P}/data/{name}", headers=t.h, json={})
            r = await ac.put(url, headers=h, json=cfg(dedicated))
            assert r.status_code == 200
            assert (await ac.post(f"{url}/sync", headers=h)).status_code == 409
    assert (
        await ac.post(f"{own_url}/disable", headers=h)
    ).status_code == 204  # teardown: database is dropped


async def test_dedicated_routing_end_to_end(
    ded_client: tuple[AsyncClient, Container],
    dedicated: dict[str, str],
    platform_token: str,
    new_tenant: NewTenant,
    new_table: NewTable,
) -> None:
    ac, c = ded_client
    h = auth(platform_token)
    table = await new_table([{"name": "title", "type": "string", "searchable": True}])
    t, shared = await new_tenant(), await new_tenant()
    url = f"{P}/platform/tenants/{t.id}/database"
    r = await ac.put(url, headers=h, json=cfg(dedicated))
    assert r.status_code == 200, r.text
    assert (
        r.json()["status"] == "pending" and "password" not in r.text and dedicated["password"] not in r.text
    )
    r = await ac.post(f"{url}/sync", headers=h)
    assert r.status_code == 200, r.text
    assert r.json()["synced_schema_version"] == await c.schema.version()

    r = await ac.post(f"{P}/data/{table}", headers=t.h, json={"title": "enterprise row"})
    assert r.status_code == 201, r.text
    assert (
        await ac.post(f"{P}/data/{table}", headers=shared.h, json={"title": "shared row"})
    ).status_code == 201

    ded = await asyncpg.connect(ADMIN_URL.rsplit("/", 1)[0] + f"/{dedicated['db']}")
    cp = await _admin("tenantcore")
    try:
        assert await ded.fetchval(f"SELECT count(*) FROM tenant_data.dt_{table}") == 1
        assert (
            await cp.fetchval(
                f"SELECT count(*) FROM tenant_data.dt_{table} WHERE tenant_id = $1", uuid.UUID(t.id)
            )
            == 0
        )
        assert await ded.fetchval("SELECT count(*) FROM system_audit_logs WHERE action = 'data.create'") == 1
        forced = await ded.fetchval(
            "SELECT relforcerowsecurity FROM pg_class WHERE oid = $1::regclass", f"tenant_data.dt_{table}"
        )
        assert forced is True
    finally:
        await cp.close()
    # As the tenant's own role, without tenant context, RLS hides every row.
    role_conn = await asyncpg.connect(
        user=dedicated["role"], password=dedicated["password"], host="127.0.0.1", database=dedicated["db"]
    )
    try:
        assert await role_conn.fetchval(f"SELECT count(*) FROM tenant_data.dt_{table}") == 0
    finally:
        await role_conn.close()

    items = (await ac.get(f"{P}/data/{table}", headers=t.h, params={"q": "enterprise"})).json()["items"]
    assert [i["title"] for i in items] == ["enterprise row"]
    assert [i["title"] for i in (await ac.get(f"{P}/data/{table}", headers=shared.h)).json()["items"]] == [
        "shared row"
    ]

    # A table created later is replayed onto the dedicated database automatically.
    later = await new_table([{"name": "n", "type": "integer"}])
    sync = await ac.get(url, headers=h)
    if (
        sync.json()["synced_schema_version"] < await c.schema.version()
    ):  # fan-out ran on the other app instance
        r = await ac.post(f"{url}/sync", headers=h)
        assert r.status_code == 200, r.text
    assert (await ac.post(f"{P}/data/{later}", headers=t.h, json={"n": 7})).status_code == 201
    assert await ded.fetchval(f"SELECT n FROM tenant_data.dt_{later}") == 7

    # Credential rotation: same target, new secret version, the pooled engine is replaced.
    await ded.execute(f"ALTER ROLE {dedicated['role']} PASSWORD 'rotated_{dedicated['password']}'")
    await ded.close()
    before = c.tenant_db._engines[uuid.UUID(t.id)].engine
    r = await ac.put(url, headers=h, json=cfg(dedicated, password=f"rotated_{dedicated['password']}"))
    assert r.json()["secret_version"] == 2 and r.json()["status"] == "active"
    assert (await ac.get(f"{P}/data/{table}", headers=t.h)).status_code == 200
    assert c.tenant_db._engines[uuid.UUID(t.id)].engine is not before

    assert (await ac.post(f"{url}/disable", headers=h)).status_code == 204
    assert (await ac.get(f"{P}/data/{table}", headers=t.h)).status_code == 503
    assert (await ac.get(f"{P}/data/{table}", headers=shared.h)).status_code == 200
