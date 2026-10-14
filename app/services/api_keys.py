"""Tenant API keys: creation, rotation, revocation, and cached validation.

Validation path
---------------
1. Parse ``tck_<key_id>_<secret>``; malformed keys are rejected without touching Redis or the DB.
2. Redis ``apikey:v1:<key_id>`` holds {tenant, row id, digest, scopes, creator, expiry, state}.
   Cache hit: HMAC-verify against the cached digest; no database round trip.
3. Cache miss: one SELECT under the narrow ``api_key_lookup`` RLS policy, then populate with SET NX.
4. Redis unreachable: database lookup on every request (correct but slower). Never fails open.

Revocation consistency
----------------------
Revoking commits ``revoked_at`` and then writes a *tombstone* to the cache key (unconditional SET).
Readers populate with SET NX, so a reader that loaded pre-revocation data races harmlessly: its NX
write loses to the tombstone. With Redis healthy, revocation applies to the next request. If the
tombstone write fails (Redis down), every node is already on the DB path, and if Redis returns with
an old entry still present, that entry expires within ``api_key_cache_ttl_s`` (default 60 s). That
TTL is the documented worst-case consistency window.
"""

from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import select, text

from app.cache.redis import Cache, safe
from app.core.config import Settings
from app.core.errors import Forbidden, NotFound, Unauthorized, ValidationFailed
from app.db.session import Database
from app.models import Action, TenantApiKey
from app.models.rbac import BUILTIN_RESOURCES
from app.security import api_keys as keyfmt
from app.services.audit import AuditContext, record
from app.services.authz import Principal

_SCOPE_RE = re.compile(
    r"^(data\.\*|data\.[a-z][a-z0-9_]{0,62}|"
    + "|".join(BUILTIN_RESOURCES)
    + r"):(create|read|update|delete|manage|\*)$"
)
_NEGATIVE_TTL_S = 30
MAX_ROTATION_GRACE_S = 86_400


@dataclass(frozen=True, slots=True)
class ApiKeyIdentity:
    row_id: uuid.UUID
    tenant_id: uuid.UUID
    creator_id: uuid.UUID
    scopes: frozenset[str]


def _now() -> datetime:
    return datetime.now(UTC)


def validate_scopes(scopes: list[str]) -> list[str]:
    if not 1 <= len(scopes) <= 50:
        raise ValidationFailed("between 1 and 50 scopes are required")
    bad = [s for s in scopes if not _SCOPE_RE.fullmatch(s)]
    if bad:
        raise ValidationFailed("invalid scope format", details={"invalid": bad[:10]})
    return sorted(set(scopes))


def _ensure_creator_holds(principal: Principal, scopes: list[str]) -> None:
    """A key may never carry more than its creator holds (no privilege escalation via keys)."""
    for s in scopes:
        res, _, act = s.partition(":")
        if (res == "data.*" or act == "*") and not principal.perms.is_admin:
            raise Forbidden("Wildcard scopes require tenant-admin privileges.")
        if act != "*" and res != "data.*" and principal.grant(res, Action(act)) is None:
            raise Forbidden(f"You cannot delegate scope {s!r}.")


class ApiKeyService:
    def __init__(self, settings: Settings, db: Database, cache: Cache) -> None:
        self._s = settings
        self._db = db
        self._cache = cache
        self._peppers = {pid: pep.encode() for pid, pep in settings.pepper_keyring.items()}
        self._active_pepper = settings.api_key_active_pepper

    # -- management -----------------------------------------------------------------------------

    async def create(
        self,
        principal: Principal,
        *,
        name: str,
        scopes: list[str],
        expires_in_days: int | None,
        ctx: AuditContext,
        rotated_from: uuid.UUID | None = None,
    ) -> tuple[str, TenantApiKey]:
        principal.require("api_keys", Action.manage)
        if principal.kind == "api_key":
            raise Forbidden("API keys cannot mint API keys.")
        scopes = validate_scopes(scopes)
        _ensure_creator_holds(principal, scopes)
        raw, parsed = keyfmt.generate()
        async with self._db.tenant_session(principal.tenant_id, user_id=principal.user_id) as s:
            row = TenantApiKey(
                tenant_id=principal.tenant_id,
                key_id=parsed.key_id,
                name=name,
                digest=keyfmt.digest(self._peppers[self._active_pepper], parsed),
                pepper_id=self._active_pepper,
                scopes=scopes,
                created_by_user_id=principal.user_id,
                expires_at=(_now() + timedelta(days=expires_in_days)) if expires_in_days else None,
                rotated_from_id=rotated_from,
            )
            s.add(row)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=principal.tenant_id,
                action="api_key.create",
                resource="api_keys",
                resource_id=str(row.id),
                detail={
                    "key_id": parsed.key_id,
                    "scopes": scopes,
                    "rotated_from": str(rotated_from) if rotated_from else None,
                },
            )
            await s.commit()
        return raw, row

    async def list_keys(self, principal: Principal) -> list[TenantApiKey]:
        principal.require("api_keys", Action.manage)
        async with self._db.tenant_session(principal.tenant_id, user_id=principal.user_id) as s:
            return list(
                (await s.execute(select(TenantApiKey).order_by(TenantApiKey.created_at.desc()))).scalars()
            )

    async def revoke(self, principal: Principal, key_row_id: uuid.UUID, ctx: AuditContext) -> None:
        principal.require("api_keys", Action.manage)
        async with self._db.tenant_session(principal.tenant_id, user_id=principal.user_id) as s:
            row = (
                await s.execute(select(TenantApiKey).where(TenantApiKey.id == key_row_id).with_for_update())
            ).scalar_one_or_none()
            if row is None:
                raise NotFound()
            if row.revoked_at is None:
                row.revoked_at = _now()
                await record(
                    s,
                    ctx,
                    tenant_id=principal.tenant_id,
                    action="api_key.revoke",
                    resource="api_keys",
                    resource_id=str(row.id),
                    detail={"key_id": row.key_id},
                )
            key_id = row.key_id
            await s.commit()
        await self._tombstone(key_id)

    async def rotate(
        self, principal: Principal, key_row_id: uuid.UUID, *, grace_s: int, ctx: AuditContext
    ) -> tuple[str, TenantApiKey]:
        """Issue a replacement with the same scopes; the old key expires after ``grace_s``."""
        if not 0 <= grace_s <= MAX_ROTATION_GRACE_S:
            raise ValidationFailed(f"grace period must be 0..{MAX_ROTATION_GRACE_S} seconds")
        principal.require("api_keys", Action.manage)
        async with self._db.tenant_session(principal.tenant_id, user_id=principal.user_id) as s:
            old = (
                await s.execute(select(TenantApiKey).where(TenantApiKey.id == key_row_id).with_for_update())
            ).scalar_one_or_none()
            if old is None or old.revoked_at is not None:
                raise NotFound()
            new_expiry = _now() + timedelta(seconds=grace_s)
            if old.expires_at is None or old.expires_at > new_expiry:
                old.expires_at = new_expiry
            scopes, name, old_key_id = list(old.scopes), old.name, old.key_id
            await s.commit()
        if grace_s == 0:
            await self._tombstone(old_key_id)
        else:
            await safe(self._cache.delete(self._cache.keys.api_key(old_key_id)), op="apikey.invalidate")
        return await self.create(
            principal, name=name, scopes=scopes, expires_in_days=None, ctx=ctx, rotated_from=key_row_id
        )

    async def _tombstone(self, key_id: str) -> None:
        await safe(
            self._cache.set_json(
                self._cache.keys.api_key(key_id), {"state": "revoked"}, self._s.api_key_tombstone_ttl_s
            ),
            op="apikey.tombstone",
        )

    # -- authentication -------------------------------------------------------------------------

    async def authenticate(self, raw: str) -> ApiKeyIdentity:
        parsed = keyfmt.parse(raw)
        if parsed is None:
            raise Unauthorized("Invalid API key.")
        cache_key = self._cache.keys.api_key(parsed.key_id)
        ok, meta = await safe(self._cache.get_json(cache_key), op="apikey.get")
        if not (ok and meta is not None):
            meta = await self._load(parsed.key_id)
            if ok:
                ttl = _NEGATIVE_TTL_S if meta["state"] == "absent" else self._s.api_key_cache_ttl_s
                await safe(self._cache.set_json(cache_key, meta, ttl, nx=True), op="apikey.populate")
        return self._check(parsed, meta)

    def _check(self, parsed: keyfmt.ParsedKey, meta: dict[str, Any]) -> ApiKeyIdentity:
        if meta.get("state") != "active":
            raise Unauthorized("Invalid API key.")
        pepper = self._peppers.get(meta.get("pep", "p1"))
        if pepper is None or not keyfmt.verify(pepper, parsed, bytes.fromhex(meta["digest"])):
            raise Unauthorized("Invalid API key.")
        exp = meta.get("exp")
        if exp is not None and datetime.fromisoformat(exp) <= _now():
            raise Unauthorized("API key expired.")
        return ApiKeyIdentity(
            row_id=uuid.UUID(meta["id"]),
            tenant_id=uuid.UUID(meta["tid"]),
            creator_id=uuid.UUID(meta["uid"]),
            scopes=frozenset(meta["scopes"]),
        )

    async def _load(self, key_id: str) -> dict[str, Any]:
        async with self._db.anonymous_session() as s:
            await s.execute(text("SELECT set_config('app.api_key_lookup', :k, true)"), {"k": key_id})
            row = (
                await s.execute(select(TenantApiKey).where(TenantApiKey.key_id == key_id))
            ).scalar_one_or_none()
        if row is None:
            return {"state": "absent"}
        if row.revoked_at is not None:
            return {"state": "revoked"}
        return {
            "state": "active",
            "id": str(row.id),
            "tid": str(row.tenant_id),
            "uid": str(row.created_by_user_id),
            "digest": row.digest.hex(),
            "pep": row.pepper_id,
            "scopes": list(row.scopes),
            "exp": row.expires_at.isoformat() if row.expires_at else None,
        }
