"""Catalog and inventory (tenant-owned, RLS-protected).

Stock model
-----------
``stock_balances`` holds the current quantities per (variant, bin):
  * ``on_hand``  -- physically present
  * ``reserved`` -- promised to open orders; ``available = on_hand - reserved``
CHECK constraints guarantee ``0 <= reserved <= on_hand`` at the database level, as a last line of
defence behind the application's locked validation.

``stock_movements`` is the immutable ledger: every balance change is one row, written in the same
transaction as the balance update. UPDATE/DELETE/TRUNCATE are blocked by trigger and not granted to
the runtime role. Each movement belongs to a ``batch_key`` (unique per tenant with ``line_no``), which
makes posting a batch idempotent: replaying the same batch is detected and returns the original.
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
    Computed,
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
from sqlalchemy.dialects.postgresql import JSONB, TSVECTOR, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin, tenant_fk, uuid_pk


def _enum(e: type[enum.Enum], name: str) -> Enum:
    return Enum(e, name=name, values_callable=lambda x: [m.value for m in x])


class ProductStatus(enum.StrEnum):
    draft = "draft"
    active = "active"
    archived = "archived"


class MovementKind(enum.StrEnum):
    receipt = "receipt"  # +on_hand (PO receiving)
    adjustment = "adjustment"  # +/- on_hand (count corrections, damage)
    reservation = "reservation"  # +reserved (order placed)
    release = "release"  # -reserved (order cancelled)
    shipment = "shipment"  # -on_hand, and -reserved if it was reserved (order fulfilled)
    transfer = "transfer"  # -on_hand in one bin, +on_hand in another (two lines)
    return_restock = "return_restock"  # +on_hand (RMA received)


class PurchaseOrderStatus(enum.StrEnum):
    draft = "draft"
    submitted = "submitted"
    partially_received = "partially_received"
    received = "received"
    cancelled = "cancelled"


class AppWarehouse(Base, TimestampMixin):
    __tablename__ = "app_warehouses"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    code: Mapped[str] = mapped_column(String(32), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    address: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, server_default="{}")
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")

    __table_args__ = (UniqueConstraint("tenant_id", "id"), UniqueConstraint("tenant_id", "code"))


class WarehouseBin(Base, TimestampMixin):
    __tablename__ = "warehouse_bins"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    warehouse_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    code: Mapped[str] = mapped_column(String(32), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "warehouse_id", "code"),
        ForeignKeyConstraint(
            ["tenant_id", "warehouse_id"],
            ["app_warehouses.tenant_id", "app_warehouses.id"],
            ondelete="RESTRICT",
        ),
    )


class AppProduct(Base, TimestampMixin):
    __tablename__ = "app_products"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[str | None] = mapped_column(Text)
    status: Mapped[ProductStatus] = mapped_column(
        _enum(ProductStatus, "product_status"), nullable=False, server_default=ProductStatus.draft.value
    )
    is_public: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    # Free-form attributes, queryable with JSONB containment (GIN jsonb_path_ops index).
    metadata_: Mapped[dict[str, Any]] = mapped_column("metadata", JSONB, nullable=False, server_default="{}")
    search: Mapped[str] = mapped_column(
        TSVECTOR,
        Computed(
            "to_tsvector('simple'::regconfig, coalesce(name, '') || ' ' || coalesce(description, ''))",
            persisted=True,
        ),
    )

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        Index(
            "ix_app_products_metadata",
            "metadata",
            postgresql_using="gin",
            postgresql_ops={"metadata": "jsonb_path_ops"},
        ),
        Index("ix_app_products_search", "search", postgresql_using="gin"),
        Index("ix_app_products_tenant_status", "tenant_id", "status"),
    )


class AppProductVariant(Base, TimestampMixin):
    __tablename__ = "app_product_variants"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    product_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    sku: Mapped[str] = mapped_column(String(64), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    price: Mapped[Decimal] = mapped_column(Numeric(12, 2), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    attributes: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, server_default="{}")
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "sku"),
        ForeignKeyConstraint(
            ["tenant_id", "product_id"], ["app_products.tenant_id", "app_products.id"], ondelete="RESTRICT"
        ),
        CheckConstraint("price >= 0", name="price_nonnegative"),
        CheckConstraint("currency ~ '^[A-Z]{3}$'", name="currency_format"),
        Index("ix_app_product_variants_tenant_product", "tenant_id", "product_id"),
    )


class StockBalance(Base):
    __tablename__ = "stock_balances"

    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    variant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    bin_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    on_hand: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    reserved: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (
        PrimaryKeyConstraint("tenant_id", "variant_id", "bin_id"),
        ForeignKeyConstraint(
            ["tenant_id", "variant_id"],
            ["app_product_variants.tenant_id", "app_product_variants.id"],
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["tenant_id", "bin_id"], ["warehouse_bins.tenant_id", "warehouse_bins.id"], ondelete="RESTRICT"
        ),
        CheckConstraint("on_hand >= 0", name="on_hand_nonnegative"),
        CheckConstraint("reserved >= 0", name="reserved_nonnegative"),
        CheckConstraint("reserved <= on_hand", name="reserved_le_on_hand"),
    )


class StockMovement(Base):
    __tablename__ = "stock_movements"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    batch_key: Mapped[str] = mapped_column(String(160), nullable=False)
    line_no: Mapped[int] = mapped_column(Integer, nullable=False)
    variant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    bin_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    kind: Mapped[MovementKind] = mapped_column(_enum(MovementKind, "stock_movement_kind"), nullable=False)
    delta_on_hand: Mapped[int] = mapped_column(Integer, nullable=False)
    delta_reserved: Mapped[int] = mapped_column(Integer, nullable=False)
    reference_type: Mapped[str | None] = mapped_column(String(32))
    reference_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    note: Mapped[str | None] = mapped_column(String(500))
    created_by: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    __table_args__ = (
        UniqueConstraint("tenant_id", "batch_key", "line_no"),
        ForeignKeyConstraint(
            ["tenant_id", "variant_id"],
            ["app_product_variants.tenant_id", "app_product_variants.id"],
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["tenant_id", "bin_id"], ["warehouse_bins.tenant_id", "warehouse_bins.id"], ondelete="RESTRICT"
        ),
        CheckConstraint("delta_on_hand <> 0 OR delta_reserved <> 0", name="nonzero"),
        Index(
            "ix_stock_movements_tenant_variant_created", "tenant_id", "variant_id", text("created_at DESC")
        ),
        Index("ix_stock_movements_tenant_reference", "tenant_id", "reference_type", "reference_id"),
    )


class AppPurchaseOrder(Base, TimestampMixin):
    __tablename__ = "app_purchase_orders"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    number: Mapped[str] = mapped_column(String(40), nullable=False)
    supplier_name: Mapped[str] = mapped_column(String(200), nullable=False)
    warehouse_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    status: Mapped[PurchaseOrderStatus] = mapped_column(
        _enum(PurchaseOrderStatus, "purchase_order_status"), nullable=False, server_default="draft"
    )
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    notes: Mapped[str | None] = mapped_column(Text)
    created_by: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "number"),
        ForeignKeyConstraint(
            ["tenant_id", "warehouse_id"],
            ["app_warehouses.tenant_id", "app_warehouses.id"],
            ondelete="RESTRICT",
        ),
    )


class PoItem(Base):
    __tablename__ = "po_items"

    id: Mapped[uuid.UUID] = uuid_pk()
    tenant_id: Mapped[uuid.UUID] = tenant_fk()
    po_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    variant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    qty_ordered: Mapped[int] = mapped_column(Integer, nullable=False)
    qty_received: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    unit_cost: Mapped[Decimal] = mapped_column(Numeric(12, 2), nullable=False)

    __table_args__ = (
        UniqueConstraint("tenant_id", "id"),
        UniqueConstraint("tenant_id", "po_id", "variant_id"),
        ForeignKeyConstraint(
            ["tenant_id", "po_id"],
            ["app_purchase_orders.tenant_id", "app_purchase_orders.id"],
            ondelete="CASCADE",
        ),
        ForeignKeyConstraint(
            ["tenant_id", "variant_id"],
            ["app_product_variants.tenant_id", "app_product_variants.id"],
            ondelete="RESTRICT",
        ),
        CheckConstraint("qty_ordered > 0", name="qty_ordered_positive"),
        CheckConstraint("qty_received >= 0 AND qty_received <= qty_ordered", name="qty_received_range"),
        CheckConstraint("unit_cost >= 0", name="unit_cost_nonnegative"),
    )
