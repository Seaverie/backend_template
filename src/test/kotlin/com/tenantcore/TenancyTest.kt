package com.tenantcore

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection

class TenancyTest : IntegrationTest() {

    private fun Connection.count(sql: String): Int = createStatement().executeQuery(sql).apply { next() }.getInt(1)

    @Test
    fun `tenants cannot see each other's data through the API`() {
        val a = newTenant(); val b = newTenant()
        val (bUser, _) = newUser(b)
        val aUsers = get("/users", a.token)["items"].map { it["email"].asText() }
        assertThat(aUsers).containsExactly(a.ownerEmail)
        // Addressing another tenant's user by id behaves exactly like a missing record.
        assertThat(patch("/users/$bUser", mapOf("full_name" to "pwned"), a.token).status).isEqualTo(404)
        assertThat(get("/audit-logs", a.token)["items"].none { it["resource_id"].asText() == bUser }).isTrue()
    }

    @Test
    fun `row-level security isolates tenants inside the database itself`() {
        val a = newTenant(); val b = newTenant()
        newUser(b)
        appRole().use { c ->
            c.autoCommit = false
            // No tenant context: nothing is visible.
            assertThat(c.count("SELECT count(*) FROM app_users")).isZero()
            c.createStatement().execute("SELECT set_config('app.tenant_id', '${a.id}', true)")
            assertThat(c.count("SELECT count(*) FROM app_users")).isEqualTo(1)
            assertThat(c.count("SELECT count(*) FROM app_users WHERE tenant_id = '${b.id}'")).isZero()
            // Writing a row for another tenant is rejected by the policy.
            assertThatThrownBy {
                c.createStatement().execute("INSERT INTO roles (tenant_id, name) VALUES ('${b.id}', 'sneaky')")
            }.hasMessageContaining("row-level security")
            c.rollback()
        }
        superuser().use { c -> assertThat(c.count("SELECT count(*) FROM app_users WHERE tenant_id = '${b.id}'")).isEqualTo(2) }
    }

    @Test
    fun `the runtime role cannot bypass RLS, alter tenants or rewrite the audit log`() {
        val a = newTenant()
        appRole().use { c ->
            c.autoCommit = false
            c.createStatement().execute("SELECT set_config('app.tenant_id', '${a.id}', true)")
            // A tenant may not change platform-controlled columns of its own row.
            assertThatThrownBy { c.createStatement().execute("UPDATE tenants SET status = 'active', rate_limit_per_min = 99999 WHERE id = '${a.id}'") }
                .hasMessageContaining("platform-controlled")
            c.rollback()
            c.createStatement().execute("SELECT set_config('app.tenant_id', '${a.id}', true)")
            assertThatThrownBy { c.createStatement().execute("DELETE FROM audit_logs") }.hasMessageContaining("permission denied")
            c.rollback()
            c.createStatement().execute("SELECT set_config('app.is_platform', 'on', true)")
            assertThatThrownBy { c.createStatement().execute("UPDATE audit_logs SET action = 'x'") }.hasMessageContaining("permission denied")
            c.rollback()
            assertThatThrownBy { c.createStatement().execute("SET row_security = off; SELECT * FROM app_users") }
                .hasMessageContaining("row-level security")
            c.rollback()
        }
        superuser().use { c ->
            assertThatThrownBy { c.createStatement().execute("DELETE FROM audit_logs") }.hasMessageContaining("append-only")
        }
    }

    @Test
    fun `platform admin creates, lists, edits and suspends tenants`() {
        val token = platformToken()
        val t = newTenant()
        assertThat(post("/platform/tenants", mapOf("slug" to t.slug, "name" to "dup", "owner_email" to "x@example.com", "owner_password" to PASSWORD), token).status)
            .isEqualTo(409)
        assertThat(post("/platform/tenants", mapOf("slug" to "Bad Slug", "name" to "x", "owner_email" to "x@example.com", "owner_password" to PASSWORD), token).status)
            .isEqualTo(422)

        val found = get("/platform/tenants?q=${t.slug}", token)
        assertThat(found["total"].asInt()).isEqualTo(1)
        val detail = get("/platform/tenants/${t.id}", token)
        assertThat(detail["user_count"].asInt()).isEqualTo(1)
        assertThat(detail["modules"].map { it.asText() }).containsExactlyInAnyOrder("catalog", "orders", "files", "email")

        val renamed = patch("/platform/tenants/${t.id}", mapOf("name" to "Renamed", "rate_limit_per_min" to 500), token)
        assertThat(renamed["name"].asText()).isEqualTo("Renamed")
        assertThat(renamed["rate_limit_per_min"].asInt()).isEqualTo(500)
        assertThat(patch("/platform/tenants/${t.id}", mapOf("use_default_rate_limit" to true), token)["rate_limit_per_min"].isNull).isTrue()
        assertThat(patch("/platform/tenants/${t.id}", mapOf("rate_limit_per_min" to 10_000_000), token).status).isEqualTo(422)

        // Suspending signs everyone out and blocks new sign-ins.
        assertThat(patch("/platform/tenants/${t.id}", mapOf("status" to "suspended"), token)["status"].asText()).isEqualTo("suspended")
        assertThat(get("/auth/me", t.token).status).isEqualTo(401)
        val blocked = post("/auth/login", mapOf("tenant" to t.slug, "email" to t.ownerEmail, "password" to PASSWORD))
        assertThat(blocked.status).isEqualTo(403)
        patch("/platform/tenants/${t.id}", mapOf("status" to "active"), token)
        login(t.slug, t.ownerEmail)

        val audit = get("/platform/audit-logs", token)["items"].map { it["action"].asText() }
        assertThat(audit).contains("tenant.create", "tenant.update")
    }

    @Test
    fun `modules are switched per tenant and dependencies are enforced`() {
        val token = platformToken()
        val t = newTenant(modules = listOf("catalog"))
        val bad = put("/platform/tenants/${t.id}/modules", mapOf("modules" to mapOf("returns" to true)), token)
        assertThat(bad.status).isEqualTo(422)
        assertThat(bad["error"]["message"].asText()).contains("Returns needs Orders")
        assertThat(put("/platform/tenants/${t.id}/modules", mapOf("modules" to mapOf("nope" to true)), token).status).isEqualTo(422)

        val ok = put("/platform/tenants/${t.id}/modules", mapOf("modules" to mapOf("orders" to true, "returns" to true, "data_tables" to true)), token)
        assertThat(ok.status).isEqualTo(200)
        assertThat(ok["items"].filter { it["enabled"].asBoolean() }.map { it["code"].asText() })
            .containsExactlyInAnyOrder("catalog", "orders", "returns", "data_tables")
        // Turning off something another module needs is refused.
        assertThat(put("/platform/tenants/${t.id}/modules", mapOf("modules" to mapOf("orders" to false)), token).status).isEqualTo(422)

        val me = get("/auth/me", login(t.slug, t.ownerEmail)["access_token"].asText())
        assertThat(me["modules"].map { it.asText() }).containsExactly("catalog", "data_tables", "orders", "returns")
        assertThat(get("/modules", t.token)["items"].size()).isEqualTo(com.tenantcore.security.Module.entries.size)
        // Workspace users cannot switch modules themselves.
        assertThat(put("/platform/tenants/${t.id}/modules", mapOf("modules" to mapOf("maps" to true)), t.token).status).isEqualTo(401)
    }

    @Test
    fun `settings are edited from the dashboard and validated`() {
        val t = newTenant()
        val initial = get("/settings", t.token)
        assertThat(initial["display_name"].asText()).isEqualTo("Tenant ${t.slug}")
        assertThat(initial["currency"].asText()).isEqualTo("USD")
        val updated = put("/settings", mapOf("display_name" to "Acme", "primary_color" to "#112233", "currency" to "THB",
            "timezone" to "Asia/Bangkok", "locale" to "th-TH", "extra" to mapOf("tagline" to "Hello")), t.token)
        assertThat(updated.status).isEqualTo(200)
        assertThat(updated["currency"].asText()).isEqualTo("THB")
        assertThat(updated["extra"]["tagline"].asText()).isEqualTo("Hello")
        assertThat(put("/settings", mapOf("timezone" to "Mars/Base"), t.token).status).isEqualTo(422)
        assertThat(put("/settings", mapOf("primary_color" to "red"), t.token).status).isEqualTo(422)
        assertThat(put("/settings", mapOf("currency" to "thb"), t.token).status).isEqualTo(422)
        // Partial update keeps the other fields.
        assertThat(put("/settings", mapOf("display_name" to "Acme 2"), t.token)["currency"].asText()).isEqualTo("THB")
        // The platform admin can edit them too.
        val token = platformToken()
        assertThat(put("/platform/tenants/${t.id}/settings", mapOf("locale" to "en"), token)["display_name"].asText()).isEqualTo("Acme 2")
        // A member without settings:manage can read but not write.
        val (_, member) = newUser(t)
        assertThat(get("/settings", member).status).isEqualTo(200)
        assertThat(put("/settings", mapOf("display_name" to "x"), member).status).isEqualTo(403)
    }
}

class DbScopeTest : IntegrationTest() {
    @org.springframework.beans.factory.annotation.Autowired lateinit var db: com.tenantcore.db.Db

    @Test
    fun `a nested scope restores the outer tenant context`() {
        val a = newTenant()
        val tenantId = java.util.UUID.fromString(a.id)
        db.tenant(tenantId) { j ->
            fun ctx() = j.sql("SELECT current_setting('app.tenant_id', true) || '|' || current_setting('app.is_platform', true)").query(String::class.java).single()
            assertThat(ctx()).isEqualTo("${a.id}|off")
            db.platform { inner -> assertThat(inner.sql("SELECT current_setting('app.is_platform', true)").query(String::class.java).single()).isEqualTo("on") }
            db.anonymous { }
            assertThat(ctx()).isEqualTo("${a.id}|off")
            assertThat(j.sql("SELECT count(*) FROM app_users").query(Long::class.java).single()).isEqualTo(1)
        }
    }
}
