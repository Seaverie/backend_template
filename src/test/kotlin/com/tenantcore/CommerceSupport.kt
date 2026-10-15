package com.tenantcore

import org.springframework.http.HttpMethod
import java.math.BigDecimal
import java.util.UUID

/** Helpers for the commerce tests: a shop tenant, products, a warehouse with stock, orders. */
abstract class CommerceTest : IntegrationTest() {
    val allCommerce = listOf("catalog", "inventory", "purchasing", "orders", "payments", "returns", "files", "email")

    fun shop(modules: List<String> = allCommerce) = newTenant(modules)

    data class Product(val id: String, val variantId: String, val sku: String)

    fun product(t: Tenant, price: String = "10.00", status: String = "active", track: Boolean = true, name: String = "Widget"): Product {
        val sku = "SKU-" + UUID.randomUUID().toString().take(8)
        val r = post("/products", mapOf("name" to name, "status" to status,
            "variants" to listOf(mapOf("sku" to sku, "price" to BigDecimal(price), "track_inventory" to track, "cost" to BigDecimal("4.00")))), t.token)
        check(r.status == 201) { r.toString() }
        return Product(r["id"].asText(), r["variants"][0]["id"].asText(), sku)
    }

    data class Warehouse(val id: String, val mainBin: String)

    fun warehouse(t: Tenant, code: String = "WH" + UUID.randomUUID().toString().take(4)): Warehouse {
        val r = post("/inventory/warehouses", mapOf("code" to code, "name" to "Warehouse $code"), t.token)
        check(r.status == 201) { r.toString() }
        return Warehouse(r["id"].asText(), r["bins"][0]["id"].asText())
    }

    fun addStock(t: Tenant, variantId: String, qty: Int, binId: String? = null): Res {
        val r = post("/inventory/adjustments", buildMap { put("variant_id", variantId); put("quantity", qty); if (binId != null) put("bin_id", binId) }, t.token)
        check(r.status == 200) { r.toString() }
        return r
    }

    fun stockOf(t: Tenant, sku: String) = get("/inventory/stock?q=$sku", t.token)["items"][0]

    fun order(t: Tenant, items: Map<String, Int>, key: String? = null, extra: Map<String, Any?> = emptyMap()): Res =
        call(HttpMethod.POST, "/api/v1/orders", mapOf("items" to items.map { (v, q) -> mapOf("variant_id" to v, "quantity" to q) }) + extra, t.token,
            if (key != null) mapOf("Idempotency-Key" to key) else emptyMap())

    fun move(t: Tenant, orderId: String, to: String) = post("/orders/$orderId/transition", mapOf("to" to to), t.token)

    fun dec(s: String) = BigDecimal(s)
}
