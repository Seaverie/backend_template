package com.tenantcore

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class PaymentsTest : CommerceTest() {
    private val http = HttpClient.newHttpClient()

    private fun webhook(tenantId: String, payload: String, signature: String?): Res {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/public/payments/stripe/$tenantId/webhook"))
            .POST(HttpRequest.BodyPublishers.ofString(payload)).header("Content-Type", "application/json")
        if (signature != null) b.header("Stripe-Signature", signature)
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Res(r.statusCode(), mapper.readTree(r.body().ifBlank { "{}" }), r.headers())
    }

    private fun event(id: String, type: String, obj: Map<String, Any?>) = mapper.writeValueAsString(mapOf("id" to id, "type" to type, "data" to mapOf("object" to obj)))

    @Test
    fun `manual payments mark orders paid, move them on, and refunds are capped`() {
        val t = shop()
        val p = product(t, price = "100.00", track = false)
        val o = order(t, mapOf(p.variantId to 1))["id"].asText()
        val first = post("/orders/$o/payments", mapOf("provider" to "manual", "amount" to 40, "method" to "cash"), t.token)
        assertThat(first.status).isEqualTo(201)
        assertThat(first["payment"]["status"].asText()).isEqualTo("succeeded")
        assertThat(get("/orders/$o", t.token)["payment_status"].asText()).isEqualTo("partially_paid")
        assertThat(post("/orders/$o/payments", mapOf("provider" to "manual", "amount" to 61), t.token).status).isEqualTo(422)

        val rest = post("/orders/$o/payments", mapOf("provider" to "manual", "method" to "bank_transfer"), t.token)
        assertThat(rest["payment"]["amount"].decimalValue()).isEqualByComparingTo("60")
        val paid = get("/orders/$o", t.token)
        assertThat(paid["payment_status"].asText()).isEqualTo("paid")
        assertThat(paid["status"].asText()).isEqualTo("confirmed") // status_on_paid
        assertThat(paid["history"].last()["note"].asText()).isEqualTo("Paid in full")
        assertThat(post("/orders/$o/payments", mapOf("provider" to "manual"), t.token).status).isEqualTo(409)

        val charge1 = first["payment"]["id"].asText()
        assertThat(post("/payments/$charge1/refund", mapOf("amount" to 30), t.token)["status"].asText()).isEqualTo("succeeded")
        assertThat(post("/payments/$charge1/refund", mapOf("amount" to 20), t.token).status).isEqualTo(422)
        val refunded = get("/orders/$o", t.token)
        assertThat(refunded["payment_status"].asText()).isEqualTo("partially_refunded")
        assertThat(refunded["refunded_total"].decimalValue()).isEqualByComparingTo("30")
        val refundId = refunded["payments"].first { it["kind"].asText() == "refund" }["id"].asText()
        assertThat(post("/payments/$refundId/refund", emptyMap<String, Any>(), t.token).status).isEqualTo(409)

        // Without payments permission nothing can be taken.
        val role = post("/roles", mapOf("name" to "Viewer", "permissions" to listOf(mapOf("resource" to "orders", "action" to "read"))), t.token)
        val (_, member) = newUser(t, listOf(role["id"].asText()))
        assertThat(post("/orders/$o/payments", mapOf("provider" to "manual"), member).status).isEqualTo(403)
    }

    @Test
    fun `the sandbox gateway runs the card flow without real money`() {
        val t = shop()
        val p = product(t, price = "25.00", track = false)
        val o = order(t, mapOf(p.variantId to 1))["id"].asText()
        assertThat(post("/orders/$o/payments", mapOf("provider" to "sandbox"), t.token).status).isEqualTo(422) // not enabled
        assertThat(put("/payments/gateways/sandbox", mapOf("is_enabled" to true), t.token)["is_enabled"].asBoolean()).isTrue()
        val start = post("/orders/$o/payments", mapOf("provider" to "sandbox"), t.token)
        assertThat(start["payment"]["status"].asText()).isEqualTo("pending")
        assertThat(start["client_secret"].asText()).endsWith("_secret")
        // Retrying the same checkout reuses the open attempt.
        assertThat(post("/orders/$o/payments", mapOf("provider" to "sandbox"), t.token)["payment"]["id"]).isEqualTo(start["payment"]["id"])
        val pid = start["payment"]["id"].asText()
        assertThat(post("/payments/$pid/sandbox", mapOf("outcome" to "succeeded"), t.token)["status"].asText()).isEqualTo("succeeded")
        assertThat(post("/payments/$pid/sandbox", mapOf("outcome" to "failed"), t.token).status).isEqualTo(409)
        assertThat(get("/orders/$o", t.token)["payment_status"].asText()).isEqualTo("paid")
    }

    @Test
    fun `stripe charges, signed webhooks and refunds`() {
        val t = shop()
        val p = product(t, price = "25.00", track = false)
        assertThat(put("/payments/gateways/stripe", mapOf("secret_key" to "pk_wrong"), t.token).status).isEqualTo(422)
        assertThat(put("/payments/gateways/stripe", mapOf("is_enabled" to true, "secret_key" to "sk_test_fake"), t.token).status).isEqualTo(422) // no webhook secret
        val g = put("/payments/gateways/stripe", mapOf("is_enabled" to true, "secret_key" to "sk_test_fake", "webhook_secret" to "whsec_test",
            "publishable_key" to "pk_test_123"), t.token)
        assertThat(g.status).isEqualTo(200)
        assertThat(g["secret_set"].asBoolean()).isTrue()
        assertThat(g.body.toString()).doesNotContain("sk_test_fake").doesNotContain("whsec_test")
        superuser().use { c ->
            val rs = c.createStatement().executeQuery("SELECT secret_enc, webhook_secret_enc FROM payment_gateways WHERE tenant_id = '${t.id}'")
            rs.next()
            assertThat(rs.getString(1)).startsWith("v1:").doesNotContain("sk_test")
            assertThat(rs.getString(2)).startsWith("v1:").doesNotContain("whsec")
        }

        val o = order(t, mapOf(p.variantId to 2))["id"].asText()
        val start = post("/orders/$o/payments", mapOf("provider" to "stripe"), t.token)
        assertThat(start.status).isEqualTo(201)
        val pid = start["payment"]["id"].asText()
        val intent = start["payment"]["provider_ref"].asText()
        assertThat(start["client_secret"].asText()).isEqualTo("${intent}_secret_x")
        assertThat(start["publishable_key"].asText()).isEqualTo("pk_test_123")
        val call = stripe.calls.last { it.path == "/v1/payment_intents" && it.idempotencyKey == "tc-charge-$pid" }
        assertThat(call.form["amount"]).isEqualTo("5000")
        assertThat(call.form["currency"]).isEqualTo("usd")
        assertThat(call.form["metadata[order_id]"]).isEqualTo(o)

        val succeeded = event("evt_${pid.take(8)}", "payment_intent.succeeded", mapOf("id" to intent, "metadata" to mapOf("payment_id" to pid)))
        assertThat(webhook(t.id, succeeded, "t=1,v1=deadbeef").status).isEqualTo(400)
        assertThat(webhook(t.id, succeeded, stripe.sign(succeeded, "whsec_other")).status).isEqualTo(400)
        assertThat(webhook(t.id, succeeded, stripe.sign(succeeded, "whsec_test", System.currentTimeMillis() / 1000 - 3600)).status).isEqualTo(400)
        assertThat(get("/orders/$o", t.token)["payment_status"].asText()).isEqualTo("unpaid")
        assertThat(webhook(t.id, succeeded, stripe.sign(succeeded, "whsec_test")).status).isEqualTo(200)
        assertThat(webhook(t.id, succeeded, stripe.sign(succeeded, "whsec_test"))["duplicate"].asBoolean()).isTrue()
        val paid = get("/orders/$o", t.token)
        assertThat(paid["payment_status"].asText()).isEqualTo("paid")
        assertThat(paid["paid_total"].decimalValue()).isEqualByComparingTo("50")
        // Another tenant's id in the URL: its (missing) secret is used, so the event is rejected.
        assertThat(webhook(shop().id, succeeded, stripe.sign(succeeded, "whsec_test")).status).isEqualTo(404)

        val refund = post("/payments/$pid/refund", mapOf("amount" to 10), t.token)
        assertThat(refund["status"].asText()).isEqualTo("pending")
        assertThat(stripe.calls.last { it.path == "/v1/refunds" }.form).containsEntry("amount", "1000").containsEntry("payment_intent", intent)
        val done = event("evt_r_${pid.take(8)}", "refund.updated", mapOf("id" to refund["provider_ref"].asText(), "status" to "succeeded"))
        assertThat(webhook(t.id, done, stripe.sign(done, "whsec_test")).status).isEqualTo(200)
        assertThat(get("/orders/$o", t.token)["refunded_total"].decimalValue()).isEqualByComparingTo("10")

        // A provider error is reported and the attempt is marked failed.
        val o2 = order(t, mapOf(p.variantId to 1))["id"].asText()
        stripe.failNextIntent = "Your card was declined."
        val failed = post("/orders/$o2/payments", mapOf("provider" to "stripe"), t.token)
        assertThat(failed.status).isEqualTo(502)
        assertThat(failed["error"]["message"].asText()).contains("declined")
        assertThat(get("/orders/$o2/payments", t.token)["items"][0]["status"].asText()).isEqualTo("failed")
    }
}

class ReturnsTest : CommerceTest() {

    @Test
    fun `a return is approved, received back into stock and refunded`() {
        val t = shop()
        warehouse(t)
        val a = product(t, price = "10.00"); val b = product(t, price = "20.00")
        addStock(t, a.variantId, 10); addStock(t, b.variantId, 10)
        val o = order(t, mapOf(a.variantId to 3, b.variantId to 1))
        val id = o["id"].asText()
        val charge = post("/orders/$id/payments", mapOf("provider" to "manual"), t.token)["payment"]["id"].asText()
        assertThat(move(t, id, "shipped")["stock_state"].asText()).isEqualTo("deducted")
        assertThat(stockOf(t, a.sku)["on_hand"].asInt()).isEqualTo(7)

        val itemA = o["items"].first { it["variant_id"].asText() == a.variantId }["id"].asText()
        assertThat(post("/returns", mapOf("order_id" to id, "items" to listOf(mapOf("order_item_id" to itemA, "quantity" to 4))), t.token).status).isEqualTo(422)
        val rma = post("/returns", mapOf("order_id" to id, "reason" to "Wrong size", "items" to listOf(mapOf("order_item_id" to itemA, "quantity" to 2))), t.token)
        assertThat(rma.status).isEqualTo(201)
        assertThat(rma["status"].asText()).isEqualTo("requested")
        assertThat(rma["refundable"].decimalValue()).isEqualByComparingTo("20")
        val rid = rma["id"].asText()
        val rmaItem = rma["items"][0]["id"].asText()

        assertThat(post("/returns/$rid/receive", mapOf("items" to listOf(mapOf("rma_item_id" to rmaItem, "quantity" to 2))), t.token).status).isEqualTo(409)
        assertThat(post("/returns/$rid/approve", null, t.token)["status"].asText()).isEqualTo("approved")
        val received = post("/returns/$rid/receive", mapOf("items" to listOf(mapOf("rma_item_id" to rmaItem, "quantity" to 2))), t.token)
        assertThat(received["status"].asText()).isEqualTo("received")
        assertThat(received["items"][0]["restocked_quantity"].asInt()).isEqualTo(2)
        assertThat(stockOf(t, a.sku)["on_hand"].asInt()).isEqualTo(9)
        assertThat(get("/inventory/movements?kind=return", t.token)["items"][0]["quantity"].asInt()).isEqualTo(2)

        val refunded = post("/returns/$rid/refund", mapOf("payment_id" to charge), t.token)
        assertThat(refunded["status"].asText()).isEqualTo("refunded")
        assertThat(refunded["refund_total"].decimalValue()).isEqualByComparingTo("20")
        assertThat(refunded["refunds"][0]["amount"].decimalValue()).isEqualByComparingTo("20")
        assertThat(get("/orders/$id", t.token)["payment_status"].asText()).isEqualTo("partially_refunded")
        assertThat(post("/returns/$rid/refund", emptyMap<String, Any>(), t.token).status).isEqualTo(409)
        assertThat(post("/returns/$rid/close", null, t.token)["status"].asText()).isEqualTo("closed")

        // Only one more of item A can come back.
        assertThat(post("/returns", mapOf("order_id" to id, "items" to listOf(mapOf("order_item_id" to itemA, "quantity" to 2))), t.token).status).isEqualTo(422)
        val second = post("/returns", mapOf("order_id" to id, "items" to listOf(mapOf("order_item_id" to itemA, "quantity" to 1))), t.token)
        assertThat(second.status).isEqualTo(201)
        // Rejecting frees the quantity again.
        post("/returns/${second["id"].asText()}/reject", mapOf("note" to "Outside the return window"), t.token)
        assertThat(post("/returns", mapOf("order_id" to id, "items" to listOf(mapOf("order_item_id" to itemA, "quantity" to 1))), t.token).status).isEqualTo(201)
        assertThat(get("/returns?order_id=$id", t.token)["total"].asInt()).isEqualTo(3)
    }
}
