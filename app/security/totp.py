"""RFC 6238 TOTP (SHA-1, 6 digits, 30 s), compatible with standard authenticator apps."""

from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import struct
import time
from urllib.parse import quote

STEP_S = 30
DIGITS = 6


def new_secret() -> str:
    return base64.b32encode(secrets.token_bytes(20)).decode().rstrip("=")


def _key(secret_b32: str) -> bytes:
    pad = "=" * (-len(secret_b32) % 8)
    return base64.b32decode(secret_b32.upper() + pad)


def code_at(secret_b32: str, step: int) -> str:
    digest = hmac.new(_key(secret_b32), struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    value = struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF
    return str(value % 10**DIGITS).zfill(DIGITS)


def current_step(now: float | None = None) -> int:
    return int((time.time() if now is None else now) // STEP_S)


def verify(
    secret_b32: str, code: str, *, last_used_step: int | None, window: int = 1, now: float | None = None
) -> int | None:
    """Return the matched time step, or None. Steps at or before ``last_used_step`` are rejected so a
    code can never be replayed, not even within its validity window."""
    if not (len(code) == DIGITS and code.isdigit()):
        return None
    step = current_step(now)
    for s in range(step - window, step + window + 1):
        if last_used_step is not None and s <= last_used_step:
            continue
        if hmac.compare_digest(code_at(secret_b32, s), code):
            return s
    return None


def provisioning_uri(secret_b32: str, account: str, issuer: str) -> str:
    label = quote(f"{issuer}:{account}")
    return f"otpauth://totp/{label}?secret={secret_b32}&issuer={quote(issuer)}&algorithm=SHA1&digits={DIGITS}&period={STEP_S}"
