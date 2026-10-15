package com.tenantcore.commerce

import com.tenantcore.audit.Audit
import com.tenantcore.common.Conflict
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.db.Db
import com.tenantcore.files.FileService
import com.tenantcore.files.FileTypes
import com.tenantcore.security.Action
import com.tenantcore.security.UserPrincipal
import com.tenantcore.security.randomToken
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.dao.DataIntegrityViolationException
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
import java.time.OffsetDateTime
import java.util.UUID

// ------------------------------------------------------------------------------------------------
// Model
// ------------------------------------------------------------------------------------------------

private const val SKU = "^[A-Za-z0-9._/-]{1,64}$"
private const val SLUG = "^[a-z0-9][a-z0-9-]{0,99}$"

data class VariantIn(
    @field:Pattern(regexp = SKU, message = "letters, digits and . _ / - (1-64)") val sku: String,
    @field:Size(max = 200) val name: String? = null,
    val options: Map<String, Any?>? = null,
    val price: BigDecimal,
    val compareAtPrice: BigDecimal? = null,
    val cost: BigDecimal? = null,
    @field:Size(max = 64) val barcode: String? = null,
    @field:Min(0) val weightGrams: Int? = null,
    val trackInventory: Boolean = true,
    @field:Min(0) val lowStockThreshold: Int? = null,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
)

data class VariantPatchIn(
    @field:Pattern(regexp = SKU, message = "letters, digits and . _ / - (1-64)") val sku: String? = null,
    @field:Size(max = 200) val name: String? = null,
    val options: Map<String, Any?>? = null,
    val price: BigDecimal? = null,
    val compareAtPrice: BigDecimal? = null,
    val cost: BigDecimal? = null,
    @field:Size(max = 64) val barcode: String? = null,
    @field:Min(0) val weightGrams: Int? = null,
    val trackInventory: Boolean? = null,
    @field:Min(0) val lowStockThreshold: Int? = null,
    val isActive: Boolean? = null,
    val sortOrder: Int? = null,
    /** Optional fields to empty: name, compare_at_price, cost, barcode, weight_grams, low_stock_threshold. */
    val clear: List<String>? = null,
)

data class ProductIn(
    @field:NotBlank @field:Size(max = 300) val name: String,
    @field:Pattern(regexp = SLUG, message = "lowercase letters, digits and dashes") val slug: String? = null,
    @field:Size(max = 20_000) val description: String? = null,
    @field:Pattern(regexp = "^(draft|active|archived)$", message = "must be draft, active or archived") val status: String = "draft",
    val categoryId: UUID? = null,
    val attributes: Map<String, Any?>? = null,
    @field:Size(min = 1, max = 100, message = "a product needs 1 to 100 variants") val variants: List<@Valid VariantIn>,
    @field:Size(max = 20) val imageFileIds: List<UUID> = emptyList(),
)

data class ProductPatchIn(
    @field:Size(min = 1, max = 300) val name: String? = null,
    @field:Pattern(regexp = SLUG, message = "lowercase letters, digits and dashes") val slug: String? = null,
    @field:Size(max = 20_000) val description: String? = null,
    @field:Pattern(regexp = "^(draft|active|archived)$", message = "must be draft, active or archived") val status: String? = null,
    val categoryId: UUID? = null,
    val clearCategory: Boolean? = null,
    val attributes: Map<String, Any?>? = null,
    /** Replaces the product's images, in this order. */
    @field:Size(max = 20) val imageFileIds: List<UUID>? = null,
)

data class StockSummary(val onHand: Int, val reserved: Int, val available: Int)
data class ImageOut(val fileId: UUID, val url: String, val thumbnailUrl: String?)

data class VariantOut(
    val id: UUID, val productId: UUID, val sku: String, val name: String?, val options: Map<String, Any?>, val price: BigDecimal,
    val compareAtPrice: BigDecimal?, val cost: BigDecimal?, val barcode: String?, val weightGrams: Int?, val trackInventory: Boolean,
    val lowStockThreshold: Int?, val isActive: Boolean, val sortOrder: Int, val stock: StockSummary?,
)

data class ProductOut(
    val id: UUID, val name: String, val slug: String, val description: String?, val status: String, val categoryId: UUID?,
    val attributes: Map<String, Any?>, val variants: List<VariantOut>, val images: List<ImageOut>,
    val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
)

data class CategoryIn(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Pattern(regexp = SLUG, message = "lowercase letters, digits and dashes") val slug: String? = null,
    val parentId: UUID? = null,
    val sortOrder: Int = 0,
)
data class CategoryPatchIn(
    @field:Size(min = 1, max = 200) val name: String? = null,
    @field:Pattern(regexp = SLUG, message = "lowercase letters, digits and dashes") val slug: String? = null,
    val parentId: UUID? = null,
    val clearParent: Boolean? = null,
    val sortOrder: Int? = null,
)
data class CategoryOut(val id: UUID, val name: String, val slug: String, val parentId: UUID?, val sortOrder: Int, val productCount: Int)

// ------------------------------------------------------------------------------------------------
// Service
// ------------------------------------------------------------------------------------------------

@Service
class CatalogService(
    private val db: Db, private val audit: Audit, private val rows: Rows, private val files: FileService,
    @org.springframework.context.annotation.Lazy private val events: com.tenantcore.integrations.Events,
) {
    private fun emit(j: JdbcClient, tenantId: UUID, type: String, p: ProductOut) = events.emit(j, tenantId, type, mapOf("id" to p.id, "name" to p.name,
        "slug" to p.slug, "status" to p.status, "variants" to p.variants.map { mapOf("id" to it.id, "sku" to it.sku, "price" to it.price, "is_active" to it.isActive) }))


    // -- reading -----------------------------------------------------------------------------------

    /** Products with their variants, images and (when inventory is on) stock totals, in the order of [ids]. */
    fun load(j: JdbcClient, tenantId: UUID, ids: List<UUID>): List<ProductOut> {
        if (ids.isEmpty()) return emptyList()
        val withStock = rows.moduleOn(j, "inventory")
        val stock = if (withStock) j.sql("""SELECT sl.variant_id, sum(sl.on_hand), sum(sl.reserved) FROM stock_levels sl
                                            JOIN product_variants v ON v.id = sl.variant_id WHERE v.product_id IN (:ids) GROUP BY sl.variant_id""")
            .param("ids", ids).query { rs, _ -> rs.getObject(1, UUID::class.java) to (rs.getInt(2) to rs.getInt(3)) }.list().toMap()
        else emptyMap()
        val variants = j.sql("SELECT * FROM product_variants WHERE product_id IN (:ids) ORDER BY sort_order, sku")
            .param("ids", ids).query { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                val track = rs.getBoolean("track_inventory")
                VariantOut(id, rs.getObject("product_id", UUID::class.java), rs.getString("sku"), rs.getString("name"), rows.json(rs, "options") ?: emptyMap(),
                    rs.getBigDecimal("price"), rs.getBigDecimal("compare_at_price"), rs.getBigDecimal("cost"), rs.getString("barcode"),
                    rows.int(rs, "weight_grams"), track, rows.int(rs, "low_stock_threshold"), rs.getBoolean("is_active"), rs.getInt("sort_order"),
                    if (withStock && track) (stock[id] ?: (0 to 0)).let { (h, r) -> StockSummary(h, r, h - r) } else null)
            }.list().groupBy { it.productId }
        val images = imagesOf(j, tenantId, ids)
        val products = j.sql("SELECT * FROM products WHERE id IN (:ids)").param("ids", ids).query { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            ProductOut(id, rs.getString("name"), rs.getString("slug"), rs.getString("description"), rs.getString("status"),
                rows.uuid(rs, "category_id"), rows.json(rs, "attributes") ?: emptyMap(), variants[id].orEmpty(), images[id].orEmpty(),
                rows.time(rs, "created_at")!!, rows.time(rs, "updated_at")!!)
        }.list().associateBy { it.id }
        return ids.mapNotNull { products[it] }
    }

    fun imagesOf(j: JdbcClient, tenantId: UUID, productIds: List<UUID>): Map<UUID, List<ImageOut>> {
        if (productIds.isEmpty()) return emptyMap()
        return j.sql("""SELECT pi.product_id, pi.file_id, f.thumb_key IS NOT NULL FROM product_images pi JOIN files f ON f.id = pi.file_id
                        WHERE pi.product_id IN (:ids) ORDER BY pi.sort_order""")
            .param("ids", productIds).query { rs, _ ->
                val fid = rs.getObject(2, UUID::class.java)
                rs.getObject(1, UUID::class.java) to ImageOut(fid, files.publicPath(tenantId, fid), if (rs.getBoolean(3)) files.publicPath(tenantId, fid, thumb = true) else null)
            }.list().groupBy({ it.first }, { it.second })
    }

    private fun one(j: JdbcClient, tenantId: UUID, id: UUID) = load(j, tenantId, listOf(id)).firstOrNull() ?: throw NotFound("Product")

    fun list(p: UserPrincipal, q: String?, status: String?, categoryId: UUID?, limit: Int, offset: Int): Map<String, Any> {
        p.require("products", Action.READ)
        return db.tenant(p.tenantId) { j ->
            val where = buildList {
                if (!q.isNullOrBlank()) add("(p.name ILIKE :q OR EXISTS (SELECT 1 FROM product_variants v WHERE v.product_id = p.id AND (v.sku ILIKE :q OR v.barcode = :raw)))")
                if (status != null) add("p.status = :s")
                if (categoryId != null) add("p.category_id = :c")
            }.let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
            fun bind(s: JdbcClient.StatementSpec) = s.apply {
                if (!q.isNullOrBlank()) { param("q", rows.like(q.trim())); param("raw", q.trim()) }
                if (status != null) param("s", status)
                if (categoryId != null) param("c", categoryId)
            }
            val ids = bind(j.sql("SELECT p.id FROM products p $where ORDER BY p.created_at DESC, p.id LIMIT :l OFFSET :o"))
                .param("l", limit).param("o", offset).query(UUID::class.java).list()
            mapOf("items" to load(j, p.tenantId, ids), "total" to bind(j.sql("SELECT count(*) FROM products p $where")).query(Long::class.java).single())
        }
    }

    fun get(p: UserPrincipal, id: UUID): ProductOut { p.require("products", Action.READ); return db.tenant(p.tenantId) { one(it, p.tenantId, id) } }

    // -- writing -----------------------------------------------------------------------------------

    private fun checkVariant(v: VariantIn) {
        Money.check(v.price, "price"); v.compareAtPrice?.let { Money.check(it, "compare_at_price") }; v.cost?.let { Money.check(it, "cost") }
        rows.limitJson(v.options, "options", 4096)
    }

    private fun insertVariant(j: JdbcClient, tenantId: UUID, productId: UUID, v: VariantIn): UUID {
        checkVariant(v)
        if (j.sql("SELECT count(*) FROM product_variants WHERE sku = :s").param("s", v.sku).query(Long::class.java).single() > 0)
            throw Conflict("The SKU '${v.sku}' is already used by another variant.")
        return j.sql("""INSERT INTO product_variants (tenant_id, product_id, sku, name, options, price, compare_at_price, cost, barcode,
                            weight_grams, track_inventory, low_stock_threshold, is_active, sort_order)
                        VALUES (:t, :p, :sku, :n, CAST(:o AS jsonb), :price, :cap, :cost, :bc, :w, :track, :low, :act, :sort) RETURNING id""")
            .param("t", tenantId).param("p", productId).param("sku", v.sku).param("n", v.name?.trim()).param("o", rows.jsonParam(v.options ?: emptyMap<String, Any?>()))
            .param("price", Money.check(v.price, "price")).param("cap", v.compareAtPrice).param("cost", v.cost).param("bc", v.barcode)
            .param("w", v.weightGrams).param("track", v.trackInventory).param("low", v.lowStockThreshold).param("act", v.isActive).param("sort", v.sortOrder)
            .query(UUID::class.java).single()
    }

    private fun uniqueSlug(j: JdbcClient, table: String, wanted: String?, name: String, max: Int): String {
        fun taken(s: String) = j.sql("SELECT count(*) FROM $table WHERE slug = :s").param("s", s).query(Long::class.java).single() > 0
        if (wanted != null) { if (taken(wanted)) throw Conflict("The slug '$wanted' is already used."); return wanted }
        val base = slugify(name, max - 8)
        if (!taken(base)) return base
        repeat(5) { val s = "$base-${randomToken(4).lowercase().filter { it.isLetterOrDigit() }.take(6)}"; if (!taken(s)) return s }
        throw Conflict("Could not generate a unique slug; send one explicitly.")
    }

    private fun checkCategory(j: JdbcClient, id: UUID?) {
        if (id != null && j.sql("SELECT count(*) FROM product_categories WHERE id = :id").param("id", id).query(Long::class.java).single() == 0L)
            throw Invalid("Category not found.")
    }

    /** Product images must be the tenant's own images; attaching one makes it public so shop pages can show it. */
    private fun setImages(j: JdbcClient, tenantId: UUID, productId: UUID, fileIds: List<UUID>) {
        if (fileIds.toSet().size != fileIds.size) throw Invalid("The same image is listed twice.")
        if (fileIds.isNotEmpty()) {
            val types = j.sql("SELECT id, content_type FROM files WHERE id IN (:ids)").param("ids", fileIds)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list().toMap()
            fileIds.forEach { id ->
                val t = types[id] ?: throw Invalid("Image $id not found.")
                if (t !in FileTypes.IMAGES) throw Invalid("File $id is not an image.")
            }
        }
        j.sql("DELETE FROM product_images WHERE product_id = :p").param("p", productId).update()
        fileIds.forEachIndexed { i, fid ->
            j.sql("INSERT INTO product_images (tenant_id, product_id, file_id, sort_order) VALUES (:t, :p, :f, :s)")
                .param("t", tenantId).param("p", productId).param("f", fid).param("s", i).update()
        }
        if (fileIds.isNotEmpty())
            j.sql("UPDATE files SET is_public = true, purpose = 'product' WHERE id IN (:ids)").param("ids", fileIds).update()
    }

    fun create(p: UserPrincipal, input: ProductIn): ProductOut {
        p.require("products", Action.CREATE)
        rows.limitJson(input.attributes, "attributes")
        input.variants.forEach { checkVariant(it) }
        if (input.variants.map { it.sku.lowercase() }.toSet().size != input.variants.size) throw Invalid("Each variant needs a different SKU.")
        return db.tenant(p.tenantId) { j ->
            checkCategory(j, input.categoryId)
            val slug = uniqueSlug(j, "products", input.slug, input.name, 200)
            val id = j.sql("""INSERT INTO products (tenant_id, name, slug, description, status, category_id, attributes)
                              VALUES (:t, :n, :s, :d, :st, :c, CAST(:a AS jsonb)) RETURNING id""")
                .param("t", p.tenantId).param("n", input.name.trim()).param("s", slug).param("d", input.description).param("st", input.status)
                .param("c", input.categoryId).param("a", rows.jsonParam(input.attributes ?: emptyMap<String, Any?>())).query(UUID::class.java).single()
            input.variants.forEach { insertVariant(j, p.tenantId, id, it) }
            setImages(j, p.tenantId, id, input.imageFileIds)
            audit.record(j, p.tenantId, "product.create", "products", id, mapOf("name" to input.name, "variants" to input.variants.size))
            one(j, p.tenantId, id).also { emit(j, p.tenantId, "product.created", it) }
        }
    }

    fun patch(p: UserPrincipal, id: UUID, input: ProductPatchIn): ProductOut {
        p.require("products", Action.UPDATE)
        rows.limitJson(input.attributes, "attributes")
        return db.tenant(p.tenantId) { j ->
            val current = one(j, p.tenantId, id)
            if (input.slug != null && input.slug != current.slug) uniqueSlug(j, "products", input.slug, current.name, 200)
            checkCategory(j, input.categoryId)
            j.sql("""UPDATE products SET name = :n, slug = :s, description = :d, status = :st, category_id = :c, attributes = CAST(:a AS jsonb)
                     WHERE id = :id""")
                .param("n", input.name?.trim() ?: current.name).param("s", input.slug ?: current.slug)
                .param("d", input.description ?: current.description).param("st", input.status ?: current.status)
                .param("c", if (input.clearCategory == true) null else input.categoryId ?: current.categoryId)
                .param("a", rows.jsonParam(input.attributes ?: current.attributes)).param("id", id).update()
            input.imageFileIds?.let { setImages(j, p.tenantId, id, it) }
            audit.record(j, p.tenantId, "product.update", "products", id, mapOf("status" to input.status, "images" to input.imageFileIds?.size))
            one(j, p.tenantId, id).also { emit(j, p.tenantId, "product.updated", it) }
        }
    }

    private fun hasHistory(j: JdbcClient, variantFilter: String, id: UUID): Boolean =
        j.sql("""SELECT EXISTS (SELECT 1 FROM order_items WHERE variant_id IN ($variantFilter))
                     OR EXISTS (SELECT 1 FROM stock_movements WHERE variant_id IN ($variantFilter))
                     OR EXISTS (SELECT 1 FROM purchase_order_lines WHERE variant_id IN ($variantFilter))
                     OR EXISTS (SELECT 1 FROM stock_levels WHERE variant_id IN ($variantFilter))""")
            .param("id", id).query(Boolean::class.java).single()

    /** Products that were ever ordered, stocked or purchased keep their history: archive them instead. */
    fun delete(p: UserPrincipal, id: UUID) {
        p.require("products", Action.DELETE)
        db.tenant(p.tenantId) { j ->
            val name = j.sql("SELECT name FROM products WHERE id = :id").param("id", id).query(String::class.java).optional().orElseThrow { NotFound("Product") }
            if (hasHistory(j, "SELECT id FROM product_variants WHERE product_id = :id", id))
                throw Conflict("This product has orders, stock or purchase history. Archive it instead (status: archived).")
            try { j.sql("DELETE FROM products WHERE id = :id").param("id", id).update() }
            catch (e: DataIntegrityViolationException) { throw Conflict("This product is still referenced elsewhere. Archive it instead.") }
            audit.record(j, p.tenantId, "product.delete", "products", id, mapOf("name" to name))
            events.emit(j, p.tenantId, "product.deleted", mapOf("id" to id, "name" to name))
        }
    }

    fun addVariant(p: UserPrincipal, productId: UUID, input: VariantIn): ProductOut {
        p.require("products", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            one(j, p.tenantId, productId)
            val count = j.sql("SELECT count(*) FROM product_variants WHERE product_id = :p").param("p", productId).query(Long::class.java).single()
            if (count >= 100) throw Invalid("A product can have at most 100 variants.")
            val vid = insertVariant(j, p.tenantId, productId, input)
            audit.record(j, p.tenantId, "variant.create", "products", vid, mapOf("product_id" to productId, "sku" to input.sku))
            one(j, p.tenantId, productId).also { emit(j, p.tenantId, "product.updated", it) }
        }
    }

    private val clearable = mapOf("name" to "name", "compare_at_price" to "compare_at_price", "cost" to "cost", "barcode" to "barcode",
        "weight_grams" to "weight_grams", "low_stock_threshold" to "low_stock_threshold")

    fun patchVariant(p: UserPrincipal, productId: UUID, variantId: UUID, input: VariantPatchIn): ProductOut {
        p.require("products", Action.UPDATE)
        input.price?.let { Money.check(it, "price") }; input.compareAtPrice?.let { Money.check(it, "compare_at_price") }
        input.cost?.let { Money.check(it, "cost") }; rows.limitJson(input.options, "options", 4096)
        input.clear?.forEach { if (it !in clearable) throw Invalid("'$it' cannot be cleared. Clearable: ${clearable.keys.joinToString()}.") }
        return db.tenant(p.tenantId) { j ->
            val exists = j.sql("SELECT count(*) FROM product_variants WHERE id = :v AND product_id = :p").param("v", variantId).param("p", productId)
                .query(Long::class.java).single() > 0
            if (!exists) throw NotFound("Variant")
            if (input.sku != null && j.sql("SELECT count(*) FROM product_variants WHERE sku = :s AND id <> :v").param("s", input.sku).param("v", variantId)
                    .query(Long::class.java).single() > 0) throw Conflict("The SKU '${input.sku}' is already used by another variant.")
            val sets = buildMap<String, Any?> {
                input.sku?.let { put("sku", it) }; input.name?.let { put("name", it.trim()) }
                input.options?.let { put("options", rows.jsonParam(it)) }; input.price?.let { put("price", it) }
                input.compareAtPrice?.let { put("compare_at_price", it) }; input.cost?.let { put("cost", it) }
                input.barcode?.let { put("barcode", it) }; input.weightGrams?.let { put("weight_grams", it) }
                input.trackInventory?.let { put("track_inventory", it) }; input.lowStockThreshold?.let { put("low_stock_threshold", it) }
                input.isActive?.let { put("is_active", it) }; input.sortOrder?.let { put("sort_order", it) }
                input.clear?.forEach { put(clearable.getValue(it), null) }
            }
            if (sets.isNotEmpty()) {
                // Column names come from the fixed map above, never from the request.
                val sql = sets.keys.joinToString(", ") { c -> if (c == "options") "options = CAST(:options AS jsonb)" else "$c = :$c" }
                j.sql("UPDATE product_variants SET $sql WHERE id = :vid").apply { sets.forEach { (k, v) -> param(k, v) } }.param("vid", variantId).update()
            }
            audit.record(j, p.tenantId, "variant.update", "products", variantId, mapOf("fields" to sets.keys))
            one(j, p.tenantId, productId).also { emit(j, p.tenantId, "product.updated", it) }
        }
    }

    fun deleteVariant(p: UserPrincipal, productId: UUID, variantId: UUID): ProductOut {
        p.require("products", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            val count = j.sql("SELECT count(*) FROM product_variants WHERE product_id = :p").param("p", productId).query(Long::class.java).single()
            val exists = j.sql("SELECT count(*) FROM product_variants WHERE id = :v AND product_id = :p").param("v", variantId).param("p", productId)
                .query(Long::class.java).single() > 0
            if (!exists) throw NotFound("Variant")
            if (count <= 1) throw Conflict("A product needs at least one variant. Delete or archive the product instead.")
            if (hasHistory(j, ":id", variantId)) throw Conflict("This variant has orders, stock or purchase history. Deactivate it instead (is_active: false).")
            j.sql("DELETE FROM product_variants WHERE id = :v").param("v", variantId).update()
            audit.record(j, p.tenantId, "variant.delete", "products", variantId, mapOf("product_id" to productId))
            one(j, p.tenantId, productId).also { emit(j, p.tenantId, "product.updated", it) }
        }
    }

    // -- categories --------------------------------------------------------------------------------

    fun categories(j: JdbcClient): List<CategoryOut> =
        j.sql("""SELECT c.id, c.name, c.slug, c.parent_id, c.sort_order, (SELECT count(*) FROM products p WHERE p.category_id = c.id)
                 FROM product_categories c ORDER BY c.sort_order, c.name""")
            .query { rs, _ -> CategoryOut(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getObject(4, UUID::class.java), rs.getInt(5), rs.getInt(6)) }
            .list()

    fun listCategories(p: UserPrincipal) = p.require("products", Action.READ).let { db.tenant(p.tenantId) { categories(it) } }

    private fun checkParent(j: JdbcClient, id: UUID?, parentId: UUID?) {
        if (parentId == null) return
        checkCategory(j, parentId)
        // Walk up from the new parent; reaching the category itself would make a loop.
        var cursor: UUID? = parentId
        var depth = 0
        while (cursor != null) {
            if (cursor == id) throw Invalid("A category cannot be inside itself.")
            if (++depth > 20) throw Invalid("Categories can be nested at most 20 levels.")
            cursor = j.sql("SELECT parent_id FROM product_categories WHERE id = :id").param("id", cursor).query { rs, _ -> rs.getObject(1, UUID::class.java) }
                .optional().orElse(null)
        }
    }

    fun createCategory(p: UserPrincipal, input: CategoryIn): CategoryOut {
        p.require("products", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            checkParent(j, null, input.parentId)
            val slug = uniqueSlug(j, "product_categories", input.slug, input.name, 100)
            val id = j.sql("INSERT INTO product_categories (tenant_id, name, slug, parent_id, sort_order) VALUES (:t, :n, :s, :p, :o) RETURNING id")
                .param("t", p.tenantId).param("n", input.name.trim()).param("s", slug).param("p", input.parentId).param("o", input.sortOrder)
                .query(UUID::class.java).single()
            audit.record(j, p.tenantId, "category.create", "products", id, mapOf("name" to input.name))
            categories(j).first { it.id == id }
        }
    }

    fun patchCategory(p: UserPrincipal, id: UUID, input: CategoryPatchIn): CategoryOut {
        p.require("products", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            val c = categories(j).firstOrNull { it.id == id } ?: throw NotFound("Category")
            if (input.slug != null && input.slug != c.slug) uniqueSlug(j, "product_categories", input.slug, c.name, 100)
            val parent = if (input.clearParent == true) null else input.parentId ?: c.parentId
            checkParent(j, id, parent)
            j.sql("UPDATE product_categories SET name = :n, slug = :s, parent_id = :p, sort_order = :o WHERE id = :id")
                .param("n", input.name?.trim() ?: c.name).param("s", input.slug ?: c.slug).param("p", parent).param("o", input.sortOrder ?: c.sortOrder)
                .param("id", id).update()
            audit.record(j, p.tenantId, "category.update", "products", id)
            categories(j).first { it.id == id }
        }
    }

    /** Products in the category stay, without a category; sub-categories move to the top level. */
    fun deleteCategory(p: UserPrincipal, id: UUID) {
        p.require("products", Action.UPDATE)
        db.tenant(p.tenantId) { j ->
            if (j.sql("DELETE FROM product_categories WHERE id = :id").param("id", id).update() == 0) throw NotFound("Category")
            audit.record(j, p.tenantId, "category.delete", "products", id)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// HTTP
// ------------------------------------------------------------------------------------------------

@RestController
@RequestMapping("\${tc.api-prefix}")
@Validated
class CatalogController(private val catalog: CatalogService) {

    @GetMapping("/products")
    fun list(@RequestParam(required = false) @Size(max = 100) q: String?,
             @RequestParam(required = false) @Pattern(regexp = "^(draft|active|archived)$") status: String?,
             @RequestParam(name = "category_id", required = false) categoryId: UUID?,
             @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
             @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = catalog.list(Caller.user(), q, status, categoryId, limit, offset)

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: ProductIn) = catalog.create(Caller.user(), body)

    @GetMapping("/products/{id}")
    fun get(@PathVariable id: UUID) = catalog.get(Caller.user(), id)

    @PatchMapping("/products/{id}")
    fun patch(@PathVariable id: UUID, @Valid @RequestBody body: ProductPatchIn) = catalog.patch(Caller.user(), id, body)

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = catalog.delete(Caller.user(), id)

    @PostMapping("/products/{id}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    fun addVariant(@PathVariable id: UUID, @Valid @RequestBody body: VariantIn) = catalog.addVariant(Caller.user(), id, body)

    @PatchMapping("/products/{id}/variants/{variantId}")
    fun patchVariant(@PathVariable id: UUID, @PathVariable variantId: UUID, @Valid @RequestBody body: VariantPatchIn) =
        catalog.patchVariant(Caller.user(), id, variantId, body)

    @DeleteMapping("/products/{id}/variants/{variantId}")
    fun deleteVariant(@PathVariable id: UUID, @PathVariable variantId: UUID) = catalog.deleteVariant(Caller.user(), id, variantId)

    @GetMapping("/product-categories")
    fun categories() = mapOf("items" to catalog.listCategories(Caller.user()))

    @PostMapping("/product-categories")
    @ResponseStatus(HttpStatus.CREATED)
    fun createCategory(@Valid @RequestBody body: CategoryIn) = catalog.createCategory(Caller.user(), body)

    @PatchMapping("/product-categories/{id}")
    fun patchCategory(@PathVariable id: UUID, @Valid @RequestBody body: CategoryPatchIn) = catalog.patchCategory(Caller.user(), id, body)

    @DeleteMapping("/product-categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCategory(@PathVariable id: UUID) = catalog.deleteCategory(Caller.user(), id)
}
