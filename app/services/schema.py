"""Schema registry: cached metadata lookups and controlled DDL.

Metadata cache: ``meta:v1:<schema_version>:<table>`` (TTL ``meta_cache_ttl_s``, default 300 s).
Every DDL change bumps ``system_schema_state.version`` in the DDL transaction, so new keys are used
immediately and stale entries are simply never read again. The schema version itself is cached
for ``SCHEMA_VERSION_TTL_S`` with set-if-greater semantics. That TTL bounds staleness if publishing
the new version to Redis fails.
"""

from __future__ import annotations

import uuid
from collections import OrderedDict
from typing import Any

import sqlalchemy as sa
from sqlalchemy import select, text, update

from app.cache.redis import Cache, safe
from app.core.config import Settings
from app.core.errors import Conflict, ValidationFailed
from app.core.logging import get_logger
from app.db.session import Database
from app.models import AppField, AppTable, SchemaMigration, SchemaState
from app.services.audit import AuditContext, record

from app.dynamic.schema import (  # isort: skip
    FieldMeta,
    TableMeta,
    TableSpec,
    build_table,
    checksum,
    create_table_statements,
    physical_name,
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

    # -- DDL ------------------------------------------------------------------------------------

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
        statements = create_table_statements(meta, self._s.app_db_role)
        digest = checksum(statements)

        async with self._db.ddl_session() as s:
            await s.execute(text("SELECT set_config('app.is_platform', 'on', true)"))
            await s.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": _DDL_LOCK_KEY})
            if (await s.execute(select(AppTable.id).where(AppTable.name == spec.name))).first():
                raise Conflict("A table with this name already exists.")
            refs = {f.ref_table for f in spec.fields if f.ref_table}
            if refs:
                found = set((await s.execute(select(AppTable.name).where(AppTable.name.in_(refs)))).scalars())
                missing = sorted(refs - found)
                if missing:
                    raise ValidationFailed("Referenced tables do not exist.", details={"missing": missing})
            if dry_run:
                return {"dry_run": True, "statements": statements, "checksum": digest}

            for stmt in statements:
                # Statements are generated from validated metadata (see app.dynamic.schema).
                await s.execute(text(stmt))
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
                s.add(
                    AppField(
                        table_id=meta.id,
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
                )
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
                    operation="create_table",
                    table_name=spec.name,
                    checksum=digest,
                    statements=statements,
                    applied_by=admin_id,
                )
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

        await safe(
            self._cache.set_if_greater(self._cache.keys.schema_version(), version, SCHEMA_VERSION_TTL_S),
            op="schema_version.publish",
        )
        log.info("schema.table_created", table=spec.name, schema_version=version, checksum=digest)
        return {
            "dry_run": False,
            "statements": statements,
            "checksum": digest,
            "schema_version": version,
            "id": str(meta.id),
        }
