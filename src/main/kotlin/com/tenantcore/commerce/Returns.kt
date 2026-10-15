package com.tenantcore.commerce

import com.tenantcore.audit.Audit
import com.tenantcore.common.Conflict
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.UserPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

data class RmaItemIn(val orderItemId: UUID, @field:Min(1) @field:Max(100_000) val quantity: Int)
data class RmaIn(
    val orderId: UUID,
    @field:Size(max = 500) val reason: String? = null,
    @field:Size(max = 5000) val note: String? = null,
    @field:Size(min = 1, max = 200) val items: List<@Valid RmaItemIn>,
)
data class RmaNoteIn(@field:Size(max = 1000) val note: String? = null)
data class RmaReceiveItemIn(
    val rmaItemId: UUID,
    @field:Min(1) @field:Max(100_000) val quantity: Int,
    /** Put the goods back into stock (false for damaged goods). */
    val restock: Boolean = true,
    val binId: UUID? = null,
)
data class RmaReceiveIn(@field:Size(min = 1, max = 200) val items: List<@Valid RmaReceiveItemIn>, val binId: UUID? = null)
data class RmaRefundIn(
    /** Defaults to what is left to refund on this return. */
    val amount: BigDecimal? = null,
    /** Refund through this payment (its provider sends the money back). Omit to only record a refund made outside the system. */
    val paymentId: UUID? = null,
    @field:Size(max = 200) val reference: String? = null,
)

data class RmaItemOut(val id: UUID, val orderItemId: UUID, val sku: String, val name: String, val unitPrice: BigDecimal, val quantity: Int,
                      val receivedQuantity: Int, val restockedQuantity: Int)
data class RmaOut(
    val id: UUID, val number: Long, val orderId: UUID, val orderNumber: Long, val status: String, val reason: String?, val note: String?,
    val refundTotal: BigDecimal, val refundable: BigDecimal, val items: List<RmaItemOut>, val refunds: List<PaymentOut>,
    val createdBy: UUID?, val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
)

@Service
class ReturnService(
    private val db: Db, private val audit: Audit, private val rows: Rows, private val stock: Stock, private val payments: PaymentService,
    @org.springframework.context.annotation.Lazy private val events: com.tenantcore.integrations.Events,
) {
    private fun emit(j: JdbcClient, tenantId: UUID, type: String, id: UUID) {
        val r = one(j, id)
        events.emit(j, tenantId, type, mapOf("id" to r.id, "number" to r.number, "order_id" to r.orderId, "order_number" to r.orderNumber,
            "status" to r.status, "refund_total" to r.refundTotal, "items" to r.items.map { mapOf("sku" to it.sku, "quantity" to it.quantity, "received" to it.receivedQuantity) }))
    }


    private fun load(j: JdbcClient, ids: List<UUID>): List<RmaOut> {
        if (ids.isEmpty()) return emptyList()
        val items = j.sql("""SELECT ri.id, ri.rma_id, ri.order_item_id, oi.sku, oi.name, oi.unit_price, ri.quantity, ri.received_quantity, ri.restocked_quantity
                             FROM rma_items ri JOIN order_items oi ON oi.id = ri.order_item_id WHERE ri.rma_id IN (:ids) ORDER BY oi.sku""")
            .param("ids", ids).query { rs, _ ->
                rs.getObject(2, UUID::class.java) to RmaItemOut(rs.getObject(1, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getString(4),
                    rs.getString(5), rs.getBigDecimal(6), rs.getInt(7), rs.getInt(8), rs.getInt(9))
            }.list().groupBy({ it.first }, { it.second })
        val refunds = j.sql("SELECT id FROM payments WHERE rma_id IN (:ids)").param("ids", ids).query(UUID::class.java).list()
            .map { paymentById(j, it) }.groupBy { it.rmaId }
        val found = j.sql("""SELECT r.*, o.number AS order_number, o.total, o.subtotal FROM rmas r JOIN orders o ON o.id = r.order_id WHERE r.id IN (:ids)""")
            .param("ids", ids).query { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                val its = items[id].orEmpty()
                val refunded = rs.getBigDecimal("refund_total")
                RmaOut(id, rs.getLong("number"), rs.getObject("order_id", UUID::class.java), rs.getLong("order_number"), rs.getString("status"),
                    rs.getString("reason"), rs.getString("note"), refunded,
                    (value(its, rs.getBigDecimal("total"), rs.getBigDecimal("subtotal")) - refunded).max(Money.ZERO), its, refunds[id].orEmpty(),
                    rows.uuid(rs, "created_by"), rows.time(rs, "created_at")!!, rows.time(rs, "updated_at")!!)
            }.list().associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }

    /**
     * What the returned goods are worth to the customer: their share of what the order cost overall,
     * so discounts, tax and shipping are spread across items in proportion to their price.
     */
    private fun value(items: List<RmaItemOut>, total: BigDecimal, subtotal: BigDecimal): BigDecimal {
        val goods = items.fold(BigDecimal.ZERO) { a, i -> a + i.unitPrice * i.quantity.toBigDecimal() }
        if (subtotal.signum() == 0) return Money.ZERO
        return goods.multiply(total).divide(subtotal, 2, RoundingMode.HALF_UP).min(total)
    }

    private fun one(j: JdbcClient, id: UUID) = load(j, listOf(id)).firstOrNull() ?: throw NotFound("Return")

    private fun lockStatus(j: JdbcClient, id: UUID): String =
        j.sql("SELECT status FROM rmas WHERE id = :id FOR UPDATE").param("id", id).query(String::class.java).optional().orElseThrow { NotFound("Return") }

    fun list(p: UserPrincipal, status: String?, orderId: UUID?, limit: Int, offset: Int): Map<String, Any> {
        p.require("rma", Action.READ)
        return db.tenant(p.tenantId) { j ->
            val where = buildList { if (status != null) add("status = :s"); if (orderId != null) add("order_id = :o") }
                .let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
            fun bind(s: JdbcClient.StatementSpec) = s.apply { if (status != null) param("s", status); if (orderId != null) param("o", orderId) }
            val ids = bind(j.sql("SELECT id FROM rmas $where ORDER BY number DESC LIMIT :l OFFSET :o2")).param("l", limit).param("o2", offset)
                .query(UUID::class.java).list()
            mapOf("items" to load(j, ids), "total" to bind(j.sql("SELECT count(*) FROM rmas $where")).query(Long::class.java).single())
        }
    }

    fun get(p: UserPrincipal, id: UUID) = p.require("rma", Action.READ).let { db.tenant(p.tenantId) { one(it, id) } }

    fun create(p: UserPrincipal, input: RmaIn): RmaOut {
        p.require("rma", Action.CREATE)
        if (input.items.map { it.orderItemId }.toSet().size != input.items.size) throw Invalid("Each order item can be listed only once.")
        return db.tenant(p.tenantId) { j ->
            // Lock the order so two returns for it are checked one after the other.
            j.sql("SELECT id FROM orders WHERE id = :o FOR UPDATE").param("o", input.orderId).query(UUID::class.java).optional().orElseThrow { Invalid("Order not found.") }
            val ordered = j.sql("SELECT id, sku, quantity FROM order_items WHERE order_id = :o").param("o", input.orderId)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to (rs.getString(2) to rs.getInt(3)) }.list().toMap()
            val returned = j.sql("""SELECT ri.order_item_id, sum(ri.quantity) FROM rma_items ri JOIN rmas r ON r.id = ri.rma_id
                                    WHERE r.order_id = :o AND r.status <> 'rejected' GROUP BY ri.order_item_id""")
                .param("o", input.orderId).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }.list().toMap()
            input.items.forEach {
                val (sku, qty) = ordered[it.orderItemId] ?: throw Invalid("Item ${it.orderItemId} is not on this order.")
                val left = qty - (returned[it.orderItemId] ?: 0)
                if (it.quantity > left) throw Invalid("$sku: only $left can still be returned.")
            }
            val id = j.sql("INSERT INTO rmas (tenant_id, number, order_id, reason, note, created_by) VALUES (:t, :n, :o, :r, :no, :u) RETURNING id")
                .param("t", p.tenantId).param("n", rows.next(j, p.tenantId, "rma")).param("o", input.orderId).param("r", input.reason)
                .param("no", input.note).param("u", p.userId).query(UUID::class.java).single()
            input.items.forEach {
                j.sql("INSERT INTO rma_items (tenant_id, rma_id, order_item_id, quantity) VALUES (:t, :r, :oi, :q)")
                    .param("t", p.tenantId).param("r", id).param("oi", it.orderItemId).param("q", it.quantity).update()
            }
            audit.record(j, p.tenantId, "rma.create", "rma", id, mapOf("order_id" to input.orderId, "items" to input.items.size))
            emit(j, p.tenantId, "return.created", id)
            one(j, id)
        }
    }

    private fun move(p: UserPrincipal, id: UUID, allowed: Set<String>, to: String, note: String?): RmaOut {
        p.require("rma", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            val status = lockStatus(j, id)
            if (status !in allowed) throw Conflict("A return that is $status cannot become $to.")
            if (to == "rejected" && j.sql("SELECT coalesce(sum(received_quantity), 0) FROM rma_items WHERE rma_id = :id").param("id", id).query(Long::class.java).single() > 0)
                throw Conflict("Goods have already been received for this return; close it instead.")
            j.sql("UPDATE rmas SET status = :s, note = coalesce(:n, note) WHERE id = :id").param("s", to).param("n", note).param("id", id).update()
            audit.record(j, p.tenantId, "rma.$to", "rma", id, mapOf("from" to status))
            emit(j, p.tenantId, "return.updated", id)
            one(j, id)
        }
    }

    fun approve(p: UserPrincipal, id: UUID, n: RmaNoteIn) = move(p, id, setOf("requested"), "approved", n.note)
    fun reject(p: UserPrincipal, id: UUID, n: RmaNoteIn) = move(p, id, setOf("requested", "approved"), "rejected", n.note)
    fun close(p: UserPrincipal, id: UUID, n: RmaNoteIn) = move(p, id, setOf("requested", "approved", "rejected", "received", "refunded"), "closed", n.note)

    /** Records goods coming back and, for tracked items, puts them back into stock (ledger kind "return"). */
    fun receive(p: UserPrincipal, id: UUID, input: RmaReceiveIn): RmaOut {
        p.require("rma", Action.UPDATE)
        if (input.items.map { it.rmaItemId }.toSet().size != input.items.size) throw Invalid("Each item can be listed only once.")
        return db.tenant(p.tenantId) { j ->
            val status = lockStatus(j, id)
            if (status !in setOf("approved", "received", "refunded")) throw Conflict("Approve the return before receiving goods (it is $status).")
            val items = one(j, id).items.associateBy { it.id }
            val inventory = rows.moduleOn(j, "inventory")
            input.items.forEach { r ->
                val item = items[r.rmaItemId] ?: throw Invalid("Item ${r.rmaItemId} is not on this return.")
                val left = item.quantity - item.receivedQuantity
                if (r.quantity > left) throw Invalid("${item.sku}: only $left still expected.")
                val (variantId, tracked) = j.sql("SELECT v.id, v.track_inventory FROM order_items oi JOIN product_variants v ON v.id = oi.variant_id WHERE oi.id = :i")
                    .param("i", item.orderItemId).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getBoolean(2) }.single()
                val restock = r.restock && inventory && tracked
                if (restock) {
                    val bin = stock.resolveBin(j, r.binId ?: input.binId, null)
                    stock.change(j, p.tenantId, variantId, bin, r.quantity, "return", "rma", id, null, p.userId)
                }
                j.sql("UPDATE rma_items SET received_quantity = received_quantity + :q, restocked_quantity = restocked_quantity + :rq WHERE id = :id")
                    .param("q", r.quantity).param("rq", if (restock) r.quantity else 0).param("id", r.rmaItemId).update()
            }
            val open = j.sql("SELECT count(*) FROM rma_items WHERE rma_id = :id AND received_quantity < quantity").param("id", id).query(Long::class.java).single()
            if (open == 0L && status == "approved") j.sql("UPDATE rmas SET status = 'received' WHERE id = :id").param("id", id).update()
            emit(j, p.tenantId, "return.updated", id)
            audit.record(j, p.tenantId, "rma.receive", "rma", id, mapOf("items" to input.items.map { mapOf("rma_item_id" to it.rmaItemId, "quantity" to it.quantity, "restock" to it.restock) }))
            one(j, id)
        }
    }

    /**
     * Refunds the customer. The amount is reserved on the return first (so two refunds at once cannot
     * exceed its value), then sent through the payment's provider; if that fails the reservation is undone.
     */
    fun refund(p: UserPrincipal, id: UUID, input: RmaRefundIn): RmaOut {
        p.require("rma", Action.UPDATE)
        if (input.paymentId != null) p.require("payments", Action.UPDATE)
        input.amount?.let { Money.check(it, "amount") }
        val amount = db.tenant(p.tenantId) { j ->
            val status = lockStatus(j, id)
            if (status !in setOf("approved", "received", "refunded")) throw Conflict("Only approved returns can be refunded (this one is $status).")
            val r = one(j, id)
            val amt = input.amount ?: r.refundable
            if (amt.signum() <= 0) throw Conflict("Nothing is left to refund on this return.")
            if (amt > r.refundable) throw Invalid("At most ${r.refundable} can be refunded on this return.")
            if (input.paymentId != null) {
                val pay = paymentById(j, input.paymentId)
                if (pay.orderId != r.orderId || pay.kind != "charge") throw Invalid("That payment is not a charge on this return's order.")
            }
            j.sql("UPDATE rmas SET refund_total = refund_total + :a WHERE id = :id").param("a", amt).param("id", id).update()
            amt
        }
        if (input.paymentId != null) {
            val result = try { payments.refund(p.tenantId, input.paymentId, amount, input.reference, id, Actor.of(p)) }
                catch (e: Exception) { undo(p.tenantId, id, amount); throw e }
            if (result.status == "failed" || result.status == "cancelled") undo(p.tenantId, id, amount)
        }
        return db.tenant(p.tenantId) { j ->
            val status = lockStatus(j, id)
            if (status in setOf("approved", "received")) j.sql("UPDATE rmas SET status = 'refunded' WHERE id = :id").param("id", id).update()
            audit.record(j, p.tenantId, "rma.refund", "rma", id, mapOf("amount" to amount, "payment_id" to input.paymentId))
            emit(j, p.tenantId, "return.updated", id)
            one(j, id)
        }
    }

    private fun undo(tenantId: UUID, id: UUID, amount: BigDecimal) = db.tenant(tenantId) { j ->
        j.sql("UPDATE rmas SET refund_total = greatest(refund_total - :a, 0) WHERE id = :id").param("a", amount).param("id", id).update()
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/returns")
@Validated
class ReturnController(private val returns: ReturnService) {
    @GetMapping
    fun list(@RequestParam(required = false) @Pattern(regexp = "^(requested|approved|rejected|received|refunded|closed)$") status: String?,
             @RequestParam(name = "order_id", required = false) orderId: UUID?,
             @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
             @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = returns.list(Caller.user(), status, orderId, limit, offset)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: RmaIn) = returns.create(Caller.user(), body)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = returns.get(Caller.user(), id)

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: UUID, @Valid @RequestBody(required = false) body: RmaNoteIn?) = returns.approve(Caller.user(), id, body ?: RmaNoteIn())

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: UUID, @Valid @RequestBody(required = false) body: RmaNoteIn?) = returns.reject(Caller.user(), id, body ?: RmaNoteIn())

    @PostMapping("/{id}/receive")
    fun receive(@PathVariable id: UUID, @Valid @RequestBody body: RmaReceiveIn) = returns.receive(Caller.user(), id, body)

    @PostMapping("/{id}/refund")
    fun refund(@PathVariable id: UUID, @Valid @RequestBody body: RmaRefundIn) = returns.refund(Caller.user(), id, body)

    @PostMapping("/{id}/close")
    fun close(@PathVariable id: UUID, @Valid @RequestBody(required = false) body: RmaNoteIn?) = returns.close(Caller.user(), id, body ?: RmaNoteIn())
}
