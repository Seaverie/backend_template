"""Map provider interface (geocoding) with Google Maps and Mapbox implementations."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Protocol

from app.integrations.http import OutboundPolicy, SafeHttpClient
from app.integrations.payments import ProviderError

GOOGLE_SECRET_RULES = {"api_key": re.compile(r"^[A-Za-z0-9_\-]{20,100}$")}
MAPBOX_SECRET_RULES = {"api_key": re.compile(r"^[ps]k\.[A-Za-z0-9_\-.]{20,300}$")}
MAX_RESULTS = 5


@dataclass(frozen=True, slots=True)
class Place:
    lat: float
    lng: float
    label: str

    def to_json(self) -> dict[str, Any]:
        return {"lat": self.lat, "lng": self.lng, "label": self.label}


class MapProvider(Protocol):
    async def geocode(self, query: str, *, language: str | None) -> list[Place]: ...

    async def reverse(self, lat: float, lng: float, *, language: str | None) -> list[Place]: ...


class GoogleMapsProvider:
    URL = "https://maps.googleapis.com/maps/api/geocode/json"
    POLICY = OutboundPolicy(frozenset({"maps.googleapis.com"}))

    def __init__(self, http: SafeHttpClient, secrets: dict[str, str]) -> None:
        self._http = http
        self._key = secrets["api_key"]

    async def _call(self, params: dict[str, str]) -> list[Place]:
        r = await self._http.request("GET", self.URL, policy=self.POLICY, params={**params, "key": self._key})
        if r.status_code != 200:
            raise ProviderError(f"google maps {r.status_code}")
        body = r.json()
        if body.get("status") not in ("OK", "ZERO_RESULTS"):
            raise ProviderError(f"google maps status {body.get('status')}")
        return [
            Place(
                float(x["geometry"]["location"]["lat"]),
                float(x["geometry"]["location"]["lng"]),
                str(x.get("formatted_address", "")),
            )
            for x in body.get("results", [])[:MAX_RESULTS]
        ]

    async def geocode(self, query: str, *, language: str | None) -> list[Place]:
        return await self._call({"address": query, **({"language": language} if language else {})})

    async def reverse(self, lat: float, lng: float, *, language: str | None) -> list[Place]:
        return await self._call({"latlng": f"{lat},{lng}", **({"language": language} if language else {})})


class MapboxProvider:
    BASE = "https://api.mapbox.com/search/geocode/v6"
    POLICY = OutboundPolicy(frozenset({"api.mapbox.com"}))

    def __init__(self, http: SafeHttpClient, secrets: dict[str, str]) -> None:
        self._http = http
        self._key = secrets["api_key"]

    async def _call(self, path: str, params: dict[str, str]) -> list[Place]:
        r = await self._http.request(
            "GET",
            f"{self.BASE}/{path}",
            policy=self.POLICY,
            params={**params, "access_token": self._key, "limit": str(MAX_RESULTS)},
        )
        if r.status_code != 200:
            raise ProviderError(f"mapbox {r.status_code}")
        out = []
        for f in r.json().get("features", [])[:MAX_RESULTS]:
            lng, lat = f["geometry"]["coordinates"][:2]
            out.append(Place(float(lat), float(lng), str(f.get("properties", {}).get("full_address", ""))))
        return out

    async def geocode(self, query: str, *, language: str | None) -> list[Place]:
        return await self._call("forward", {"q": query, **({"language": language} if language else {})})

    async def reverse(self, lat: float, lng: float, *, language: str | None) -> list[Place]:
        return await self._call("reverse", {"latitude": str(lat), "longitude": str(lng)})
