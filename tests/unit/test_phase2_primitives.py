from __future__ import annotations

import base64
import json
import os
import time
from decimal import Decimal
from typing import Any

import httpx
import pytest

from app.integrations.http import (
    OutboundBlocked,
    OutboundError,
    OutboundPolicy,
    SafeHttpClient,
    is_public_address,
)
from app.integrations.payments import (
    StripeProvider,
    WebhookVerificationError,
    from_minor,
    sign_payload,
    to_minor,
    verify_signed_payload,
)
from app.security import totp
from app.security.crypto import AwsKmsProvider, CryptoError, Envelope, LocalKeyProvider, VaultTransitProvider

CTX = {"tenant": "t1", "purpose": "payment_provider", "id": "row1"}


# -- envelope encryption ----------------------------------------------------------------------------


@pytest.fixture
def env() -> Envelope:
    return Envelope(LocalKeyProvider(os.urandom(32)))


async def test_envelope_roundtrip_and_fresh_dek(env: Envelope) -> None:
    a = await env.encrypt(b"sk_live_secret", CTX)
    b = await env.encrypt(b"sk_live_secret", CTX)
    assert a != b  # fresh DEK and nonce each time
    assert b"sk_live_secret" not in a.encode()
    assert await env.decrypt(a, CTX) == b"sk_live_secret"


async def test_envelope_is_bound_to_context(env: Envelope) -> None:
    blob = await env.encrypt(b"secret", CTX)
    for other in ({**CTX, "tenant": "t2"}, {**CTX, "id": "row2"}, {**CTX, "purpose": "map_provider"}):
        with pytest.raises(CryptoError):
            await Envelope(env.provider).decrypt(blob, other)  # new instance: no DEK cache


async def test_envelope_detects_tampering(env: Envelope) -> None:
    blob = json.loads(await env.encrypt(b"secret", CTX))
    ct = bytearray(base64.b64decode(blob["c"]))
    ct[0] ^= 1
    blob["c"] = base64.b64encode(bytes(ct)).decode()
    with pytest.raises(CryptoError):
        await env.decrypt(json.dumps(blob), CTX)
    with pytest.raises(CryptoError):
        await env.decrypt("not json", CTX)


async def test_envelope_rejects_other_kek_and_rewraps(env: Envelope) -> None:
    blob = await env.encrypt(b"secret", CTX)
    other = Envelope(LocalKeyProvider(os.urandom(32)))
    with pytest.raises(CryptoError):
        await other.decrypt(blob, CTX)
    rewrapped = await env.rewrap(blob, CTX)
    assert rewrapped != blob and await env.decrypt(rewrapped, CTX) == b"secret"


async def test_vault_transit_provider() -> None:
    dek = os.urandom(32)
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req.url.path)
        assert req.headers["X-Vault-Token"] == "s.token"
        if req.url.path.endswith("/datakey/plaintext/tc"):
            return httpx.Response(
                200,
                json={"data": {"plaintext": base64.b64encode(dek).decode(), "ciphertext": "vault:v1:abc"}},
            )
        assert json.loads(req.content) == {"ciphertext": "vault:v1:abc"}
        return httpx.Response(200, json={"data": {"plaintext": base64.b64encode(dek).decode()}})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    e = Envelope(
        VaultTransitProvider("https://vault.internal:8200", "s.token", "tc", client=client), dek_cache_ttl_s=0
    )
    blob = await e.encrypt(b"hello", CTX)
    assert await e.decrypt(blob, CTX) == b"hello"
    assert calls == ["/v1/transit/datakey/plaintext/tc", "/v1/transit/decrypt/tc"]


async def test_aws_kms_provider_passes_encryption_context() -> None:
    dek = os.urandom(32)
    seen: dict[str, Any] = {}

    class FakeKms:
        def generate_data_key(self, **kw: Any) -> dict[str, Any]:
            seen["gen"] = kw
            return {"Plaintext": dek, "CiphertextBlob": b"wrapped", "KeyId": "arn:key/1"}

        def decrypt(self, **kw: Any) -> dict[str, Any]:
            seen["dec"] = kw
            return {"Plaintext": dek}

    e = Envelope(AwsKmsProvider("alias/tc", "ap-southeast-1", client=FakeKms()))
    blob = await e.encrypt(b"x", CTX)
    assert await e.decrypt(blob, CTX) == b"x"
    assert seen["gen"]["EncryptionContext"] == CTX and seen["dec"]["EncryptionContext"] == CTX
    assert seen["dec"]["KeyId"] == "arn:key/1"


# -- TOTP ---------------------------------------------------------------------------------------------

RFC_SECRET = base64.b32encode(b"12345678901234567890").decode()


@pytest.mark.parametrize(("t", "expected"), [(59, "287082"), (1111111109, "081804"), (1234567890, "005924")])
def test_totp_rfc6238_vectors(t: int, expected: str) -> None:
    assert totp.code_at(RFC_SECRET, t // 30) == expected


def test_totp_rejects_replay_and_bad_input() -> None:
    now = 1_700_000_000.0
    step = totp.current_step(now)
    code = totp.code_at(RFC_SECRET, step)
    assert totp.verify(RFC_SECRET, code, last_used_step=None, now=now) == step
    assert totp.verify(RFC_SECRET, code, last_used_step=step, now=now) is None  # replay
    assert totp.verify(RFC_SECRET, "12345", last_used_step=None, now=now) is None
    assert totp.verify(RFC_SECRET, "abcdef", last_used_step=None, now=now) is None
    assert totp.verify(RFC_SECRET, totp.code_at(RFC_SECRET, step + 5), last_used_step=None, now=now) is None


# -- SSRF guard ---------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("ip", "public"),
    [
        ("8.8.8.8", True),
        ("2606:4700:4700::1111", True),
        ("127.0.0.1", False),
        ("10.1.2.3", False),
        ("172.16.0.1", False),
        ("192.168.1.1", False),
        ("169.254.169.254", False),
        ("100.64.0.1", False),
        ("0.0.0.0", False),
        ("::1", False),
        ("::ffff:10.0.0.1", False),
        ("fd00::1", False),
        ("fe80::1", False),
        ("224.0.0.1", False),
        ("2002:0a00:0001::", False),
    ],
)
def test_public_address_classification(ip: str, public: bool) -> None:
    assert is_public_address(ip) is public


POLICY = OutboundPolicy(frozenset({"api.example.com"}))


def _client(handler: Any, addrs: list[str]) -> SafeHttpClient:
    async def resolver(host: str, port: int) -> list[str]:
        return addrs

    return SafeHttpClient(transport=httpx.MockTransport(handler), resolver=resolver, max_response_bytes=1000)


async def test_outbound_policy_blocks_before_sending() -> None:
    sent: list[httpx.Request] = []
    c = _client(lambda r: sent.append(r) or httpx.Response(200), ["93.184.216.34"])
    for url in (
        "http://api.example.com/x",
        "https://evil.example.com/x",
        "https://api.example.com:8443/x",
        "https://user:pw@api.example.com/x",
        "https://169.254.169.254/latest/meta-data",
    ):
        with pytest.raises(OutboundBlocked):
            await c.request("GET", url, policy=POLICY)
    rebinding = _client(lambda r: sent.append(r) or httpx.Response(200), ["93.184.216.34", "10.0.0.5"])
    with pytest.raises(OutboundBlocked):
        await rebinding.request("GET", "https://api.example.com/x", policy=POLICY)
    assert sent == []


async def test_outbound_pins_validated_ip_and_keeps_host() -> None:
    seen: list[httpx.Request] = []

    def handler(r: httpx.Request) -> httpx.Response:
        seen.append(r)
        return httpx.Response(200, json={"ok": True})

    c = _client(handler, ["93.184.216.34"])
    r = await c.request("GET", "https://api.example.com/v1/x", policy=POLICY)
    assert r.json() == {"ok": True}
    assert seen[0].url.host == "93.184.216.34"
    assert seen[0].headers["host"] == "api.example.com"
    assert seen[0].extensions["sni_hostname"] == "api.example.com"


async def test_outbound_refuses_redirects_and_large_bodies() -> None:
    c = _client(lambda r: httpx.Response(302, headers={"location": "http://127.0.0.1/"}), ["93.184.216.34"])
    with pytest.raises(OutboundError):
        await c.request("GET", "https://api.example.com/x", policy=POLICY)
    big = _client(lambda r: httpx.Response(200, content=b"x" * 5000), ["93.184.216.34"])
    with pytest.raises(OutboundError):
        await big.request("GET", "https://api.example.com/x", policy=POLICY)


# -- payments -----------------------------------------------------------------------------------------


def test_minor_units() -> None:
    assert to_minor(Decimal("12.34"), "USD") == 1234
    assert to_minor(Decimal("1200"), "JPY") == 1200
    assert from_minor(1234, "usd") == Decimal("12.34")


def test_webhook_signature() -> None:
    body = b'{"id":"evt_1"}'
    hdr = sign_payload(body, "whsec_test_secret")
    verify_signed_payload(body, hdr, "whsec_test_secret")
    for bad_body, bad_hdr, secret in (
        (b'{"id":"evt_2"}', hdr, "whsec_test_secret"),  # tampered body
        (body, hdr, "whsec_other"),  # wrong secret
        (
            body,
            sign_payload(body, "whsec_test_secret", ts=int(time.time()) - 3600),
            "whsec_test_secret",
        ),  # stale
        (body, None, "whsec_test_secret"),
        (body, "garbage", "whsec_test_secret"),
    ):
        with pytest.raises(WebhookVerificationError):
            verify_signed_payload(bad_body, bad_hdr, secret)
    # Multiple v1 signatures (provider secret rotation): any match passes.
    ts = int(time.time())
    good = sign_payload(body, "whsec_test_secret", ts).split(",")[1]
    verify_signed_payload(body, f"t={ts},v1=deadbeef,{good}", "whsec_test_secret")


async def test_stripe_provider_request_shape() -> None:
    seen: list[httpx.Request] = []

    def handler(r: httpx.Request) -> httpx.Response:
        seen.append(r)
        return httpx.Response(
            200, json={"id": "pi_123", "status": "requires_payment_method", "client_secret": "cs"}
        )

    http = _client(handler, ["93.184.216.34"])
    sp = StripeProvider(http, {"api_key": "sk_test_abcdefghijkl", "webhook_secret": "whsec_abcdefghijkl"})
    res = await sp.create_payment(
        amount=Decimal("10.50"), currency="USD", idempotency_key="k1", metadata={"order_id": "o"}
    )
    assert (res.provider_ref, res.status, res.client_secret) == ("pi_123", "pending", "cs")
    req = seen[0]
    assert req.headers["host"] == "api.stripe.com"
    assert req.headers["idempotency-key"] == "k1"
    assert req.headers["authorization"] == "Bearer sk_test_abcdefghijkl"
    form = dict(x.split("=", 1) for x in req.content.decode().split("&"))
    assert form["amount"] == "1050" and form["currency"] == "usd"


# -- dedicated database adapters ---------------------------------------------------------------------

from app.db.adapters import ADAPTERS, ConnSpec, TargetNotAllowed, validate_target  # noqa: E402
from app.models import DbDialect, DbSslMode  # noqa: E402


def test_adapter_capabilities_never_assume_rls_outside_postgres() -> None:
    assert ADAPTERS[DbDialect.postgresql].capabilities.supports_rls is True
    assert ADAPTERS[DbDialect.mysql].capabilities.supports_rls is False
    assert ADAPTERS[DbDialect.oracle].capabilities.supports_rls is False
    assert not ADAPTERS[DbDialect.mysql].capabilities.supports_dynamic_crud


def test_adapter_urls_and_tls() -> None:
    spec = ConnSpec(
        DbDialect.mysql, "db.internal", 3306, "shop", "app", "p@ss/word", DbSslMode.verify_full, None
    )
    url = ADAPTERS[DbDialect.mysql].url(spec)
    assert url.drivername == "mysql+aiomysql" and url.password == "p@ss/word" and url.host == "db.internal"
    assert ADAPTERS[DbDialect.mysql].connect_args(spec, 5)["ssl"].check_hostname is True
    ora = ConnSpec(DbDialect.oracle, "ora.internal", 1521, "ORCLPDB1", "app", "x", DbSslMode.require, None)
    assert ADAPTERS[DbDialect.oracle].url(ora).query["service_name"] == "ORCLPDB1"
    assert ADAPTERS[DbDialect.oracle].connect_args(ora, 5)["protocol"] == "tcps"
    pg = ConnSpec(DbDialect.postgresql, "pg", 5432, "d", "u", "p", DbSslMode.disable, None)
    assert ADAPTERS[DbDialect.postgresql].connect_args(pg, 5)["ssl"] is False


async def test_database_target_validation() -> None:
    assert await validate_target("127.0.0.1", 5432, ["127.0.0.0/8"]) == ["127.0.0.1"]
    for host, nets in (
        ("127.0.0.1", []),
        ("127.0.0.1", ["10.0.0.0/8"]),
        ("169.254.169.254", ["0.0.0.0/0"]),
        ("no-such-host.invalid", ["0.0.0.0/0"]),
    ):
        with pytest.raises(TargetNotAllowed):
            await validate_target(host, 5432, nets)
