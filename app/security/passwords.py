"""Argon2id password hashing.

Hashing is CPU/memory heavy, so it runs in a worker thread to keep the event loop responsive.
"""

from __future__ import annotations

import asyncio
from functools import lru_cache

from argon2 import PasswordHasher, Type
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

from app.core.config import get_settings

MIN_PASSWORD_LENGTH = 12
MAX_PASSWORD_LENGTH = 256  # bounds hashing cost; prevents long-password DoS


@lru_cache(maxsize=1)
def _hasher() -> PasswordHasher:
    s = get_settings()
    return PasswordHasher(
        time_cost=s.argon2_time_cost,
        memory_cost=s.argon2_memory_kib,
        parallelism=s.argon2_parallelism,
        hash_len=32,
        salt_len=16,
        type=Type.ID,
    )


def validate_password_policy(password: str) -> None:
    if not MIN_PASSWORD_LENGTH <= len(password) <= MAX_PASSWORD_LENGTH:
        raise ValueError(f"password must be {MIN_PASSWORD_LENGTH}-{MAX_PASSWORD_LENGTH} characters")
    if password.strip() != password or len(set(password)) < 5:
        raise ValueError("password is too weak")


def hash_password_sync(password: str) -> str:
    return _hasher().hash(password)


async def hash_password(password: str) -> str:
    return await asyncio.to_thread(hash_password_sync, password)


def _verify_sync(stored_hash: str, password: str) -> tuple[bool, bool]:
    h = _hasher()
    try:
        h.verify(stored_hash, password)
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False, False
    return True, h.check_needs_rehash(stored_hash)


async def verify_password(stored_hash: str, password: str) -> tuple[bool, bool]:
    """Return (valid, needs_rehash)."""
    if len(password) > MAX_PASSWORD_LENGTH:
        return False, False
    return await asyncio.to_thread(_verify_sync, stored_hash, password)


@lru_cache(maxsize=1)
def _dummy_hash() -> str:
    return hash_password_sync("dummy-password-for-timing-equalisation")


async def burn_verification_time(password: str) -> None:
    """Spend the same time as a real verify so unknown accounts are not distinguishable by latency."""
    await verify_password(_dummy_hash(), password[:MAX_PASSWORD_LENGTH])
