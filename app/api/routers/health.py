"""Liveness and readiness. Responses reveal only up/down per dependency, never versions or errors."""

from __future__ import annotations

import asyncio
from typing import Any

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from sqlalchemy import text

from app.api.deps import container
from app.container import Container

router = APIRouter(tags=["health"])
_CHECK_TIMEOUT_S = 1.0


@router.get("/health")
async def health() -> dict[str, str]:
    """Liveness: the process is serving requests. No dependency checks (avoids restart storms)."""
    return {"status": "ok"}


async def _db_ok(c: Container) -> bool:
    try:
        async with asyncio.timeout(_CHECK_TIMEOUT_S), c.db.engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        return True
    except Exception:
        return False


async def _redis_ok(c: Container) -> bool:
    try:
        async with asyncio.timeout(_CHECK_TIMEOUT_S):
            return await c.cache.ping()
    except Exception:
        return False


@router.get("/ready")
async def ready(c: Container = Depends(container)) -> Any:
    """Readiness: bounded checks of required dependencies.

    Redis is reported but does not fail readiness: every Redis-backed feature has a defined degraded
    mode (DB fallback, local rate limiting, idempotency 503), so pulling all instances out of the
    load balancer during a Redis outage would turn a partial degradation into a total outage.
    """
    db_ok, redis_ok = await asyncio.gather(_db_ok(c), _redis_ok(c))
    body = {"status": "ready" if db_ok else "not_ready", "checks": {"database": db_ok, "redis": redis_ok}}
    return JSONResponse(body, status_code=200 if db_ok else 503)
