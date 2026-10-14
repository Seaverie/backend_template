"""Geocoding through the tenant's configured map provider, with a per-tenant Redis result cache."""

from __future__ import annotations

import hashlib
from typing import Any

from app.cache.redis import Cache, safe
from app.core.config import Settings
from app.core.errors import AppError
from app.integrations.http import OutboundBlocked, OutboundError
from app.integrations.payments import ProviderError
from app.models import Action
from app.services.authz import Principal
from app.services.providers import ProviderService


class MapProviderFailed(AppError):
    status_code = 502
    code = "map_provider_error"
    message = "The map provider could not process the request."


class MapService:
    def __init__(self, settings: Settings, cache: Cache, providers: ProviderService) -> None:
        self._s = settings
        self._cache = cache
        self._providers = providers

    async def _cached(self, p: Principal, op: str, key_material: str, call: Any) -> list[dict[str, Any]]:
        digest = hashlib.sha256(key_material.encode()).hexdigest()
        key = f"{self._cache.keys.p}:maps:v1:{p.tenant_id}:{op}:{digest}"
        if self._s.maps_cache_ttl_s:
            ok, hit = await safe(self._cache.get_json(key), op="maps.get")
            if ok and hit is not None:
                return hit  # type: ignore[no-any-return]
        try:
            places = await call()
        except (ProviderError, OutboundError, OutboundBlocked) as e:
            raise MapProviderFailed() from e
        out = [x.to_json() for x in places]
        if self._s.maps_cache_ttl_s:
            await safe(self._cache.set_json(key, out, self._s.maps_cache_ttl_s), op="maps.set")
        return out

    async def geocode(self, p: Principal, query: str, language: str | None) -> list[dict[str, Any]]:
        p.require("maps", Action.read)
        provider_id, provider = await self._providers.map_provider(p.tenant_id)
        return await self._cached(
            p,
            "geocode",
            f"{provider_id}|{language}|{query.strip().lower()}",
            lambda: provider.geocode(query, language=language),
        )

    async def reverse(
        self, p: Principal, lat: float, lng: float, language: str | None
    ) -> list[dict[str, Any]]:
        p.require("maps", Action.read)
        provider_id, provider = await self._providers.map_provider(p.tenant_id)
        return await self._cached(
            p,
            "reverse",
            f"{provider_id}|{language}|{lat:.6f},{lng:.6f}",
            lambda: provider.reverse(lat, lng, language=language),
        )
