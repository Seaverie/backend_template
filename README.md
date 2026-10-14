# TenantCore

A multi-tenant FastAPI backend built on Python 3.11+, SQLAlchemy 2.0 (async), and asyncpg. It covers:

* PostgreSQL row-level security for tenant isolation
* dedicated databases for enterprise tenants
* a dynamic schema engine, plus tenant-scoped RBAC and API keys
* an inventory ledger, orders and returns
* payment and map provider integrations
* Redis-backed rate limiting

**Status: Phase 2 of 3.** Phase 1 (security core) and Phase 2 (domain, integrations, enterprise routing) are implemented and tested. Phase 3 (Celery, webhooks, imports, notifications, offboarding, OpenTelemetry, load tests) is not in this code yet. [ROADMAP.md](ROADMAP.md) lists exactly what is and isn't built.

## What's implemented

### Security core (Phase 1)

| Area | Implementation |
|---|---|
| Tenant isolation | Every tenant-owned table (static and dynamic) uses `FORCE ROW LEVEL SECURITY`. Tenant context is set per transaction with `set_config(..., true)`, which is safe under PgBouncer transaction pooling. The runtime role is `NOSUPERUSER NOBYPASSRLS`. Composite `(tenant_id, id)` foreign keys block cross-tenant references. |
| Auth | Argon2id password hashing. JWTs use `kid` key rotation and full claim validation. Tenant and platform tokens use separate audiences. Refresh tokens are single-use with reuse detection. Accounts lock after repeated failures, and login timing is uniform. |
| API keys | Only an HMAC digest is stored, and the pepper keyring can rotate without breaking existing keys. A key's scopes are intersected with its creator's live permissions. Validation is cached in Redis, and revocation writes a tombstone so it takes effect on the next request. |
| RBAC | Tenant roles carry row scope (`all`/`own`) and field-level read/write allowlists. Users can only delegate what they hold. The permission cache is versioned and bumped in the same transaction as the change. |
| Dynamic CRUD | `/data/{table}` supports typed allowlisted filters, sort, pagination, full-text search, projection, and reference expansion. It rejects mass assignment and writes an audit trail. |
| Guardrails | Idempotency keys (fail closed without Redis), atomic multi-level GCRA rate limits, request-size limits, statement timeouts, generic error responses, secret redaction in logs, slow-query logging, and `/health` and `/ready`. |

### Domain and integrations (Phase 2)

| Area | Implementation |
|---|---|
| Inventory ledger | Append-only `stock_movements` (trigger-enforced). Balances are row-locked `FOR UPDATE` in deterministic order, and `0 ≤ reserved ≤ on_hand` is enforced by CHECK constraints. Batches are idempotent through an advisory-locked `batch_key`. Supports adjustments, transfers of available stock only, and purchase-order receiving. |
| Catalog | Products and variants with JSONB metadata (GIN `jsonb_path_ops`), a generated `tsvector` with a GIN index for full-text search, and an anonymous public catalog endpoint. |
| Orders | Tenant-configurable statuses and transition graph. Each status has a stock effect: `reserve`, `commit`, `release`, or none. Checkout prices come from the catalog, `Idempotency-Key` is required, and stock is reserved under row locks. Status history is append-only. |
| Returns (RMA) | Validated state machine: requested → approved/rejected → received → refunded → closed. Return quantities are bounded by what shipped and what is already in open returns. Received items restock through the ledger. Refunds are idempotent at the provider and in the database. |
| Payments | Provider interface with Stripe and a sandbox provider (disabled in production). Credentials are envelope-encrypted and bound to tenant, purpose, and row. Webhooks are HMAC-verified with timestamp tolerance and deduplicated by event id. Retries reuse provider idempotency keys. |
| Maps | Google Maps and Mapbox geocoding, routed to each tenant's default provider, with a per-tenant result cache. |
| Analytics | `GET /analytics/sales` groups by day, week, month, or product, per currency. It is tenant-isolated, uses an index, is paginated, and has a statement timeout. |
| Outbound HTTP | One SSRF-safe client for all integrations: HTTPS only, a per-provider host allowlist, every resolved IP must be public, the connection is pinned to the validated IP (SNI and Host preserved), no redirects, and response size is capped. |
| Secrets | Envelope encryption through AWS KMS, Vault Transit, or a local KEK (dev only). A fresh DEK per secret is sealed with AES-256-GCM and bound to an encryption context. |
| Dedicated databases | `app_tenant_databases` stores encrypted credentials. Hosts are checked against network allowlists, and roles that are superuser or have BYPASSRLS are refused. The engine registry is bounded LRU and rotates by credential version. The dedicated database gets the platform's DDL ledger replayed in order, with checksums verified. Dynamic data is routed per request, and the same RLS policies apply inside the dedicated database. Adapters exist for PostgreSQL, MySQL, and Oracle; data routing for MySQL and Oracle is not done yet. |
| Schema management | Tables support create, PATCH (add or drop fields, change flags), and DELETE. Index changes use `CREATE`/`DROP INDEX CONCURRENTLY` outside transactions, and invalid indexes are cleaned up after a failure. Snapshot export and import are checksummed and additive, and import is blocked when the snapshot conflicts with the live schema. Dry runs are available. |
| Platform hardening | TOTP MFA for platform admins (required in production, replay-protected, secret encrypted). Platform and DDL routes can be disabled on request-serving deployments so the owner credential never reaches them. |

## Quick start

```bash
cp .env.example .env          # replace every CHANGE_ME
docker compose up --build     # postgres (roles bootstrapped), redis, migrations, api
docker compose exec api python -m app.cli create-platform-admin --email ops@example.com
```

The API is served at `http://localhost:8000/api/v1`. OpenAPI docs are at `/api/v1/docs` outside production. Change the prefix with `TC_API_PREFIX`.

A typical flow:

1. Platform admin:
   * `POST /platform/auth/login`
   * enroll MFA with `/platform/auth/mfa/setup` and `/confirm`
   * `POST /platform/tenants` to create a tenant
2. Tenant admin:
   * `POST /auth/login`
   * create warehouses, bins, and products (`/warehouses`, `/warehouses/{id}/bins`, `/products`)
   * receive stock (`/purchase-orders` → `/receive`, or `/stock/adjustments`)
3. Configure payments with `POST /payment-gateways`.
4. Run an order through its lifecycle:
   * `POST /orders` with an `Idempotency-Key`
   * `POST /orders/{id}/payments`
   * the provider webhook reaches `/webhooks/payments/{gateway_id}`
   * `POST /orders/{id}/transitions` with `{"to": "shipped"}`
5. Handle a return:
   * `POST /orders/{id}/returns`
   * `POST /returns/{id}/actions` with `approve`, then `receive`, then `refund`
6. `GET /analytics/sales?from=…&to=…&group_by=day`

## Development

```bash
pip install -e ".[dev]"
ruff check . && ruff format --check app tests
mypy app                                  # strict
pytest                                    # integration tests auto-skip without PostgreSQL/Redis
TC_REQUIRE_INTEGRATION=1 pytest           # what CI runs: fail instead of skip
```

Integration tests expect the database bootstrapped by `infra/postgres/01-roles.sh` and migrated with `alembic upgrade head`. The dedicated-database tests also need `TC_TEST_ADMIN_URL`, a superuser DSN used to create a throwaway database.

### Test coverage

187 tests pass against real PostgreSQL 16 and Redis 7, using the restricted application role. Beyond the Phase 1 coverage (tenant isolation, token forgery, injection, escalation, revocation, Redis outage), the Phase 2 tests cover:

* **Concurrency:** 20 concurrent checkouts against 5 units sell exactly 5. Concurrent negative adjustments never take stock below zero. Concurrent idempotent requests execute once.
* **Inventory:** ledger immutability and the CHECK constraints, idempotent and conflicting batch keys, transfers, partial and replayed PO receiving.
* **Orders:** order lifecycles, including custom ones and lifecycle validation. Clients cannot set prices.
* **Returns:** the RMA state machine, restocking, and refund bounds. Replaying a refund does not pay out twice.
* **Payments:** forged, tampered, stale, and duplicate webhooks. Stripe request shape (idempotency key, pinned host).
* **Maps and outbound HTTP:** geocoding cache, and provider errors that never leak keys.
* **Outbound guard:** the SSRF classifier and the blocked-before-send cases.
* **Crypto:** envelope encryption context binding and tamper detection, Vault and KMS providers, TOTP RFC 6238 vectors and replay rejection.
* **Platform:** MFA enrollment and enforcement, pepper rotation, the request-serving deployment exposing no platform routes.
* **Schema management:** PATCH with concurrent indexes and search-index rebuild, cleanup of an invalid index after a failure, DELETE guards, checksummed ledger, snapshot round trip and conflicts.
* **Dedicated databases:** guards (metadata IPs, allowlist, superuser roles, one database per tenant), routing and ledger replay, forced RLS inside the dedicated database, credential rotation replacing the pool, disable → 503.

Mutation checks were used to confirm several tests fail when the protection they cover is removed:

* the revocation tombstone
* the row-scope filter
* refresh-reuse detection
* webhook signature verification
* the ledger's row locks

No performance benchmarks are claimed. Load-testing scripts are Phase 3.

## Layout

```
app/
  api/            routers (auth, platform, tenant admin, data, catalog, commerce, integrations) + deps
  core/           settings, logging/redaction, error handling
  db/             engines, transaction-scoped tenant context, dedicated-DB dialect adapters
  cache/          Redis client, key registry, Lua helpers
  models/         control plane, identity, RBAC, schema registry, audit, inventory, orders, integrations
  security/       passwords, JWT, API keys, identifiers, envelope encryption, TOTP
  integrations/   SSRF-safe HTTP client, payment providers, map providers
  services/       auth, authz, rbac, tenants, schema/DDL, ledger, inventory, orders, rma, payments,
                  providers, maps, analytics, tenant_db, idempotency, rate limiting, audit
  dynamic/        table specs + DDL generation, query parsing, generic CRUD
  middleware/     request ID, body limit, security headers, access log
migrations/       Alembic: 0001 control plane, 0002 domain + integrations (tables, RLS, grants, triggers)
infra/postgres/   role bootstrap
docs/             SECURITY.md, DEPLOYMENT.md
```
