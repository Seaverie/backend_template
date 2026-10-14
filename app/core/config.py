"""Application settings.

All secrets are typed as ``SecretStr`` so they never appear in ``repr()`` or logs. In production the
values are injected by the platform's secret manager (Vault agent / AWS Secrets Manager CSI driver) as
environment variables; Phase 2 adds direct KMS/Vault envelope decryption for tenant DB credentials.
"""

from __future__ import annotations

import json
from functools import lru_cache
from typing import Any, Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_MIN_SECRET_BYTES = 32
_DEV_MARKER = "dev-only"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="TC_", env_file=".env", extra="ignore")

    environment: Literal["dev", "test", "staging", "production"] = "dev"
    service_name: str = "tenantcore"
    api_prefix: str = "/api/v1"
    log_level: str = "INFO"
    log_json: bool = True

    # --- Databases -------------------------------------------------------------------------------
    # Runtime connections use a non-superuser role WITHOUT BYPASSRLS.
    database_url: SecretStr
    # DDL connections (dynamic schema, migrations) use the schema-owner role. Only platform-admin
    # endpoints ever touch this engine.
    ddl_database_url: SecretStr
    # Name of the runtime role that dynamic DDL grants DML to (must match the role in database_url).
    app_db_role: str = Field("tc_app", pattern=r"^[a-z_][a-z0-9_]{0,62}$")
    db_pool_size: int = Field(10, ge=1, le=200)
    db_max_overflow: int = Field(10, ge=0, le=200)
    db_pool_timeout_s: float = Field(5.0, gt=0, le=60)
    db_pool_recycle_s: int = Field(1800, ge=60)
    db_connect_timeout_s: float = Field(5.0, gt=0, le=60)
    db_statement_timeout_ms: int = Field(5000, ge=100, le=600_000)
    db_lock_timeout_ms: int = Field(2000, ge=50, le=60_000)
    db_idle_in_tx_timeout_ms: int = Field(15_000, ge=1000)
    # When running behind PgBouncer in transaction mode asyncpg's prepared-statement cache must be off.
    db_pgbouncer_mode: bool = False
    slow_query_ms: int = Field(200, ge=1)

    # --- Redis -----------------------------------------------------------------------------------
    redis_url: SecretStr = SecretStr("redis://localhost:6379/0")
    redis_timeout_s: float = Field(0.25, gt=0, le=5)
    redis_key_prefix: str = "tc"

    # --- Auth ------------------------------------------------------------------------------------
    jwt_issuer: str = "tenantcore"
    jwt_audience_tenant: str = "tenantcore:tenant"
    jwt_audience_platform: str = "tenantcore:platform"
    # JSON object {"kid": "secret", ...}. Multiple keys allow rotation: sign with the active kid,
    # verify with any listed kid until old tokens expire.
    jwt_keys: dict[str, SecretStr]
    jwt_active_kid: str
    jwt_leeway_s: int = Field(10, ge=0, le=60)
    access_ttl_s: int = Field(600, ge=60, le=3600)
    refresh_ttl_s: int = Field(14 * 24 * 3600, ge=3600)
    platform_access_ttl_s: int = Field(900, ge=60, le=3600)

    api_key_pepper: SecretStr
    api_key_cache_ttl_s: int = Field(60, ge=5, le=600)
    api_key_tombstone_ttl_s: int = Field(3600, ge=60)

    argon2_time_cost: int = Field(3, ge=2)
    argon2_memory_kib: int = Field(65_536, ge=19_456)
    argon2_parallelism: int = Field(4, ge=1)

    max_failed_logins: int = Field(10, ge=3)
    lockout_s: int = Field(900, ge=60)

    # --- Caching ---------------------------------------------------------------------------------
    perm_cache_ttl_s: int = Field(300, ge=10, le=3600)
    meta_cache_ttl_s: int = Field(300, ge=10, le=3600)
    tenant_cache_ttl_s: int = Field(60, ge=5, le=600)
    session_revocation_ttl_s: int = Field(3600, ge=60)

    # --- Guardrails ------------------------------------------------------------------------------
    max_request_bytes: int = Field(1_048_576, ge=1024)
    max_page_size: int = Field(200, ge=1, le=1000)
    default_page_size: int = Field(50, ge=1)
    max_offset: int = Field(10_000, ge=0)
    max_filters: int = Field(20, ge=1)
    idempotency_ttl_s: int = Field(86_400, ge=60)
    idempotency_max_body_bytes: int = Field(262_144, ge=1024)

    # --- Rate limiting (requests per minute) -----------------------------------------------------
    rl_platform_per_min: int = Field(60_000, ge=1)
    rl_tenant_default_per_min: int = Field(1_200, ge=1)
    rl_tenant_max_per_min: int = Field(12_000, ge=1)
    rl_principal_per_min: int = Field(300, ge=1)
    rl_login_per_min: int = Field(10, ge=1)
    # When Redis is unreachable each instance enforces limit / divisor locally (approximate).
    rl_local_fallback_divisor: int = Field(4, ge=1)

    @field_validator("jwt_keys", mode="before")
    @classmethod
    def _parse_jwt_keys(cls, v: Any) -> Any:
        if isinstance(v, str):
            return json.loads(v)
        return v

    @field_validator("api_prefix")
    @classmethod
    def _norm_prefix(cls, v: str) -> str:
        v = "/" + v.strip("/")
        return "" if v == "/" else v

    @model_validator(mode="after")
    def _validate_secrets(self) -> Settings:
        if self.jwt_active_kid not in self.jwt_keys:
            raise ValueError("TC_JWT_ACTIVE_KID must be a key of TC_JWT_KEYS")
        for kid, secret in self.jwt_keys.items():
            if len(secret.get_secret_value().encode()) < _MIN_SECRET_BYTES:
                raise ValueError(f"JWT key {kid!r} must be at least {_MIN_SECRET_BYTES} bytes")
        if len(self.api_key_pepper.get_secret_value().encode()) < _MIN_SECRET_BYTES:
            raise ValueError(f"TC_API_KEY_PEPPER must be at least {_MIN_SECRET_BYTES} bytes")
        if self.rl_tenant_default_per_min > self.rl_tenant_max_per_min:
            raise ValueError("default tenant rate limit exceeds platform maximum")
        if self.environment == "production":
            secrets = [s.get_secret_value() for s in self.jwt_keys.values()]
            secrets.append(self.api_key_pepper.get_secret_value())
            if any(_DEV_MARKER in s for s in secrets):
                raise ValueError("development secrets must not be used in production")
        return self


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
