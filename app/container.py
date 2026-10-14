"""Service wiring. One container per process, created in the app lifespan."""

from __future__ import annotations

from dataclasses import dataclass

from app.cache.redis import Cache
from app.core.config import Settings
from app.db.session import Database
from app.security.tokens import TokenService
from app.services.api_keys import ApiKeyService
from app.services.auth import AuthService
from app.services.authz import AuthzService
from app.services.idempotency import IdempotencyService
from app.services.ratelimit import RateLimiter
from app.services.rbac import RbacService
from app.services.schema import SchemaService
from app.services.tenants import TenantService


@dataclass
class Container:
    settings: Settings
    db: Database
    cache: Cache
    tokens: TokenService
    tenants: TenantService
    authz: AuthzService
    auth: AuthService
    api_keys: ApiKeyService
    schema: SchemaService
    limiter: RateLimiter
    idempotency: IdempotencyService
    rbac: RbacService

    @classmethod
    def build(cls, settings: Settings) -> Container:
        db = Database(settings)
        cache = Cache(settings)
        tokens = TokenService(settings)
        tenants = TenantService(settings, db, cache)
        authz = AuthzService(settings, db, cache)
        schema = SchemaService(settings, db, cache)
        return cls(
            settings=settings,
            db=db,
            cache=cache,
            tokens=tokens,
            tenants=tenants,
            authz=authz,
            auth=AuthService(settings, db, cache, tokens, tenants),
            api_keys=ApiKeyService(settings, db, cache),
            schema=schema,
            limiter=RateLimiter(settings, cache),
            idempotency=IdempotencyService(settings, cache),
            rbac=RbacService(settings, db, cache, authz, schema),
        )

    async def close(self) -> None:
        await self.cache.close()
        await self.db.dispose()
