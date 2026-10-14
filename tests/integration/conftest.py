"""Integration fixtures: real PostgreSQL (application role, RLS active) and real Redis."""

from __future__ import annotations

import os
import socket
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

from app.container import Container
from app.core.config import get_settings
from app.main import create_app
from app.models import PlatformAdmin
from app.security.passwords import hash_password

PASSWORD = "Integration-Test-Passw0rd!"
P = "/api/v1"


def _reachable(url: str, default_port: int) -> bool:
    u = urlparse(url.replace("+asyncpg", ""))
    try:
        with socket.create_connection((u.hostname or "127.0.0.1", u.port or default_port), timeout=1):
            return True
    except OSError:
        return False


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    s = get_settings()
    ok = _reachable(s.database_url.get_secret_value(), 5432) and _reachable(
        s.redis_url.get_secret_value(), 6379
    )
    if not ok and os.environ.get("TC_REQUIRE_INTEGRATION") == "1":
        raise pytest.UsageError("TC_REQUIRE_INTEGRATION=1 but PostgreSQL/Redis are not reachable")
    for item in items:
        if "integration" in str(item.fspath):
            item.add_marker(pytest.mark.integration)
            if not ok:
                item.add_marker(pytest.mark.skip(reason="PostgreSQL/Redis not reachable"))


@pytest.fixture(scope="session")
async def container() -> AsyncIterator[Container]:
    c = Container.build(get_settings())
    yield c
    await c.close()


def make_client(c: Container) -> AsyncClient:
    app = create_app(c.settings, c)
    app.state.container = c  # ASGITransport does not run lifespan
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://test")


@pytest.fixture(scope="session")
async def client(container: Container) -> AsyncIterator[AsyncClient]:
    async with make_client(container) as ac:
        yield ac


@pytest.fixture(scope="session")
async def platform_token(client: AsyncClient, container: Container) -> str:
    email = f"ops-{uuid.uuid4().hex[:8]}@example.com"
    async with container.db.ddl_session() as s:
        await s.execute(text("SELECT set_config('app.is_platform', 'on', true)"))
        s.add(PlatformAdmin(email=email, password_hash=await hash_password(PASSWORD)))
        await s.commit()
    r = await client.post(f"{P}/platform/auth/login", json={"email": email, "password": PASSWORD})
    assert r.status_code == 200, r.text
    return str(r.json()["access_token"])


@dataclass
class TenantCtx:
    id: str
    slug: str
    owner_email: str
    token: str
    refresh_token: str

    @property
    def h(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}


def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture(scope="session")
def new_tenant(client: AsyncClient, platform_token: str) -> Callable[..., Awaitable[TenantCtx]]:
    async def make(**extra: Any) -> TenantCtx:
        slug = f"t-{uuid.uuid4().hex[:10]}"
        email = f"owner@{slug}.example.com"
        r = await client.post(
            f"{P}/platform/tenants",
            headers=auth(platform_token),
            json={"slug": slug, "name": slug, "owner_email": email, "owner_password": PASSWORD, **extra},
        )
        assert r.status_code == 201, r.text
        tid = r.json()["tenant"]["id"]
        r = await client.post(f"{P}/auth/login", json={"tenant": slug, "email": email, "password": PASSWORD})
        assert r.status_code == 200, r.text
        return TenantCtx(tid, slug, email, r.json()["access_token"], r.json()["refresh_token"])

    return make


@pytest.fixture(scope="session")
def new_user(client: AsyncClient) -> Callable[..., Awaitable[tuple[str, str]]]:
    """Create a staff user in the tenant, optionally assign roles; return (user_id, access_token)."""

    async def make(t: TenantCtx, role_ids: list[str] | None = None) -> tuple[str, str]:
        email = f"staff-{uuid.uuid4().hex[:8]}@{t.slug}.example.com"
        r = await client.post(f"{P}/users", headers=t.h, json={"email": email, "password": PASSWORD})
        assert r.status_code == 201, r.text
        uid = r.json()["id"]
        if role_ids:
            r = await client.put(f"{P}/users/{uid}/roles", headers=t.h, json={"role_ids": role_ids})
            assert r.status_code == 204, r.text
        r = await client.post(
            f"{P}/auth/login", json={"tenant": t.slug, "email": email, "password": PASSWORD}
        )
        assert r.status_code == 200, r.text
        return uid, r.json()["access_token"]

    return make


@pytest.fixture(scope="session")
def new_table(client: AsyncClient, platform_token: str) -> Callable[..., Awaitable[str]]:
    async def make(fields: list[dict[str, Any]], **extra: Any) -> str:
        name = f"tbl_{uuid.uuid4().hex[:10]}"
        r = await client.post(
            f"{P}/system/tables", headers=auth(platform_token), json={"name": name, "fields": fields, **extra}
        )
        assert r.status_code == 201, r.text
        return name

    return make


PRODUCT_FIELDS: list[dict[str, Any]] = [
    {"name": "name", "type": "string", "nullable": False, "searchable": True, "max_length": 100},
    {"name": "sku", "type": "string", "unique": True},
    {"name": "price", "type": "numeric", "precision": 10, "scale": 2},
    {"name": "cost", "type": "numeric", "precision": 10, "scale": 2, "sensitive": True},
    {"name": "status", "type": "enum", "enum_values": ["draft", "live"]},
    {"name": "attrs", "type": "json"},
]
