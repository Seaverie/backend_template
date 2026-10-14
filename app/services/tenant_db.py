"""Dedicated (enterprise) tenant databases: configuration, schema sync, and runtime routing.

Routing
-------
For a tenant whose ``isolation_mode`` is ``dedicated``, dynamic-table traffic (``/data/*``) is routed to
its own database. Engines live in a bounded LRU registry (``tenant_db_max_engines``), each with a
small bounded pool. An engine is keyed by ``secret_version``: when credentials rotate, the next request
on every node builds a new engine and disposes the old one. The in-process config cache is 30 s, which
is the cross-node rotation window. Idle engines are disposed after ``tenant_db_idle_dispose_s``.

Isolation inside a dedicated PostgreSQL database
-------------------------------------------------
The same RLS policies are applied there and the session sets the same transaction-scoped tenant
context, so a misrouted request still cannot see rows (defense in depth). The configured role must not
be a superuser and must not have BYPASSRLS; this is verified on configuration. For MySQL and Oracle
there is no RLS (see :mod:`app.db.adapters`). Data routing for those dialects returns 501 in Phase 2.

Schema sync
-----------
A dedicated database replays the platform's DDL ledger (``system_schema_migrations``), in order.
Each entry's checksum is verified before execution, and progress is recorded per tenant in
``synced_schema_version``. New schema changes fan out automatically after commit. A tenant whose
database is behind gets 503 on data endpoints until it catches up; nothing is served from an
out-of-date schema.
"""

from __future__ import annotations

import asyncio
import re
import time
import uuid
from collections import OrderedDict
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import sqlalchemy as sa
from sqlalchemy import select, text, update
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.core.config import Settings
from app.core.errors import AppError, Conflict, NotFound, ServiceUnavailable, ValidationFailed
from app.core.logging import get_logger
from app.db.adapters import ADAPTERS, ConnSpec, TargetNotAllowed, validate_target
from app.db.session import Database
from app.dynamic.schema import checksum, physical_name
from app.models import (
    AppTenant,
    AppTenantDatabase,
    DbDialect,
    DbSslMode,
    IsolationMode,
    TenantDbStatus,
)
from app.security.crypto import CryptoError, Envelope
from app.services.audit import AuditContext, record
from app.services.authz import Principal
from app.services.schema import SchemaService
from app.services.tenants import TenantService

log = get_logger("tenant_db")
CONFIG_TTL_S = 30
SYSTEM_ADMIN = uuid.UUID(int=0)
_GRANT_RE = re.compile(r"^GRANT\s", re.IGNORECASE)

_BOOTSTRAP = [
    "CREATE TABLE IF NOT EXISTS public.app_tenants (id uuid PRIMARY KEY)",
    # Which ledger entries this database has applied (source of truth for replay, independent of the
    # control plane's bookkeeping).
    """CREATE TABLE IF NOT EXISTS public.tenantcore_schema_sync (
         schema_version bigint PRIMARY KEY, checksum varchar(64) NOT NULL,
         applied_at timestamptz NOT NULL DEFAULT now())""",
    "INSERT INTO public.app_tenants (id) VALUES (CAST(:tenant AS uuid)) ON CONFLICT DO NOTHING",
    """CREATE OR REPLACE FUNCTION public.app_current_tenant_id() RETURNS uuid
       LANGUAGE sql STABLE PARALLEL SAFE
       AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$""",
    """CREATE OR REPLACE FUNCTION public.app_is_platform() RETURNS boolean
       LANGUAGE sql STABLE PARALLEL SAFE
       AS $$ SELECT coalesce(current_setting('app.is_platform', true), '') = 'on' $$""",
    "CREATE SCHEMA IF NOT EXISTS tenant_data",
    """CREATE TABLE IF NOT EXISTS public.system_audit_logs (
         id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
         tenant_id uuid REFERENCES public.app_tenants (id),
         actor_type varchar(16) NOT NULL, actor_id uuid, action varchar(64) NOT NULL,
         resource varchar(100) NOT NULL, resource_id varchar(100), request_id varchar(64), ip inet,
         detail jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now())""",
    "ALTER TABLE public.system_audit_logs ENABLE ROW LEVEL SECURITY",
    "ALTER TABLE public.system_audit_logs FORCE ROW LEVEL SECURITY",
    """DO $$ BEGIN
         IF NOT EXISTS (SELECT 1 FROM pg_policy WHERE polname = 'tenant_isolation'
                        AND polrelid = 'public.system_audit_logs'::regclass) THEN
           CREATE POLICY tenant_isolation ON public.system_audit_logs FOR ALL TO PUBLIC
             USING (tenant_id = public.app_current_tenant_id())
             WITH CHECK (tenant_id = public.app_current_tenant_id());
         END IF;
       END $$""",
]

_SET_CONTEXT = text(
    "SELECT set_config('app.tenant_id', :t, true), set_config('app.user_id', :u, true),"
    " set_config('statement_timeout', :st, true), set_config('lock_timeout', :lt, true)"
)


class DialectNotSupported(AppError):
    status_code = 501
    code = "not_implemented"
    message = "This operation is not yet supported for the tenant's database dialect."


@dataclass(frozen=True, slots=True)
class DbConfigIn:
    dialect: DbDialect
    host: str
    port: int
    database: str
    username: str
    password: str
    ssl_mode: DbSslMode
    ssl_root_cert: str | None
    pool_size: int


@dataclass
class _Engine:
    version: int
    engine: AsyncEngine
    last_used: float


def _ctx(tenant_id: uuid.UUID, row_id: uuid.UUID) -> dict[str, str]:
    return {"tenant": str(tenant_id), "purpose": "tenant_db", "id": str(row_id)}


class TenantDbService:
    def __init__(
        self,
        settings: Settings,
        db: Database,
        envelope: Envelope,
        schema: SchemaService,
        tenants: TenantService,
    ) -> None:
        self._s = settings
        self._db = db
        self._env = envelope
        self._schema = schema
        self._tenants = tenants
        self._cfg: dict[uuid.UUID, tuple[float, AppTenantDatabase | None]] = {}
        self._engines: OrderedDict[uuid.UUID, _Engine] = OrderedDict()
        self._locks: dict[uuid.UUID, asyncio.Lock] = {}
        schema.on_change.append(self._fan_out)

    # -- engine construction ----------------------------------------------------------------------

    async def _spec(self, row: AppTenantDatabase) -> ConnSpec:
        try:
            password = await self._env.decrypt_str(row.password_enc, _ctx(row.tenant_id, row.id))
        except CryptoError as e:
            raise ServiceUnavailable("Tenant database credentials are unavailable.") from e
        return ConnSpec(
            row.dialect,
            row.host,
            row.port,
            row.database,
            row.username,
            password,
            row.ssl_mode,
            row.ssl_root_cert,
        )

    def _build(self, spec: ConnSpec, pool_size: int | None) -> AsyncEngine:
        adapter = ADAPTERS[spec.dialect]
        kw: dict[str, Any] = {
            "connect_args": adapter.connect_args(spec, self._s.db_connect_timeout_s),
            "hide_parameters": True,
        }
        if pool_size is None:
            kw["poolclass"] = NullPool
        else:
            kw.update(
                pool_size=min(pool_size, self._s.tenant_db_pool_size),
                max_overflow=0,
                pool_timeout=self._s.db_pool_timeout_s,
                pool_pre_ping=True,
                pool_recycle=self._s.db_pool_recycle_s,
            )
        return create_async_engine(adapter.url(spec), **kw)

    async def _probe(self, spec: ConnSpec) -> None:
        """Connectivity check; for PostgreSQL also refuse roles that would bypass RLS."""
        engine = self._build(spec, None)
        try:
            async with asyncio.timeout(self._s.db_connect_timeout_s + 5), engine.connect() as conn:
                if spec.dialect == DbDialect.postgresql:
                    row = (
                        await conn.execute(
                            text("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")
                        )
                    ).one()
                    if row.rolsuper or row.rolbypassrls:
                        raise ValidationFailed("The database role must not be a superuser or have BYPASSRLS.")
                else:
                    await conn.execute(
                        text("SELECT 1 FROM DUAL" if spec.dialect == DbDialect.oracle else "SELECT 1")
                    )
        except ValidationFailed:
            raise
        except Exception as e:
            log.warning("tenant_db.probe_failed", error_type=type(e).__name__)
            raise ValidationFailed("Could not connect to the tenant database with the given settings.") from e
        finally:
            await engine.dispose()

    # -- platform operations --------------------------------------------------------------------

    async def configure(
        self, admin_id: uuid.UUID, tenant_id: uuid.UUID, cfg: DbConfigIn, ctx: AuditContext
    ) -> AppTenantDatabase:
        try:
            await validate_target(cfg.host, cfg.port, self._s.tenant_db_allowed_networks)
        except TargetNotAllowed as e:
            raise ValidationFailed(str(e)) from e
        spec = ConnSpec(
            cfg.dialect,
            cfg.host,
            cfg.port,
            cfg.database,
            cfg.username,
            cfg.password,
            cfg.ssl_mode,
            cfg.ssl_root_cert,
        )
        await self._probe(spec)
        async with self._db.platform_session(admin_id, tenant_id=tenant_id) as s:
            if (await s.execute(select(AppTenant.id).where(AppTenant.id == tenant_id))).first() is None:
                raise NotFound()
            row = (
                await s.execute(
                    select(AppTenantDatabase)
                    .where(AppTenantDatabase.tenant_id == tenant_id)
                    .with_for_update()
                )
            ).scalar_one_or_none()
            if row is None:
                row = AppTenantDatabase(id=uuid.uuid4(), tenant_id=tenant_id, secret_version=1)
                s.add(row)
                target_changed = True
            else:
                target_changed = (row.dialect, row.host, row.port, row.database) != (
                    cfg.dialect,
                    cfg.host,
                    cfg.port,
                    cfg.database,
                )
                row.secret_version += 1
            row.dialect, row.host, row.port, row.database = cfg.dialect, cfg.host, cfg.port, cfg.database
            row.username, row.ssl_mode, row.ssl_root_cert, row.pool_size = (
                cfg.username,
                cfg.ssl_mode,
                cfg.ssl_root_cert,
                cfg.pool_size,
            )
            row.password_enc = await self._env.encrypt_str(cfg.password, _ctx(tenant_id, row.id))
            row.last_verified_at = datetime.now(UTC)
            if target_changed:
                # A different database must be (re)synced before it serves traffic.
                row.status, row.synced_schema_version = TenantDbStatus.pending, 0
            await record(
                s,
                ctx,
                tenant_id=None,
                action="tenant_db.configure",
                resource="tenant_databases",
                resource_id=str(tenant_id),
                detail={"dialect": cfg.dialect.value, "host": cfg.host, "secret_version": row.secret_version},
            )
            await s.commit()
        await self._forget(tenant_id)
        return row

    async def get(self, admin_id: uuid.UUID, tenant_id: uuid.UUID) -> AppTenantDatabase:
        async with self._db.platform_session(admin_id) as s:
            row = (
                await s.execute(select(AppTenantDatabase).where(AppTenantDatabase.tenant_id == tenant_id))
            ).scalar_one_or_none()
        if row is None:
            raise NotFound()
        return row

    async def disable(self, admin_id: uuid.UUID, tenant_id: uuid.UUID, ctx: AuditContext) -> None:
        async with self._db.platform_session(admin_id) as s:
            res = await s.execute(
                update(AppTenantDatabase)
                .where(AppTenantDatabase.tenant_id == tenant_id)
                .values(status=TenantDbStatus.disabled)
            )
            if not res.rowcount:  # type: ignore[attr-defined]
                raise NotFound()
            await record(
                s,
                ctx,
                tenant_id=None,
                action="tenant_db.disable",
                resource="tenant_databases",
                resource_id=str(tenant_id),
            )
            await s.commit()
        await self._forget(tenant_id)

    async def sync(
        self, admin_id: uuid.UUID, tenant_id: uuid.UUID, ctx: AuditContext | None
    ) -> dict[str, Any]:
        """Bootstrap the dedicated database and replay the DDL ledger. On first success the tenant is
        switched to dedicated isolation (refused if it still has rows in the shared tables)."""
        row = await self.get(admin_id, tenant_id)
        if row.dialect != DbDialect.postgresql:
            raise DialectNotSupported()
        if row.status == TenantDbStatus.disabled:
            raise Conflict("The tenant database is disabled.")
        lock = self._locks.setdefault(tenant_id, asyncio.Lock())
        async with lock:
            spec = await self._spec(row)
            engine = self._build(spec, None)
            applied = 0
            try:
                async with engine.begin() as conn:
                    # A dedicated database belongs to exactly one tenant.
                    if (
                        await conn.execute(text("SELECT to_regclass('public.app_tenants')"))
                    ).scalar_one() is not None:
                        other = (
                            await conn.execute(
                                text("SELECT 1 FROM public.app_tenants WHERE id <> CAST(:t AS uuid) LIMIT 1"),
                                {"t": str(tenant_id)},
                            )
                        ).first()
                        if other:
                            raise Conflict("This database already belongs to another tenant.")
                    for stmt in _BOOTSTRAP:
                        await conn.execute(
                            text(stmt), {"tenant": str(tenant_id)} if ":tenant" in stmt else {}
                        )
                    done = set(
                        (
                            await conn.execute(
                                text("SELECT schema_version FROM public.tenantcore_schema_sync")
                            )
                        ).scalars()
                    )
                synced = row.synced_schema_version
                for m in await self._schema.ledger_since(0):
                    if checksum(list(m.statements)) != m.checksum:
                        raise Conflict(
                            "DDL ledger checksum mismatch; refusing to replay.",
                            details={"schema_version": m.schema_version},
                        )
                    if m.schema_version not in done:
                        stmts = [x for x in m.statements if not _GRANT_RE.match(x)]
                        marker = text(
                            "INSERT INTO public.tenantcore_schema_sync (schema_version, checksum)"
                            " VALUES (:v, :c)"
                        )
                        if m.operation.endswith("_concurrently"):
                            async with engine.connect() as raw:
                                conn = await raw.execution_options(isolation_level="AUTOCOMMIT")
                                for stmt in stmts:
                                    await conn.execute(text(stmt))
                                await conn.execute(marker, {"v": m.schema_version, "c": m.checksum})
                        else:
                            async with engine.begin() as conn:
                                for stmt in stmts:
                                    await conn.execute(text(stmt))
                                await conn.execute(marker, {"v": m.schema_version, "c": m.checksum})
                        applied += 1
                    synced = m.schema_version
                await self._set_synced(admin_id, tenant_id, synced)
            finally:
                await engine.dispose()
            await self._activate(admin_id, tenant_id, ctx)
        await self._forget(tenant_id)
        return {"applied": applied, "synced_schema_version": synced}

    async def _set_synced(self, admin_id: uuid.UUID, tenant_id: uuid.UUID, version: int) -> None:
        async with self._db.platform_session(admin_id) as s:
            await s.execute(
                update(AppTenantDatabase)
                .where(AppTenantDatabase.tenant_id == tenant_id)
                .values(synced_schema_version=version)
            )
            await s.commit()

    async def _activate(self, admin_id: uuid.UUID, tenant_id: uuid.UUID, ctx: AuditContext | None) -> None:
        tenant = await self._tenants.get(tenant_id)
        if tenant is not None and tenant.isolation_mode != IsolationMode.dedicated:
            # Refuse to switch while shared tables still hold this tenant's rows (they would vanish).
            async with self._db.tenant_session(tenant_id) as s:
                for meta in await self._schema.all_tables():
                    t = sa.table(physical_name(meta.name), sa.column("tenant_id"), schema="tenant_data")
                    if (await s.execute(sa.select(sa.literal(1)).select_from(t).limit(1))).first():
                        raise Conflict(
                            "The tenant still has data in shared tables; migrate it before switching."
                        )
        async with self._db.platform_session(admin_id, tenant_id=tenant_id) as s:
            await s.execute(
                update(AppTenantDatabase)
                .where(AppTenantDatabase.tenant_id == tenant_id)
                .values(status=TenantDbStatus.active)
            )
            await s.execute(
                update(AppTenant)
                .where(AppTenant.id == tenant_id)
                .values(isolation_mode=IsolationMode.dedicated)
            )
            if ctx is not None:
                await record(
                    s,
                    ctx,
                    tenant_id=None,
                    action="tenant_db.activate",
                    resource="tenant_databases",
                    resource_id=str(tenant_id),
                )
            await s.commit()
        await self._tenants.invalidate(tenant_id)

    async def _fan_out(self, version: int) -> None:
        async with self._db.platform_session(SYSTEM_ADMIN) as s:
            rows = (
                (
                    await s.execute(
                        select(AppTenantDatabase.tenant_id).where(
                            AppTenantDatabase.status == TenantDbStatus.active,
                            AppTenantDatabase.dialect == DbDialect.postgresql,
                            AppTenantDatabase.synced_schema_version < version,
                        )
                    )
                )
                .scalars()
                .all()
            )
        for tenant_id in rows:
            try:
                await self.sync(SYSTEM_ADMIN, tenant_id, None)
            except Exception as e:  # recorded as "behind"; data endpoints return 503 until a manual sync
                log.error("tenant_db.fan_out_failed", tenant=str(tenant_id), error_type=type(e).__name__)

    # -- routing --------------------------------------------------------------------------------

    async def _config(self, tenant_id: uuid.UUID) -> AppTenantDatabase | None:
        hit = self._cfg.get(tenant_id)
        if hit and hit[0] > time.monotonic():
            return hit[1]
        async with self._db.tenant_session(tenant_id) as s:
            row = (
                await s.execute(select(AppTenantDatabase).where(AppTenantDatabase.tenant_id == tenant_id))
            ).scalar_one_or_none()
        self._cfg[tenant_id] = (time.monotonic() + CONFIG_TTL_S, row)
        return row

    async def _engine(self, row: AppTenantDatabase) -> AsyncEngine:
        entry = self._engines.get(row.tenant_id)
        if entry and entry.version == row.secret_version:
            entry.last_used = time.monotonic()
            self._engines.move_to_end(row.tenant_id)
            return entry.engine
        lock = self._locks.setdefault(row.tenant_id, asyncio.Lock())
        async with lock:
            entry = self._engines.get(row.tenant_id)
            if entry and entry.version == row.secret_version:
                return entry.engine
            try:
                await validate_target(
                    row.host, row.port, self._s.tenant_db_allowed_networks
                )  # DNS may have changed
            except TargetNotAllowed as e:
                raise ServiceUnavailable("Tenant database target is not allowed.") from e
            engine = self._build(await self._spec(row), row.pool_size)
            if entry is not None:  # credential rotation: retire the old pool
                await entry.engine.dispose()
            self._engines[row.tenant_id] = _Engine(row.secret_version, engine, time.monotonic())
            self._engines.move_to_end(row.tenant_id)
            await self._evict()
            return engine

    async def _evict(self) -> None:
        now = time.monotonic()
        stale = [t for t, e in self._engines.items() if now - e.last_used > self._s.tenant_db_idle_dispose_s]
        while len(self._engines) - len(stale) > self._s.tenant_db_max_engines:
            stale.append(next(t for t in self._engines if t not in stale))
        for t in stale:
            await self._engines.pop(t).engine.dispose()

    async def _forget(self, tenant_id: uuid.UUID) -> None:
        self._cfg.pop(tenant_id, None)
        entry = self._engines.pop(tenant_id, None)
        if entry is not None:
            await entry.engine.dispose()

    @asynccontextmanager
    async def session(self, p: Principal, *, dedicated: bool) -> AsyncIterator[AsyncSession]:
        """Session for dynamic-table data: the control-plane DB for shared tenants, the tenant's own
        database for dedicated tenants. Tenant context is transaction-scoped in both cases."""
        if not dedicated:
            async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
                yield s
            return
        row = await self._config(p.tenant_id)
        if row is None or row.status != TenantDbStatus.active:
            raise ServiceUnavailable("The tenant's dedicated database is not available.")
        if not ADAPTERS[row.dialect].capabilities.supports_dynamic_crud:
            raise DialectNotSupported()
        current = await self._schema.version()
        if row.synced_schema_version < current:
            # Another node may have just synced: re-read once before refusing.
            self._cfg.pop(p.tenant_id, None)
            row = await self._config(p.tenant_id)
        if row is None or row.status != TenantDbStatus.active or row.synced_schema_version < current:
            raise ServiceUnavailable("The tenant's database schema is being synchronised; retry shortly.")
        engine = await self._engine(row)
        async with async_sessionmaker(engine, expire_on_commit=False, autoflush=False)() as s:
            await s.execute(
                _SET_CONTEXT,
                {
                    "t": str(p.tenant_id),
                    "u": str(p.user_id),
                    "st": str(self._s.db_statement_timeout_ms),
                    "lt": str(self._s.db_lock_timeout_ms),
                },
            )
            yield s

    def engine_count(self) -> int:
        return len(self._engines)

    async def close(self) -> None:
        for entry in self._engines.values():
            await entry.engine.dispose()
        self._engines.clear()
