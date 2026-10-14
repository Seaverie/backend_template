"""Shared test configuration.

Unit tests need only these environment defaults. Integration tests additionally need PostgreSQL
(migrated, with the tc_owner/tc_app roles) and Redis; they are skipped when either is unreachable.
"""

from __future__ import annotations

import json
import os
import uuid

_DEFAULTS = {
    "TC_ENVIRONMENT": "test",
    "TC_DATABASE_URL": "postgresql+asyncpg://tc_app:app_dev_pw@127.0.0.1:5432/tenantcore",
    "TC_DDL_DATABASE_URL": "postgresql+asyncpg://tc_owner:owner_dev_pw@127.0.0.1:5432/tenantcore",
    "TC_REDIS_URL": "redis://127.0.0.1:6379/15",
    "TC_JWT_KEYS": json.dumps(
        {"k1": "dev-only-test-key-0123456789abcdef0123", "k0": "dev-only-old-key-0123456789abcdef01234"}
    ),
    "TC_JWT_ACTIVE_KID": "k1",
    "TC_API_KEY_PEPPER": "dev-only-test-pepper-0123456789abcdef0123",
    "TC_LOG_JSON": "false",
    "TC_LOG_LEVEL": "WARNING",
    # Cheap hashing in tests only (production defaults are enforced by Settings minimums).
    "TC_ARGON2_TIME_COST": "2",
    "TC_ARGON2_MEMORY_KIB": "19456",
    "TC_ARGON2_PARALLELISM": "1",
    "TC_RL_PRINCIPAL_PER_MIN": "100000",
    "TC_RL_TENANT_DEFAULT_PER_MIN": "100000",
    "TC_RL_TENANT_MAX_PER_MIN": "200000",
    "TC_RL_PLATFORM_PER_MIN": "1000000",
    "TC_RL_LOGIN_PER_MIN": "1000",
}
for k, v in _DEFAULTS.items():
    os.environ.setdefault(k, v)
# Isolate every run's Redis keys so runs never see each other's cache or rate-limit state.
os.environ["TC_REDIS_KEY_PREFIX"] = f"tctest-{uuid.uuid4().hex[:8]}"
