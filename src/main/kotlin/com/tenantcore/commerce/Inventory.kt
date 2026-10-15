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
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

// ------------------------------------------------------------------------------------------------
// Stock engine: every change to stock_levels goes through here.
// ------------------------------------------------------------------------------------------------

/**
 * Rules that keep stock correct under concurrency:
 *  - rows are locked (SELECT ... FOR UPDATE) before they are read for a decision, always in
 *    (variant_id, bin_id) order, so two transactions cannot deadlock or both take the last unit;
 *  - the table's CHECK constraints (on_hand >= 0, 0 <= reserved <= on_hand) are a second line of
 *    defence: even a bug here cannot oversell;
 *  - every on-hand change writes a row to the append-only stock_movements ledger.
 */
@Component
class Stock {

    private data class Level(val variantId: UUID, val binId: UUID, var onHand: Int, var reserved: Int, val usable: Boolean, val preferred: Boolean) {
        val available get() = onHand - reserved
    }

    /** The bin to use: the given bin, else the warehouse's first active bin (MAIN first), else the default warehouse's. */
    fun resolveBin(j: JdbcClient, binId: UUID?, warehouseId: UUID?): UUID {
        if (binId != null) {
            val active = j.sql("SELECT b.is_active AND w.is_active FROM bins b JOIN warehouses w ON w.id = b.warehouse_id WHERE b.id = :b")
                .param("b", binId).query(Boolean::class.java).optional().orElseThrow { Invalid("Bin not found.") }
            if (!active) throw Invalid("That bin or its warehouse is inactive.")
            return binId
        }
        val where = if (warehouseId != null) "w.id = :w" else "w.is_default"
        return j.sql("""SELECT b.id FROM bins b JOIN warehouses w ON w.id = b.warehouse_id
                        WHERE $where AND b.is_active AND w.is_active ORDER BY (b.code = 'MAIN') DESC, b.code LIMIT 1""")
            .apply { if (warehouseId != null) param("w", warehouseId) }
            .query(UUID::class.java).optional()
            .orElseThrow { Invalid(if (warehouseId != null) "That warehouse has no active bin." else "Create a warehouse first (or choose a bin).") }
    }

    fun requireTracked(j: JdbcClient, variantId: UUID): String {
        val (sku, tracked) = j.sql("SELECT sku, track_inventory FROM product_variants WHERE id = :v").param("v", variantId)
            .query { rs, _ -> rs.getString(1) to rs.getBoolean(2) }.optional().orElseThrow { Invalid("Variant not found.") }
        if (!tracked) throw Invalid("The variant $sku does not track inventory.")
        return sku
    }

    /** Locks (creating if needed) one stock row and returns (on_hand, reserved). */
    fun lock(j: JdbcClient, tenantId: UUID, variantId: UUID, binId: UUID): Pair<Int, Int> {
        j.sql("INSERT INTO stock_levels (tenant_id, variant_id, bin_id) VALUES (:t, :v, :b) ON CONFLICT DO NOTHING")
            .param("t", tenantId).param("v", variantId).param("b", binId).update()
        return j.sql("SELECT on_hand, reserved FROM stock_levels WHERE variant_id = :v AND bin_id = :b FOR UPDATE")
            .param("v", variantId).param("b", binId).query { rs, _ -> rs.getInt(1) to rs.getInt(2) }.single()
    }

    private fun ledger(j: JdbcClient, tenantId: UUID, variantId: UUID, binId: UUID, qty: Int, kind: String, refType: String?, refId: UUID?, note: String?, actor: UUID?) {
        j.sql("""INSERT INTO stock_movements (tenant_id, variant_id, bin_id, quantity, kind, ref_type, ref_id, note, actor_id)
                 VALUES (:t, :v, :b, :q, :k, :rt, :ri, :n, :a)""")
            .param("t", tenantId).param("v", variantId).param("b", binId).param("q", qty).param("k", kind)
            .param("rt", refType).param("ri", refId).param("n", note).param("a", actor).update()
    }

    /** Changes on-hand by [delta]. Stock that is reserved for orders cannot be removed. */
    fun change(j: JdbcClient, tenantId: UUID, variantId: UUID, binId: UUID, delta: Int, kind: String,
               refType: String? = null, refId: UUID? = null, note: String? = null, actor: UUID? = null) {
        if (delta == 0) return
        val (onHand, reserved) = lock(j, tenantId, variantId, binId)
        if (onHand + delta < reserved) throw InsufficientStock(listOf(mapOf("variant_id" to variantId, "bin_id" to binId,
            "requested" to -delta, "available" to onHand - reserved)))
        j.sql("UPDATE stock_levels SET on_hand = on_hand + :d, updated_at = now() WHERE variant_id = :v AND bin_id = :b")
            .param("d", delta).param("v", variantId).param("b", binId).update()
        ledger(j, tenantId, variantId, binId, delta, kind, refType, refId, note, actor)
    }

    fun transfer(j: JdbcClient, tenantId: UUID, variantId: UUID, from: UUID, to: UUID, qty: Int, note: String?, actor: UUID?): UUID {
        if (from == to) throw Invalid("Choose two different bins.")
        // Lock both rows in a fixed order.
        listOf(from, to).sorted().forEach { lock(j, tenantId, variantId, it) }
        val ref = UUID.randomUUID()
        change(j, tenantId, variantId, from, -qty, "transfer", "transfer", ref, note, actor)
        change(j, tenantId, variantId, to, qty, "transfer", "transfer", ref, note, actor)
        return ref
    }

    private fun lockLevels(j: JdbcClient, variantIds: Collection<UUID>): MutableList<Level> =
        if (variantIds.isEmpty()) mutableListOf() else
        j.sql("""SELECT sl.variant_id, sl.bin_id, sl.on_hand, sl.reserved, b.is_active AND w.is_active, w.is_default
                 FROM stock_levels sl JOIN bins b ON b.id = sl.bin_id JOIN warehouses w ON w.id = b.warehouse_id
                 WHERE sl.variant_id IN (:v) ORDER BY sl.variant_id, sl.bin_id FOR UPDATE OF sl""")
            .param("v", variantIds.toList())
            .query { rs, _ -> Level(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getInt(3), rs.getInt(4), rs.getBoolean(5), rs.getBoolean(6)) }
            .list().toMutableList()

    /** Reserves stock for every tracked item of the order, all or nothing. Prefers the default warehouse. */
    fun reserveOrder(j: JdbcClient, tenantId: UUID, orderId: UUID) {
        data class Item(val id: UUID, val variantId: UUID, val qty: Int, val sku: String)
        val items = j.sql("""SELECT oi.id, oi.variant_id, oi.quantity, oi.sku FROM order_items oi JOIN product_variants v ON v.id = oi.variant_id
                             WHERE oi.order_id = :o AND v.track_inventory ORDER BY oi.variant_id, oi.id""")
            .param("o", orderId).query { rs, _ -> Item(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getInt(3), rs.getString(4)) }.list()
        if (items.isEmpty()) return
        val levels = lockLevels(j, items.map { it.variantId }.toSet())
        val plan = mutableListOf<Triple<Item, Level, Int>>()
        val short = mutableListOf<Map<String, Any?>>()
        items.forEach { item ->
            var need = item.qty
            levels.filter { it.variantId == item.variantId && it.usable && it.available > 0 }
                .sortedWith(compareByDescending<Level> { it.preferred }.thenByDescending { it.available }.thenBy { it.binId })
                .forEach { l -> if (need > 0) { val take = minOf(need, l.available); l.reserved += take; need -= take; plan += Triple(item, l, take) } }
            if (need > 0) short += mapOf("variant_id" to item.variantId, "sku" to item.sku, "requested" to item.qty, "available" to item.qty - need)
        }
        if (short.isNotEmpty()) throw InsufficientStock(short)
        plan.sortedWith(compareBy<Triple<Item, Level, Int>> { it.second.variantId }.thenBy { it.second.binId }).forEach { (item, l, q) ->
            j.sql("UPDATE stock_levels SET reserved = reserved + :q, updated_at = now() WHERE variant_id = :v AND bin_id = :b")
                .param("q", q).param("v", l.variantId).param("b", l.binId).update()
            j.sql("""INSERT INTO order_allocations (tenant_id, order_id, order_item_id, variant_id, bin_id, quantity, state)
                     VALUES (:t, :o, :i, :v, :b, :q, 'reserved')""")
                .param("t", tenantId).param("o", orderId).param("i", item.id).param("v", l.variantId).param("b", l.binId).param("q", q).update()
        }
    }

    private data class Alloc(val id: UUID, val variantId: UUID, val binId: UUID, val qty: Int)

    private fun reservedAllocations(j: JdbcClient, orderId: UUID) =
        j.sql("SELECT id, variant_id, bin_id, quantity FROM order_allocations WHERE order_id = :o AND state = 'reserved' ORDER BY variant_id, bin_id")
            .param("o", orderId).query { rs, _ -> Alloc(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getInt(4)) }.list()

    /** Ships reserved stock: on-hand and reserved both drop, and the ledger records a sale. */
    fun deductOrder(j: JdbcClient, tenantId: UUID, orderId: UUID, actor: UUID?) {
        val allocs = reservedAllocations(j, orderId)
        lockLevels(j, allocs.map { it.variantId }.toSet())
        allocs.forEach { a ->
            j.sql("UPDATE stock_levels SET on_hand = on_hand - :q, reserved = reserved - :q, updated_at = now() WHERE variant_id = :v AND bin_id = :b")
                .param("q", a.qty).param("v", a.variantId).param("b", a.binId).update()
            j.sql("UPDATE order_allocations SET state = 'deducted' WHERE id = :id").param("id", a.id).update()
            ledger(j, tenantId, a.variantId, a.binId, -a.qty, "sale", "order", orderId, null, actor)
        }
    }

    /** Gives reserved (not yet shipped) stock back. */
    fun releaseOrder(j: JdbcClient, orderId: UUID) {
        val allocs = reservedAllocations(j, orderId)
        lockLevels(j, allocs.map { it.variantId }.toSet())
        allocs.forEach { a ->
            j.sql("UPDATE stock_levels SET reserved = reserved - :q, updated_at = now() WHERE variant_id = :v AND bin_id = :b")
                .param("q", a.qty).param("v", a.variantId).param("b", a.binId).update()
            j.sql("UPDATE order_allocations SET state = 'released' WHERE id = :id").param("id", a.id).update()
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Model
// ------------------------------------------------------------------------------------------------

private const val CODE = "^[A-Za-z0-9._-]{1,32}$"

data class WarehouseIn(
    @field:Pattern(regexp = CODE, message = "letters, digits and . _ - (1-32)") val code: String,
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Size(max = 2000) val address: String? = null,
    val isDefault: Boolean = false,
)
data class WarehousePatchIn(
    @field:Size(min = 1, max = 200) val name: String? = null,
    @field:Size(max = 2000) val address: String? = null,
    val isActive: Boolean? = null,
    /** true makes this the default warehouse (orders take stock from it first). */
    val isDefault: Boolean? = null,
)
data class BinIn(@field:Pattern(regexp = CODE, message = "letters, digits and . _ - (1-32)") val code: String, @field:Size(max = 200) val name: String? = null)
data class BinPatchIn(@field:Size(max = 200) val name: String? = null, val isActive: Boolean? = null)
data class BinOut(val id: UUID, val warehouseId: UUID, val code: String, val name: String?, val isActive: Boolean)
data class WarehouseOut(val id: UUID, val code: String, val name: String, val address: String?, val isActive: Boolean, val isDefault: Boolean, val bins: List<BinOut>)

data class AdjustmentIn(
    val variantId: UUID,
    val binId: UUID? = null,
    /** Instead of a bin: the warehouse's main bin. Neither: the default warehouse. */
    val warehouseId: UUID? = null,
    /** Change by this many (negative removes). */
    val quantity: Int? = null,
    /** Or: set the bin's on-hand count to this (a stock count). */
    @field:Min(0) val setTo: Int? = null,
    @field:Size(max = 500) val note: String? = null,
)
data class TransferIn(
    val variantId: UUID, val fromBinId: UUID, val toBinId: UUID,
    @field:Min(1) @field:Max(10_000_000) val quantity: Int,
    @field:Size(max = 500) val note: String? = null,
)

data class BinStock(val binId: UUID, val binCode: String, val warehouseId: UUID, val warehouseCode: String, val onHand: Int, val reserved: Int, val available: Int)
data class StockRow(
    val variantId: UUID, val productId: UUID, val productName: String, val sku: String, val variantName: String?,
    val onHand: Int, val reserved: Int, val available: Int, val lowStockThreshold: Int?, val isLow: Boolean, val bins: List<BinStock>,
)
data class MovementOut(
    val id: UUID, val variantId: UUID, val sku: String, val binId: UUID, val binCode: String, val warehouseCode: String, val quantity: Int,
    val kind: String, val refType: String?, val refId: UUID?, val note: String?, val actorId: UUID?, val createdAt: OffsetDateTime,
)

// ------------------------------------------------------------------------------------------------
// Service
// ------------------------------------------------------------------------------------------------

@Service
class InventoryService(private val db: Db, private val audit: Audit, private val stock: Stock, private val rows: Rows) {

    // -- warehouses and bins -----------------------------------------------------------------------

    fun warehouses(j: JdbcClient): List<WarehouseOut> {
        val bins = j.sql("SELECT id, warehouse_id, code, name, is_active FROM bins ORDER BY code").query { rs, _ ->
            BinOut(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getBoolean(5))
        }.list().groupBy { it.warehouseId }
        return j.sql("SELECT id, code, name, address, is_active, is_default FROM warehouses ORDER BY is_default DESC, code").query { rs, _ ->
            val id = rs.getObject(1, UUID::class.java)
            WarehouseOut(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), bins[id].orEmpty())
        }.list()
    }

    fun listWarehouses(p: UserPrincipal) = p.require("inventory", Action.READ).let { db.tenant(p.tenantId) { warehouses(it) } }

    private fun warehouse(j: JdbcClient, id: UUID) = warehouses(j).firstOrNull { it.id == id } ?: throw NotFound("Warehouse")

    /** A new warehouse gets a MAIN bin; the first warehouse becomes the default. */
    fun createWarehouse(p: UserPrincipal, input: WarehouseIn): WarehouseOut {
        p.require("inventory", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            if (j.sql("SELECT count(*) FROM warehouses WHERE code = :c").param("c", input.code).query(Long::class.java).single() > 0)
                throw Conflict("A warehouse with code '${input.code}' already exists.")
            val first = j.sql("SELECT count(*) FROM warehouses").query(Long::class.java).single() == 0L
            if (input.isDefault) j.sql("UPDATE warehouses SET is_default = false WHERE is_default").update()
            val id = j.sql("INSERT INTO warehouses (tenant_id, code, name, address, is_default) VALUES (:t, :c, :n, :a, :d) RETURNING id")
                .param("t", p.tenantId).param("c", input.code).param("n", input.name.trim()).param("a", input.address).param("d", input.isDefault || first)
                .query(UUID::class.java).single()
            j.sql("INSERT INTO bins (tenant_id, warehouse_id, code, name) VALUES (:t, :w, 'MAIN', 'Main')").param("t", p.tenantId).param("w", id).update()
            audit.record(j, p.tenantId, "warehouse.create", "inventory", id, mapOf("code" to input.code))
            warehouse(j, id)
        }
    }

    fun patchWarehouse(p: UserPrincipal, id: UUID, input: WarehousePatchIn): WarehouseOut {
        p.require("inventory", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            val w = warehouse(j, id)
            if (input.isDefault == false && w.isDefault) throw Invalid("Make another warehouse the default instead.")
            if ((input.isDefault ?: w.isDefault) && !(input.isActive ?: w.isActive)) throw Invalid("The default warehouse cannot be deactivated.")
            if (input.isDefault == true && !w.isDefault) j.sql("UPDATE warehouses SET is_default = false WHERE is_default").update()
            j.sql("UPDATE warehouses SET name = :n, address = :a, is_active = :act, is_default = :d WHERE id = :id")
                .param("n", input.name?.trim() ?: w.name).param("a", input.address ?: w.address).param("act", input.isActive ?: w.isActive)
                .param("d", input.isDefault ?: w.isDefault).param("id", id).update()
            audit.record(j, p.tenantId, "warehouse.update", "inventory", id, mapOf("is_active" to input.isActive, "is_default" to input.isDefault))
            warehouse(j, id)
        }
    }

    fun createBin(p: UserPrincipal, warehouseId: UUID, input: BinIn): WarehouseOut {
        p.require("inventory", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            val w = warehouse(j, warehouseId)
            if (w.bins.any { it.code.equals(input.code, true) }) throw Conflict("This warehouse already has a bin '${input.code}'.")
            val id = j.sql("INSERT INTO bins (tenant_id, warehouse_id, code, name) VALUES (:t, :w, :c, :n) RETURNING id")
                .param("t", p.tenantId).param("w", warehouseId).param("c", input.code).param("n", input.name).query(UUID::class.java).single()
            audit.record(j, p.tenantId, "bin.create", "inventory", id, mapOf("warehouse_id" to warehouseId, "code" to input.code))
            warehouse(j, warehouseId)
        }
    }

    fun patchBin(p: UserPrincipal, warehouseId: UUID, binId: UUID, input: BinPatchIn): WarehouseOut {
        p.require("inventory", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            val bin = warehouse(j, warehouseId).bins.firstOrNull { it.id == binId } ?: throw NotFound("Bin")
            if (input.isActive == false) {
                val held = j.sql("SELECT coalesce(sum(on_hand), 0) FROM stock_levels WHERE bin_id = :b").param("b", binId).query(Long::class.java).single()
                if (held > 0) throw Conflict("Move the stock out of this bin before deactivating it ($held units).")
            }
            j.sql("UPDATE bins SET name = :n, is_active = :a WHERE id = :id").param("n", input.name ?: bin.name).param("a", input.isActive ?: bin.isActive)
                .param("id", binId).update()
            audit.record(j, p.tenantId, "bin.update", "inventory", binId, mapOf("is_active" to input.isActive))
            warehouse(j, warehouseId)
        }
    }

    // -- stock -------------------------------------------------------------------------------------

    fun stockRows(j: JdbcClient, q: String?, warehouseId: UUID?, lowOnly: Boolean, variantIds: List<UUID>?, limit: Int, offset: Int): Pair<List<StockRow>, Long> {
        val levelFilter = if (warehouseId != null) "AND sl.bin_id IN (SELECT id FROM bins WHERE warehouse_id = :w)" else ""
        val where = buildList {
            add("v.track_inventory")
            if (!q.isNullOrBlank()) add("(v.sku ILIKE :q OR p.name ILIKE :q OR v.barcode = :raw)")
            if (variantIds != null) add("v.id IN (:ids)")
        }.joinToString(" AND ")
        val having = if (lowOnly) "HAVING coalesce(sum(sl.on_hand), 0) - coalesce(sum(sl.reserved), 0) <= coalesce(v.low_stock_threshold, 0)" else ""
        val base = """SELECT v.id, p.id AS product_id, p.name AS product_name, v.sku, v.name AS variant_name, v.low_stock_threshold,
                             coalesce(sum(sl.on_hand), 0) AS on_hand, coalesce(sum(sl.reserved), 0) AS reserved
                      FROM product_variants v JOIN products p ON p.id = v.product_id
                      LEFT JOIN stock_levels sl ON sl.variant_id = v.id $levelFilter
                      WHERE $where GROUP BY v.id, p.id $having"""
        fun bind(s: JdbcClient.StatementSpec) = s.apply {
            if (warehouseId != null) param("w", warehouseId)
            if (!q.isNullOrBlank()) { param("q", rows.like(q.trim())); param("raw", q.trim()) }
            if (variantIds != null) param("ids", variantIds)
        }
        val total = bind(j.sql("SELECT count(*) FROM ($base) x")).query(Long::class.java).single()
        data class Agg(val v: UUID, val pid: UUID, val pname: String, val sku: String, val vname: String?, val low: Int?, val onHand: Int, val reserved: Int)
        val aggs = bind(j.sql("$base ORDER BY p.name, v.sku LIMIT :l OFFSET :o")).param("l", limit).param("o", offset).query { rs, _ ->
            Agg(rs.getObject("id", UUID::class.java), rs.getObject("product_id", UUID::class.java), rs.getString("product_name"), rs.getString("sku"),
                rs.getString("variant_name"), rows.int(rs, "low_stock_threshold"), rs.getInt("on_hand"), rs.getInt("reserved"))
        }.list()
        val bins = if (aggs.isEmpty()) emptyMap() else
            j.sql("""SELECT sl.variant_id, b.id, b.code, w.id, w.code, sl.on_hand, sl.reserved FROM stock_levels sl
                     JOIN bins b ON b.id = sl.bin_id JOIN warehouses w ON w.id = b.warehouse_id
                     WHERE sl.variant_id IN (:ids) ${if (warehouseId != null) "AND w.id = :w" else ""} AND (sl.on_hand > 0 OR sl.reserved > 0)
                     ORDER BY w.code, b.code""")
                .param("ids", aggs.map { it.v }).apply { if (warehouseId != null) param("w", warehouseId) }
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to BinStock(rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java),
                    rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(6) - rs.getInt(7)) }
                .list().groupBy({ it.first }, { it.second })
        return aggs.map { a ->
            StockRow(a.v, a.pid, a.pname, a.sku, a.vname, a.onHand, a.reserved, a.onHand - a.reserved, a.low,
                a.onHand - a.reserved <= (a.low ?: 0), bins[a.v].orEmpty())
        } to total
    }

    fun stock(p: UserPrincipal, q: String?, warehouseId: UUID?, lowOnly: Boolean, limit: Int, offset: Int): Map<String, Any> {
        p.require("inventory", Action.READ)
        return db.tenant(p.tenantId) { j -> stockRows(j, q, warehouseId, lowOnly, null, limit, offset).let { mapOf("items" to it.first, "total" to it.second) } }
    }

    private fun stockOf(j: JdbcClient, variantId: UUID) = stockRows(j, null, null, false, listOf(variantId), 1, 0).first.first()

    fun adjust(p: UserPrincipal, input: AdjustmentIn): StockRow {
        p.require("inventory", Action.UPDATE)
        if ((input.quantity == null) == (input.setTo == null)) throw Invalid("Send either quantity (a change) or set_to (a count).")
        if (input.quantity == 0) throw Invalid("quantity cannot be 0.")
        return db.tenant(p.tenantId) { j ->
            stock.requireTracked(j, input.variantId)
            val bin = stock.resolveBin(j, input.binId, input.warehouseId)
            val delta = input.quantity ?: run {
                input.setTo!! - stock.lock(j, p.tenantId, input.variantId, bin).first
            }
            stock.change(j, p.tenantId, input.variantId, bin, delta, "adjustment", note = input.note, actor = p.userId)
            audit.record(j, p.tenantId, "stock.adjust", "inventory", input.variantId, mapOf("bin_id" to bin, "delta" to delta, "note" to input.note))
            stockOf(j, input.variantId)
        }
    }

    fun transfer(p: UserPrincipal, input: TransferIn): StockRow {
        p.require("inventory", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            stock.requireTracked(j, input.variantId)
            stock.resolveBin(j, input.fromBinId, null); stock.resolveBin(j, input.toBinId, null)
            val ref = stock.transfer(j, p.tenantId, input.variantId, input.fromBinId, input.toBinId, input.quantity, input.note, p.userId)
            audit.record(j, p.tenantId, "stock.transfer", "inventory", input.variantId,
                mapOf("from" to input.fromBinId, "to" to input.toBinId, "quantity" to input.quantity, "ref" to ref))
            stockOf(j, input.variantId)
        }
    }

    fun movements(p: UserPrincipal, variantId: UUID?, binId: UUID?, kind: String?, limit: Int, offset: Int): Map<String, Any> {
        p.require("inventory", Action.READ)
        return db.tenant(p.tenantId) { j ->
            val where = buildList {
                if (variantId != null) add("m.variant_id = :v")
                if (binId != null) add("m.bin_id = :b")
                if (kind != null) add("m.kind = :k")
            }.let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
            fun bind(s: JdbcClient.StatementSpec) = s.apply {
                if (variantId != null) param("v", variantId); if (binId != null) param("b", binId); if (kind != null) param("k", kind)
            }
            val items = bind(j.sql("""SELECT m.*, v.sku, b.code AS bin_code, w.code AS warehouse_code FROM stock_movements m
                                      JOIN product_variants v ON v.id = m.variant_id JOIN bins b ON b.id = m.bin_id JOIN warehouses w ON w.id = b.warehouse_id
                                      $where ORDER BY m.created_at DESC, m.id LIMIT :l OFFSET :o"""))
                .param("l", limit).param("o", offset).query { rs, _ ->
                    MovementOut(rs.getObject("id", UUID::class.java), rs.getObject("variant_id", UUID::class.java), rs.getString("sku"),
                        rs.getObject("bin_id", UUID::class.java), rs.getString("bin_code"), rs.getString("warehouse_code"), rs.getInt("quantity"),
                        rs.getString("kind"), rs.getString("ref_type"), rows.uuid(rs, "ref_id"), rs.getString("note"), rows.uuid(rs, "actor_id"),
                        rows.time(rs, "created_at")!!)
                }.list()
            mapOf("items" to items, "total" to bind(j.sql("SELECT count(*) FROM stock_movements m $where")).query(Long::class.java).single())
        }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/inventory")
@Validated
class InventoryController(private val inventory: InventoryService) {

    @GetMapping("/warehouses")
    fun warehouses() = mapOf("items" to inventory.listWarehouses(Caller.user()))

    @PostMapping("/warehouses")
    @ResponseStatus(HttpStatus.CREATED)
    fun createWarehouse(@Valid @RequestBody body: WarehouseIn) = inventory.createWarehouse(Caller.user(), body)

    @PatchMapping("/warehouses/{id}")
    fun patchWarehouse(@PathVariable id: UUID, @Valid @RequestBody body: WarehousePatchIn) = inventory.patchWarehouse(Caller.user(), id, body)

    @PostMapping("/warehouses/{id}/bins")
    @ResponseStatus(HttpStatus.CREATED)
    fun createBin(@PathVariable id: UUID, @Valid @RequestBody body: BinIn) = inventory.createBin(Caller.user(), id, body)

    @PatchMapping("/warehouses/{id}/bins/{binId}")
    fun patchBin(@PathVariable id: UUID, @PathVariable binId: UUID, @Valid @RequestBody body: BinPatchIn) = inventory.patchBin(Caller.user(), id, binId, body)

    @GetMapping("/stock")
    fun stock(@RequestParam(required = false) @Size(max = 100) q: String?,
              @RequestParam(name = "warehouse_id", required = false) warehouseId: UUID?,
              @RequestParam(name = "low_only", defaultValue = "false") lowOnly: Boolean,
              @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
              @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = inventory.stock(Caller.user(), q, warehouseId, lowOnly, limit, offset)

    @PostMapping("/adjustments")
    fun adjust(@Valid @RequestBody body: AdjustmentIn) = inventory.adjust(Caller.user(), body)

    @PostMapping("/transfers")
    fun transfer(@Valid @RequestBody body: TransferIn) = inventory.transfer(Caller.user(), body)

    @GetMapping("/movements")
    fun movements(@RequestParam(name = "variant_id", required = false) variantId: UUID?,
                  @RequestParam(name = "bin_id", required = false) binId: UUID?,
                  @RequestParam(required = false) @Pattern(regexp = "^(adjustment|transfer|purchase|sale|return)$") kind: String?,
                  @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
                  @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = inventory.movements(Caller.user(), variantId, binId, kind, limit, offset)
}
