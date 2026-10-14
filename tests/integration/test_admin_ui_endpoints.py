"""Endpoints added for the admin frontend: table discovery, user role ids, platform table fields."""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from httpx import AsyncClient

from tests.integration.conftest import P, TenantCtx, auth

NewTenant = Callable[..., Awaitable[TenantCtx]]
NewTable = Callable[..., Awaitable[str]]
NewUser = Callable[..., Awaitable[tuple[str, str]]]


async def test_table_discovery_respects_permissions(
    client: AsyncClient, new_tenant: NewTenant, new_table: NewTable, new_user: NewUser
) -> None:
    table = await new_table(
        [{"name": "title", "type": "string", "searchable": True}, {"name": "cost", "type": "numeric"}]
    )
    hidden = await new_table([{"name": "x", "type": "string"}])
    t = await new_tenant()

    admin_view = {x["name"]: x for x in (await client.get(f"{P}/data", headers=t.h)).json()["items"]}
    assert {table, hidden} <= set(admin_view)
    assert admin_view[table]["actions"] == ["read", "create", "update", "delete"]
    assert {f["name"] for f in admin_view[table]["fields"]} == {"title", "cost"}
    assert all(f["writable"] for f in admin_view[table]["fields"])
    assert admin_view[table]["searchable"] == ["title"]

    role = await client.post(
        f"{P}/roles",
        headers=t.h,
        json={
            "name": "reader",
            "permissions": [
                {"resource": f"data.{table}", "action": "read", "readable_fields": ["title"]},
                {"resource": f"data.{table}", "action": "update", "writable_fields": ["title"]},
            ],
        },
    )
    assert role.status_code == 201, role.text
    uid, token = await new_user(t, [role.json()["id"]])
    staff_view = {x["name"]: x for x in (await client.get(f"{P}/data", headers=auth(token))).json()["items"]}
    assert hidden not in staff_view  # no grant: not even listed
    assert staff_view[table]["actions"] == ["read", "update"]
    assert [f["name"] for f in staff_view[table]["fields"]] == ["title"]  # cost is not readable
    assert staff_view[table]["fields"][0]["writable"] is True

    users = {u["id"]: u for u in (await client.get(f"{P}/users", headers=t.h)).json()["items"]}
    assert users[uid]["role_ids"] == [role.json()["id"]]
    assert "last_login_at" in users[uid] and "password_hash" not in users[uid]


async def test_platform_table_list_includes_fields(
    client: AsyncClient, platform_token: str, new_table: NewTable
) -> None:
    table = await new_table([{"name": "sku", "type": "string", "unique": True}])
    items = {
        x["name"]: x
        for x in (await client.get(f"{P}/system/tables", headers=auth(platform_token))).json()["items"]
    }
    assert [f["name"] for f in items[table]["fields"]] == ["sku"]
    assert items[table]["fields"][0]["is_unique"] is True
