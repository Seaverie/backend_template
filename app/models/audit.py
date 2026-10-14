"""Append-only audit log. UPDATE/DELETE are revoked from the runtime role and blocked by trigger."""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import DateTime, ForeignKey, Index, String, func, text
from sqlalchemy.dialects.postgresql import INET, JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, uuid_pk


class SystemAuditLog(Base):
    __tablename__ = "system_audit_logs"

    id: Mapped[uuid.UUID] = uuid_pk()
    # NULL for platform-level events (tenant provisioning, DDL). Visible only to platform context.
    tenant_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("app_tenants.id", ondelete="RESTRICT")
    )
    actor_type: Mapped[str] = mapped_column(String(16), nullable=False)  # user | api_key | platform | system
    actor_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    resource: Mapped[str] = mapped_column(String(100), nullable=False)
    resource_id: Mapped[str | None] = mapped_column(String(100))
    request_id: Mapped[str | None] = mapped_column(String(64))
    ip: Mapped[str | None] = mapped_column(INET)
    detail: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, server_default="{}")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    __table_args__ = (
        Index("ix_system_audit_logs_tenant_created", "tenant_id", text("created_at DESC")),
        Index("ix_system_audit_logs_tenant_resource", "tenant_id", "resource", "resource_id"),
    )
