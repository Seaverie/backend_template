"""Audit logging. Entries are written in the same transaction as the change they describe, so a
committed change always has its audit row and a rolled-back change never does."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Any

from sqlalchemy import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.logging import redact_value
from app.models import SystemAuditLog


@dataclass(frozen=True, slots=True)
class AuditContext:
    actor_type: str  # user | api_key | platform | system
    actor_id: uuid.UUID | None
    request_id: str | None
    ip: str | None


async def record(
    session: AsyncSession,
    ctx: AuditContext,
    *,
    tenant_id: uuid.UUID | None,
    action: str,
    resource: str,
    resource_id: str | None = None,
    detail: dict[str, Any] | None = None,
) -> None:
    await session.execute(
        insert(SystemAuditLog).values(
            tenant_id=tenant_id,
            actor_type=ctx.actor_type,
            actor_id=ctx.actor_id,
            action=action,
            resource=resource,
            resource_id=resource_id,
            request_id=ctx.request_id,
            ip=ctx.ip,
            detail=redact_value(detail or {}),
        )
    )
