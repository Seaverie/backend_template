"""Tenant API key format and digests.

Format: ``tck_<key_id>_<secret>``
  * key_id  -- 20 chars base32 (100 bits), public, used as the lookup index.
  * secret  -- 43 chars base64url (256 bits), never stored.

The stored value is HMAC-SHA256(pepper, key_id || secret). Because the secret has 256 bits of entropy a
slow KDF adds nothing; the pepper (kept in the secret manager, not the database) means a database dump
alone is not enough to brute-force or verify keys offline.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets
from dataclasses import dataclass

PREFIX = "tck"
_KEY_RE = re.compile(r"^tck_([a-z2-7]{20})_([A-Za-z0-9_-]{43})$")


@dataclass(frozen=True, slots=True)
class ParsedKey:
    key_id: str
    secret: str


def generate() -> tuple[str, ParsedKey]:
    key_id = base64.b32encode(secrets.token_bytes(15)).decode().lower().rstrip("=")[:20]
    secret = secrets.token_urlsafe(32)
    if len(secret) != 43:  # token_urlsafe(32) is always 43 chars; guard against stdlib changes
        raise RuntimeError("unexpected secret length")
    return f"{PREFIX}_{key_id}_{secret}", ParsedKey(key_id, secret)


def parse(raw: str) -> ParsedKey | None:
    m = _KEY_RE.fullmatch(raw.strip())
    if not m:
        return None
    return ParsedKey(m.group(1), m.group(2))


def digest(pepper: bytes, key: ParsedKey) -> bytes:
    return hmac.new(pepper, f"{key.key_id}:{key.secret}".encode(), hashlib.sha256).digest()


def verify(pepper: bytes, key: ParsedKey, stored_digest: bytes) -> bool:
    return hmac.compare_digest(digest(pepper, key), stored_digest)
