"""Payments: initiate, verify webhooks, refund.

Ordering of side effects. The provider call happens outside the database transaction, because a
network call must not hold row locks. It carries a provider idempotency key derived from the
client's ``Idempotency-Key``, so a retry after any failure reaches the *same* provider object, and
the local insert is an upsert on (gateway, provider_ref). Webhooks are deduplicated on the provider's
event id. Payment state only moves forward (``pending -> succeeded|failed``; refunds accumulate).
"""

from __future__ import annotations

import uuid
from decimal import Decimal
from typing import Any

from sqlalchemy import func, select, text
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.core.errors import AppError, Conflict, NotFound, ServiceUnavailable, ValidationFailed
from app.core.logging import get_logger
from app.db.session import Database
from app.integrations.http import OutboundBlocked, OutboundError
from app.integrations.payments import ProviderError, WebhookVerificationError, from_minor
from app.models import (
    Action,
    AppOrder,
    AppPayment,
    AppPaymentGateway,
    PaymentRefund,
    PaymentStatus,
    PaymentWebhookEvent,
)
from app.services.audit import AuditContext, record
from app.services.authz import Principal
from app.services.outbox import emit
from app.services.providers import ProviderService

log = get_logger("payments")


class PaymentFailed(AppError):
    status_code = 502
    code = "payment_provider_error"
    message = "The payment provider could not process the request."


class PaymentService:
    def __init__(self, db: Database, providers: ProviderService) -> None:
        self._db = db
        self._providers = providers

    async def start(
        self,
        p: Principal,
        order_id: uuid.UUID,
        *,
        gateway_id: uuid.UUID | None,
        idempotency_key: str,
        ctx: AuditContext,
    ) -> tuple[AppPayment, str | None]:
        p.require("payments", Action.create)
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            order = (await s.execute(select(AppOrder).where(AppOrder.id == order_id))).scalar_one_or_none()
            if order is None:
                raise NotFound()
            amount = order.total - order.amount_paid
            currency = order.currency
        if amount <= 0:
            raise Conflict("This order is already fully paid.")
        gw_id, _, provider = await self._providers.payment_provider(p.tenant_id, gateway_id)
        try:
            result = await provider.create_payment(
                amount=amount,
                currency=currency,
                idempotency_key=f"tc:{p.tenant_id}:{order_id}:{idempotency_key}",
                metadata={"tenant_id": str(p.tenant_id), "order_id": str(order_id)},
            )
        except (ProviderError, OutboundError, OutboundBlocked) as e:
            log.warning("payment.create_failed", error=str(e))
            raise PaymentFailed() from e
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            await s.execute(
                pg_insert(AppPayment)
                .values(
                    tenant_id=p.tenant_id,
                    order_id=order_id,
                    gateway_id=gw_id,
                    provider_ref=result.provider_ref,
                    amount=amount,
                    currency=currency,
                    status=PaymentStatus.pending,
                )
                .on_conflict_do_nothing(index_elements=["tenant_id", "gateway_id", "provider_ref"])
            )
            pay = (
                await s.execute(
                    select(AppPayment).where(
                        AppPayment.gateway_id == gw_id, AppPayment.provider_ref == result.provider_ref
                    )
                )
            ).scalar_one()
            if result.status == "succeeded":
                await self._mark_succeeded(s, pay)
            await record(
                s,
                ctx,
                tenant_id=p.tenant_id,
                action="payment.start",
                resource="payments",
                resource_id=str(pay.id),
                detail={"order_id": str(order_id), "amount": str(amount)},
            )
            await s.commit()
        return pay, result.client_secret

    async def _mark_succeeded(self, s: Any, pay: AppPayment) -> bool:
        if pay.status != PaymentStatus.pending:
            return False
        pay.status = PaymentStatus.succeeded
        order = (
            await s.execute(select(AppOrder).where(AppOrder.id == pay.order_id).with_for_update())
        ).scalar_one()
        order.amount_paid = min(order.total, order.amount_paid + pay.amount)
        await emit(
            s,
            pay.tenant_id,
            "payment.succeeded",
            {
                "payment_id": pay.id,
                "order_id": pay.order_id,
                "amount": pay.amount,
                "currency": pay.currency,
            },
        )
        return True

    async def handle_webhook(
        self, gateway_id: uuid.UUID, payload: bytes, headers: dict[str, str]
    ) -> dict[str, Any]:
        """Unauthenticated endpoint: authenticity comes only from the signature."""
        async with self._db.anonymous_session() as s:
            await s.execute(text("SELECT set_config('app.gateway_lookup', :g, true)"), {"g": str(gateway_id)})
            gw = (
                await s.execute(select(AppPaymentGateway).where(AppPaymentGateway.id == gateway_id))
            ).scalar_one_or_none()
        if gw is None or not gw.is_active:
            raise NotFound()
        cfg = {
            "id": str(gw.id),
            "provider": gw.provider.value,
            "secrets_enc": gw.secrets_enc,
            "public_config": gw.public_config,
            "secret_version": gw.secret_version,
        }
        _, _, provider = await self._providers.payment_provider(gw.tenant_id, gw.id, cfg=cfg)
        try:
            event = provider.verify_webhook(payload, {k.lower(): v for k, v in headers.items()})
        except WebhookVerificationError as e:
            log.warning("payment.webhook_rejected", gateway_id=str(gateway_id), reason=str(e))
            raise ValidationFailed("Invalid webhook signature.") from e

        async with self._db.tenant_session(gw.tenant_id) as s:
            inserted = (
                await s.execute(
                    pg_insert(PaymentWebhookEvent)
                    .values(
                        tenant_id=gw.tenant_id,
                        gateway_id=gw.id,
                        event_id=event.event_id[:255],
                        event_type=event.event_type[:100],
                    )
                    .on_conflict_do_nothing(index_elements=["tenant_id", "gateway_id", "event_id"])
                    .returning(PaymentWebhookEvent.id)
                )
            ).first()
            if inserted is None:
                return {"received": True, "duplicate": True}
            applied = False
            if event.payment_ref and event.payment_status:
                pay = (
                    await s.execute(
                        select(AppPayment)
                        .where(AppPayment.gateway_id == gw.id, AppPayment.provider_ref == event.payment_ref)
                        .with_for_update()
                    )
                ).scalar_one_or_none()
                if pay is not None:
                    if event.payment_status == "succeeded":
                        applied = await self._mark_succeeded(s, pay)
                    elif event.payment_status == "failed" and pay.status == PaymentStatus.pending:
                        pay.status = PaymentStatus.failed
                        applied = True
                        await emit(
                            s,
                            pay.tenant_id,
                            "payment.failed",
                            {"payment_id": pay.id, "order_id": pay.order_id},
                        )
                    elif event.payment_status == "refunded" and event.amount_refunded_minor is not None:
                        total_refunded = from_minor(event.amount_refunded_minor, pay.currency)
                        applied = await self._apply_refund_total(s, pay, total_refunded)
            await record(
                s,
                _system_ctx(),
                tenant_id=gw.tenant_id,
                action="payment.webhook",
                resource="payments",
                resource_id=event.event_id[:100],
                detail={"type": event.event_type, "applied": applied},
            )
            await s.commit()
        return {"received": True, "duplicate": False}

    async def _apply_refund_total(self, s: Any, pay: AppPayment, total_refunded: Decimal) -> bool:
        """Refund totals only increase; idempotent whether reported by API response or webhook."""
        total_refunded = min(total_refunded, pay.amount)
        if total_refunded <= pay.amount_refunded:
            return False
        delta = total_refunded - pay.amount_refunded
        pay.amount_refunded = total_refunded
        pay.status = (
            PaymentStatus.refunded if total_refunded == pay.amount else PaymentStatus.partially_refunded
        )
        order = (
            await s.execute(select(AppOrder).where(AppOrder.id == pay.order_id).with_for_update())
        ).scalar_one()
        order.amount_refunded = min(order.amount_paid, order.amount_refunded + delta)
        await emit(
            s,
            pay.tenant_id,
            "payment.refunded",
            {
                "payment_id": pay.id,
                "order_id": pay.order_id,
                "amount_refunded": total_refunded,
                "currency": pay.currency,
            },
        )
        return True

    async def refund_order(
        self, p: Principal, order_id: uuid.UUID, amount: Decimal, *, idempotency_key: str, ctx: AuditContext
    ) -> Decimal:
        """Refund ``amount`` across the order's succeeded payments (newest first). Returns refunded total."""
        if amount <= 0:
            return Decimal("0")
        async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
            order = (await s.execute(select(AppOrder).where(AppOrder.id == order_id))).scalar_one()
            # Amount already refunded under this same key (a retry) does not count against the limit.
            done_total = (
                await s.execute(
                    select(func.coalesce(func.sum(PaymentRefund.amount), 0))
                    .join(AppPayment, AppPayment.id == PaymentRefund.payment_id)
                    .where(
                        AppPayment.order_id == order_id,
                        PaymentRefund.idempotency_key == idempotency_key[:200],
                    )
                )
            ).scalar_one()
            if amount - Decimal(done_total) > order.amount_paid - order.amount_refunded:
                raise ValidationFailed("Refund exceeds the amount paid and not yet refunded.")
            pays = list(
                (
                    await s.execute(
                        select(AppPayment)
                        .where(
                            AppPayment.order_id == order_id,
                            AppPayment.status.in_(
                                [
                                    PaymentStatus.succeeded,
                                    PaymentStatus.partially_refunded,
                                    PaymentStatus.refunded,
                                ]
                            ),
                        )
                        .order_by(AppPayment.created_at.desc())
                    )
                ).scalars()
            )
        remaining = amount
        for pay in pays:
            if remaining <= 0:
                break
            key = idempotency_key[:200]
            async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
                done = (
                    await s.execute(
                        select(PaymentRefund.amount).where(
                            PaymentRefund.payment_id == pay.id, PaymentRefund.idempotency_key == key
                        )
                    )
                ).scalar_one_or_none()
            if done is not None:  # this refund was already applied by an earlier attempt
                remaining -= done
                continue
            portion = min(remaining, pay.amount - pay.amount_refunded)
            if portion <= 0:
                continue
            _, _, provider = await self._providers.payment_provider(p.tenant_id, pay.gateway_id)
            try:
                res = await provider.refund(
                    provider_ref=pay.provider_ref,
                    amount=portion,
                    currency=pay.currency,
                    idempotency_key=f"tc:{p.tenant_id}:{pay.id}:refund:{key}",
                )
            except (ProviderError, OutboundError, OutboundBlocked) as e:
                raise PaymentFailed() from e
            if res.status == "failed":
                raise PaymentFailed("The provider declined the refund.")
            async with self._db.tenant_session(p.tenant_id, user_id=p.user_id) as s:
                locked = (
                    await s.execute(select(AppPayment).where(AppPayment.id == pay.id).with_for_update())
                ).scalar_one()
                await s.execute(
                    pg_insert(PaymentRefund)
                    .values(
                        tenant_id=p.tenant_id,
                        payment_id=pay.id,
                        provider_ref=res.provider_ref[:255],
                        idempotency_key=key,
                        amount=portion,
                    )
                    .on_conflict_do_nothing()
                )
                refunded = (
                    await s.execute(
                        select(func.coalesce(func.sum(PaymentRefund.amount), 0)).where(
                            PaymentRefund.payment_id == pay.id
                        )
                    )
                ).scalar_one()
                await self._apply_refund_total(s, locked, Decimal(refunded))
                await record(
                    s,
                    ctx,
                    tenant_id=p.tenant_id,
                    action="payment.refund",
                    resource="payments",
                    resource_id=str(pay.id),
                    detail={"amount": str(portion), "provider_ref": res.provider_ref},
                )
                await s.commit()
            remaining -= portion
        if remaining > 0:
            raise ServiceUnavailable("Part of the refund could not be applied to a captured payment.")
        return amount


def _system_ctx() -> AuditContext:
    from app.core.logging import request_id_var

    return AuditContext("system", None, request_id_var.get(), None)
