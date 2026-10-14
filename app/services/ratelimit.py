"""Atomic multi-level rate limiting (GCRA) in Redis.

A request is checked against several buckets (platform, tenant, principal, endpoint) in a *single*
Lua script: either every bucket admits it and all are charged, or none is charged. That avoids
partial consumption and check-then-act races between instances. Time comes from Redis ``TIME``, so
clock skew between API instances does not matter.

All keys of one call must live on the same Redis node. Single-node and Sentinel deployments satisfy
this. For Redis Cluster, wrap the keys in a common hash tag.

Redis outage: each process falls back to an in-memory GCRA with every limit divided by
``rl_local_fallback_divisor`` (roughly the number of API instances). Enforcement becomes approximate
but stays on. The API neither fails open nor rejects all traffic.
"""

from __future__ import annotations

import math
import time
from collections import OrderedDict
from dataclasses import dataclass

from app.cache.redis import Cache, safe
from app.core.config import Settings

_GCRA = """
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local n = #KEYS
local new_tats = {}
for i = 1, n do
  local limit = tonumber(ARGV[2 * i - 1])
  local period = tonumber(ARGV[2 * i])
  local interval = period / limit
  local tat = tonumber(redis.call('GET', KEYS[i]) or now)
  if tat < now then tat = now end
  local new_tat = tat + interval
  local allow_at = new_tat - period
  if allow_at > now then
    return {0, i, math.ceil(allow_at - now)}
  end
  new_tats[i] = new_tat
end
for i = 1, n do
  redis.call('SET', KEYS[i], tostring(new_tats[i]), 'PX', math.ceil(new_tats[i] - now) + 1000)
end
return {1, 0, 0}
"""

PERIOD_MS = 60_000


@dataclass(frozen=True, slots=True)
class Bucket:
    name: str
    per_min: int


@dataclass(frozen=True, slots=True)
class Decision:
    allowed: bool
    bucket: str | None = None
    retry_after_s: int = 0
    degraded: bool = False


class _LocalGcra:
    """Bounded in-process fallback. Not shared across processes; deliberately conservative."""

    def __init__(self, max_keys: int = 50_000) -> None:
        self._tat: OrderedDict[str, float] = OrderedDict()
        self._max = max_keys

    def check(self, buckets: list[Bucket], divisor: int) -> Decision:
        now = time.monotonic() * 1000
        staged: list[tuple[str, float]] = []
        for b in buckets:
            limit = max(1, b.per_min // divisor)
            interval = PERIOD_MS / limit
            tat = max(self._tat.get(b.name, now), now)
            new_tat = tat + interval
            if new_tat - PERIOD_MS > now:
                return Decision(False, b.name, math.ceil((new_tat - PERIOD_MS - now) / 1000), degraded=True)
            staged.append((b.name, new_tat))
        for name, tat in staged:
            self._tat[name] = tat
            self._tat.move_to_end(name)
        while len(self._tat) > self._max:
            self._tat.popitem(last=False)
        return Decision(True, degraded=True)


class RateLimiter:
    def __init__(self, settings: Settings, cache: Cache) -> None:
        self._s = settings
        self._cache = cache
        self._script = cache.client.register_script(_GCRA)
        self._local = _LocalGcra()

    async def check(self, buckets: list[Bucket]) -> Decision:
        keys = [self._cache.keys.ratelimit(b.name) for b in buckets]
        args: list[int] = []
        for b in buckets:
            args += [b.per_min, PERIOD_MS]
        ok, res = await safe(self._script(keys=keys, args=args), op="ratelimit")
        if not ok:
            return self._local.check(buckets, self._s.rl_local_fallback_divisor)
        allowed, idx, retry_ms = int(res[0]), int(res[1]), int(res[2])
        if allowed:
            return Decision(True)
        return Decision(False, buckets[idx - 1].name, max(1, math.ceil(retry_ms / 1000)))
