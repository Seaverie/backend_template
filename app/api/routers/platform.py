"""Super-admin endpoints: platform auth, tenant provisioning, and dynamic schema (DDL)."""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, ConfigDict, EmailStr, Field
from sqlalchemy import select, text

from app.api.deps import (
    client_ip,
    container,
    login_rate_limit,
    platform_admin,
    platform_audit_ctx,
    platform_claims,
)
from app.api.serialize import pick
from app.container import Container
from app.core.errors import NotFound, ValidationFailed
from app.core.logging import redact_value, request_id_var
from app.dynamic.schema import TablePatch, TableSpec
from app.models import AppTenant, DbDialect, DbSslMode, SystemDeadLetter, SystemTask, TenantStatus
from app.security.tokens import TokenClaims
from app.services.audit import record
from app.services.authz import PlatformPrincipal
from app.services.tenant_db import DbConfigIn

router = APIRouter(tags=["platform"])


class PlatformLoginIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: EmailStr
    password: str = Field(..., min_length=1, max_length=256)
    otp: str | None = Field(None, pattern=r"^[0-9]{6}$")


class MfaConfirmIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Field(..., pattern=r"^[0-9]{6}$")


class TenantCreateIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    slug: str = Field(..., pattern=r"^[a-z0-9][a-z0-9-]{1,62}$")
    name: str = Field(..., min_length=1, max_length=200)
    owner_email: EmailStr
    owner_password: str = Field(..., min_length=12, max_length=256)
    rate_limit_per_min: int | None = Field(None, ge=1)


class TenantPatchIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: TenantStatus | None = None
    rate_limit_per_min: int | None = Field(None, ge=1)


def _tenant_out(t: AppTenant) -> dict[str, Any]:
    return {
        "id": str(t.id),
        "slug": t.slug,
        "name": t.name,
        "status": t.status.value,
        "isolation_mode": t.isolation_mode.value,
        "rate_limit_per_min": t.rate_limit_per_min,
    }


@router.post("/platform/auth/login")
async def platform_login(
    body: PlatformLoginIn, request: Request, c: Container = Depends(container)
) -> dict[str, Any]:
    await login_rate_limit(request, f"platform:{body.email}", c)
    pair = await c.auth.platform_login(
        email=str(body.email),
        password=body.password,
        otp=body.otp,
        ip=client_ip(request),
        request_id=request_id_var.get(),
    )
    return {"access_token": pair.access_token, "token_type": pair.token_type, "expires_in": pair.expires_in}


@router.post("/platform/auth/logout", status_code=204)
async def platform_logout(
    claims: TokenClaims = Depends(platform_claims), c: Container = Depends(container)
) -> None:
    await c.auth.platform_logout(claims)


@router.post("/platform/auth/mfa/setup")
async def mfa_setup(
    claims: TokenClaims = Depends(platform_claims), c: Container = Depends(container)
) -> dict[str, str]:
    # The secret is returned once so it can be added to an authenticator app; it is stored encrypted.
    return await c.auth.mfa_setup(claims)


@router.post("/platform/auth/mfa/confirm")
async def mfa_confirm(
    body: MfaConfirmIn,
    request: Request,
    claims: TokenClaims = Depends(platform_claims),
    c: Container = Depends(container),
) -> dict[str, Any]:
    pair = await c.auth.mfa_confirm(claims, body.code, ip=client_ip(request), request_id=request_id_var.get())
    return {"access_token": pair.access_token, "token_type": pair.token_type, "expires_in": pair.expires_in}


@router.post("/platform/tenants", status_code=201)
async def create_tenant(
    body: TenantCreateIn,
    request: Request,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    try:
        tenant, owner = await c.tenants.provision(
            admin_id=admin.admin_id,
            slug=body.slug,
            name=body.name,
            owner_email=str(body.owner_email),
            owner_password=body.owner_password,
            rate_limit_per_min=body.rate_limit_per_min,
            ctx=platform_audit_ctx(request, admin),
        )
    except ValueError as e:
        raise ValidationFailed(str(e)) from e
    return {"tenant": _tenant_out(tenant), "owner_user_id": str(owner.id)}


@router.get("/platform/tenants")
async def list_tenants(
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0, le=100_000),
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    async with c.db.platform_session(admin.admin_id) as s:
        rows = (
            await s.execute(select(AppTenant).order_by(AppTenant.created_at).limit(limit).offset(offset))
        ).scalars()
        return {"items": [_tenant_out(t) for t in rows]}


@router.patch("/platform/tenants/{tenant_id}")
async def patch_tenant(
    tenant_id: uuid.UUID,
    body: TenantPatchIn,
    request: Request,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    try:
        t = await c.tenants.update(
            admin_id=admin.admin_id,
            tenant_id=tenant_id,
            status=body.status,
            rate_limit_per_min=body.rate_limit_per_min,
            ctx=platform_audit_ctx(request, admin),
        )
    except ValueError as e:
        raise ValidationFailed(str(e)) from e
    if t is None:
        raise NotFound()
    return _tenant_out(t)


@router.post("/system/tables", status_code=201)
async def create_table(
    spec: TableSpec,
    request: Request,
    dry_run: bool = Query(False),
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return await c.schema.create_table(
        spec, admin_id=admin.admin_id, dry_run=dry_run, ctx=platform_audit_ctx(request, admin)
    )


@router.get("/system/tables")
async def list_tables(
    _: PlatformPrincipal = Depends(platform_admin), c: Container = Depends(container)
) -> dict[str, Any]:
    tables = await c.schema.list_tables()
    metas = {m.name: m for m in await c.schema.all_tables()}
    return {
        "items": [
            {
                "id": str(t.id),
                "name": t.name,
                "crud_mode": t.crud_mode.value,
                "version": t.version,
                "fields": metas[t.name].to_json()["fields"] if t.name in metas else [],
            }
            for t in tables
        ]
    }


@router.patch("/system/tables/{table_id}")
async def patch_table(
    table_id: uuid.UUID,
    patch: TablePatch,
    request: Request,
    dry_run: bool = Query(False),
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return await c.schema.patch_table(
        table_id, patch, admin_id=admin.admin_id, dry_run=dry_run, ctx=platform_audit_ctx(request, admin)
    )


@router.delete("/system/tables/{table_id}")
async def delete_table(
    table_id: uuid.UUID,
    request: Request,
    confirm: str = Query(..., max_length=63),
    dry_run: bool = Query(False),
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return await c.schema.delete_table(
        table_id,
        confirm=confirm,
        admin_id=admin.admin_id,
        dry_run=dry_run,
        ctx=platform_audit_ctx(request, admin),
    )


@router.post("/system/snapshot/export")
async def export_snapshot(
    _: PlatformPrincipal = Depends(platform_admin), c: Container = Depends(container)
) -> dict[str, Any]:
    return await c.schema.export_snapshot()


class SnapshotImportIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    snapshot: dict[str, Any]
    dry_run: bool = True


@router.post("/system/snapshot/import")
async def import_snapshot(
    body: SnapshotImportIn,
    request: Request,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return await c.schema.import_snapshot(
        body.snapshot, admin_id=admin.admin_id, dry_run=body.dry_run, ctx=platform_audit_ctx(request, admin)
    )


class TenantDbIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    dialect: DbDialect
    host: str = Field(..., min_length=1, max_length=255, pattern=r"^[A-Za-z0-9.\-:]+$")
    port: int = Field(..., ge=1, le=65535)
    database: str = Field(..., pattern=r"^[A-Za-z0-9_\-.]{1,128}$")
    username: str = Field(..., pattern=r"^[A-Za-z0-9_\-.@]{1,128}$")
    password: str = Field(..., min_length=1, max_length=512)
    ssl_mode: DbSslMode = DbSslMode.verify_full
    ssl_root_cert: str | None = Field(None, max_length=20_000)
    pool_size: int = Field(3, ge=1, le=20)


def _db_out(row: Any) -> dict[str, Any]:
    # Never includes the (encrypted) password.
    return pick(
        row,
        "tenant_id",
        "dialect",
        "host",
        "port",
        "database",
        "username",
        "ssl_mode",
        "status",
        "secret_version",
        "pool_size",
        "synced_schema_version",
        "last_verified_at",
    )


@router.put("/platform/tenants/{tenant_id}/database")
async def configure_tenant_db(
    tenant_id: uuid.UUID,
    body: TenantDbIn,
    request: Request,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    """Create or update (including credential rotation) a tenant's dedicated database."""
    cfg = DbConfigIn(**body.model_dump())
    return _db_out(
        await c.tenant_db.configure(admin.admin_id, tenant_id, cfg, platform_audit_ctx(request, admin))
    )


@router.get("/platform/tenants/{tenant_id}/database")
async def get_tenant_db(
    tenant_id: uuid.UUID,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return _db_out(await c.tenant_db.get(admin.admin_id, tenant_id))


@router.post("/platform/tenants/{tenant_id}/database/sync")
async def sync_tenant_db(
    tenant_id: uuid.UUID,
    request: Request,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return await c.tenant_db.sync(admin.admin_id, tenant_id, platform_audit_ctx(request, admin))


@router.post("/platform/tenants/{tenant_id}/database/disable", status_code=204)
async def disable_tenant_db(
    tenant_id: uuid.UUID,
    request: Request,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> None:
    await c.tenant_db.disable(admin.admin_id, tenant_id, platform_audit_ctx(request, admin))


# -- offboarding, background tasks, dead letters, diagnostics -------------------------------------------


class OffboardIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    confirm_slug: str = Field(..., max_length=63)
    drop_dedicated_data: bool = False


def _task_out(t: Any) -> dict[str, Any]:
    return pick(
        t,
        "id",
        "kind",
        "status",
        "input",
        "progress",
        "result",
        "error",
        "attempts",
        "created_at",
        "started_at",
        "finished_at",
    )


@router.post("/platform/tenants/{tenant_id}/offboard", status_code=202)
async def offboard_tenant(
    tenant_id: uuid.UUID,
    body: OffboardIn,
    request: Request,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    """Irreversibly erase a tenant (after archiving its audit trail). Runs as a background task."""
    task = await c.offboarding.request(
        admin.admin_id,
        tenant_id,
        confirm_slug=body.confirm_slug,
        drop_dedicated_data=body.drop_dedicated_data,
        ctx=platform_audit_ctx(request, admin),
    )
    from app.workers.tasks import enqueue

    await asyncio.to_thread(enqueue, "offboard", None, task.id)
    return _task_out(task)


@router.get("/platform/tasks/{task_id}")
async def platform_task(
    task_id: uuid.UUID, admin: PlatformPrincipal = Depends(platform_admin), c: Container = Depends(container)
) -> dict[str, Any]:
    async with c.db.platform_session(admin.admin_id) as s:
        task = (await s.execute(select(SystemTask).where(SystemTask.id == task_id))).scalar_one_or_none()
    if task is None:
        raise NotFound()
    return _task_out(task)


@router.get("/platform/dead-letters")
async def dead_letters(
    limit: int = Query(100, ge=1, le=500),
    include_requeued: bool = False,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    stmt = select(SystemDeadLetter).order_by(SystemDeadLetter.created_at.desc()).limit(limit)
    if not include_requeued:
        stmt = stmt.where(SystemDeadLetter.requeued_at.is_(None))
    async with c.db.platform_session(admin.admin_id) as s:
        rows = (await s.execute(stmt)).scalars().all()
    return {
        "items": [
            pick(
                d,
                "id",
                "tenant_id",
                "source",
                "reference_id",
                "task_name",
                "error",
                "created_at",
                "requeued_at",
            )
            for d in rows
        ]
    }


@router.post("/platform/dead-letters/{dead_letter_id}/requeue", status_code=202)
async def requeue(
    dead_letter_id: uuid.UUID,
    request: Request,
    admin: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    from app.workers import jobs
    from app.workers.tasks import enqueue

    dl = await jobs.requeue_dead_letter(c, dead_letter_id)
    if dl.source == "task":
        async with c.db.platform_session(admin.admin_id) as s:
            task = (
                await s.execute(select(SystemTask).where(SystemTask.id == uuid.UUID(dl.reference_id)))
            ).scalar_one_or_none()
        if task is not None:
            await asyncio.to_thread(enqueue, task.kind, task.tenant_id, task.id)
    async with c.db.platform_session(admin.admin_id) as s:
        await record(
            s,
            platform_audit_ctx(request, admin),
            tenant_id=None,
            action="dead_letter.requeue",
            resource="dead_letters",
            resource_id=str(dead_letter_id),
        )
        await s.commit()
    return {"requeued": True}


@router.get("/platform/diagnostics/slow-queries")
async def slow_queries(
    limit: int = Query(20, ge=1, le=100),
    _: PlatformPrincipal = Depends(platform_admin),
    c: Container = Depends(container),
) -> dict[str, Any]:
    """Query-plan diagnostics from PostgreSQL statistics, never from live data.

    * ``pg_stat_statements``: normalised statements (constants replaced by ``$n``) ranked by mean time.
      Needs the extension, plus ``pg_read_all_stats`` for the runtime role to see other roles' queries.
    * ``pg_stat_user_tables``: tables where sequential scans dominate, which usually means a missing
      index.
    """
    out: dict[str, Any] = {"pg_stat_statements": None}
    async with c.db.anonymous_session() as s:
        has_ext = (
            await s.execute(text("SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'"))
        ).first()
        if has_ext:
            rows = (
                await s.execute(
                    text(
                        "SELECT query, calls, mean_exec_time, total_exec_time, rows FROM pg_stat_statements "
                        "WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) "
                        "ORDER BY mean_exec_time DESC LIMIT :n"
                    ),
                    {"n": limit},
                )
            ).all()
            out["pg_stat_statements"] = [
                {
                    "query": redact_value(r.query[:2000]),
                    "calls": r.calls,
                    "mean_ms": round(r.mean_exec_time, 2),
                    "total_ms": round(r.total_exec_time, 2),
                    "rows": r.rows,
                }
                for r in rows
            ]
        tables = (
            await s.execute(
                text(
                    "SELECT schemaname, relname, seq_scan, seq_tup_read, idx_scan, n_live_tup"
                    " FROM pg_stat_user_tables "
                    "WHERE seq_scan > coalesce(idx_scan, 0) AND n_live_tup > 1000 "
                    "ORDER BY seq_tup_read DESC LIMIT :n"
                ),
                {"n": limit},
            )
        ).all()
    out["seq_scan_heavy_tables"] = [dict(r._mapping) for r in tables]
    out["slow_query_log_threshold_ms"] = c.settings.slow_query_ms
    return out
