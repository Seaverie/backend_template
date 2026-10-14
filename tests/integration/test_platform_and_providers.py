"""Platform MFA, API key pepper rotation, deployment split, and provider integrations with outbound
HTTP intercepted (Google Maps, Stripe)."""

from __future__ import annotations

import json
import time
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from typing import Any

import httpx
import pytest
from httpx import AsyncClient
from pydantic import SecretStr
from sqlalchemy import text

from app.container import Container
from app.core.config import get_settings
from app.integrations.http import SafeHttpClient
from app.integrations.payments import sign_payload
from app.models import PlatformAdmin
from app.security import totp
from app.security.passwords import hash_password
from tests.integration.conftest import PASSWORD, P, TenantCtx, auth, make_client

NewTenant = Callable[..., Awaitable[TenantCtx]]


# -- platform MFA ---------------------------------------------------------------------------------------


@pytest.fixture(scope="module")
async def mfa_client() -> AsyncIterator[tuple[AsyncClient, Container]]:
    c = Container.build(get_settings().model_copy(update={"platform_mfa_required": True}))
    async with make_client(c) as ac:
        yield ac, c
    await c.close()


async def test_platform_mfa_enrollment_and_replay_protection(mfa_client: tuple[AsyncClient, Container]) -> None:
    client, c = mfa_client
    email = f"mfa-{uuid.uuid4().hex[:8]}@example.com"
    async with c.db.ddl_session() as s:
        await s.execute(text("SELECT set_config('app.is_platform', 'on', true)"))
        s.add(PlatformAdmin(email=email, password_hash=await hash_password(PASSWORD)))
        await s.commit()
    login = {"email": email, "password": PASSWORD}
    tok = (await client.post(f"{P}/platform/auth/login", json=login)).json()["access_token"]
    # Without MFA the session can only enroll.
    r = await client.get(f"{P}/platform/tenants", headers=auth(tok))
    assert r.status_code == 403 and "MFA" in r.json()["error"]["message"]
    setup = (await client.post(f"{P}/platform/auth/mfa/setup", headers=auth(tok))).json()
    secret = setup["secret"]
    assert setup["otpauth_uri"].startswith("otpauth://totp/")
    async with c.db.ddl_session() as s:
        await s.execute(text("SELECT set_config('app.is_platform', 'on', true)"))
        stored = (await s.execute(text("SELECT mfa_secret_enc FROM platform_admins WHERE email = :e"), {"e": email})).scalar_one()
    assert secret not in stored  # encrypted at rest
    assert (await client.post(f"{P}/platform/auth/mfa/confirm", headers=auth(tok), json={"code": "000000"})).status_code == 401
    step = totp.current_step()
    r = await client.post(f"{P}/platform/auth/mfa/confirm", headers=auth(tok), json={"code": totp.code_at(secret, step)})
    assert r.status_code == 200
    mfa_tok = r.json()["access_token"]
    assert (await client.get(f"{P}/platform/tenants", headers=auth(mfa_tok))).status_code == 200
    # Password alone is no longer enough; the code used at confirmation cannot be replayed.
    assert (await client.post(f"{P}/platform/auth/login", json=login)).status_code == 401
    assert (await client.post(f"{P}/platform/auth/login", json={**login, "otp": totp.code_at(secret, step)})).status_code == 401
    r = await client.post(f"{P}/platform/auth/login", json={**login, "otp": totp.code_at(secret, step + 1)})
    assert r.status_code == 200
    assert (await client.get(f"{P}/platform/tenants", headers=auth(r.json()["access_token"]))).status_code == 200
    assert (await client.post(f"{P}/platform/auth/mfa/setup", headers=auth(r.json()["access_token"]))).status_code == 409


# -- API key pepper rotation ------------------------------------------------------------------------------


async def test_pepper_rotation_keeps_existing_keys_valid(client: AsyncClient, new_tenant: NewTenant) -> None:
    t = await new_tenant()
    old_key = (await client.post(f"{P}/api-keys", headers=t.h, json={"name": "old", "scopes": ["users:read"]})).json()["api_key"]
    rotated = get_settings().model_copy(
        update={"api_key_peppers": {"p2": SecretStr("dev-only-second-pepper-0123456789abcdef")}, "api_key_active_pepper": "p2",
                "redis_key_prefix": f"pep-{uuid.uuid4().hex[:6]}"}
    )
    c2 = Container.build(rotated)
    try:
        async with make_client(c2) as client2:
            assert (await client2.get(f"{P}/auth/me", headers={"X-API-Key": old_key})).status_code == 200
            new = await client2.post(f"{P}/api-keys", headers=t.h, json={"name": "new", "scopes": ["users:read"]})
            new_key = new.json()["api_key"]
            assert (await client2.get(f"{P}/auth/me", headers={"X-API-Key": new_key})).status_code == 200
        async with c2.db.tenant_session(uuid.UUID(t.id)) as s:
            peppers = dict((await s.execute(text("SELECT name, pepper_id FROM tenant_api_keys"))).all())
        assert peppers == {"old": "p1", "new": "p2"}
    finally:
        await c2.close()
    # A node that does not know p2 rejects the new key instead of failing open.
    assert (await client.get(f"{P}/auth/me", headers={"X-API-Key": new_key})).status_code == 401


async def test_request_serving_deployment_has_no_platform_routes() -> None:
    s = get_settings().model_copy(update={"enable_platform_routes": False, "ddl_database_url": None})
    c = Container.build(s)
    try:
        assert c.db.ddl_engine is None
        async with make_client(c) as ac:
            assert (await ac.post(f"{P}/platform/auth/login", json={"email": "a@b.co", "password": "x"})).status_code == 404
            assert (await ac.post(f"{P}/system/tables", json={})).status_code == 404
            assert (await ac.get("/health")).status_code == 200
    finally:
        await c.close()


# -- providers with intercepted outbound HTTP -----------------------------------------------------------


class Recorder:
    def __init__(self, responder: Callable[[httpx.Request], httpx.Response]) -> None:
        self.requests: list[httpx.Request] = []
        self._responder = responder

    def __call__(self, req: httpx.Request) -> httpx.Response:
        self.requests.append(req)
        return self._responder(req)


def intercept(c: Container, responder: Callable[[httpx.Request], httpx.Response]) -> Recorder:
    rec = Recorder(responder)

    async def resolver(host: str, port: int) -> list[str]:
        return ["93.184.216.34"]  # a public address, so the SSRF guard lets it through

    c.providers.http = SafeHttpClient(transport=httpx.MockTransport(rec), resolver=resolver)
    return rec


@pytest.fixture
def restore_http(container: Container) -> Any:
    original = container.providers.http
    yield
    container.providers.http = original


GOOGLE_KEY = "AIzaSyTestKey_0123456789abcdefghij"


async def test_geocoding_via_tenant_provider_with_cache(
    client: AsyncClient, container: Container, new_tenant: NewTenant, restore_http: Any
) -> None:
    rec = intercept(container, lambda r: httpx.Response(200, json={
        "status": "OK",
        "results": [{"formatted_address": "Phnom Penh, Cambodia", "geometry": {"location": {"lat": 11.5564, "lng": 104.9282}}}],
    }))
    t = await new_tenant()
    assert (await client.get(f"{P}/maps/geocode", headers=t.h, params={"q": "Phnom Penh"})).status_code == 404  # none configured
    r = await client.post(f"{P}/map-providers", headers=t.h, json={
        "name": "google", "provider": "google", "secrets": {"api_key": GOOGLE_KEY}, "is_default": True})
    assert r.status_code == 201 and GOOGLE_KEY not in r.text
    for _ in range(2):
        r = await client.get(f"{P}/maps/geocode", headers=t.h, params={"q": "Phnom Penh"})
        assert r.status_code == 200
        assert r.json()["items"] == [{"lat": 11.5564, "lng": 104.9282, "label": "Phnom Penh, Cambodia"}]
    assert len(rec.requests) == 1  # second answer served from the per-tenant cache
    req = rec.requests[0]
    assert req.headers["host"] == "maps.googleapis.com" and req.url.params["key"] == GOOGLE_KEY
    # Another tenant shares neither the provider nor the cache.
    other = await new_tenant()
    assert (await client.get(f"{P}/maps/geocode", headers=other.h, params={"q": "Phnom Penh"})).status_code == 404


async def test_provider_failure_is_reported_generically(
    client: AsyncClient, container: Container, new_tenant: NewTenant, restore_http: Any
) -> None:
    intercept(container, lambda r: httpx.Response(500, text="internal stack trace with key " + GOOGLE_KEY))
    t = await new_tenant()
    await client.post(f"{P}/map-providers", headers=t.h, json={
        "name": "g", "provider": "google", "secrets": {"api_key": GOOGLE_KEY}, "is_default": True})
    r = await client.get(f"{P}/maps/geocode", headers=t.h, params={"q": "anywhere"})
    assert r.status_code == 502 and GOOGLE_KEY not in r.text and "stack" not in r.text


async def test_stripe_payment_and_webhook(
    client: AsyncClient, container: Container, new_tenant: NewTenant, restore_http: Any
) -> None:
    rec = intercept(container, lambda r: httpx.Response(200, json={
        "id": "pi_test_123", "status": "requires_payment_method", "client_secret": "pi_test_123_secret"}))
    t = await new_tenant()
    whsec = "whsec_testsecret0123456789"
    gw = (await client.post(f"{P}/payment-gateways", headers=t.h, json={
        "name": "stripe", "provider": "stripe", "is_default": True,
        "secrets": {"api_key": "sk_test_0123456789abcdef", "webhook_secret": whsec}})).json()["id"]
    wh = (await client.post(f"{P}/warehouses", headers=t.h, json={"code": "W", "name": "W"})).json()["id"]
    b = (await client.post(f"{P}/warehouses/{wh}/bins", headers=t.h, json={"code": "B"})).json()["id"]
    v = (await client.post(f"{P}/products", headers=t.h, json={
        "name": "Lamp", "status": "active",
        "variants": [{"sku": "LAMP", "name": "Lamp", "price": "15.50", "currency": "USD"}]})).json()["variants"][0]["id"]
    await client.post(f"{P}/stock/adjustments", headers=t.h, json={"batch_key": "seed-0001", "lines": [{"variant_id": v, "bin_id": b, "delta": 3}]})
    oid = (await client.post(f"{P}/orders", headers={**t.h, "Idempotency-Key": "chk-00000001"},
                             json={"items": [{"variant_id": v, "qty": 2}]})).json()["id"]
    r = await client.post(f"{P}/orders/{oid}/payments", headers={**t.h, "Idempotency-Key": "pay-00000001"}, json={})
    assert r.status_code == 201, r.text
    assert r.json()["provider_ref"] == "pi_test_123" and r.json()["client_secret"] == "pi_test_123_secret"
    sent = rec.requests[0]
    assert sent.headers["host"] == "api.stripe.com"
    assert sent.headers["idempotency-key"] == f"tc:{t.id}:{oid}:pay-00000001"
    assert "amount=3100" in sent.content.decode()
    event = {"id": "evt_1", "type": "payment_intent.succeeded", "data": {"object": {"id": "pi_test_123"}}}
    body = json.dumps(event).encode()
    stale = sign_payload(body, whsec, ts=int(time.time()) - 1000)
    assert (await client.post(f"{P}/webhooks/payments/{gw}", content=body, headers={"Stripe-Signature": stale})).status_code == 422
    r = await client.post(f"{P}/webhooks/payments/{gw}", content=body, headers={"Stripe-Signature": sign_payload(body, whsec)})
    assert r.status_code == 200 and r.json()["duplicate"] is False
    assert (await client.get(f"{P}/orders/{oid}", headers=t.h)).json()["amount_paid"] == "31.00"
