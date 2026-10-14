# Deployment

## Topology

```
LB / API gateway (TLS)  ->  API pods (uvicorn, stateless)  ->  PgBouncer (transaction mode)  ->  PostgreSQL 16
                                                           ->  Redis (Sentinel or managed): cache + broker
Celery workers (N)  ----------------------------------------^
Celery beat (exactly 1)
API + workers  ->  OpenTelemetry Collector  ->  Jaeger (traces), Prometheus (metrics)
```

* Run at least two API replicas. Set `FORWARDED_ALLOW_IPS` to the load balancer's address range so `X-Forwarded-For` is trusted only from it.
* Set `TC_RL_LOCAL_FALLBACK_DIVISOR` to roughly the replica count.
* For readiness and liveness probes, use `/ready` and `/health` respectively.
* On shutdown, SIGTERM makes uvicorn stop accepting connections and drain for 20 s. Lifespan shutdown then closes the DB and Redis pools. Set the pod `terminationGracePeriodSeconds` above 20.

## Deployments

Run two deployments from the same image:

| Deployment | Settings | Serves |
|---|---|---|
| `api` (scaled out) | `TC_ENABLE_PLATFORM_ROUTES=false`, no `TC_DDL_DATABASE_URL` | tenant APIs, public catalog, webhooks |
| `admin` (1-2 replicas, internal ingress only) | `TC_ENABLE_PLATFORM_ROUTES=true`, `TC_DDL_DATABASE_URL` set | `/platform/*`, `/system/*` |

The schema-owner credential then exists only on the admin deployment, which should be reachable only over VPN or a zero-trust proxy. Platform MFA is enforced either way.

## Background workers

Run from the same image:

| Process | Command | Replicas |
|---|---|---|
| worker | `celery -A app.workers.celery_app worker -Q default,deliveries,imports,maintenance --without-gossip --without-mingle` | 2 or more; scale `imports` separately if uploads are heavy (`-Q imports`) |
| beat | `celery -A app.workers.celery_app beat` | **exactly one**: it only schedules the relay, dispatchers, stale-task sweeper and cleanup, but two beats double every schedule |

* Workers need `TC_DATABASE_URL`, `TC_REDIS_URL` and `TC_CELERY_BROKER_URL`. Offboarding also needs `TC_DDL_DATABASE_URL` (owner role), so run it on a separate worker deployment consuming only `-Q maintenance` if you keep the owner credential off the general workers.
* Give the broker its own Redis database or instance, with persistence (AOF) enabled. Losing the broker loses only in-flight messages: task state is in PostgreSQL and the sweeper re-sends queued tasks.
* Set the pod termination grace period above `TC_CELERY_TASK_TIME_LIMIT_S` so a running task can finish; otherwise it is redelivered (`acks_late`) and re-runs idempotently.
* Watch `system_dead_letters` (`GET /platform/dead-letters`) and the `tenantcore.jobs` metric's `dead_letter` outcome.

## Observability

* Set `TC_OTEL_ENABLED=true` and `TC_OTEL_EXPORTER_ENDPOINT` to the collector (OTLP/HTTP, port 4318). `infra/otel/collector.yaml` is a starting config: traces to Jaeger, metrics on a Prometheus endpoint.
* Sampling: `TC_OTEL_SAMPLE_RATIO` for new root traces, parent-based otherwise.
* Metrics: request/DB/Redis/HTTP-client metrics from the instrumentations, plus `tenantcore.cache.operations` (hit/miss), `tenantcore.outbound.duration`, `tenantcore.jobs`, `tenantcore.db.pool.connections` and `tenantcore.db.dedicated_engines`.
* Logs are JSON with `trace_id`/`span_id` when a span is active, so logs and traces join.
* `GET /platform/diagnostics/slow-queries` reports the top statements from `pg_stat_statements` (if the extension is installed) and tables dominated by sequential scans.

## Load testing

`loadtest/locustfile.py` defines a mixed read/search/write/QR profile for one tenant. It contains no results: run it from a separate machine against a production-like stack and record your own numbers. Raise the test tenant's rate limits first, or the run measures the limiter.

## Database bootstrap

1. A DBA runs `infra/postgres/01-roles.sh` once, as a superuser. It creates `tc_owner` and `tc_app` and the database, and revokes PUBLIC privileges.
2. Migrations run as `tc_owner` (`TC_DDL_DATABASE_URL`) with `alembic upgrade head`, as a pre-deploy job.
3. The runtime connection uses `tc_app` (`TC_DATABASE_URL`). Only the admin deployment also gets `TC_DDL_DATABASE_URL` (`tc_owner`), which dynamic DDL uses; see Deployments above.
4. Bootstrap the first platform admin, either with `python -m app.cli seed` (reads `TC_SEED_ADMIN_EMAIL` / `TC_SEED_ADMIN_PASSWORD` from your secret store; idempotent) or with `python -m app.cli create-platform-admin --email …` (password from `TC_BOOTSTRAP_PASSWORD` or a prompt). In production MFA is required, so enroll it on first sign-in.

## Secrets

Inject secrets as environment variables from your secret manager. Use the Vault agent injector, the AWS Secrets Manager CSI driver, or External Secrets. Never bake them into images.

| Variable | Rotation |
|---|---|
| `TC_JWT_KEYS` / `TC_JWT_ACTIVE_KID` | Add the new key to the map and deploy. Switch `ACTIVE_KID` and deploy. After the refresh TTL (14 days by default), remove the old key. |
| API key peppers | Add the new pepper to `TC_API_KEY_PEPPERS` (e.g. `{"p2": "…"}`) and set `TC_API_KEY_ACTIVE_PEPPER=p2`. Existing keys keep verifying with the pepper recorded on their row and move to `p2` when rotated (`POST /api-keys/{id}/rotate`). Remove `p1` only after no key uses it (`SELECT count(*) FROM tenant_api_keys WHERE pepper_id = 'p1'`). |
| DB role passwords | `ALTER ROLE … PASSWORD`, then update the secret and roll the pods. The pool's `pool_pre_ping` and `pool_recycle` drop stale connections. |
| Redis password | Use ACL users and rotate them in the same way. |

### Envelope encryption

Set `TC_KMS_PROVIDER` to `vault` (Transit engine) or `aws` (KMS). The local provider is rejected in production.

* **Vault:** create a transit key (`vault write -f transit/keys/tenantcore`). The application token needs `update` on `transit/datakey/plaintext/tenantcore` and `transit/decrypt/tenantcore`.
* **AWS KMS:** the role needs `kms:GenerateDataKey` and `kms:Decrypt` on the key. A condition on `kms:EncryptionContext:purpose` can restrict it further.

To rotate the KEK:

* **Vault:** `vault write -f transit/keys/tenantcore/rotate`. Existing data keys still decrypt.
* **AWS:** enable automatic key rotation.

To re-wrap stored secrets under the new key version, update each provider or database credential through its API (`PUT …/secrets` or `PUT /platform/tenants/{id}/database`), which re-encrypts it with a fresh data key.

### Dedicated tenant databases

1. Create a database and a login role that **owns** it, as `NOSUPERUSER NOBYPASSRLS`.
2. Allow its network in `TC_TENANT_DB_ALLOWED_NETWORKS`.
3. `PUT /platform/tenants/{id}/database` with `ssl_mode: verify-full` and the CA bundle.
4. `POST …/database/sync`. This bootstraps the database, replays the DDL ledger, and switches the tenant to dedicated routing. The switch is refused if the tenant still has rows in the shared dynamic tables.

Later schema changes are applied automatically. If one fails, the tenant's data endpoints return 503 until `POST …/sync` succeeds.

## Tenant offboarding

* Production should use `TC_ARCHIVE_STORE=s3` with a bucket that has versioning, Object Lock (compliance mode) for your audit-retention period, and a KMS key. The worker role needs `s3:PutObject` and `kms:GenerateDataKey` only.
* The local store writes envelope-encrypted files to `TC_ARCHIVE_DIR` and suits single-node development only.

## Backup and recovery

* PostgreSQL: continuous WAL archiving plus daily base backups (for example pgBackRest or a managed PITR service).
  * Test restores monthly to an isolated instance.
  * Then run `alembic current` and `pytest tests/integration/test_rls.py` against the restored copy to confirm policies and grants survived.
* Redis holds only caches, rate-limit state, and idempotency records, so losing it does not lose data.
  * During the 24 h idempotency window, a Redis loss means a retried checkout can create a second order.
  * Payments and refunds are deduplicated in the database as well: provider idempotency keys, unique provider references, and `payment_refunds`.
  * Stock batches are deduplicated in the database by `batch_key`.
* Dedicated tenant databases are the tenant's own infrastructure. Back them up on the same schedule.
  * After a restore, run `POST /platform/tenants/{id}/database/sync`. The `tenantcore_schema_sync` table in the dedicated database records which ledger entries it already has, so only missing ones are applied.
* The dynamic-schema history is in `system_schema_migrations`, with the exact statements and a SHA-256 checksum. A restored database can be compared with `app_tables` and with the live catalog.

## Supply chain

* CI runs `pip-audit` on every push.
* Pin production dependencies with a lock file: `pip-compile` or `uv lock` from `pyproject.toml`.
* Rebuild images weekly to pick up base-image patches.
* The image runs as a non-root UID (10001) with no shell entrypoint.
