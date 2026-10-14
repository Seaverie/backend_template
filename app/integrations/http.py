"""SSRF-safe outbound HTTP client used by every external integration.

Defences, in order:
  1. HTTPS only; no userinfo in URLs; port allowlist (443 by default).
  2. Host allowlist per integration (e.g. ``api.stripe.com``). Tenants choose a provider, never a URL.
  3. DNS is resolved by us, and **every** resolved address must be globally routable. Loopback,
     RFC1918, link-local (incl. 169.254.169.254 metadata), CGNAT, multicast, reserved and IPv4-mapped
     private IPv6 are refused.
  4. The connection goes to the address we validated (``Host`` header and TLS SNI keep the original
     name, so certificate verification is unchanged). This closes the DNS-rebinding window between
     check and connect.
  5. Redirects are never followed; responses are size-capped and time-bounded.
"""

from __future__ import annotations

import asyncio
import ipaddress
import socket
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.core.logging import get_logger

log = get_logger("outbound")

Resolver = Callable[[str, int], Awaitable[list[str]]]


class OutboundBlocked(Exception):
    """The request was refused by the outbound policy (never sent)."""


class OutboundError(Exception):
    """Transport failure, timeout or oversized response."""


@dataclass(frozen=True, slots=True)
class OutboundPolicy:
    allowed_hosts: frozenset[str]
    allowed_ports: frozenset[int] = field(default_factory=lambda: frozenset({443}))


@dataclass(frozen=True, slots=True)
class SafeResponse:
    status_code: int
    headers: dict[str, str]
    body: bytes

    def json(self) -> Any:
        import json

        return json.loads(self.body)


async def system_resolver(host: str, port: int) -> list[str]:
    infos = await asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM)
    return sorted({str(i[4][0]) for i in infos})


def is_public_address(ip: str) -> bool:
    addr = ipaddress.ip_address(ip)
    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped is not None:
        addr = addr.ipv4_mapped
    if isinstance(addr, ipaddress.IPv6Address) and addr.sixtofour is not None:
        addr = addr.sixtofour
    return bool(addr.is_global) and not (
        addr.is_multicast or addr.is_reserved or addr.is_loopback or addr.is_link_local or addr.is_private
    )


class SafeHttpClient:
    def __init__(
        self,
        *,
        timeout_s: float = 10.0,
        max_response_bytes: int = 1_048_576,
        transport: httpx.AsyncBaseTransport | None = None,
        resolver: Resolver | None = None,
    ) -> None:
        self._max = max_response_bytes
        self._resolver = resolver or system_resolver
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_s, connect=min(timeout_s, 5.0)),
            follow_redirects=False,
            transport=transport,
            trust_env=False,  # never route through env-configured proxies implicitly
        )

    async def request(
        self,
        method: str,
        url: str,
        *,
        policy: OutboundPolicy,
        headers: dict[str, str] | None = None,
        params: dict[str, Any] | None = None,
        data: dict[str, Any] | None = None,
        json_body: Any = None,
        content: bytes | None = None,
    ) -> SafeResponse:
        u = httpx.URL(url)
        host = (u.host or "").lower()
        port = u.port or 443
        if u.scheme != "https":
            raise OutboundBlocked("only https is allowed")
        if u.userinfo:
            raise OutboundBlocked("credentials in URL are not allowed")
        if host not in policy.allowed_hosts:
            raise OutboundBlocked("host is not allowlisted for this integration")
        if port not in policy.allowed_ports:
            raise OutboundBlocked("port is not allowed")
        try:
            addresses = await self._resolver(host, port)
        except OSError as e:
            raise OutboundError("DNS resolution failed") from e
        if not addresses or not all(is_public_address(a) for a in addresses):
            log.warning("outbound.blocked_address", host=host)
            raise OutboundBlocked("host resolves to a non-public address")

        pinned = u.copy_with(host=addresses[0])
        req_headers = {**(headers or {}), "Host": host if port == 443 else f"{host}:{port}"}
        req = self._client.build_request(
            method,
            pinned,
            headers=req_headers,
            params=params,
            data=data,
            json=json_body,
            content=content,
            extensions={"sni_hostname": host},
        )
        try:
            resp = await self._client.send(req, stream=True)
        except httpx.HTTPError as e:
            raise OutboundError(f"request failed: {type(e).__name__}") from e
        try:
            body = bytearray()
            async for chunk in resp.aiter_bytes():
                body.extend(chunk)
                if len(body) > self._max:
                    raise OutboundError("response too large")
        finally:
            await resp.aclose()
        if 300 <= resp.status_code < 400:
            raise OutboundError("redirects are not followed")
        return SafeResponse(resp.status_code, dict(resp.headers), bytes(body))

    async def close(self) -> None:
        await self._client.aclose()
