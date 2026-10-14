"""Schema registry: cached metadata lookups and controlled DDL.

Metadata cache: ``meta:v1:<schema_version>:<table>`` (TTL ``meta_cache_ttl_s``, default 300 s).
Every DDL change bumps ``system_schema_state.version`` in the DDL transaction, so new keys are used
immediately and stale entries are simply never read again. The schema version itself is cached
for ``SCHEMA_VERSION_TTL_S`` with set-if-greater semantics. That TTL bounds staleness if publishing
the new version to Redis fails.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from collections import OrderedDict
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import Any

import sqlalchemy as sa
from pydantic import ValidationError as PydanticValidationError
from sqlalchemy import delete, select, text, update
from sqlalchemy.exc import DBAPIError
from sqlalchemy.ext.asyncio import AsyncSession

from app.cache.redis import Cache, safe
from app.core.config import Settings
from app.core.errors import Conflict, NotFound, ValidationFailed
from app.core.logging import get_logger
from app.db.session import Database
from app.models import DYNAMIC_SCHEMA, AppField, AppTable, FieldType, SchemaMigration, SchemaState
from app.security.identifiers import IDENTIFIER_RE
from app.services.audit import AuditContext, record

from app.dynamic.schema import (  # isort: skip
    FieldMeta,
    FieldSpec,
    TableMeta,
    TablePatch,
    TableSpec,
    add_field_statements,
    build_table,
    checksum,
    create_field_index_concurrently,
    create_search_index_concurrently,
    create_table_statements,
    drop_field_statement,
    drop_index_concurrently,
    drop_table_statement,
    field_index_name,
    grant_statement,
    physical_name,
    search_index_name,
)

log = get_logger("schema")
SCHEMA_VERSION_TTL_S = 30
_DDL_LOCK_KEY = 0x7C0DE  # advisory lock serialising all dynamic DDL


class SchemaService:
    def __init__(self, settings: Settings, db: Database, cache: Cache) -> None:
        self._s = settings
        self._db = db
        self._cache = cache
        self._tables: OrderedDict[tuple[int, str], sa.Table] = OrderedDict()
        # Called with the new schema version after every committed change (dedicated-DB fan-out).
        self.on_change: list[Callable[[int], Awaitable[None]]] = []

    # -- reads ----------------------------------------------------------------------------------

    async def version(self) -> int:
        key = self._cache.keys.schema_version()
        ok, v = await safe(self._cache.get_int(key), op="schema_version.get")
        if ok and v is not None:
            return int(v)
        async with self._db.anonymous_session() as s:
            v = int((await s.execute(select(SchemaState.version).where(SchemaState.id == 1))).scalar_one())
        if ok:
            ok2, cur = await safe(
                self._cache.set_if_greater(key, v, SCHEMA_VERSION_TTL_S), op="schema_version.set"
            )
            if ok2 and cur is not None:
                v = int(cur)
        return v

    async def get_table(self, name: str) -> TableMeta | None:
        version = await self.version()
        key = self._cache.keys.table_meta(version, name)
        ok, cached = await safe(self._cache.get_json(key), op="meta.get")
        if ok and cached is not None:
            return None if cached.get("absent") else TableMeta.from_json(cached)
        meta = await self._load(name)
        if ok:
            payload: dict[str, Any] = meta.to_json() if meta else {"absent": True}
            await safe(self._cache.set_json(key, payload, self._s.meta_cache_ttl_s), op="meta.set")
        return meta

    async def _load(self, name: str) -> TableMeta | None:
        async with self._db.anonymous_session() as s:
            t = (await s.execute(select(AppTable).where(AppTable.name == name))).scalar_one_or_none()
            if t is None:
                return None
            fields = (
                (
                    await s.execute(
                        select(AppField).where(AppField.table_id == t.id).order_by(AppField.position)
                    )
                )
                .scalars()
                .all()
            )
        return TableMeta(
            t.id,
            t.name,
            t.physical_name,
            t.crud_mode,
            t.require_idempotency,
            tuple(
                FieldMeta(
                    f.name,
                    f.type,
                    f.nullable,
                    f.is_unique,
                    f.is_indexed,
                    f.is_searchable,
                    f.is_sensitive,
                    f.max_length,
                    f.precision,
                    f.scale,
                    tuple(f.enum_values) if f.enum_values else None,
                    f.ref_table,
                )
                for f in fields
            ),
        )

    async def sa_table(self, meta: TableMeta) -> sa.Table:
        """In-process cache of SQLAlchemy Table objects keyed by (schema version, name)."""
        version = await self.version()
        key = (version, meta.name)
        t = self._tables.get(key)
        if t is None:
            t = build_table(meta)
            self._tables[key] = t
            while len(self._tables) > 512:
                self._tables.popitem(last=False)
        return t

    async def list_tables(self) -> list[AppTable]:
        async with self._db.anonymous_session() as s:
            return list((await s.execute(select(AppTable).order_by(AppTable.name))).scalars())

    async def get_table_by_id(self, table_id: uuid.UUID) -> TableMeta | None:
        async with self._db.anonymous_session() as s:
            name = (
                await s.execute(select(AppTable.name).where(AppTable.id == table_id))
            ).scalar_one_or_none()
        return None if name is None else await self._load(name)

    async def all_tables(self) -> list[TableMeta]:
        """Every registered table, ordered so referenced tables come before tables that reference them."""
        async with self._db.anonymous_session() as s:
            names = list((await s.execute(select(AppTable.name).order_by(AppTable.name))).scalars())
        metas = [m for m in [await self._load(n) for n in names] if m is not None]
        return _topological(metas)

    # -- DDL plumbing -----------------------------------------------------------------------------

    @asynccontextmanager
    async def _ddl_tx(self) -> AsyncIterator[AsyncSession]:
        async with self._db.ddl_session() as s:
            await s.execute(text("SELECT set_config('app.is_platform', 'on', true)"))
            await s.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": _DDL_LOCK_KEY})
            yield s

    @staticmethod
    async def _ledger(
        s: AsyncSession, *, operation: str, table_name: str, statements: list[str], admin_id: uuid.UUID
    ) -> int:
        version = int(
            (
                await s.execute(
                    update(SchemaState)
                    .where(SchemaState.id == 1)
                    .values(version=SchemaState.version + 1)
                    .returning(SchemaState.version)
                )
            ).scalar_one()
        )
        s.add(
            SchemaMigration(
                schema_version=version,
                operation=operation,
                table_name=table_name,
                checksum=checksum(statements),
                statements=statements,
                applied_by=admin_id,
            )
        )
        return version

    async def _published(self, version: int) -> None:
        await safe(
            self._cache.set_if_greater(self._cache.keys.schema_version(), version, SCHEMA_VERSION_TTL_S),
            op="schema_version.publish",
        )
        for hook in self.on_change:
            try:
                await hook(version)
            except Exception as e:  # fan-out is best effort; dedicated DBs record their sync state
                log.warning("schema.change_hook_failed", error_type=type(e).__name__)

    async def _run_concurrently(
        self,
        *,
        operation: str,
        meta: TableMeta,
        statement: str,
        admin_id: uuid.UUID,
        ctx: AuditContext,
        after: Any = None,
    ) -> int:
        """Run CREATE/DROP INDEX CONCURRENTLY outside any transaction block.

        Serialised with transactional DDL via the same advisory lock (session-level here). If a
        concurrent build fails, PostgreSQL leaves an INVALID index behind; it is dropped before the
        error propagates so the operation can simply be retried (recovery procedure).
        """
        engine = self._db.ddl_engine
        if engine is None:
            raise RuntimeError("DDL database is not configured")
        async with engine.connect() as raw:
            conn = await raw.execution_options(isolation_level="AUTOCOMMIT")
            await conn.execute(text("SELECT pg_advisory_lock(:k)"), {"k": _DDL_LOCK_KEY})
            try:
                await conn.execute(text("SET statement_timeout = '30min'"))
                await conn.execute(text("SET lock_timeout = '10s'"))
                try:
                    await conn.execute(text(statement))
                except DBAPIError:
                    await _drop_invalid_indexes(conn, meta.physical_name)
                    raise
            finally:
                await conn.execute(text("RESET ALL"))
                await conn.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": _DDL_LOCK_KEY})
        async with self._ddl_tx() as s:
            version = await self._ledger(
                s, operation=operation, table_name=meta.name, statements=[statement], admin_id=admin_id
            )
            if after is not None:
                await after(s)
            await record(
                s,
                ctx,
                tenant_id=None,
                action=f"schema.{operation}",
                resource="app_tables",
                resource_id=str(meta.id),
                detail={"statement_checksum": checksum([statement])},
            )
            await s.commit()
        await self._published(version)
        return version

    # -- create ---------------------------------------------------------------------------------

    async def create_table(
        self, spec: TableSpec, *, admin_id: uuid.UUID, dry_run: bool, ctx: AuditContext
    ) -> dict[str, Any]:
        meta = TableMeta(
            uuid.uuid4(),
            spec.name,
            physical_name(spec.name),
            spec.crud_mode,
            spec.require_idempotency,
            tuple(FieldMeta.from_spec(f) for f in spec.fields),
        )
        statements = create_table_statements(meta)
        digest = checksum(statements)
        async with self._ddl_tx() as s:
            if (await s.execute(select(AppTable.id).where(AppTable.name == spec.name))).first():
                raise Conflict("A table with this name already exists.")
            await _require_refs(s, {f.ref_table for f in spec.fields if f.ref_table})
            if dry_run:
                return {"dry_run": True, "statements": statements, "checksum": digest}
            for stmt in [*statements, grant_statement(meta.physical_name, self._s.app_db_role)]:
                await s.execute(text(stmt))  # generated from validated metadata (app.dynamic.schema)
            s.add(
                AppTable(
                    id=meta.id,
                    name=meta.name,
                    physical_name=meta.physical_name,
                    label=spec.label,
                    crud_mode=spec.crud_mode,
                    require_idempotency=spec.require_idempotency,
                )
            )
            await s.flush()
            for pos, f in enumerate(spec.fields):
                s.add(_field_row(meta.id, f, pos))
            version = await self._ledger(
                s, operation="create_table", table_name=spec.name, statements=statements, admin_id=admin_id
            )
            await record(
                s,
                ctx,
                tenant_id=None,
                action="schema.create_table",
                resource="app_tables",
                resource_id=str(meta.id),
                detail={"name": spec.name, "checksum": digest, "schema_version": version},
            )
            await s.commit()
        await self._published(version)
        log.info("schema.table_created", table=spec.name, schema_version=version, checksum=digest)
        return {
            "dry_run": False,
            "statements": statements,
            "checksum": digest,
            "schema_version": version,
            "id": str(meta.id),
        }

    # -- patch ----------------------------------------------------------------------------------

    async def patch_table(
        self, table_id: uuid.UUID, patch: TablePatch, *, admin_id: uuid.UUID, dry_run: bool, ctx: AuditContext
    ) -> dict[str, Any]:
        meta = await self.get_table_by_id(table_id)
        if meta is None:
            raise NotFound()
        fmap = meta.field_map
        if unknown := [f for f in patch.drop_fields if f not in fmap]:
            raise ValidationFailed("Unknown fields to drop.", details={"fields": unknown})
        if dupes := [f.name for f in patch.add_fields if f.name in fmap]:
            raise ValidationFailed("Fields already exist.", details={"fields": dupes})
        if len(meta.fields) - len(patch.drop_fields) + len(patch.add_fields) < 1:
            raise ValidationFailed("A table must keep at least one field.")
        remaining = {
            **{k: v for k, v in fmap.items() if k not in patch.drop_fields},
            **{f.name: FieldMeta.from_spec(f) for f in patch.add_fields},
        }
        for name in patch.add_indexes:
            f = remaining.get(name)
            if (
                f is None
                or f.type == FieldType.json
                or f.is_unique
                or f.is_indexed
                or f.type == FieldType.reference
            ):
                raise ValidationFailed(
                    "Index requires an existing, unindexed, non-json field.", details={"field": name}
                )
        for name in patch.drop_indexes:
            f = remaining.get(name)
            if f is None or not f.is_indexed:
                raise ValidationFailed("Field has no secondary index to drop.", details={"field": name})

        added = [FieldMeta.from_spec(f) for f in patch.add_fields]
        after = TableMeta(
            meta.id,
            meta.name,
            meta.physical_name,
            patch.crud_mode or meta.crud_mode,
            meta.require_idempotency if patch.require_idempotency is None else patch.require_idempotency,
            tuple(f for f in meta.fields if f.name not in patch.drop_fields) + tuple(added),
        )
        tx_statements = [drop_field_statement(meta, f) for f in patch.drop_fields]
        for f in added:
            tx_statements += add_field_statements(after, f)
        concurrent: list[
            tuple[str, str, str | None, bool | None]
        ] = []  # (operation, statement, field, indexed)
        for name in patch.add_indexes:
            concurrent.append(
                ("create_index_concurrently", create_field_index_concurrently(after, name), name, True)
            )
        for f in added:
            if f.type == FieldType.reference or f.is_indexed:
                concurrent.append(
                    ("create_index_concurrently", create_field_index_concurrently(after, f.name), None, None)
                )
        for name in patch.drop_indexes:
            concurrent.append(
                (
                    "drop_index_concurrently",
                    drop_index_concurrently(meta.physical_name, field_index_name(meta.physical_name, name)),
                    name,
                    False,
                )
            )
        if set(meta.searchable) != set(after.searchable):
            concurrent.append(
                (
                    "drop_index_concurrently",
                    drop_index_concurrently(meta.physical_name, search_index_name(meta.physical_name)),
                    None,
                    None,
                )
            )
            if after.searchable:
                concurrent.append(
                    ("create_index_concurrently", create_search_index_concurrently(after), None, None)
                )
        if dry_run:
            return {"dry_run": True, "statements": tx_statements, "concurrent": [c[1] for c in concurrent]}

        async with self._ddl_tx() as s:
            await _require_refs(s, {f.ref_table for f in patch.add_fields if f.ref_table})
            for stmt in tx_statements:
                await s.execute(text(stmt))
            if patch.drop_fields:
                await s.execute(
                    delete(AppField).where(
                        AppField.table_id == table_id, AppField.name.in_(patch.drop_fields)
                    )
                )
            start = len(meta.fields)
            for pos, spec_f in enumerate(patch.add_fields, start=start):
                s.add(_field_row(table_id, spec_f, pos))
            row = (
                await s.execute(select(AppTable).where(AppTable.id == table_id).with_for_update())
            ).scalar_one()
            if patch.label is not None:
                row.label = patch.label
            if patch.crud_mode is not None:
                row.crud_mode = patch.crud_mode
            if patch.require_idempotency is not None:
                row.require_idempotency = patch.require_idempotency
            row.version += 1
            version = await self._ledger(
                s, operation="alter_table", table_name=meta.name, statements=tx_statements, admin_id=admin_id
            )
            await record(
                s,
                ctx,
                tenant_id=None,
                action="schema.alter_table",
                resource="app_tables",
                resource_id=str(table_id),
                detail=patch.model_dump(mode="json"),
            )
            await s.commit()
        await self._published(version)

        for op, stmt, field, indexed in concurrent:

            async def _flag(
                s: AsyncSession, field: str | None = field, indexed: bool | None = indexed
            ) -> None:
                if field is not None and indexed is not None:
                    await s.execute(
                        update(AppField)
                        .where(AppField.table_id == table_id, AppField.name == field)
                        .values(is_indexed=indexed)
                    )

            version = await self._run_concurrently(
                operation=op, meta=after, statement=stmt, admin_id=admin_id, ctx=ctx, after=_flag
            )
        return {
            "dry_run": False,
            "statements": tx_statements,
            "concurrent": [c[1] for c in concurrent],
            "schema_version": version,
        }

    # -- delete ---------------------------------------------------------------------------------

    async def delete_table(
        self, table_id: uuid.UUID, *, confirm: str, admin_id: uuid.UUID, dry_run: bool, ctx: AuditContext
    ) -> dict[str, Any]:
        meta = await self.get_table_by_id(table_id)
        if meta is None:
            raise NotFound()
        if confirm != meta.name:
            raise ValidationFailed("Pass ?confirm=<table name> to delete a table and all of its data.")
        statement = drop_table_statement(meta)
        async with self._ddl_tx() as s:
            referencing = list(
                (
                    await s.execute(
                        select(AppTable.name)
                        .join(AppField, AppField.table_id == AppTable.id)
                        .where(AppField.ref_table == meta.name, AppTable.id != table_id)
                    )
                ).scalars()
            )
            if referencing:
                raise Conflict(
                    "Other tables reference this table.", details={"referenced_by": sorted(set(referencing))}
                )
            if dry_run:
                return {"dry_run": True, "statements": [statement]}
            await s.execute(text(statement))
            await s.execute(delete(AppTable).where(AppTable.id == table_id))
            version = await self._ledger(
                s, operation="drop_table", table_name=meta.name, statements=[statement], admin_id=admin_id
            )
            await record(
                s,
                ctx,
                tenant_id=None,
                action="schema.drop_table",
                resource="app_tables",
                resource_id=str(table_id),
                detail={"name": meta.name},
            )
            await s.commit()
        await self._published(version)
        return {"dry_run": False, "statements": [statement], "schema_version": version}

    # -- snapshots (GitOps) -----------------------------------------------------------------------

    async def export_snapshot(self) -> dict[str, Any]:
        tables = [_spec_dict(m, await self._label(m.id)) for m in await self.all_tables()]
        return {
            "format": SNAPSHOT_FORMAT,
            "schema_version": await self.version(),
            "exported_at": datetime.now(UTC).isoformat(),
            "tables": tables,
            "checksum": snapshot_checksum(tables),
        }

    async def _label(self, table_id: uuid.UUID) -> str | None:
        async with self._db.anonymous_session() as s:
            return (
                await s.execute(select(AppTable.label).where(AppTable.id == table_id))
            ).scalar_one_or_none()

    async def import_snapshot(
        self, snapshot: dict[str, Any], *, admin_id: uuid.UUID, dry_run: bool, ctx: AuditContext
    ) -> dict[str, Any]:
        """Apply a snapshot additively: create missing tables, add missing fields, sync table flags.
        Never drops tables or fields. Incompatible differences are reported as conflicts and block
        the import (nothing is applied)."""
        if snapshot.get("format") != SNAPSHOT_FORMAT or not isinstance(snapshot.get("tables"), list):
            raise ValidationFailed("Unsupported snapshot format.")
        if snapshot.get("checksum") != snapshot_checksum(snapshot["tables"]):
            raise ValidationFailed("Snapshot checksum mismatch; the file was modified or truncated.")
        try:
            specs = [TableSpec.model_validate(t) for t in snapshot["tables"]]
        except PydanticValidationError as e:
            raise ValidationFailed(
                "Snapshot contains an invalid table definition.", details=str(e)[:500]
            ) from e

        plan: list[dict[str, Any]] = []
        conflicts: list[dict[str, Any]] = []
        for spec in specs:
            current = await self.get_table(spec.name)
            if current is None:
                plan.append({"table": spec.name, "action": "create"})
                continue
            cur = current.field_map
            add = [f for f in spec.fields if f.name not in cur]
            for f in spec.fields:
                if f.name in cur and _field_signature(FieldMeta.from_spec(f)) != _field_signature(
                    cur[f.name]
                ):
                    conflicts.append({"table": spec.name, "field": f.name, "reason": "definition differs"})
            for f in add:
                if not f.nullable:
                    conflicts.append(
                        {"table": spec.name, "field": f.name, "reason": "cannot add a non-nullable field"}
                    )
            flags: dict[str, Any] = {}
            if spec.crud_mode != current.crud_mode:
                flags["crud_mode"] = spec.crud_mode.value
            if spec.require_idempotency != current.require_idempotency:
                flags["require_idempotency"] = spec.require_idempotency
            extra = sorted(set(cur) - {f.name for f in spec.fields})
            action = "patch" if add or flags else "none"
            plan.append(
                {
                    "table": spec.name,
                    "action": action,
                    "add_fields": [f.name for f in add],
                    "flags": flags,
                    "extra_fields_kept": extra,
                }
            )
        result: dict[str, Any] = {"dry_run": dry_run, "plan": plan, "conflicts": conflicts}
        if dry_run or conflicts:
            if conflicts and not dry_run:
                raise Conflict(
                    "Snapshot conflicts with the current schema; nothing was applied.", details=conflicts
                )
            return result
        by_name = {s.name: s for s in specs}
        for step in plan:
            spec = by_name[step["table"]]
            if step["action"] == "create":
                await self.create_table(spec, admin_id=admin_id, dry_run=False, ctx=ctx)
            elif step["action"] == "patch":
                current = await self.get_table(spec.name)
                if current is None:  # dropped concurrently by another admin
                    raise Conflict("Table disappeared during import.", details={"table": spec.name})
                patch = TablePatch(
                    crud_mode=spec.crud_mode if "crud_mode" in step["flags"] else None,
                    require_idempotency=spec.require_idempotency
                    if "require_idempotency" in step["flags"]
                    else None,
                    add_fields=[f for f in spec.fields if f.name in step["add_fields"]],
                )
                await self.patch_table(current.id, patch, admin_id=admin_id, dry_run=False, ctx=ctx)
        result["schema_version"] = await self.version()
        return result

    async def ledger_since(self, version: int) -> list[SchemaMigration]:
        async with self._db.anonymous_session() as s:
            return list(
                (
                    await s.execute(
                        select(SchemaMigration)
                        .where(SchemaMigration.schema_version > version)
                        .order_by(SchemaMigration.schema_version)
                    )
                ).scalars()
            )


SNAPSHOT_FORMAT = "tenantcore.schema/v1"


def snapshot_checksum(tables: list[Any]) -> str:
    return hashlib.sha256(json.dumps(tables, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _field_signature(f: FieldMeta) -> tuple[Any, ...]:
    return (f.type, f.nullable, f.is_unique, f.max_length, f.precision, f.scale, f.enum_values, f.ref_table)


def _spec_dict(m: TableMeta, label: str | None) -> dict[str, Any]:
    return {
        "name": m.name,
        "label": label,
        "crud_mode": m.crud_mode.value,
        "require_idempotency": m.require_idempotency,
        "fields": [
            {
                "name": f.name,
                "type": f.type.value,
                "nullable": f.nullable,
                "unique": f.is_unique,
                "indexed": f.is_indexed,
                "searchable": f.is_searchable,
                "sensitive": f.is_sensitive,
                "max_length": f.max_length,
                "precision": f.precision,
                "scale": f.scale,
                "enum_values": list(f.enum_values) if f.enum_values else None,
                "ref_table": f.ref_table,
            }
            for f in m.fields
        ],
    }


def _topological(metas: list[TableMeta]) -> list[TableMeta]:
    by_name = {m.name: m for m in metas}
    out: list[TableMeta] = []
    seen: set[str] = set()

    def visit(m: TableMeta, stack: frozenset[str]) -> None:
        if m.name in seen:
            return
        for f in m.fields:
            if f.ref_table and f.ref_table in by_name and f.ref_table not in stack:
                visit(by_name[f.ref_table], stack | {m.name})
        seen.add(m.name)
        out.append(m)

    for m in metas:
        visit(m, frozenset())
    return out


def _field_row(table_id: uuid.UUID, f: FieldSpec, pos: int) -> AppField:
    return AppField(
        table_id=table_id,
        name=f.name,
        type=f.type,
        nullable=f.nullable,
        is_unique=f.unique,
        is_indexed=f.indexed,
        is_searchable=f.searchable,
        is_sensitive=f.sensitive,
        max_length=f.max_length,
        precision=f.precision,
        scale=f.scale,
        enum_values=f.enum_values,
        ref_table=f.ref_table,
        position=pos,
    )


async def _require_refs(s: AsyncSession, refs: set[str]) -> None:
    if not refs:
        return
    found = set((await s.execute(select(AppTable.name).where(AppTable.name.in_(refs)))).scalars())
    if missing := sorted(refs - found):
        raise ValidationFailed("Referenced tables do not exist.", details={"missing": missing})


async def _drop_invalid_indexes(conn: Any, physical: str) -> None:
    rows = (
        await conn.execute(
            text(
                "SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                "JOIN pg_class t ON t.oid = i.indrelid JOIN pg_namespace n ON n.oid = t.relnamespace "
                "WHERE n.nspname = :schema AND t.relname = :table AND NOT i.indisvalid"
            ),
            {"schema": DYNAMIC_SCHEMA, "table": physical},
        )
    ).scalars()
    for name in rows:
        if IDENTIFIER_RE.fullmatch(name):
            await conn.execute(text(drop_index_concurrently(physical, name)))
            log.warning("schema.invalid_index_dropped", index=name)
