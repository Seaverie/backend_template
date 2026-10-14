"""Celery task wrappers.

Each worker process owns one event loop and one service container (created on first use and closed at
process shutdown), because async SQLAlchemy engines and Redis clients are bound to the loop that
created them.
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

from celery import Task
from celery.signals import worker_process_shutdown
from redis.exceptions import RedisError
from sqlalchemy.exc import DBAPIError, OperationalError

from app.container import Container
from app.core.config import get_settings
from app.core.logging import configure_logging, get_logger
from app.observability import JOBS
from app.workers import jobs
from app.workers.celery_app import celery

log = get_logger("worker")
T = TypeVar("T")


class _Runtime:
    loop: asyncio.AbstractEventLoop | None = None
    container: Container | None = None


def _runtime() -> tuple[asyncio.AbstractEventLoop, Container]:
    if _Runtime.loop is None or _Runtime.container is None:
        settings = get_settings()
        configure_logging(settings.log_level, settings.log_json)
        _Runtime.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(_Runtime.loop)
        _Runtime.container = _Runtime.loop.run_until_complete(_build(settings))
    return _Runtime.loop, _Runtime.container


async def _build(settings: Any) -> Container:
    from app.observability import setup_worker_telemetry

    c = Container.build(settings)
    setup_worker_telemetry(settings, c)
    return c


def run(fn: Callable[[Container], Awaitable[T]]) -> T:
    loop, c = _runtime()
    return loop.run_until_complete(fn(c))


@worker_process_shutdown.connect
def _shutdown(**_: Any) -> None:
    if _Runtime.loop is not None and _Runtime.container is not None:
        _Runtime.loop.run_until_complete(_Runtime.container.close())


class ReliableTask(Task):  # type: ignore[misc]
    """Retries transient infrastructure errors with exponential backoff + jitter; dead-letters the rest."""

    autoretry_for = (OperationalError, DBAPIError, RedisError, ConnectionError, TimeoutError)
    retry_backoff = 5
    retry_backoff_max = 600
    retry_jitter = True
    max_retries = get_settings().celery_max_retries
    acks_late = True

    def on_success(self, retval: Any, task_id: str, args: Any, kwargs: Any) -> None:
        JOBS.add(1, {"task": self.name, "outcome": "succeeded"})

    def on_retry(self, exc: BaseException, task_id: str, args: Any, kwargs: Any, einfo: Any) -> None:
        JOBS.add(1, {"task": self.name, "outcome": "retry"})

    def on_failure(self, exc: BaseException, task_id: str, args: Any, kwargs: Any, einfo: Any) -> None:
        JOBS.add(1, {"task": self.name, "outcome": "dead_letter"})
        tenant = kwargs.get("tenant_id")
        ref = kwargs.get("task_row_id") or task_id
        msg = f"{type(exc).__name__}: {str(exc)[:500]}"
        log.error("task.dead_letter", task=self.name, reference=str(ref), error_type=type(exc).__name__)
        try:
            run(
                lambda c: jobs.dead_letter(
                    c,
                    tenant_id=uuid.UUID(tenant) if tenant else None,
                    source="task",
                    reference_id=str(ref),
                    task_name=self.name,
                    error=msg,
                )
            )
            if kwargs.get("task_row_id"):
                run(lambda c: jobs.fail_task(c, uuid.UUID(kwargs["task_row_id"]), msg))
        except Exception as e:  # never mask the original failure
            log.error("task.dead_letter_failed", error_type=type(e).__name__)


@celery.task(base=ReliableTask, name="tenantcore.relay_outbox")
def relay_outbox() -> int:
    n = run(jobs.relay_outbox)
    if n:
        dispatch_webhooks.delay()
        dispatch_notifications.delay()
    return n


@celery.task(base=ReliableTask, name="tenantcore.dispatch_webhooks")
def dispatch_webhooks() -> dict[str, int]:
    return run(jobs.dispatch_webhooks)


@celery.task(base=ReliableTask, name="tenantcore.dispatch_notifications")
def dispatch_notifications() -> dict[str, int]:
    return run(jobs.dispatch_notifications)


@celery.task(base=ReliableTask, name="tenantcore.run_import")
def run_import(*, tenant_id: str, task_row_id: str) -> str:
    return run(lambda c: c.imports.run(uuid.UUID(tenant_id), uuid.UUID(task_row_id))).value


@celery.task(base=ReliableTask, name="tenantcore.offboard_tenant")
def offboard_tenant(*, task_row_id: str) -> str:
    return run(lambda c: c.offboarding.run(uuid.UUID(task_row_id))).value


@celery.task(base=ReliableTask, name="tenantcore.requeue_stale_tasks")
def requeue_stale_tasks() -> int:
    stale = run(jobs.stale_tasks)
    for kind, tenant_id, task_id in stale:
        enqueue(kind, tenant_id, task_id)
    return len(stale)


@celery.task(base=ReliableTask, name="tenantcore.cleanup")
def cleanup() -> dict[str, int]:
    return run(jobs.cleanup)


def enqueue(kind: str, tenant_id: uuid.UUID | None, task_id: uuid.UUID) -> bool:
    """Send a task message. Returns False if the broker is unreachable; the task row stays ``queued``
    and ``requeue_stale_tasks`` sends it later, so submission never depends on the broker being up."""
    try:
        if kind == "import" and tenant_id is not None:
            run_import.apply_async(
                kwargs={"tenant_id": str(tenant_id), "task_row_id": str(task_id)}, retry=False
            )
        elif kind == "offboard":
            offboard_tenant.apply_async(kwargs={"task_row_id": str(task_id)}, retry=False)
        else:
            return False
    except Exception as e:  # broker down: rely on the stale-task sweeper
        log.warning("task.enqueue_deferred", kind=kind, error_type=type(e).__name__)
        return False
    return True


def nudge_relay() -> None:
    """Best-effort hint after commits that produced outbox events; beat runs the relay anyway."""
    try:
        relay_outbox.apply_async(retry=False)
    except Exception as e:
        log.debug("relay.nudge_failed", error_type=type(e).__name__)
