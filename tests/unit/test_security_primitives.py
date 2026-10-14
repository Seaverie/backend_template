from __future__ import annotations

import time
import uuid

import jwt
import pytest

from app.core.config import get_settings
from app.core.errors import Unauthorized
from app.core.logging import REDACTED, redact_value
from app.security import api_keys as keyfmt
from app.security.identifiers import InvalidIdentifier, quote, validate_identifier
from app.security.passwords import hash_password, validate_password_policy, verify_password
from app.security.tokens import TokenService

# -- identifiers ------------------------------------------------------------------------------------


@pytest.mark.parametrize("name", ["products", "order_items", "a", "x1_y2", "a" * 63])
def test_valid_identifiers(name: str) -> None:
    assert validate_identifier(name) == name


@pytest.mark.parametrize(
    "name",
    [
        "",
        "1abc",
        "Products",
        "a-b",
        "a b",
        'a"; DROP TABLE x; --',
        "a;b",
        "a" * 64,
        "select",
        "user",
        "pg_catalog",
        "a__b",
        "tenant_id",
        "id",
        "created_by",
        "ctid",
        "naïve",
        "a\x00b",
        "_x",
    ],
)
def test_invalid_identifiers(name: str) -> None:
    with pytest.raises(InvalidIdentifier):
        validate_identifier(name)


def test_quote_validates_before_quoting() -> None:
    assert quote("tenant_data") == '"tenant_data"'
    with pytest.raises(InvalidIdentifier):
        quote('x"; DROP SCHEMA public; --')


# -- passwords --------------------------------------------------------------------------------------


async def test_argon2id_roundtrip() -> None:
    h = await hash_password("correct horse battery staple")
    assert h.startswith("$argon2id$")
    assert await verify_password(h, "correct horse battery staple") == (True, False)
    assert (await verify_password(h, "wrong horse battery staple"))[0] is False
    assert (await verify_password("not-a-hash", "x"))[0] is False
    assert (await verify_password(h, "x" * 10_000))[0] is False  # bounded length


@pytest.mark.parametrize("pw", ["short", " padded-password ", "aaaaaaaaaaaaaaaa", "x" * 300])
def test_password_policy_rejects(pw: str) -> None:
    with pytest.raises(ValueError):
        validate_password_policy(pw)


# -- JWT ----------------------------------------------------------------------------------------------


@pytest.fixture
def tokens() -> TokenService:
    return TokenService(get_settings())


def _ids() -> tuple[uuid.UUID, uuid.UUID, uuid.UUID]:
    return uuid.uuid4(), uuid.uuid4(), uuid.uuid4()


def test_token_roundtrip(tokens: TokenService) -> None:
    sub, sid, tid = _ids()
    tok, _ = tokens.issue(typ="access", aud="tenant", sub=sub, sid=sid, tid=tid, ttl_s=60)
    c = tokens.verify(tok, expected_typ="access", expected_aud="tenant")
    assert (c.sub, c.sid, c.tid) == (sub, sid, tid)


def test_refresh_token_not_accepted_as_access(tokens: TokenService) -> None:
    sub, sid, tid = _ids()
    tok, _ = tokens.issue(typ="refresh", aud="tenant", sub=sub, sid=sid, tid=tid, ttl_s=60)
    with pytest.raises(Unauthorized):
        tokens.verify(tok, expected_typ="access", expected_aud="tenant")


def test_platform_and_tenant_audiences_are_disjoint(tokens: TokenService) -> None:
    sub, sid, tid = _ids()
    tenant_tok, _ = tokens.issue(typ="access", aud="tenant", sub=sub, sid=sid, tid=tid, ttl_s=60)
    platform_tok, _ = tokens.issue(typ="access", aud="platform", sub=sub, sid=sid, tid=None, ttl_s=60)
    with pytest.raises(Unauthorized):
        tokens.verify(tenant_tok, expected_typ="access", expected_aud="platform")
    with pytest.raises(Unauthorized):
        tokens.verify(platform_tok, expected_typ="access", expected_aud="tenant")


def test_expired_token_rejected(tokens: TokenService) -> None:
    s = get_settings()
    now = int(time.time())
    payload = {
        "iss": s.jwt_issuer,
        "aud": s.jwt_audience_tenant,
        "sub": str(uuid.uuid4()),
        "sid": str(uuid.uuid4()),
        "jti": str(uuid.uuid4()),
        "typ": "access",
        "tid": str(uuid.uuid4()),
        "iat": now - 1000,
        "nbf": now - 1000,
        "exp": now - 500,
    }
    tok = jwt.encode(payload, s.jwt_keys["k1"].get_secret_value(), algorithm="HS256", headers={"kid": "k1"})
    with pytest.raises(Unauthorized, match="expired"):
        tokens.verify(tok, expected_typ="access", expected_aud="tenant")


def _claims(tid: bool = True) -> dict[str, object]:
    s = get_settings()
    now = int(time.time())
    d: dict[str, object] = {
        "iss": s.jwt_issuer,
        "aud": s.jwt_audience_tenant,
        "sub": str(uuid.uuid4()),
        "sid": str(uuid.uuid4()),
        "jti": str(uuid.uuid4()),
        "typ": "access",
        "iat": now,
        "nbf": now,
        "exp": now + 60,
    }
    if tid:
        d["tid"] = str(uuid.uuid4())
    return d


def test_alg_none_rejected(tokens: TokenService) -> None:
    tok = jwt.encode(_claims(), key=None, algorithm="none", headers={"kid": "k1"})  # type: ignore[arg-type]
    with pytest.raises(Unauthorized):
        tokens.verify(tok, expected_typ="access", expected_aud="tenant")


def test_wrong_key_rejected(tokens: TokenService) -> None:
    tok = jwt.encode(
        _claims(), "attacker-key-attacker-key-attacker-key!!", algorithm="HS256", headers={"kid": "k1"}
    )
    with pytest.raises(Unauthorized):
        tokens.verify(tok, expected_typ="access", expected_aud="tenant")


def test_unknown_kid_rejected(tokens: TokenService) -> None:
    s = get_settings()
    tok = jwt.encode(
        _claims(), s.jwt_keys["k1"].get_secret_value(), algorithm="HS256", headers={"kid": "nope"}
    )
    with pytest.raises(Unauthorized):
        tokens.verify(tok, expected_typ="access", expected_aud="tenant")


def test_old_kid_still_verifies_during_rotation(tokens: TokenService) -> None:
    s = get_settings()
    tok = jwt.encode(_claims(), s.jwt_keys["k0"].get_secret_value(), algorithm="HS256", headers={"kid": "k0"})
    assert tokens.verify(tok, expected_typ="access", expected_aud="tenant").tid is not None


def test_tenant_token_without_tid_rejected(tokens: TokenService) -> None:
    s = get_settings()
    tok = jwt.encode(
        _claims(tid=False), s.jwt_keys["k1"].get_secret_value(), algorithm="HS256", headers={"kid": "k1"}
    )
    with pytest.raises(Unauthorized):
        tokens.verify(tok, expected_typ="access", expected_aud="tenant")


def test_tampered_payload_rejected(tokens: TokenService) -> None:
    sub, sid, tid = _ids()
    tok, _ = tokens.issue(typ="access", aud="tenant", sub=sub, sid=sid, tid=tid, ttl_s=60)
    header, _payload, sig = tok.split(".")
    other, _ = tokens.issue(typ="access", aud="tenant", sub=sub, sid=sid, tid=uuid.uuid4(), ttl_s=60)
    forged = ".".join([header, other.split(".")[1], sig])
    with pytest.raises(Unauthorized):
        tokens.verify(forged, expected_typ="access", expected_aud="tenant")


# -- API key format -----------------------------------------------------------------------------------


def test_api_key_roundtrip() -> None:
    pepper = b"p" * 32
    raw, parsed = keyfmt.generate()
    assert keyfmt.parse(raw) == parsed
    d = keyfmt.digest(pepper, parsed)
    assert len(d) == 32
    assert keyfmt.verify(pepper, parsed, d)
    assert not keyfmt.verify(b"q" * 32, parsed, d)
    wrong = keyfmt.ParsedKey(parsed.key_id, parsed.secret[:-1] + ("A" if parsed.secret[-1] != "A" else "B"))
    assert not keyfmt.verify(pepper, wrong, d)


def test_api_keys_are_unique_and_high_entropy() -> None:
    keys = {keyfmt.generate()[0] for _ in range(500)}
    assert len(keys) == 500


@pytest.mark.parametrize(
    "raw",
    ["", "tck_", "tck_short_secret", "xyz_" + "a" * 20 + "_" + "b" * 43, "tck_" + "A" * 20 + "_" + "b" * 43],
)
def test_malformed_api_keys(raw: str) -> None:
    assert keyfmt.parse(raw) is None


# -- log redaction ------------------------------------------------------------------------------------


def test_redaction() -> None:
    raw, _ = keyfmt.generate()
    out = redact_value(
        {
            "password": "hunter2",
            "nested": {"Authorization": "Bearer abc"},
            "msg": f"key {raw} used",
            "dsn": "postgresql://user:pw@host/db",
            "url": "redis://admin:s3cret@cache:6379/0",
            "ok": "plain",
        }
    )
    assert out["password"] == REDACTED
    assert out["nested"]["Authorization"] == REDACTED
    assert raw not in out["msg"]
    assert out["dsn"] == REDACTED
    assert "s3cret" not in out["url"]
    assert out["ok"] == "plain"
