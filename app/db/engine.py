"""Async engines and slow-query logging."""

from __future__ import annotations

import time
from typing import Any

from sqlalchemy import event
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

from app.core.config import Settings
from app.core.logging import get_logger

log = get_logger("db")
_MAX_LOGGED_SQL = 2000


def _connect_args(settings: Settings, application_name: str) -> dict[str, Any]:
    args: dict[str, Any] = {"timeout": settings.db_connect_timeout_s}
    if settings.db_pgbouncer_mode:
        # PgBouncer transaction pooling: no server-side prepared statements, no startup parameters
        # (they would stick to whichever server connection happened to be used).
        args["statement_cache_size"] = 0
    else:
        args["server_settings"] = {"application_name": application_name}
    return args


def create_engine(
    settings: Settings, url: str, *, application_name: str, pool_size: int | None = None
) -> AsyncEngine:
    parsed = make_url(url)
    if parsed.drivername != "postgresql+asyncpg":
        raise ValueError("only postgresql+asyncpg URLs are supported for the control-plane database")
    query: dict[str, str] = {}
    if settings.db_pgbouncer_mode:
        query["prepared_statement_cache_size"] = "0"
    engine = create_async_engine(
        parsed.update_query_dict(query),
        pool_size=pool_size or settings.db_pool_size,
        max_overflow=settings.db_max_overflow,
        pool_timeout=settings.db_pool_timeout_s,
        pool_recycle=settings.db_pool_recycle_s,
        pool_pre_ping=True,
        pool_reset_on_return="rollback",
        hide_parameters=True,  # bound values never appear in exception messages or logs
        connect_args=_connect_args(settings, application_name),
    )
    _install_slow_query_logging(engine, settings.slow_query_ms)
    return engine


def _install_slow_query_logging(engine: AsyncEngine, threshold_ms: int) -> None:
    sync_engine = engine.sync_engine

    @event.listens_for(sync_engine, "before_cursor_execute")
    def _before(conn: Any, cursor: Any, statement: str, params: Any, context: Any, executemany: bool) -> None:
        conn.info.setdefault("_tc_query_start", []).append(time.perf_counter())

    @event.listens_for(sync_engine, "after_cursor_execute")
    def _after(conn: Any, cursor: Any, statement: str, params: Any, context: Any, executemany: bool) -> None:
        starts = conn.info.get("_tc_query_start")
        if not starts:
            return
        elapsed_ms = (time.perf_counter() - starts.pop()) * 1000
        if elapsed_ms >= threshold_ms:
            # Statement text only: parameters are bound separately and never logged.
            log.warning(
                "db.slow_query",
                duration_ms=round(elapsed_ms, 1),
                statement=statement[:_MAX_LOGGED_SQL],
                executemany=executemany,
            )

    @event.listens_for(sync_engine, "handle_error")
    def _on_error(ctx: Any) -> None:
        conn = ctx.connection
        if conn is not None and conn.info.get("_tc_query_start"):
            conn.info["_tc_query_start"].pop()
