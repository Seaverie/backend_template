"""Orders with tenant-configurable lifecycles, and returns (RMA). Tenant-owned, RLS-protected."""

from __future__ import annotations

import enum
import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    Enum,
    ForeignKeyConstraint,
    Index,
    Integer,
    Numeric,
    PrimaryKeyConstraint,
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


class StockEffect(enum.StrEnum):
    """What entering a status does to the order's stock."""

    none = "none"
    reserve = "reserve"  # hold stock (on_hand unchanged, reserved +)
    commit = "commit"  # stock leaves the warehouse (on_hand -, reserved - if held)
    release = "release"  # give back a reservation


class OrderStockState(enum.StrEnum):
    none = "none"
    reserved = "reserved"
    committed = "committed"
    released = "released"


class RmaStatus(enum.StrEnum):
    requested = "requested"
    approved = "approved"
    rejected = "rejected"
    received = "received"
    refunded = "refunded"
    closed = "closed"


class AppOrderStatus(Base, TimestampMixin):
    __tablename__ = "app_order_statuses"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    code: Mapped[str] = mapped_column(String(32), nullable=False)
    label: Mapped[str] = mapped_column(String(100), nullable=False)
    is_initial: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    is_terminal: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    stock_effect: Mapped[StockEffect] = mapped_column(
        _enum(StockEffect, "order_stock_effect"), nullable=False, server_default="none"
    )
    # Orders currently in this status count towards sales analytics.
    is_sale: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")
    position: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "code"),
        CheckConstraint("code ~ '^[a-z][a-z0-9_]{0,31}$'", name="code_format"),
        # Exactly-one-initial is enforced in the service; at most one is enforced here.
        Index("uq_app_order_statuses_initial", "tenant_id", unique=True, postgresql_where=text("is_initial")),
    )


class AppOrderStatusTransition(Base):
    __tablename__ = "app_order_status_transitions"

    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    from_status_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    to_status_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)

    __table_args__ = (
        PrimaryKeyConstraint("tenant_id", "from_status_id", "to_status_id"),
        ForeignKeyConstraint(
            ["tenant_id", "from_status_id"],
            ["app_order_statuses.tenant_id", "app_order_statuses.id"],
            ondelete="CASCADE",
        ),
        ForeignKeyConstraint(
            ["tenant_id", "to_status_id"],
            ["app_order_statuses.tenant_id", "app_order_statuses.id"],
            ondelete="CASCADE",
        ),
        CheckConstraint("from_status_id <> to_status_id", name="no_self_loop"),
    )


class AppOrder(Base, TimestampMixin):
    __tablename__ = "app_orders"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    number: Mapped[str] = mapped_column(String(40), nullable=False)
    status_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    stock_state: Mapped[OrderStockState] = mapped_column(
        _enum(OrderStockState, "order_stock_state"), nullable=False, server_default="none"
    )
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    total: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False)
    amount_paid: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False, server_default="0")
    amount_refunded: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False, server_default="0")
    customer: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, server_default="{}")
    placed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    created_by: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "number"),
        ForeignKeyConstraint(
            ["tenant_id", "status_id"],
            ["app_order_statuses.tenant_id", "app_order_statuses.id"],
            ondelete="RESTRICT",
        ),
        CheckConstraint(
            "total >= 0 AND amount_paid >= 0 AND amount_refunded >= 0", name="amounts_nonnegative"
        ),
        CheckConstraint("amount_refunded <= amount_paid", name="refund_le_paid"),
        Index("ix_app_orders_tenant_placed", "tenant_id", "placed_at"),
        Index("ix_app_orders_tenant_status", "tenant_id", "status_id"),
    )


class AppOrderItem(Base):
    __tablename__ = "app_order_items"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    order_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    variant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    sku: Mapped[str] = mapped_column(String(64), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    qty: Mapped[int] = mapped_column(Integer, nullable=False)
    unit_price: Mapped[Decimal] = mapped_column(Numeric(12, 2), nullable=False)
    line_total: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False)
    qty_returned: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        ForeignKeyConstraint(
            ["tenant_id", "order_id"], ["app_orders.tenant_id", "app_orders.id"], ondelete="CASCADE"
        ),
        ForeignKeyConstraint(
            ["tenant_id", "variant_id"],
            ["app_product_variants.tenant_id", "app_product_variants.id"],
            ondelete="RESTRICT",
        ),
        CheckConstraint("qty > 0", name="qty_positive"),
        CheckConstraint("qty_returned >= 0 AND qty_returned <= qty", name="qty_returned_range"),
        Index("ix_app_order_items_tenant_order", "tenant_id", "order_id"),
        Index("ix_app_order_items_tenant_variant", "tenant_id", "variant_id"),
    )


class OrderAllocation(Base):
    """Which bins hold the stock reserved/shipped for an order line (needed to release or ship exactly)."""

    __tablename__ = "order_allocations"

    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    order_item_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    bin_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    qty: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (
        PrimaryKeyConstraint("tenant_id", "order_item_id", "bin_id"),
        ForeignKeyConstraint(
            ["tenant_id", "order_item_id"],
            ["app_order_items.tenant_id", "app_order_items.id"],
            ondelete="CASCADE",
        ),
        ForeignKeyConstraint(
            ["tenant_id", "bin_id"], ["warehouse_bins.tenant_id", "warehouse_bins.id"], ondelete="RESTRICT"
        ),
        CheckConstraint("qty > 0", name="qty_positive"),
    )


class OrderStatusHistory(Base):
    __tablename__ = "order_status_history"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    order_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    from_status: Mapped[str | None] = mapped_column(String(32))
    to_status: Mapped[str] = mapped_column(String(32), nullable=False)
    actor_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    note: Mapped[str | None] = mapped_column(String(500))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (
        ForeignKeyConstraint(
            ["tenant_id", "order_id"], ["app_orders.tenant_id", "app_orders.id"], ondelete="CASCADE"
        ),
        Index("ix_order_status_history_tenant_order", "tenant_id", "order_id"),
    )


class RmaRequest(Base, TimestampMixin):
    __tablename__ = "rma_requests"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    number: Mapped[str] = mapped_column(String(40), nullable=False)
    order_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    status: Mapped[RmaStatus] = mapped_column(
        _enum(RmaStatus, "rma_status"), nullable=False, server_default="requested"
    )
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    restock_bin_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    refund_amount: Mapped[Decimal] = mapped_column(Numeric(14, 2), nullable=False, server_default="0")
    created_by: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "number"),
        ForeignKeyConstraint(
            ["tenant_id", "order_id"], ["app_orders.tenant_id", "app_orders.id"], ondelete="RESTRICT"
        ),
        ForeignKeyConstraint(
            ["tenant_id", "restock_bin_id"],
            ["warehouse_bins.tenant_id", "warehouse_bins.id"],
            ondelete="RESTRICT",
        ),
        CheckConstraint("refund_amount >= 0", name="refund_nonnegative"),
        Index("ix_rma_requests_tenant_order", "tenant_id", "order_id"),
    )


class RmaItem(Base):
    __tablename__ = "rma_items"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    rma_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    order_item_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    qty: Mapped[int] = mapped_column(Integer, nullable=False)
    restock: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")

    __table_args__ = (
        UniqueConstraint("tenant_id", "rma_id", "order_item_id"),
        ForeignKeyConstraint(
            ["tenant_id", "rma_id"], ["rma_requests.tenant_id", "rma_requests.id"], ondelete="CASCADE"
        ),
        ForeignKeyConstraint(
            ["tenant_id", "order_item_id"],
            ["app_order_items.tenant_id", "app_order_items.id"],
            ondelete="RESTRICT",
        ),
        CheckConstraint("qty > 0", name="qty_positive"),
    )


class RmaEvent(Base):
    """Append-only RMA history (UPDATE/DELETE blocked by trigger)."""

    __tablename__ = "rma_events"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    rma_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    from_status: Mapped[str | None] = mapped_column(String(16))
    to_status: Mapped[str] = mapped_column(String(16), nullable=False)
    actor_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    note: Mapped[str | None] = mapped_column(Text)
    detail: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, server_default="{}")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (
        ForeignKeyConstraint(
            ["tenant_id", "rma_id"], ["rma_requests.tenant_id", "rma_requests.id"], ondelete="RESTRICT"
        ),
        Index("ix_rma_events_tenant_rma", "tenant_id", "rma_id"),
    )
