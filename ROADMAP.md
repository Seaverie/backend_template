# Roadmap

The specification was split into three phases so that no security path ships as a stub. Each phase is complete and tested before the next begins.

## Phase 1: security core (this repository) ✅

| Spec section | Status |
|---|---|
| §1 Control-plane models, Alembic, RLS | ✅ tenants, platform admins, users, roles, permissions, role/user links, sessions, refresh tokens, API keys, schema registry, migration ledger, and audit log. Other §1 tables come in Phases 2 and 3. |
| §3 Auth, multi-tenancy, API keys | ✅ complete |
| §4 Dynamic schema | 🟡 `POST /system/tables` with dry run, advisory lock, checksum, and audit. PATCH, DELETE, snapshots, and concurrent index builds come in Phase 2. |
| §5 Generic CRUD | ✅ All five endpoints are in place, along with filters, sorting, pagination, search, expansion, field permissions, audit, metadata cache, and idempotency. |
| §6 RBAC, row and field security, caching | ✅ All of §6 except caching of payment, map, and notification configuration, which arrives with those features. |
| §8 Rate limiting and guardrails | ✅ Rate limiting, request size, pagination caps, and timeouts are done. SSRF guards arrive with the first outbound integrations in Phase 2. |
| §9 Health | ✅ `/health` and `/ready`, structured logs, request IDs, slow-query log, and graceful shutdown. OpenTelemetry comes in Phase 3. |
| §10 Repo, Docker, CI, tests | ✅ Phase 1 scope. Load tests come in Phase 3. |

## Phase 2: domain and integrations

* **Inventory.** `app_warehouses`, `warehouse_bins`, `app_products`, and `app_product_variants`. An append-only `stock_movements` ledger with `SELECT … FOR UPDATE` balance rows and idempotent movement keys, plus oversell tests under concurrency.
* **Purchasing, orders, and RMA.** Purchase orders with items, orders with items, configurable `app_order_statuses`, and an RMA state machine with validated transitions, refunds, and restocking.
* **Analytics.** `/analytics/sales` with tenant-scoped aggregation, an index plan, and a statement timeout.
* **Providers.** `app_payment_gateways` and `app_map_providers` with a provider interface, KMS/Vault-encrypted credentials, and webhook signature verification. An SSRF-safe HTTP client blocks private ranges, pins DNS, and allowlists schemes and ports.
* **Enterprise databases.** `app_tenant_databases` with envelope encryption, a dialect-aware adapter registry (PostgreSQL, MySQL, Oracle), bounded per-tenant engine pools, credential rotation, and disposal. Documentation will state that RLS does not apply to MySQL or Oracle.
* **Dynamic schema.** PATCH and DELETE, snapshot export and import, `CREATE`/`DROP INDEX CONCURRENTLY` outside transactions, and rollback procedures.
* **Platform hardening.** MFA for platform admins, a separate admin deployment for DDL routes, and dual-pepper API key rotation.

## Phase 3: async, offboarding, and observability

* **Async processing.** Celery with backoff and jitter, bounded retries, timeouts, and a dead-letter queue. A transactional outbox, `app_webhooks` with signing and an SSRF guard, CSV and Excel imports, `/tasks/{id}`, and `/qrcode/generate`.
* **Notifications.** FCM notification rules, `system_user_devices`, and `delivery_tracking_pings`.
* **Offboarding.** GDPR/CCPA tenant offboarding: cascading purge, pool teardown, Redis key scavenging, and cold-storage audit archive.
* **Observability.** OpenTelemetry for FastAPI, SQLAlchemy, Redis, Celery, and httpx, exported through an OTLP collector to Jaeger, plus metrics.
* **Testing.** Reproducible load-test scripts (k6/Locust). Results will be published only from real runs.
