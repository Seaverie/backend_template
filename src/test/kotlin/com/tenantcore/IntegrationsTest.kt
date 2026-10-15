package com.tenantcore

import com.tenantcore.common.Invalid
import com.tenantcore.config.TcProperties
import com.tenantcore.integrations.NetGuard
import com.tenantcore.integrations.PromptPay
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.math.BigDecimal
import java.net.InetAddress
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun <T> eventually(timeoutMs: Long = 15_000, check: () -> T?): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try { check()?.let { return it } } catch (e: Throwable) { last = e }
        Thread.sleep(150)
    }
    throw AssertionError("condition not met within ${timeoutMs}ms", last)
}

/** No Spring: the address checks themselves, as production runs them (private targets refused). */
class NetGuardTest {
    private val guard = NetGuard(TcProperties())

    @Test
    fun `private, loopback and reserved addresses are refused`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "198.18.0.1", "240.0.0.1",
            "::1", "fc00::1", "fe80::1", "64:ff9b::a00:1").forEach { assertThat(guard.isPublic(InetAddress.getByName(it))).describedAs(it).isFalse() }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach { assertThat(guard.isPublic(InetAddress.getByName(it))).describedAs(it).isTrue() }
        assertThatThrownBy { guard.checkUrl("http://example.com/hook") }.isInstanceOf(Invalid::class.java)
        assertThatThrownBy { guard.checkUrl("https://127.0.0.1/hook") }.isInstanceOf(Invalid::class.java)
        assertThatThrownBy { guard.checkUrl("https://localhost/hook") }.isInstanceOf(Invalid::class.java)
        assertThatThrownBy { guard.checkUrl("https://user:pw@example.com/") }.isInstanceOf(Invalid::class.java)
        // Even a reachable local server is never contacted.
        assertThatThrownBy { guard.post("https://localhost:9/x", emptyMap(), ByteArray(0), 1000) }.isInstanceOf(Invalid::class.java)
    }

    @Test
    fun `promptpay payloads follow the EMVCo format`() {
        assertThat(PromptPay.crc16("123456789")).isEqualTo("29B1") // CRC-16/CCITT-FALSE check value
        val open = PromptPay.payload("081-234-5678", null)
        assertThat(open).startsWith("000201010211" + "2937" + "0016A000000677010111" + "01130066812345678" + "5802TH" + "5303764" + "6304")
        assertThat(open.takeLast(4)).isEqualTo(PromptPay.crc16(open.dropLast(4)))
        val withAmount = PromptPay.payload("1234567890123", BigDecimal("150.5"))
        assertThat(withAmount).contains("010212").contains("02131234567890123").contains("5406150.50")
        assertThat(withAmount.takeLast(4)).isEqualTo(PromptPay.crc16(withAmount.dropLast(4)))
        assertThatThrownBy { PromptPay.payload("12345", null) }.isInstanceOf(Invalid::class.java)
    }
}

class WebhooksTest : CommerceTest() {

    private fun verify(secret: String, header: String, body: String): Boolean {
        val t = header.substringAfter("t=").substringBefore(",")
        val v1 = header.substringAfter("v1=")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return mac.doFinal("$t.$body".toByteArray()).joinToString("") { "%02x".format(it) } == v1
    }

    @Test
    fun `events reach subscribed endpoints, signed, only after the change commits`() {
        val t = shop(allCommerce + "webhooks")
        val path = "/hooks/${UUID.randomUUID()}"
        assertThat(post("/webhooks", mapOf("url" to "${apis.baseUrl}$path", "events" to listOf("nonsense.event")), t.token).status).isEqualTo(422)
        val hook = post("/webhooks", mapOf("url" to "${apis.baseUrl}$path", "events" to listOf("order.*"), "description" to "ERP"), t.token)
        assertThat(hook.status).isEqualTo(201)
        val secret = hook["secret"].asText()
        assertThat(secret).startsWith("whsec_")
        assertThat(get("/webhooks", t.token)["items"][0].has("secret").let { has -> !has || get("/webhooks", t.token)["items"][0]["secret"].isNull }).isTrue()

        warehouse(t)
        val p = product(t, price = "10.00")
        // A failed order (no stock) rolls back, and with it the event.
        assertThat(order(t, mapOf(p.variantId to 1)).status).isEqualTo(409)
        addStock(t, p.variantId, 5)
        val o = order(t, mapOf(p.variantId to 2))["id"].asText()

        val hit = eventually { apis.calls(path).firstOrNull() }
        assertThat(hit.headers["tenantcore-event"]).isEqualTo("order.created")
        assertThat(verify(secret, hit.headers["tenantcore-signature"]!!, hit.body)).isTrue()
        val event = mapper.readTree(hit.body)
        assertThat(event["type"].asText()).isEqualTo("order.created")
        assertThat(event["data"]["id"].asText()).isEqualTo(o)
        assertThat(event["data"]["items"][0]["quantity"].asInt()).isEqualTo(2)
        Thread.sleep(500)
        assertThat(apis.calls(path)).hasSize(1) // product.created was not subscribed, the failed order never happened

        val deliveries = eventually { get("/webhooks/${hook["id"].asText()}/deliveries", t.token)["items"].takeIf { it.size() == 1 && it[0]["status"].asText() == "succeeded" } }
        assertThat(deliveries[0]["last_status_code"].asInt()).isEqualTo(200)

        // A failing receiver is retried later; the attempt is recorded.
        apis.webhookStatus[path] = 500
        move(t, o, "confirmed")
        val failed = eventually { get("/webhooks/${hook["id"].asText()}/deliveries", t.token)["items"].firstOrNull { it["status"].asText() == "failed" } }
        assertThat(failed["last_status_code"].asInt()).isEqualTo(500)
        assertThat(failed["event_type"].asText()).isEqualTo("order.status_changed")
        val jobs = get("/platform/jobs?kind=webhook.deliver&status=queued", platformToken())
        assertThat(jobs["items"].any { it["last_error"].asText().contains("500") }).isTrue()

        apis.webhookStatus.remove(path)
        val again = post("/webhooks/deliveries/${failed["id"].asText()}/redeliver", null, t.token)
        assertThat(again.status).isEqualTo(200)
        eventually { get("/webhooks/${hook["id"].asText()}/deliveries", t.token)["items"].firstOrNull { it["id"].asText() == again["id"].asText() && it["status"].asText() == "succeeded" } }

        // Rotating the secret returns a new one, once.
        assertThat(post("/webhooks/${hook["id"].asText()}/rotate-secret", null, t.token)["secret"].asText()).isNotEqualTo(secret)
        // Without the module, nothing is sent and the endpoints are not reachable.
        put("/platform/tenants/${t.id}/modules", mapOf("modules" to mapOf("webhooks" to false)), platformToken())
        assertThat(get("/webhooks", login(t.slug, t.ownerEmail)["access_token"].asText()).status).isEqualTo(403)
    }
}

class ApiKeysTest : CommerceTest() {

    @Test
    fun `api keys act with their role's permissions and can be revoked`() {
        val t = shop()
        product(t)
        val reader = post("/roles", mapOf("name" to "Catalog reader", "permissions" to listOf(mapOf("resource" to "products", "action" to "read"))), t.token)["id"].asText()
        val created = post("/api-keys", mapOf("name" to "Website", "role_id" to reader, "expires_in_days" to 30), t.token)
        assertThat(created.status).isEqualTo(201)
        val key = created["key"].asText()
        assertThat(key).startsWith("tck_")
        assertThat(get("/api-keys", t.token)["items"][0]["key"].isNull).isTrue()
        assertThat(get("/api-keys", t.token)["items"][0]["hint"].asText()).isEqualTo("…" + key.takeLast(4))

        assertThat(get("/products", key).status).isEqualTo(200)
        assertThat(post("/products", mapOf("name" to "x", "variants" to listOf(mapOf("sku" to "K1", "price" to 1))), key).status).isEqualTo(403)
        assertThat(get("/auth/me", key).status).isEqualTo(403)
        assertThat(post("/api-keys", mapOf("name" to "child", "role_id" to reader), key).status).isEqualTo(403)
        assertThat(get("/products", key.dropLast(2) + "xx").status).isEqualTo(401)
        // The tenant part of a key cannot be swapped to reach another workspace.
        val other = shop()
        assertThat(get("/products", "tck_${other.id.replace("-", "")}_${key.substringAfterLast('_')}").status).isEqualTo(401)

        // Writes by a key are attributed to it.
        val writer = post("/roles", mapOf("name" to "Catalog writer", "permissions" to listOf(mapOf("resource" to "products", "action" to "create"),
            mapOf("resource" to "products", "action" to "read"))), t.token)["id"].asText()
        val wkey = post("/api-keys", mapOf("name" to "Importer", "role_id" to writer), t.token)["key"].asText()
        assertThat(post("/products", mapOf("name" to "By key", "variants" to listOf(mapOf("sku" to "K2", "price" to 1))), wkey).status).isEqualTo(201)
        assertThat(get("/audit-logs", t.token)["items"].first { it["action"].asText() == "product.create" }["actor_type"].asText()).isEqualTo("api_key")

        // A manager cannot mint a key stronger than themselves.
        val mgr = post("/roles", mapOf("name" to "Key manager", "permissions" to listOf(mapOf("resource" to "api_keys", "action" to "manage"),
            mapOf("resource" to "products", "action" to "read"))), t.token)["id"].asText()
        val (_, manager) = newUser(t, listOf(mgr))
        assertThat(post("/api-keys", mapOf("name" to "Sneaky", "role_id" to writer), manager).status).isEqualTo(403)
        assertThat(post("/api-keys", mapOf("name" to "Fine", "role_id" to reader), manager).status).isEqualTo(201)

        val id = created["id"].asText()
        assertThat(delete("/api-keys/$id", t.token).status).isEqualTo(204)
        assertThat(get("/products", key).status).isEqualTo(401)
    }
}

class CustomersTest : CommerceTest() {
    private val mods = listOf("catalog", "orders", "customer_accounts", "email", "inventory")
    private fun acct(slug: String, path: String, body: Any? = null, token: String? = null, method: HttpMethod = HttpMethod.POST) =
        call(method, "/api/v1/public/shop/$slug/account$path", body, token)
    private fun linkToken(mail: Mail) = Regex("token=([A-Za-z0-9._%-]+)").find(mail.text)!!.groupValues[1].let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }

    @Test
    fun `customers sign up, confirm their email, sign in and see their orders`() {
        usePlatformMailpit()
        val t = shop(mods)
        val email = "cust-${UUID.randomUUID().toString().take(6)}@example.com"
        assertThat(acct(t.slug, "/register", mapOf("email" to email, "password" to "short", "name" to "Nok")).status).isEqualTo(422)
        val reg = acct(t.slug, "/register", mapOf("email" to email, "password" to "Mango-Sticky-1", "name" to "Nok"))
        assertThat(reg.status).isEqualTo(201)
        assertThat(acct(t.slug, "/register", mapOf("email" to email.uppercase(), "password" to "Mango-Sticky-1", "name" to "Nok")).status).isEqualTo(409)
        var access = reg["access_token"].asText()
        assertThat(acct(t.slug, "/me", token = access, method = HttpMethod.GET)["email_verified"].asBoolean()).isFalse()

        val mail = awaitMail(email, "Confirm your email")
        assertThat(acct(t.slug, "/verify-email", mapOf("token" to linkToken(mail))).status).isEqualTo(204)
        assertThat(acct(t.slug, "/me", token = access, method = HttpMethod.GET)["email_verified"].asBoolean()).isTrue()
        assertThat(acct(t.slug, "/verify-email", mapOf("token" to linkToken(mail))).status).isEqualTo(422) // single use

        // A customer token is not a staff token, and a staff token is not a customer token.
        assertThat(get("/products", access).status).isEqualTo(401)
        assertThat(acct(t.slug, "/me", token = t.token, method = HttpMethod.GET).status).isEqualTo(401)
        // Nor does it work in another shop.
        val other = shop(mods)
        assertThat(acct(other.slug, "/me", token = access, method = HttpMethod.GET).status).isEqualTo(401)

        // Signed-in checkout uses the account and links the order to it.
        put("/orders/settings", mapOf("storefront_checkout" to true), t.token)
        val p = product(t, price = "20.00", track = false)
        val checkout = call(HttpMethod.POST, "/api/v1/public/shop/${t.slug}/checkout", mapOf("items" to listOf(mapOf("variant_id" to p.variantId, "quantity" to 1))), access)
        assertThat(checkout.status).isEqualTo(201)
        val orderId = checkout["order"]["id"].asText()
        assertThat(call(HttpMethod.POST, "/api/v1/public/shop/${t.slug}/checkout", mapOf("items" to listOf(mapOf("variant_id" to p.variantId, "quantity" to 1)))).status)
            .isEqualTo(422) // a guest must give name and email
        val mine = acct(t.slug, "/orders", token = access, method = HttpMethod.GET)
        assertThat(mine["items"].map { it["id"].asText() }).containsExactly(orderId)
        assertThat(get("/orders/$orderId", t.token)["customer_email"].asText()).isEqualTo(email)

        val email2 = "cust2-${UUID.randomUUID().toString().take(6)}@example.com"
        val second = acct(t.slug, "/register", mapOf("email" to email2, "password" to "Papaya-Salad-2", "name" to "Lek"))["access_token"].asText()
        assertThat(acct(t.slug, "/orders/$orderId", token = second, method = HttpMethod.GET).status).isEqualTo(404)

        // Refresh rotates; reusing an old refresh token ends the session.
        val login = acct(t.slug, "/login", mapOf("email" to email, "password" to "Mango-Sticky-1"))
        val r1 = login["refresh_token"].asText()
        val r2 = acct(t.slug, "/refresh", mapOf("refresh_token" to r1))["refresh_token"].asText()
        assertThat(acct(t.slug, "/refresh", mapOf("refresh_token" to r1)).status).isEqualTo(401)
        assertThat(acct(t.slug, "/refresh", mapOf("refresh_token" to r2)).status).isEqualTo(401)

        // Staff see and can deactivate customers.
        val listed = get("/customers?q=${email.substringBefore('@')}", t.token)
        assertThat(listed["items"][0]["order_count"].asInt()).isEqualTo(1)
        val cid = listed["items"][0]["id"].asText()
        patch("/customers/$cid", mapOf("is_active" to false), t.token)
        assertThat(acct(t.slug, "/me", token = access, method = HttpMethod.GET).status).isEqualTo(401)
        assertThat(acct(t.slug, "/login", mapOf("email" to email, "password" to "Mango-Sticky-1")).status).isEqualTo(401)
        patch("/customers/$cid", mapOf("is_active" to true), t.token)

        // Forgotten password: the reset ends every session.
        access = acct(t.slug, "/login", mapOf("email" to email, "password" to "Mango-Sticky-1"))["access_token"].asText()
        assertThat(acct(t.slug, "/forgot-password", mapOf("email" to "nobody@example.com")).status).isEqualTo(202)
        assertThat(acct(t.slug, "/forgot-password", mapOf("email" to email)).status).isEqualTo(202)
        val reset = awaitMail(email, "Reset your")
        assertThat(acct(t.slug, "/reset-password", mapOf("token" to linkToken(reset), "password" to "Durian-Season-3")).status).isEqualTo(204)
        assertThat(acct(t.slug, "/me", token = access, method = HttpMethod.GET).status).isEqualTo(401)
        access = acct(t.slug, "/login", mapOf("email" to email, "password" to "Durian-Season-3"))["access_token"].asText()

        // Closing the account erases personal details; the business keeps the order.
        assertThat(acct(t.slug, "/me/delete", mapOf("password" to "wrong-password"), access).status).isEqualTo(401)
        assertThat(acct(t.slug, "/me/delete", mapOf("password" to "Durian-Season-3"), access).status).isEqualTo(204)
        assertThat(acct(t.slug, "/login", mapOf("email" to email, "password" to "Durian-Season-3")).status).isEqualTo(401)
        assertThat(get("/orders/$orderId", t.token).status).isEqualTo(200)
        superuser().use { c ->
            val rs = c.createStatement().executeQuery("SELECT email, name FROM customers WHERE id = '$cid'")
            rs.next()
            assertThat(rs.getString(1)).startsWith("deleted-")
            assertThat(rs.getString(2)).isNull()
        }
    }

    @Test
    fun `shops can require a confirmed email and switch sign-ups off`() {
        val t = shop(mods)
        assertThat(put("/customers/settings", mapOf("require_verified_email" to true, "storefront_url" to "https://shop.example.test"), t.token).status).isEqualTo(200)
        val email = "v-${UUID.randomUUID().toString().take(6)}@example.com"
        acct(t.slug, "/register", mapOf("email" to email, "password" to "Mango-Sticky-1", "name" to "V"))
        val denied = acct(t.slug, "/login", mapOf("email" to email, "password" to "Mango-Sticky-1"))
        assertThat(denied.status).isEqualTo(403)
        assertThat(denied["error"]["code"].asText()).isEqualTo("email_not_verified")
        put("/customers/settings", mapOf("allow_signup" to false), t.token)
        assertThat(acct(t.slug, "/register", mapOf("email" to "x$email", "password" to "Mango-Sticky-1", "name" to "X")).status).isEqualTo(403)
        // A shop without customer accounts has no account endpoints.
        val plain = shop(listOf("catalog", "orders"))
        assertThat(acct(plain.slug, "/register", mapOf("email" to "a@example.com", "password" to "Mango-Sticky-1", "name" to "A")).status).isEqualTo(404)
    }
}

class PushTest : CommerceTest() {
    private val mods = listOf("catalog", "orders", "customer_accounts", "email", "push_notifications")

    @Test
    fun `notifications go out through FCM and dead tokens are retired`() {
        val t = shop(mods)
        assertThat(put("/notifications/settings", mapOf("service_account_json" to "{\"type\":\"user\"}"), t.token).status).isEqualTo(422)
        val s = put("/notifications/settings", mapOf("service_account_json" to apis.serviceAccountJson), t.token)
        assertThat(s["configured"].asBoolean()).isTrue()
        assertThat(s["project_id"].asText()).isEqualTo("demo-shop-app")
        assertThat(s.body.toString()).doesNotContain("PRIVATE KEY")

        val email = "p-${UUID.randomUUID().toString().take(6)}@example.com"
        val access = call(HttpMethod.POST, "/api/v1/public/shop/${t.slug}/account/register",
            mapOf("email" to email, "password" to "Mango-Sticky-1", "name" to "P"))["access_token"].asText()
        val good = "good-${UUID.randomUUID()}"
        assertThat(call(HttpMethod.POST, "/api/v1/public/shop/${t.slug}/account/devices", mapOf("token" to good, "platform" to "android"), access).status).isEqualTo(204)
        assertThat(call(HttpMethod.POST, "/api/v1/public/shop/${t.slug}/account/devices", mapOf("token" to "bad-${UUID.randomUUID()}", "platform" to "ios"), access).status)
            .isEqualTo(204)

        val sent = post("/notifications", mapOf("title" to "Flash sale", "body" to "20% off today", "data" to mapOf("screen" to "sale"),
            "audience" to mapOf("all_customers" to true)), t.token)
        assertThat(sent.status).isEqualTo(201)
        assertThat(sent["target_count"].asInt()).isEqualTo(2)
        val done = eventually { get("/notifications/${sent["id"].asText()}", t.token).takeIf { it["pending"].asInt() == 0 } }
        assertThat(done["sent"].asInt()).isEqualTo(1)
        assertThat(done["failed"].asInt()).isEqualTo(1)
        val msg = mapper.readTree(apis.calls("/v1/projects/demo-shop-app/messages:send").last { it.body.contains(good) }.body)
        assertThat(msg["message"]["notification"]["title"].asText()).isEqualTo("Flash sale")
        assertThat(msg["message"]["data"]["screen"].asText()).isEqualTo("sale")

        // The dead token is not tried again.
        val second = post("/notifications", mapOf("title" to "Again", "body" to "x", "audience" to mapOf("all_customers" to true)), t.token)
        assertThat(second["target_count"].asInt()).isEqualTo(1)

        // Order updates reach the customer's phone automatically.
        put("/orders/settings", mapOf("storefront_checkout" to true), t.token)
        val p = product(t, track = false)
        val order = call(HttpMethod.POST, "/api/v1/public/shop/${t.slug}/checkout", mapOf("items" to listOf(mapOf("variant_id" to p.variantId, "quantity" to 1))), access)
        move(t, order["order"]["id"].asText(), "confirmed")
        eventually { apis.calls("/v1/projects/").firstOrNull { it.body.contains("Order #${order["order"]["number"].asText()}") && it.body.contains("Confirmed") } }
        assertThat(get("/notifications", t.token)["items"].any { it["source"].asText() == "auto" }).isTrue()

        // Staff without the permission cannot broadcast.
        val (_, member) = newUser(t)
        assertThat(post("/notifications", mapOf("title" to "x", "body" to "y", "audience" to mapOf("all_customers" to true)), member).status).isEqualTo(403)
    }
}

class MapsDeliveriesTest : CommerceTest() {

    @Test
    fun `geocoding is cached per tenant and google needs a key`() {
        val t = shop(listOf("maps"))
        val first = get("/maps/geocode?q=Siam+Paragon", t.token)
        assertThat(first["items"][0]["lat"].asDouble()).isEqualTo(13.7462)
        val calls = apis.calls("/search").size
        get("/maps/geocode?q=siam++paragon", t.token)
        assertThat(apis.calls("/search").size).isEqualTo(calls) // same query (case and spacing ignored): from the cache
        assertThat(get("/maps/reverse?lat=13.7462&lng=100.5347", t.token)["place"]["label"].asText()).contains("Bangkok")

        assertThat(put("/maps/settings", mapOf("provider" to "google"), t.token).status).isEqualTo(422)
        put("/maps/settings", mapOf("provider" to "google", "google_api_key" to "wrong-key"), t.token)
        assertThat(get("/maps/geocode?q=Chiang+Mai+old+city", t.token).status).isEqualTo(502)
        put("/maps/settings", mapOf("google_api_key" to "good-key"), t.token)
        assertThat(get("/maps/geocode?q=Chiang+Mai+old+city", t.token)["items"][0]["country_code"].asText()).isEqualTo("th")
        assertThat(get("/maps/settings", t.token).body.toString()).doesNotContain("good-key")

        val km = post("/maps/distance", mapOf("from" to mapOf("lat" to 13.7563, "lng" to 100.5018), "to" to mapOf("lat" to 18.7883, "lng" to 98.9853)), t.token)["km"].asDouble()
        assertThat(km).isBetween(575.0, 595.0) // Bangkok to Chiang Mai, straight line
    }

    @Test
    fun `drivers update their deliveries and customers follow them by link`() {
        val t = shop(listOf("catalog", "orders", "delivery_tracking"))
        val p = product(t, track = false)
        val orderId = order(t, mapOf(p.variantId to 1))["id"].asText()
        move(t, orderId, "confirmed"); move(t, orderId, "shipped")
        val driverRole = post("/roles", mapOf("name" to "Driver", "permissions" to listOf(mapOf("resource" to "deliveries", "action" to "read"))), t.token)["id"].asText()
        val (driverId, driver) = newUser(t, listOf(driverRole))
        val (_, otherDriver) = newUser(t, listOf(driverRole))

        val d = post("/deliveries", mapOf("order_id" to orderId, "driver_user_id" to driverId, "destination_lat" to 13.75, "destination_lng" to 100.5,
            "destination_label" to "Condo lobby"), t.token)
        assertThat(d.status).isEqualTo(201)
        val id = d["id"].asText()
        assertThat(post("/deliveries", mapOf("order_id" to orderId), driver).status).isEqualTo(403)
        assertThat(get("/deliveries?mine=true", driver)["items"].map { it["id"].asText() }).containsExactly(id)

        val now = java.time.OffsetDateTime.now()
        val ping = mapOf("pings" to listOf(mapOf("lat" to 13.70, "lng" to 100.52, "accuracy_m" to 8, "recorded_at" to now.toString())))
        assertThat(post("/deliveries/$id/pings", ping, driver).status).isEqualTo(409) // not picked up yet
        assertThat(patch("/deliveries/$id", mapOf("status" to "delivered"), driver).status).isEqualTo(409) // must be picked up first
        assertThat(patch("/deliveries/$id", mapOf("status" to "picked_up"), driver)["status"].asText()).isEqualTo("picked_up")
        assertThat(post("/deliveries/$id/pings", ping, otherDriver).status).isEqualTo(403)
        assertThat(patch("/deliveries/$id", mapOf("driver_user_id" to driverId), driver).status).isEqualTo(403) // reassigning needs deliveries:update
        assertThat(post("/deliveries/$id/pings", mapOf("pings" to listOf(mapOf("lat" to 1, "lng" to 1, "recorded_at" to now.plusHours(1).toString()))), driver).status)
            .isEqualTo(422)
        assertThat(post("/deliveries/$id/pings", mapOf("pings" to listOf(mapOf("lat" to 95, "lng" to 1, "recorded_at" to now.toString()))), driver).status)
            .isEqualTo(422)
        assertThat(post("/deliveries/$id/pings", ping, driver)["accepted"].asInt()).isEqualTo(1)
        patch("/deliveries/$id", mapOf("status" to "in_transit"), driver)

        val token = d["tracking_token"].asText()
        val tracked = get("/public/shop/${t.slug}/deliveries/$id?token=$token")
        assertThat(tracked["status"].asText()).isEqualTo("in_transit")
        assertThat(tracked["location"]["lat"].asDouble()).isEqualTo(13.70)
        assertThat(get("/public/shop/${t.slug}/deliveries/$id?token=x$token").status).isEqualTo(404)

        assertThat(patch("/deliveries/$id", mapOf("status" to "delivered"), driver)["delivered_at"].isNull).isFalse()
        assertThat(get("/public/shop/${t.slug}/deliveries/$id?token=$token")["location"].isNull).isTrue() // no longer shown
        assertThat(get("/orders/$orderId", t.token)["status"].asText()).isEqualTo("delivered")       // the order followed
        assertThat(get("/deliveries/$id", t.token)["recent_pings"].size()).isEqualTo(1)
    }
}

class QrTest : CommerceTest() {

    private fun decode(png: ByteArray): String {
        val img = javax.imageio.ImageIO.read(png.inputStream())
        val px = IntArray(img.width * img.height).also { img.getRGB(0, 0, img.width, img.height, it, 0, img.width) }
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(img.width, img.height, px)))
        return com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text
    }

    @Test
    fun `qr codes and promptpay payments`() {
        val t = shop(listOf("catalog", "orders", "payments", "qr_codes"))
        val png = raw("/api/v1/qr?data=${java.net.URLEncoder.encode("https://shop.example/ไทย", Charsets.UTF_8)}&size=300", t.token)
        assertThat(png.statusCode()).isEqualTo(200)
        assertThat(decode(png.body())).isEqualTo("https://shop.example/ไทย")
        val svg = raw("/api/v1/qr?data=hello&format=svg", t.token)
        assertThat(svg.headers().firstValue("content-type").get()).startsWith("image/svg+xml")
        assertThat(String(svg.body())).doesNotContain("hello").doesNotContain("<script")
        assertThat(raw("/api/v1/qr?data=x&size=5000", t.token).statusCode()).isEqualTo(422)

        val p = product(t, price = "125.50", track = false)
        val o = order(t, mapOf(p.variantId to 2))["id"].asText()
        assertThat(get("/orders/$o/promptpay", t.token).status).isEqualTo(409) // no PromptPay id yet
        assertThat(put("/qr/settings", mapOf("promptpay_id" to "123"), t.token).status).isEqualTo(422)
        put("/qr/settings", mapOf("promptpay_id" to "081-234-5678", "promptpay_name" to "Demo Shop"), t.token)
        assertThat(get("/orders/$o/promptpay", t.token).status).isEqualTo(409) // the shop is in USD
        put("/settings", mapOf("currency" to "THB"), t.token)
        val o2 = order(t, mapOf(p.variantId to 2))["id"].asText()
        val pp = get("/orders/$o2/promptpay", t.token)
        assertThat(pp["amount"].decimalValue()).isEqualByComparingTo("251.00")
        val payload = pp["payload"].asText()
        assertThat(payload).contains("01130066812345678").contains("5406251.00")
        assertThat(payload.takeLast(4)).isEqualTo(PromptPay.crc16(payload.dropLast(4)))
        assertThat(decode(raw("/api/v1/orders/$o2/promptpay?format=png", t.token).body())).isEqualTo(payload)

        // The customer's order page can show it too, with the order's lookup token.
        put("/orders/settings", mapOf("storefront_checkout" to true), t.token)
        val c = call(HttpMethod.POST, "/api/v1/public/shop/${t.slug}/checkout", mapOf("items" to listOf(mapOf("variant_id" to p.variantId, "quantity" to 1)),
            "customer_name" to "A", "customer_email" to "a@example.com"))
        val pub = get("/public/shop/${t.slug}/orders/${c["order"]["id"].asText()}/promptpay?token=${c["lookup_token"].asText()}")
        assertThat(pub["amount"].decimalValue()).isEqualByComparingTo("125.50")
        assertThat(get("/public/shop/${t.slug}/orders/${c["order"]["id"].asText()}/promptpay?token=nope").status).isEqualTo(404)
        // Paid in full: nothing to pay.
        post("/orders/$o2/payments", mapOf("provider" to "manual", "method" to "promptpay"), t.token)
        assertThat(get("/orders/$o2/promptpay", t.token).status).isEqualTo(409)
    }
}
