"""Generic dynamic CRUD: /data/{table_name}[/{record_id}]."""

from __future__ import annotations

import json
import uuid
from typing import Any

import sqlalchemy as sa
from fastapi import APIRouter, Depends, Path, Request
from fastapi.responses import JSONResponse, Response

from app.api.deps import audit_ctx, container, rate_limited
from app.container import Container
from app.core.errors import NotFound, ValidationFailed
from app.dynamic import crud
from app.dynamic.query import parse_list_query, readable_fields, validate_write
from app.dynamic.schema import TableMeta
from app.models import Action, CrudMode
from app.services.audit import record
from app.services.authz import Grant, Principal
from app.services.outbox import emit

router = APIRouter(prefix="/data", tags=["data"])

TableName = Path(..., pattern=r"^[a-z][a-z0-9_]{0,62}$")


async def _resolve(
    c: Container, table_name: str, principal: Principal, action: Action
) -> tuple[TableMeta, sa.Table, Grant]:
    meta = await c.schema.get_table(table_name)
    # Unknown tables and tables the caller cannot use look identical (404): no table enumeration.
    if meta is None:
        raise NotFound()
    crud.check_mode(meta, action)
    grant = principal.grant(meta.resource, action)
    if grant is None:
        if principal.grant(meta.resource, Action.read) is None:
            raise NotFound()
        principal.require(meta.resource, action)
    if grant is None:  # unreachable: require() raised above
        raise NotFound()
    return meta, await c.schema.sa_table(meta), grant


async def _emit(
    c: Container, request: Request, p: Principal, s: Any, event: str, table: str, record_id: uuid.UUID
) -> None:
    """Shared tenants: the event joins the data transaction (transactional outbox)."""
    if not request.state.tenant_dedicated:
        await emit(s, p.tenant_id, event, {"table": table, "record_id": record_id})


async def _emit_after(
    c: Container, request: Request, p: Principal, event: str, table: str, record_id: uuid.UUID
) -> None:
    """Dedicated tenants: the data lives in another database, so the outbox row (control plane) is
    written right after the data commit. A crash in between loses that one event; documented."""
    if request.state.tenant_dedicated:
        async with c.db.tenant_session(p.tenant_id, user_id=p.user_id) as cs:
            await emit(cs, p.tenant_id, event, {"table": table, "record_id": record_id})
            await cs.commit()


async def _json_body(request: Request) -> Any:
    raw = await request.body()
    if not raw:
        raise ValidationFailed("Request body is required.")
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        raise ValidationFailed("Request body must be valid JSON.") from e


async def _expand(
    c: Container,
    session: Any,
    principal: Principal,
    meta: TableMeta,
    items: list[dict[str, Any]],
    fields: list[str],
) -> None:
    fmap = meta.field_map
    for name in fields:
        ref_name = fmap[name].ref_table
        if ref_name is None:
            continue
        ref_meta = await c.schema.get_table(ref_name)
        ref_grant = principal.grant(f"data.{ref_name}", Action.read) if ref_meta else None
        if ref_meta is None or ref_grant is None:
            continue  # silently omit expansions the caller cannot read
        ids = {uuid.UUID(i[name]) for i in items if i.get(name)}
        if not ids:
            continue
        table = await c.schema.sa_table(ref_meta)
        cols = list(readable_fields(ref_meta, ref_grant))
        stmt = sa.select(*[table.c[x] for x in cols]).where(table.c.id.in_(ids))
        if ref_grant.row_scope.value == "own":
            stmt = stmt.where(table.c.created_by == principal.user_id)
        found = {str(r._mapping["id"]): crud.serialize(r, cols) for r in (await session.execute(stmt)).all()}
        for i in items:
            i.setdefault("_expanded", {})[name] = found.get(i.get(name) or "")


@router.get("")
async def list_tables(
    p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    """Tables the caller can use, with only the fields their role lets them read, and the actions
    they may take. Tables without read access are omitted, as with every other data endpoint."""
    out: list[dict[str, Any]] = []
    for meta in await c.schema.all_tables():
        if meta.crud_mode == CrudMode.disabled:
            continue
        read = p.grant(meta.resource, Action.read)
        if read is None:
            continue
        actions = ["read"]
        if meta.crud_mode == CrudMode.full:
            actions += [
                a.value for a in (Action.create, Action.update, Action.delete) if p.grant(meta.resource, a)
            ]
        write = p.grant(meta.resource, Action.update) or p.grant(meta.resource, Action.create)
        fields = [
            {**f, "writable": bool(write and write.can_write(f["name"]))}
            for f in meta.to_json()["fields"]
            if read.can_read(f["name"])
        ]
        out.append(
            {
                "name": meta.name,
                "crud_mode": meta.crud_mode.value,
                "require_idempotency": meta.require_idempotency,
                "actions": actions,
                "searchable": [f for f in meta.searchable if read.can_read(f)],
                "fields": fields,
            }
        )
    return {"items": out}


@router.get("/{table_name}")
async def list_records(
    request: Request,
    table_name: str = TableName,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    meta, table, grant = await _resolve(c, table_name, p, Action.read)
    q = parse_list_query(list(request.query_params.multi_items()), meta, grant, c.settings)
    async with c.tenant_db.session(p, dedicated=request.state.tenant_dedicated) as s:
        items, has_more = await crud.list_rows(s, table, meta, p, grant, q)
        if q.expand:
            await _expand(c, s, p, meta, items, q.expand)
    return {
        "items": items,
        "limit": q.limit,
        "offset": q.offset,
        "next_offset": q.offset + q.limit
        if has_more and q.offset + q.limit <= c.settings.max_offset
        else None,
    }


@router.get("/{table_name}/{record_id}")
async def get_record(
    request: Request,
    record_id: uuid.UUID,
    table_name: str = TableName,
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    meta, table, grant = await _resolve(c, table_name, p, Action.read)
    params = list(request.query_params.multi_items())
    if any(k not in ("fields", "expand") for k, _ in params):
        raise ValidationFailed("Only 'fields' and 'expand' are supported here.")
    q = parse_list_query(params, meta, grant, c.settings)
    async with c.tenant_db.session(p, dedicated=request.state.tenant_dedicated) as s:
        item = await crud.get_row(s, table, meta, p, grant, record_id, q.fields)
        if q.expand:
            await _expand(c, s, p, meta, [item], q.expand)
    return item


@router.post("/{table_name}", status_code=201)
async def create_record(
    request: Request,
    table_name: str = TableName,
    p: Principal = Depends(rate_limited("data.write")),
    c: Container = Depends(container),
) -> Response:
    meta, table, grant = await _resolve(c, table_name, p, Action.create)

    async def handler() -> Response:
        values = validate_write(meta, grant, await _json_body(request), partial=False)
        read_grant = p.grant(meta.resource, Action.read)
        async with c.tenant_db.session(p, dedicated=request.state.tenant_dedicated) as s:
            record_id, out = await crud.insert_row(s, table, meta, p, read_grant, values)
            await record(
                s,
                audit_ctx(request, p),
                tenant_id=p.tenant_id,
                action="data.create",
                resource=meta.resource,
                resource_id=str(record_id),
                detail={"values": crud.audit_values(meta, values)},
            )
            await _emit(c, request, p, s, "data.created", meta.name, record_id)
            await s.commit()
        await _emit_after(c, request, p, "data.created", meta.name, record_id)
        return JSONResponse(out, status_code=201)

    return await c.idempotency.run(request, p, required=meta.require_idempotency, handler=handler)


@router.patch("/{table_name}/{record_id}")
async def update_record(
    request: Request,
    record_id: uuid.UUID,
    table_name: str = TableName,
    p: Principal = Depends(rate_limited("data.write")),
    c: Container = Depends(container),
) -> Response:
    meta, table, grant = await _resolve(c, table_name, p, Action.update)

    async def handler() -> Response:
        values = validate_write(meta, grant, await _json_body(request), partial=True)
        read_grant = p.grant(meta.resource, Action.read)
        async with c.tenant_db.session(p, dedicated=request.state.tenant_dedicated) as s:
            out = await crud.update_row(s, table, meta, p, grant, read_grant, record_id, values)
            await record(
                s,
                audit_ctx(request, p),
                tenant_id=p.tenant_id,
                action="data.update",
                resource=meta.resource,
                resource_id=str(record_id),
                detail={"values": crud.audit_values(meta, values)},
            )
            await _emit(c, request, p, s, "data.updated", meta.name, record_id)
            await s.commit()
        await _emit_after(c, request, p, "data.updated", meta.name, record_id)
        return JSONResponse(out)

    return await c.idempotency.run(request, p, required=False, handler=handler)


@router.delete("/{table_name}/{record_id}", status_code=204)
async def delete_record(
    request: Request,
    record_id: uuid.UUID,
    table_name: str = TableName,
    p: Principal = Depends(rate_limited("data.write")),
    c: Container = Depends(container),
) -> Response:
    meta, table, grant = await _resolve(c, table_name, p, Action.delete)
    async with c.tenant_db.session(p, dedicated=request.state.tenant_dedicated) as s:
        await crud.delete_row(s, table, p, grant, record_id)
        await record(
            s,
            audit_ctx(request, p),
            tenant_id=p.tenant_id,
            action="data.delete",
            resource=meta.resource,
            resource_id=str(record_id),
        )
        await _emit(c, request, p, s, "data.deleted", meta.name, record_id)
        await s.commit()
    await _emit_after(c, request, p, "data.deleted", meta.name, record_id)
    return Response(status_code=204)
