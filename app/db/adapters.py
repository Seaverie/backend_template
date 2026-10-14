"""Dialect adapters for dedicated (enterprise) tenant databases.

Capability matrix (what TenantCore relies on, per dialect):

| Capability                    | PostgreSQL           | MySQL 8           | Oracle 19c+          |
|-------------------------------|----------------------|-------------------|----------------------|
| Row-level security            | yes (same policies)  | **no**            | **no** (VPD unused)  |
| RETURNING on INSERT/UPDATE    | yes                  | **no**            | yes (RETURNING INTO) |
| JSONB + GIN                   | yes                  | JSON, no GIN      | JSON (21c+), no GIN  |
| CREATE INDEX CONCURRENTLY     | yes                  | online DDL        | ONLINE keyword       |
| Dynamic CRUD / schema sync    | **supported**        | not yet (501)     | not yet (501)        |

For MySQL and Oracle, isolation rests **entirely** on the database being dedicated to one tenant (a
separate database/schema plus credentials). Nothing here assumes PostgreSQL RLS exists there. Phase 2
supports connection management (registry, rotation, connectivity checks) for all three dialects, and
data routing for PostgreSQL only.
"""

from __future__ import annotations

import asyncio
import ipaddress
import socket
import ssl
from dataclasses import dataclass
from typing import Any

from sqlalchemy.engine import URL

from app.models import DbDialect, DbSslMode

# Never allowed, whatever the configured allowlist says.
_ALWAYS_DENIED = [
    ipaddress.ip_network(n)
    for n in ("0.0.0.0/8", "169.254.0.0/16", "224.0.0.0/4", "240.0.0.0/4", "::/128", "fe80::/10", "ff00::/8")
]


class TargetNotAllowed(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class Capabilities:
    supports_rls: bool
    supports_returning: bool
    supports_jsonb_gin: bool
    supports_concurrent_index: bool
    supports_dynamic_crud: bool


@dataclass(frozen=True, slots=True)
class ConnSpec:
    dialect: DbDialect
    host: str
    port: int
    database: str
    username: str
    password: str
    ssl_mode: DbSslMode
    ssl_root_cert: str | None


def ssl_context(mode: DbSslMode, root_cert: str | None) -> ssl.SSLContext | bool:
    if mode == DbSslMode.disable:
        return False
    if mode == DbSslMode.require:  # encrypt, do not verify (libpq "require" semantics)
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    ctx = ssl.create_default_context(cadata=root_cert) if root_cert else ssl.create_default_context()
    ctx.check_hostname = True
    ctx.verify_mode = ssl.CERT_REQUIRED
    return ctx


class PostgresAdapter:
    capabilities = Capabilities(True, True, True, True, True)

    def url(self, c: ConnSpec) -> URL:
        return URL.create("postgresql+asyncpg", c.username, c.password, c.host, c.port, c.database)

    def connect_args(self, c: ConnSpec, timeout_s: float) -> dict[str, Any]:
        return {
            "ssl": ssl_context(c.ssl_mode, c.ssl_root_cert),
            "timeout": timeout_s,
            "server_settings": {"application_name": "tenantcore-dedicated"},
        }


class MySqlAdapter:
    capabilities = Capabilities(False, False, False, False, False)

    def url(self, c: ConnSpec) -> URL:
        return URL.create("mysql+aiomysql", c.username, c.password, c.host, c.port, c.database)

    def connect_args(self, c: ConnSpec, timeout_s: float) -> dict[str, Any]:
        ctx = ssl_context(c.ssl_mode, c.ssl_root_cert)
        args: dict[str, Any] = {"connect_timeout": timeout_s}
        if ctx:
            args["ssl"] = ctx
        return args


class OracleAdapter:
    capabilities = Capabilities(False, True, False, False, False)

    def url(self, c: ConnSpec) -> URL:
        # ``database`` is the service name.
        return URL.create(
            "oracle+oracledb_async",
            c.username,
            c.password,
            c.host,
            c.port,
            query={"service_name": c.database},
        )

    def connect_args(self, c: ConnSpec, timeout_s: float) -> dict[str, Any]:
        args: dict[str, Any] = {"tcp_connect_timeout": timeout_s}
        if c.ssl_mode != DbSslMode.disable:
            args["protocol"] = "tcps"
            args["ssl_server_dn_match"] = c.ssl_mode == DbSslMode.verify_full
        return args


ADAPTERS: dict[DbDialect, PostgresAdapter | MySqlAdapter | OracleAdapter] = {
    DbDialect.postgresql: PostgresAdapter(),
    DbDialect.mysql: MySqlAdapter(),
    DbDialect.oracle: OracleAdapter(),
}


async def validate_target(host: str, port: int, allowed_networks: list[str]) -> list[str]:
    """Resolve ``host`` and require every address to be inside an allowed network (SSRF guard for
    database routing). Returns the resolved addresses."""
    if not allowed_networks:
        raise TargetNotAllowed("dedicated tenant databases are disabled (no allowed networks configured)")
    nets = [ipaddress.ip_network(n, strict=False) for n in allowed_networks]
    try:
        infos = await asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError as e:
        raise TargetNotAllowed("host does not resolve") from e
    addrs = sorted({str(i[4][0]) for i in infos})
    for a in addrs:
        ip = ipaddress.ip_address(a)
        if any(ip in n for n in _ALWAYS_DENIED):
            raise TargetNotAllowed("host resolves to a forbidden address range")
        if not any(ip.version == n.version and ip in n for n in nets):
            raise TargetNotAllowed("host resolves outside the allowed networks")
    return addrs
