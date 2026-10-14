"""OpenTelemetry: traces and metrics for FastAPI, SQLAlchemy, Redis, Celery and outbound httpx calls.

Export goes over OTLP/HTTP to an OpenTelemetry Collector (``TC_OTEL_EXPORTER_ENDPOINT``), which forwards
traces to Jaeger and metrics to Prometheus (see ``infra/otel/collector.yaml``).

Privacy:
* SQLAlchemy spans carry the statement text only. Parameters are never attached, because SQLAlchemy
  instrumentation records ``db.statement`` without bound values.
* httpx spans record method, host and status, not headers or bodies.
* Health checks are excluded from tracing.

Sampling is parent-based with ``TC_OTEL_SAMPLE_RATIO`` for new root traces.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from opentelemetry import metrics, trace
from opentelemetry.metrics import CallbackOptions, Observation
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import SpanProcessor, TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased
from structlog.typing import EventDict

from app.core.config import Settings

_meter = metrics.get_meter("tenantcore")
CACHE_OPS = _meter.create_counter("tenantcore.cache.operations", description="Cache lookups by result")
OUTBOUND_LATENCY = _meter.create_histogram(
    "tenantcore.outbound.duration", unit="s", description="Outbound integration call latency"
)
JOBS = _meter.create_counter("tenantcore.jobs", description="Background job outcomes")


class _State:
    configured = False


def trace_context_processor(_: Any, __: str, event_dict: EventDict) -> EventDict:
    """structlog processor: add trace/span ids so logs and traces can be joined."""
    ctx = trace.get_current_span().get_span_context()
    if ctx.is_valid:
        event_dict.setdefault("trace_id", format(ctx.trace_id, "032x"))
        event_dict.setdefault("span_id", format(ctx.span_id, "016x"))
    return event_dict


def build_tracer_provider(
    settings: Settings, service: str, processor: SpanProcessor | None = None
) -> TracerProvider:
    provider = TracerProvider(
        resource=Resource.create({"service.name": service, "deployment.environment": settings.environment}),
        sampler=ParentBased(TraceIdRatioBased(settings.otel_sample_ratio)),
    )
    if processor is None:
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

        processor = BatchSpanProcessor(
            OTLPSpanExporter(endpoint=f"{settings.otel_exporter_endpoint}/v1/traces")
        )
    provider.add_span_processor(processor)
    return provider


def _setup_metrics(settings: Settings, container: Any) -> None:
    from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
    from opentelemetry.sdk.metrics import MeterProvider
    from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader

    reader = PeriodicExportingMetricReader(
        OTLPMetricExporter(endpoint=f"{settings.otel_exporter_endpoint}/v1/metrics"),
        export_interval_millis=15_000,
    )
    metrics.set_meter_provider(
        MeterProvider(
            resource=Resource.create({"service.name": settings.service_name}), metric_readers=[reader]
        )
    )
    register_pool_gauges(container)


def register_pool_gauges(container: Any) -> None:
    def pool_usage(_: CallbackOptions) -> Iterable[Observation]:
        pool = container.db.engine.pool
        yield Observation(pool.checkedout(), {"state": "in_use"})
        yield Observation(pool.checkedin(), {"state": "idle"})

    def dedicated_engines(_: CallbackOptions) -> Iterable[Observation]:
        yield Observation(container.tenant_db.engine_count())

    _meter.create_observable_gauge("tenantcore.db.pool.connections", callbacks=[pool_usage])
    _meter.create_observable_gauge("tenantcore.db.dedicated_engines", callbacks=[dedicated_engines])


def instrument(
    settings: Settings,
    container: Any,
    *,
    app: Any = None,
    tracer_provider: TracerProvider | None = None,
    celery: bool = False,
) -> TracerProvider | None:
    """Instrument everything. Without ``tracer_provider`` this is a no-op unless OTel is enabled."""
    if tracer_provider is None:
        if not settings.otel_enabled:
            return None
        tracer_provider = build_tracer_provider(
            settings, settings.service_name + ("-worker" if celery else "")
        )
        if not _State.configured:
            trace.set_tracer_provider(tracer_provider)
            _setup_metrics(settings, container)
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
    from opentelemetry.instrumentation.redis import RedisInstrumentor
    from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor

    engines = [container.db.engine.sync_engine]
    if container.db.ddl_engine is not None:
        engines.append(container.db.ddl_engine.sync_engine)
    SQLAlchemyInstrumentor().instrument(
        engines=engines, tracer_provider=tracer_provider, enable_commenter=False
    )
    if not _State.configured:
        RedisInstrumentor().instrument(tracer_provider=tracer_provider)
        HTTPXClientInstrumentor().instrument(tracer_provider=tracer_provider)
    if app is not None:
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor

        FastAPIInstrumentor.instrument_app(app, tracer_provider=tracer_provider, excluded_urls="health,ready")
    if celery and not _State.configured:
        from opentelemetry.instrumentation.celery import CeleryInstrumentor

        CeleryInstrumentor().instrument(tracer_provider=tracer_provider)  # type: ignore[no-untyped-call]
    _State.configured = True
    return tracer_provider


def setup_worker_telemetry(settings: Settings, container: Any) -> None:
    instrument(settings, container, celery=True)
