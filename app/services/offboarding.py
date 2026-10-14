"""Tenant offboarding and erasure (GDPR Art. 17 / CCPA deletion).

Steps, each safe to re-run after a crash (the job is retried until it completes):

1. **Freeze.** The tenant's status becomes ``offboarding``: logins, tokens and API keys stop working
   at once (cache invalidated), and all sessions are revoked.
2. **Archive.** The tenant's audit log is exported as gzip JSONL to cold storage. Locally it is
   envelope-encrypted; in S3 it uses SSE-KMS. The location and SHA-256 go into ``tenant_archives``,
   which survives the tenant. Audit logs in a dedicated database are archived the same way.
3. **Dedicated database.** Pools are disposed. Optionally the tenant's TenantCore objects are dropped
   from it (``tenant_data`` schema, audit table, bootstrap objects); the database itself belongs to
   the customer and is never dropped.
4. **Purge.** Within one owner-role transaction, every row of the tenant is deleted from every
   shared table, children before parents (dynamic tables first), then the tenant row itself. RLS
   still applies: the transaction is scoped to that tenant, and the append-only triggers allow
   deletion only for ``app.purge_tenant = <this tenant>`` inside a platform transaction.
5. **Scavenge Redis.** Every key that embeds the tenant id, plus cached API-key metadata, is deleted.
6. **Record.** A platform audit entry (no tenant id) records the per-table row counts and the
   archive checksums, as the erasure record.
"""

from __future__ import annotations

import asyncio
import gzip
import hashlib
import io
import json
import os
import tempfile
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Protocol

import sqlalchemy as sa
from sqlalchemy import select, text, update

from app.cache.redis import Cache, safe
from app.core.config import Settings
from app.core.errors import NotFound, ValidationFailed
from app.core.logging import get_logger
from app.db.session import Database
from app.dynamic.crud import to_json_value
from app.dynamic.schema import physical_name
from app.models import (
    DYNAMIC_SCHEMA,
    AppTenant,
    AppTenantDatabase,
    AuthSession,
    Base,
    SystemAuditLog,
    SystemTask,
    TaskStatus,
    TenantApiKey,
    TenantArchive,
    TenantStatus,
)
from app.security.crypto import Envelope
from app.services.audit import AuditContext, record
from app.services.schema import SchemaService
from app.services.tenant_db import SYSTEM_ADMIN, TenantDbService
from app.services.tenants import TenantService

log = get_logger("offboarding")
_PRESERVED = {"app_tenants", "tenant_archives"}


class ArchiveStore(Protocol):
    async def put(self, key: str, data: bytes) -> str: ...


class LocalArchiveStore:
    """Development/single-node store. Files are envelope-encrypted and written atomically with 0600."""

    def __init__(self, root: str, envelope: Envelope) -> None:
        self._root = Path(root)
        self._env = envelope

    async def put(self, key: str, data: bytes) -> str:
        blob = (await self._env.encrypt(data, {"purpose": "tenant_archive", "id": key})).encode()
        return await asyncio.to_thread(self._write, key, blob)

    def _write(self, key: str, blob: bytes) -> str:
        target = (self._root / key).resolve()
        if self._root.resolve() not in target.parents:
            raise ValueError("archive key escapes the archive directory")
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        fd, tmp = tempfile.mkstemp(dir=target.parent)
        try:
            with os.fdopen(fd, "wb") as fh:
                fh.write(blob)
            os.chmod(tmp, 0o600)
            os.replace(tmp, target)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise
        return f"file://{target}"


class S3ArchiveStore:
    def __init__(self, bucket: str, prefix: str, kms_key_id: str | None, client: Any = None) -> None:
        if client is None:
            import boto3

            client = boto3.client("s3")
        self._s3, self._bucket, self._prefix, self._kms = client, bucket, prefix, kms_key_id

    async def put(self, key: str, data: bytes) -> str:
        extra: dict[str, Any] = {"ServerSideEncryption": "aws:kms"}
        if self._kms:
            extra["SSEKMSKeyId"] = self._kms
        await asyncio.to_thread(
            self._s3.put_object,
            Bucket=self._bucket,
            Key=self._prefix + key,
            Body=data,
            ChecksumAlgorithm="SHA256",
            **extra,
        )
        return f"s3://{self._bucket}/{self._prefix}{key}"


def tenant_scoped_tables() -> list[sa.Table]:
    """Shared tables holding tenant rows, children first (safe deletion order)."""
    return [
        t
        for t in reversed(Base.metadata.sorted_tables)
        if "tenant_id" in t.c and t.name not in _PRESERVED and t.schema is None
    ]


class OffboardingService:
    def __init__(
        self,
        settings: Settings,
        db: Database,
        cache: Cache,
        envelope: Envelope,
        schema: SchemaService,
        tenants: TenantService,
        tenant_db: TenantDbService,
        store: ArchiveStore | None = None,
    ) -> None:
        self._s = settings
        self._db = db
        self._cache = cache
        self._env = envelope
        self._schema = schema
        self._tenants = tenants
        self._tenant_db = tenant_db
        self.store: ArchiveStore = store or (
            S3ArchiveStore(
                settings.archive_s3_bucket, settings.archive_s3_prefix, settings.archive_s3_kms_key_id
            )
            if settings.archive_store == "s3" and settings.archive_s3_bucket
            else LocalArchiveStore(settings.archive_dir, envelope)
        )

    async def request(
        self,
        admin_id: uuid.UUID,
        tenant_id: uuid.UUID,
        *,
        confirm_slug: str,
        drop_dedicated_data: bool,
        ctx: AuditContext,
    ) -> SystemTask:
        async with self._db.platform_session(admin_id) as s:
            tenant = (
                await s.execute(select(AppTenant).where(AppTenant.id == tenant_id))
            ).scalar_one_or_none()
            if tenant is None:
                raise NotFound()
            if confirm_slug != tenant.slug:
                raise ValidationFailed("confirm_slug must equal the tenant's slug.")
            task = SystemTask(
                tenant_id=None,
                kind="offboard",
                idempotency_key=f"offboard:{tenant_id}",
                created_by=admin_id,
                input={
                    "tenant_id": str(tenant_id),
                    "slug": tenant.slug,
                    "drop_dedicated_data": drop_dedicated_data,
                },
            )
            existing = (
                await s.execute(
                    select(SystemTask).where(
                        SystemTask.kind == "offboard", SystemTask.idempotency_key == task.idempotency_key
                    )
                )
            ).scalar_one_or_none()
            if existing is not None:
                return existing
            s.add(task)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=None,
                action="tenant.offboard_requested",
                resource="tenants",
                resource_id=str(tenant_id),
                detail={"slug": tenant.slug, "drop_dedicated_data": drop_dedicated_data},
            )
            await s.commit()
        return task

    async def _progress(self, task_id: uuid.UUID, step: str, **extra: Any) -> None:
        async with self._db.platform_session(SYSTEM_ADMIN) as s:
            task = (
                await s.execute(select(SystemTask).where(SystemTask.id == task_id).with_for_update())
            ).scalar_one()
            task.progress = {**task.progress, "step": step, **{k: to_json_value(v) for k, v in extra.items()}}
            if task.status == TaskStatus.queued:
                task.status, task.started_at = TaskStatus.running, datetime.now(UTC)
            await s.commit()

    async def run(self, task_id: uuid.UUID) -> TaskStatus:
        async with self._db.platform_session(SYSTEM_ADMIN) as s:
            task = (
                await s.execute(select(SystemTask).where(SystemTask.id == task_id).with_for_update())
            ).scalar_one_or_none()
            if task is None or task.status == TaskStatus.succeeded:
                return task.status if task else TaskStatus.failed
            task.attempts += 1
            inp = dict(task.input)
            previously_purged: dict[str, int] = dict(task.progress.get("purged") or {})
            await s.commit()
        tenant_id, slug = uuid.UUID(inp["tenant_id"]), str(inp["slug"])

        await self._progress(task_id, "freeze")
        await self._freeze(tenant_id)
        await self._progress(task_id, "archive")
        archives = await self._archive(tenant_id, slug)
        await self._progress(task_id, "dedicated_db", archives=archives)
        await self._dedicated(tenant_id, slug, bool(inp.get("drop_dedicated_data")))
        key_ids = await self._api_key_ids(tenant_id)
        await self._progress(task_id, "purge")
        # A re-run after a crash finds nothing left to delete; keep the counts from the run that did.
        counts = previously_purged
        for table, n in (await self._purge(tenant_id)).items():
            counts[table] = counts.get(table, 0) + n
        await self._progress(task_id, "scavenge_cache", purged=counts)
        removed = await self._scavenge(tenant_id, key_ids)

        async with self._db.platform_session(SYSTEM_ADMIN) as s:
            await record(
                s,
                AuditContext("system", None, None, None),
                tenant_id=None,
                action="tenant.erased",
                resource="tenants",
                resource_id=str(tenant_id),
                detail={
                    "slug": slug,
                    "rows_deleted": counts,
                    "archives": archives,
                    "redis_keys_deleted": removed,
                },
            )
            task = (
                await s.execute(select(SystemTask).where(SystemTask.id == task_id).with_for_update())
            ).scalar_one()
            task.status, task.finished_at = TaskStatus.succeeded, datetime.now(UTC)
            task.result = {"rows_deleted": counts, "archives": archives, "redis_keys_deleted": removed}
            await s.commit()
        log.info("tenant.erased", tenant=str(tenant_id), rows=sum(counts.values()))
        return TaskStatus.succeeded

    async def _freeze(self, tenant_id: uuid.UUID) -> None:
        async with self._db.platform_session(SYSTEM_ADMIN, tenant_id=tenant_id) as s:
            await s.execute(
                update(AppTenant).where(AppTenant.id == tenant_id).values(status=TenantStatus.offboarding)
            )
            await s.execute(
                update(AuthSession)
                .where(AuthSession.revoked_at.is_(None))
                .values(revoked_at=datetime.now(UTC), revoked_reason="tenant_offboarding")
            )
            await s.commit()
        await self._tenants.invalidate(tenant_id)

    async def _archive_rows(
        self, rows: list[dict[str, Any]], tenant_id: uuid.UUID, slug: str, kind: str
    ) -> dict[str, Any]:
        async with self._db.platform_session(SYSTEM_ADMIN) as s:
            done = (
                await s.execute(
                    select(TenantArchive).where(
                        TenantArchive.tenant_id == tenant_id, TenantArchive.kind == kind
                    )
                )
            ).scalar_one_or_none()
            if done is not None:
                return {
                    "kind": kind,
                    "location": done.location,
                    "sha256": done.sha256,
                    "records": done.records,
                }
        buf = io.BytesIO()
        with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as gz:
            for r in rows:
                gz.write(json.dumps(r, separators=(",", ":"), sort_keys=True, default=str).encode() + b"\n")
        data = buf.getvalue()
        key = f"{tenant_id}/{kind}-{datetime.now(UTC):%Y%m%dT%H%M%SZ}.jsonl.gz"
        location = await self.store.put(key, data)
        digest = hashlib.sha256(data).hexdigest()
        async with self._db.platform_session(SYSTEM_ADMIN) as s:
            s.add(
                TenantArchive(
                    tenant_id=tenant_id,
                    tenant_slug=slug,
                    kind=kind,
                    location=location,
                    sha256=digest,
                    size_bytes=len(data),
                    records=len(rows),
                )
            )
            await s.commit()
        return {"kind": kind, "location": location, "sha256": digest, "records": len(rows)}

    async def _archive(self, tenant_id: uuid.UUID, slug: str) -> list[dict[str, Any]]:
        cols = [c.name for c in SystemAuditLog.__table__.c]
        rows: list[dict[str, Any]] = []
        async with self._db.tenant_session(tenant_id, statement_timeout_ms=600_000) as s:
            result = await s.stream(
                select(SystemAuditLog.__table__)
                .order_by(SystemAuditLog.created_at, SystemAuditLog.id)
                .execution_options(yield_per=5000)
            )
            async for r in result:
                rows.append({c: to_json_value(r._mapping[c]) for c in cols})
        return [await self._archive_rows(rows, tenant_id, slug, "audit_logs")]

    async def _dedicated(self, tenant_id: uuid.UUID, slug: str, drop: bool) -> None:
        async with self._db.platform_session(SYSTEM_ADMIN) as s:
            row = (
                await s.execute(select(AppTenantDatabase).where(AppTenantDatabase.tenant_id == tenant_id))
            ).scalar_one_or_none()
        if row is None:
            return
        await self._tenant_db._forget(tenant_id)
        if row.dialect.value != "postgresql":
            return
        engine = self._tenant_db._build(await self._tenant_db._spec(row), None)
        try:
            async with engine.connect() as conn:
                await conn.execute(
                    text("SELECT set_config('app.tenant_id', :t, false)"), {"t": str(tenant_id)}
                )
                exists = (
                    await conn.execute(text("SELECT to_regclass('public.system_audit_logs')"))
                ).scalar_one()
                rows = []
                if exists is not None:
                    rows = [
                        dict(r._mapping)
                        for r in (
                            await conn.execute(
                                text("SELECT * FROM public.system_audit_logs ORDER BY created_at")
                            )
                        ).all()
                    ]
                await conn.execute(text("RESET ALL"))
            await self._archive_rows(
                [{k: to_json_value(v) for k, v in r.items()} for r in rows],
                tenant_id,
                slug,
                "dedicated_audit_logs",
            )
            if drop:
                async with engine.begin() as conn:
                    for stmt in (
                        "DROP SCHEMA IF EXISTS tenant_data CASCADE",
                        "DROP TABLE IF EXISTS public.system_audit_logs",
                        "DROP TABLE IF EXISTS public.tenantcore_schema_sync",
                        "DROP TABLE IF EXISTS public.app_tenants",
                        "DROP FUNCTION IF EXISTS public.app_current_tenant_id()",
                        "DROP FUNCTION IF EXISTS public.app_is_platform()",
                    ):
                        await conn.execute(text(stmt))
        finally:
            await engine.dispose()

    async def _api_key_ids(self, tenant_id: uuid.UUID) -> list[str]:
        async with self._db.tenant_session(tenant_id) as s:
            return list((await s.execute(select(TenantApiKey.key_id))).scalars())

    async def _purge(self, tenant_id: uuid.UUID) -> dict[str, int]:
        counts: dict[str, int] = {}
        tables = [m for m in reversed(await self._schema.all_tables())]
        async with self._db.ddl_session() as s:
            await s.execute(
                text(
                    "SELECT set_config('app.tenant_id', :t, true), set_config('app.is_platform', 'on', true),"
                    " set_config('app.purge_tenant', :t, true),"
                    " set_config('statement_timeout', '600000', true)"
                ),
                {"t": str(tenant_id)},
            )
            for meta in tables:
                t = sa.table(physical_name(meta.name), sa.column("tenant_id"), schema=DYNAMIC_SCHEMA)
                res = await s.execute(sa.delete(t).where(t.c.tenant_id == tenant_id))
                counts[f"{DYNAMIC_SCHEMA}.{meta.physical_name}"] = res.rowcount or 0  # type: ignore[attr-defined]
            for table in tenant_scoped_tables():
                res = await s.execute(table.delete().where(table.c.tenant_id == tenant_id))
                if res.rowcount:  # type: ignore[attr-defined]
                    counts[table.name] = res.rowcount  # type: ignore[attr-defined]
            res = await s.execute(sa.delete(AppTenant).where(AppTenant.id == tenant_id))
            counts["app_tenants"] = res.rowcount or 0  # type: ignore[attr-defined]
            await s.commit()
        await self._tenants.invalidate(tenant_id)
        return counts

    async def _scavenge(self, tenant_id: uuid.UUID, key_ids: list[str]) -> int:
        removed = 0
        pattern = f"{self._cache.keys.p}:*{tenant_id}*"
        ok, _ = await safe(self._cache.ping(), op="scavenge.ping")
        if not ok:
            return -1  # recorded; caches expire on their own TTLs
        cursor = 0
        while True:
            cursor, keys = await self._cache.client.scan(cursor=cursor, match=pattern, count=1000)
            if keys:
                removed += int(await self._cache.client.delete(*keys))
            if cursor == 0:
                break
        api_keys = [self._cache.keys.api_key(k) for k in key_ids]
        if api_keys:
            removed += int(await self._cache.client.delete(*api_keys))
        return removed
