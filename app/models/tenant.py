"""Control-plane tables: tenants and platform administrators.

These tables are not tenant-owned. ``app_tenants`` is readable by the runtime role (needed to resolve
a tenant slug at login), but an RLS policy allows writes only inside a platform-admin transaction.
"""

from __future__ import annotations

import enum
import uuid
from datetime import datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    Enum,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    func,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin, uuid_pk


class TenantStatus(enum.StrEnum):
    active = "active"
    suspended = "suspended"
    offboarding = "offboarding"


class IsolationMode(enum.StrEnum):
    shared = "shared"  # shared tables + RLS
    dedicated = "dedicated"  # enterprise database via app_tenant_databases (Phase 2)


class AppTenant(Base, TimestampMixin):
    __tablename__ = "app_tenants"

    id: Mapped[uuid.UUID] = uuid_pk()
    slug: Mapped[str] = mapped_column(String(63), nullable=False, unique=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    status: Mapped[TenantStatus] = mapped_column(
        Enum(TenantStatus, name="tenant_status", values_callable=lambda e: [m.value for m in e]),
        nullable=False,
        server_default=TenantStatus.active.value,
    )
    isolation_mode: Mapped[IsolationMode] = mapped_column(
        Enum(IsolationMode, name="tenant_isolation_mode", values_callable=lambda e: [m.value for m in e]),
        nullable=False,
        server_default=IsolationMode.shared.value,
    )
    # Tenant-adjustable, but clamped to the platform maximum at write and read time.
    rate_limit_per_min: Mapped[int | None] = mapped_column(Integer)
    # Monotonic counter bumped in the same transaction as any authorization change in the tenant.
    authz_version: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default="1")

    __table_args__ = (
        CheckConstraint("slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'", name="slug_format"),
        CheckConstraint("rate_limit_per_min IS NULL OR rate_limit_per_min > 0", name="rate_limit_positive"),
    )


class PlatformAdmin(Base, TimestampMixin):
    __tablename__ = "platform_admins"

    id: Mapped[uuid.UUID] = uuid_pk()
    email: Mapped[str] = mapped_column(String(320), nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")
    failed_logins: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # TOTP secret, envelope-encrypted (context: purpose=platform_mfa, id=<admin id>).
    mfa_secret_enc: Mapped[str | None] = mapped_column(Text)
    mfa_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    # Highest TOTP time step already accepted; codes at or below it are rejected (no replay).
    mfa_last_step: Mapped[int | None] = mapped_column(BigInteger)

    __table_args__ = (Index("uq_platform_admins_email_lower", func.lower(email), unique=True),)


class PlatformSession(Base):
    __tablename__ = "platform_sessions"

    id: Mapped[uuid.UUID] = uuid_pk()
    admin_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("platform_admins.id", ondelete="CASCADE"), nullable=False, index=True
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
