"""End-to-end API tests through the ASGI app with real PostgreSQL and Redis."""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

from httpx import AsyncClient

from app.container import Container
from tests.integration.conftest import PASSWORD, PRODUCT_FIELDS, P, TenantCtx, auth

NewTenant = Callable[..., Awaitable[TenantCtx]]
NewTable = Callable[..., Awaitable[str]]
NewUser = Callable[..., Awaitable[tuple[str, str]]]


async def test_health_and_ready(client: AsyncClient) -> None:
    assert (await client.get("/health")).json() == {"status": "ok"}
    r = await client.get("/ready")
    assert r.status_code == 200 and r.json()["checks"] == {"database": True, "redis": True}


async def test_routes_use_configured_prefix(client: AsyncClient) -> None:
    assert (await client.post("/auth/login", json={})).status_code == 404
    assert (await client.post(f"{P}/auth/login", json={})).status_code == 422


async def test_login_failures_are_uniform(client: AsyncClient, new_tenant: NewTenant) -> None:
    t = await new_tenant()
    bad = [
        {"tenant": t.slug, "email": t.owner_email, "password": "wrong-password-123"},
        {"tenant": t.slug, "email": "nobody@example.com", "password": PASSWORD},
        {"tenant": "no-such-tenant", "email": t.owner_email, "password": PASSWORD},
    ]
    bodies = []
    for b in bad:
        r = await client.post(f"{P}/auth/login", json=b)
        assert r.status_code == 401
        bodies.append(r.json()["error"]["message"])
    assert len(set(bodies)) == 1


async def test_account_lockout(client: AsyncClient, new_tenant: NewTenant, container: Container) -> None:
    t = await new_tenant()
    for _ in range(container.settings.max_failed_logins):
        await client.post(
            f"{P}/auth/login", json={"tenant": t.slug, "email": t.owner_email, "password": "nope-nope-nope"}
        )
    r = await client.post(
        f"{P}/auth/login", json={"tenant": t.slug, "email": t.owner_email, "password": PASSWORD}
    )
    assert r.status_code == 401  # correct password is refused while locked


async def test_tenant_isolation_through_api(
    client: AsyncClient, new_tenant: NewTenant, new_table: NewTable
) -> None:
    table = await new_table(PRODUCT_FIELDS)
    a, b = await new_tenant(), await new_tenant()
    r = await client.post(f"{P}/data/{table}", headers=a.h, json={"name": "A-only", "sku": "S1"})
    assert r.status_code == 201, r.text
    rid = r.json()["id"]
    # Tenant B cannot see, read, update or delete A's record.
    assert (await client.get(f"{P}/data/{table}", headers=b.h)).json()["items"] == []
    assert (await client.get(f"{P}/data/{table}/{rid}", headers=b.h)).status_code == 404
    assert (await client.patch(f"{P}/data/{table}/{rid}", headers=b.h, json={"name": "x"})).status_code == 404
    assert (await client.delete(f"{P}/data/{table}/{rid}", headers=b.h)).status_code == 404
    # Same SKU in another tenant is fine (uniqueness is per tenant).
    assert (
        await client.post(f"{P}/data/{table}", headers=b.h, json={"name": "B", "sku": "S1"})
    ).status_code == 201
    # Client-supplied tenant identifiers are rejected, never honoured.
    r = await client.post(f"{P}/data/{table}", headers=a.h, json={"name": "x", "tenant_id": b.id})
    assert r.status_code == 422
    r = await client.get(
        f"{P}/data/{table}", headers={**a.h, "X-Tenant-ID": b.id}, params={"tenant_id": b.id}
    )
    assert r.status_code == 422


async def test_crud_filter_sort_search_paginate(
    client: AsyncClient, new_tenant: NewTenant, new_table: NewTable
) -> None:
    table = await new_table(PRODUCT_FIELDS)
    t = await new_tenant()
    for i, (name, price, status) in enumerate(
        [("Oak chair", "10.00", "live"), ("Pine chair", "25.50", "draft"), ("Oak table", "99.99", "live")]
    ):
        r = await client.post(
            f"{P}/data/{table}",
            headers=t.h,
            json={"name": name, "sku": f"SKU{i}", "price": price, "status": status, "attrs": {"i": i}},
        )
        assert r.status_code == 201, r.text
        assert r.json()["price"] == price  # numeric precision preserved as string
    base = f"{P}/data/{table}"
    r = await client.get(base, headers=t.h, params={"filter[price][gte]": "20", "sort": "-price"})
    assert [x["name"] for x in r.json()["items"]] == ["Oak table", "Pine chair"]
    r = await client.get(base, headers=t.h, params={"filter[status][in]": "live", "sort": "name"})
    assert [x["name"] for x in r.json()["items"]] == ["Oak chair", "Oak table"]
    r = await client.get(base, headers=t.h, params={"q": "oak", "sort": "name"})
    assert [x["name"] for x in r.json()["items"]] == ["Oak chair", "Oak table"]
    r = await client.get(base, headers=t.h, params={"filter[name][like]": "%", "sort": "name"})
    assert r.json()["items"] == []  # LIKE wildcards in user input are escaped
    r = await client.get(base, headers=t.h, params={"limit": "2", "sort": "name"})
    assert len(r.json()["items"]) == 2 and r.json()["next_offset"] == 2
    r2 = await client.get(base, headers=t.h, params={"limit": "2", "offset": "2", "sort": "name"})
    assert len(r2.json()["items"]) == 1 and r2.json()["next_offset"] is None
    rid = r.json()["items"][0]["id"]
    r = await client.patch(f"{base}/{rid}", headers=t.h, json={"price": "11.00"})
    assert r.status_code == 200 and r.json()["price"] == "11.00"
    r = await client.get(f"{base}/{rid}", headers=t.h, params={"fields": "id,price"})
    assert set(r.json()) == {"id", "price"}
    assert (await client.delete(f"{base}/{rid}", headers=t.h)).status_code == 204
    assert (await client.get(f"{base}/{rid}", headers=t.h)).status_code == 404


async def test_adversarial_inputs(client: AsyncClient, new_tenant: NewTenant, new_table: NewTable) -> None:
    table = await new_table(PRODUCT_FIELDS)
    t = await new_tenant()
    base = f"{P}/data/{table}"
    attacks: list[dict[str, str]] = [
        {"sort": "name; DROP TABLE app_users"},
        {"sort": "(SELECT 1)"},
        {"filter[name][eq) OR 1=1 --]": "x"},
        {"filter[price][gt]": "0 OR 1=1"},
        {"filter[name][raw]": "1"},
        {"fields": "name,(select password_hash from app_users)"},
        {"limit": "1000000"},
        {"offset": "999999999"},
    ]
    for params in attacks:
        r = await client.get(base, headers=t.h, params=params)
        assert r.status_code == 422, (params, r.text)
        assert "sql" not in r.text.lower() and "select" not in r.json()["error"]["message"].lower()
    for name in ["app_users", "..%2fapp_users", "dt_" + table, "pg_catalog.pg_authid", "x;drop"]:
        r = await client.get(f"{P}/data/{name}", headers=t.h)
        assert r.status_code in (404, 422), name
    # Unique violation surfaces as a generic 409 without constraint names or SQL.
    await client.post(base, headers=t.h, json={"name": "a", "sku": "DUP"})
    r = await client.post(base, headers=t.h, json={"name": "b", "sku": "DUP"})
    assert r.status_code == 409
    assert "uq_" not in r.text and "dt_" not in r.text and "duplicate key" not in r.text.lower()


async def test_field_and_row_level_permissions(
    client: AsyncClient, new_tenant: NewTenant, new_table: NewTable, new_user: NewUser
) -> None:
    table = await new_table(PRODUCT_FIELDS)
    t = await new_tenant()
    res = f"data.{table}"
    r = await client.post(
        f"{P}/roles",
        headers=t.h,
        json={
            "name": "clerk",
            "permissions": [
                {"resource": res, "action": "read", "row_scope": "own", "readable_fields": ["name", "price"]},
                {"resource": res, "action": "create", "writable_fields": ["name", "price"]},
            ],
        },
    )
    assert r.status_code == 201, r.text
    role = r.json()["id"]
    _, tok1 = await new_user(t, [role])
    _, tok2 = await new_user(t, [role])
    await client.post(f"{P}/data/{table}", headers=t.h, json={"name": "admin-row", "cost": "1.00"})

    r = await client.post(f"{P}/data/{table}", headers=auth(tok1), json={"name": "mine", "price": "5.00"})
    assert r.status_code == 201, r.text
    assert set(r.json()) == {"id", "created_at", "updated_at", "created_by", "name", "price"}  # no cost/sku
    rid = r.json()["id"]
    r = await client.post(f"{P}/data/{table}", headers=auth(tok1), json={"name": "x", "cost": "9.00"})
    assert r.status_code == 403  # field not writable

    items = (await client.get(f"{P}/data/{table}", headers=auth(tok1))).json()["items"]
    assert [i["name"] for i in items] == ["mine"]  # own rows only
    assert "cost" not in items[0]
    assert (await client.get(f"{P}/data/{table}", headers=auth(tok2))).json()["items"] == []
    assert (await client.get(f"{P}/data/{table}/{rid}", headers=auth(tok2))).status_code == 404
    assert (
        await client.get(f"{P}/data/{table}", headers=auth(tok1), params={"sort": "cost"})
    ).status_code == 422
    # No update/delete permission at all.
    assert (
        await client.patch(f"{P}/data/{table}/{rid}", headers=auth(tok1), json={"name": "y"})
    ).status_code == 403
    assert (await client.delete(f"{P}/data/{table}/{rid}", headers=auth(tok1))).status_code == 403


async def test_privilege_escalation_blocked(
    client: AsyncClient, new_tenant: NewTenant, new_table: NewTable, new_user: NewUser
) -> None:
    table = await new_table(PRODUCT_FIELDS)
    t = await new_tenant()
    r = await client.post(
        f"{P}/roles",
        headers=t.h,
        json={
            "name": "manager",
            "permissions": [
                {"resource": "roles", "action": "manage"},
                {"resource": "users", "action": "manage"},
                {"resource": f"data.{table}", "action": "read", "readable_fields": ["name"]},
            ],
        },
    )
    manager_role = r.json()["id"]
    mgr_id, mgr = await new_user(t, [manager_role])
    # Cannot grant what it does not hold.
    for perms in (
        [{"resource": f"data.{table}", "action": "delete"}],
        [{"resource": f"data.{table}", "action": "read"}],  # all fields > ["name"]
        [{"resource": "api_keys", "action": "manage"}],
    ):
        r = await client.post(
            f"{P}/roles", headers=auth(mgr), json={"name": f"r{uuid.uuid4().hex[:6]}", "permissions": perms}
        )
        assert r.status_code == 403, perms
    # Cannot mint tenant admins, nor promote itself.
    r = await client.post(
        f"{P}/users",
        headers=auth(mgr),
        json={"email": "x@y.example.com", "password": PASSWORD, "is_tenant_admin": True},
    )
    assert r.status_code == 403
    assert (
        await client.patch(f"{P}/users/{mgr_id}", headers=auth(mgr), json={"is_tenant_admin": True})
    ).status_code == 403
    # Cannot assign a role that holds permissions it lacks.
    r = await client.post(
        f"{P}/roles",
        headers=t.h,
        json={"name": "deleter", "permissions": [{"resource": f"data.{table}", "action": "delete"}]},
    )
    assert r.status_code == 201, r.text
    r = await client.put(
        f"{P}/users/{mgr_id}/roles", headers=auth(mgr), json={"role_ids": [manager_role, r.json()["id"]]}
    )
    assert r.status_code == 403
    # Cannot change tenant rate limits.
    assert (
        await client.put(f"{P}/tenant/rate-limit", headers=auth(mgr), json={"rate_limit_per_min": 5})
    ).status_code == 403
    # Roles of another tenant are invisible.
    other = await new_tenant()
    r = await client.put(f"{P}/users/{mgr_id}/roles", headers=t.h, json={"role_ids": [manager_role]})
    assert r.status_code == 204
    other_roles = (await client.get(f"{P}/roles", headers=other.h)).json()["items"]
    r = await client.put(f"{P}/users/{mgr_id}/roles", headers=t.h, json={"role_ids": [other_roles[0]["id"]]})
    assert r.status_code == 422


async def test_permission_changes_apply_immediately(
    client: AsyncClient, new_tenant: NewTenant, new_table: NewTable, new_user: NewUser
) -> None:
    table = await new_table(PRODUCT_FIELDS)
    t = await new_tenant()
    r = await client.post(
        f"{P}/roles",
        headers=t.h,
        json={"name": "reader", "permissions": [{"resource": f"data.{table}", "action": "read"}]},
    )
    uid, tok = await new_user(t, [r.json()["id"]])
    assert (await client.get(f"{P}/data/{table}", headers=auth(tok))).status_code == 200  # matrix now cached
    assert (await client.put(f"{P}/users/{uid}/roles", headers=t.h, json={"role_ids": []})).status_code == 204
    assert (await client.get(f"{P}/data/{table}", headers=auth(tok))).status_code == 404
    # Deactivation revokes sessions and blocks access at once.
    assert (await client.patch(f"{P}/users/{uid}", headers=t.h, json={"is_active": False})).status_code == 200
    assert (await client.get(f"{P}/auth/me", headers=auth(tok))).status_code == 401


async def test_refresh_rotation_and_reuse_detection(client: AsyncClient, new_tenant: NewTenant) -> None:
    t = await new_tenant()
    r1 = await client.post(f"{P}/auth/refresh", json={"refresh_token": t.refresh_token})
    assert r1.status_code == 200
    new_access, new_refresh = r1.json()["access_token"], r1.json()["refresh_token"]
    assert new_refresh != t.refresh_token
    assert (await client.get(f"{P}/auth/me", headers=auth(new_access))).status_code == 200
    # Replaying the already-used refresh token revokes the whole session.
    assert (
        await client.post(f"{P}/auth/refresh", json={"refresh_token": t.refresh_token})
    ).status_code == 401
    assert (await client.get(f"{P}/auth/me", headers=auth(new_access))).status_code == 401
    assert (await client.post(f"{P}/auth/refresh", json={"refresh_token": new_refresh})).status_code == 401


async def test_concurrent_refresh_only_one_wins(client: AsyncClient, new_tenant: NewTenant) -> None:
    t = await new_tenant()
    results = await asyncio.gather(
        *[client.post(f"{P}/auth/refresh", json={"refresh_token": t.refresh_token}) for _ in range(5)]
    )
    assert sorted(r.status_code for r in results).count(200) == 1


async def test_logout_revokes_access_token(client: AsyncClient, new_tenant: NewTenant) -> None:
    t = await new_tenant()
    assert (await client.post(f"{P}/auth/logout", headers=t.h)).status_code == 204
    assert (await client.get(f"{P}/auth/me", headers=t.h)).status_code == 401
    assert (
        await client.post(f"{P}/auth/refresh", json={"refresh_token": t.refresh_token})
    ).status_code == 401


async def test_token_confusion(client: AsyncClient, new_tenant: NewTenant, platform_token: str) -> None:
    t = await new_tenant()
    assert (await client.get(f"{P}/auth/me", headers=auth(platform_token))).status_code == 401
    assert (await client.get(f"{P}/platform/tenants", headers=t.h)).status_code == 401
    assert (await client.get(f"{P}/auth/me", headers=auth(t.refresh_token))).status_code == 401
    assert (await client.get(f"{P}/system/tables", headers=t.h)).status_code == 401


async def test_api_keys_lifecycle(client: AsyncClient, new_tenant: NewTenant, new_table: NewTable) -> None:
    table = await new_table(PRODUCT_FIELDS)
    t = await new_tenant()
    r = await client.post(
        f"{P}/api-keys", headers=t.h, json={"name": "reader", "scopes": [f"data.{table}:read"]}
    )
    assert r.status_code == 201, r.text
    key, row_id = r.json()["api_key"], r.json()["id"]
    kh = {"X-API-Key": key}
    assert (await client.get(f"{P}/data/{table}", headers=kh)).status_code == 200  # DB path, then cached
    assert (await client.get(f"{P}/data/{table}", headers=kh)).status_code == 200  # cache path
    assert (
        await client.post(f"{P}/data/{table}", headers=kh, json={"name": "x"})
    ).status_code == 403  # scope
    assert (
        await client.post(f"{P}/api-keys", headers=kh, json={"name": "n", "scopes": ["users:read"]})
    ).status_code == 403
    listing = (await client.get(f"{P}/api-keys", headers=t.h)).json()["items"]
    assert all("api_key" not in k and "digest" not in k for k in listing)
    # A tampered secret with a valid key id is rejected.
    bad = key[:-1] + ("A" if key[-1] != "A" else "B")
    assert (await client.get(f"{P}/data/{table}", headers={"X-API-Key": bad})).status_code == 401
    # Revocation is effective on the very next request, despite the warm cache.
    assert (await client.post(f"{P}/api-keys/{row_id}/revoke", headers=t.h)).status_code == 204
    assert (await client.get(f"{P}/data/{table}", headers=kh)).status_code == 401
    # Another tenant cannot revoke or see this tenant's keys.
    other = await new_tenant()
    assert (await client.post(f"{P}/api-keys/{row_id}/revoke", headers=other.h)).status_code == 404


async def test_api_key_rotation(client: AsyncClient, new_tenant: NewTenant) -> None:
    t = await new_tenant()
    r = await client.post(f"{P}/api-keys", headers=t.h, json={"name": "svc", "scopes": ["users:read"]})
    old_key, old_id = r.json()["api_key"], r.json()["id"]
    r = await client.post(f"{P}/api-keys/{old_id}/rotate", headers=t.h, json={"grace_seconds": 0})
    assert r.status_code == 201
    new_key = r.json()["api_key"]
    assert r.json()["rotated_from_id"] == old_id
    assert (await client.get(f"{P}/auth/me", headers={"X-API-Key": old_key})).status_code == 401
    assert (await client.get(f"{P}/auth/me", headers={"X-API-Key": new_key})).status_code == 200


async def test_api_key_scope_delegation_limits(
    client: AsyncClient, new_tenant: NewTenant, new_table: NewTable, new_user: NewUser
) -> None:
    table = await new_table(PRODUCT_FIELDS)
    t = await new_tenant()
    r = await client.post(
        f"{P}/roles",
        headers=t.h,
        json={
            "name": "km",
            "permissions": [
                {"resource": "api_keys", "action": "manage"},
                {"resource": f"data.{table}", "action": "read"},
            ],
        },
    )
    _, tok = await new_user(t, [r.json()["id"]])
    ok = await client.post(
        f"{P}/api-keys", headers=auth(tok), json={"name": "a", "scopes": [f"data.{table}:read"]}
    )
    assert ok.status_code == 201
    for scopes in ([f"data.{table}:delete"], ["data.*:read"], ["roles:manage"], ["bogus scope"]):
        r = await client.post(f"{P}/api-keys", headers=auth(tok), json={"name": "b", "scopes": scopes})
        assert r.status_code in (403, 422), scopes


async def test_idempotency(client: AsyncClient, new_tenant: NewTenant, new_table: NewTable) -> None:
    table = await new_table(
        [{"name": "amount", "type": "numeric", "nullable": False}], require_idempotency=True
    )
    t = await new_tenant()
    url = f"{P}/data/{table}"
    assert (await client.post(url, headers=t.h, json={"amount": "10.00"})).status_code == 422  # key required
    h = {**t.h, "Idempotency-Key": f"order-{uuid.uuid4()}"}
    r1 = await client.post(url, headers=h, json={"amount": "10.00"})
    r2 = await client.post(url, headers=h, json={"amount": "10.00"})
    assert r1.status_code == r2.status_code == 201
    assert r1.json()["id"] == r2.json()["id"]
    assert r2.headers.get("Idempotent-Replayed") == "true"
    assert len((await client.get(url, headers=t.h)).json()["items"]) == 1
    r3 = await client.post(url, headers=h, json={"amount": "99.00"})
    assert r3.status_code == 422  # key reuse with a different body
    # Keys are scoped per principal: another tenant using the same key is unaffected.
    other = await new_tenant()
    r4 = await client.post(
        url, headers={**other.h, "Idempotency-Key": h["Idempotency-Key"]}, json={"amount": "10.00"}
    )
    assert r4.status_code == 201 and r4.json()["id"] != r1.json()["id"]


async def test_concurrent_idempotent_requests_execute_once(
    client: AsyncClient, new_tenant: NewTenant, new_table: NewTable
) -> None:
    table = await new_table([{"name": "amount", "type": "numeric"}], require_idempotency=True)
    t = await new_tenant()
    h = {**t.h, "Idempotency-Key": f"burst-{uuid.uuid4()}"}
    rs = await asyncio.gather(
        *[client.post(f"{P}/data/{table}", headers=h, json={"amount": "1.00"}) for _ in range(8)]
    )
    assert {r.status_code for r in rs} <= {201, 409}
    assert len((await client.get(f"{P}/data/{table}", headers=t.h)).json()["items"]) == 1


async def test_rate_limits(
    client: AsyncClient, new_tenant: NewTenant, platform_token: str, container: Container
) -> None:
    t = await new_tenant()
    r = await client.patch(
        f"{P}/platform/tenants/{t.id}", headers=auth(platform_token), json={"rate_limit_per_min": 3}
    )
    assert r.status_code == 200
    codes = [(await client.get(f"{P}/auth/me", headers=t.h)).status_code for _ in range(5)]
    assert codes[:3] == [200, 200, 200] and codes[3] == 429
    r = await client.get(f"{P}/auth/me", headers=t.h)
    assert int(r.headers["Retry-After"]) >= 1
    # Tenant admins cannot exceed the platform maximum.
    other = await new_tenant()
    too_high = container.settings.rl_tenant_max_per_min + 1
    assert (
        await client.put(f"{P}/tenant/rate-limit", headers=other.h, json={"rate_limit_per_min": too_high})
    ).status_code == 422
    assert (
        await client.put(f"{P}/tenant/rate-limit", headers=other.h, json={"rate_limit_per_min": 500})
    ).status_code == 200


async def test_suspended_tenant_is_locked_out(
    client: AsyncClient, new_tenant: NewTenant, platform_token: str
) -> None:
    t = await new_tenant()
    r = await client.patch(
        f"{P}/platform/tenants/{t.id}", headers=auth(platform_token), json={"status": "suspended"}
    )
    assert r.status_code == 200
    assert (await client.get(f"{P}/auth/me", headers=t.h)).status_code == 401
    r = await client.post(
        f"{P}/auth/login", json={"tenant": t.slug, "email": t.owner_email, "password": PASSWORD}
    )
    assert r.status_code == 401


async def test_request_size_limit(
    client: AsyncClient, new_tenant: NewTenant, new_table: NewTable, container: Container
) -> None:
    table = await new_table([{"name": "blob", "type": "text"}])
    t = await new_tenant()
    big = "x" * (container.settings.max_request_bytes + 10)
    r = await client.post(
        f"{P}/data/{table}",
        headers={**t.h, "Content-Type": "application/json"},
        content=f'{{"blob": "{big}"}}',
    )
    assert r.status_code == 413


async def test_ddl_dry_run_and_validation(
    client: AsyncClient, platform_token: str, new_tenant: NewTenant
) -> None:
    name = f"dry_{uuid.uuid4().hex[:8]}"
    r = await client.post(
        f"{P}/system/tables",
        headers=auth(platform_token),
        params={"dry_run": "true"},
        json={"name": name, "fields": [{"name": "a", "type": "text"}]},
    )
    assert r.status_code == 201 and r.json()["dry_run"] is True
    assert any("ROW LEVEL SECURITY" in s for s in r.json()["statements"])
    t = await new_tenant()
    assert (await client.get(f"{P}/data/{name}", headers=t.h)).status_code == 404  # not created
    bad: list[dict[str, Any]] = [
        {"name": "x; drop table app_users", "fields": [{"name": "a", "type": "text"}]},
        {"name": "ok_name", "fields": [{"name": "a", "type": "text", "default": "now()"}]},
        {"name": "ok_name", "fields": [{"name": "a", "type": "reference", "ref_table": "app_users"}]},
    ]
    for body in bad:
        r = await client.post(f"{P}/system/tables", headers=auth(platform_token), json=body)
        assert r.status_code == 422, body
    # Tenant tokens can never run DDL.
    r = await client.post(
        f"{P}/system/tables", headers=t.h, json={"name": "evil", "fields": [{"name": "a", "type": "text"}]}
    )
    assert r.status_code == 401


async def test_reference_expansion_and_cross_tenant_references(
    client: AsyncClient, new_tenant: NewTenant, new_table: NewTable
) -> None:
    customers = await new_table([{"name": "name", "type": "string"}])
    orders = await new_table(
        [
            {"name": "customer", "type": "reference", "ref_table": customers},
            {"name": "total", "type": "numeric"},
        ]
    )
    a, b = await new_tenant(), await new_tenant()
    cust_a = (await client.post(f"{P}/data/{customers}", headers=a.h, json={"name": "Alice"})).json()["id"]
    r = await client.post(f"{P}/data/{orders}", headers=a.h, json={"customer": cust_a, "total": "5.00"})
    assert r.status_code == 201
    r = await client.get(f"{P}/data/{orders}", headers=a.h, params={"expand": "customer"})
    assert r.json()["items"][0]["_expanded"]["customer"]["name"] == "Alice"
    # Tenant B cannot reference tenant A's customer (composite FK), and learns nothing beyond a 409.
    r = await client.post(f"{P}/data/{orders}", headers=b.h, json={"customer": cust_a, "total": "1.00"})
    assert r.status_code == 409


async def test_audit_trail(client: AsyncClient, new_tenant: NewTenant, new_table: NewTable) -> None:
    table = await new_table(PRODUCT_FIELDS)
    t = await new_tenant()
    await client.post(f"{P}/data/{table}", headers=t.h, json={"name": "audited", "cost": "3.00"})
    items = (await client.get(f"{P}/audit-logs", headers=t.h)).json()["items"]
    create = next(i for i in items if i["action"] == "data.create")
    assert create["detail"]["values"]["name"] == "audited"
    assert create["detail"]["values"]["cost"] == "[REDACTED]"  # sensitive field
    assert any(i["action"] == "auth.login" for i in items)
    other = await new_tenant()
    other_items = (await client.get(f"{P}/audit-logs", headers=other.h)).json()["items"]
    assert all(i["resource"] != f"data.{table}" for i in other_items)
