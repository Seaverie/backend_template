"""Request dependencies: authentication, principal resolution, rate limiting.

Tenant identity is taken only from verified credentials (signed JWT ``tid`` or the API key's
database row). Client-supplied tenant identifiers (headers, query, body) are never consulted.
"""

from __future__ import annotations

import hashlib
from collections.abc import Awaitable, Callable

from fastapi import Depends, Request

from app.container import Container
from app.core.errors import RateLimited, Unauthorized, ValidationFailed
from app.core.logging import request_id_var, tenant_id_var
from app.services.audit import AuditContext
from app.services.authz import PlatformPrincipal, Principal
from app.services.ratelimit import Bucket


def container(request: Request) -> Container:
    return request.app.state.container  # type: ignore[no-any-return]


def client_ip(request: Request) -> str | None:
    # The ASGI server resolves X-Forwarded-For only for trusted proxies (uvicorn --forwarded-allow-ips).
    return request.client.host if request.client else None


def _bearer(request: Request) -> str | None:
    auth = request.headers.get("Authorization")
    if not auth:
        return None
    scheme, _, token = auth.partition(" ")
    if scheme.lower() != "bearer" or not token or len(token) > 4096:
        raise Unauthorized("Malformed Authorization header.")
    return token.strip()


async def authenticate(request: Request, c: Container = Depends(container)) -> Principal:
    token = _bearer(request)
    api_key = request.headers.get("X-API-Key")
    if token and api_key:
        raise ValidationFailed("Send either a bearer token or an API key, not both.")
    if token:
        claims = c.tokens.verify(token, expected_typ="access", expected_aud="tenant")
        if claims.tid is None:  # verify() already enforces this for the tenant audience
            raise Unauthorized("Invalid token.")
        tenant = await c.tenants.get(claims.tid)
        if tenant is None or not tenant.active:
            raise Unauthorized("Tenant is not active.")
        if not await c.auth.session_is_valid(claims):
            raise Unauthorized("Session revoked.")
        perms = await c.authz.matrix(claims.tid, claims.sub)
        if not perms.user_active:
            raise Unauthorized("Account disabled.")
        principal = Principal("user", claims.tid, claims.sub, claims.sub, claims.sid, perms)
    elif api_key:
        if len(api_key) > 200:
            raise Unauthorized("Invalid API key.")
        ident = await c.api_keys.authenticate(api_key)
        tenant = await c.tenants.get(ident.tenant_id)
        if tenant is None or not tenant.active:
            raise Unauthorized("Tenant is not active.")
        perms = await c.authz.matrix(ident.tenant_id, ident.creator_id)
        if not perms.user_active:
            raise Unauthorized("API key owner is disabled.")
        principal = Principal(
            "api_key", ident.tenant_id, ident.creator_id, ident.row_id, None, perms, ident.scopes
        )
    else:
        raise Unauthorized()
    tenant_id_var.set(str(principal.tenant_id))
    request.state.tenant_rate_limit = tenant.rate_limit_per_min
    return principal


def rate_limited(
    endpoint: str | None = None, per_min: int | None = None
) -> Callable[..., Awaitable[Principal]]:
    """Authenticate, then charge platform, tenant, principal (and optional endpoint) buckets atomically."""

    async def dep(
        request: Request, principal: Principal = Depends(authenticate), c: Container = Depends(container)
    ) -> Principal:
        s = c.settings
        buckets = [
            Bucket("platform", s.rl_platform_per_min),
            Bucket(f"tenant:{principal.tenant_id}", int(request.state.tenant_rate_limit)),
            Bucket(f"principal:{principal.rate_key}", s.rl_principal_per_min),
        ]
        if endpoint:
            buckets.append(
                Bucket(f"endpoint:{endpoint}:{principal.rate_key}", per_min or s.rl_principal_per_min)
            )
        decision = await c.limiter.check(buckets)
        if not decision.allowed:
            raise RateLimited(headers={"Retry-After": str(decision.retry_after_s)})
        return principal

    return dep


async def login_rate_limit(request: Request, account_key: str, c: Container) -> None:
    acct = hashlib.sha256(account_key.lower().encode()).hexdigest()[:32]
    per_min = c.settings.rl_login_per_min
    decision = await c.limiter.check(
        [
            Bucket("platform", c.settings.rl_platform_per_min),
            Bucket(f"login:ip:{client_ip(request)}", per_min * 5),
            Bucket(f"login:acct:{acct}", per_min),
        ]
    )
    if not decision.allowed:
        raise RateLimited(headers={"Retry-After": str(decision.retry_after_s)})


async def platform_admin(request: Request, c: Container = Depends(container)) -> PlatformPrincipal:
    token = _bearer(request)
    if not token:
        raise Unauthorized()
    claims = c.tokens.verify(token, expected_typ="access", expected_aud="platform")
    if not await c.auth.platform_session_is_valid(claims):
        raise Unauthorized("Session revoked.")
    return PlatformPrincipal(claims.sub, claims.sid)


def audit_ctx(request: Request, principal: Principal) -> AuditContext:
    return AuditContext(principal.kind, principal.actor_id, request_id_var.get(), client_ip(request))


def platform_audit_ctx(request: Request, admin: PlatformPrincipal) -> AuditContext:
    return AuditContext("platform", admin.admin_id, request_id_var.get(), client_ip(request))
