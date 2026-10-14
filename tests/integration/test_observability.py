"""OpenTelemetry wiring, checked with an in-memory span exporter (no collector needed).

Instrumentation is process-global, so the traced requests run in a subprocess
(``tests/integration/otel/run_traced.py``) that prints the finished spans.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

from opentelemetry.sdk.trace import TracerProvider

from app.core.config import get_settings
from app.observability import instrument, trace_context_processor

ROOT = Path(__file__).resolve().parents[2]


def _spans() -> list[dict[str, Any]]:
    out = subprocess.run(
        [sys.executable, "-m", "tests.integration.otel.run_traced"],
        cwd=ROOT,
        env=dict(os.environ),
        capture_output=True,
        text=True,
        timeout=120,
        check=False,
    )
    line = next((x for x in out.stdout.splitlines() if x.startswith("SPANS=")), None)
    assert line is not None, out.stdout[-2000:] + out.stderr[-4000:]
    return list(json.loads(line.removeprefix("SPANS=")))


def test_requests_produce_linked_server_db_and_cache_spans() -> None:
    spans = _spans()
    servers = {s["attributes"].get("http.route"): s for s in spans if s["kind"] == "SERVER"}
    assert set(servers) == {"/api/v1/platform/auth/login", "/api/v1/platform/tenants"}  # /health excluded

    login_trace = servers["/api/v1/platform/auth/login"]["trace_id"]
    in_login = [s for s in spans if s["trace_id"] == login_trace]
    db = [s for s in in_login if "sqlalchemy" in s["scope"]]
    assert any("platform_admins" in s["attributes"].get("db.statement", "") for s in db)
    assert any(s["attributes"].get("db.user") == "tc_app" for s in db)
    assert any("redis" in s["scope"] for s in in_login)  # rate limiter (Lua) call

    # Statements are recorded without bound values, and nothing secret lands in any attribute.
    blob = json.dumps(spans)
    for secret in ("Otel-Secret-Passw0rd!", "$argon2", "Bearer ", "access_token"):
        assert secret not in blob
    redis_statements = [s["attributes"].get("db.statement", "") for s in spans if "redis" in s["scope"]]
    assert redis_statements and all(set(x.split()[1:]) <= {"?"} for x in redis_statements)


def test_trace_ids_are_added_to_logs() -> None:
    provider = TracerProvider()
    with provider.get_tracer("t").start_as_current_span("op") as span:
        event = trace_context_processor(None, "info", {"event": "x"})
        assert event["trace_id"] == format(span.get_span_context().trace_id, "032x")
    assert "trace_id" not in trace_context_processor(None, "info", {"event": "y"})


def test_instrumentation_is_off_unless_enabled() -> None:
    settings = get_settings().model_copy(update={"otel_enabled": False})
    assert instrument(settings, container=None) is None
