package com.tenantcore

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class OrdersTest : CommerceTest() {

    @Test
    fun `prices, shipping and tax are computed on the server from the settings`() {
        val t = shop()
        val p = product(t, price = "100.00", track = false)
        val s = put("/orders/settings", mapOf("tax_rate_percent" to 7, "shipping_flat" to 50, "free_shipping_over" to 1000), t.token)
        assertThat(s.status).isEqualTo(200)
        val o = order(t, mapOf(p.variantId to 3))
        assertThat(o.status).isEqualTo(201)
        assertThat(o["subtotal"].decimalValue()).isEqualByComparingTo("300.00")
        assertThat(o["shipping_total"].decimalValue()).isEqualByComparingTo("50.00")
        assertThat(o["tax_total"].decimalValue()).isEqualByComparingTo("24.50")
        assertThat(o["total"].decimalValue()).isEqualByComparingTo("374.50")
        assertThat(o["number"].asLong()).isEqualTo(1)
        assertThat(o["status"].asText()).isEqualTo("pending")
        assertThat(o["allowed_transitions"].map { it.asText() }).containsExactlyInAnyOrder("confirmed", "cancelled")

        // Prices included tax; free shipping above the threshold; a dashboard discount.
        put("/orders/settings", mapOf("prices_include_tax" to true), t.token)
        val big = order(t, mapOf(p.variantId to 11), extra = mapOf("discount" to 50))
        assertThat(big["shipping_total"].decimalValue()).isEqualByComparingTo("0")
        assertThat(big["total"].decimalValue()).isEqualByComparingTo("1050.00")
        assertThat(big["tax_total"].decimalValue()).isEqualByComparingTo("68.69") // 1050 × 7 / 107
        assertThat(big["number"].asLong()).isEqualTo(2)

        // A client cannot send its own price, and a discount cannot exceed the goods.
        assertThat(order(t, emptyMap(), extra = mapOf("items" to listOf(mapOf("variant_id" to p.variantId, "quantity" to 1, "unit_price" to 1)))).status).isEqualTo(422)
        assertThat(order(t, mapOf(p.variantId to 1), extra = mapOf("discount" to 101)).status).isEqualTo(422)
        assertThat(order(t, mapOf(UUID.randomUUID().toString() to 1)).status).isEqualTo(422)
        assertThat(put("/orders/settings", mapOf("tax_rate_percent" to 101), t.token).status).isEqualTo(422)
        // Query filters use snake_case names and are validated.
        post("/orders/${o["id"].asText()}/payments", mapOf("provider" to "manual"), t.token)
        assertThat(get("/orders?payment_status=paid", t.token)["total"].asInt()).isEqualTo(1)
        assertThat(get("/orders?q=%23${big["number"].asText()}", t.token)["items"][0]["id"].asText()).isEqualTo(big["id"].asText())
        assertThat(get("/orders?payment_status=bogus", t.token).status).isEqualTo(422)
        assertThat(get("/platform/tenants?status=bogus", platformToken()).status).isEqualTo(422)
    }

    @Test
    fun `an idempotency key never creates a second order, even under concurrent retries`() {
        val t = shop(listOf("catalog", "orders"))
        val p = product(t, track = false)
        val key = "checkout-" + UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(6)
        val results = pool.invokeAll((1..6).map { Callable { order(t, mapOf(p.variantId to 2), key) } }).map { it.get() }
        pool.shutdown()
        assertThat(results.map { it.status }.toSet()).containsExactly(201)
        assertThat(results.map { it["id"].asText() }.toSet()).hasSize(1)
        assertThat(get("/orders", t.token)["total"].asInt()).isEqualTo(1)
        // Same key, different body: refused.
        val reused = order(t, mapOf(p.variantId to 3), key)
        assertThat(reused.status).isEqualTo(409)
        assertThat(reused["error"]["code"].asText()).isEqualTo("idempotency_key_reused")
        assertThat(order(t, mapOf(p.variantId to 2), "bad key!").status).isEqualTo(422)
    }

    @Test
    fun `the lifecycle reserves, ships and releases stock`() {
        val t = shop()
        val w = warehouse(t)
        val p = product(t)
        addStock(t, p.variantId, 10, w.mainBin)

        val a = order(t, mapOf(p.variantId to 4))
        assertThat(a["stock_state"].asText()).isEqualTo("reserved")
        stockOf(t, p.sku).let { assertThat(it["on_hand"].asInt()).isEqualTo(10); assertThat(it["reserved"].asInt()).isEqualTo(4) }

        val id = a["id"].asText()
        assertThat(move(t, id, "shipped").status).isEqualTo(409) // not allowed straight from pending
        move(t, id, "confirmed")
        val shipped = move(t, id, "shipped")
        assertThat(shipped["stock_state"].asText()).isEqualTo("deducted")
        stockOf(t, p.sku).let { assertThat(it["on_hand"].asInt()).isEqualTo(6); assertThat(it["reserved"].asInt()).isZero() }
        assertThat(get("/inventory/movements?kind=sale", t.token)["items"][0]["quantity"].asInt()).isEqualTo(-4)

        val b = order(t, mapOf(p.variantId to 5))
        val cancelled = move(t, b["id"].asText(), "cancelled")
        assertThat(cancelled["stock_state"].asText()).isEqualTo("released")
        stockOf(t, p.sku).let { assertThat(it["available"].asInt()).isEqualTo(6) }

        val tooMany = order(t, mapOf(p.variantId to 7))
        assertThat(tooMany.status).isEqualTo(409)
        assertThat(tooMany["error"]["code"].asText()).isEqualTo("insufficient_stock")
        assertThat(tooMany["error"]["details"][0]["available"].asInt()).isEqualTo(6)

        val detail = get("/orders/$id", t.token)
        assertThat(detail["history"].map { it["to_status"].asText() }).containsExactly("pending", "confirmed", "shipped")
        assertThat(move(t, id, "nope").status).isEqualTo(422)
    }

    @Test
    fun `concurrent checkouts never oversell`() {
        val t = shop()
        warehouse(t)
        val p = product(t)
        addStock(t, p.variantId, 5)
        val pool = Executors.newFixedThreadPool(20)
        val results = pool.invokeAll((1..20).map { Callable { order(t, mapOf(p.variantId to 1)).status } }).map { it.get() }
        pool.shutdown()
        assertThat(results.count { it == 201 }).isEqualTo(5)
        assertThat(results.count { it == 409 }).isEqualTo(15)
        stockOf(t, p.sku).let { assertThat(it["reserved"].asInt()).isEqualTo(5); assertThat(it["available"].asInt()).isZero() }
        // Order numbers are unique and gap-free.
        assertThat(get("/orders", t.token)["items"].map { it["number"].asLong() }.sorted()).isEqualTo((1L..5L).toList())
    }

    @Test
    fun `the lifecycle is edited from the dashboard`() {
        val t = shop()
        val p = product(t, track = false)
        val life = get("/orders/lifecycle", t.token)
        assertThat(life["statuses"].map { it["code"].asText() }).containsExactly("pending", "confirmed", "shipped", "delivered", "cancelled")

        val o = order(t, mapOf(p.variantId to 1))["id"].asText()
        val custom = mapOf(
            "statuses" to listOf(
                mapOf("code" to "pending", "label" to "New", "stock_effect" to "reserve", "is_initial" to true),
                mapOf("code" to "preparing", "label" to "Preparing", "stock_effect" to "reserve", "counts_as_sale" to true),
                mapOf("code" to "ready", "label" to "Ready for pickup", "stock_effect" to "deduct", "counts_as_sale" to true),
                mapOf("code" to "collected", "label" to "Collected", "is_final" to true, "counts_as_sale" to true),
                mapOf("code" to "cancelled", "label" to "Cancelled", "stock_effect" to "release", "is_final" to true)),
            "transitions" to listOf(mapOf("from" to "pending", "to" to "preparing"), mapOf("from" to "preparing", "to" to "ready"),
                mapOf("from" to "ready", "to" to "collected"), mapOf("from" to "pending", "to" to "cancelled")))
        val r = put("/orders/lifecycle", custom, t.token)
        assertThat(r.status).isEqualTo(200)
        assertThat(r["statuses"].first { it["code"].asText() == "pending" }["label"].asText()).isEqualTo("New")
        assertThat(get("/orders/$o", t.token)["allowed_transitions"].map { it.asText() }).containsExactlyInAnyOrder("preparing", "cancelled")
        assertThat(move(t, o, "preparing")["status_label"].asText()).isEqualTo("Preparing")

        // Removing a status that an order is in is refused; two initial statuses are refused; unknown codes are refused.
        val without = custom + ("statuses" to (custom["statuses"] as List<*>).filter { (it as Map<*, *>)["code"] != "preparing" })
        assertThat(put("/orders/lifecycle", without + ("transitions" to emptyList<Any>()), t.token).status).isEqualTo(409)
        val twoInitial = custom + ("statuses" to (custom["statuses"] as List<*>).map { (it as Map<String, Any?>) + ("is_initial" to true) })
        assertThat(put("/orders/lifecycle", twoInitial, t.token).status).isEqualTo(422)
        assertThat(put("/orders/lifecycle", custom + ("transitions" to listOf(mapOf("from" to "pending", "to" to "ghost"))), t.token).status).isEqualTo(422)

        // A member who can create orders cannot change the lifecycle.
        val role = post("/roles", mapOf("name" to "Sales", "permissions" to listOf(mapOf("resource" to "orders", "action" to "create"),
            mapOf("resource" to "orders", "action" to "update"), mapOf("resource" to "orders", "action" to "read"))), t.token)
        val (_, member) = newUser(t, listOf(role["id"].asText()))
        assertThat(put("/orders/lifecycle", custom, member).status).isEqualTo(403)
        assertThat(order(t.copy(token = member), mapOf(p.variantId to 1)).status).isEqualTo(201)
    }

    @Test
    fun `orders are isolated between tenants, in the API and in the database`() {
        val a = shop(); val b = shop()
        val p = product(a, track = false)
        val o = order(a, mapOf(p.variantId to 1))["id"].asText()
        val bWarehouse = warehouse(b)
        assertThat(get("/orders/$o", b.token).status).isEqualTo(404)
        assertThat(move(b, o, "confirmed").status).isEqualTo(404)
        // Tenant B cannot order tenant A's product.
        assertThat(order(b, mapOf(p.variantId to 1)).status).isEqualTo(422)
        appRole().use { c ->
            c.autoCommit = false
            c.createStatement().execute("SELECT set_config('app.tenant_id', '${b.id}', true)")
            assertThat(c.createStatement().executeQuery("SELECT count(*) FROM orders WHERE id = '$o'").apply { next() }.getInt(1)).isZero()
            // Composite foreign keys stop a row in B from pointing at A's variant even with a guessed id.
            assertThatThrownBy {
                c.createStatement().execute("""INSERT INTO stock_levels (tenant_id, variant_id, bin_id) VALUES ('${b.id}', '${p.variantId}', '${bWarehouse.mainBin}')""")
            }.hasMessageContaining("foreign key")
            c.rollback()
        }
        superuser().use { c ->
            assertThat(c.createStatement().executeQuery("SELECT count(*) FROM orders WHERE id = '$o'").apply { next() }.getInt(1)).isEqualTo(1)
        }
    }
}
