"""Generic CRUD over registered dynamic tables.

Layers of protection, outermost first:
  1. The table must be registered in ``app_tables``; its physical name comes from the registry, never
     from the URL. Only ``tenant_data`` tables are reachable, never control-plane or domain tables.
  2. ``crud_mode`` lets domain-managed tables be read-only or hidden from generic CRUD, so business
     invariants (inventory ledger, payments, orders) are enforced only by their own services.
  3. RBAC: table-level action grant, row scope (``own`` adds ``created_by = <user>``), and field-level
     read/write allowlists.
  4. PostgreSQL RLS on ``tenant_id`` (transaction-scoped context) as the final backstop; composite
     ``(tenant_id, id)`` foreign keys prevent cross-tenant references.
All SQL is built with SQLAlchemy expressions and bound parameters.
"""

from __future__ import annotations

import datetime as dt
import decimal
import uuid
from typing import Any, cast

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import Forbidden, NotFound
from app.dynamic.query import ListQuery, readable_fields
from app.dynamic.schema import TableMeta, search_vector
from app.models import Action, CrudMode, RowScope
from app.services.authz import Grant, Principal

REDACTED = "[REDACTED]"


def check_mode(meta: TableMeta, action: Action) -> None:
    if meta.crud_mode == CrudMode.disabled:
        raise NotFound()
    if meta.crud_mode == CrudMode.read_only and action != Action.read:
        raise Forbidden("This table is managed by a domain service and is read-only here.")


def _escape_like(value: str) -> str:
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _row_scope_clause(table: sa.Table, grant: Grant, principal: Principal) -> sa.ColumnElement[bool] | None:
    if grant.row_scope == RowScope.own:
        return table.c.created_by == principal.user_id
    return None


def _filter_clause(col: sa.ColumnElement[Any], op: str, value: Any) -> sa.ColumnElement[bool]:
    clause: Any
    match op:
        case "eq":
            clause = col == value
        case "ne":
            clause = sa.or_(col != value, col.is_(None))
        case "gt":
            clause = col > value
        case "gte":
            clause = col >= value
        case "lt":
            clause = col < value
        case "lte":
            clause = col <= value
        case "in":
            clause = col.in_(value)
        case "like":
            clause = col.ilike(f"%{_escape_like(value)}%", escape="\\")
        case "isnull":
            clause = col.is_(None) if value else col.is_not(None)
        case _:  # pragma: no cover - ops are allowlisted in query.py
            raise ValueError(op)
    return cast("sa.ColumnElement[bool]", clause)


def to_json_value(v: Any) -> Any:
    if isinstance(v, decimal.Decimal):
        return str(v)  # preserve exact precision; floats would lose it
    if isinstance(v, dt.datetime | dt.date):
        return v.isoformat()
    if isinstance(v, uuid.UUID):
        return str(v)
    return v


def serialize(row: Any, columns: list[str]) -> dict[str, Any]:
    m = row._mapping
    return {c: to_json_value(m[c]) for c in columns}


async def list_rows(
    session: AsyncSession, table: sa.Table, meta: TableMeta, principal: Principal, grant: Grant, q: ListQuery
) -> tuple[list[dict[str, Any]], bool]:
    readable = readable_fields(meta, grant)
    columns = q.fields or list(readable)
    stmt = sa.select(*[table.c[c] for c in columns])
    if (rs := _row_scope_clause(table, grant, principal)) is not None:
        stmt = stmt.where(rs)
    for f in q.filters:
        stmt = stmt.where(_filter_clause(table.c[f.field], f.op, f.value))
    if q.search:
        stmt = stmt.where(
            search_vector(table, meta.searchable).op("@@")(
                sa.func.plainto_tsquery(sa.literal_column("'simple'::regconfig"), q.search)
            )
        )
    order = [table.c[n].desc() if desc else table.c[n].asc() for n, desc in q.sort]
    order.append(table.c.id.asc())  # deterministic pagination
    if not q.sort:
        order.insert(0, table.c.created_at.desc())
    stmt = stmt.order_by(*order).limit(q.limit + 1).offset(q.offset)
    rows = (await session.execute(stmt)).all()
    has_more = len(rows) > q.limit
    return [serialize(r, columns) for r in rows[: q.limit]], has_more


async def get_row(
    session: AsyncSession,
    table: sa.Table,
    meta: TableMeta,
    principal: Principal,
    grant: Grant,
    record_id: uuid.UUID,
    fields: list[str] | None = None,
) -> dict[str, Any]:
    columns = fields or list(readable_fields(meta, grant))
    stmt = sa.select(*[table.c[c] for c in columns]).where(table.c.id == record_id)
    if (rs := _row_scope_clause(table, grant, principal)) is not None:
        stmt = stmt.where(rs)
    row = (await session.execute(stmt)).first()
    if row is None:
        raise NotFound()
    return serialize(row, columns)


async def insert_row(
    session: AsyncSession,
    table: sa.Table,
    meta: TableMeta,
    principal: Principal,
    read_grant: Grant | None,
    values: dict[str, Any],
) -> tuple[uuid.UUID, dict[str, Any]]:
    columns = list(readable_fields(meta, read_grant)) if read_grant else ["id"]
    stmt = (
        sa.insert(table)
        .values(
            **values,
            tenant_id=principal.tenant_id,
            created_by=principal.user_id,
            updated_by=principal.user_id,
        )
        .returning(*[table.c[c] for c in columns])
    )
    row = (await session.execute(stmt)).one()
    out = serialize(row, columns)
    return uuid.UUID(str(row._mapping["id"])), out


async def update_row(
    session: AsyncSession,
    table: sa.Table,
    meta: TableMeta,
    principal: Principal,
    grant: Grant,
    read_grant: Grant | None,
    record_id: uuid.UUID,
    values: dict[str, Any],
) -> dict[str, Any]:
    columns = list(readable_fields(meta, read_grant)) if read_grant else ["id"]
    stmt = (
        sa.update(table)
        .where(table.c.id == record_id)
        .values(**values, updated_at=sa.func.now(), updated_by=principal.user_id)
        .returning(*[table.c[c] for c in columns])
    )
    if (rs := _row_scope_clause(table, grant, principal)) is not None:
        stmt = stmt.where(rs)
    row = (await session.execute(stmt)).first()
    if row is None:
        raise NotFound()
    return serialize(row, columns)


async def delete_row(
    session: AsyncSession, table: sa.Table, principal: Principal, grant: Grant, record_id: uuid.UUID
) -> None:
    stmt = sa.delete(table).where(table.c.id == record_id).returning(table.c.id)
    if (rs := _row_scope_clause(table, grant, principal)) is not None:
        stmt = stmt.where(rs)
    if (await session.execute(stmt)).first() is None:
        raise NotFound()


def audit_values(meta: TableMeta, values: dict[str, Any]) -> dict[str, Any]:
    fmap = meta.field_map
    return {k: (REDACTED if fmap[k].is_sensitive else to_json_value(v)) for k, v in values.items()}
