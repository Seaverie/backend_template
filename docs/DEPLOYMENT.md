# Deployment

## Topology

```
LB / API gateway (TLS)  ->  API pods (uvicorn, stateless)  ->  PgBouncer (transaction mode)  ->  PostgreSQL 16
                                                           ->  Redis (Sentinel or managed)
```

* Run at least two API replicas. Set `FORWARDED_ALLOW_IPS` to the load balancer's address range so `X-Forwarded-For` is trusted only from it.
* Set `TC_RL_LOCAL_FALLBACK_DIVISOR` to roughly the replica count.
* For readiness and liveness probes, use `/ready` and `/health` respectively.
* On shutdown, SIGTERM makes uvicorn stop accepting connections and drain for 20 s. Lifespan shutdown then closes the DB and Redis pools. Set the pod `terminationGracePeriodSeconds` above 20.

## Database bootstrap

1. A DBA runs `infra/postgres/01-roles.sh` once, as a superuser. It creates `tc_owner` and `tc_app` and the database, and revokes PUBLIC privileges.
2. Migrations run as `tc_owner` (`TC_DDL_DATABASE_URL`) with `alembic upgrade head`, as a pre-deploy job.
3. The API's runtime connection uses `tc_app` (`TC_DATABASE_URL`). Dynamic DDL uses `TC_DDL_DATABASE_URL`, so the API needs both URLs. To keep the owner credential away from request-serving pods, run a separate admin deployment for `/system/*` routes and leave `TC_DDL_DATABASE_URL` unset elsewhere. Splitting the routes this way is a Phase 2 item.
4. Bootstrap the first platform admin with `python -m app.cli create-platform-admin --email …`. The password comes from `TC_BOOTSTRAP_PASSWORD` or a prompt.

## Secrets

Inject secrets as environment variables from your secret manager. Use the Vault agent injector, the AWS Secrets Manager CSI driver, or External Secrets. Never bake them into images.

| Variable | Rotation |
|---|---|
| `TC_JWT_KEYS` / `TC_JWT_ACTIVE_KID` | Add the new key to the map and deploy. Switch `ACTIVE_KID` and deploy. After the refresh TTL (14 days by default), remove the old key. |
| `TC_API_KEY_PEPPER` | Rotation invalidates every API key, so treat it as an incident-response action. Dual-pepper support is on the roadmap. |
| DB role passwords | `ALTER ROLE … PASSWORD`, then update the secret and roll the pods. The pool's `pool_pre_ping` and `pool_recycle` drop stale connections. |
| Redis password | Use ACL users and rotate them in the same way. |

Phase 2 adds KMS/Vault envelope encryption for per-tenant database credentials in `app_tenant_databases`.

## Backup and recovery

* PostgreSQL: continuous WAL archiving plus daily base backups (for example pgBackRest or a managed PITR service).
  * Test restores monthly to an isolated instance.
  * Then run `alembic current` and `pytest tests/integration/test_rls.py` against the restored copy to confirm policies and grants survived.
* Redis holds only caches, rate-limit state, and idempotency records, so losing it does not lose data. During the 24 h idempotency window, a Redis loss means retried requests can execute again. Phase 2 business keys in the database cover this.
* The dynamic-schema history is in `system_schema_migrations`, with the exact statements and a SHA-256 checksum. A restored database can be compared with `app_tables` and with the live catalog.

## Supply chain

* CI runs `pip-audit` on every push.
* Pin production dependencies with a lock file: `pip-compile` or `uv lock` from `pyproject.toml`.
* Rebuild images weekly to pick up base-image patches.
* The image runs as a non-root UID (10001) with no shell entrypoint.
