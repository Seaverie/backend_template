package com.tenantcore

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.nio.file.Files
import java.util.zip.ZipInputStream

class OpsTest : CommerceTest() {
    private val everything = allCommerce + listOf("data_tables", "customer_accounts", "webhooks", "delivery_tracking", "qr_codes")

    private fun zipEntries(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = String(z.readAllBytes(), Charsets.UTF_8) }
        }
        return out
    }

    /** A workspace with rows in most tables: orders with stock movements and refunds, custom data, customers, files, deliveries. */
    private fun busyTenant(): Tenant {
        val t = shop(everything)
        warehouse(t)
        val p = product(t, price = "10.00")
        addStock(t, p.variantId, 10)
        val o = order(t, mapOf(p.variantId to 2))["id"].asText()
        val pay = post("/orders/$o/payments", mapOf("provider" to "manual"), t.token)["payment"]["id"].asText()
        post("/payments/$pay/refund", mapOf("amount" to 5), t.token)
        move(t, o, "shipped")
        post("/deliveries", mapOf("order_id" to o), t.token)
        post("/data/tables", mapOf("key" to "notes", "name" to "Notes", "fields" to listOf(mapOf("key" to "text", "label" to "Text", "type" to "text", "unique" to true))), t.token)
        post("/data/tables/notes/rows", mapOf("data" to mapOf("text" to "hello")), t.token)
        call(HttpMethod.POST, "/api/v1/public/shop/${t.slug}/account/register", mapOf("email" to "c-${t.slug}@example.com", "password" to "Mango-Sticky-1", "name" to "C"))
        upload(t.token, "note.txt", "a file".toByteArray())
        post("/webhooks", mapOf("url" to "${apis.baseUrl}/hooks/${t.slug}", "events" to listOf("order.*")), t.token)
        return t
    }

    @Test
    fun `a workspace admin can export all of their data, without secrets`() {
        val t = busyTenant()
        val (_, member) = newUser(t)
        assertThat(post("/workspace/exports", null, member).status).isEqualTo(403)
        val e = post("/workspace/exports", null, t.token)
        assertThat(e.status).isEqualTo(201)
        assertThat(post("/workspace/exports", null, t.token).status).isIn(201, 409) // one at a time (the first may already be done)
        val done = eventually { get("/workspace/exports", t.token)["items"].firstOrNull { it["id"].asText() == e["id"].asText() && it["status"].asText() == "done" } }
        assertThat(done["size_bytes"].asLong()).isPositive()
        val zip = raw("/api/v1/workspace/exports/${e["id"].asText()}/download", t.token)
        assertThat(zip.statusCode()).isEqualTo(200)
        val entries = zipEntries(zip.body())
        assertThat(entries.keys).contains("manifest.json", "tables/orders.jsonl", "tables/app_users.jsonl", "tables/data_rows.jsonl", "tables/stock_movements.jsonl")
        assertThat(entries["tables/orders.jsonl"]!!.lines().filter { it.isNotBlank() }).hasSize(1)
        assertThat(entries.keys.any { it.startsWith("files/") && it.endsWith("note.txt") }).isTrue()
        assertThat(entries.filterKeys { it.startsWith("tables/") }.values.joinToString()).doesNotContain("password_hash").doesNotContain("secret_enc").doesNotContain("\$argon2")
        assertThat(entries.keys).doesNotContain("tables/auth_sessions.jsonl", "tables/tenant_secrets.jsonl")
        // Another workspace cannot fetch it.
        assertThat(raw("/api/v1/workspace/exports/${e["id"].asText()}/download", shop().token).statusCode()).isEqualTo(404)
    }

    @Test
    fun `offboarding locks the workspace, exports it, and purge removes every row and file`() {
        val t = busyTenant()
        val bystander = busyTenant()
        val token = platformToken()
        val fileKeysDir = storageDir.resolve(t.id)
        assertThat(Files.exists(fileKeysDir)).isTrue()

        assertThat(delete("/platform/tenants/${t.id}?confirm=${t.slug}", token).status).isEqualTo(409) // not offboarded
        assertThat(post("/platform/tenants/${t.id}/offboard", mapOf("confirm" to "wrong"), token).status).isEqualTo(422)
        val off = post("/platform/tenants/${t.id}/offboard", mapOf("confirm" to t.slug), token)
        assertThat(off["status"].asText()).isEqualTo("offboarding")
        assertThat(get("/auth/me", t.token).status).isEqualTo(401)
        assertThat(post("/auth/login", mapOf("tenant" to t.slug, "email" to t.ownerEmail, "password" to PASSWORD)).status).isEqualTo(403)
        assertThat(get("/public/shop/${t.slug}").status).isEqualTo(404)

        val export = eventually { get("/platform/tenants/${t.id}/exports", token)["items"].firstOrNull { it["status"].asText() == "done" } }
        assertThat(delete("/platform/tenants/${t.id}?confirm=nope", token).status).isEqualTo(422)
        assertThat(delete("/platform/tenants/${t.id}?confirm=${t.slug}", token).status).isEqualTo(202)
        eventually { get("/platform/tenants/${t.id}", token).takeIf { it.status == 404 } }

        superuser().use { c ->
            val tables = c.createStatement().executeQuery("""SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t
                USING (table_schema, table_name) WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND t.table_type = 'BASE TABLE'""")
                .let { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            assertThat(tables).hasSizeGreaterThan(40)
            val left = tables.filter { it != "tenant_exports" }.associateWith { table ->
                c.createStatement().executeQuery("SELECT count(*) FROM \"$table\" WHERE tenant_id = '${t.id}'").apply { next() }.getInt(1)
            }.filterValues { it > 0 }
            assertThat(left).isEmpty()
            val other = c.createStatement().executeQuery("SELECT count(*) FROM stock_movements WHERE tenant_id = '${bystander.id}'").apply { next() }.getInt(1)
            assertThat(other).isPositive()
            assertThat(c.createStatement().executeQuery("SELECT count(*) FROM tenants WHERE id = '${t.id}'").apply { next() }.getInt(1)).isZero()
        }
        assertThat(Files.walk(fileKeysDir).use { s -> s.filter { Files.isRegularFile(it) && !it.toString().contains("/exports/") }.count() }).isZero()
        // The final export outlives the workspace for a few days, so it can be handed over.
        assertThat(raw("/api/v1/platform/exports/${export["id"].asText()}/download", token).statusCode()).isEqualTo(200)
        assertThat(get("/platform/audit-logs", token)["items"].map { it["action"].asText() }).contains("tenant.purge_requested", "tenant.offboard")
        eventually { get("/platform/audit-logs", token)["items"].firstOrNull { it["action"].asText() == "tenant.purged" } }
        // The other workspace is untouched.
        assertThat(get("/orders", login(bystander.slug, bystander.ownerEmail)["access_token"].asText())["total"].asInt()).isEqualTo(1)
    }

    @Test
    fun `offboarding can be cancelled, and purge without an export needs force`() {
        val t = shop()
        val token = platformToken()
        post("/platform/tenants/${t.id}/offboard", mapOf("confirm" to t.slug), token)
        assertThat(post("/platform/tenants/${t.id}/cancel-offboarding", null, token)["status"].asText()).isEqualTo("active")
        login(t.slug, t.ownerEmail)

        val u = shop()
        post("/platform/tenants/${u.id}/offboard", mapOf("confirm" to u.slug), token)
        // Remove the automatic export so there is none.
        superuser().use { c -> c.createStatement().execute("DELETE FROM tenant_exports WHERE tenant_id = '${u.id}'") }
        assertThat(delete("/platform/tenants/${u.id}?confirm=${u.slug}", token).status).isEqualTo(409)
        assertThat(delete("/platform/tenants/${u.id}?confirm=${u.slug}&force=true", token).status).isEqualTo(202)
        eventually { get("/platform/tenants/${u.id}", token).takeIf { it.status == 404 } }
    }

    @Test
    fun `backup status lists backups and flags a late one`() {
        val token = platformToken()
        val stamp = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        Files.writeString(backupDir.resolve("tenantcore-$stamp.dump"), "x")
        Files.writeString(backupDir.resolve("storage-$stamp.tar.gz"), "y")
        Files.writeString(backupDir.resolve("notes.txt"), "ignored")
        val s = get("/platform/backups", token)
        assertThat(s["configured"].asBoolean()).isTrue()
        assertThat(s["healthy"].asBoolean()).isTrue()
        assertThat(s["items"].map { it["name"].asText() }).containsExactlyInAnyOrder("tenantcore-$stamp.dump", "storage-$stamp.tar.gz")
        Files.setLastModifiedTime(backupDir.resolve("tenantcore-$stamp.dump"), java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3 * 86400)))
        assertThat(get("/platform/backups", token)["healthy"].asBoolean()).isFalse()
        assertThat(get("/platform/backups", newTenant().token).status).isEqualTo(401)
    }

    @Test
    fun `the dashboard is served with a strict policy and API paths never fall back to it`() {
        val page = raw("/orders/123")
        assertThat(page.statusCode()).isEqualTo(200)
        assertThat(String(page.body())).contains("<div id=\"app\">")
        assertThat(page.headers().firstValue("content-security-policy").get()).contains("script-src 'self'").contains("frame-ancestors 'none'")
        assertThat(raw("/api/v1/no-such-endpoint").statusCode()).isIn(401, 404)
        assertThat(raw("/assets/missing.js").statusCode()).isEqualTo(404)
        assertThat(call(HttpMethod.POST, "/orders", mapOf("x" to 1)).status).isIn(401, 403, 405)
    }
}
