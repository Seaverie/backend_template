package com.tenantcore.commerce

import com.fasterxml.jackson.databind.ObjectMapper
import com.tenantcore.audit.Audit
import com.tenantcore.common.Conflict
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.UserPrincipal
import com.tenantcore.tenants.ModuleService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// ------------------------------------------------------------------------------------------------
// Lifecycle (statuses and allowed moves), edited from the dashboard
// ------------------------------------------------------------------------------------------------

data class StatusDef(
    @field:Pattern(regexp = "^[a-z][a-z0-9_]{0,31}$", message = "lowercase letters, digits and _ (max 32)") val code: String,
    @field:NotBlank @field:Size(max = 100) val label: String,
    @field:Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "must be a colour like #22c55e") val color: String? = null,
    /** What entering this status does to stock: none, reserve, deduct (ship) or release (give back reserved stock). */
    @field:Pattern(regexp = "^(none|reserve|deduct|release)$", message = "must be none, reserve, deduct or release") val stockEffect: String = "none",
    val isInitial: Boolean = false,
    val isFinal: Boolean = false,
    /** Orders in this status count as sales in analytics. */
    val countsAsSale: Boolean = false,
)
data class TransitionDef(@field:NotBlank val from: String, @field:NotBlank val to: String)
data class LifecycleIn(
    @field:Size(min = 1, max = 30, message = "1 to 30 statuses") val statuses: List<@Valid StatusDef>,
    @field:Size(max = 300) val transitions: List<@Valid TransitionDef>,
)
data class Lifecycle(val statuses: List<StatusDef>, val transitions: List<TransitionDef>) {
    fun status(code: String) = statuses.firstOrNull { it.code == code }
    fun initial() = statuses.first { it.isInitial }
    fun next(from: String) = transitions.filter { it.from == from }.map { it.to }
}

// ------------------------------------------------------------------------------------------------
// Checkout settings (stored as options of the orders module)
// ------------------------------------------------------------------------------------------------

data class OrderSettings(
    val taxRatePercent: BigDecimal = BigDecimal.ZERO,
    val pricesIncludeTax: Boolean = false,
    val shippingFlat: BigDecimal = Money.ZERO,
    val freeShippingOver: BigDecimal? = null,
    /** Lets anyone place orders through the public shop endpoints. Off by default. */
    val storefrontCheckout: Boolean = false,
    /** When an order becomes fully paid it moves to this status (if the lifecycle allows the move). */
    val statusOnPaid: String? = "confirmed",
    /** Email the customer an order confirmation (storefront orders; needs the email module). */
    val sendConfirmationEmail: Boolean = true,
)
data class OrderSettingsIn(
    @field:jakarta.validation.constraints.DecimalMin("0") @field:jakarta.validation.constraints.DecimalMax("100") val taxRatePercent: BigDecimal? = null,
    val pricesIncludeTax: Boolean? = null,
    val shippingFlat: BigDecimal? = null,
    val freeShippingOver: BigDecimal? = null,
    val clearFreeShippingOver: Boolean? = null,
    val storefrontCheckout: Boolean? = null,
    @field:Pattern(regexp = "^[a-z][a-z0-9_]{0,31}$") val statusOnPaid: String? = null,
    val clearStatusOnPaid: Boolean? = null,
    val sendConfirmationEmail: Boolean? = null,
)

// ------------------------------------------------------------------------------------------------
// Orders
// ------------------------------------------------------------------------------------------------

data class OrderItemIn(val variantId: UUID, @field:Min(1) @field:Max(100_000) val quantity: Int)

data class OrderCreateIn(
    @field:Size(min = 1, max = 200, message = "an order needs 1 to 200 items") val items: List<@Valid OrderItemIn>,
    @field:Size(max = 200) val customerName: String? = null,
    @field:Email @field:Size(max = 320) val customerEmail: String? = null,
    @field:Size(max = 40) val customerPhone: String? = null,
    val shippingAddress: Map<String, Any?>? = null,
    val billingAddress: Map<String, Any?>? = null,
    @field:Size(max = 5000) val notes: String? = null,
    /** Dashboard only: a fixed discount off the goods. */
    val discount: BigDecimal? = null,
    /** Dashboard only: overrides the shipping fee from the checkout settings. */
    val shippingTotal: BigDecimal? = null,
    /** Set by the server for orders placed by a signed-in shop customer; never read from a request. */
    @com.fasterxml.jackson.annotation.JsonIgnore val customerId: UUID? = null,
)
data class OrderPatchIn(
    @field:Size(max = 200) val customerName: String? = null,
    @field:Email @field:Size(max = 320) val customerEmail: String? = null,
    @field:Size(max = 40) val customerPhone: String? = null,
    val shippingAddress: Map<String, Any?>? = null,
    val billingAddress: Map<String, Any?>? = null,
    @field:Size(max = 5000) val notes: String? = null,
)
data class TransitionIn(@field:NotBlank @field:Size(max = 32) val to: String, @field:Size(max = 1000) val note: String? = null)

data class OrderItemOut(val id: UUID, val variantId: UUID, val sku: String, val name: String, val options: Map<String, Any?>,
                        val unitPrice: BigDecimal, val quantity: Int, val lineTotal: BigDecimal)
data class HistoryOut(val fromStatus: String?, val toStatus: String, val note: String?, val actorType: String, val actorId: UUID?, val createdAt: OffsetDateTime)

data class OrderOut(
    val id: UUID, val number: Long, val status: String, val statusLabel: String, val source: String,
    val customerName: String?, val customerEmail: String?, val customerPhone: String?,
    val shippingAddress: Map<String, Any?>?, val billingAddress: Map<String, Any?>?, val notes: String?,
    val currency: String, val subtotal: BigDecimal, val discountTotal: BigDecimal, val shippingTotal: BigDecimal, val taxTotal: BigDecimal,
    val taxInclusive: Boolean, val total: BigDecimal, val paidTotal: BigDecimal, val refundedTotal: BigDecimal, val balanceDue: BigDecimal,
    val paymentStatus: String, val stockState: String, val items: List<OrderItemOut>, val allowedTransitions: List<String>,
    val createdBy: UUID?, val placedAt: OffsetDateTime, val updatedAt: OffsetDateTime,
    val history: List<HistoryOut>? = null, val payments: List<PaymentOut>? = null,
)

data class Totals(val subtotal: BigDecimal, val discount: BigDecimal, val shipping: BigDecimal, val tax: BigDecimal, val total: BigDecimal, val inclusive: Boolean)
data class PricedItem(val variantId: UUID, val sku: String, val name: String, val options: Map<String, Any?>, val unitPrice: BigDecimal, val quantity: Int) {
    val lineTotal: BigDecimal get() = Money.round(unitPrice * quantity.toBigDecimal())
}

data class Actor(val type: String, val id: UUID?) {
    companion object {
        fun of(p: UserPrincipal) = Actor(p.actorType, p.userId)
        val SYSTEM = Actor("system", null)
        val CUSTOMER = Actor("customer", null)
    }
}

@Service
class OrderService(
    private val db: Db, private val audit: Audit, private val rows: Rows, private val stock: Stock, private val modules: ModuleService,
    private val mapper: ObjectMapper, private val idempotency: Idempotency, private val email: com.tenantcore.email.EmailService,
    @org.springframework.context.annotation.Lazy private val events: com.tenantcore.integrations.Events,
) {

    /** What webhooks and notifications say about an order. */
    fun summary(j: JdbcClient, id: UUID): Map<String, Any?> =
        j.sql("SELECT id, number, status, payment_status, total, currency, customer_id, customer_email, customer_name, source, placed_at FROM orders WHERE id = :id")
            .param("id", id).query { rs, _ -> linkedMapOf("id" to rs.getObject(1, UUID::class.java), "number" to rs.getLong(2), "status" to rs.getString(3),
                "payment_status" to rs.getString(4), "total" to rs.getBigDecimal(5), "currency" to rs.getString(6), "customer_id" to rs.getObject(7, UUID::class.java),
                "customer_email" to rs.getString(8), "customer_name" to rs.getString(9), "source" to rs.getString(10),
                "placed_at" to rs.getObject(11, OffsetDateTime::class.java)) }.single()

    // -- lifecycle ---------------------------------------------------------------------------------

    private val defaultLifecycle = Lifecycle(
        listOf(
            StatusDef("pending", "Pending", "#f59e0b", "reserve", isInitial = true),
            StatusDef("confirmed", "Confirmed", "#3b82f6", "reserve", countsAsSale = true),
            StatusDef("shipped", "Shipped", "#8b5cf6", "deduct", countsAsSale = true),
            StatusDef("delivered", "Delivered", "#22c55e", "none", isFinal = true, countsAsSale = true),
            StatusDef("cancelled", "Cancelled", "#ef4444", "release", isFinal = true),
        ),
        listOf(TransitionDef("pending", "confirmed"), TransitionDef("pending", "cancelled"), TransitionDef("confirmed", "shipped"),
            TransitionDef("confirmed", "cancelled"), TransitionDef("shipped", "delivered")),
    )

    /** A tenant gets the default lifecycle the first time it is needed. Concurrent first uses insert it once. */
    fun lifecycle(j: JdbcClient, tenantId: UUID): Lifecycle {
        if (j.sql("SELECT count(*) FROM order_statuses").query(Long::class.java).single() == 0L) writeStatuses(j, tenantId, defaultLifecycle, conflictsIgnored = true)
        val statuses = j.sql("SELECT code, label, color, stock_effect, is_initial, is_final, counts_as_sale FROM order_statuses ORDER BY sort_order, code")
            .query { rs, _ -> StatusDef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7)) }.list()
        val transitions = j.sql("SELECT from_code, to_code FROM order_transitions ORDER BY from_code, to_code")
            .query { rs, _ -> TransitionDef(rs.getString(1), rs.getString(2)) }.list()
        return Lifecycle(statuses, transitions)
    }

    private fun writeStatuses(j: JdbcClient, tenantId: UUID, l: Lifecycle, conflictsIgnored: Boolean) {
        l.statuses.forEachIndexed { i, s ->
            j.sql("""INSERT INTO order_statuses (tenant_id, code, label, color, stock_effect, is_initial, is_final, counts_as_sale, sort_order)
                     VALUES (:t, :c, :l, :col, :e, :i, :f, :s, :o)
                     ${if (conflictsIgnored) "ON CONFLICT DO NOTHING" else """ON CONFLICT (tenant_id, code) DO UPDATE SET label = EXCLUDED.label,
                       color = EXCLUDED.color, stock_effect = EXCLUDED.stock_effect, is_initial = EXCLUDED.is_initial, is_final = EXCLUDED.is_final,
                       counts_as_sale = EXCLUDED.counts_as_sale, sort_order = EXCLUDED.sort_order"""}""")
                .param("t", tenantId).param("c", s.code).param("l", s.label).param("col", s.color).param("e", s.stockEffect)
                .param("i", s.isInitial).param("f", s.isFinal).param("s", s.countsAsSale).param("o", i).update()
        }
        l.transitions.forEach {
            j.sql("INSERT INTO order_transitions (tenant_id, from_code, to_code) VALUES (:t, :f, :to) ON CONFLICT DO NOTHING")
                .param("t", tenantId).param("f", it.from).param("to", it.to).update()
        }
    }

    fun getLifecycle(p: UserPrincipal) = p.require("orders", Action.READ).let { db.tenant(p.tenantId) { lifecycle(it, p.tenantId) } }

    /** Replaces the whole lifecycle. Statuses that orders are in cannot be removed. */
    fun setLifecycle(p: UserPrincipal, input: LifecycleIn): Lifecycle {
        p.require("orders", Action.MANAGE)
        val codes = input.statuses.map { it.code }
        if (codes.toSet().size != codes.size) throw Invalid("Each status code must be unique.")
        val initial = input.statuses.filter { it.isInitial }
        if (initial.size != 1) throw Invalid("Exactly one status must be the initial status.")
        if (initial[0].isFinal) throw Invalid("The initial status cannot be final.")
        if (initial[0].stockEffect == "release") throw Invalid("The initial status cannot release stock.")
        val pairs = input.transitions.map { it.from to it.to }
        if (pairs.toSet().size != pairs.size) throw Invalid("A transition is listed twice.")
        input.transitions.forEach {
            if (it.from !in codes || it.to !in codes) throw Invalid("Transition ${it.from} → ${it.to} uses an unknown status.")
            if (it.from == it.to) throw Invalid("A status cannot move to itself.")
        }
        return db.tenant(p.tenantId) { j ->
            lifecycle(j, p.tenantId)
            j.sql("SELECT pg_advisory_xact_lock(hashtext('lifecycle:' || :t))").param("t", p.tenantId.toString()).query().singleRow()
            val inUse = j.sql("SELECT DISTINCT status FROM orders").query(String::class.java).list().filter { it !in codes }
            if (inUse.isNotEmpty()) throw Conflict("Orders are still in these statuses, so they cannot be removed: ${inUse.joinToString()}.")
            j.sql("DELETE FROM order_transitions").update()
            // Clear the initial flag first so the "one initial status" index never sees two.
            j.sql("UPDATE order_statuses SET is_initial = false").update()
            j.sql("DELETE FROM order_statuses WHERE code NOT IN (:c)").param("c", codes).update()
            writeStatuses(j, p.tenantId, Lifecycle(input.statuses, input.transitions), conflictsIgnored = false)
            audit.record(j, p.tenantId, "orders.lifecycle", "orders", null, mapOf("statuses" to codes, "transitions" to input.transitions.size))
            lifecycle(j, p.tenantId)
        }
    }

    // -- settings ----------------------------------------------------------------------------------

    fun settings(j: JdbcClient): OrderSettings {
        @Suppress("UNCHECKED_CAST")
        val c = modules.config(j, "orders")["checkout"] as? Map<String, Any?> ?: return OrderSettings()
        fun dec(k: String) = c[k]?.toString()?.let { BigDecimal(it) }
        return OrderSettings(
            dec("tax_rate_percent") ?: BigDecimal.ZERO, c["prices_include_tax"] as? Boolean ?: false, dec("shipping_flat") ?: Money.ZERO,
            dec("free_shipping_over"), c["storefront_checkout"] as? Boolean ?: false,
            if (c.containsKey("status_on_paid")) c["status_on_paid"] as String? else "confirmed",
            c["send_confirmation_email"] as? Boolean ?: true,
        )
    }

    fun getSettings(p: UserPrincipal) = p.require("orders", Action.READ).let { db.tenant(p.tenantId) { settings(it) } }

    fun setSettings(p: UserPrincipal, input: OrderSettingsIn): OrderSettings {
        p.require("orders", Action.MANAGE)
        input.taxRatePercent?.let { if (it.stripTrailingZeros().scale() > 3) throw Invalid("tax_rate_percent can have at most 3 decimal places.") }
        input.shippingFlat?.let { Money.check(it, "shipping_flat") }; input.freeShippingOver?.let { Money.check(it, "free_shipping_over") }
        return db.tenant(p.tenantId) { j ->
            input.statusOnPaid?.let { if (lifecycle(j, p.tenantId).status(it) == null) throw Invalid("Unknown status '$it'.") }
            val cur = settings(j)
            val next = OrderSettings(
                input.taxRatePercent ?: cur.taxRatePercent, input.pricesIncludeTax ?: cur.pricesIncludeTax, input.shippingFlat ?: cur.shippingFlat,
                if (input.clearFreeShippingOver == true) null else input.freeShippingOver ?: cur.freeShippingOver,
                input.storefrontCheckout ?: cur.storefrontCheckout,
                if (input.clearStatusOnPaid == true) null else input.statusOnPaid ?: cur.statusOnPaid,
                input.sendConfirmationEmail ?: cur.sendConfirmationEmail,
            )
            val config = modules.config(j, "orders").toMutableMap()
            config["checkout"] = mapOf("tax_rate_percent" to next.taxRatePercent.toPlainString(), "prices_include_tax" to next.pricesIncludeTax,
                "shipping_flat" to next.shippingFlat.toPlainString(), "free_shipping_over" to next.freeShippingOver?.toPlainString(),
                "storefront_checkout" to next.storefrontCheckout, "status_on_paid" to next.statusOnPaid,
                "send_confirmation_email" to next.sendConfirmationEmail)
            modules.setConfig(j, p.tenantId, "orders", config, mapper)
            settings(j)
        }
    }

    // -- pricing -----------------------------------------------------------------------------------

    /** Prices always come from the catalog; a client never sends a price. */
    fun price(j: JdbcClient, items: List<OrderItemIn>, storefront: Boolean): List<PricedItem> {
        val merged = items.groupBy { it.variantId }.mapValues { (_, v) -> v.sumOf { it.quantity } }
        merged.values.forEach { if (it > 100_000) throw Invalid("At most 100000 of one item per order.") }
        data class V(val id: UUID, val sku: String, val name: String, val options: Map<String, Any?>, val price: BigDecimal, val active: Boolean, val status: String)
        val found = j.sql("""SELECT v.id, v.sku, coalesce(p.name || ' – ' || v.name, p.name), v.options::text, v.price, v.is_active, p.status
                             FROM product_variants v JOIN products p ON p.id = v.product_id WHERE v.id IN (:ids)""")
            .param("ids", merged.keys.toList()).query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                V(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), mapper.readValue(rs.getString(4), Map::class.java) as Map<String, Any?>,
                    rs.getBigDecimal(5), rs.getBoolean(6), rs.getString(7))
            }.list().associateBy { it.id }
        return merged.map { (id, qty) ->
            val v = found[id] ?: throw Invalid("Item $id is not in the catalog.")
            val sellable = v.active && (if (storefront) v.status == "active" else v.status != "archived")
            if (!sellable) throw Invalid("${v.sku} is not available for sale.")
            PricedItem(v.id, v.sku, v.name, v.options, v.price, qty)
        }
    }

    fun totals(items: List<PricedItem>, s: OrderSettings, discount: BigDecimal?, shippingOverride: BigDecimal?): Totals {
        val subtotal = items.fold(Money.ZERO) { a, i -> a + i.lineTotal }
        val disc = discount?.let { Money.check(it, "discount") } ?: Money.ZERO
        if (disc > subtotal) throw Invalid("The discount cannot exceed the subtotal.")
        val goods = subtotal - disc
        val shipping = shippingOverride?.let { Money.check(it, "shipping_total") }
            ?: if (s.freeShippingOver != null && goods >= s.freeShippingOver) Money.ZERO else s.shippingFlat.setScale(2)
        val base = goods + shipping
        val r = s.taxRatePercent
        val tax = if (r.signum() == 0) Money.ZERO
            else if (s.pricesIncludeTax) (base * r).divide(BigDecimal(100) + r, 2, RoundingMode.HALF_UP)
            else (base * r).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        return Totals(subtotal, disc, shipping, tax, if (s.pricesIncludeTax) base else base + tax, s.pricesIncludeTax)
    }

    // -- creating ----------------------------------------------------------------------------------

    /** Creates an order in its initial status, applying that status's stock effect. Returns the id. */
    fun insert(j: JdbcClient, tenantId: UUID, source: String, input: OrderCreateIn, actor: Actor, storefront: Boolean): UUID {
        rows.limitJson(input.shippingAddress, "shipping_address", 4096); rows.limitJson(input.billingAddress, "billing_address", 4096)
        val life = lifecycle(j, tenantId)
        val items = price(j, input.items, storefront)
        val t = totals(items, settings(j), input.discount, input.shippingTotal)
        val initial = life.initial()
        val id = j.sql("""INSERT INTO orders (tenant_id, number, status, source, customer_name, customer_email, customer_phone, shipping_address,
                              billing_address, notes, currency, subtotal, discount_total, shipping_total, tax_total, tax_inclusive, total, created_by)
                          VALUES (:t, :n, :st, :src, :cn, :ce, :cp, CAST(:sa AS jsonb), CAST(:ba AS jsonb), :notes, :cur, :sub, :disc, :ship, :tax, :inc, :tot, :by)
                          RETURNING id""")
            .param("t", tenantId).param("n", rows.next(j, tenantId, "order")).param("st", initial.code).param("src", source)
            .param("cn", input.customerName?.trim()).param("ce", input.customerEmail?.trim()?.lowercase()).param("cp", input.customerPhone)
            .param("sa", rows.jsonParam(input.shippingAddress)).param("ba", rows.jsonParam(input.billingAddress)).param("notes", input.notes)
            .param("cur", rows.currency(j)).param("sub", t.subtotal).param("disc", t.discount).param("ship", t.shipping).param("tax", t.tax)
            .param("inc", t.inclusive).param("tot", t.total).param("by", actor.id.takeIf { actor.type == "user" })
            .query(UUID::class.java).single()
        items.forEach { i ->
            j.sql("""INSERT INTO order_items (tenant_id, order_id, variant_id, sku, name, options, unit_price, quantity, line_total)
                     VALUES (:t, :o, :v, :sku, :n, CAST(:op AS jsonb), :up, :q, :lt)""")
                .param("t", tenantId).param("o", id).param("v", i.variantId).param("sku", i.sku).param("n", i.name.take(500))
                .param("op", rows.jsonParam(i.options)).param("up", i.unitPrice).param("q", i.quantity).param("lt", i.lineTotal).update()
        }
        history(j, tenantId, id, null, initial.code, null, actor)
        if (input.customerId != null) j.sql("UPDATE orders SET customer_id = :c WHERE id = :id").param("c", input.customerId).param("id", id).update()
        applyStock(j, tenantId, id, "none", initial.stockEffect, actor)
        audit.record(j, tenantId, "order.create", "orders", id, mapOf("source" to source, "total" to t.total, "items" to items.size),
            actorType = actor.type, actorId = actor.id)
        events.emit(j, tenantId, "order.created", summary(j, id) + ("items" to items.map { mapOf("sku" to it.sku, "name" to it.name, "quantity" to it.quantity, "unit_price" to it.unitPrice) }))
        return id
    }

    fun create(p: UserPrincipal, input: OrderCreateIn, idempotencyKey: String?): OrderOut {
        p.require("orders", Action.CREATE)
        return db.tenant(p.tenantId) { j ->
            val (id, _) = idempotency.run(j, p.tenantId, "order", idempotencyKey, input) { insert(j, p.tenantId, "dashboard", input, Actor.of(p), storefront = false) }
            detail(j, p.tenantId, id)
        }
    }

    /** Queues the order confirmation in the caller's transaction, when the settings and the email module allow. */
    fun queueConfirmation(j: JdbcClient, tenantId: UUID, orderId: UUID) {
        if (!rows.moduleOn(j, "email") || !settings(j).sendConfirmationEmail) return
        val o = orderRows(j, lifecycle(j, tenantId), listOf(orderId)).single()
        val to = o.customerEmail ?: return
        email.queue(j, tenantId, to, "order_confirmation", mapOf(
            "customer_name" to (o.customerName ?: ""), "order_number" to o.number.toString(), "order_total" to o.total.toPlainString(),
            "currency" to o.currency, "items" to o.items.joinToString("\n") { "${it.quantity} × ${it.name} (${it.sku}) — ${it.lineTotal.toPlainString()}" }))
    }

    private fun history(j: JdbcClient, tenantId: UUID, orderId: UUID, from: String?, to: String, note: String?, actor: Actor) {
        j.sql("INSERT INTO order_history (tenant_id, order_id, from_status, to_status, note, actor_type, actor_id) VALUES (:t, :o, :f, :to, :n, :at, :ai)")
            .param("t", tenantId).param("o", orderId).param("f", from).param("to", to).param("n", note).param("at", actor.type).param("ai", actor.id).update()
    }

    /**
     * Brings the order's stock to what the new status asks for. Stock that has shipped (deducted) is
     * never put back automatically; returns do that.
     */
    private fun applyStock(j: JdbcClient, tenantId: UUID, orderId: UUID, state: String, effect: String, actor: Actor) {
        if (effect == "none" || !rows.moduleOn(j, "inventory")) return
        var s = state
        when (effect) {
            "reserve" -> if (s == "none" || s == "released") { stock.reserveOrder(j, tenantId, orderId); s = "reserved" }
            "deduct" -> {
                if (s == "none" || s == "released") { stock.reserveOrder(j, tenantId, orderId); s = "reserved" }
                if (s == "reserved") { stock.deductOrder(j, tenantId, orderId, actor.id); s = "deducted" }
            }
            "release" -> if (s == "reserved") { stock.releaseOrder(j, orderId); s = "released" }
        }
        if (s != state) j.sql("UPDATE orders SET stock_state = :s WHERE id = :id").param("s", s).param("id", orderId).update()
    }

    // -- reading -----------------------------------------------------------------------------------

    private fun orderRows(j: JdbcClient, life: Lifecycle, ids: List<UUID>): List<OrderOut> {
        if (ids.isEmpty()) return emptyList()
        val items = j.sql("SELECT * FROM order_items WHERE order_id IN (:ids) ORDER BY sku").param("ids", ids).query { rs, _ ->
            rs.getObject("order_id", UUID::class.java) to OrderItemOut(rs.getObject("id", UUID::class.java), rs.getObject("variant_id", UUID::class.java),
                rs.getString("sku"), rs.getString("name"), rows.json(rs, "options") ?: emptyMap(), rs.getBigDecimal("unit_price"), rs.getInt("quantity"),
                rs.getBigDecimal("line_total"))
        }.list().groupBy({ it.first }, { it.second })
        val found = j.sql("SELECT * FROM orders WHERE id IN (:ids)").param("ids", ids).query { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val status = rs.getString("status")
            val total = rs.getBigDecimal("total"); val paid = rs.getBigDecimal("paid_total"); val refunded = rs.getBigDecimal("refunded_total")
            OrderOut(id, rs.getLong("number"), status, life.status(status)?.label ?: status, rs.getString("source"), rs.getString("customer_name"),
                rs.getString("customer_email"), rs.getString("customer_phone"), rows.json(rs, "shipping_address"), rows.json(rs, "billing_address"),
                rs.getString("notes"), rs.getString("currency"), rs.getBigDecimal("subtotal"), rs.getBigDecimal("discount_total"),
                rs.getBigDecimal("shipping_total"), rs.getBigDecimal("tax_total"), rs.getBoolean("tax_inclusive"), total, paid, refunded,
                (total - paid).max(Money.ZERO), rs.getString("payment_status"), rs.getString("stock_state"), items[id].orEmpty(), life.next(status),
                rows.uuid(rs, "created_by"), rows.time(rs, "placed_at")!!, rows.time(rs, "updated_at")!!)
        }.list().associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }

    fun detail(j: JdbcClient, tenantId: UUID, id: UUID): OrderOut {
        val o = orderRows(j, lifecycle(j, tenantId), listOf(id)).firstOrNull() ?: throw NotFound("Order")
        val history = j.sql("SELECT from_status, to_status, note, actor_type, actor_id, created_at FROM order_history WHERE order_id = :o ORDER BY created_at, id")
            .param("o", id).query { rs, _ -> HistoryOut(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID::class.java),
                rs.getObject(6, OffsetDateTime::class.java)) }.list()
        return o.copy(history = history, payments = paymentsOf(j, id))
    }

    fun get(p: UserPrincipal, id: UUID) = p.require("orders", Action.READ).let { db.tenant(p.tenantId) { detail(it, p.tenantId, id) } }

    fun list(p: UserPrincipal, status: String?, paymentStatus: String?, q: String?, from: LocalDate?, to: LocalDate?, limit: Int, offset: Int): Map<String, Any> {
        p.require("orders", Action.READ)
        return db.tenant(p.tenantId) { j ->
            val tz = j.sql("SELECT timezone FROM tenant_settings").query(String::class.java).optional().orElse("UTC")
            val number = q?.trim()?.removePrefix("#")?.toLongOrNull()
            val where = buildList {
                if (status != null) add("status = :s")
                if (paymentStatus != null) add("payment_status = :ps")
                if (!q.isNullOrBlank()) add(if (number != null) "number = :num" else "(customer_email ILIKE :q OR customer_name ILIKE :q OR customer_phone ILIKE :q)")
                if (from != null) add("placed_at >= (CAST(:from AS date)::timestamp AT TIME ZONE :tz)")
                if (to != null) add("placed_at < ((CAST(:to AS date) + 1)::timestamp AT TIME ZONE :tz)")
            }.let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
            fun bind(s: JdbcClient.StatementSpec) = s.apply {
                if (status != null) param("s", status); if (paymentStatus != null) param("ps", paymentStatus)
                if (!q.isNullOrBlank()) { if (number != null) param("num", number) else param("q", rows.like(q.trim())) }
                if (from != null) param("from", from); if (to != null) param("to", to)
                if (from != null || to != null) param("tz", tz)
            }
            val ids = bind(j.sql("SELECT id FROM orders $where ORDER BY placed_at DESC, number DESC LIMIT :l OFFSET :o")).param("l", limit).param("o", offset)
                .query(UUID::class.java).list()
            mapOf("items" to orderRows(j, lifecycle(j, p.tenantId), ids), "total" to bind(j.sql("SELECT count(*) FROM orders $where")).query(Long::class.java).single())
        }
    }

    // -- changing ----------------------------------------------------------------------------------

    private fun lock(j: JdbcClient, id: UUID): Pair<String, String> =
        j.sql("SELECT status, stock_state FROM orders WHERE id = :id FOR UPDATE").param("id", id)
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElseThrow { NotFound("Order") }

    fun patch(p: UserPrincipal, id: UUID, input: OrderPatchIn): OrderOut {
        p.require("orders", Action.UPDATE)
        rows.limitJson(input.shippingAddress, "shipping_address", 4096); rows.limitJson(input.billingAddress, "billing_address", 4096)
        return db.tenant(p.tenantId) { j ->
            lock(j, id)
            val sets = buildMap<String, Any?> {
                input.customerName?.let { put("customer_name", it.trim()) }; input.customerEmail?.let { put("customer_email", it.trim().lowercase()) }
                input.customerPhone?.let { put("customer_phone", it) }; input.notes?.let { put("notes", it) }
                input.shippingAddress?.let { put("shipping_address", rows.jsonParam(it)) }; input.billingAddress?.let { put("billing_address", rows.jsonParam(it)) }
            }
            if (sets.isNotEmpty()) {
                // Column names come from the fixed list above.
                val sql = sets.keys.joinToString(", ") { if (it.endsWith("_address")) "$it = CAST(:$it AS jsonb)" else "$it = :$it" }
                j.sql("UPDATE orders SET $sql WHERE id = :id").apply { sets.forEach { (k, v) -> param(k, v) } }.param("id", id).update()
            }
            audit.record(j, p.tenantId, "order.update", "orders", id, mapOf("fields" to sets.keys))
            detail(j, p.tenantId, id)
        }
    }

    /** Moves an order along the lifecycle. Only moves listed in the lifecycle are allowed. */
    fun transition(j: JdbcClient, tenantId: UUID, id: UUID, to: String, note: String?, actor: Actor) {
        val life = lifecycle(j, tenantId)
        val (current, stockState) = lock(j, id)
        val target = life.status(to) ?: throw Invalid("Unknown status '$to'.")
        if (to == current) throw Conflict("The order is already ${target.label}.")
        if (to !in life.next(current))
            throw Conflict("An order cannot go from ${life.status(current)?.label ?: current} to ${target.label}. Allowed: ${life.next(current).joinToString().ifEmpty { "none" }}.")
        applyStock(j, tenantId, id, stockState, target.stockEffect, actor)
        j.sql("UPDATE orders SET status = :s WHERE id = :id").param("s", to).param("id", id).update()
        history(j, tenantId, id, current, to, note, actor)
        events.emit(j, tenantId, "order.status_changed", summary(j, id) + mapOf("from" to current, "to" to to, "status_label" to target.label))
        audit.record(j, tenantId, "order.transition", "orders", id, mapOf("from" to current, "to" to to), actorType = actor.type, actorId = actor.id)
    }

    fun transition(p: UserPrincipal, id: UUID, input: TransitionIn): OrderOut {
        p.require("orders", Action.UPDATE)
        return db.tenant(p.tenantId) { j -> transition(j, p.tenantId, id, input.to, input.note, Actor.of(p)); detail(j, p.tenantId, id) }
    }

    /**
     * Recomputes the paid and refunded totals from the payments, and moves a newly fully-paid order to
     * the "status on paid" when the lifecycle allows that move.
     */
    fun afterPayment(j: JdbcClient, tenantId: UUID, orderId: UUID, actor: Actor) {
        val (status, _) = lock(j, orderId)
        val (paid, refunded) = j.sql("""SELECT coalesce(sum(amount) FILTER (WHERE kind = 'charge' AND status = 'succeeded'), 0),
                                               coalesce(sum(amount) FILTER (WHERE kind = 'refund' AND status = 'succeeded'), 0)
                                        FROM payments WHERE order_id = :o""")
            .param("o", orderId).query { rs, _ -> rs.getBigDecimal(1) to rs.getBigDecimal(2) }.single()
        val (total, before) = j.sql("SELECT total, payment_status FROM orders WHERE id = :o").param("o", orderId)
            .query { rs, _ -> rs.getBigDecimal(1) to rs.getString(2) }.single()
        val ps = when {
            paid.signum() == 0 -> "unpaid"
            refunded.signum() > 0 && refunded >= paid -> "refunded"
            refunded.signum() > 0 -> "partially_refunded"
            paid >= total -> "paid"
            else -> "partially_paid"
        }
        j.sql("UPDATE orders SET paid_total = :p, refunded_total = :r, payment_status = :s WHERE id = :o")
            .param("p", paid).param("r", refunded).param("s", ps).param("o", orderId).update()
        if (ps == "paid" && before != "paid") {
            events.emit(j, tenantId, "order.paid", summary(j, orderId))
            val target = settings(j).statusOnPaid
            if (target != null && target != status && target in lifecycle(j, tenantId).next(status))
                transition(j, tenantId, orderId, target, "Paid in full", actor)
        }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/orders")
@Validated
class OrderController(private val orders: OrderService) {

    @GetMapping
    fun list(@RequestParam(required = false) @Pattern(regexp = "^[a-z][a-z0-9_]{0,31}$") status: String?,
             @RequestParam(name = "payment_status", required = false) @Pattern(regexp = "^(unpaid|partially_paid|paid|partially_refunded|refunded)$") paymentStatus: String?,
             @RequestParam(required = false) @Size(max = 100) q: String?,
             @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) from: LocalDate?,
             @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) to: LocalDate?,
             @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
             @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = orders.list(Caller.user(), status, paymentStatus, q, from, to, limit, offset)

    /** Send an Idempotency-Key header so a retried request never creates a second order. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: OrderCreateIn, @RequestHeader("Idempotency-Key", required = false) key: String?) = orders.create(Caller.user(), body, key)

    @GetMapping("/lifecycle")
    fun lifecycle() = orders.getLifecycle(Caller.user())

    @PutMapping("/lifecycle")
    fun setLifecycle(@Valid @RequestBody body: LifecycleIn) = orders.setLifecycle(Caller.user(), body)

    @GetMapping("/settings")
    fun settings() = orders.getSettings(Caller.user())

    @PutMapping("/settings")
    fun setSettings(@Valid @RequestBody body: OrderSettingsIn) = orders.setSettings(Caller.user(), body)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = orders.get(Caller.user(), id)

    @PatchMapping("/{id}")
    fun patch(@PathVariable id: UUID, @Valid @RequestBody body: OrderPatchIn) = orders.patch(Caller.user(), id, body)

    @PostMapping("/{id}/transition")
    fun transition(@PathVariable id: UUID, @Valid @RequestBody body: TransitionIn) = orders.transition(Caller.user(), id, body)
}
