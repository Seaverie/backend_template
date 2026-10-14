"""Tenant-isolated sales analytics.

Isolation comes from RLS (tenant transaction). Cost is bounded by a maximum date range, pagination,
a per-transaction ``statement_timeout`` (default 3 s), and the ``(tenant_id, placed_at)`` index on
``app_orders``. Amounts are grouped per currency and never summed across currencies.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any, Literal

from sqlalchemy import func, literal, select

from app.core.config import Settings
from app.core.errors import ValidationFailed
from app.db.session import Database
from app.dynamic.crud import to_json_value
from app.models import Action, AppOrder, AppOrderItem, AppOrderStatus
from app.services.authz import Principal

GroupBy = Literal["day", "week", "month", "product"]


class AnalyticsService:
    def __init__(self, settings: Settings, db: Database) -> None:
        self._s = settings
        self._db = db

    async def sales(
        self,
        p: Principal,
        *,
        date_from: datetime,
        date_to: datetime,
        group_by: GroupBy,
        limit: int,
        offset: int,
    ) -> list[dict[str, Any]]:
        p.require("analytics", Action.read)
        if date_from.tzinfo is None or date_to.tzinfo is None:
            raise ValidationFailed("from/to must include a timezone offset")
        if not date_from < date_to or date_to - date_from > timedelta(days=self._s.analytics_max_days):
            raise ValidationFailed(
                f"the range must be positive and at most {self._s.analytics_max_days} days"
            )

        sale_orders = (
            select(
                AppOrder.id, AppOrder.placed_at, AppOrder.total, AppOrder.amount_refunded, AppOrder.currency
            )
            .join(AppOrderStatus, AppOrderStatus.id == AppOrder.status_id)
            .where(
                AppOrderStatus.is_sale.is_(True),
                AppOrder.placed_at >= date_from,
                AppOrder.placed_at < date_to,
            )
            .subquery()
        )
        if group_by == "product":
            stmt = (
                select(
                    AppOrderItem.sku.label("key"),
                    sale_orders.c.currency,
                    func.count(func.distinct(sale_orders.c.id)).label("orders"),
                    func.sum(AppOrderItem.qty).label("units"),
                    func.sum(AppOrderItem.line_total).label("revenue"),
                    func.sum(AppOrderItem.qty_returned).label("units_returned"),
                )
                .join(sale_orders, sale_orders.c.id == AppOrderItem.order_id)
                .group_by(AppOrderItem.sku, sale_orders.c.currency)
                .order_by(func.sum(AppOrderItem.line_total).desc(), AppOrderItem.sku)
            )
        else:
            bucket = func.date_trunc(literal(group_by), func.timezone("UTC", sale_orders.c.placed_at)).label(
                "key"
            )
            stmt = (
                select(
                    bucket,
                    sale_orders.c.currency,
                    func.count().label("orders"),
                    func.sum(sale_orders.c.total).label("revenue"),
                    func.sum(sale_orders.c.amount_refunded).label("refunded"),
                )
                .group_by(bucket, sale_orders.c.currency)
                .order_by(bucket, sale_orders.c.currency)
            )
        stmt = stmt.limit(limit).offset(offset)
        async with self._db.tenant_session(
            p.tenant_id, user_id=p.user_id, statement_timeout_ms=self._s.analytics_statement_timeout_ms
        ) as s:
            rows = (await s.execute(stmt)).all()
        return [{k: to_json_value(v) for k, v in r._mapping.items()} for r in rows]
