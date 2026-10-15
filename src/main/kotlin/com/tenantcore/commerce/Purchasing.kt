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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class SupplierIn(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Email @field:Size(max = 320) val email: String? = null,
    @field:Size(max = 40) val phone: String? = null,
    @field:Size(max = 5000) val notes: String? = null,
)
data class SupplierPatchIn(
    @field:Size(min = 1, max = 200) val name: String? = null,
    @field:Email @field:Size(max = 320) val email: String? = null,
    @field:Size(max = 40) val phone: String? = null,
    @field:Size(max = 5000) val notes: String? = null,
    val isActive: Boolean? = null,
)
data class SupplierOut(val id: UUID, val name: String, val email: String?, val phone: String?, val notes: String?, val isActive: Boolean, val createdAt: OffsetDateTime)

data class PoLineIn(val variantId: UUID, @field:Min(1) @field:Max(10_000_000) val quantity: Int, val unitCost: BigDecimal = BigDecimal.ZERO)
data class PoIn(
    val supplierId: UUID,
    /** Where the goods will arrive; defaults to the default warehouse. */
    val warehouseId: UUID? = null,
    val expectedAt: LocalDate? = null,
    @field:Size(max = 5000) val notes: String? = null,
    @field:Size(min = 1, max = 500, message = "a purchase order needs 1 to 500 lines") val lines: List<@Valid PoLineIn>,
)
data class PoPatchIn(
    val supplierId: UUID? = null,
    val warehouseId: UUID? = null,
    val expectedAt: LocalDate? = null,
    @field:Size(max = 5000) val notes: String? = null,
    /** Replaces all lines (drafts only). */
    @field:Size(min = 1, max = 500) val lines: List<@Valid PoLineIn>? = null,
)
data class ReceiveLineIn(val lineId: UUID, @field:Min(1) @field:Max(10_000_000) val quantity: Int, val binId: UUID? = null)
data class ReceiveIn(
    @field:Size(min = 1, max = 500) val lines: List<@Valid ReceiveLineIn>,
    /** Bin for lines that do not name one; defaults to the warehouse's main bin. */
    val binId: UUID? = null,
    @field:Size(max = 500) val note: String? = null,
)

data class PoLineOut(val id: UUID, val variantId: UUID, val sku: String, val productName: String, val quantity: Int, val receivedQuantity: Int,
                     val unitCost: BigDecimal, val lineTotal: BigDecimal)
data class PoOut(
    val id: UUID, val number: Long, val supplierId: UUID, val supplierName: String, val warehouseId: UUID, val warehouseCode: String,
    val status: String, val currency: String, val expectedAt: LocalDate?, val notes: String?, val total: BigDecimal, val lines: List<PoLineOut>,
    val createdBy: UUID?, val orderedAt: OffsetDateTime?, val closedAt: OffsetDateTime?, val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
)

@Service
class PurchasingService(private val db: Db, private val audit: Audit, private val rows: Rows, private val stock: Stock) {

    // -- suppliers ---------------------------------------------------------------------------------

    private fun suppliers(j: JdbcClient, id: UUID? = null): List<SupplierOut> =
        j.sql("SELECT id, name, email, phone, notes, is_active, created_at FROM suppliers ${if (id != null) "WHERE id = :id" else ""} ORDER BY name")
            .apply { if (id != null) param("id", id) }
            .query { rs, _ -> SupplierOut(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6),
                rs.getObject(7, OffsetDateTime::class.java)) }.list()

    fun listSuppliers(p: UserPrincipal) = p.require("purchasing", Action.READ).let { db.tenant(p.tenantId) { suppliers(it) } }

    fun createSupplier(p: UserPrincipal, input: SupplierIn): SupplierOut {
        p.require("purchasing", Action.CREATE)
        return db.tenant(p.tenantId) { j ->
            val id = j.sql("INSERT INTO suppliers (tenant_id, name, email, phone, notes) VALUES (:t, :n, :e, :p, :no) RETURNING id")
                .param("t", p.tenantId).param("n", input.name.trim()).param("e", input.email?.trim()?.lowercase()).param("p", input.phone).param("no", input.notes)
                .query(UUID::class.java).single()
            audit.record(j, p.tenantId, "supplier.create", "purchasing", id, mapOf("name" to input.name))
            suppliers(j, id).single()
        }
    }

    fun patchSupplier(p: UserPrincipal, id: UUID, input: SupplierPatchIn): SupplierOut {
        p.require("purchasing", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            val s = suppliers(j, id).firstOrNull() ?: throw NotFound("Supplier")
            j.sql("UPDATE suppliers SET name = :n, email = :e, phone = :p, notes = :no, is_active = :a WHERE id = :id")
                .param("n", input.name?.trim() ?: s.name).param("e", input.email?.trim()?.lowercase() ?: s.email).param("p", input.phone ?: s.phone)
                .param("no", input.notes ?: s.notes).param("a", input.isActive ?: s.isActive).param("id", id).update()
            audit.record(j, p.tenantId, "supplier.update", "purchasing", id)
            suppliers(j, id).single()
        }
    }

    // -- purchase orders ---------------------------------------------------------------------------

    private fun load(j: JdbcClient, ids: List<UUID>): List<PoOut> {
        if (ids.isEmpty()) return emptyList()
        val lines = j.sql("""SELECT l.id, l.po_id, l.variant_id, v.sku, p.name, l.quantity, l.received_quantity, l.unit_cost
                             FROM purchase_order_lines l JOIN product_variants v ON v.id = l.variant_id JOIN products p ON p.id = v.product_id
                             WHERE l.po_id IN (:ids) ORDER BY v.sku""")
            .param("ids", ids).query { rs, _ ->
                rs.getObject(2, UUID::class.java) to PoLineOut(rs.getObject(1, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getString(5),
                    rs.getInt(6), rs.getInt(7), rs.getBigDecimal(8), Money.round(rs.getBigDecimal(8) * rs.getInt(6).toBigDecimal()))
            }.list().groupBy({ it.first }, { it.second })
        val pos = j.sql("""SELECT po.*, s.name AS supplier_name, w.code AS warehouse_code FROM purchase_orders po
                           JOIN suppliers s ON s.id = po.supplier_id JOIN warehouses w ON w.id = po.warehouse_id WHERE po.id IN (:ids)""")
            .param("ids", ids).query { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                val ls = lines[id].orEmpty()
                PoOut(id, rs.getLong("number"), rs.getObject("supplier_id", UUID::class.java), rs.getString("supplier_name"),
                    rs.getObject("warehouse_id", UUID::class.java), rs.getString("warehouse_code"), rs.getString("status"), rs.getString("currency"),
                    rs.getObject("expected_at", LocalDate::class.java), rs.getString("notes"), ls.fold(Money.ZERO) { a, l -> a + l.lineTotal }, ls,
                    rows.uuid(rs, "created_by"), rows.time(rs, "ordered_at"), rows.time(rs, "closed_at"), rows.time(rs, "created_at")!!, rows.time(rs, "updated_at")!!)
            }.list().associateBy { it.id }
        return ids.mapNotNull { pos[it] }
    }

    private fun one(j: JdbcClient, id: UUID) = load(j, listOf(id)).firstOrNull() ?: throw NotFound("Purchase order")

    private fun lockStatus(j: JdbcClient, id: UUID): String =
        j.sql("SELECT status FROM purchase_orders WHERE id = :id FOR UPDATE").param("id", id).query(String::class.java).optional().orElseThrow { NotFound("Purchase order") }

    fun list(p: UserPrincipal, status: String?, supplierId: UUID?, limit: Int, offset: Int): Map<String, Any> {
        p.require("purchasing", Action.READ)
        return db.tenant(p.tenantId) { j ->
            val where = buildList { if (status != null) add("status = :s"); if (supplierId != null) add("supplier_id = :sup") }
                .let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
            fun bind(s: JdbcClient.StatementSpec) = s.apply { if (status != null) param("s", status); if (supplierId != null) param("sup", supplierId) }
            val ids = bind(j.sql("SELECT id FROM purchase_orders $where ORDER BY number DESC LIMIT :l OFFSET :o")).param("l", limit).param("o", offset)
                .query(UUID::class.java).list()
            mapOf("items" to load(j, ids), "total" to bind(j.sql("SELECT count(*) FROM purchase_orders $where")).query(Long::class.java).single())
        }
    }

    fun get(p: UserPrincipal, id: UUID) = p.require("purchasing", Action.READ).let { db.tenant(p.tenantId) { one(it, id) } }

    private fun checkRefs(j: JdbcClient, supplierId: UUID?, warehouseId: UUID?) {
        if (supplierId != null && j.sql("SELECT count(*) FROM suppliers WHERE id = :id AND is_active").param("id", supplierId).query(Long::class.java).single() == 0L)
            throw Invalid("Supplier not found or inactive.")
        if (warehouseId != null && j.sql("SELECT count(*) FROM warehouses WHERE id = :id AND is_active").param("id", warehouseId).query(Long::class.java).single() == 0L)
            throw Invalid("Warehouse not found or inactive.")
    }

    private fun insertLines(j: JdbcClient, tenantId: UUID, poId: UUID, lines: List<PoLineIn>) {
        if (lines.map { it.variantId }.toSet().size != lines.size) throw Invalid("Each variant can appear only once on a purchase order.")
        val found = j.sql("SELECT count(*) FROM product_variants WHERE id IN (:ids)").param("ids", lines.map { it.variantId }).query(Long::class.java).single()
        if (found != lines.size.toLong()) throw Invalid("Some variants were not found.")
        lines.forEach { l ->
            j.sql("INSERT INTO purchase_order_lines (tenant_id, po_id, variant_id, quantity, unit_cost) VALUES (:t, :po, :v, :q, :c)")
                .param("t", tenantId).param("po", poId).param("v", l.variantId).param("q", l.quantity).param("c", Money.check(l.unitCost, "unit_cost")).update()
        }
    }

    fun create(p: UserPrincipal, input: PoIn): PoOut {
        p.require("purchasing", Action.CREATE)
        return db.tenant(p.tenantId) { j ->
            checkRefs(j, input.supplierId, input.warehouseId)
            val warehouse = input.warehouseId ?: j.sql("SELECT id FROM warehouses WHERE is_default").query(UUID::class.java).optional()
                .orElseThrow { Invalid("Create a warehouse first.") }
            val id = j.sql("""INSERT INTO purchase_orders (tenant_id, number, supplier_id, warehouse_id, currency, expected_at, notes, created_by)
                              VALUES (:t, :n, :s, :w, :c, :e, :no, :u) RETURNING id""")
                .param("t", p.tenantId).param("n", rows.next(j, p.tenantId, "purchase_order")).param("s", input.supplierId).param("w", warehouse)
                .param("c", rows.currency(j)).param("e", input.expectedAt).param("no", input.notes).param("u", p.userId).query(UUID::class.java).single()
            insertLines(j, p.tenantId, id, input.lines)
            audit.record(j, p.tenantId, "purchase_order.create", "purchasing", id, mapOf("lines" to input.lines.size))
            one(j, id)
        }
    }

    fun patch(p: UserPrincipal, id: UUID, input: PoPatchIn): PoOut {
        p.require("purchasing", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            if (lockStatus(j, id) != "draft") throw Conflict("Only draft purchase orders can be edited.")
            checkRefs(j, input.supplierId, input.warehouseId)
            val po = one(j, id)
            j.sql("UPDATE purchase_orders SET supplier_id = :s, warehouse_id = :w, expected_at = :e, notes = :n WHERE id = :id")
                .param("s", input.supplierId ?: po.supplierId).param("w", input.warehouseId ?: po.warehouseId)
                .param("e", input.expectedAt ?: po.expectedAt).param("n", input.notes ?: po.notes).param("id", id).update()
            input.lines?.let {
                j.sql("DELETE FROM purchase_order_lines WHERE po_id = :id").param("id", id).update()
                insertLines(j, p.tenantId, id, it)
            }
            audit.record(j, p.tenantId, "purchase_order.update", "purchasing", id)
            one(j, id)
        }
    }

    fun submit(p: UserPrincipal, id: UUID): PoOut {
        p.require("purchasing", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            if (lockStatus(j, id) != "draft") throw Conflict("Only a draft can be marked as ordered.")
            j.sql("UPDATE purchase_orders SET status = 'ordered', ordered_at = now() WHERE id = :id").param("id", id).update()
            audit.record(j, p.tenantId, "purchase_order.submit", "purchasing", id)
            one(j, id)
        }
    }

    /** Receiving puts stock into the PO's warehouse and records it in the ledger as a purchase. */
    fun receive(p: UserPrincipal, id: UUID, input: ReceiveIn): PoOut {
        p.require("purchasing", Action.UPDATE)
        p.requireModule("inventory")
        if (input.lines.map { it.lineId }.toSet().size != input.lines.size) throw Invalid("Each line can be listed only once.")
        return db.tenant(p.tenantId) { j ->
            val status = lockStatus(j, id)
            if (status !in setOf("ordered", "partially_received")) throw Conflict("Only ordered purchase orders can be received (this one is $status).")
            val po = one(j, id)
            val lines = po.lines.associateBy { it.id }
            input.lines.forEach { r ->
                val line = lines[r.lineId] ?: throw Invalid("Line ${r.lineId} is not on this purchase order.")
                val remaining = line.quantity - line.receivedQuantity
                if (r.quantity > remaining) throw Invalid("${line.sku}: only $remaining left to receive.")
                val chosen = r.binId ?: input.binId
                val bin = stock.resolveBin(j, chosen, if (chosen == null) po.warehouseId else null)
                val binWarehouse = j.sql("SELECT warehouse_id FROM bins WHERE id = :b").param("b", bin).query(UUID::class.java).single()
                if (binWarehouse != po.warehouseId) throw Invalid("The bin must be in the purchase order's warehouse (${po.warehouseCode}).")
                val tracked = j.sql("SELECT track_inventory FROM product_variants WHERE id = :v").param("v", line.variantId).query(Boolean::class.java).single()
                if (tracked) stock.change(j, p.tenantId, line.variantId, bin, r.quantity, "purchase", "purchase_order", id, input.note, p.userId)
                j.sql("UPDATE purchase_order_lines SET received_quantity = received_quantity + :q WHERE id = :id").param("q", r.quantity).param("id", r.lineId).update()
            }
            val open = j.sql("SELECT count(*) FROM purchase_order_lines WHERE po_id = :id AND received_quantity < quantity").param("id", id).query(Long::class.java).single()
            j.sql("UPDATE purchase_orders SET status = :s, closed_at = CASE WHEN :s = 'received' THEN now() END WHERE id = :id")
                .param("s", if (open == 0L) "received" else "partially_received").param("id", id).update()
            audit.record(j, p.tenantId, "purchase_order.receive", "purchasing", id, mapOf("lines" to input.lines.map { mapOf("line_id" to it.lineId, "quantity" to it.quantity) }))
            one(j, id)
        }
    }

    fun cancel(p: UserPrincipal, id: UUID): PoOut {
        p.require("purchasing", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            val status = lockStatus(j, id)
            if (status in setOf("received", "cancelled")) throw Conflict("This purchase order is already $status.")
            j.sql("UPDATE purchase_orders SET status = 'cancelled', closed_at = now() WHERE id = :id").param("id", id).update()
            audit.record(j, p.tenantId, "purchase_order.cancel", "purchasing", id, mapOf("was" to status))
            one(j, id)
        }
    }

    fun delete(p: UserPrincipal, id: UUID) {
        p.require("purchasing", Action.DELETE)
        db.tenant(p.tenantId) { j ->
            if (lockStatus(j, id) != "draft") throw Conflict("Only drafts can be deleted; cancel it instead.")
            j.sql("DELETE FROM purchase_orders WHERE id = :id").param("id", id).update()
            audit.record(j, p.tenantId, "purchase_order.delete", "purchasing", id)
        }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}")
@Validated
class PurchasingController(private val purchasing: PurchasingService) {
    @GetMapping("/suppliers")
    fun suppliers() = mapOf("items" to purchasing.listSuppliers(Caller.user()))

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSupplier(@Valid @RequestBody body: SupplierIn) = purchasing.createSupplier(Caller.user(), body)

    @PatchMapping("/suppliers/{id}")
    fun patchSupplier(@PathVariable id: UUID, @Valid @RequestBody body: SupplierPatchIn) = purchasing.patchSupplier(Caller.user(), id, body)

    @GetMapping("/purchase-orders")
    fun list(@RequestParam(required = false) @Pattern(regexp = "^(draft|ordered|partially_received|received|cancelled)$") status: String?,
             @RequestParam(name = "supplier_id", required = false) supplierId: UUID?,
             @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
             @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = purchasing.list(Caller.user(), status, supplierId, limit, offset)

    @PostMapping("/purchase-orders")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: PoIn) = purchasing.create(Caller.user(), body)

    @GetMapping("/purchase-orders/{id}")
    fun get(@PathVariable id: UUID) = purchasing.get(Caller.user(), id)

    @PatchMapping("/purchase-orders/{id}")
    fun patch(@PathVariable id: UUID, @Valid @RequestBody body: PoPatchIn) = purchasing.patch(Caller.user(), id, body)

    @PostMapping("/purchase-orders/{id}/submit")
    fun submit(@PathVariable id: UUID) = purchasing.submit(Caller.user(), id)

    @PostMapping("/purchase-orders/{id}/receive")
    fun receive(@PathVariable id: UUID, @Valid @RequestBody body: ReceiveIn) = purchasing.receive(Caller.user(), id, body)

    @PostMapping("/purchase-orders/{id}/cancel")
    fun cancel(@PathVariable id: UUID) = purchasing.cancel(Caller.user(), id)

    @DeleteMapping("/purchase-orders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = purchasing.delete(Caller.user(), id)
}
