"""JWT issuance and validation.

* HS256 only (algorithm allowlist prevents ``alg=none`` / algorithm-confusion attacks).
* ``kid`` header selects the verification key so keys can be rotated without downtime.
* Required claims: iss, aud, sub, exp, iat, nbf, jti, typ, sid; tenant tokens also carry ``tid``.
* Tenant and platform tokens use different audiences, so one can never be accepted as the other.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Any, Literal

import jwt

from app.core.config import Settings
from app.core.errors import Unauthorized

ALGORITHM = "HS256"
TokenType = Literal["access", "refresh"]
Audience = Literal["tenant", "platform"]


@dataclass(frozen=True, slots=True)
class TokenClaims:
    sub: uuid.UUID
    sid: uuid.UUID
    jti: uuid.UUID
    typ: TokenType
    aud: Audience
    tid: uuid.UUID | None
    exp: int
    iat: int
    mfa: bool = False  # platform tokens: whether the session passed TOTP


class TokenService:
    def __init__(self, settings: Settings) -> None:
        self._s = settings
        self._keys = {kid: v.get_secret_value() for kid, v in settings.jwt_keys.items()}
        self._aud = {"tenant": settings.jwt_audience_tenant, "platform": settings.jwt_audience_platform}

    def issue(
        self,
        *,
        typ: TokenType,
        aud: Audience,
        sub: uuid.UUID,
        sid: uuid.UUID,
        tid: uuid.UUID | None,
        ttl_s: int,
        jti: uuid.UUID | None = None,
        mfa: bool = False,
    ) -> tuple[str, TokenClaims]:
        if aud == "tenant" and tid is None:
            raise ValueError("tenant tokens require tid")
        if aud == "platform" and tid is not None:
            raise ValueError("platform tokens must not carry tid")
        now = int(time.time())
        claims = TokenClaims(
            sub=sub,
            sid=sid,
            jti=jti or uuid.uuid4(),
            typ=typ,
            aud=aud,
            tid=tid,
            exp=now + ttl_s,
            iat=now,
            mfa=mfa,
        )
        payload: dict[str, Any] = {
            "iss": self._s.jwt_issuer,
            "aud": self._aud[aud],
            "sub": str(sub),
            "sid": str(sid),
            "jti": str(claims.jti),
            "typ": typ,
            "iat": now,
            "nbf": now,
            "exp": claims.exp,
        }
        if tid is not None:
            payload["tid"] = str(tid)
        if aud == "platform":
            payload["mfa"] = mfa
        kid = self._s.jwt_active_kid
        token = jwt.encode(payload, self._keys[kid], algorithm=ALGORITHM, headers={"kid": kid})
        return token, claims

    def verify(self, token: str, *, expected_typ: TokenType, expected_aud: Audience) -> TokenClaims:
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError as e:
            raise Unauthorized("Invalid token.") from e
        kid = header.get("kid")
        if header.get("alg") != ALGORITHM or not isinstance(kid, str) or kid not in self._keys:
            raise Unauthorized("Invalid token.")
        try:
            payload = jwt.decode(
                token,
                self._keys[kid],
                algorithms=[ALGORITHM],
                audience=self._aud[expected_aud],
                issuer=self._s.jwt_issuer,
                leeway=self._s.jwt_leeway_s,
                options={"require": ["iss", "aud", "sub", "exp", "iat", "nbf", "jti", "typ", "sid"]},
            )
        except jwt.ExpiredSignatureError as e:
            raise Unauthorized("Token expired.") from e
        except jwt.PyJWTError as e:
            raise Unauthorized("Invalid token.") from e
        if payload.get("typ") != expected_typ:
            raise Unauthorized("Invalid token.")
        try:
            tid_raw = payload.get("tid")
            tid = uuid.UUID(tid_raw) if tid_raw is not None else None
            claims = TokenClaims(
                sub=uuid.UUID(payload["sub"]),
                sid=uuid.UUID(payload["sid"]),
                jti=uuid.UUID(payload["jti"]),
                typ=expected_typ,
                aud=expected_aud,
                tid=tid,
                exp=int(payload["exp"]),
                iat=int(payload["iat"]),
                mfa=payload.get("mfa") is True,
            )
        except (ValueError, TypeError, KeyError) as e:
            raise Unauthorized("Invalid token.") from e
        if (expected_aud == "tenant") != (claims.tid is not None):
            raise Unauthorized("Invalid token.")
        return claims
