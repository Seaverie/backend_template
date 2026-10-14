"""Row-level security verified directly against PostgreSQL under the real application role."""

from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable

import pytest
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError, IntegrityError, ProgrammingError
from sqlalchemy.ext.asyncio import create_async_engine

from app.container import Container
from app.models import TENANT_OWNED_TABLES
from tests.integration.conftest import TenantCtx


async def test_runtime_role_is_not_privileged(container: Container) -> None:
    async with container.db.engine.connect() as conn:
        row = (
            await conn.execute(
                text("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")
            )
        ).one()
    assert row.rolsuper is False
    assert row.rolbypassrls is False


async def test_every_tenant_table_has_forced_rls(container: Container) -> None:
    async with container.db.engine.connect() as conn:
        rows = (
            await conn.execute(
                text(
                    "SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity,"
                    " (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) AS policies"
                    " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    " WHERE n.nspname = 'public' AND c.relname = ANY(:t)"
                ),
                {"t": list(TENANT_OWNED_TABLES)},
            )
        ).all()
    found = {r.relname: r for r in rows}
    assert set(found) == set(TENANT_OWNED_TABLES)
    for name, r in found.items():
        assert r.relrowsecurity and r.relforcerowsecurity and r.policies >= 1, name


async def test_dynamic_tables_have_forced_rls(
    container: Container, new_table: Callable[..., Awaitable[str]]
) -> None:
    name = await new_table([{"name": "a", "type": "text"}])
    async with container.db.engine.connect() as conn:
        r = (
            await conn.execute(
                text(
                    "SELECT c.relrowsecurity, c.relforcerowsecurity FROM pg_class c"
                    " JOIN pg_namespace n ON n.oid = c.relnamespace"
                    " WHERE n.nspname = 'tenant_data' AND c.relname = :n"
                ),
                {"n": f"dt_{name}"},
            )
        ).one()
    assert r.relrowsecurity and r.relforcerowsecurity


async def test_no_context_sees_nothing(
    container: Container, new_tenant: Callable[..., Awaitable[TenantCtx]]
) -> None:
    await new_tenant()
    async with container.db.anonymous_session() as s:
        assert (await s.execute(text("SELECT count(*) FROM app_users"))).scalar_one() == 0
        assert (await s.execute(text("SELECT count(*) FROM app_roles"))).scalar_one() == 0


async def test_cross_tenant_reads_and_writes_blocked(
    container: Container, new_tenant: Callable[..., Awaitable[TenantCtx]]
) -> None:
    a, b = await new_tenant(), await new_tenant()
    ta, tb = uuid.UUID(a.id), uuid.UUID(b.id)
    async with container.db.tenant_session(ta) as s:
        emails = set((await s.execute(text("SELECT email FROM app_users"))).scalars())
        assert emails == {a.owner_email}
        # Explicitly targeting the other tenant's rows still returns nothing.
        n = (
            await s.execute(text("SELECT count(*) FROM app_users WHERE tenant_id = :t"), {"t": tb})
        ).scalar_one()
        assert n == 0
        # UPDATE/DELETE against the other tenant affect zero rows.
        res = await s.execute(
            text("UPDATE app_users SET full_name = 'pwned' WHERE tenant_id = :t"), {"t": tb}
        )
        assert res.rowcount == 0  # type: ignore[attr-defined]
    # INSERT with another tenant's id violates WITH CHECK.
    with pytest.raises(DBAPIError) as ei:
        async with container.db.tenant_session(ta) as s:
            await s.execute(text("INSERT INTO app_roles (tenant_id, name) VALUES (:t, 'evil')"), {"t": tb})
    assert "row-level security" in str(ei.value.orig).lower()


async def test_composite_fk_blocks_cross_tenant_reference(
    container: Container, new_tenant: Callable[..., Awaitable[TenantCtx]]
) -> None:
    a, b = await new_tenant(), await new_tenant()
    ta, tb = uuid.UUID(a.id), uuid.UUID(b.id)
    async with container.db.tenant_session(tb) as s:
        role_b = (await s.execute(text("SELECT id FROM app_roles LIMIT 1"))).scalar_one()
    async with container.db.tenant_session(ta) as s:
        user_a = (await s.execute(text("SELECT id FROM app_users LIMIT 1"))).scalar_one()
    with pytest.raises(IntegrityError):
        async with container.db.tenant_session(ta) as s:
            await s.execute(
                text("INSERT INTO app_user_roles (tenant_id, user_id, role_id) VALUES (:t, :u, :r)"),
                {"t": ta, "u": user_a, "r": role_b},
            )


async def test_context_does_not_leak_across_pooled_connections(container: Container) -> None:
    """One physical connection, reused: tenant context must vanish at COMMIT and at ROLLBACK."""
    s = container.settings
    engine = create_async_engine(s.database_url.get_secret_value(), pool_size=1, max_overflow=0)
    try:
        tid = str(uuid.uuid4())
        async with engine.connect() as conn:
            pid1 = (await conn.execute(text("SELECT pg_backend_pid()"))).scalar_one()
            await conn.execute(text("SELECT set_config('app.tenant_id', :t, true)"), {"t": tid})
            assert (await conn.execute(text("SELECT app_current_tenant_id()::text"))).scalar_one() == tid
            await conn.commit()
            assert (await conn.execute(text("SELECT app_current_tenant_id()"))).scalar_one() is None
            await conn.execute(text("SELECT set_config('app.tenant_id', :t, true)"), {"t": tid})
            await conn.rollback()
            assert (await conn.execute(text("SELECT app_current_tenant_id()"))).scalar_one() is None
        async with engine.connect() as conn:  # returned to the pool and checked out again
            pid2 = (await conn.execute(text("SELECT pg_backend_pid()"))).scalar_one()
            assert pid1 == pid2
            assert (await conn.execute(text("SELECT app_current_tenant_id()"))).scalar_one() is None
    finally:
        await engine.dispose()


async def test_session_after_commit_fails_closed(
    container: Container, new_tenant: Callable[..., Awaitable[TenantCtx]]
) -> None:
    a = await new_tenant()
    async with container.db.tenant_session(uuid.UUID(a.id)) as s:
        assert (await s.execute(text("SELECT count(*) FROM app_users"))).scalar_one() == 1
        await s.commit()
        # Same session, new implicit transaction, no context -> nothing visible.
        assert (await s.execute(text("SELECT count(*) FROM app_users"))).scalar_one() == 0


async def test_tenant_cannot_change_platform_columns(
    container: Container, new_tenant: Callable[..., Awaitable[TenantCtx]]
) -> None:
    a = await new_tenant()
    with pytest.raises(DBAPIError):
        async with container.db.tenant_session(uuid.UUID(a.id)) as s:
            await s.execute(
                text("UPDATE app_tenants SET status = 'active', slug = 'stolen' WHERE id = :t"), {"t": a.id}
            )
    async with container.db.tenant_session(uuid.UUID(a.id)) as s:
        # Other tenants' rows are not updatable at all.
        res = await s.execute(text("UPDATE app_tenants SET name = 'x' WHERE id <> :t"), {"t": a.id})
        assert res.rowcount == 0  # type: ignore[attr-defined]


async def test_audit_log_is_append_only(
    container: Container, new_tenant: Callable[..., Awaitable[TenantCtx]]
) -> None:
    a = await new_tenant()
    for stmt in ("UPDATE system_audit_logs SET action = 'x'", "DELETE FROM system_audit_logs"):
        with pytest.raises((DBAPIError, ProgrammingError)):
            async with container.db.tenant_session(uuid.UUID(a.id)) as s:
                await s.execute(text(stmt))


async def test_runtime_role_cannot_write_schema_registry(container: Container) -> None:
    with pytest.raises(DBAPIError) as ei:
        async with container.db.anonymous_session() as s:
            await s.execute(text("UPDATE app_tables SET crud_mode = 'full'"))
    assert getattr(ei.value.orig, "sqlstate", None) == "42501"


async def test_platform_tables_hidden_from_tenant_context(
    container: Container, new_tenant: Callable[..., Awaitable[TenantCtx]]
) -> None:
    a = await new_tenant()
    async with container.db.tenant_session(uuid.UUID(a.id)) as s:
        assert (await s.execute(text("SELECT count(*) FROM platform_admins"))).scalar_one() == 0


async def test_api_key_lookup_policy_exposes_only_the_named_key(
    container: Container, new_tenant: Callable[..., Awaitable[TenantCtx]]
) -> None:
    from tests.integration.conftest import P, make_client

    a = await new_tenant()
    async with make_client(container) as c:
        ids = []
        for i in range(2):
            r = await c.post(f"{P}/api-keys", headers=a.h, json={"name": f"k{i}", "scopes": ["users:read"]})
            assert r.status_code == 201, r.text
            ids.append(r.json()["key_id"])
    async with container.db.anonymous_session() as s:
        await s.execute(text("SELECT set_config('app.api_key_lookup', :k, true)"), {"k": ids[0]})
        visible = list((await s.execute(text("SELECT key_id FROM tenant_api_keys"))).scalars())
    assert visible == [ids[0]]


async def test_tenant_database_registry_is_platform_writable_only(
    container: Container, new_tenant: Callable[..., Awaitable[TenantCtx]]
) -> None:
    a = await new_tenant()
    ta = uuid.UUID(a.id)
    with pytest.raises(DBAPIError) as ei:
        async with container.db.tenant_session(ta) as s:
            await s.execute(
                text(
                    "INSERT INTO app_tenant_databases (tenant_id, dialect, host, port, database, username,"
                    " password_enc, ssl_mode)"
                    " VALUES (:t, 'postgresql', 'evil', 5432, 'd', 'u', 'x', 'disable')"
                ),
                {"t": ta},
            )
    assert "row-level security" in str(ei.value.orig).lower()


async def test_gateway_lookup_policy_is_select_only(
    container: Container, new_tenant: Callable[..., Awaitable[TenantCtx]]
) -> None:
    a = await new_tenant()
    gid = uuid.uuid4()
    async with container.db.anonymous_session() as s:
        await s.execute(text("SELECT set_config('app.gateway_lookup', :g, true)"), {"g": str(gid)})
        res = await s.execute(text("UPDATE app_payment_gateways SET is_active = false"))
        assert res.rowcount == 0  # type: ignore[attr-defined]
    assert a.id
