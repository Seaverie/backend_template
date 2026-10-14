"""Alembic environment. Runs as the schema-owner role (TC_DDL_DATABASE_URL), never as a superuser."""

from __future__ import annotations

import asyncio
import os

from alembic import context
from sqlalchemy import pool
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import create_async_engine

from app.models import Base

config = context.config
target_metadata = Base.metadata


def _url() -> str:
    url = os.environ.get("TC_DDL_DATABASE_URL")
    if not url:
        raise RuntimeError("TC_DDL_DATABASE_URL must be set to run migrations")
    return url


def _include_object(obj, name, type_, reflected, compare_to):  # type: ignore[no-untyped-def]
    # Dynamic tenant tables are managed by the DDL engine, not Alembic autogenerate.
    return not (type_ == "table" and getattr(obj, "schema", None) == "tenant_data")


def run_offline() -> None:
    context.configure(
        url=_url(), target_metadata=target_metadata, literal_binds=True, include_object=_include_object
    )
    with context.begin_transaction():
        context.run_migrations()


def _do_run(connection: Connection) -> None:
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        include_object=_include_object,
        transaction_per_migration=True,
    )
    with context.begin_transaction():
        context.run_migrations()


async def run_online() -> None:
    engine = create_async_engine(_url(), poolclass=pool.NullPool)
    async with engine.connect() as conn:
        await conn.run_sync(_do_run)
    await engine.dispose()


if context.is_offline_mode():
    run_offline()
else:
    asyncio.run(run_online())
