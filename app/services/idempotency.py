"""Idempotency-Key support for state-changing endpoints.

Protocol (per tenant + principal + method + path + key):
  * first request   -> reserve the key as ``pending`` (short TTL), run the handler, store the response
                       for ``idempotency_ttl_s`` (24 h)
  * retry, same body -> replay the stored status/body with ``Idempotent-Replayed: true``
  * retry, different body -> 422 (a key may not be reused for a different request)
  * retry while the first is still running -> 409
  * handler raised / returned 5xx -> reservation released so the client can retry safely

Redis unavailable: requests that carry or require a key are rejected with 503 (fail closed). Without
the store we cannot guarantee a retried financial operation is not executed twice.
"""

from __future__ import annotations

import contextlib
import hashlib
import json
import re
from collections.abc import Awaitable, Callable
from typing import Any

from fastapi import Request
from fastapi.responses import JSONResponse, Response

from app.cache.redis import Cache, CacheError
from app.core.config import Settings
from app.core.errors import AppError, Conflict, ServiceUnavailable, ValidationFailed
from app.core.logging import request_id_var
from app.services.authz import Principal

_KEY_RE = re.compile(r"^[A-Za-z0-9._:-]{8,128}$")
_PENDING_TTL_S = 120  # longer than any request may run; a crashed worker frees the key after this

_BEGIN = """
local v = redis.call('GET', KEYS[1])
if v then return v end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return false
"""


class IdempotencyService:
    def __init__(self, settings: Settings, cache: Cache) -> None:
        self._s = settings
        self._cache = cache
        self._begin = cache.client.register_script(_BEGIN)

    async def run(
        self,
        request: Request,
        principal: Principal,
        *,
        required: bool,
        handler: Callable[[], Awaitable[Response]],
    ) -> Response:
        raw_key = request.headers.get("Idempotency-Key")
        if raw_key is None:
            if required:
                raise ValidationFailed("Idempotency-Key header is required for this endpoint.")
            return await handler()
        if not _KEY_RE.fullmatch(raw_key):
            raise ValidationFailed("Idempotency-Key must be 8-128 characters of [A-Za-z0-9._:-].")

        body = await request.body()
        if len(body) > self._s.idempotency_max_body_bytes:
            raise ValidationFailed("Request body too large for an idempotent request.")
        fingerprint = hashlib.sha256(
            request.method.encode() + b"\0" + request.url.path.encode() + b"\0" + body
        ).hexdigest()
        scope = f"{principal.tenant_id}:{principal.kind}:{principal.actor_id}"
        key = self._cache.keys.idempotency(scope, hashlib.sha256(raw_key.encode()).hexdigest())

        try:
            existing = await self._begin(
                keys=[key], args=[json.dumps({"state": "pending", "fp": fingerprint}), _PENDING_TTL_S]
            )
        except CacheError as e:
            raise ServiceUnavailable("Idempotency store unavailable; retry later.") from e

        if existing:
            stored: dict[str, Any] = json.loads(existing)
            if stored.get("fp") != fingerprint:
                raise ValidationFailed("Idempotency-Key was already used with a different request.")
            if stored["state"] == "pending":
                raise Conflict("A request with this Idempotency-Key is still in progress.")
            return Response(
                content=stored["body"].encode(),
                status_code=int(stored["status"]),
                media_type="application/json",
                headers={"Idempotent-Replayed": "true"},
            )

        try:
            response = await handler()
        except AppError as e:
            if e.status_code >= 500:
                await self._release(key)
            else:
                await self._store(key, fingerprint, e.status_code, _error_body(e))
            raise
        except BaseException:
            await self._release(key)
            raise

        if response.status_code >= 500:
            await self._release(key)
            return response
        body_bytes = bytes(response.body) if isinstance(response, JSONResponse | Response) else b""
        await self._store(key, fingerprint, response.status_code, body_bytes.decode())
        return response

    async def _store(self, key: str, fp: str, status: int, body: str) -> None:
        payload = json.dumps({"state": "done", "fp": fp, "status": status, "body": body})
        # If this fails the operation has still committed. The pending marker remains (TTL
        # _PENDING_TTL_S), so a quick retry gets 409 rather than a duplicate execution.
        with contextlib.suppress(*CacheError):
            await self._cache.client.set(key, payload, ex=self._s.idempotency_ttl_s)

    async def _release(self, key: str) -> None:
        with contextlib.suppress(*CacheError):
            await self._cache.client.delete(key)


def _error_body(e: AppError) -> str:
    return json.dumps({"error": {"code": e.code, "message": e.message, "request_id": request_id_var.get()}})
