"""Catalog, warehouses, stock, purchase orders, and the public (anonymous) catalog."""

from __future__ import annotations

import json
import uuid
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, Path, Query, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.api.deps import audit_ctx, client_ip, container, rate_limited
from app.api.serialize import pick
from app.container import Container
from app.core.errors import NotFound, RateLimited, ValidationFailed
from app.models import AppProduct, AppProductVariant, ProductStatus, PurchaseOrderStatus
from app.services.authz import Principal
from app.services.inventory import ReceiveLine
from app.services.ratelimit import Bucket

router = APIRouter(tags=["catalog"])

Code = Field(..., pattern=r"^[A-Za-z0-9_.-]{1,32}$")
BatchKey = Field(..., pattern=r"^[A-Za-z0-9_.:-]{8,100}$")


class VariantIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sku: str = Field(..., pattern=r"^[A-Za-z0-9_.-]{1,64}$")
    name: str = Field(..., min_length=1, max_length=200)
    price: Decimal = Field(..., ge=0, max_digits=12, decimal_places=2)
    currency: str = Field(..., pattern=r"^[A-Z]{3}$")
    attributes: dict[str, Any] = Field(default_factory=dict)


def _small_json(v: dict[str, Any]) -> dict[str, Any]:
    if len(json.dumps(v)) > 16_384:
        raise ValueError("object too large (max 16 KiB)")
    return v


class ProductIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(..., min_length=1, max_length=200)
    description: str | None = Field(None, max_length=20_000)
    status: ProductStatus = ProductStatus.draft
    is_public: bool = False
    metadata: dict[str, Any] = Field(default_factory=dict)
    variants: list[VariantIn] = Field(default_factory=list, max_length=100)

    _m = field_validator("metadata")(_small_json)


class ProductPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str | None = Field(None, min_length=1, max_length=200)
    description: str | None = Field(None, max_length=20_000)
    status: ProductStatus | None = None
    is_public: bool | None = None
    metadata: dict[str, Any] | None = None

    @field_validator("metadata")
    @classmethod
    def _meta(cls, v: dict[str, Any] | None) -> dict[str, Any] | None:
        return None if v is None else _small_json(v)


class WarehouseIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Code
    name: str = Field(..., min_length=1, max_length=200)
    address: dict[str, Any] = Field(default_factory=dict)


class BinIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Code


class AdjustLine(BaseModel):
    model_config = ConfigDict(extra="forbid")
    variant_id: uuid.UUID
    bin_id: uuid.UUID
    delta: int = Field(..., ge=-1_000_000, le=1_000_000)


class AdjustIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    batch_key: str = BatchKey
    lines: list[AdjustLine] = Field(..., min_length=1, max_length=200)
    note: str | None = Field(None, max_length=500)


class TransferIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    batch_key: str = BatchKey
    variant_id: uuid.UUID
    from_bin_id: uuid.UUID
    to_bin_id: uuid.UUID
    qty: int = Field(..., gt=0, le=1_000_000)


class PoItemIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    variant_id: uuid.UUID
    qty: int = Field(..., gt=0, le=1_000_000)
    unit_cost: Decimal = Field(..., ge=0, max_digits=12, decimal_places=2)


class PoIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    supplier_name: str = Field(..., min_length=1, max_length=200)
    warehouse_id: uuid.UUID
    currency: str = Field(..., pattern=r"^[A-Z]{3}$")
    items: list[PoItemIn] = Field(..., min_length=1, max_length=200)
    notes: str | None = Field(None, max_length=2000)


class PoStatusIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: PurchaseOrderStatus


class ReceiveLineIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    po_item_id: uuid.UUID
    qty: int = Field(..., gt=0, le=1_000_000)
    bin_id: uuid.UUID


class ReceiveIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    batch_key: str = BatchKey
    lines: list[ReceiveLineIn] = Field(..., min_length=1, max_length=200)


def _variant(v: AppProductVariant) -> dict[str, Any]:
    return pick(v, "id", "product_id", "sku", "name", "price", "currency", "attributes", "is_active")


def _product(p: AppProduct, variants: list[AppProductVariant]) -> dict[str, Any]:
    return {
        **pick(p, "id", "name", "description", "status", "is_public", "created_at", "updated_at", metadata="metadata_"),
        "variants": [_variant(v) for v in variants],
    }


# -- products ---------------------------------------------------------------------------------------


@router.post("/products", status_code=201)
async def create_product(
    body: ProductIn, request: Request, p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    prod, vs = await c.inventory.create_product(
        p, name=body.name, description=body.description, status=body.status, is_public=body.is_public,
        metadata=body.metadata, variants=[v.model_dump() for v in body.variants], ctx=audit_ctx(request, p),
    )
    return _product(prod, vs)


@router.patch("/products/{product_id}")
async def patch_product(
    product_id: uuid.UUID, body: ProductPatch, request: Request, p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    changes = body.model_dump(exclude_unset=True)
    if not changes:
        raise ValidationFailed("No fields to update.")
    prod = await c.inventory.update_product(p, product_id, changes, audit_ctx(request, p))
    return pick(prod, "id", "name", "description", "status", "is_public", metadata="metadata_")


@router.post("/products/{product_id}/variants", status_code=201)
async def add_variant(
    product_id: uuid.UUID, body: VariantIn, request: Request, p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return _variant(await c.inventory.add_variant(p, product_id, body.model_dump(), audit_ctx(request, p)))


def _metadata_filter(raw: str | None) -> dict[str, Any] | None:
    if raw is None:
        return None
    try:
        value = json.loads(raw)
    except ValueError as e:
        raise ValidationFailed("metadata must be a JSON object") from e
    if not isinstance(value, dict) or len(raw) > 2000:
        raise ValidationFailed("metadata must be a small JSON object")
    return value


@router.get("/products")
async def list_products(
    q: str | None = Query(None, min_length=1, max_length=200),
    metadata: str | None = Query(None, description="JSON object matched by containment, e.g. {\"color\":\"red\"}"),
    status: ProductStatus | None = None,
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0, le=10_000),
    p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    rows = await c.inventory.search_products(
        p, p.tenant_id, q=q, metadata=_metadata_filter(metadata), status=status, public_only=False,
        limit=limit, offset=offset,
    )
    return {"items": [_product(x, vs) for x, vs in rows]}


@router.get("/catalog/{tenant_slug}/products")
async def public_catalog(
    request: Request,
    tenant_slug: str = Path(..., pattern=r"^[a-z0-9][a-z0-9-]{1,62}$"),
    q: str | None = Query(None, min_length=1, max_length=200),
    limit: int = Query(24, ge=1, le=100),
    offset: int = Query(0, ge=0, le=5_000),
    c: Container = Depends(container),
) -> dict[str, Any]:
    """Anonymous storefront view: active + public products, active variants, no internal fields."""
    decision = await c.limiter.check([
        Bucket("platform", c.settings.rl_platform_per_min), Bucket(f"catalog:ip:{client_ip(request)}", 120),
    ])
    if not decision.allowed:
        raise RateLimited(headers={"Retry-After": str(decision.retry_after_s)})
    tenant = await c.tenants.get_by_slug(tenant_slug)
    if tenant is None or not tenant.active:
        raise NotFound()
    rows = await c.inventory.search_products(
        None, tenant.id, q=q, metadata=None, status=None, public_only=True, limit=limit, offset=offset
    )
    return {
        "items": [
            {
                "id": str(x.id), "name": x.name, "description": x.description,
                "variants": [pick(v, "sku", "name", "price", "currency", "attributes") for v in vs],
            }
            for x, vs in rows
        ]
    }


# -- warehouses & stock ------------------------------------------------------------------------------


@router.post("/warehouses", status_code=201)
async def create_warehouse(
    body: WarehouseIn, request: Request, p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    w = await c.inventory.create_warehouse(p, code=body.code, name=body.name, address=body.address, ctx=audit_ctx(request, p))
    return pick(w, "id", "code", "name", "address")


@router.post("/warehouses/{warehouse_id}/bins", status_code=201)
async def create_bin(
    warehouse_id: uuid.UUID, body: BinIn, request: Request, p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    b = await c.inventory.create_bin(p, warehouse_id, code=body.code, ctx=audit_ctx(request, p))
    return pick(b, "id", "warehouse_id", "code")


@router.get("/warehouses")
async def list_warehouses(p: Principal = Depends(rate_limited()), c: Container = Depends(container)) -> dict[str, Any]:
    return {
        "items": [
            {**pick(w, "id", "code", "name", "address", "is_active"), "bins": [pick(b, "id", "code", "is_active") for b in bins]}
            for w, bins in await c.inventory.list_warehouses(p)
        ]
    }


@router.post("/stock/adjustments")
async def adjust(
    body: AdjustIn, request: Request, p: Principal = Depends(rate_limited("stock.write")), c: Container = Depends(container)
) -> dict[str, Any]:
    replayed = await c.inventory.adjust(
        p, batch_key=body.batch_key, lines=[(x.variant_id, x.bin_id, x.delta) for x in body.lines], note=body.note,
        ctx=audit_ctx(request, p),
    )
    return {"applied": not replayed, "replayed": replayed}


@router.post("/stock/transfers")
async def transfer(
    body: TransferIn, request: Request, p: Principal = Depends(rate_limited("stock.write")), c: Container = Depends(container)
) -> dict[str, Any]:
    replayed = await c.inventory.transfer(
        p, batch_key=body.batch_key, variant_id=body.variant_id, from_bin=body.from_bin_id, to_bin=body.to_bin_id,
        qty=body.qty, ctx=audit_ctx(request, p),
    )
    return {"applied": not replayed, "replayed": replayed}


@router.get("/stock/levels")
async def stock_levels(
    variant_id: uuid.UUID | None = None, limit: int = Query(100, ge=1, le=500), offset: int = Query(0, ge=0, le=100_000),
    p: Principal = Depends(rate_limited()), c: Container = Depends(container),
) -> dict[str, Any]:
    rows = await c.inventory.levels(p, variant_id=variant_id, limit=limit, offset=offset)
    return {"items": [{**pick(b, "variant_id", "bin_id", "on_hand", "reserved"), "available": b.on_hand - b.reserved} for b in rows]}


@router.get("/stock/movements")
async def stock_movements(
    variant_id: uuid.UUID | None = None, limit: int = Query(100, ge=1, le=500), offset: int = Query(0, ge=0, le=100_000),
    p: Principal = Depends(rate_limited()), c: Container = Depends(container),
) -> dict[str, Any]:
    rows = await c.inventory.movements(p, variant_id=variant_id, limit=limit, offset=offset)
    return {
        "items": [
            pick(m, "id", "batch_key", "line_no", "variant_id", "bin_id", "kind", "delta_on_hand", "delta_reserved",
                 "reference_type", "reference_id", "note", "created_by", "created_at")
            for m in rows
        ]
    }


# -- purchase orders --------------------------------------------------------------------------------


def _po(po: Any, items: list[Any] | None = None) -> dict[str, Any]:
    out = pick(po, "id", "number", "supplier_name", "warehouse_id", "status", "currency", "notes", "created_at")
    if items is not None:
        out["items"] = [pick(i, "id", "variant_id", "qty_ordered", "qty_received", "unit_cost") for i in items]
    return out


@router.post("/purchase-orders", status_code=201)
async def create_po(
    body: PoIn, request: Request, p: Principal = Depends(rate_limited()), c: Container = Depends(container)
) -> dict[str, Any]:
    po = await c.inventory.create_po(
        p, supplier_name=body.supplier_name, warehouse_id=body.warehouse_id, currency=body.currency,
        items=[(i.variant_id, i.qty, i.unit_cost) for i in body.items], notes=body.notes, ctx=audit_ctx(request, p),
    )
    po, items = await c.inventory.get_po(p, po.id)
    return _po(po, items)


@router.get("/purchase-orders/{po_id}")
async def get_po(po_id: uuid.UUID, p: Principal = Depends(rate_limited()), c: Container = Depends(container)) -> dict[str, Any]:
    po, items = await c.inventory.get_po(p, po_id)
    return _po(po, items)


@router.post("/purchase-orders/{po_id}/status")
async def po_status(
    po_id: uuid.UUID, body: PoStatusIn, request: Request, p: Principal = Depends(rate_limited()),
    c: Container = Depends(container),
) -> dict[str, Any]:
    return _po(await c.inventory.set_po_status(p, po_id, body.status, audit_ctx(request, p)))


@router.post("/purchase-orders/{po_id}/receive")
async def receive_po(
    po_id: uuid.UUID, body: ReceiveIn, request: Request, p: Principal = Depends(rate_limited("stock.write")),
    c: Container = Depends(container),
) -> dict[str, Any]:
    po, replayed = await c.inventory.receive_po(
        p, po_id, batch_key=body.batch_key,
        lines=[ReceiveLine(x.po_item_id, x.qty, x.bin_id) for x in body.lines], ctx=audit_ctx(request, p),
    )
    po, items = await c.inventory.get_po(p, po.id)
    return {**_po(po, items), "replayed": replayed}
