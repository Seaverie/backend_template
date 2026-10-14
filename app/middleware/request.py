"""Pure-ASGI middleware: request IDs, request-size limits, security headers, access logs.

Written as raw ASGI (not BaseHTTPMiddleware) so the body limit is enforced while streaming, before
the whole body is buffered, and contextvars propagate correctly to handlers.
"""

from __future__ import annotations

import json
import re
import time
import uuid
from typing import Any

from starlette.exceptions import HTTPException
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.logging import get_logger, request_id_var, tenant_id_var

log = get_logger("http")
_RID_RE = re.compile(r"^[A-Za-z0-9._-]{8,64}$")
_SECURITY_HEADERS = [
    (b"x-content-type-options", b"nosniff"),
    (b"x-frame-options", b"DENY"),
    (b"referrer-policy", b"no-referrer"),
    (b"cache-control", b"no-store"),
    (b"content-security-policy", b"default-src 'none'; frame-ancestors 'none'"),
]


class _BodyTooLarge(HTTPException):
    """Subclass of HTTPException so FastAPI's body parsing re-raises it unchanged (-> 413)."""

    def __init__(self) -> None:
        super().__init__(status_code=413, detail="Request body too large.")


class RequestContextMiddleware:
    def __init__(self, app: ASGIApp, *, max_body_bytes: int) -> None:
        self.app = app
        self.max_body = max_body_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = {k.lower(): v for k, v in scope.get("headers", [])}
        incoming = headers.get(b"x-request-id", b"").decode("latin-1")
        rid = incoming if _RID_RE.fullmatch(incoming) else uuid.uuid4().hex
        rid_token = request_id_var.set(rid)
        tid_token = tenant_id_var.set(None)
        start = time.perf_counter()
        status_holder: dict[str, int] = {"status": 500}

        declared = headers.get(b"content-length")
        if declared is not None:
            try:
                too_big = int(declared) > self.max_body
            except ValueError:
                too_big = True
            if too_big:
                await self._reject(send, rid, 413, "payload_too_large", "Request body too large.")
                self._finish(scope, rid_token, tid_token, 413, start)
                return

        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_body:
                    raise _BodyTooLarge
            return message

        response_started = False

        async def send_wrapper(message: Message) -> None:
            nonlocal response_started
            if message["type"] == "http.response.start":
                response_started = True
                status_holder["status"] = message["status"]
                hdrs = list(message.get("headers", []))
                present = {k.lower() for k, _ in hdrs}
                hdrs.append((b"x-request-id", rid.encode()))
                hdrs += [(k, v) for k, v in _SECURITY_HEADERS if k not in present]
                message = {**message, "headers": hdrs}
            await send(message)

        try:
            await self.app(scope, limited_receive, send_wrapper)
        except _BodyTooLarge:
            if not response_started:
                await self._reject(send, rid, 413, "payload_too_large", "Request body too large.")
            status_holder["status"] = 413
        finally:
            self._finish(scope, rid_token, tid_token, status_holder["status"], start)

    @staticmethod
    async def _reject(send: Send, rid: str, status: int, code: str, message: str) -> None:
        body = json.dumps({"error": {"code": code, "message": message, "request_id": rid}}).encode()
        await send(
            {
                "type": "http.response.start",
                "status": status,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(body)).encode()),
                    (b"x-request-id", rid.encode()),
                    *_SECURITY_HEADERS,
                ],
            }
        )
        await send({"type": "http.response.body", "body": body})

    @staticmethod
    def _finish(scope: Scope, rid_token: Any, tid_token: Any, status: int, start: float) -> None:
        # Path template is not available here; log the raw path (no query string: may hold filters/PII).
        log.info(
            "http.request",
            method=scope.get("method"),
            path=scope.get("path"),
            status=status,
            duration_ms=round((time.perf_counter() - start) * 1000, 1),
        )
        request_id_var.reset(rid_token)
        tenant_id_var.reset(tid_token)
