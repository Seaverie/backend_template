"""Redis access with strict timeouts and explicit outage semantics.

Every caller decides what a Redis failure means for it (see docs/SECURITY.md, "Redis outage"):
  * auth caches       -> fall back to the database (source of truth); never fail open
  * rate limiting     -> fall back to an approximate per-process limiter
  * idempotency       -> fail closed (503) on endpoints that require it
"""

from __future__ import annotations

import json
from typing import Any

import redis.asyncio as aioredis
from redis.exceptions import RedisError

from app.core.config import Settings
from app.core.logging import get_logger

log = get_logger("cache")

CacheError = (RedisError, OSError, TimeoutError)

# Set a numeric value only if it is greater than the current one. Used for version counters so a slow
# reader that loaded an old version from the database can never move the cached version backwards.
_SET_IF_GREATER = """
local cur = redis.call('GET', KEYS[1])
if (not cur) or (tonumber(ARGV[1]) > tonumber(cur)) then
  redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
  return tonumber(ARGV[1])
end
redis.call('EXPIRE', KEYS[1], ARGV[2])
return tonumber(cur)
"""


class Keys:
    """All Redis keys in one place. Versioned so a format change never reads stale shapes."""

    def __init__(self, prefix: str) -> None:
        self.p = prefix

    def api_key(self, key_id: str) -> str:
        return f"{self.p}:apikey:v1:{key_id}"

    def authz_version(self, tenant_id: str) -> str:
        return f"{self.p}:authzver:v1:{tenant_id}"

    def perms(self, tenant_id: str, user_id: str, authz_version: int) -> str:
        return f"{self.p}:perm:v1:{tenant_id}:{user_id}:{authz_version}"

    def tenant(self, tenant_id: str) -> str:
        return f"{self.p}:tenant:v1:{tenant_id}"

    def schema_version(self) -> str:
        return f"{self.p}:schemaver:v1"

    def table_meta(self, schema_version: int, name: str) -> str:
        return f"{self.p}:meta:v1:{schema_version}:{name}"

    def session_revoked(self, sid: str) -> str:
        return f"{self.p}:sessrev:v1:{sid}"

    def idempotency(self, scope: str, key_hash: str) -> str:
        return f"{self.p}:idem:v1:{scope}:{key_hash}"

    def ratelimit(self, bucket: str) -> str:
        return f"{self.p}:rl:v1:{bucket}"

    def tenant_pattern(self, tenant_id: str) -> str:
        return f"{self.p}:*{tenant_id}*"


class Cache:
    def __init__(self, settings: Settings) -> None:
        self.keys = Keys(settings.redis_key_prefix)
        self.client: aioredis.Redis = aioredis.from_url(  # type: ignore[no-untyped-call,unused-ignore]
            settings.redis_url.get_secret_value(),
            socket_timeout=settings.redis_timeout_s,
            socket_connect_timeout=settings.redis_timeout_s,
            health_check_interval=30,
            max_connections=200,
            decode_responses=False,
        )
        self._set_if_greater = self.client.register_script(_SET_IF_GREATER)

    async def get_json(self, key: str) -> Any | None:
        raw = await self.client.get(key)
        from app.observability import CACHE_OPS

        CACHE_OPS.add(
            1, {"result": "miss" if raw is None else "hit", "kind": key.split(":")[1] if ":" in key else ""}
        )
        return None if raw is None else json.loads(raw)

    async def set_json(self, key: str, value: Any, ttl_s: int, *, nx: bool = False) -> bool:
        return bool(await self.client.set(key, json.dumps(value, separators=(",", ":")), ex=ttl_s, nx=nx))

    async def set_if_greater(self, key: str, value: int, ttl_s: int) -> int:
        return int(await self._set_if_greater(keys=[key], args=[value, ttl_s]))

    async def get_int(self, key: str) -> int | None:
        raw = await self.client.get(key)
        return None if raw is None else int(raw)

    async def delete(self, *keys: str) -> None:
        if keys:
            await self.client.delete(*keys)

    async def ping(self) -> bool:
        return bool(await self.client.ping())

    async def close(self) -> None:
        await self.client.aclose()


async def safe(coro: Any, *, op: str) -> tuple[bool, Any]:
    """Await a Redis coroutine; return (ok, result). Logs and swallows Redis failures."""
    try:
        return True, await coro
    except CacheError as e:
        log.warning("cache.unavailable", op=op, error_type=type(e).__name__)
        return False, None
