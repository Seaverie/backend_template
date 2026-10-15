package com.tenantcore.commerce

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tenantcore.audit.Audit
import com.tenantcore.common.ApiException
import com.tenantcore.common.Conflict
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.Crypto
import com.tenantcore.security.UserPrincipal
import com.tenantcore.security.constantTimeEquals
import com.tenantcore.security.randomToken
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Currency
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ------------------------------------------------------------------------------------------------
// Model
// ------------------------------------------------------------------------------------------------

data class PaymentOut(
    val id: UUID, val orderId: UUID, val parentId: UUID?, val rmaId: UUID?, val kind: String, val provider: String, val method: String?,
    val status: String, val amount: BigDecimal, val currency: String, val providerRef: String?, val reference: String?,
    val failureMessage: String?, val createdBy: UUID?, val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
)

private const val PAYMENT_COLS = "id, order_id, parent_id, rma_id, kind, provider, method, status, amount, currency, provider_ref, reference, failure_message, created_by, created_at, updated_at"

private fun paymentRow(rs: java.sql.ResultSet) = PaymentOut(
    rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java),
    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9), rs.getString(10), rs.getString(11), rs.getString(12),
    rs.getString(13), rs.getObject(14, UUID::class.java), rs.getObject(15, OffsetDateTime::class.java), rs.getObject(16, OffsetDateTime::class.java))

fun paymentsOf(j: JdbcClient, orderId: UUID): List<PaymentOut> =
    j.sql("SELECT $PAYMENT_COLS FROM payments WHERE order_id = :o ORDER BY created_at, id").param("o", orderId).query { rs, _ -> paymentRow(rs) }.list()

fun paymentById(j: JdbcClient, id: UUID): PaymentOut =
    j.sql("SELECT $PAYMENT_COLS FROM payments WHERE id = :id").param("id", id).query { rs, _ -> paymentRow(rs) }.optional().orElseThrow { NotFound("Payment") }

data class GatewayIn(
    val isEnabled: Boolean? = null,
    @field:Size(max = 300) val publishableKey: String? = null,
    /** Write-only. Omit to keep, "" to remove. */
    @field:Size(max = 300) val secretKey: String? = null,
    /** Write-only. Omit to keep, "" to remove. */
    @field:Size(max = 300) val webhookSecret: String? = null,
)
data class GatewayOut(val provider: String, val label: String, val isEnabled: Boolean, val publishableKey: String?, val secretSet: Boolean,
                      val webhookSecretSet: Boolean, val webhookPath: String?)

data class PaymentCreateIn(
    @field:Pattern(regexp = "^(manual|stripe|sandbox)$", message = "must be manual, stripe or sandbox") val provider: String,
    /** Defaults to the balance due. */
    val amount: BigDecimal? = null,
    /** For manual payments: cash, bank_transfer, card_terminal, ... */
    @field:Pattern(regexp = "^[a-z][a-z0-9_]{0,31}$", message = "lowercase letters, digits and _") val method: String? = null,
    @field:Size(max = 200) val reference: String? = null,
)
data class RefundIn(val amount: BigDecimal? = null, @field:Size(max = 200) val reference: String? = null)
data class SandboxIn(@field:Pattern(regexp = "^(succeeded|failed)$", message = "must be succeeded or failed") val outcome: String)

/** What a client needs to finish a card payment (Stripe.js uses client_secret + publishable_key). */
data class PaymentStart(val payment: PaymentOut, val clientSecret: String?, val publishableKey: String?)

// ------------------------------------------------------------------------------------------------
// Gateways
// ------------------------------------------------------------------------------------------------

data class GatewayConfig(val provider: String, val enabled: Boolean, val publishableKey: String?, val secret: String?, val webhookSecret: String?)
data class GatewayResult(val ref: String?, val status: String, val clientSecret: String? = null, val failure: String? = null)

class ProviderError(message: String) : ApiException(HttpStatus.BAD_GATEWAY, "payment_provider_error", message)

/** Smallest currency unit, as card networks count (USD 12.34 → 1234, JPY 500 → 500). */
fun minorUnits(amount: BigDecimal, currency: String): Long {
    val digits = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
    return try { amount.movePointRight(digits).longValueExact() }
    catch (e: ArithmeticException) { throw Invalid("$currency amounts cannot have more than $digits decimal places.") }
}

/** Stripe over its REST API (no SDK): PaymentIntents for charges, Refunds for refunds. */
@Component
class StripeGateway(private val props: TcProperties, private val mapper: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private fun form(vararg pairs: Pair<String, Any?>) = pairs.filter { it.second != null }
        .joinToString("&") { (k, v) -> URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v.toString(), Charsets.UTF_8) }

    private fun call(secret: String, method: String, path: String, body: String?, idempotencyKey: String?): JsonNode {
        val req = HttpRequest.newBuilder(URI("${props.payments.stripeApiBase.trimEnd('/')}$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $secret")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            .build()
        val res = try { http.send(req, HttpResponse.BodyHandlers.ofString()) }
            catch (e: Exception) { throw ProviderError("Could not reach Stripe: ${e.javaClass.simpleName}") }
        val json = runCatching { mapper.readTree(res.body()) }.getOrNull()
        if (res.statusCode() !in 200..299) throw ProviderError("Stripe: " + (json?.path("error")?.path("message")?.asText()?.takeIf { it.isNotBlank() } ?: "HTTP ${res.statusCode()}"))
        return json ?: throw ProviderError("Stripe returned an unreadable response.")
    }

    fun intentStatus(s: String) = when (s) { "succeeded" -> "succeeded"; "canceled" -> "cancelled"; else -> "pending" }
    fun refundStatus(s: String) = when (s) { "succeeded" -> "succeeded"; "failed" -> "failed"; "canceled" -> "cancelled"; else -> "pending" }

    fun charge(cfg: GatewayConfig, amount: BigDecimal, currency: String, paymentId: UUID, orderId: UUID, tenantId: UUID, orderNumber: Long): GatewayResult {
        val r = call(cfg.secret!!, "POST", "/v1/payment_intents", form(
            "amount" to minorUnits(amount, currency), "currency" to currency.lowercase(), "automatic_payment_methods[enabled]" to "true",
            "description" to "Order #$orderNumber", "metadata[tenant_id]" to tenantId, "metadata[order_id]" to orderId, "metadata[payment_id]" to paymentId,
        ), "tc-charge-$paymentId")
        return GatewayResult(r.path("id").asText(), intentStatus(r.path("status").asText()), r.path("client_secret").asText(null))
    }

    fun clientSecret(cfg: GatewayConfig, ref: String): String? =
        call(cfg.secret!!, "GET", "/v1/payment_intents/${URLEncoder.encode(ref, Charsets.UTF_8)}", null, null).path("client_secret").asText(null)

    fun refund(cfg: GatewayConfig, chargeRef: String, amount: BigDecimal, currency: String, refundId: UUID): GatewayResult {
        val r = call(cfg.secret!!, "POST", "/v1/refunds", form("payment_intent" to chargeRef, "amount" to minorUnits(amount, currency),
            "metadata[payment_id]" to refundId), "tc-refund-$refundId")
        return GatewayResult(r.path("id").asText(), refundStatus(r.path("status").asText()), failure = r.path("failure_reason").asText(null))
    }

    /** Verifies a Stripe-Signature header: t=<unix>,v1=<hex HMAC-SHA256 of "t.payload">, within the tolerance window. */
    fun verify(payload: String, header: String?, secret: String): Boolean {
        if (header == null) return false
        val parts = header.split(",").mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 } }
        val t = parts.firstOrNull { it[0] == "t" }?.get(1)?.toLongOrNull() ?: return false
        if (kotlin.math.abs(Instant.now().epochSecond - t) > props.payments.webhookToleranceSeconds) return false
        val expected = HexFormat.of().formatHex(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            .doFinal("$t.$payload".toByteArray()))
        return parts.filter { it[0] == "v1" }.any { constantTimeEquals(it[1], expected) }
    }
}

// ------------------------------------------------------------------------------------------------
// Service
// ------------------------------------------------------------------------------------------------

/**
 * Card payments call the provider outside any database transaction: a pending row is committed first
 * (its id is the provider idempotency key), then the provider is called, then the result is stored.
 * A crash in between leaves a pending row that the provider's webhook later completes.
 */
@Service
class PaymentService(
    private val db: Db, private val audit: Audit, private val crypto: Crypto, private val orders: OrderService,
    private val stripe: StripeGateway, private val mapper: ObjectMapper, private val props: TcProperties,
    @org.springframework.context.annotation.Lazy private val events: com.tenantcore.integrations.Events,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val providers = linkedMapOf("stripe" to "Stripe", "sandbox" to "Sandbox (testing only; no real money)")

    // -- gateway settings --------------------------------------------------------------------------

    private fun ctx(tenantId: UUID, provider: String, what: String) = "payment_gateway:$tenantId:$provider:$what"

    fun gateway(j: JdbcClient, tenantId: UUID, provider: String): GatewayConfig =
        j.sql("SELECT is_enabled, config::text, secret_enc, webhook_secret_enc FROM payment_gateways WHERE provider = :p").param("p", provider)
            .query { rs, _ ->
                val cfg = mapper.readTree(rs.getString(2))
                GatewayConfig(provider, rs.getBoolean(1), cfg.path("publishable_key").asText(null),
                    rs.getString(3)?.let { crypto.decrypt(it, ctx(tenantId, provider, "secret")) },
                    rs.getString(4)?.let { crypto.decrypt(it, ctx(tenantId, provider, "webhook")) })
            }.optional().orElse(GatewayConfig(provider, false, null, null, null))

    private fun out(tenantId: UUID, g: GatewayConfig) = GatewayOut(g.provider, providers.getValue(g.provider), g.enabled, g.publishableKey,
        !g.secret.isNullOrEmpty(), !g.webhookSecret.isNullOrEmpty(),
        if (g.provider == "stripe") "${props.apiPrefix}/public/payments/stripe/$tenantId/webhook" else null)

    fun gateways(p: UserPrincipal): List<GatewayOut> {
        p.require("payments", Action.READ)
        return db.tenant(p.tenantId) { j -> providers.keys.map { out(p.tenantId, gateway(j, p.tenantId, it)) } }
    }

    fun setGateway(p: UserPrincipal, provider: String, input: GatewayIn): GatewayOut {
        p.require("payments", Action.MANAGE)
        if (provider !in providers) throw NotFound("Payment provider")
        if (provider == "stripe") {
            input.publishableKey?.let { if (it.isNotEmpty() && !it.startsWith("pk_")) throw Invalid("The publishable key starts with pk_.") }
            input.secretKey?.let { if (it.isNotEmpty() && !(it.startsWith("sk_") || it.startsWith("rk_"))) throw Invalid("The secret key starts with sk_ or rk_.") }
            input.webhookSecret?.let { if (it.isNotEmpty() && !it.startsWith("whsec_")) throw Invalid("The webhook signing secret starts with whsec_.") }
        } else if (input.secretKey != null || input.webhookSecret != null || input.publishableKey != null) throw Invalid("The sandbox takes no keys.")
        return db.tenant(p.tenantId) { j ->
            val cur = gateway(j, p.tenantId, provider)
            val secret = when (input.secretKey) { null -> cur.secret; "" -> null; else -> input.secretKey }
            val webhook = when (input.webhookSecret) { null -> cur.webhookSecret; "" -> null; else -> input.webhookSecret }
            val enabled = input.isEnabled ?: cur.enabled
            if (enabled && provider == "stripe" && (secret.isNullOrEmpty() || webhook.isNullOrEmpty()))
                throw Invalid("Stripe needs a secret key and a webhook signing secret before it can be enabled.")
            val publishable = when (input.publishableKey) { null -> cur.publishableKey; "" -> null; else -> input.publishableKey }
            j.sql("""INSERT INTO payment_gateways (tenant_id, provider, is_enabled, config, secret_enc, webhook_secret_enc)
                     VALUES (:t, :p, :e, CAST(:c AS jsonb), :s, :w)
                     ON CONFLICT (tenant_id, provider) DO UPDATE SET is_enabled = EXCLUDED.is_enabled, config = EXCLUDED.config,
                       secret_enc = EXCLUDED.secret_enc, webhook_secret_enc = EXCLUDED.webhook_secret_enc, updated_at = now()""")
                .param("t", p.tenantId).param("p", provider).param("e", enabled)
                .param("c", mapper.writeValueAsString(mapOf("publishable_key" to publishable)))
                .param("s", secret?.let { crypto.encrypt(it, ctx(p.tenantId, provider, "secret")) })
                .param("w", webhook?.let { crypto.encrypt(it, ctx(p.tenantId, provider, "webhook")) }).update()
            audit.record(j, p.tenantId, "payment_gateway.update", "payments", provider, mapOf("enabled" to enabled,
                "secret_changed" to (input.secretKey != null), "webhook_secret_changed" to (input.webhookSecret != null)))
            out(p.tenantId, gateway(j, p.tenantId, provider))
        }
    }

    /** Enabled card gateways with their public keys, for a storefront. */
    fun publicGateways(j: JdbcClient, tenantId: UUID): List<Map<String, Any?>> =
        providers.keys.map { gateway(j, tenantId, it) }.filter { it.enabled }.map { mapOf("provider" to it.provider, "publishable_key" to it.publishableKey) }

    // -- charges -----------------------------------------------------------------------------------

    private data class OrderMoney(val total: BigDecimal, val paid: BigDecimal, val currency: String, val number: Long)

    private fun lockOrder(j: JdbcClient, orderId: UUID) =
        j.sql("SELECT total, paid_total, currency, number FROM orders WHERE id = :id FOR UPDATE").param("id", orderId)
            .query { rs, _ -> OrderMoney(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3), rs.getLong(4)) }.optional().orElseThrow { NotFound("Order") }

    /** Takes or records a payment on an order. Used by the dashboard and the storefront. */
    fun startCharge(tenantId: UUID, orderId: UUID, input: PaymentCreateIn, actor: Actor): PaymentStart {
        input.amount?.let { Money.check(it, "amount") }
        data class Step1(val payment: PaymentOut, val cfg: GatewayConfig?, val order: OrderMoney, val reused: Boolean)
        val s1 = db.tenant(tenantId) { j ->
            val o = lockOrder(j, orderId)
            val due = o.total - o.paid
            val amount = input.amount ?: due
            if (amount.signum() <= 0) throw Conflict("Nothing is due on this order.")
            if (amount > due) throw Invalid("The amount is more than the $due due.")
            minorUnits(amount, o.currency)
            val cfg = if (input.provider == "manual") null else gateway(j, tenantId, input.provider).also {
                if (!it.enabled) throw Invalid("The ${providers[input.provider]} gateway is not enabled.")
            }
            // A retried card checkout for the same amount reuses the open attempt instead of starting another.
            val open = if (cfg == null) null else j.sql("""SELECT id FROM payments WHERE order_id = :o AND kind = 'charge' AND provider = :p
                                                         AND status = 'pending' AND amount = :a AND provider_ref IS NOT NULL ORDER BY created_at DESC LIMIT 1""")
                .param("o", orderId).param("p", input.provider).param("a", amount).query(UUID::class.java).optional().orElse(null)
            if (open != null) return@tenant Step1(paymentById(j, open), cfg, o, true)
            val id = j.sql("""INSERT INTO payments (tenant_id, order_id, kind, provider, method, status, amount, currency, reference, created_by)
                              VALUES (:t, :o, 'charge', :p, :m, :s, :a, :c, :r, :u) RETURNING id""")
                .param("t", tenantId).param("o", orderId).param("p", input.provider).param("m", input.method ?: if (cfg == null) "other" else "card")
                .param("s", if (cfg == null) "succeeded" else "pending").param("a", amount).param("c", o.currency).param("r", input.reference)
                .param("u", actor.id.takeIf { actor.type == "user" }).query(UUID::class.java).single()
            audit.record(j, tenantId, "payment.create", "payments", id, mapOf("order_id" to orderId, "provider" to input.provider, "amount" to amount),
                actorType = actor.type, actorId = actor.id)
            if (cfg == null) {
                orders.afterPayment(j, tenantId, orderId, actor)
                events.emit(j, tenantId, "payment.succeeded", mapOf("payment" to paymentById(j, id), "order" to orders.summary(j, orderId)))
            }
            Step1(paymentById(j, id), cfg, o, false)
        }
        val cfg = s1.cfg ?: return PaymentStart(s1.payment, null, null)
        if (s1.reused) {
            val secret = if (cfg.provider == "stripe") stripe.clientSecret(cfg, s1.payment.providerRef!!) else sandboxSecret(s1.payment.providerRef!!)
            return PaymentStart(s1.payment, secret, cfg.publishableKey)
        }
        val result = try {
            when (cfg.provider) {
                "stripe" -> stripe.charge(cfg, s1.payment.amount, s1.order.currency, s1.payment.id, orderId, tenantId, s1.order.number)
                else -> ("sbx_" + randomToken(12)).let { GatewayResult(it, "pending", sandboxSecret(it)) }
            }
        } catch (e: ProviderError) {
            db.tenant(tenantId) { j -> setStatus(j, tenantId, s1.payment.id, "failed", null, e.message, actor) }
            throw e
        }
        val payment = db.tenant(tenantId) { j -> setStatus(j, tenantId, s1.payment.id, result.status, result.ref, result.failure, actor); paymentById(j, s1.payment.id) }
        return PaymentStart(payment, result.clientSecret, cfg.publishableKey)
    }

    private fun sandboxSecret(ref: String) = "${ref}_secret"

    /** Moves a pending payment to its final status (only from pending) and updates the order. */
    private fun setStatus(j: JdbcClient, tenantId: UUID, id: UUID, status: String, ref: String?, failure: String?, actor: Actor): Boolean {
        val changed = j.sql("""UPDATE payments SET status = :s, provider_ref = coalesce(:r, provider_ref), failure_message = :f
                               WHERE id = :id AND status = 'pending'""")
            .param("s", status).param("r", ref).param("f", failure?.take(500)).param("id", id).update() > 0
        if (changed && status != "pending") {
            val p = paymentById(j, id)
            audit.record(j, tenantId, "payment.${p.kind}.$status", "payments", id, mapOf("order_id" to p.orderId, "amount" to p.amount), actorType = actor.type, actorId = actor.id)
            orders.afterPayment(j, tenantId, p.orderId, actor)
            val type = if (p.kind == "refund") (if (status == "succeeded") "payment.refunded" else null) else when (status) {
                "succeeded" -> "payment.succeeded"; "failed" -> "payment.failed"; else -> null }
            type?.let { events.emit(j, tenantId, it, mapOf("payment" to p, "order" to orders.summary(j, p.orderId))) }
        }
        return changed
    }

    fun create(p: UserPrincipal, orderId: UUID, input: PaymentCreateIn): PaymentStart {
        p.require("payments", Action.CREATE)
        return startCharge(p.tenantId, orderId, input, Actor.of(p))
    }

    fun list(p: UserPrincipal, orderId: UUID): List<PaymentOut> {
        p.require("payments", Action.READ)
        return db.tenant(p.tenantId) { j -> lockOrderExists(j, orderId); paymentsOf(j, orderId) }
    }

    private fun lockOrderExists(j: JdbcClient, orderId: UUID) {
        if (j.sql("SELECT count(*) FROM orders WHERE id = :id").param("id", orderId).query(Long::class.java).single() == 0L) throw NotFound("Order")
    }

    /** Completes a sandbox payment from the dashboard, to test the whole flow without a real provider. */
    fun sandbox(p: UserPrincipal, id: UUID, input: SandboxIn): PaymentOut {
        p.require("payments", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            val pay = paymentById(j, id)
            if (pay.provider != "sandbox") throw Invalid("Only sandbox payments can be completed this way.")
            if (pay.status != "pending") throw Conflict("This payment is already ${pay.status}.")
            setStatus(j, p.tenantId, id, input.outcome, null, if (input.outcome == "failed") "Declined (sandbox)" else null, Actor.of(p))
            paymentById(j, id)
        }
    }

    // -- refunds -----------------------------------------------------------------------------------

    /** Refunds part or all of a succeeded charge, never more than what is left of it. */
    fun refund(tenantId: UUID, chargeId: UUID, amount: BigDecimal?, reference: String?, rmaId: UUID?, actor: Actor): PaymentOut {
        amount?.let { Money.check(it, "amount") }
        data class Step1(val refund: PaymentOut, val charge: PaymentOut, val cfg: GatewayConfig?)
        val s1 = db.tenant(tenantId) { j ->
            val charge = paymentById(j, chargeId)
            lockOrder(j, charge.orderId)
            if (charge.kind != "charge" || charge.status != "succeeded") throw Conflict("Only succeeded payments can be refunded.")
            val already = j.sql("SELECT coalesce(sum(amount), 0) FROM payments WHERE parent_id = :p AND status IN ('pending', 'succeeded')")
                .param("p", chargeId).query(BigDecimal::class.java).single()
            val left = charge.amount - already
            val amt = amount ?: left
            if (amt.signum() <= 0) throw Conflict("This payment has been fully refunded.")
            if (amt > left) throw Invalid("At most $left can still be refunded on this payment.")
            val cfg = if (charge.provider == "manual") null else gateway(j, tenantId, charge.provider)
            if (cfg != null && charge.provider == "stripe" && cfg.secret.isNullOrEmpty()) throw Invalid("Stripe is not configured, so the refund cannot be sent.")
            val immediate = cfg == null || charge.provider == "sandbox"
            val id = j.sql("""INSERT INTO payments (tenant_id, order_id, parent_id, rma_id, kind, provider, method, status, amount, currency, reference, created_by)
                              VALUES (:t, :o, :p, :rma, 'refund', :prov, :m, :s, :a, :c, :r, :u) RETURNING id""")
                .param("t", tenantId).param("o", charge.orderId).param("p", chargeId).param("rma", rmaId).param("prov", charge.provider)
                .param("m", charge.method).param("s", if (immediate) "succeeded" else "pending").param("a", amt).param("c", charge.currency)
                .param("r", reference).param("u", actor.id.takeIf { actor.type == "user" }).query(UUID::class.java).single()
            audit.record(j, tenantId, "payment.refund", "payments", id, mapOf("charge_id" to chargeId, "amount" to amt, "rma_id" to rmaId),
                actorType = actor.type, actorId = actor.id)
            if (immediate) {
                orders.afterPayment(j, tenantId, charge.orderId, actor)
                events.emit(j, tenantId, "payment.refunded", mapOf("payment" to paymentById(j, id), "order" to orders.summary(j, charge.orderId)))
            }
            Step1(paymentById(j, id), charge, cfg)
        }
        if (s1.refund.status != "pending" || s1.cfg == null) return s1.refund
        val result = try { stripe.refund(s1.cfg, s1.charge.providerRef!!, s1.refund.amount, s1.refund.currency, s1.refund.id) }
            catch (e: ProviderError) { db.tenant(tenantId) { j -> setStatus(j, tenantId, s1.refund.id, "failed", null, e.message, actor) }; throw e }
        return db.tenant(tenantId) { j -> setStatus(j, tenantId, s1.refund.id, result.status, result.ref, result.failure, actor); paymentById(j, s1.refund.id) }
    }

    fun refund(p: UserPrincipal, id: UUID, input: RefundIn): PaymentOut {
        p.require("payments", Action.UPDATE)
        return refund(p.tenantId, id, input.amount, input.reference, null, Actor.of(p))
    }

    // -- webhooks ----------------------------------------------------------------------------------

    /** Stripe webhook: verified with the tenant's signing secret, applied once per event id. */
    fun stripeWebhook(tenantId: UUID, payload: String, signature: String?): Map<String, Any> = db.tenant(tenantId) { j ->
        val cfg = gateway(j, tenantId, "stripe")
        if (cfg.webhookSecret.isNullOrEmpty()) throw NotFound("Webhook")
        if (!stripe.verify(payload, signature, cfg.webhookSecret)) throw ApiException(HttpStatus.BAD_REQUEST, "invalid_signature", "The webhook signature is not valid.")
        val event = runCatching { mapper.readTree(payload) }.getOrNull() ?: throw Invalid("Not JSON.")
        val eventId = event.path("id").asText()
        if (eventId.isBlank() || eventId.length > 255) throw Invalid("Missing event id.")
        val fresh = j.sql("INSERT INTO payment_events (tenant_id, provider, event_id) VALUES (:t, 'stripe', :e) ON CONFLICT DO NOTHING")
            .param("t", tenantId).param("e", eventId).update() > 0
        if (!fresh) return@tenant mapOf("received" to true, "duplicate" to true)
        val obj = event.path("data").path("object")
        val type = event.path("type").asText()
        val ref = obj.path("id").asText()
        // Match on the provider's id, or on our own payment id in the metadata (in case the event
        // arrives before the provider's id was stored).
        fun find(kind: String): UUID? = j.sql("SELECT id FROM payments WHERE provider = 'stripe' AND kind = :k AND provider_ref = :r")
            .param("k", kind).param("r", ref).query(UUID::class.java).optional().orElse(null)
            ?: obj.path("metadata").path("payment_id").asText("").let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id ->
                j.sql("SELECT id FROM payments WHERE id = :id AND provider = 'stripe' AND kind = :k AND (provider_ref IS NULL OR provider_ref = :r)")
                    .param("id", id).param("k", kind).param("r", ref).query(UUID::class.java).optional().orElse(null)
            }
        val system = Actor("provider", null)
        when (type) {
            "payment_intent.succeeded" -> find("charge")?.let { setStatus(j, tenantId, it, "succeeded", ref, null, system) }
            "payment_intent.payment_failed" -> find("charge")?.let {
                setStatus(j, tenantId, it, "failed", ref, obj.path("last_payment_error").path("message").asText(null) ?: "Payment failed", system)
            }
            "payment_intent.canceled" -> find("charge")?.let { setStatus(j, tenantId, it, "cancelled", ref, null, system) }
            "refund.created", "refund.updated", "charge.refund.updated" -> find("refund")?.let {
                val s = stripe.refundStatus(obj.path("status").asText())
                if (s != "pending") setStatus(j, tenantId, it, s, ref, obj.path("failure_reason").asText(null), system)
            }
            else -> log.debug("ignoring stripe event {}", type)
        }
        mapOf("received" to true)
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}")
class PaymentController(private val payments: PaymentService) {

    @GetMapping("/payments/gateways")
    fun gateways() = mapOf("items" to payments.gateways(Caller.user()))

    @PutMapping("/payments/gateways/{provider}")
    fun setGateway(@PathVariable provider: String, @Valid @RequestBody body: GatewayIn) = payments.setGateway(Caller.user(), provider, body)

    @GetMapping("/orders/{orderId}/payments")
    fun list(@PathVariable orderId: UUID) = mapOf("items" to payments.list(Caller.user(), orderId))

    @PostMapping("/orders/{orderId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable orderId: UUID, @Valid @RequestBody body: PaymentCreateIn) = payments.create(Caller.user(), orderId, body)

    @PostMapping("/payments/{id}/refund")
    @ResponseStatus(HttpStatus.CREATED)
    fun refund(@PathVariable id: UUID, @Valid @RequestBody body: RefundIn) = payments.refund(Caller.user(), id, body)

    @PostMapping("/payments/{id}/sandbox")
    fun sandbox(@PathVariable id: UUID, @Valid @RequestBody body: SandboxIn) = payments.sandbox(Caller.user(), id, body)
}

/** Called by payment providers; no sign-in, the signature is the authentication. */
@RestController
@RequestMapping("\${tc.api-prefix}/public/payments")
class PaymentWebhookController(private val payments: PaymentService) {
    @PostMapping("/stripe/{tenantId}/webhook")
    fun stripe(@PathVariable tenantId: UUID, @RequestBody payload: String, @RequestHeader("Stripe-Signature", required = false) signature: String?) =
        payments.stripeWebhook(tenantId, payload, signature)
}
