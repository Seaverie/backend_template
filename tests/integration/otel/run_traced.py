"""Run a few API requests with tracing into an in-memory exporter; print the finished spans as JSON.

Executed in a subprocess by test_observability.py because OpenTelemetry instrumentation of Redis and
httpx is process-global and must not leak into the rest of the test session.
"""

from __future__ import annotations

import asyncio
import json
import sys
import uuid

from httpx import ASGITransport, AsyncClient
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from sqlalchemy import text

from app.container import Container
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.main import create_app
from app.models import PlatformAdmin
from app.observability import build_tracer_provider, instrument
from app.security.passwords import hash_password

P = "/api/v1"
SECRET = "Otel-Secret-Passw0rd!"


async def main() -> None:
    settings = get_settings().model_copy(update={"otel_sample_ratio": 1.0})
    exporter = InMemorySpanExporter()
    provider = build_tracer_provider(settings, "tenantcore-test", SimpleSpanProcessor(exporter))
    c = Container.build(settings)
    app = create_app(settings, c)
    app.state.container = c
    instrument(settings, c, app=app, tracer_provider=provider)
    configure_logging("INFO", True)

    email = f"otel-{uuid.uuid4().hex[:8]}@example.com"
    async with c.db.ddl_session() as s:
        await s.execute(text("SELECT set_config('app.is_platform', 'on', true)"))
        s.add(PlatformAdmin(email=email, password_hash=await hash_password(SECRET)))
        await s.commit()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        await ac.get("/health")
        r = await ac.post(f"{P}/platform/auth/login", json={"email": email, "password": SECRET})
        assert r.status_code == 200, r.text
        r = await ac.get(
            f"{P}/platform/tenants", headers={"Authorization": f"Bearer {r.json()['access_token']}"}
        )
        assert r.status_code == 200, r.text
    await c.close()
    provider.force_flush()
    out = [
        {
            "name": s.name,
            "trace_id": format(s.context.trace_id, "032x"),
            "kind": s.kind.name,
            "attributes": {k: str(v) for k, v in (s.attributes or {}).items()},
            "scope": s.instrumentation_scope.name if s.instrumentation_scope else "",
        }
        for s in exporter.get_finished_spans()
    ]
    sys.stdout.write("SPANS=" + json.dumps(out) + "\n")


asyncio.run(main())
