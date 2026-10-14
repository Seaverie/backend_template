"""Platform-level dynamic schema registry (not tenant-owned).

Tables defined here are created physically in the ``tenant_data`` schema with a ``tenant_id`` column
and RLS, and are shared by all tenants. The runtime role can only SELECT these registry tables; writes
go through the DDL engine (schema-owner role) used exclusively by platform-admin endpoints.
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
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import ARRAY, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin, uuid_pk


class CrudMode(enum.StrEnum):
    full = "full"
    read_only = "read_only"  # domain-managed tables: generic CRUD may read but never write
    disabled = "disabled"


class FieldType(enum.StrEnum):
    string = "string"
    text = "text"
    integer = "integer"
    bigint = "bigint"
    numeric = "numeric"
    boolean = "boolean"
    date = "date"
    timestamp = "timestamp"
    uuid = "uuid"
    json = "json"
    enum = "enum"
    reference = "reference"


class AppTable(Base, TimestampMixin):
    __tablename__ = "app_tables"

    id: Mapped[uuid.UUID] = uuid_pk()
    name: Mapped[str] = mapped_column(String(63), nullable=False, unique=True)
    physical_name: Mapped[str] = mapped_column(String(63), nullable=False, unique=True)
    label: Mapped[str | None] = mapped_column(String(200))
    crud_mode: Mapped[CrudMode] = mapped_column(
        Enum(CrudMode, name="crud_mode", values_callable=lambda e: [m.value for m in e]),
        nullable=False,
        server_default=CrudMode.full.value,
    )
    # Endpoints for tables flagged here require an Idempotency-Key header on POST.
    require_idempotency: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")

    __table_args__ = (
        CheckConstraint("name ~ '^[a-z][a-z0-9_]{0,62}$'", name="name_format"),
        CheckConstraint("physical_name ~ '^dt_[a-z][a-z0-9_]{0,59}$'", name="physical_name_format"),
    )


class AppField(Base, TimestampMixin):
    __tablename__ = "app_fields"

    id: Mapped[uuid.UUID] = uuid_pk()
    table_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("app_tables.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(63), nullable=False)
    type: Mapped[FieldType] = mapped_column(
        Enum(FieldType, name="field_type", values_callable=lambda e: [m.value for m in e]), nullable=False
    )
    nullable: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")
    is_unique: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    is_indexed: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    is_searchable: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    is_sensitive: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    max_length: Mapped[int | None] = mapped_column(Integer)
    precision: Mapped[int | None] = mapped_column(Integer)
    scale: Mapped[int | None] = mapped_column(Integer)
    enum_values: Mapped[list[str] | None] = mapped_column(ARRAY(Text))
    ref_table: Mapped[str | None] = mapped_column(String(63))
    position: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")

    __table_args__ = (
        UniqueConstraint("table_id", "name"),
        CheckConstraint("name ~ '^[a-z][a-z0-9_]{0,62}$'", name="name_format"),
    )


class SchemaState(Base):
    """Single-row table holding the global schema version (used to version metadata cache keys)."""

    __tablename__ = "system_schema_state"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    version: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default="1")

    __table_args__ = (CheckConstraint("id = 1", name="singleton"),)


class SchemaMigration(Base):
    """Ledger of applied dynamic-DDL changes with a checksum of the exact statements executed."""

    __tablename__ = "system_schema_migrations"

    id: Mapped[uuid.UUID] = uuid_pk()
    schema_version: Mapped[int] = mapped_column(BigInteger, nullable=False, unique=True)
    operation: Mapped[str] = mapped_column(String(32), nullable=False)
    table_name: Mapped[str] = mapped_column(String(63), nullable=False)
    checksum: Mapped[str] = mapped_column(String(64), nullable=False)
    statements: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False)
    applied_by: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    applied_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
