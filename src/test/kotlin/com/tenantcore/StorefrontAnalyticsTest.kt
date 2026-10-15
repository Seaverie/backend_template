package com.tenantcore

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.LocalDate
import java.util.UUID

class StorefrontTest : CommerceTest() {

    private fun checkout(slug: String, body: Map<String, Any?>, key: String? = null) =
        call(HttpMethod.POST, "/api/v1/public/shop/$slug/checkout", body, headers = if (key != null) mapOf("Idempotency-Key" to key) else emptyMap())

    @Test
    fun `the public catalog shows active products only, without costs`() {
        val t = shop()
        warehouse(t)
        val live = product(t, name = "Live Lamp")
        product(t, name = "Draft Desk", status = "draft")
        addStock(t, live.variantId, 2)
        put("/settings", mapOf("display_name" to "Lamp Shop", "currency" to "THB"), t.token)

        val info = get("/public/shop/${t.slug}")
        assertThat(info["name"].asText()).isEqualTo("Lamp Shop")
        assertThat(info["currency"].asText()).isEqualTo("THB")
        assertThat(info["checkout_enabled"].asBoolean()).isFalse()

        val list = get("/public/shop/${t.slug}/products")
        assertThat(list["items"].map { it["name"].asText() }).containsExactly("Live Lamp")
        val v = list["items"][0]["variants"][0]
        assertThat(v["in_stock"].asBoolean()).isTrue()
        assertThat(v.has("cost")).isFalse()
        assertThat(v.has("stock")).isFalse()
        assertThat(get("/public/shop/${t.slug}/products/draft-desk").status).isEqualTo(404)
        assertThat(get("/public/shop/no-such-shop").status).isEqualTo(404)

        // A suspended workspace has no shop.
        patch("/platform/tenants/${t.id}", mapOf("status" to "suspended"), platformToken())
        assertThat(get("/public/shop/${t.slug}/products").status).isEqualTo(404)
    }

    @Test
    fun `customers check out, get a confirmation and can follow their order`() {
        usePlatformMailpit()
        val t = shop()
        warehouse(t)
        val p = product(t, price = "15.00")
        val draft = product(t, status = "draft")
        addStock(t, p.variantId, 5)
        val body = mapOf("items" to listOf(mapOf("variant_id" to p.variantId, "quantity" to 2)), "customer_name" to "Nok <b>", "customer_email" to "nok@example.com")

        assertThat(checkout(t.slug, body).status).isEqualTo(403) // checkout is off by default
        put("/orders/settings", mapOf("storefront_checkout" to true, "shipping_flat" to 5), t.token)

        // Prices cannot be sent; draft products cannot be bought; invalid emails are refused.
        assertThat(checkout(t.slug, body + ("items" to listOf(mapOf("variant_id" to p.variantId, "quantity" to 1, "price" to 0)))).status).isEqualTo(422)
        assertThat(checkout(t.slug, body + ("items" to listOf(mapOf("variant_id" to draft.variantId, "quantity" to 1)))).status).isEqualTo(422)
        assertThat(checkout(t.slug, body + ("customer_email" to "nope")).status).isEqualTo(422)

        val key = "web-" + UUID.randomUUID()
        val r = checkout(t.slug, body, key)
        assertThat(r.status).isEqualTo(201)
        assertThat(r["order"]["total"].decimalValue()).isEqualByComparingTo("35.00")
        val id = r["order"]["id"].asText()
        val token = r["lookup_token"].asText()
        // A retry returns the same order and the same link.
        val again = checkout(t.slug, body, key)
        assertThat(again["order"]["id"].asText()).isEqualTo(id)
        assertThat(again["lookup_token"].asText()).isEqualTo(token)

        assertThat(get("/public/shop/${t.slug}/orders/$id?token=$token")["status_label"].asText()).isEqualTo("Pending")
        assertThat(get("/public/shop/${t.slug}/orders/$id?token=x$token").status).isEqualTo(404)
        val other = shop()
        assertThat(get("/public/shop/${other.slug}/orders/$id?token=$token").status).isEqualTo(404)

        val dash = get("/orders/$id", t.token)
        assertThat(dash["source"].asText()).isEqualTo("storefront")
        assertThat(dash["history"][0]["actor_type"].asText()).isEqualTo("customer")
        assertThat(stockOf(t, p.sku)["reserved"].asInt()).isEqualTo(2)

        val mail = awaitMail("nok@example.com", "order #${dash["number"].asText()}")
        assertThat(mail.html).contains("Nok &lt;b&gt;").contains("35.00")
        assertThat(mail.text).contains("2 × Widget")
    }

    @Test
    fun `checkout can start a card payment`() {
        val t = shop()
        val p = product(t, price = "9.50", track = false)
        put("/orders/settings", mapOf("storefront_checkout" to true), t.token)
        val body = mapOf("items" to listOf(mapOf("variant_id" to p.variantId, "quantity" to 1)), "customer_name" to "A", "customer_email" to "a@example.com",
            "pay_with" to "sandbox")
        assertThat(checkout(t.slug, body).status).isEqualTo(422) // sandbox not enabled
        put("/payments/gateways/sandbox", mapOf("is_enabled" to true), t.token)
        assertThat(get("/public/shop/${t.slug}")["payment_methods"][0]["provider"].asText()).isEqualTo("sandbox")
        val key = "pay-" + UUID.randomUUID()
        val r = checkout(t.slug, body, key)
        assertThat(r["payment"]["status"].asText()).isEqualTo("pending")
        assertThat(r["payment"]["client_secret"].asText()).isNotBlank()
        assertThat(checkout(t.slug, body, key)["payment"]["payment_id"]).isEqualTo(r["payment"]["payment_id"])
    }
}

class AnalyticsTest : CommerceTest() {

    @Test
    fun `sales count only statuses marked as sales, in the tenant's timezone`() {
        val t = shop()
        put("/settings", mapOf("timezone" to "Asia/Bangkok"), t.token)
        val cheap = product(t, price = "10.00", track = false, name = "Cheap")
        val dear = product(t, price = "100.00", track = false, name = "Dear")
        val a = order(t, mapOf(dear.variantId to 1))["id"].asText(); move(t, a, "confirmed")
        val b = order(t, mapOf(cheap.variantId to 3))["id"].asText(); move(t, b, "confirmed"); move(t, b, "shipped")
        order(t, mapOf(cheap.variantId to 1)) // pending: not a sale yet
        val c = order(t, mapOf(dear.variantId to 2))["id"].asText(); move(t, c, "cancelled")

        val r = get("/analytics/sales", t.token)
        assertThat(r.status).isEqualTo(200)
        assertThat(r["orders"].asLong()).isEqualTo(2)
        assertThat(r["revenue"].decimalValue()).isEqualByComparingTo("130.00")
        assertThat(r["average_order_value"].decimalValue()).isEqualByComparingTo("65.00")
        assertThat(r["items_sold"].asLong()).isEqualTo(4)
        assertThat(r["series"].size()).isEqualTo(30)
        assertThat(r["series"].last()["revenue"].decimalValue()).isEqualByComparingTo("130.00")
        assertThat(r["top_products"][0]["sku"].asText()).isEqualTo(dear.sku)
        assertThat(r["by_status"]["cancelled"].asLong()).isEqualTo(1)
        assertThat(r["by_status"]["pending"].asLong()).isEqualTo(1)
        assertThat(r["timezone"].asText()).isEqualTo("Asia/Bangkok")

        val today = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"))
        val monthly = get("/analytics/sales?interval=month&from=${today.minusMonths(2)}&to=$today", t.token)
        assertThat(monthly["series"].size()).isEqualTo(3)
        assertThat(get("/analytics/sales?from=$today&to=${today.minusDays(1)}", t.token).status).isEqualTo(422)
        assertThat(get("/analytics/sales?interval=hour", t.token).status).isEqualTo(422)

        val (_, member) = newUser(t)
        assertThat(get("/analytics/sales", member).status).isEqualTo(403)
    }
}
