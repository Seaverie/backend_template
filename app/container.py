"""Service wiring. One container per process, created in the app lifespan."""

from __future__ import annotations

from dataclasses import dataclass

from app.cache.redis import Cache
from app.core.config import Settings
from app.db.session import Database
from app.integrations.http import SafeHttpClient
from app.security.crypto import Envelope, build_envelope
from app.security.tokens import TokenService
from app.services.analytics import AnalyticsService
from app.services.api_keys import ApiKeyService
from app.services.auth import AuthService
from app.services.authz import AuthzService
from app.services.deliveries import DeliveryService
from app.services.idempotency import IdempotencyService
from app.services.imports import ImportService
from app.services.inventory import InventoryService
from app.services.maps import MapService
from app.services.notifications import NotificationService
from app.services.offboarding import OffboardingService
from app.services.orders import OrderService
from app.services.payments import PaymentService
from app.services.providers import ProviderService
from app.services.ratelimit import RateLimiter
from app.services.rbac import RbacService
from app.services.rma import RmaService
from app.services.schema import SchemaService
from app.services.tenant_db import TenantDbService
from app.services.tenants import TenantService
from app.services.webhooks import WebhookService


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
    envelope: Envelope
    http: SafeHttpClient
    inventory: InventoryService
    orders: OrderService
    providers: ProviderService
    payments: PaymentService
    rma: RmaService
    analytics: AnalyticsService
    maps: MapService
    tenant_db: TenantDbService
    webhooks: WebhookService
    notifications: NotificationService
    imports: ImportService
    offboarding: OffboardingService
    deliveries: DeliveryService

    @classmethod
    def build(cls, settings: Settings) -> Container:
        db = Database(settings)
        cache = Cache(settings)
        tokens = TokenService(settings)
        envelope = build_envelope(settings)
        tenants = TenantService(settings, db, cache)
        authz = AuthzService(settings, db, cache)
        schema = SchemaService(settings, db, cache)
        http = SafeHttpClient(
            timeout_s=settings.outbound_timeout_s, max_response_bytes=settings.outbound_max_response_bytes
        )
        providers = ProviderService(settings, db, cache, envelope, http)
        payments = PaymentService(db, providers)
        tenant_db = TenantDbService(settings, db, envelope, schema, tenants)
        outbound = SafeHttpClient(
            timeout_s=settings.outbound_timeout_s, max_response_bytes=settings.outbound_max_response_bytes
        )
        return cls(
            settings=settings,
            db=db,
            cache=cache,
            tokens=tokens,
            tenants=tenants,
            authz=authz,
            auth=AuthService(settings, db, cache, tokens, tenants, envelope),
            api_keys=ApiKeyService(settings, db, cache),
            schema=schema,
            limiter=RateLimiter(settings, cache),
            idempotency=IdempotencyService(settings, cache),
            rbac=RbacService(settings, db, cache, authz, schema),
            envelope=envelope,
            http=http,
            inventory=InventoryService(settings, db),
            orders=OrderService(db),
            providers=providers,
            payments=payments,
            rma=RmaService(db, payments),
            analytics=AnalyticsService(settings, db),
            maps=MapService(settings, cache, providers),
            tenant_db=tenant_db,
            webhooks=WebhookService(settings, db, envelope, outbound),
            notifications=NotificationService(settings, db, envelope, outbound),
            imports=ImportService(settings, db, schema, authz, tenants, tenant_db),
            offboarding=OffboardingService(settings, db, cache, envelope, schema, tenants, tenant_db),
            deliveries=DeliveryService(db),
        )

    async def close(self) -> None:
        await self.tenant_db.close()
        await self.http.close()
        await self.webhooks.http.close()
        await self.cache.close()
        await self.db.dispose()
