"""Tenant-scoped RBAC.

A permission grants ``action`` on ``resource`` with a row scope (``all`` rows or only rows the user
created) and optional field allowlists. ``NULL`` field lists mean "all fields"; an empty list means none.
Resources: ``data.<table>`` for dynamic tables, plus ``users``, ``roles``, ``api_keys``, ``audit_logs``.
"""

from __future__ import annotations

import enum
import uuid

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Enum,
    ForeignKeyConstraint,
    Index,
    PrimaryKeyConstraint,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import ARRAY, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin, tenant_fk, uuid_pk


class Action(enum.StrEnum):
    create = "create"
    read = "read"
    update = "update"
    delete = "delete"
    manage = "manage"  # privileged operations on non-data resources


class RowScope(enum.StrEnum):
    all = "all"
    own = "own"


class AppRole(Base, TimestampMixin):
    __tablename__ = "app_roles"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    description: Mapped[str | None] = mapped_column(String(500))
    is_system: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")

    __table_args__ = (UniqueConstraint("tenant_id", "id"), UniqueConstraint("tenant_id", "name"))


class AppPermission(Base, TimestampMixin):
    __tablename__ = "app_permissions"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    resource: Mapped[str] = mapped_column(String(100), nullable=False)
    action: Mapped[Action] = mapped_column(
        Enum(Action, name="permission_action", values_callable=lambda e: [m.value for m in e]), nullable=False
    )
    row_scope: Mapped[RowScope] = mapped_column(
        Enum(RowScope, name="permission_row_scope", values_callable=lambda e: [m.value for m in e]),
        nullable=False,
        server_default=RowScope.all.value,
    )
    readable_fields: Mapped[list[str] | None] = mapped_column(ARRAY(Text))
    writable_fields: Mapped[list[str] | None] = mapped_column(ARRAY(Text))

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        CheckConstraint(
            r"resource ~ '^(data\.[a-z][a-z0-9_]{0,62}|users|roles|api_keys|audit_logs)$'",
            name="resource_format",
        ),
        Index("ix_app_permissions_tenant_resource", "tenant_id", "resource"),
    )


class AppRolePermission(Base):
    __tablename__ = "app_role_permissions"

    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    role_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    permission_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)

    __table_args__ = (
        PrimaryKeyConstraint("tenant_id", "role_id", "permission_id"),
        ForeignKeyConstraint(
            ["tenant_id", "role_id"], ["app_roles.tenant_id", "app_roles.id"], ondelete="CASCADE"
        ),
        ForeignKeyConstraint(
            ["tenant_id", "permission_id"],
            ["app_permissions.tenant_id", "app_permissions.id"],
            ondelete="CASCADE",
        ),
    )


class AppUserRole(Base):
    __tablename__ = "app_user_roles"

    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    role_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)

    __table_args__ = (
        PrimaryKeyConstraint("tenant_id", "user_id", "role_id"),
        ForeignKeyConstraint(
            ["tenant_id", "user_id"], ["app_users.tenant_id", "app_users.id"], ondelete="CASCADE"
        ),
        ForeignKeyConstraint(
            ["tenant_id", "role_id"], ["app_roles.tenant_id", "app_roles.id"], ondelete="CASCADE"
        ),
    )
