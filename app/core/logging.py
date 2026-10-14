"""Structured logging with request correlation and secret redaction."""

from __future__ import annotations

import logging
import re
import sys
from contextvars import ContextVar
from typing import Any

import structlog
from structlog.typing import EventDict

request_id_var: ContextVar[str | None] = ContextVar("request_id", default=None)
tenant_id_var: ContextVar[str | None] = ContextVar("tenant_id", default=None)

_SENSITIVE_KEYS = re.compile(
    r"(pass(word)?|secret|token|authorization|api[_-]?key|cookie|pepper|credential|digest|dsn|private)",
    re.IGNORECASE,
)
# Bearer tokens, JWTs, our API key format, and URLs with embedded credentials.
_SENSITIVE_VALUES = [
    re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/=-]+"),
    re.compile(r"eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*"),
    re.compile(r"tck_[A-Za-z0-9]+_[A-Za-z0-9_-]+"),
    re.compile(r"(?i)([a-z][a-z0-9+.-]*://)[^/\s:@]+:[^/\s@]+@"),
]
REDACTED = "[REDACTED]"


def redact_value(value: Any) -> Any:
    if isinstance(value, str):
        for pattern in _SENSITIVE_VALUES:
            value = pattern.sub(lambda m: (m.group(1) + REDACTED + "@") if m.lastindex else REDACTED, value)
        return value
    if isinstance(value, dict):
        return {
            k: (REDACTED if _SENSITIVE_KEYS.search(str(k)) else redact_value(v)) for k, v in value.items()
        }
    if isinstance(value, list | tuple):
        return [redact_value(v) for v in value]
    return value


def _redact_processor(_: Any, __: str, event_dict: EventDict) -> EventDict:
    return {
        k: (REDACTED if _SENSITIVE_KEYS.search(k) and k != "event" else redact_value(v))
        for k, v in event_dict.items()
    }


def _context_processor(_: Any, __: str, event_dict: EventDict) -> EventDict:
    rid = request_id_var.get()
    if rid:
        event_dict.setdefault("request_id", rid)
    tid = tenant_id_var.get()
    if tid:
        event_dict.setdefault("tenant_id", tid)
    return event_dict


def _trace_processor(logger: Any, method: str, event_dict: EventDict) -> EventDict:
    from app.observability import trace_context_processor

    return trace_context_processor(logger, method, event_dict)


def configure_logging(level: str = "INFO", json_output: bool = True) -> None:
    logging.basicConfig(format="%(message)s", stream=sys.stdout, level=level.upper(), force=True)
    # SQLAlchemy echo would print bound parameters; keep it off. Slow queries are logged by our hook.
    logging.getLogger("sqlalchemy.engine").setLevel(logging.WARNING)
    renderer: Any = structlog.processors.JSONRenderer() if json_output else structlog.dev.ConsoleRenderer()
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            _context_processor,
            _trace_processor,
            _redact_processor,
            structlog.processors.format_exc_info,
            renderer,
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.getLevelName(level.upper())),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str) -> structlog.stdlib.BoundLogger:
    return structlog.get_logger(name)  # type: ignore[no-any-return]
