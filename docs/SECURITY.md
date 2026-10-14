# Security design

## Trust boundaries

* **Tenant identity** comes only from verified credentials: the signed JWT `tid` claim, or the API key's database row. Headers, query parameters, and bodies are never used to select a tenant. Unknown query parameters such as `tenant_id` are rejected with 422.
* **Platform vs. tenant** use separate JWT audiences (`tenantcore:platform` vs `tenantcore:tenant`), separate session tables, and separate login endpoints. Tenant admins hold every tenant permission and none of the platform ones.
* **Runtime vs. DDL database roles.**
  * `tc_app` serves requests. It has DML only on explicitly granted tables, plus `NOSUPERUSER` and `NOBYPASSRLS`.
  * `tc_owner` owns the schema and runs migrations and dynamic DDL. Only platform-admin endpoints and the CLI use it.

## Row-level security

Every tenant-owned table, static or dynamic, has `ENABLE` and `FORCE ROW LEVEL SECURITY`. FORCE applies the policies to the table owner too, and each table has this policy:

```sql
USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id())
```

* `app_current_tenant_id()` is `STABLE`. It reads `current_setting('app.tenant_id', true)`, which is stable within a statement. The planner evaluates it once per statement and can use the `(tenant_id, …)` indexes. It is not marked `IMMUTABLE` or `LEAKPROOF`.
* Context is set as the first statement of every transaction with `set_config(name, value, true)`, which is the function form of `SET LOCAL`. It is discarded at COMMIT or ROLLBACK, so:
  * the SQLAlchemy pool (`reset_on_return='rollback'`) and PgBouncer in **transaction** mode cannot carry one tenant's context into another's transaction;
  * code that keeps using a session after committing runs with **no** context, so RLS returns zero rows and rejects writes. This fails closed, and `tests/integration/test_rls.py` covers it.
* PgBouncer: set `TC_DB_PGBOUNCER_MODE=true`. This disables asyncpg's prepared-statement cache and connection startup parameters. Session-mode pooling is not required.
* **Cross-tenant references.**
  * Child tables reference parents with composite `(tenant_id, id)` foreign keys.
  * FK checks bypass RLS by design, but the referenced key includes the tenant, so a reference to another tenant's row cannot match.
  * Dynamic `reference` fields use the same pattern.
* **Narrow exceptions.** Each is limited to the minimum it needs.
  * `tenant_api_keys` has an extra SELECT-only policy that exposes exactly the one row whose `key_id` is being authenticated (`app.api_key_lookup`).
  * `app_tenants` is readable so tenant slugs can be resolved at login. Writes are allowed only in platform transactions. A tenant may update its own row, but trigger `app_tenants_guard` blocks changes to `slug`, `status`, `isolation_mode`, and `id`, and blocks any decrease of `authz_version`.
  * `system_audit_logs` rows with `tenant_id IS NULL` (platform events) are visible only in platform transactions.
* `app.is_platform` is a transaction-local setting that the application sets only on platform-admin code paths. It is defense in depth against application bugs. It is not a boundary against an attacker who can run arbitrary SQL as `tc_app`; nothing in the application lets one.
* **Not applicable to MySQL or Oracle.** PostgreSQL RLS has no equivalent there. Phase 2's dedicated-database adapters must enforce isolation through a separate database per tenant and must not assume RLS.

## SQL injection

* Dynamic DDL goes through several layers:
  * The `TableSpec` and `FieldSpec` Pydantic models use `extra="forbid"`.
  * Field types come from an allowlist.
  * Identifiers must match `^[a-z][a-z0-9_]{0,62}$`, which is the spec's `^[a-zA-Z0-9_]{1,64}$` tightened. Reserved words, `pg_` prefixes, and system-column names are rejected.
  * Enum values must match `^[A-Za-z0-9_-]{1,64}$`.
  * DDL is compiled from SQLAlchemy `Table` objects, which quote every identifier. The only hand-written statements are the RLS and GRANT templates, and they interpolate only identifiers that are already validated and quoted.
  * There are no client-supplied defaults, CHECK expressions, or SQL fragments.
* Dynamic CRUD builds SQLAlchemy expressions with bound parameters only:
  * Table names resolve through the registry to `tenant_data.dt_<name>`, so control-plane tables are unreachable.
  * Filter operators come from a per-type allowlist, and values are coerced to the column's type before binding.
  * LIKE wildcards in user input are escaped.

## Credentials

| Secret | Storage |
|---|---|
| Passwords | Argon2id (t=3, m=64 MiB, p=4 by default; minimums enforced in settings), rehash-on-login when parameters change. Length bounded to 256 to cap hashing cost. |
| API keys | HMAC-SHA256(pepper, key_id:secret). 256-bit random secret, so a slow KDF adds nothing. The pepper lives in the secret manager, not the DB, so a DB dump alone cannot verify or brute-force keys. |
| JWT keys | `TC_JWT_KEYS` JSON map; `kid` header selects the key. |
| Refresh tokens | JWT whose `jti` is a DB row; single use; reuse revokes the session. |

Secrets are `SecretStr` and never appear in `repr` or logs. The log processor also redacts by key name (`password`, `token`, `secret`, `authorization`, …) and by value pattern (bearer tokens, JWTs, API keys, and credentials embedded in URLs). SQLAlchemy engines run with `hide_parameters=True`.

## Cache consistency windows

| Cached item | Invalidation | Worst case if Redis write fails |
|---|---|---|
| API key metadata (TTL 60 s) | Revocation writes a tombstone. Populating uses `SET NX`, so a racing stale reader loses. | 60 s (`TC_API_KEY_CACHE_TTL_S`) |
| Permission matrix (TTL 300 s) | Key includes `authz_version`, which is bumped in the same transaction as the change and then published with set-if-greater. | 30 s (version key TTL) |
| Table metadata (TTL 300 s) | Key includes the schema version, bumped in the DDL transaction. | 30 s |
| Tenant status / rate limit (TTL 60 s) | `DEL` after commit | 60 s |
| Session revocation marker | Written at logout and on reuse detection. | Access-token TTL (10 min) only if Redis loses the marker *and* stays up. When Redis is unreachable, the DB is checked instead. |

With Redis healthy, every invalidation applies on the next request. The tests verify this for API key revocation, permission removal, user deactivation, logout, and tenant suspension. Invalidation is not "guaranteed" beyond these documented windows.

## Redis outage behaviour

| Feature | Behaviour | Rationale |
|---|---|---|
| JWT session check, API key validation, permissions, tenant status, metadata | Fall back to PostgreSQL. | Correct but slower, never fails open. |
| Rate limiting | Per-process GCRA with limits divided by `TC_RL_LOCAL_FALLBACK_DIVISOR`. | Stays enforced, though approximately. Rejecting everything would turn a cache outage into a full outage. |
| Idempotency keys | 503 for any request that sends or requires a key. | Without the store, a retried financial write could execute twice. |
| `/ready` | Reports `redis: false` but stays ready. | Every feature above has a defined degraded mode. |

`tests/integration/test_redis_outage.py` exercises each row.

## Idempotency semantics

The store does not provide exactly-once execution. It guarantees the following:

* A retry with the same key and body after completion replays the stored response.
* A concurrent duplicate gets 409.
* A handler failure or a 5xx response releases the key.

If the response cannot be stored after the operation commits (Redis fails at that instant), the pending marker still blocks retries for 120 s. After that, a retry would execute again. Domain services in Phase 2 add database-level uniqueness on business keys (order numbers, payment intent IDs) for true deduplication.

## Known limitations (Phase 1)

* Platform admins have no MFA yet. Put platform endpoints behind a VPN or zero-trust proxy until Phase 2 adds TOTP/WebAuthn.
* JWTs use HS256 with symmetric keys. Move to EdDSA if third parties must verify tokens.
* `last_used_at` on API keys is not yet updated, to avoid a write on every request.
* Dynamic schema supports **create** only. PATCH, DELETE, snapshots, and `CREATE INDEX CONCURRENTLY` are Phase 2.
* Rate-limit Lua scripts need every key on one Redis node. Use hash tags if you move to Redis Cluster.
