"""Payment provider interface and implementations.

Tenants pick a provider *kind* and supply credentials; they never supply URLs. Every outbound call
goes through :class:`SafeHttpClient` with a fixed host allowlist. Webhooks are verified with an HMAC
over the raw body and a timestamp tolerance, and compared in constant time.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import time
import uuid
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Protocol

from app.integrations.http import OutboundPolicy, SafeHttpClient

WEBHOOK_TOLERANCE_S = 300
# ISO 4217 currencies Stripe treats as zero-decimal.
ZERO_DECIMAL = frozenset(
    {
        "BIF",
        "CLP",
        "DJF",
        "GNF",
        "JPY",
        "KMF",
        "KRW",
        "MGA",
        "PYG",
        "RWF",
        "UGX",
        "VND",
        "VUV",
        "XAF",
        "XOF",
        "XPF",
    }
)


class ProviderError(Exception):
    """Provider rejected the request or was unreachable. Message is safe to log, not to return."""


class WebhookVerificationError(Exception):
    pass


@dataclass(frozen=True, slots=True)
class PaymentResult:
    provider_ref: str
    status: str  # pending | succeeded | failed
    client_secret: str | None = None


@dataclass(frozen=True, slots=True)
class RefundResult:
    provider_ref: str
    status: str  # succeeded | pending | failed


@dataclass(frozen=True, slots=True)
class WebhookEvent:
    event_id: str
    event_type: str
    payment_ref: str | None
    payment_status: str | None  # succeeded | failed | refunded | None (ignored type)
    amount_refunded_minor: int | None = None


def to_minor(amount: Decimal, currency: str) -> int:
    exp = 0 if currency.upper() in ZERO_DECIMAL else 2
    return int((amount * (10**exp)).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def from_minor(minor: int, currency: str) -> Decimal:
    exp = 0 if currency.upper() in ZERO_DECIMAL else 2
    value: Decimal = (Decimal(minor) / (10**exp)).quantize(Decimal("0.01"))
    return value


def verify_signed_payload(
    payload: bytes, header: str | None, secret: str, *, now: float | None = None
) -> None:
    """Stripe-style signature header: ``t=<unix>,v1=<hex hmac>[,v1=...]`` over ``f"{t}.{payload}"``."""
    if not header or len(header) > 2048:
        raise WebhookVerificationError("missing signature")
    parts: dict[str, list[str]] = {}
    for item in header.split(","):
        k, _, v = item.strip().partition("=")
        parts.setdefault(k, []).append(v)
    try:
        ts = int(parts["t"][0])
    except (KeyError, ValueError) as e:
        raise WebhookVerificationError("malformed signature") from e
    if abs((time.time() if now is None else now) - ts) > WEBHOOK_TOLERANCE_S:
        raise WebhookVerificationError("timestamp outside tolerance")
    expected = hmac.new(secret.encode(), f"{ts}.".encode() + payload, hashlib.sha256).hexdigest()
    if not any(hmac.compare_digest(expected, sig) for sig in parts.get("v1", [])):
        raise WebhookVerificationError("signature mismatch")


def sign_payload(payload: bytes, secret: str, ts: int | None = None) -> str:
    """Produce a signature header (used by the sandbox provider and tests)."""
    ts = int(time.time()) if ts is None else ts
    sig = hmac.new(secret.encode(), f"{ts}.".encode() + payload, hashlib.sha256).hexdigest()
    return f"t={ts},v1={sig}"


class PaymentProvider(Protocol):
    async def create_payment(
        self, *, amount: Decimal, currency: str, idempotency_key: str, metadata: dict[str, str]
    ) -> PaymentResult: ...

    async def refund(
        self, *, provider_ref: str, amount: Decimal, currency: str, idempotency_key: str
    ) -> RefundResult: ...

    def verify_webhook(self, payload: bytes, headers: dict[str, str]) -> WebhookEvent: ...


STRIPE_SECRET_RULES = {
    "api_key": re.compile(r"^sk_(test|live)_[A-Za-z0-9]{10,200}$"),
    "webhook_secret": re.compile(r"^whsec_[A-Za-z0-9+/=]{10,200}$"),
}
SANDBOX_SECRET_RULES = {"webhook_secret": re.compile(r"^[A-Za-z0-9_\-]{24,200}$")}


class StripeProvider:
    BASE = "https://api.stripe.com"
    POLICY = OutboundPolicy(frozenset({"api.stripe.com"}))

    def __init__(self, http: SafeHttpClient, secrets: dict[str, str]) -> None:
        self._http = http
        self._key = secrets["api_key"]
        self._whsec = secrets["webhook_secret"]

    async def _post(self, path: str, data: dict[str, Any], idempotency_key: str) -> dict[str, Any]:
        r = await self._http.request(
            "POST",
            f"{self.BASE}{path}",
            policy=self.POLICY,
            data=data,
            headers={"Authorization": f"Bearer {self._key}", "Idempotency-Key": idempotency_key},
        )
        body = r.json() if r.body else {}
        if r.status_code >= 400:
            err = body.get("error", {}) if isinstance(body, dict) else {}
            raise ProviderError(f"stripe {r.status_code}: {err.get('type', 'error')}")
        return body  # type: ignore[no-any-return]

    async def create_payment(
        self, *, amount: Decimal, currency: str, idempotency_key: str, metadata: dict[str, str]
    ) -> PaymentResult:
        data: dict[str, Any] = {
            "amount": to_minor(amount, currency),
            "currency": currency.lower(),
            "automatic_payment_methods[enabled]": "true",
        }
        for k, v in metadata.items():
            data[f"metadata[{k}]"] = v
        body = await self._post("/v1/payment_intents", data, idempotency_key)
        status = {"succeeded": "succeeded", "canceled": "failed"}.get(str(body.get("status")), "pending")
        return PaymentResult(str(body["id"]), status, body.get("client_secret"))

    async def refund(
        self, *, provider_ref: str, amount: Decimal, currency: str, idempotency_key: str
    ) -> RefundResult:
        body = await self._post(
            "/v1/refunds",
            {"payment_intent": provider_ref, "amount": to_minor(amount, currency)},
            idempotency_key,
        )
        status = {"succeeded": "succeeded", "failed": "failed", "canceled": "failed"}.get(
            str(body.get("status")), "pending"
        )
        return RefundResult(str(body["id"]), status)

    def verify_webhook(self, payload: bytes, headers: dict[str, str]) -> WebhookEvent:
        verify_signed_payload(payload, headers.get("stripe-signature"), self._whsec)
        try:
            evt = json.loads(payload)
            obj = evt["data"]["object"]
            etype = str(evt["type"])
            eid = str(evt["id"])
        except (ValueError, KeyError, TypeError) as e:
            raise WebhookVerificationError("malformed event") from e
        if etype == "payment_intent.succeeded":
            return WebhookEvent(eid, etype, str(obj.get("id")), "succeeded")
        if etype == "payment_intent.payment_failed":
            return WebhookEvent(eid, etype, str(obj.get("id")), "failed")
        if etype == "charge.refunded":
            return WebhookEvent(
                eid, etype, str(obj.get("payment_intent")), "refunded", int(obj.get("amount_refunded", 0))
            )
        return WebhookEvent(eid, etype, None, None)


class SandboxProvider:
    """Deterministic in-process provider for development and tests. Disabled in production.

    Webhooks use the same signature scheme as Stripe in the ``X-Sandbox-Signature`` header, with body
    ``{"id": ..., "type": "payment.succeeded" | "payment.failed", "payment_ref": ...}``.
    """

    def __init__(self, secrets: dict[str, str]) -> None:
        self._whsec = secrets["webhook_secret"]

    async def create_payment(
        self, *, amount: Decimal, currency: str, idempotency_key: str, metadata: dict[str, str]
    ) -> PaymentResult:
        ref = "sbx_" + uuid.uuid5(uuid.NAMESPACE_URL, f"sandbox:{idempotency_key}").hex
        return PaymentResult(ref, "pending", client_secret=f"{ref}_secret")

    async def refund(
        self, *, provider_ref: str, amount: Decimal, currency: str, idempotency_key: str
    ) -> RefundResult:
        return RefundResult("sbxre_" + uuid.uuid5(uuid.NAMESPACE_URL, idempotency_key).hex, "succeeded")

    def verify_webhook(self, payload: bytes, headers: dict[str, str]) -> WebhookEvent:
        verify_signed_payload(payload, headers.get("x-sandbox-signature"), self._whsec)
        try:
            evt = json.loads(payload)
            eid, etype, ref = str(evt["id"]), str(evt["type"]), str(evt["payment_ref"])
        except (ValueError, KeyError, TypeError) as e:
            raise WebhookVerificationError("malformed event") from e
        status = {"payment.succeeded": "succeeded", "payment.failed": "failed"}.get(etype)
        return WebhookEvent(eid, etype, ref if status else None, status)
