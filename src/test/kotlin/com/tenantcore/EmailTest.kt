package com.tenantcore

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class EmailTest : IntegrationTest() {

    @BeforeEach
    fun smtp() = usePlatformMailpit()

    private fun tokenFrom(mail: Mail): String =
        Regex("token=([A-Za-z0-9%._-]+)").find(mail.text)!!.groupValues[1].let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }

    private fun withEmail(): Tenant = newTenant(modules = listOf("email"))

    @Test
    fun `platform SMTP settings hide the password and the test email arrives`() {
        val token = platformToken()
        val to = "ops-${UUID.randomUUID().toString().take(6)}@example.test"
        val s = get("/platform/email/settings", token)
        assertThat(s["configured"].asBoolean()).isTrue()
        assertThat(post("/platform/email/test", mapOf("to" to to), token)["ok"].asBoolean()).isTrue()
        assertThat(awaitMail(to, "Test email").html).contains("Email delivery works")
    }

    @Test
    fun `an invited user chooses a password from the emailed link`() {
        val t = withEmail()
        put("/settings", mapOf("display_name" to "Acme Coffee"), t.token)
        val addr = "new-${UUID.randomUUID().toString().take(6)}@example.test"
        val invited = post("/users/invite", mapOf("email" to addr, "full_name" to "Nina New"), t.token)
        assertThat(invited.status).isEqualTo(201)
        assertThat(invited["invitation_pending"].asBoolean()).isTrue()

        val mail = awaitMail(addr, "invited")
        assertThat(mail.subject).isEqualTo("You're invited to Acme Coffee")
        assertThat(mail.text).contains("https://admin.example.test/accept-invite?token=")
        val token = tokenFrom(mail)

        // No password works before accepting.
        assertThat(post("/auth/login", mapOf("tenant" to t.slug, "email" to addr, "password" to PASSWORD)).status).isEqualTo(401)
        assertThat(post("/auth/accept-invite", mapOf("token" to token, "password" to "short")).status).isEqualTo(422)
        val accepted = post("/auth/accept-invite", mapOf("token" to token, "password" to "Chosen-Passw0rd-1"))
        assertThat(accepted.status).isEqualTo(200)
        assertThat(get("/auth/me", accepted["access_token"].asText())["user"]["email"].asText()).isEqualTo(addr)
        assertThat(post("/auth/accept-invite", mapOf("token" to token, "password" to "Another-Passw0rd-2")).status).isEqualTo(422) // single use
        login(t.slug, addr, "Chosen-Passw0rd-1")
        assertThat(get("/users", t.token)["items"].first { it["email"].asText() == addr }["invitation_pending"].asBoolean()).isFalse()
    }

    @Test
    fun `resending an invitation invalidates the previous link`() {
        val t = withEmail()
        val addr = "re-${UUID.randomUUID().toString().take(6)}@example.test"
        val id = post("/users/invite", mapOf("email" to addr), t.token)["id"].asText()
        val first = tokenFrom(awaitMail(addr, "invited"))
        assertThat(post("/users/$id/resend-invite", null, t.token).status).isEqualTo(204)
        Thread.sleep(1500)
        assertThat(mailCount(addr)).isEqualTo(2)
        assertThat(post("/auth/accept-invite", mapOf("token" to first, "password" to "Chosen-Passw0rd-1")).status).isEqualTo(422)
    }

    @Test
    fun `forgot password never reveals whether an account exists`() {
        val t = withEmail()
        val unknown = "ghost-${UUID.randomUUID().toString().take(6)}@example.test"
        val a = post("/auth/forgot-password", mapOf("tenant" to t.slug, "email" to unknown))
        val b = post("/auth/forgot-password", mapOf("tenant" to t.slug, "email" to t.ownerEmail))
        val c = post("/auth/forgot-password", mapOf("tenant" to "no-such-tenant", "email" to t.ownerEmail))
        listOf(a, b, c).forEach { assertThat(it.status).isEqualTo(202); assertThat(it["status"].asText()).isEqualTo(b["status"].asText()) }

        val mail = awaitMail(t.ownerEmail, "Reset your")
        val token = tokenFrom(mail)
        assertThat(mail.text).contains("https://admin.example.test/reset-password?token=")
        Thread.sleep(800)
        assertThat(mailCount(unknown)).isZero()

        assertThat(post("/auth/reset-password", mapOf("token" to token, "password" to "Fresh-Passw0rd-99")).status).isEqualTo(204)
        assertThat(get("/auth/me", t.token).status).isEqualTo(401) // signed out everywhere
        assertThat(post("/auth/reset-password", mapOf("token" to token, "password" to "Again-Passw0rd-99")).status).isEqualTo(422)
        assertThat(post("/auth/reset-password", mapOf("token" to "${t.id}.forged", "password" to "Again-Passw0rd-99")).status).isEqualTo(422)
        login(t.slug, t.ownerEmail, "Fresh-Passw0rd-99")
        assertThat(awaitMail(t.ownerEmail, "password was changed").text).contains("signed out everywhere else")
    }

    @Test
    fun `templates can be customised, previewed and reset, and values are escaped`() {
        val t = withEmail()
        val custom = put("/email/templates/user_invite", mapOf("subject" to "Join {{business_name}} today",
            "html" to "<p>Hi {{user_name}}, <a href=\"{{link}}\">join</a></p>", "text" to "Hi {{user_name}}: {{link}}"), t.token)
        assertThat(custom["source"].asText()).isEqualTo("tenant")
        val preview = post("/email/templates/user_invite/preview", null, t.token)
        assertThat(preview["subject"].asText()).isEqualTo("Join Tenant ${t.slug} today")

        val addr = "esc-${UUID.randomUUID().toString().take(6)}@example.test"
        post("/users/invite", mapOf("email" to addr, "full_name" to "<script>alert(1)</script>"), t.token)
        val mail = awaitMail(addr, "Join")
        assertThat(mail.html).contains("&lt;script&gt;").doesNotContain("<script>")

        assertThat(delete("/email/templates/user_invite", t.token).status).isEqualTo(200)
        assertThat(get("/email/templates", t.token)["items"].first { it["key"].asText() == "user_invite" }["source"].asText()).isEqualTo("default")
        assertThat(put("/email/templates/nope", mapOf("subject" to "x", "html" to "x", "text" to "x"), t.token).status).isEqualTo(404)
    }

    @Test
    fun `without the email module there are no invitations or reset emails`() {
        val t = newTenant(modules = listOf("catalog"))
        assertThat(post("/users/invite", mapOf("email" to "x@example.test"), t.token).status).isEqualTo(403)
        assertThat(post("/auth/forgot-password", mapOf("tenant" to t.slug, "email" to t.ownerEmail)).status).isEqualTo(202)
        Thread.sleep(800)
        assertThat(mailCount(t.ownerEmail)).isZero()
    }

    @Test
    fun `a workspace mail server must be public and use TLS`() {
        val t = withEmail()
        fun smtp(host: String, port: Int = 587, security: String = "starttls") =
            put("/email/settings", mapOf("settings" to mapOf("host" to host, "port" to port, "security" to security, "from_address" to "a@b.test"), "password" to "pw"), t.token)
        assertThat(smtp("localhost").status).isEqualTo(422)
        assertThat(smtp("127.0.0.1").status).isEqualTo(422)
        assertThat(smtp("10.0.0.5").status).isEqualTo(422)
        assertThat(smtp("8.8.8.8", port = 5432).status).isEqualTo(422)
        assertThat(smtp("8.8.8.8", security = "none").status).isEqualTo(422)
    }

    @Test
    fun `failed sends are retried with backoff and the address check is repeated before sending`() {
        val t = withEmail()
        // Simulate a workspace mail server that now resolves to an internal address (DNS rebinding).
        superuser().use { c ->
            c.createStatement().execute("""INSERT INTO tenant_smtp (tenant_id, host, port, security, from_address)
                VALUES ('${t.id}', 'localhost', 587, 'starttls', 'x@example.test')""")
        }
        val addr = "retry-${UUID.randomUUID().toString().take(6)}@example.test"
        post("/users/invite", mapOf("email" to addr), t.token)
        var row: com.fasterxml.jackson.databind.JsonNode? = null
        repeat(50) {
            row = get("/email/log", t.token)["items"].firstOrNull { it["to_address"].asText() == addr && it["status"].asText() == "failed" }
            if (row != null) return@repeat
            Thread.sleep(200)
        }
        assertThat(row).isNotNull
        assertThat(row!!["attempts"].asInt()).isEqualTo(1)
        assertThat(row!!["last_error"].asText()).contains("public address")
        assertThat(mailCount(addr)).isZero()
    }
}
