"""Tenant provider configuration (payment gateways, map providers): encrypted storage, validation,
Redis-cached resolution, and construction of provider clients.

The Redis cache holds non-secret configuration plus the *ciphertext* of the secrets, never
plaintext. Decryption happens in-process (DEK cache with a bounded TTL). Entries are keyed by
provider row and ``secret_version``, and are deleted on any change.
"""

from __future__ import annotations

import json
import re
import uuid
from typing import Any, Literal

from sqlalchemy import select, update

from app.cache.redis import Cache, safe
from app.core.config import Settings
from app.core.errors import NotFound, ServiceUnavailable, ValidationFailed
from app.db.session import Database
from app.integrations.http import SafeHttpClient
from app.integrations.maps import (
    GOOGLE_SECRET_RULES,
    MAPBOX_SECRET_RULES,
    GoogleMapsProvider,
    MapboxProvider,
    MapProvider,
)
from app.integrations.payments import (
    SANDBOX_SECRET_RULES,
    STRIPE_SECRET_RULES,
    PaymentProvider,
    SandboxProvider,
    StripeProvider,
)
from app.models import Action, AppMapProvider, AppPaymentGateway, MapProviderKind, PaymentProviderKind
from app.security.crypto import CryptoError, Envelope
from app.services.audit import AuditContext, record
from app.services.authz import Principal

Kind = Literal["payment", "map"]
_PUBLIC_KEY_RE = re.compile(r"^[a-z][a-z0-9_]{0,40}$")


def secret_context(kind: Kind, tenant_id: uuid.UUID, row_id: uuid.UUID) -> dict[str, str]:
    return {"tenant": str(tenant_id), "purpose": f"{kind}_provider", "id": str(row_id)}


def _validate_secrets(rules: dict[str, re.Pattern[str]], secrets: dict[str, str]) -> None:
    if set(secrets) != set(rules):
        raise ValidationFailed("Provider secrets must contain exactly: " + ", ".join(sorted(rules)))
    bad = [k for k, rx in rules.items() if not rx.fullmatch(secrets[k])]
    if bad:
        raise ValidationFailed("Malformed provider secrets.", details={"fields": bad})


def _validate_public(cfg: dict[str, Any]) -> None:
    if len(json.dumps(cfg)) > 4096 or any(not _PUBLIC_KEY_RE.fullmatch(k) for k in cfg):
        raise ValidationFailed("public_config must be a small object with simple keys.")
    if any(isinstance(v, dict | list) for v in cfg.values()):
        raise ValidationFailed("public_config values must be scalars.")


class ProviderService:
    def __init__(
        self, settings: Settings, db: Database, cache: Cache, envelope: Envelope, http: SafeHttpClient
    ) -> None:
        self._s = settings
        self._db = db
        self._cache = cache
        self._env = envelope
        self.http = http

    def _rules(self, kind: Kind, provider: str) -> dict[str, re.Pattern[str]]:
        try:
            if kind == "payment":
                pk = PaymentProviderKind(provider)
                if pk == PaymentProviderKind.sandbox and not self._s.sandbox_payments:
                    raise ValidationFailed("The sandbox payment provider is disabled in this environment.")
                return {PaymentProviderKind.stripe: STRIPE_SECRET_RULES, PaymentProviderKind.sandbox: SANDBOX_SECRET_RULES}[pk]
            mk = MapProviderKind(provider)
        except ValueError as e:
            raise ValidationFailed("Unsupported provider.") from e
        return {MapProviderKind.google: GOOGLE_SECRET_RULES, MapProviderKind.mapbox: MAPBOX_SECRET_RULES}[mk]

    def _model(self, kind: Kind) -> Any:
        return AppPaymentGateway if kind == "payment" else AppMapProvider

    def _resource(self, kind: Kind) -> str:
        return "payments" if kind == "payment" else "maps"

    def _ckey(self, kind: Kind, tenant_id: uuid.UUID, which: str) -> str:
        return f"{self._cache.keys.p}:provider:v1:{kind}:{tenant_id}:{which}"

    async def _invalidate(self, kind: Kind, tenant_id: uuid.UUID, row_id: uuid.UUID) -> None:
        await safe(
            self._cache.delete(
                self._ckey(kind, tenant_id, "default"), self._ckey(kind, tenant_id, str(row_id))
            ),
            op="provider.invalidate",
        )

    # -- management -----------------------------------------------------------------------------

    async def create(
        self,
        p: Principal,
        kind: Kind,
        *,
        name: str,
        provider: str,
        secrets: dict[str, str],
        public_config: dict[str, Any],
        is_default: bool,
        ctx: AuditContext,
    ) -> Any:
        p.require(self._resource(kind), Action.manage)
        _validate_secrets(self._rules(kind, provider), secrets)
        _validate_public(public_config)
        model = self._model(kind)
        row_id = uuid.uuid4()
        enc = await self._env.encrypt(json.dumps(secrets).encode(), secret_context(kind, p.tenant_id, row_id))
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            if is_default:
                await s.execute(update(model).where(model.is_default.is_(True)).values(is_default=False))
            row = model(
                id=row_id,
                tenant_id=p.tenant_id,
                name=name,
                provider=provider,
                secrets_enc=enc,
                public_config=public_config,
                is_default=is_default,
            )
            s.add(row)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action=f"{kind}_provider.create",
                resource=self._resource(kind),
                resource_id=str(row_id),
                detail={"provider": provider, "name": name},
            )
            await s.commit()
        await self._invalidate(kind, p.tenant_id, row_id)
        return row

    async def rotate_secrets(
        self, p: Principal, kind: Kind, row_id: uuid.UUID, secrets: dict[str, str], ctx: AuditContext
    ) -> None:
        p.require(self._resource(kind), Action.manage)
        model = self._model(kind)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            row = (
                await s.execute(select(model).where(model.id == row_id).with_for_update())
            ).scalar_one_or_none()
            if row is None:
                raise NotFound()
            _validate_secrets(self._rules(kind, row.provider), secrets)
            row.secrets_enc = await self._env.encrypt(
                json.dumps(secrets).encode(), secret_context(kind, p.tenant_id, row_id)
            )
            row.secret_version += 1
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action=f"{kind}_provider.rotate",
                resource=self._resource(kind),
                resource_id=str(row_id),
                detail={"secret_version": row.secret_version},
            )
            await s.commit()
        await self._invalidate(kind, p.tenant_id, row_id)

    async def list_configs(self, p: Principal, kind: Kind) -> list[Any]:
        p.require(self._resource(kind), Action.read)
        model = self._model(kind)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            return list((await s.execute(select(model).order_by(model.name))).scalars())

    # -- resolution -----------------------------------------------------------------------------

    async def _config(self, kind: Kind, tenant_id: uuid.UUID, row_id: uuid.UUID | None) -> dict[str, Any]:
        which = str(row_id) if row_id else "default"
        key = self._ckey(kind, tenant_id, which)
        ok, cached = await safe(self._cache.get_json(key), op="provider.get")
        if ok and cached:
            return cached  # type: ignore[no-any-return]
        model = self._model(kind)
        stmt = select(model).where(model.is_active.is_(True))
        stmt = stmt.where(model.id == row_id) if row_id else stmt.where(model.is_default.is_(True))
        async with self._db.tenant_session(tenant_id) as s:
            row = (await s.execute(stmt)).scalar_one_or_none()
        if row is None:
            raise NotFound("No active provider configured.")
        cfg = {
            "id": str(row.id),
            "provider": row.provider.value,
            "secrets_enc": row.secrets_enc,
            "public_config": row.public_config,
            "secret_version": row.secret_version,
        }
        if ok:
            await safe(self._cache.set_json(key, cfg, self._s.provider_cache_ttl_s), op="provider.set")
        return cfg

    async def _secrets(self, kind: Kind, tenant_id: uuid.UUID, cfg: dict[str, Any]) -> dict[str, str]:
        try:
            raw = await self._env.decrypt(
                cfg["secrets_enc"], secret_context(kind, tenant_id, uuid.UUID(cfg["id"]))
            )
        except CryptoError as e:
            raise ServiceUnavailable("Provider credentials are unavailable.") from e
        return json.loads(raw)  # type: ignore[no-any-return]

    async def payment_provider(
        self, tenant_id: uuid.UUID, gateway_id: uuid.UUID | None = None, *, cfg: dict[str, Any] | None = None
    ) -> tuple[uuid.UUID, str, PaymentProvider]:
        cfg = cfg or await self._config("payment", tenant_id, gateway_id)
        secrets = await self._secrets("payment", tenant_id, cfg)
        kind = PaymentProviderKind(cfg["provider"])
        provider: PaymentProvider
        if kind == PaymentProviderKind.stripe:
            provider = StripeProvider(self.http, secrets)
        else:
            if not self._s.sandbox_payments:
                raise ServiceUnavailable("The sandbox payment provider is disabled in this environment.")
            provider = SandboxProvider(secrets)
        return uuid.UUID(cfg["id"]), kind.value, provider

    async def map_provider(self, tenant_id: uuid.UUID) -> tuple[uuid.UUID, MapProvider]:
        cfg = await self._config("map", tenant_id, None)
        secrets = await self._secrets("map", tenant_id, cfg)
        kind = MapProviderKind(cfg["provider"])
        provider: MapProvider = (
            GoogleMapsProvider(self.http, secrets)
            if kind == MapProviderKind.google
            else MapboxProvider(self.http, secrets)
        )
        return uuid.UUID(cfg["id"]), provider
