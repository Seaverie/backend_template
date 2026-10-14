"""Behaviour with Redis unreachable: degrade safely, never fail open.

A second app instance is pointed at a closed port, sharing the same database as the healthy one.
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator, Awaitable, Callable

import pytest
from httpx import AsyncClient
from pydantic import SecretStr

from app.container import Container
from app.core.config import get_settings
from tests.integration.conftest import P, TenantCtx, auth, make_client

NewTenant = Callable[..., Awaitable[TenantCtx]]


@pytest.fixture(scope="module")
async def outage_client() -> AsyncIterator[AsyncClient]:
    s = get_settings().model_copy(
        update={
            "redis_url": SecretStr("redis://127.0.0.1:1/0"),
            "redis_timeout_s": 0.2,
            "rl_local_fallback_divisor": 4,
        }
    )
    c = Container.build(s)
    async with make_client(c) as ac:
        yield ac
    await c.close()


async def test_ready_reports_redis_down_but_stays_ready(outage_client: AsyncClient) -> None:
    r = await outage_client.get("/ready")
    assert r.status_code == 200
    assert r.json()["checks"] == {"database": True, "redis": False}


async def test_jwt_auth_falls_back_to_database(
    outage_client: AsyncClient, client: AsyncClient, new_tenant: NewTenant
) -> None:
    t = await new_tenant()
    assert (await outage_client.get(f"{P}/auth/me", headers=t.h)).status_code == 200
    # Logout happens on a healthy node; the degraded node must still honour it via the database.
    assert (await client.post(f"{P}/auth/logout", headers=t.h)).status_code == 204
    assert (await outage_client.get(f"{P}/auth/me", headers=t.h)).status_code == 401


async def test_api_keys_fall_back_to_database(
    outage_client: AsyncClient, client: AsyncClient, new_tenant: NewTenant
) -> None:
    t = await new_tenant()
    r = await client.post(f"{P}/api-keys", headers=t.h, json={"name": "k", "scopes": ["users:read"]})
    key, row_id = r.json()["api_key"], r.json()["id"]
    assert (await outage_client.get(f"{P}/auth/me", headers={"X-API-Key": key})).status_code == 200
    assert (await client.post(f"{P}/api-keys/{row_id}/revoke", headers=t.h)).status_code == 204
    assert (await outage_client.get(f"{P}/auth/me", headers={"X-API-Key": key})).status_code == 401


async def test_idempotency_fails_closed(
    outage_client: AsyncClient, new_tenant: NewTenant, new_table: Callable[..., Awaitable[str]]
) -> None:
    table = await new_table([{"name": "amount", "type": "numeric"}], require_idempotency=True)
    t = await new_tenant()
    r = await outage_client.post(
        f"{P}/data/{table}", headers={**t.h, "Idempotency-Key": f"k-{uuid.uuid4()}"}, json={"amount": "1.00"}
    )
    assert r.status_code == 503
    assert (await outage_client.get(f"{P}/data/{table}", headers=t.h)).json()["items"] == []


async def test_rate_limiting_degrades_to_local_enforcement(
    outage_client: AsyncClient, new_tenant: NewTenant, platform_token: str, client: AsyncClient
) -> None:
    t = await new_tenant()
    await client.patch(
        f"{P}/platform/tenants/{t.id}", headers=auth(platform_token), json={"rate_limit_per_min": 8}
    )
    codes = [(await outage_client.get(f"{P}/auth/me", headers=t.h)).status_code for _ in range(4)]
    assert codes == [200, 200, 429, 429]  # 8 / divisor 4 = 2 per instance
