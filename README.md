# TenantCore

A multi-tenant FastAPI backend built on Python 3.11+, SQLAlchemy 2.0 (async), and asyncpg. It covers:

* PostgreSQL row-level security for tenant isolation
* dedicated databases for enterprise tenants
* a dynamic schema engine, plus tenant-scoped RBAC and API keys
* an inventory ledger, orders and returns
* payment and map provider integrations
* Redis-backed rate limiting
* Celery workers, a transactional outbox, signed webhooks, push notifications and file imports
* tenant offboarding (archive, purge, cache scavenging) and OpenTelemetry tracing and metrics

**Status: all three phases are implemented and tested.** [ROADMAP.md](ROADMAP.md) lists what is built and the known gaps that remain.

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

### Async, offboarding, observability (Phase 3)

| Area | Implementation |
|---|---|
| Workers | Celery with `acks_late`, `reject_on_worker_lost`, prefetch 1, soft and hard time limits, exponential backoff with jitter and bounded retries. Exhausted tasks go to `system_dead_letters` and can be requeued. Task state lives in `system_tasks` under RLS; messages carry ids only. A sweeper re-sends tasks stuck in `queued` (for example after a broker outage). |
| Outbox | Domain events are written to `outbox_events` in the same transaction as the change. A relay claims them with `FOR UPDATE SKIP LOCKED` and creates one durable delivery row per target. Delivery is at-least-once; receivers deduplicate on the event id. |
| Webhooks | Per-tenant endpoints with event filters, a signing secret shown once and stored envelope-encrypted, HMAC-SHA256 signatures with timestamps, retries with backoff and jitter, auto-disable on `410` or repeated failure, delivery history and redelivery. The SSRF guard runs when a URL is saved and on every send. |
| Push notifications | Rules map events to roles; devices are registered per user with encrypted tokens. FCM HTTP v1 with a service-account JWT; unregistered tokens are deactivated. |
| Imports | `POST /import/excel/{table}` accepts CSV or XLSX with size, row and zip-bomb limits. Headers are checked against the user's writable fields, permissions are re-checked when the job runs, and rows get deterministic ids so a re-run never duplicates. Modes: `all_or_nothing` or `skip_invalid`. Progress via `GET /tasks/{id}`. |
| QR codes and tracking | `GET /qrcode/generate` (PNG/SVG); delivery pings per order with time-window validation. |
| Offboarding | `POST /platform/tenants/{id}/offboard`: freeze, encrypted audit archive (local or S3 with SSE-KMS), optional teardown of a dedicated database's TenantCore objects, purge of every tenant row (shared and dynamic tables) through a narrowly scoped trigger exemption, Redis scavenging, and an erasure record. Each step is idempotent. |
| Observability | OpenTelemetry for FastAPI, SQLAlchemy, Redis, Celery and httpx, exported over OTLP to a collector and Jaeger. Metrics for DB pools, cache hit/miss, outbound latency and job outcomes. Trace ids in JSON logs. Slow-query diagnostics endpoint. |
| Load testing | A Locust profile in `loadtest/`. No results are included. |

## Quick start

```bash
cp .env.example .env          # replace every CHANGE_ME
docker compose up --build     # postgres, redis, migrations, api, worker, beat, otel-collector, jaeger
docker compose exec api python -m app.cli create-platform-admin --email ops@example.com
```

The API is served at `http://localhost:8000/api/v1`. OpenAPI docs are at `/api/v1/docs` outside production. Change the prefix with `TC_API_PREFIX`. Traces appear in Jaeger at `http://localhost:16686`.

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
7. Async features:
   * `POST /webhooks` with a URL and event types; keep the returned `signing_secret`
   * `POST /import/excel/{table}` with a CSV/XLSX file, then poll `GET /tasks/{id}`
   * `POST /notification-rules` and `POST /devices` for push notifications

## Admin frontend

`frontend/` contains a responsive Vue 3 admin console for this API (dashboard, orders, users and roles, webhooks, platform tenants). It runs on sample data out of the box; see [frontend/README.md](frontend/README.md).

## Development

```bash
pip install -e ".[dev]"
ruff check . && ruff format --check app tests
mypy app                                  # strict
pytest                                    # integration tests auto-skip without PostgreSQL/Redis
TC_REQUIRE_INTEGRATION=1 pytest           # what CI runs: fail instead of skip
```

Integration tests expect the database bootstrapped by `infra/postgres/01-roles.sh` and migrated with `alembic upgrade head`. The dedicated-database and offboarding tests also need `TC_TEST_ADMIN_URL`, a superuser DSN used to create a throwaway database and to count rows without RLS. Set `TC_CELERY_BROKER_URL` to a spare Redis database: the worker test starts a real Celery worker and flushes that database first.

### Test coverage

210 tests pass against real PostgreSQL 16 and Redis 7, using the restricted application role, with none skipped. Phase 1 covers tenant isolation, token forgery, injection, privilege escalation, revocation and Redis outages. Phase 2 covers concurrency (20 concurrent checkouts against 5 units sell exactly 5), the inventory ledger, order lifecycles, the RMA state machine, payment webhooks, outbound SSRF guards, crypto, platform MFA, schema management and dedicated databases. The Phase 3 tests cover:

* **Outbox and webhooks:** events exist only for committed changes; signed deliveries verified with the secret; retries, dead letters, `410` handling; tenant isolation of endpoints and deliveries; a hostname re-pointed to a private address after saving is blocked before sending.
* **Push:** FCM token exchange with a generated RSA service account, message shape, deactivation of unregistered tokens.
* **Imports:** CSV and XLSX end to end, both modes, header and permission validation, idempotency keys, size limits, re-runs without duplicates.
* **Workers:** an import through a real Celery worker process and the Redis broker; a non-retryable failure landing in the dead-letter table; stale-task sweeping; cleanup.
* **Offboarding:** zero rows left for the tenant in every shared and dynamic table (counted as superuser, bypassing RLS); the other tenant's counts unchanged; JWT, refresh token, cached API key and login all rejected; no Redis key mentions the tenant; the archive decrypts, matches its checksum and contains exactly the tenant's audit rows; a crash mid-run followed by a retry completes with one archive; dedicated-database teardown; the append-only exemption refused for other tenants and for `TRUNCATE`.
* **Observability:** with an in-memory exporter, server, SQL and Redis spans share one trace; health checks are not traced; no password, hash or token appears in any span attribute; SQL and Redis spans carry no bound values.

Mutation checks were used in Phases 1 and 2 to confirm that several tests fail when the protection they cover is removed: the revocation tombstone, the row-scope filter, refresh-reuse detection, webhook signature verification and the ledger's row locks.

No performance benchmarks are claimed. `loadtest/locustfile.py` was only smoke-run locally to check that the script works.

## Layout

```
app/
  api/            routers (auth, platform, tenant admin, data, catalog, commerce, integrations) + deps
  core/           settings, logging/redaction, error handling
  db/             engines, transaction-scoped tenant context, dedicated-DB dialect adapters
  cache/          Redis client, key registry, Lua helpers
  models/         control plane, identity, RBAC, schema registry, audit, inventory, orders, integrations
  security/       passwords, JWT, API keys, identifiers, envelope encryption, TOTP
  integrations/   SSRF-safe HTTP client, payment providers, map providers, FCM
  services/       auth, authz, rbac, tenants, schema/DDL, ledger, inventory, orders, rma, payments,
                  providers, maps, analytics, tenant_db, idempotency, rate limiting, audit,
                  outbox, webhooks, notifications, imports, deliveries, offboarding
  workers/        Celery app, task wrappers (ReliableTask), jobs (relay, dispatch, sweeper, cleanup)
  observability.py  OpenTelemetry setup and custom metrics
  dynamic/        table specs + DDL generation, query parsing, generic CRUD
  middleware/     request ID, body limit, security headers, access log
migrations/       Alembic: 0001 control plane, 0002 domain + integrations, 0003 async ops + offboarding
infra/postgres/   role bootstrap
infra/otel/       OpenTelemetry Collector config
loadtest/         Locust profile
docs/             SECURITY.md, DEPLOYMENT.md
```
