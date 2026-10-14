from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routers import api_keys, auth, data, health, platform, tenant_admin
from app.container import Container
from app.core.config import Settings, get_settings
from app.core.errors import install_error_handlers
from app.core.logging import configure_logging, get_logger
from app.middleware.request import RequestContextMiddleware

log = get_logger("app")


def create_app(settings: Settings | None = None, container: Container | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level, settings.log_json)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.container = container or Container.build(settings)
        log.info("app.startup", environment=settings.environment, api_prefix=settings.api_prefix)
        try:
            yield
        finally:
            # Graceful shutdown: the ASGI server stops accepting connections and drains in-flight
            # requests before lifespan shutdown runs; then pools are closed.
            await app.state.container.close()
            log.info("app.shutdown")

    docs = settings.environment != "production"
    app = FastAPI(
        title="TenantCore",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=f"{settings.api_prefix}/docs" if docs else None,
        redoc_url=None,
        openapi_url=f"{settings.api_prefix}/openapi.json" if docs else None,
    )
    install_error_handlers(app)
    app.add_middleware(RequestContextMiddleware, max_body_bytes=settings.max_request_bytes)

    app.include_router(health.router)
    for r in (auth.router, platform.router, api_keys.router, tenant_admin.router, data.router):
        app.include_router(r, prefix=settings.api_prefix)
    return app
