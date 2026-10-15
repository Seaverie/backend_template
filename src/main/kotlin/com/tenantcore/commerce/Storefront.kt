package com.tenantcore.commerce

import com.tenantcore.common.Forbidden
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.RateLimiter
import com.tenantcore.security.RequestInfo
import com.tenantcore.security.constantTimeEquals
import com.tenantcore.tenants.SettingsService
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PublicVariant(val id: UUID, val sku: String, val name: String?, val options: Map<String, Any?>, val price: BigDecimal,
                         val compareAtPrice: BigDecimal?, val inStock: Boolean)
data class PublicProduct(val id: UUID, val name: String, val slug: String, val description: String?, val categoryId: UUID?,
                         val attributes: Map<String, Any?>, val images: List<ImageOut>, val variants: List<PublicVariant>)

data class CheckoutIn(
    @field:Size(min = 1, max = 200, message = "an order needs 1 to 200 items") val items: List<@Valid OrderItemIn>,
    /** Required unless a signed-in shop customer checks out (their account's name and email are used). */
    @field:Size(min = 1, max = 200) val customerName: String? = null,
    @field:Email @field:Size(max = 320) val customerEmail: String? = null,
    @field:Size(max = 40) val customerPhone: String? = null,
    val shippingAddress: Map<String, Any?>? = null,
    val billingAddress: Map<String, Any?>? = null,
    @field:Size(max = 2000) val notes: String? = null,
    /** Start a card payment right away with this gateway. */
    @field:Pattern(regexp = "^(stripe|sandbox)$", message = "must be stripe or sandbox") val payWith: String? = null,
)

data class PublicOrderItem(val sku: String, val name: String, val quantity: Int, val unitPrice: BigDecimal, val lineTotal: BigDecimal)
data class PublicOrder(
    val id: UUID, val number: Long, val status: String, val statusLabel: String, val currency: String, val items: List<PublicOrderItem>,
    val subtotal: BigDecimal, val discountTotal: BigDecimal, val shippingTotal: BigDecimal, val taxTotal: BigDecimal, val total: BigDecimal,
    val paidTotal: BigDecimal, val balanceDue: BigDecimal, val paymentStatus: String, val placedAt: OffsetDateTime,
)
data class CheckoutOut(val order: PublicOrder, val lookupToken: String, val payment: Map<String, Any?>?)

/**
 * Public shop endpoints for a website or app. Only active products are shown, never costs or stock
 * counts. Checkout must be switched on in the order settings; prices are always taken from the catalog.
 */
@Service
class StorefrontService(
    private val db: Db, private val rows: Rows, private val catalog: CatalogService, private val orders: OrderService,
    private val payments: PaymentService, private val settings: SettingsService, private val limiter: RateLimiter,
    private val idempotency: Idempotency, props: TcProperties, private val customerAuth: com.tenantcore.integrations.CustomerAuth,
) {
    private val lookupKey = SecretKeySpec(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(props.encryptionKeyBytes(), "HmacSHA256")); doFinal("tenantcore/order-lookup/v1".toByteArray())
    }, "HmacSHA256")

    /** Order status links: an HMAC of the order id, so nothing extra is stored and a retry gets the same link. */
    fun lookupToken(tenantId: UUID, orderId: UUID): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Mac.getInstance("HmacSHA256").apply { init(lookupKey) }.doFinal("$tenantId:$orderId".toByteArray()))

    private fun throttle(kind: String, limit: Int) = limiter.hit("shop:$kind:${RequestInfo.clientIp() ?: "unknown"}", limit)

    /** The tenant behind a shop slug: it must exist, be active and have the catalog switched on. */
    private fun shop(slug: String): UUID {
        throttle("read", 300)
        val (id, status) = db.anonymous { j ->
            j.sql("SELECT id, status FROM tenants WHERE slug = :s").param("s", slug).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }
                .optional().orElse(null)
        } ?: throw NotFound("Shop")
        if (status != "active" || !db.tenant(id) { rows.moduleOn(it, "catalog") }) throw NotFound("Shop")
        return id
    }

    fun info(slug: String): Map<String, Any?> {
        val t = shop(slug)
        val s = settings.get(t)
        return db.tenant(t) { j ->
            val checkout = rows.moduleOn(j, "orders") && orders.settings(j).storefrontCheckout
            mapOf("name" to s.displayName, "currency" to s.currency, "locale" to s.locale, "primary_color" to s.primaryColor, "logo_url" to s.logoUrl,
                "checkout_enabled" to checkout,
                "payment_methods" to if (checkout && rows.moduleOn(j, "payments")) payments.publicGateways(j, t) else emptyList())
        }
    }

    fun categories(slug: String) = shop(slug).let { t -> db.tenant(t) { catalog.categories(it).map { c -> mapOf("id" to c.id, "name" to c.name, "slug" to c.slug, "parent_id" to c.parentId) } } }

    private fun publicProducts(j: JdbcClient, tenantId: UUID, ids: List<UUID>): List<PublicProduct> =
        catalog.load(j, tenantId, ids).map { p ->
            PublicProduct(p.id, p.name, p.slug, p.description, p.categoryId, p.attributes, p.images,
                p.variants.filter { it.isActive }.map { v -> PublicVariant(v.id, v.sku, v.name, v.options, v.price, v.compareAtPrice, v.stock?.let { it.available > 0 } ?: true) })
        }.filter { it.variants.isNotEmpty() }

    fun products(slug: String, q: String?, category: String?, limit: Int, offset: Int): Map<String, Any> {
        val t = shop(slug)
        return db.tenant(t) { j ->
            val where = buildList {
                add("p.status = 'active'")
                if (!q.isNullOrBlank()) add("p.name ILIKE :q")
                if (category != null) add("p.category_id = (SELECT id FROM product_categories WHERE slug = :c)")
            }.joinToString(" AND ")
            fun bind(s: JdbcClient.StatementSpec) = s.apply { if (!q.isNullOrBlank()) param("q", rows.like(q.trim())); if (category != null) param("c", category) }
            val ids = bind(j.sql("SELECT p.id FROM products p WHERE $where ORDER BY p.created_at DESC, p.id LIMIT :l OFFSET :o")).param("l", limit).param("o", offset)
                .query(UUID::class.java).list()
            mapOf("items" to publicProducts(j, t, ids), "total" to bind(j.sql("SELECT count(*) FROM products p WHERE $where")).query(Long::class.java).single())
        }
    }

    fun product(slug: String, productSlug: String): PublicProduct {
        val t = shop(slug)
        return db.tenant(t) { j ->
            val id = j.sql("SELECT id FROM products WHERE slug = :s AND status = 'active'").param("s", productSlug).query(UUID::class.java).optional().orElse(null)
            id?.let { publicProducts(j, t, listOf(it)).firstOrNull() } ?: throw NotFound("Product")
        }
    }

    fun publicOrder(o: OrderOut) = PublicOrder(o.id, o.number, o.status, o.statusLabel, o.currency,
        o.items.map { PublicOrderItem(it.sku, it.name, it.quantity, it.unitPrice, it.lineTotal) }, o.subtotal, o.discountTotal, o.shippingTotal,
        o.taxTotal, o.total, o.paidTotal, o.balanceDue, o.paymentStatus, o.placedAt)

    fun checkout(slug: String, input: CheckoutIn, idempotencyKey: String?, authorization: String? = null): CheckoutOut {
        val t = shop(slug)
        throttle("checkout", 20)
        // A signed-in shop customer gets the order in their account.
        val customer = if (authorization != null && db.tenant(t) { rows.moduleOn(it, "customer_accounts") }) customerAuth.authenticate(authorization, t) else null
        val account = customer?.let { c -> db.tenant(t) { j ->
            j.sql("SELECT name, email, phone FROM customers WHERE id = :id").param("id", c.customerId)
                .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.single() } }
        val name = input.customerName ?: account?.first
        val email = input.customerEmail ?: account?.second
        if (name.isNullOrBlank() || email.isNullOrBlank())
            throw Invalid("Give customer_name and customer_email, or sign in.", listOfNotNull(
                if (name.isNullOrBlank()) com.tenantcore.common.FieldError(listOf("body", "customer_name"), "is required") else null,
                if (email.isNullOrBlank()) com.tenantcore.common.FieldError(listOf("body", "customer_email"), "is required") else null))
        val (orderId, due) = db.tenant(t) { j ->
            if (!rows.moduleOn(j, "orders") || !orders.settings(j).storefrontCheckout) throw Forbidden("This shop does not take orders online.")
            if (input.payWith != null && (!rows.moduleOn(j, "payments") || !payments.gateway(j, t, input.payWith).enabled))
                throw Invalid("That payment method is not available.")
            val (id, _) = idempotency.run(j, t, "storefront", idempotencyKey, input) {
                orders.insert(j, t, "storefront", OrderCreateIn(input.items, name, email, input.customerPhone ?: account?.third,
                    input.shippingAddress, input.billingAddress, input.notes, customerId = customer?.customerId),
                    Actor("customer", customer?.customerId), storefront = true)
                    .also { orders.queueConfirmation(j, t, it) }
            }
            id to orders.detail(j, t, id).balanceDue
        }
        val payment = if (input.payWith != null && due.signum() > 0) payments.startCharge(t, orderId, PaymentCreateIn(input.payWith), Actor.CUSTOMER).let {
            mapOf("payment_id" to it.payment.id, "provider" to it.payment.provider, "status" to it.payment.status, "amount" to it.payment.amount,
                "client_secret" to it.clientSecret, "publishable_key" to it.publishableKey)
        } else null
        return CheckoutOut(db.tenant(t) { publicOrder(orders.detail(it, t, orderId)) }, lookupToken(t, orderId), payment)
    }

    fun order(slug: String, id: UUID, token: String): PublicOrder {
        val t = shop(slug)
        if (!constantTimeEquals(lookupToken(t, id), token)) throw NotFound("Order")
        return db.tenant(t) { publicOrder(orders.detail(it, t, id)) }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/public/shop/{slug}")
@Validated
class StorefrontController(private val shop: StorefrontService) {
    @GetMapping
    fun info(@PathVariable slug: String) = shop.info(slug)

    @GetMapping("/categories")
    fun categories(@PathVariable slug: String) = mapOf("items" to shop.categories(slug))

    @GetMapping("/products")
    fun products(@PathVariable slug: String,
                 @RequestParam(required = false) @Size(max = 100) q: String?,
                 @RequestParam(required = false) @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,99}$") category: String?,
                 @RequestParam(defaultValue = "24") @Min(1) @Max(100) limit: Int,
                 @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) offset: Int) = shop.products(slug, q, category, limit, offset)

    @GetMapping("/products/{productSlug}")
    fun product(@PathVariable slug: String, @PathVariable productSlug: String) = shop.product(slug, productSlug)

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    fun checkout(@PathVariable slug: String, @Valid @RequestBody body: CheckoutIn, @RequestHeader("Idempotency-Key", required = false) key: String?,
                 @RequestHeader("Authorization", required = false) authorization: String?) = shop.checkout(slug, body, key, authorization)

    @GetMapping("/orders/{id}")
    fun order(@PathVariable slug: String, @PathVariable id: UUID, @RequestParam token: String) = shop.order(slug, id, token)
}
