"""Dynamic schema changes: PATCH (fields, concurrent indexes, search), DELETE, ledger, snapshots."""

from __future__ import annotations

import copy
import hashlib
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

import pytest
from httpx import AsyncClient
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError

from app.container import Container
from app.dynamic.schema import physical_name
from app.services.audit import AuditContext
from app.services.schema import snapshot_checksum
from tests.integration.conftest import P, TenantCtx, auth

NewTenant = Callable[..., Awaitable[TenantCtx]]
NewTable = Callable[..., Awaitable[str]]


async def table_id(client: AsyncClient, token: str, name: str) -> str:
    items = (await client.get(f"{P}/system/tables", headers=auth(token))).json()["items"]
    return str(next(t["id"] for t in items if t["name"] == name))


async def indexes(container: Container, table: str) -> dict[str, bool]:
    async with container.db.ddl_session() as s:
        rows = (
            await s.execute(
                text(
                    "SELECT c.relname, i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                    "JOIN pg_class t ON t.oid = i.indrelid WHERE t.relname = :t"
                ),
                {"t": physical_name(table)},
            )
        ).all()
    return {r.relname: r.indisvalid for r in rows}


async def test_patch_fields_indexes_and_search(
    client: AsyncClient, platform_token: str, container: Container, new_tenant: NewTenant, new_table: NewTable
) -> None:
    name = await new_table(
        [{"name": "title", "type": "string", "searchable": True}, {"name": "legacy", "type": "text"}]
    )
    tid = await table_id(client, platform_token, name)
    t = await new_tenant()
    await client.post(f"{P}/data/{name}", headers=t.h, json={"title": "red chair", "legacy": "x"})
    url = f"{P}/system/tables/{tid}"
    h = auth(platform_token)

    bad = await client.patch(
        url, headers=h, json={"add_fields": [{"name": "sku", "type": "string", "nullable": False}]}
    )
    assert bad.status_code == 422  # existing rows would violate NOT NULL

    patch = {
        "add_fields": [
            {"name": "note", "type": "string", "searchable": True},
            {"name": "qty", "type": "integer"},
        ],
        "drop_fields": ["legacy"],
        "add_indexes": ["qty"],
        "require_idempotency": False,
    }
    dry = await client.patch(url, headers=h, params={"dry_run": "true"}, json=patch)
    assert dry.status_code == 200 and dry.json()["dry_run"] is True
    assert any("DROP COLUMN" in s for s in dry.json()["statements"])
    assert any("CONCURRENTLY" in s for s in dry.json()["concurrent"])
    assert "legacy" in (await client.get(f"{P}/data/{name}", headers=t.h)).json()["items"][0]  # unchanged

    r = await client.patch(url, headers=h, json=patch)
    assert r.status_code == 200, r.text
    ix = await indexes(container, name)
    assert ix[f"ix_dt_{name}_qty"] is True and ix[f"ix_dt_{name}_search"] is True and all(ix.values())
    rec = (await client.get(f"{P}/data/{name}", headers=t.h)).json()["items"][0]
    assert "legacy" not in rec and rec["note"] is None
    assert (
        await client.post(f"{P}/data/{name}", headers=t.h, json={"title": "x", "legacy": "y"})
    ).status_code == 422
    await client.post(
        f"{P}/data/{name}", headers=t.h, json={"title": "plain", "note": "walnut finish", "qty": 3}
    )
    found = (await client.get(f"{P}/data/{name}", headers=t.h, params={"q": "walnut"})).json()["items"]
    assert [x["title"] for x in found] == ["plain"]  # search covers the newly added searchable field

    r = await client.patch(url, headers=h, json={"drop_indexes": ["qty"]})
    assert r.status_code == 200
    assert f"ix_dt_{name}_qty" not in await indexes(container, name)
    assert (await client.patch(url, headers=h, json={"drop_indexes": ["qty"]})).status_code == 422
    assert (await client.patch(url, headers=h, json={"add_indexes": ["nope"]})).status_code == 422


async def test_ledger_records_checksummed_statements(
    client: AsyncClient, platform_token: str, container: Container, new_table: NewTable
) -> None:
    name = await new_table([{"name": "a", "type": "text"}])
    tid = await table_id(client, platform_token, name)
    await client.patch(
        f"{P}/system/tables/{tid}",
        headers=auth(platform_token),
        json={"add_fields": [{"name": "b", "type": "integer"}]},
    )
    rows = await container.schema.ledger_since(0)
    mine = [m for m in rows if m.table_name == name]
    assert [m.operation for m in mine] == ["create_table", "alter_table"]
    for m in mine:
        assert m.checksum == hashlib.sha256("\n;\n".join(m.statements).encode()).hexdigest()
    assert all(not any("GRANT" in s for s in m.statements) for m in mine)  # role-independent, replayable


async def test_delete_table(
    client: AsyncClient, platform_token: str, container: Container, new_tenant: NewTenant, new_table: NewTable
) -> None:
    parent = await new_table([{"name": "n", "type": "text"}])
    child = await new_table([{"name": "p", "type": "reference", "ref_table": parent}])
    h = auth(platform_token)
    pid, cid = await table_id(client, platform_token, parent), await table_id(client, platform_token, child)
    assert (
        await client.delete(f"{P}/system/tables/{pid}", headers=h, params={"confirm": parent})
    ).status_code == 409
    assert (
        await client.delete(f"{P}/system/tables/{cid}", headers=h, params={"confirm": "wrong"})
    ).status_code == 422
    dry = await client.delete(
        f"{P}/system/tables/{cid}", headers=h, params={"confirm": child, "dry_run": "true"}
    )
    assert dry.json()["statements"] == [f'DROP TABLE "tenant_data"."dt_{child}"']
    assert (
        await client.delete(f"{P}/system/tables/{cid}", headers=h, params={"confirm": child})
    ).status_code == 200
    t = await new_tenant()
    assert (await client.get(f"{P}/data/{child}", headers=t.h)).status_code == 404
    async with container.db.ddl_session() as s:
        exists = (
            await s.execute(text("SELECT to_regclass(:n)"), {"n": f"tenant_data.dt_{child}"})
        ).scalar_one()
    assert exists is None
    assert (
        await client.delete(f"{P}/system/tables/{pid}", headers=h, params={"confirm": parent})
    ).status_code == 200


async def test_failed_concurrent_index_is_cleaned_up(
    container: Container, new_tenant: NewTenant, new_table: NewTable, client: AsyncClient
) -> None:
    name = await new_table([{"name": "code", "type": "string"}])
    t = await new_tenant()
    for _ in range(2):
        await client.post(f"{P}/data/{name}", headers=t.h, json={"code": "DUP"})
    meta = await container.schema.get_table(name)
    assert meta is not None
    bad = f'CREATE UNIQUE INDEX CONCURRENTLY "ix_dt_{name}_boom" ON "tenant_data"."dt_{name}" (code)'
    with pytest.raises(DBAPIError):
        await container.schema._run_concurrently(
            operation="create_index_concurrently",
            meta=meta,
            statement=bad,
            admin_id=uuid.uuid4(),
            ctx=AuditContext("platform", None, None, None),
        )
    assert f"ix_dt_{name}_boom" not in await indexes(container, name)  # INVALID index removed


async def test_snapshot_export_and_import(
    client: AsyncClient, platform_token: str, new_table: NewTable
) -> None:
    h = auth(platform_token)
    base = await new_table([{"name": "x", "type": "text"}])
    snap = (await client.post(f"{P}/system/snapshot/export", headers=h)).json()
    assert snap["checksum"] == snapshot_checksum(snap["tables"])
    names = [t["name"] for t in snap["tables"]]
    r = await client.post(f"{P}/system/snapshot/import", headers=h, json={"snapshot": snap, "dry_run": True})
    assert (
        r.status_code == 200
        and all(p["action"] == "none" for p in r.json()["plan"])
        and r.json()["conflicts"] == []
    )

    tampered = copy.deepcopy(snap)
    tampered["tables"][0]["fields"].append({"name": "evil", "type": "text"})
    assert (
        await client.post(f"{P}/system/snapshot/import", headers=h, json={"snapshot": tampered})
    ).status_code == 422

    new_name = f"snap_{uuid.uuid4().hex[:8]}"
    changed = copy.deepcopy(snap)
    for tbl in changed["tables"]:
        if tbl["name"] == base:
            tbl["fields"].append({"name": "added", "type": "integer", "nullable": True})
    changed["tables"].append(
        {
            "name": new_name,
            "label": None,
            "crud_mode": "full",
            "require_idempotency": False,
            "fields": [{"name": "ref", "type": "reference", "ref_table": base}],
        }
    )
    changed["checksum"] = snapshot_checksum(changed["tables"])
    plan = (
        await client.post(
            f"{P}/system/snapshot/import", headers=h, json={"snapshot": changed, "dry_run": True}
        )
    ).json()["plan"]
    actions: dict[str, Any] = {p["table"]: p for p in plan}
    assert actions[new_name]["action"] == "create" and actions[base]["add_fields"] == ["added"]
    r = await client.post(
        f"{P}/system/snapshot/import", headers=h, json={"snapshot": changed, "dry_run": False}
    )
    assert r.status_code == 200, r.text
    tables = {
        t["name"]: t for t in (await client.post(f"{P}/system/snapshot/export", headers=h)).json()["tables"]
    }
    assert new_name in tables and "added" in [f["name"] for f in tables[base]["fields"]]
    assert set(names) <= set(tables)

    conflicting = copy.deepcopy(snap)
    for tbl in conflicting["tables"]:
        if tbl["name"] == base:
            tbl["fields"][0]["type"] = "integer"
    conflicting["checksum"] = snapshot_checksum(conflicting["tables"])
    r = await client.post(
        f"{P}/system/snapshot/import", headers=h, json={"snapshot": conflicting, "dry_run": False}
    )
    assert r.status_code == 409  # nothing applied when any conflict exists
