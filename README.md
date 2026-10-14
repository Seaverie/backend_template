# TenantCore

A multi-tenant FastAPI backend with PostgreSQL row-level security, a dynamic schema engine, tenant-scoped RBAC, API keys, and Redis-backed rate limiting. Built on Python 3.11+, SQLAlchemy 2.0 (async), and asyncpg.

**Status: Phase 1 of 3, the security core.** This repository implements the trust boundaries everything else will sit on. Inventory, payments, Celery, enterprise database routing, and OpenTelemetry are Phase 2 and Phase 3 work and are **not** in this code yet. See [ROADMAP.md](ROADMAP.md) for exactly what is and isn't built.

## What's implemented

| Area | Implementation |
|---|---|
| Tenant isolation | `FORCE ROW LEVEL SECURITY` on every tenant-owned table, including dynamic ones. Context is set per transaction with `set_config(..., true)`, which is safe under PgBouncer transaction pooling. The runtime role is `NOSUPERUSER NOBYPASSRLS`. Composite `(tenant_id, id)` foreign keys block cross-tenant references. |
| Control plane | `app_tenants`, platform admins, and the schema registry. Writes to tenants are allowed only in platform transactions, and a trigger stops tenants from changing platform-controlled columns. |
| Auth | Argon2id passwords. HS256 JWTs with `kid` key rotation, plus validation of iss, aud, exp, nbf, typ, sid, and tid. Tenant and platform tokens use separate audiences. Refresh tokens are single-use, and reuse revokes the whole session. Accounts lock after repeated failures, and login timing is uniform. |
| API keys | Format is `tck_<id>_<secret>` (256-bit secret). The database stores only an HMAC-SHA256 digest with a pepper. Scopes are intersected with the creator's live permissions. Validation is cached in Redis. Revocation writes a tombstone, so it takes effect on the next request. Keys can be rotated with a grace period. |
| RBAC | Tenant-scoped roles grant permissions on a resource and action. Each permission carries a row scope (`all`/`own`) and field-level read/write allowlists. Escalation is blocked: users can only delegate what they hold. Permission matrices are cached under a versioned key and invalidated by an `authz_version` bump made in the same transaction as the change. |
| Dynamic schema | `POST /system/tables` (super admin) takes Pydantic-validated specs with allowlisted types and identifiers. DDL is compiled by SQLAlchemy. Supports dry runs, an advisory lock, a checksummed migration ledger, an audit row, and tenant-scoped unique constraints and FKs. Each new table gets a GIN full-text index. |
| Dynamic CRUD | `/data/{table}` supports typed filters with allowlisted operators, validated sort, capped pagination, full-text search, field projection, and expansion of reference fields. It rejects mass assignment, and `crud_mode` keeps domain tables read-only or hidden. Writes are audited, with sensitive fields redacted. |
| Idempotency | `Idempotency-Key` responses are stored for 24 h and replayed. A different body with the same key gets 422, and a concurrent duplicate gets 409. The store fails closed (503) when Redis is down. |
| Rate limiting | Atomic multi-bucket GCRA in a single Lua script, covering platform, tenant, principal, endpoint, and login buckets. Tenants can update their limits live, capped at the platform maximum. When Redis is down, an approximate per-process limiter takes over. |
| Guardrails | Streaming request-size limit, per-transaction statement and lock timeouts, security headers, and request IDs. Errors are generic and never include SQL, stack traces, or secrets. Structured JSON logs redact secrets, and slow queries (default 200 ms) are logged without parameters. |
| Health | `/health` reports liveness. `/ready` runs bounded DB and Redis checks. |

## Quick start

```bash
cp .env.example .env          # replace every CHANGE_ME
docker compose up --build     # postgres (roles bootstrapped), redis, migrations, api
docker compose exec api python -m app.cli create-platform-admin --email ops@example.com
```

The API is served at `http://localhost:8000/api/v1`, with OpenAPI docs at `/api/v1/docs` outside production. Change the prefix with `TC_API_PREFIX`.

A typical flow:

1. `POST /api/v1/platform/auth/login` returns a platform token.
2. `POST /api/v1/platform/tenants` creates a tenant and its first tenant admin.
3. `POST /api/v1/system/tables` defines a table, for example `products`.
4. `POST /api/v1/auth/login` with `{tenant, email, password}` logs in as the tenant admin.
5. `POST /api/v1/roles`, `POST /api/v1/users`, and `PUT /api/v1/users/{id}/roles` set up staff.
6. `POST /api/v1/api-keys` creates a key, then `/api/v1/data/products?filter[price][gte]=10&sort=-price` queries data.

## Development

```bash
pip install -e ".[dev]"
ruff check . && ruff format --check app tests
mypy app                                  # strict
pytest                                    # integration tests auto-skip without PostgreSQL/Redis
TC_REQUIRE_INTEGRATION=1 pytest           # what CI runs: fail instead of skip
```

Integration tests expect the database bootstrapped by `infra/postgres/01-roles.sh` and migrated with `alembic upgrade head`. Connection defaults are in `tests/conftest.py`.

### Test coverage

The suite has 122 tests. Unit tests cover token forgery and confusion, identifier and DDL injection, query parsing, mass assignment, escalation rules, and the rate-limiter fallback. Integration tests run against real PostgreSQL 16 and Redis 7 under the application role. They cover:

* Cross-tenant reads and writes through SQL and HTTP
* Forced RLS on every table, including dynamic ones
* Tenant context not leaking across a reused pooled connection
* Fail-closed behaviour after commit
* Composite-FK cross-tenant references
* The append-only audit log
* API key revocation against a warm cache
* Refresh-token reuse and concurrent refresh
* Idempotent replay and concurrent duplicates
* Rate limits
* Redis-outage degradation
* Model/migration drift

Several security tests were mutation-checked: disabling the revocation tombstone, the row-scope filter, or refresh-reuse detection makes them fail.

No performance benchmarks are claimed. Load-testing scripts are Phase 3.

## Layout

```
app/
  api/            routers + dependencies (auth, rate limiting)
  core/           settings, logging/redaction, error handling
  db/             engines, transaction-scoped tenant context
  cache/          Redis client, key registry, Lua helpers
  models/         SQLAlchemy models (control plane, identity, RBAC, registry, audit)
  security/       passwords, JWT, API key format, identifier validation
  services/       auth, authz, api keys, rbac, tenants, schema/DDL, idempotency, rate limiting, audit
  dynamic/        table specs + DDL generation, query parsing, generic CRUD
  middleware/     request ID, body limit, security headers, access log
migrations/       Alembic (tables + RLS policies + grants + triggers)
infra/postgres/   role bootstrap
docs/             SECURITY.md, DEPLOYMENT.md
```
