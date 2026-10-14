"""Payment gateways, payments, map providers, and dedicated tenant databases.

Secrets (API keys, webhook signing secrets, database passwords) are stored only as envelope-encrypted
blobs (``*_enc``), bound to tenant, purpose and row id. ``public_config`` holds non-secret settings.
"""

from __future__ import annotations

import enum
import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    Enum,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin, tenant_fk, uuid_pk


def _enum(e: type[enum.Enum], name: str) -> Enum:
    return Enum(e, name=name, values_callable=lambda x: [m.value for m in x])


class PaymentProviderKind(enum.StrEnum):
    stripe = "stripe"
    sandbox = "sandbox"  # in-process provider for development/tests; refused in production


class PaymentStatus(enum.StrEnum):
    pending = "pending"
    succeeded = "succeeded"
    failed = "failed"
    refunded = "refunded"
    partially_refunded = "partially_refunded"


class MapProviderKind(enum.StrEnum):
    google = "google"
    mapbox = "mapbox"


class DbDialect(enum.StrEnum):
    postgresql = "postgresql"
    mysql = "mysql"
    oracle = "oracle"


class DbSslMode(enum.StrEnum):
    disable = "disable"
    require = "require"
    verify_full = "verify-full"


class TenantDbStatus(enum.StrEnum):
    pending = "pending"
    active = "active"
    disabled = "disabled"


class AppPaymentGateway(Base, TimestampMixin):
    __tablename__ = "app_payment_gateways"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    provider: Mapped[PaymentProviderKind] = mapped_column(
        _enum(PaymentProviderKind, "payment_provider"), nullable=False
    )
    secrets_enc: Mapped[str] = mapped_column(Text, nullable=False)
    public_config: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, server_default="{}")
    is_default: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")
    secret_version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "name"),
        Index(
            "uq_app_payment_gateways_default", "tenant_id", unique=True, postgresql_where=text("is_default")
        ),
    )


class AppPayment(Base, TimestampMixin):
    __tablename__ = "app_payments"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    order_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    gateway_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    provider_ref: Mapped[str] = mapped_column(String(255), nullable=False)
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False)
    amount_refunded: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False, server_default="0")
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    status: Mapped[PaymentStatus] = mapped_column(
        _enum(PaymentStatus, "payment_status"), nullable=False, server_default="pending"
    )

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "gateway_id", "provider_ref"),
        ForeignKeyConstraint(
            ["tenant_id", "order_id"], ["app_orders.tenant_id", "app_orders.id"], ondelete="RESTRICT"
        ),
        ForeignKeyConstraint(
            ["tenant_id", "gateway_id"],
            ["app_payment_gateways.tenant_id", "app_payment_gateways.id"],
            ondelete="RESTRICT",
        ),
        CheckConstraint("amount > 0 AND amount_refunded >= 0 AND amount_refunded <= amount", name="amounts"),
        Index("ix_app_payments_tenant_order", "tenant_id", "order_id"),
    )


class PaymentRefund(Base):
    """One row per refund the provider accepted. ``(payment, idempotency_key)`` makes retries no-ops;
    the payment's ``amount_refunded`` is recomputed as the max of its current value and the sum here."""

    __tablename__ = "payment_refunds"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    payment_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    provider_ref: Mapped[str] = mapped_column(String(255), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(200), nullable=False)
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (
        UniqueConstraint("tenant_id", "payment_id", "idempotency_key"),
        UniqueConstraint("tenant_id", "payment_id", "provider_ref"),
        ForeignKeyConstraint(
            ["tenant_id", "payment_id"], ["app_payments.tenant_id", "app_payments.id"], ondelete="RESTRICT"
        ),
        CheckConstraint("amount > 0", name="amount_positive"),
    )


class PaymentWebhookEvent(Base):
    """Every verified provider webhook, deduplicated on the provider's event id."""

    __tablename__ = "payment_webhook_events"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    gateway_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    event_id: Mapped[str] = mapped_column(String(255), nullable=False)
    event_type: Mapped[str] = mapped_column(String(100), nullable=False)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (
        UniqueConstraint("tenant_id", "gateway_id", "event_id"),
        ForeignKeyConstraint(
            ["tenant_id", "gateway_id"],
            ["app_payment_gateways.tenant_id", "app_payment_gateways.id"],
            ondelete="CASCADE",
        ),
    )


class AppMapProvider(Base, TimestampMixin):
    __tablename__ = "app_map_providers"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    provider: Mapped[MapProviderKind] = mapped_column(_enum(MapProviderKind, "map_provider"), nullable=False)
    secrets_enc: Mapped[str] = mapped_column(Text, nullable=False)
    public_config: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, server_default="{}")
    is_default: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")
    secret_version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "name"),
        Index("uq_app_map_providers_default", "tenant_id", unique=True, postgresql_where=text("is_default")),
    )


class AppTenantDatabase(Base, TimestampMixin):
    """Connection details for an enterprise tenant's dedicated database (control plane).

    Readable inside the owning tenant's context (needed to route its requests) and in platform
    transactions; writable only by platform admins. The password is envelope-encrypted.
    """

    __tablename__ = "app_tenant_databases"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("app_tenants.id", ondelete="RESTRICT"), nullable=False, unique=True
    )
    dialect: Mapped[DbDialect] = mapped_column(_enum(DbDialect, "tenant_db_dialect"), nullable=False)
    host: Mapped[str] = mapped_column(String(255), nullable=False)
    port: Mapped[int] = mapped_column(Integer, nullable=False)
    database: Mapped[str] = mapped_column(String(128), nullable=False)
    username: Mapped[str] = mapped_column(String(128), nullable=False)
    password_enc: Mapped[str] = mapped_column(Text, nullable=False)
    ssl_mode: Mapped[DbSslMode] = mapped_column(_enum(DbSslMode, "tenant_db_ssl_mode"), nullable=False)
    ssl_root_cert: Mapped[str | None] = mapped_column(Text)
    secret_version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    pool_size: Mapped[int] = mapped_column(Integer, nullable=False, server_default="3")
    status: Mapped[TenantDbStatus] = mapped_column(
        _enum(TenantDbStatus, "tenant_db_status"), nullable=False, server_default="pending"
    )
    synced_schema_version: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default="0")
    last_verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (
        CheckConstraint("port BETWEEN 1 AND 65535", name="port_range"),
        CheckConstraint("pool_size BETWEEN 1 AND 20", name="pool_size_range"),
    )
