"""Transaction-scoped tenant context.

Every unit of work runs in one transaction whose first statement sets, with ``is_local = true``:

* ``app.tenant_id``    -- read by the RLS policies through ``app_current_tenant_id()``
* ``app.user_id``      -- informational (audit triggers, debugging)
* ``app.is_platform``  -- allows writes to control-plane tables (platform-admin transactions only)
* ``statement_timeout`` / ``lock_timeout`` / ``idle_in_transaction_session_timeout``

``set_config(..., true)`` is equivalent to ``SET LOCAL``: the values vanish at COMMIT or ROLLBACK. That
is what makes connection pooling (SQLAlchemy's pool and PgBouncer in *transaction* mode) safe: no
tenant state survives on a connection after the transaction that set it. If code accidentally keeps
using a session after committing, the next statement runs in a fresh transaction with no tenant
context, and RLS returns zero rows / rejects writes. It fails closed.
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from app.core.config import Settings
from app.db.engine import create_engine

_SET_CONTEXT = text(
    "SELECT set_config('app.tenant_id', :tenant_id, true),"
    " set_config('app.user_id', :user_id, true),"
    " set_config('app.is_platform', :is_platform, true),"
    " set_config('app.api_key_lookup', '', true),"
    " set_config('statement_timeout', :st, true),"
    " set_config('lock_timeout', :lt, true),"
    " set_config('idle_in_transaction_session_timeout', :it, true)"
)


class Database:
    def __init__(self, settings: Settings) -> None:
        self._s = settings
        self.engine: AsyncEngine = create_engine(
            settings,
            settings.database_url.get_secret_value(),
            application_name=f"{settings.service_name}-app",
        )
        # Small dedicated pool: DDL is rare and must not starve request traffic.
        self.ddl_engine: AsyncEngine = create_engine(
            settings,
            settings.ddl_database_url.get_secret_value(),
            application_name=f"{settings.service_name}-ddl",
            pool_size=2,
        )
        self._sessions = async_sessionmaker(self.engine, expire_on_commit=False, autoflush=False)
        self._ddl_sessions = async_sessionmaker(self.ddl_engine, expire_on_commit=False, autoflush=False)

    async def _apply(
        self,
        session: AsyncSession,
        *,
        tenant_id: uuid.UUID | None,
        user_id: uuid.UUID | None,
        is_platform: bool,
        statement_timeout_ms: int | None = None,
    ) -> None:
        await session.execute(
            _SET_CONTEXT,
            {
                "tenant_id": str(tenant_id) if tenant_id else "",
                "user_id": str(user_id) if user_id else "",
                "is_platform": "on" if is_platform else "",
                "st": str(statement_timeout_ms or self._s.db_statement_timeout_ms),
                "lt": str(self._s.db_lock_timeout_ms),
                "it": str(self._s.db_idle_in_tx_timeout_ms),
            },
        )

    @asynccontextmanager
    async def tenant_session(
        self,
        tenant_id: uuid.UUID,
        *,
        user_id: uuid.UUID | None = None,
        statement_timeout_ms: int | None = None,
    ) -> AsyncIterator[AsyncSession]:
        """Open a transaction bound to one tenant. Caller commits explicitly; anything else rolls back."""
        async with self._sessions() as session:
            await self._apply(
                session,
                tenant_id=tenant_id,
                user_id=user_id,
                is_platform=False,
                statement_timeout_ms=statement_timeout_ms,
            )
            yield session

    @asynccontextmanager
    async def anonymous_session(self) -> AsyncIterator[AsyncSession]:
        """No tenant context: only control-plane rows readable by the runtime role are visible."""
        async with self._sessions() as session:
            await self._apply(session, tenant_id=None, user_id=None, is_platform=False)
            yield session

    @asynccontextmanager
    async def platform_session(
        self, admin_id: uuid.UUID, *, tenant_id: uuid.UUID | None = None
    ) -> AsyncIterator[AsyncSession]:
        """Platform-admin transaction. Still subject to RLS: to touch a tenant's rows the caller must
        name that tenant explicitly (``tenant_id`` or :meth:`switch_tenant`)."""
        async with self._sessions() as session:
            await self._apply(session, tenant_id=tenant_id, user_id=admin_id, is_platform=True)
            yield session

    async def switch_tenant(self, session: AsyncSession, tenant_id: uuid.UUID) -> None:
        await session.execute(text("SELECT set_config('app.tenant_id', :t, true)"), {"t": str(tenant_id)})

    @asynccontextmanager
    async def ddl_session(self) -> AsyncIterator[AsyncSession]:
        """Schema-owner connection for dynamic DDL. Platform-admin code paths only."""
        async with self._ddl_sessions() as session:
            await session.execute(
                text(
                    "SELECT set_config('statement_timeout', :st, true), set_config('lock_timeout', :lt, true)"
                ),
                {"st": "60000", "lt": str(self._s.db_lock_timeout_ms)},
            )
            yield session

    async def dispose(self) -> None:
        await self.engine.dispose()
        await self.ddl_engine.dispose()
