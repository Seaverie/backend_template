"""End-to-end through a real Celery worker process and the Redis broker.

The API enqueues the import; a separate ``celery worker`` process (solo pool) consumes it, runs it
with its own event loop and service container, and updates ``system_tasks``. A task that fails with
a non-retryable error lands in ``system_dead_letters``.
"""

from __future__ import annotations

import asyncio
import os
import subprocess
import sys
import uuid
from collections.abc import Awaitable, Callable, Iterator
from pathlib import Path

import pytest
import redis
from httpx import AsyncClient

from app.container import Container
from app.core.config import get_settings
from tests.integration.conftest import P, TenantCtx, auth

NewTenant = Callable[..., Awaitable[TenantCtx]]
NewTable = Callable[..., Awaitable[str]]
ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="module")
def worker(tmp_path_factory: pytest.TempPathFactory) -> Iterator[Path]:
    broker = get_settings().celery_broker_url.get_secret_value()
    try:
        r = redis.Redis.from_url(broker, socket_connect_timeout=1)
        r.flushdb()  # drop messages left by other tests that enqueue without a worker
        r.close()
    except redis.RedisError as e:
        pytest.skip(f"broker not reachable: {type(e).__name__}")
    log = tmp_path_factory.mktemp("worker") / "worker.log"
    fh = log.open("wb")
    proc = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "celery",
            "-A",
            "app.workers.celery_app",
            "worker",
            "-P",
            "solo",
            "-Q",
            "default,deliveries,imports,maintenance",
            "--without-gossip",
            "--without-mingle",
            "--without-heartbeat",
            "-l",
            "info",
        ],
        cwd=ROOT,
        env={**os.environ, "TC_CELERY_MAX_RETRIES": "0"},
        stdout=fh,
        stderr=subprocess.STDOUT,
    )
    try:
        yield log
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
        fh.close()


async def _poll(fn: Callable[[], Awaitable[bool]], log: Path, timeout: float = 45.0) -> None:
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        if await fn():
            return
        await asyncio.sleep(0.3)
    pytest.fail("timed out waiting for the worker; log tail:\n" + log.read_text()[-3000:])


async def test_import_runs_on_a_real_worker(
    worker: Path, client: AsyncClient, new_tenant: NewTenant, new_table: NewTable
) -> None:
    table = await new_table([{"name": "title", "type": "string"}])
    t = await new_tenant()
    r = await client.post(
        f"{P}/import/excel/{table}", headers=t.h, files={"file": ("a.csv", b"title\none\ntwo\n", "text/csv")}
    )
    assert r.status_code == 202, r.text
    task_id = r.json()["id"]
    status: dict[str, object] = {}

    async def done() -> bool:
        status.update((await client.get(f"{P}/tasks/{task_id}", headers=t.h)).json())
        return status["status"] in ("succeeded", "failed")

    await _poll(done, worker)
    assert status["status"] == "succeeded", status
    items = (await client.get(f"{P}/data/{table}", headers=t.h)).json()["items"]
    assert sorted(i["title"] for i in items) == ["one", "two"]


async def test_non_retryable_failure_is_dead_lettered(
    worker: Path, client: AsyncClient, container: Container, platform_token: str
) -> None:
    from app.workers.tasks import offboard_tenant

    ref = f"bad-{uuid.uuid4().hex[:8]}"
    await asyncio.to_thread(offboard_tenant.apply_async, kwargs={"task_row_id": ref})
    found: list[dict[str, object]] = []

    async def dead() -> bool:
        r = await client.get(f"{P}/platform/dead-letters", headers=auth(platform_token))
        found[:] = [d for d in r.json()["items"] if d.get("reference_id") == ref]
        return bool(found)

    await _poll(dead, worker)
    assert found[0]["task_name"] == "tenantcore.offboard_tenant"
    assert "ValueError" in str(found[0]["error"])
