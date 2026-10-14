# Roadmap

The specification was split into three phases so that no security path ships as a stub. Each phase is complete and tested before the next begins.

## Phase 1: security core ✅

* Tenancy and RLS
* Auth: Argon2id, JWT rotation, MFA-ready claims
* API keys
* RBAC with row and field security
* Dynamic table creation and generic CRUD
* Idempotency and rate limiting
* Guardrails and health checks
* Docker, CI, and tests

## Phase 2: domain, integrations, enterprise routing ✅

| Spec section | Status |
|---|---|
| §1 Remaining control-plane tables | ✅ The following are built, all with RLS: `app_tenant_databases` (envelope-encrypted, rotation, bounded pools, dialect adapters), `app_payment_gateways`, `app_map_providers`, `app_order_statuses`, and `rma_requests`. |
| §1 Tables deferred to Phase 3 | `system_files`, `app_webhooks`, `app_notification_rules`, `delivery_tracking_pings`, and `system_user_devices` belong to the Phase 3 features that use them. |
| §2 Inventory, e-commerce, RMA, analytics | ✅ All of §2 except FCM, which is Phase 3. |
| §3 Dual-pepper API key rotation | ✅ |
| §4 Dynamic schema | ✅ PATCH, DELETE, snapshot export and import, concurrent indexes with invalid-index cleanup, dry runs, ledger checksums. |
| §6 Caching of payment and map configs | ✅ Only ciphertext is cached. |
| §8 SSRF protection for providers and database routing | ✅ |
| Platform hardening | ✅ TOTP MFA, and the platform/DDL routes and owner credential can be split off from request-serving deployments. |

## Phase 3: async, offboarding, observability

* **Workers**
  * Celery with exponential backoff, jitter, bounded retries, timeouts, and a dead-letter queue.
  * A transactional outbox.
  * Move dedicated-database schema fan-out to workers.
* **Webhooks and imports**
  * `app_webhooks` with signed payloads, event IDs, delivery tracking, and the SSRF-safe client.
  * `POST /import/excel/{table}` and `GET /tasks/{id}`.
  * `GET /qrcode/generate`.
* **Notifications**
  * FCM notification rules, `system_user_devices`, and `delivery_tracking_pings`.
  * Automatic order transitions on payment events through notification rules.
* **Offboarding**
  * GDPR/CCPA tenant offboarding: cascading purge, dedicated-pool teardown, Redis key scavenging, and cold-storage archiving of audit logs.
* **Dedicated databases**
  * Data routing for MySQL and Oracle, through dialect-specific DDL generation.
  * Routing of domain tables (inventory, orders) to dedicated databases.
* **Observability**
  * OpenTelemetry for FastAPI, SQLAlchemy, Redis, Celery, and httpx.
  * OTLP export through a collector to Jaeger.
  * Metrics for pools, cache, queues, and providers.
* **Load testing**
  * Reproducible k6 or Locust scripts. Results will be published only from real runs.
