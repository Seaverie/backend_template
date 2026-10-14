"""Phase 3 tables: transactional outbox, webhooks, notifications, devices, delivery pings, files,
background task status, dead letters and offboarding archives."""

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
    ForeignKeyConstraint,
    Index,
    Integer,
    LargeBinary,
    Numeric,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin, tenant_fk, uuid_pk


def _enum(e: type[enum.Enum], name: str) -> Enum:
    return Enum(e, name=name, values_callable=lambda x: [m.value for m in x])


class DeliveryStatus(enum.StrEnum):
    pending = "pending"
    succeeded = "succeeded"
    dead = "dead"  # gave up after max attempts or permanent failure


class TaskStatus(enum.StrEnum):
    queued = "queued"
    running = "running"
    succeeded = "succeeded"
    failed = "failed"


class DevicePlatform(enum.StrEnum):
    android = "android"
    ios = "ios"
    web = "web"


class OutboxEvent(Base):
    """Domain events written in the same transaction as the change they describe."""

    __tablename__ = "outbox_events"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (
        Index("ix_outbox_events_unpublished", "created_at", postgresql_where=text("published_at IS NULL")),
    )


class AppWebhook(Base, TimestampMixin):
    __tablename__ = "app_webhooks"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    url: Mapped[str] = mapped_column(String(2048), nullable=False)
    description: Mapped[str | None] = mapped_column(String(200))
    event_types: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False)
    secret_enc: Mapped[str] = mapped_column(Text, nullable=False)
    secret_version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")
    consecutive_failures: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    disabled_reason: Mapped[str | None] = mapped_column(String(200))

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        CheckConstraint("url ~ '^https://'", name="https_only"),
        CheckConstraint("cardinality(event_types) BETWEEN 1 AND 50", name="event_types_count"),
    )


class WebhookDelivery(Base, TimestampMixin):
    __tablename__ = "webhook_deliveries"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    webhook_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    event_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    status: Mapped[DeliveryStatus] = mapped_column(
        _enum(DeliveryStatus, "webhook_delivery_status"), nullable=False, server_default="pending"
    )
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    next_attempt_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    last_status_code: Mapped[int | None] = mapped_column(SmallInteger)
    last_error: Mapped[str | None] = mapped_column(String(500))
    delivered_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (
        UniqueConstraint("tenant_id", "webhook_id", "event_id"),
        ForeignKeyConstraint(
            ["tenant_id", "webhook_id"], ["app_webhooks.tenant_id", "app_webhooks.id"], ondelete="CASCADE"
        ),
        Index("ix_webhook_deliveries_due", "next_attempt_at", postgresql_where=text("status = 'pending'")),
        Index("ix_webhook_deliveries_tenant_webhook", "tenant_id", "webhook_id", "created_at"),
    )


class AppNotificationRule(Base, TimestampMixin):
    """When ``event_type`` happens, push a notification to the devices of users holding any of
    ``target_role_ids`` (and optionally every tenant admin). Templates use ``$placeholders`` that are
    filled only from the event payload's top-level scalar fields (``string.Template.safe_substitute``)."""

    __tablename__ = "app_notification_rules"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    target_role_ids: Mapped[list[uuid.UUID]] = mapped_column(
        ARRAY(UUID(as_uuid=True)), nullable=False, server_default="{}"
    )
    include_tenant_admins: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    title_template: Mapped[str] = mapped_column(String(200), nullable=False)
    body_template: Mapped[str] = mapped_column(String(1000), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "name"),
        Index("ix_app_notification_rules_tenant_event", "tenant_id", "event_type"),
    )


class SystemUserDevice(Base, TimestampMixin):
    """Push tokens. Stored envelope-encrypted; ``token_hash`` (HMAC-free SHA-256) deduplicates without
    decrypting. Tokens rejected by FCM as unregistered are deactivated automatically."""

    __tablename__ = "system_user_devices"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    platform: Mapped[DevicePlatform] = mapped_column(_enum(DevicePlatform, "device_platform"), nullable=False)
    token_enc: Mapped[str] = mapped_column(Text, nullable=False)
    token_hash: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "token_hash"),
        ForeignKeyConstraint(
            ["tenant_id", "user_id"], ["app_users.tenant_id", "app_users.id"], ondelete="CASCADE"
        ),
        Index("ix_system_user_devices_tenant_user", "tenant_id", "user_id"),
    )


class NotificationDelivery(Base, TimestampMixin):
    __tablename__ = "notification_deliveries"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    rule_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    event_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    body: Mapped[str] = mapped_column(String(1000), nullable=False)
    data: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, server_default="{}")
    status: Mapped[DeliveryStatus] = mapped_column(
        _enum(DeliveryStatus, "notification_delivery_status"), nullable=False, server_default="pending"
    )
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    next_attempt_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    last_error: Mapped[str | None] = mapped_column(String(500))
    delivered_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (
        UniqueConstraint("tenant_id", "rule_id", "event_id", "device_id"),
        ForeignKeyConstraint(
            ["tenant_id", "rule_id"],
            ["app_notification_rules.tenant_id", "app_notification_rules.id"],
            ondelete="CASCADE",
        ),
        ForeignKeyConstraint(
            ["tenant_id", "device_id"],
            ["system_user_devices.tenant_id", "system_user_devices.id"],
            ondelete="CASCADE",
        ),
        Index(
            "ix_notification_deliveries_due", "next_attempt_at", postgresql_where=text("status = 'pending'")
        ),
    )


class DeliveryTrackingPing(Base):
    __tablename__ = "delivery_tracking_pings"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    order_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    lat: Mapped[Decimal] = mapped_column(Numeric(9, 6), nullable=False)
    lng: Mapped[Decimal] = mapped_column(Numeric(9, 6), nullable=False)
    accuracy_m: Mapped[int | None] = mapped_column(Integer)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (
        ForeignKeyConstraint(
            ["tenant_id", "order_id"], ["app_orders.tenant_id", "app_orders.id"], ondelete="CASCADE"
        ),
        CheckConstraint("lat BETWEEN -90 AND 90 AND lng BETWEEN -180 AND 180", name="coordinates"),
        Index("ix_delivery_tracking_pings_tenant_order", "tenant_id", "order_id", text("recorded_at DESC")),
    )


class SystemFile(Base):
    """Uploaded files (bulk imports). Kept in PostgreSQL for transactional consistency and RLS; size is
    capped by ``import_max_bytes`` and rows expire after ``file_retention_days``."""

    __tablename__ = "system_files"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    purpose: Mapped[str] = mapped_column(String(32), nullable=False)
    filename: Mapped[str] = mapped_column(String(255), nullable=False)
    content_type: Mapped[str] = mapped_column(String(100), nullable=False)
    size_bytes: Mapped[int] = mapped_column(Integer, nullable=False)
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    content: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    created_by: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    __table_args__ = (UniqueConstraint("tenant_id", "id"), Index("ix_system_files_expires", "expires_at"))


class SystemTask(Base):
    """Status of background work, owned by a tenant (or the platform when ``tenant_id`` is NULL).
    Polling authorisation is checked against this row, never against the broker."""

    __tablename__ = "system_tasks"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    kind: Mapped[str] = mapped_column(String(32), nullable=False)
    status: Mapped[TaskStatus] = mapped_column(
        _enum(TaskStatus, "task_status"), nullable=False, server_default="queued"
    )
    idempotency_key: Mapped[str | None] = mapped_column(String(128))
    created_by: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    input: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, server_default="{}")
    progress: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, server_default="{}")
    result: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    error: Mapped[str | None] = mapped_column(String(1000))
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (
        Index(
            "uq_system_tasks_idempotency",
            text("coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid)"),
            "kind",
            "idempotency_key",
            unique=True,
            postgresql_where=text("idempotency_key IS NOT NULL"),
        ),
        Index("ix_system_tasks_status_created", "status", "created_at"),
        Index("ix_system_tasks_tenant_created", "tenant_id", "created_at"),
    )


class SystemDeadLetter(Base):
    """Work that exhausted its retries. Platform-only; requeue via the platform API."""

    __tablename__ = "system_dead_letters"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    source: Mapped[str] = mapped_column(String(32), nullable=False)  # task | webhook | notification
    reference_id: Mapped[str] = mapped_column(String(100), nullable=False)
    task_name: Mapped[str | None] = mapped_column(String(200))
    error: Mapped[str] = mapped_column(String(1000), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    requeued_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (Index("ix_system_dead_letters_created", "created_at"),)


class TenantArchive(Base):
    """Cold-storage archive written during offboarding. Survives the tenant (no FK)."""

    __tablename__ = "tenant_archives"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    tenant_slug: Mapped[str] = mapped_column(String(63), nullable=False)
    kind: Mapped[str] = mapped_column(String(32), nullable=False)  # audit_logs | dedicated_audit_logs
    location: Mapped[str] = mapped_column(String(1024), nullable=False)
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    records: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (UniqueConstraint("tenant_id", "kind"),)
