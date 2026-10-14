"""The stock ledger: the only code path that changes inventory quantities.

Guarantees (per call to :func:`post`, inside the caller's tenant transaction):

* **Atomic**: movement rows and balance updates commit or roll back together.
* **No oversell**: every affected ``stock_balances`` row is locked ``FOR UPDATE`` in a deterministic
  order (variant_id, bin_id) before any check, so concurrent checkouts serialise on the rows they
  share and cannot both pass a check against the same stock. Deterministic lock order prevents
  deadlocks between batches touching the same rows. Database CHECK constraints
  (``0 <= reserved <= on_hand``) back this up.
* **Idempotent**: a batch is identified by ``batch_key``. A transaction-scoped advisory lock on
  (tenant, batch_key) serialises duplicates, and a batch already present is not applied again:
  identical replays return ``replayed=True``, while a different payload under the same key is
  rejected.
* **Append-only**: the runtime role has no UPDATE/DELETE on ``stock_movements`` and a trigger blocks
  them for every role.
"""

from __future__ import annotations

import uuid
from collections.abc import Iterable
from dataclasses import dataclass

from sqlalchemy import func, select, text, tuple_, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import Conflict, ValidationFailed
from app.models import MovementKind, StockBalance, StockMovement

MAX_LINES = 500


class InsufficientStock(Conflict):
    code = "insufficient_stock"
    message = "Not enough stock to complete this operation."


@dataclass(frozen=True, slots=True)
class Line:
    variant_id: uuid.UUID
    bin_id: uuid.UUID
    kind: MovementKind
    delta_on_hand: int
    delta_reserved: int = 0
    reference_type: str | None = None
    reference_id: uuid.UUID | None = None
    note: str | None = None


@dataclass(slots=True)
class Bal:
    on_hand: int
    reserved: int

    @property
    def available(self) -> int:
        return self.on_hand - self.reserved


async def lock_balances(
    session: AsyncSession, tenant_id: uuid.UUID, keys: Iterable[tuple[uuid.UUID, uuid.UUID]]
) -> dict[tuple[uuid.UUID, uuid.UUID], Bal]:
    """Ensure balance rows exist, then lock them in (variant_id, bin_id) order."""
    keys = sorted(set(keys))
    if not keys:
        return {}
    await session.execute(
        pg_insert(StockBalance)
        .values([{"tenant_id": tenant_id, "variant_id": v, "bin_id": b} for v, b in keys])
        .on_conflict_do_nothing(index_elements=["tenant_id", "variant_id", "bin_id"])
    )
    rows = (
        await session.execute(
            select(StockBalance.variant_id, StockBalance.bin_id, StockBalance.on_hand, StockBalance.reserved)
            .where(tuple_(StockBalance.variant_id, StockBalance.bin_id).in_(keys))
            .order_by(StockBalance.variant_id, StockBalance.bin_id)
            .with_for_update()
        )
    ).all()
    return {(r.variant_id, r.bin_id): Bal(r.on_hand, r.reserved) for r in rows}


async def lock_variant_balances(
    session: AsyncSession, variant_ids: Iterable[uuid.UUID]
) -> dict[tuple[uuid.UUID, uuid.UUID], Bal]:
    """Lock every existing balance row of the given variants (all bins), in deterministic order."""
    ids = sorted(set(variant_ids))
    if not ids:
        return {}
    rows = (
        await session.execute(
            select(StockBalance.variant_id, StockBalance.bin_id, StockBalance.on_hand, StockBalance.reserved)
            .where(StockBalance.variant_id.in_(ids))
            .order_by(StockBalance.variant_id, StockBalance.bin_id)
            .with_for_update()
        )
    ).all()
    return {(r.variant_id, r.bin_id): Bal(r.on_hand, r.reserved) for r in rows}


def allocate(
    balances: dict[tuple[uuid.UUID, uuid.UUID], Bal], variant_id: uuid.UUID, qty: int
) -> list[tuple[uuid.UUID, int]]:
    """Pick bins with available stock for ``qty`` units (largest availability first, then bin id).
    Caller must hold the locks. Raises InsufficientStock if total availability is short."""
    candidates = sorted(
        ((b, bal.available) for (v, b), bal in balances.items() if v == variant_id and bal.available > 0),
        key=lambda x: (-x[1], str(x[0])),
    )
    out: list[tuple[uuid.UUID, int]] = []
    remaining = qty
    for bin_id, avail in candidates:
        take = min(avail, remaining)
        out.append((bin_id, take))
        remaining -= take
        if remaining == 0:
            return out
    raise InsufficientStock(
        details={"variant_id": str(variant_id), "requested": qty, "available": qty - remaining}
    )


def _same(existing: list[StockMovement], lines: list[Line]) -> bool:
    if len(existing) != len(lines):
        return False
    return all(
        (m.variant_id, m.bin_id, m.kind, m.delta_on_hand, m.delta_reserved)
        == (ln.variant_id, ln.bin_id, ln.kind, ln.delta_on_hand, ln.delta_reserved)
        for m, ln in zip(sorted(existing, key=lambda m: m.line_no), lines, strict=True)
    )


async def post(
    session: AsyncSession,
    *,
    tenant_id: uuid.UUID,
    actor_id: uuid.UUID | None,
    batch_key: str,
    lines: list[Line],
) -> bool:
    """Apply a batch of movements. Returns True if the batch had already been applied (replay)."""
    if not 1 <= len(lines) <= MAX_LINES:
        raise ValidationFailed(f"a stock batch must have 1-{MAX_LINES} lines")
    if not 1 <= len(batch_key) <= 160:
        raise ValidationFailed("invalid batch key")
    for ln in lines:
        if ln.delta_on_hand == 0 and ln.delta_reserved == 0:
            raise ValidationFailed("a movement must change on_hand or reserved")

    await session.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))"),
        {"k": f"ledger:{tenant_id}:{batch_key}"},
    )
    existing = list(
        (await session.execute(select(StockMovement).where(StockMovement.batch_key == batch_key))).scalars()
    )
    if existing:
        if not _same(existing, lines):
            raise Conflict("This stock batch key was already used for different movements.")
        return True

    balances = await lock_balances(session, tenant_id, ((ln.variant_id, ln.bin_id) for ln in lines))
    for ln in lines:
        bal = balances[(ln.variant_id, ln.bin_id)]
        bal.on_hand += ln.delta_on_hand
        bal.reserved += ln.delta_reserved
        if bal.on_hand < 0 or bal.reserved < 0 or bal.reserved > bal.on_hand:
            raise InsufficientStock(
                details={"variant_id": str(ln.variant_id), "bin_id": str(ln.bin_id), "kind": ln.kind.value}
            )

    session.add_all(
        StockMovement(
            tenant_id=tenant_id,
            batch_key=batch_key,
            line_no=i,
            variant_id=ln.variant_id,
            bin_id=ln.bin_id,
            kind=ln.kind,
            delta_on_hand=ln.delta_on_hand,
            delta_reserved=ln.delta_reserved,
            reference_type=ln.reference_type,
            reference_id=ln.reference_id,
            note=ln.note,
            created_by=actor_id,
        )
        for i, ln in enumerate(lines)
    )
    for (variant_id, bin_id), bal in balances.items():
        await session.execute(
            update(StockBalance)
            .where(
                StockBalance.tenant_id == tenant_id,
                StockBalance.variant_id == variant_id,
                StockBalance.bin_id == bin_id,
            )
            .values(on_hand=bal.on_hand, reserved=bal.reserved, updated_at=func.now())
        )
    await session.flush()
    return False
