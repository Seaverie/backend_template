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

## Phase 3: async, offboarding, observability ✅

| Spec section | Status |
|---|---|
| §1 Remaining tables | ✅ `system_files`, `app_webhooks`, `app_notification_rules`, `delivery_tracking_pings` and `system_user_devices`, plus `outbox_events`, `webhook_deliveries`, `notification_deliveries`, `system_tasks`, `system_dead_letters` and `tenant_archives`. All with RLS and least-privilege grants. |
| §2 FCM | ✅ Notification rules, device registration, FCM HTTP v1 with a service-account JWT, deactivation of unregistered tokens. |
| §2 Delivery tracking | ✅ `POST`/`GET /orders/{id}/delivery-pings`. |
| §7 Celery | ✅ `acks_late`, time limits, exponential backoff with jitter, bounded retries, dead letters with requeue, stale-task sweeper, beat schedule. |
| §7 Transactional outbox | ✅ Events written in the same transaction as the change; relay with `SKIP LOCKED`. |
| §7 Webhooks | ✅ Signed payloads, event ids, per-delivery durable retries, auto-disable, redelivery, SSRF checks at save and send time. |
| §7 Imports | ✅ `POST /import/excel/{table}` (CSV and XLSX), `GET /tasks/{id}`, idempotent re-runs, two modes. |
| §7 QR codes | ✅ `GET /qrcode/generate` (PNG or SVG). |
| Offboarding | ✅ Freeze, encrypted archive, dedicated-database teardown, purge across all shared and dynamic tables, Redis scavenging, erasure record. |
| §9 Observability | ✅ OpenTelemetry for FastAPI, SQLAlchemy, Redis, Celery and httpx; OTLP to a collector, Jaeger; pool, cache, outbound and job metrics; trace ids in logs; slow-query diagnostics. |
| §10 Load testing | ✅ Locust profile. No results are published; numbers must come from your own runs. |
| §10 Deployment | ✅ docker-compose adds worker, beat, collector and Jaeger. |

## Not built yet

These are known gaps, not hidden ones. Each is also listed in [docs/SECURITY.md](docs/SECURITY.md#known-limitations).

* **Dedicated databases**
  * Data routing for MySQL and Oracle (currently 501). These dialects have no RLS; isolation would rest on the database being single-tenant.
  * Routing of domain tables (inventory, orders, payments, returns) to dedicated databases.
  * Moving schema fan-out to dedicated databases into a background job.
  * An outbox inside the dedicated database, so `data.*` events there are atomic with the change.
* **Workflow**
  * Automatic order transitions driven by payment events.
* **Operations**
  * Queue-depth metrics (broker-specific; use a Redis/Celery exporter meanwhile).
  * Redis Cluster support for the rate-limit scripts (hash tags).
