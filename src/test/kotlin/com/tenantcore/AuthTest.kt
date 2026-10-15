package com.tenantcore

import com.tenantcore.security.Totp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.util.UUID

class AuthTest : IntegrationTest() {

    @Test
    fun `health and readiness`() {
        assertThat(call(HttpMethod.GET, "/health").status).isEqualTo(200)
        val ready = call(HttpMethod.GET, "/ready")
        assertThat(ready.status).isEqualTo(200)
        assertThat(ready["database"].asBoolean()).isTrue()
        assertThat(ready["redis"].asBoolean()).isTrue()
    }

    @Test
    fun `seeded super admin can sign in and its password is only stored hashed`() {
        val r = post("/platform/auth/login", mapOf("email" to ADMIN_EMAIL.uppercase(), "password" to ADMIN_PASSWORD))
        assertThat(r.status).isEqualTo(200)
        assertThat(r["refresh_token"].isNull).isTrue()
        val me = get("/platform/auth/me", r["access_token"].asText())
        assertThat(me["email"].asText()).isEqualTo(ADMIN_EMAIL)
        superuser().use { c ->
            val hash = c.createStatement().executeQuery("SELECT password_hash FROM platform_admins WHERE email = '$ADMIN_EMAIL'")
                .apply { next() }.getString(1)
            assertThat(hash).startsWith("\$argon2id\$").doesNotContain(ADMIN_PASSWORD)
        }
    }

    @Test
    fun `wrong credentials get one generic answer and repeated failures lock the account`() {
        val t = newTenant()
        val unknownTenant = post("/auth/login", mapOf("tenant" to "no-such-tenant", "email" to t.ownerEmail, "password" to PASSWORD))
        val unknownUser = post("/auth/login", mapOf("tenant" to t.slug, "email" to "nobody@example.com", "password" to PASSWORD))
        val wrongPassword = post("/auth/login", mapOf("tenant" to t.slug, "email" to t.ownerEmail, "password" to "Wrong-Passw0rd-123"))
        listOf(unknownTenant, unknownUser, wrongPassword).forEach {
            assertThat(it.status).isEqualTo(401)
            assertThat(it["error"]["message"].asText()).isEqualTo("Invalid credentials.")
        }
        repeat(4) { post("/auth/login", mapOf("tenant" to t.slug, "email" to t.ownerEmail, "password" to "Wrong-Passw0rd-123")) }
        // Locked: even the right password is refused until the lock expires or an admin unlocks it.
        assertThat(post("/auth/login", mapOf("tenant" to t.slug, "email" to t.ownerEmail, "password" to PASSWORD)).status).isEqualTo(401)
        val users = get("/users", t.token)["items"]
        assertThat(users[0]["locked_until"].isNull).isFalse()
        // The owner's existing session still works, and an admin can lift the lock.
        val unlocked = patch("/users/${users[0]["id"].asText()}", mapOf("unlock" to true), t.token)
        assertThat(unlocked.status).isEqualTo(200)
        assertThat(post("/auth/login", mapOf("tenant" to t.slug, "email" to t.ownerEmail, "password" to PASSWORD)).status).isEqualTo(200)
    }

    @Test
    fun `refresh tokens rotate and a reused one ends the session`() {
        val t = newTenant()
        val first = post("/auth/refresh", mapOf("refresh_token" to t.refresh))
        assertThat(first.status).isEqualTo(200)
        val second = first["refresh_token"].asText()
        assertThat(second).isNotEqualTo(t.refresh)
        assertThat(get("/auth/me", first["access_token"].asText()).status).isEqualTo(200)

        // The old token is presented again: treated as stolen, so the whole session is revoked.
        val reuse = post("/auth/refresh", mapOf("refresh_token" to t.refresh))
        assertThat(reuse.status).isEqualTo(401)
        assertThat(post("/auth/refresh", mapOf("refresh_token" to second)).status).isEqualTo(401)
        assertThat(get("/auth/me", first["access_token"].asText()).status).isEqualTo(401)

        assertThat(post("/auth/refresh", mapOf("refresh_token" to "${t.id}.${UUID.randomUUID()}.forged")).status).isEqualTo(401)
        assertThat(post("/auth/refresh", mapOf("refresh_token" to "garbage")).status).isEqualTo(401)
    }

    @Test
    fun `logout and password change end sessions`() {
        val t = newTenant()
        val other = login(t.slug, t.ownerEmail)["access_token"].asText()
        assertThat(post("/auth/change-password", mapOf("current_password" to "nope-nope-nope", "new_password" to "Another-Passw0rd-1"), t.token).status).isEqualTo(401)
        assertThat(post("/auth/change-password", mapOf("current_password" to PASSWORD, "new_password" to "short"), t.token).status).isEqualTo(422)
        assertThat(post("/auth/change-password", mapOf("current_password" to PASSWORD, "new_password" to "Another-Passw0rd-1"), t.token).status).isEqualTo(204)
        assertThat(get("/auth/me", other).status).isEqualTo(401) // the other sign-in ended
        assertThat(get("/auth/me", t.token).status).isEqualTo(200) // this one stays
        assertThat(post("/auth/logout", null, t.token).status).isEqualTo(204)
        assertThat(get("/auth/me", t.token).status).isEqualTo(401)
        login(t.slug, t.ownerEmail, "Another-Passw0rd-1")
    }

    @Test
    fun `tokens are checked for signature, audience and key id`() {
        val t = newTenant()
        assertThat(get("/auth/me", "not-a-jwt").status).isEqualTo(401)
        // A platform token is not accepted on tenant routes, and vice versa.
        assertThat(get("/auth/me", platformToken()).status).isEqualTo(401)
        assertThat(get("/platform/tenants", t.token).status).isEqualTo(401)
        val parts = t.token.split(".")
        val tampered = parts[0] + "." + parts[1].dropLast(2) + "xx." + parts[2]
        assertThat(get("/auth/me", tampered).status).isEqualTo(401)
        assertThat(get("/auth/me").status).isEqualTo(401)
        assertThat(get("/auth/me")["error"]["code"].asText()).isEqualTo("unauthorized")
    }

    @Test
    fun `platform MFA enrolment and sign-in with a code`() {
        val token = platformToken()
        val email = "mfa-${UUID.randomUUID().toString().take(6)}@example.com"
        assertThat(post("/platform/admins", mapOf("email" to email, "password" to PASSWORD), token).status).isEqualTo(201)
        val mine = post("/platform/auth/login", mapOf("email" to email, "password" to PASSWORD))["access_token"].asText()

        val setup = post("/platform/auth/mfa/setup", null, mine)
        assertThat(setup.status).isEqualTo(200)
        val secret = setup["secret"].asText()
        assertThat(setup["otpauth_uri"].asText()).startsWith("otpauth://totp/").contains(secret)
        assertThat(post("/platform/auth/mfa/confirm", mapOf("code" to "000000"), mine).status).isIn(422)

        val now = System.currentTimeMillis() / 1000
        val code = Totp.codeAt(Totp.unbase32(secret), now / 30)
        val confirmed = post("/platform/auth/mfa/confirm", mapOf("code" to code), mine)
        assertThat(confirmed.status).isEqualTo(200)
        assertThat(get("/platform/auth/me", confirmed["access_token"].asText())["mfa_enabled"].asBoolean()).isTrue()

        // From now on the password alone is not enough, and a used code cannot be replayed.
        val noCode = post("/platform/auth/login", mapOf("email" to email, "password" to PASSWORD))
        assertThat(noCode.status).isEqualTo(401)
        assertThat(noCode["error"]["code"].asText()).isEqualTo("otp_required")
        assertThat(post("/platform/auth/login", mapOf("email" to email, "password" to PASSWORD, "otp" to code)).status).isEqualTo(401)
        val next = Totp.codeAt(Totp.unbase32(secret), now / 30 + 1)
        assertThat(post("/platform/auth/login", mapOf("email" to email, "password" to PASSWORD, "otp" to next)).status).isEqualTo(200)

        // Another admin can reset a lost authenticator.
        val id = get("/platform/admins", token)["items"].first { it["email"].asText() == email }["id"].asText()
        assertThat(patch("/platform/admins/$id", mapOf("reset_mfa" to true), token)["mfa_enabled"].asBoolean()).isFalse()
        assertThat(post("/platform/auth/login", mapOf("email" to email, "password" to PASSWORD)).status).isEqualTo(200)
    }

    @Test
    fun `MFA secret is stored encrypted`() {
        val token = platformToken()
        val email = "enc-${UUID.randomUUID().toString().take(6)}@example.com"
        post("/platform/admins", mapOf("email" to email, "password" to PASSWORD), token)
        val mine = post("/platform/auth/login", mapOf("email" to email, "password" to PASSWORD))["access_token"].asText()
        val secret = post("/platform/auth/mfa/setup", null, mine)["secret"].asText()
        superuser().use { c ->
            val stored = c.createStatement().executeQuery("SELECT mfa_secret_enc FROM platform_admins WHERE email = '$email'").apply { next() }.getString(1)
            assertThat(stored).startsWith("v1:").doesNotContain(secret)
        }
    }

    @Test
    fun `validation errors list the offending fields`() {
        val r = post("/auth/login", mapOf("tenant" to "", "email" to "not-an-email", "password" to "x"))
        assertThat(r.status).isEqualTo(422)
        assertThat(r["error"]["code"].asText()).isEqualTo("validation_failed")
        val fields = r["error"]["details"].map { it["loc"][1].asText() }
        assertThat(fields).contains("tenant", "email")
        val unknown = post("/auth/login", mapOf("tenant" to "a", "email" to "a@b.co", "password" to "x", "extra" to 1))
        assertThat(unknown.status).isEqualTo(422)
        assertThat(r.headers.firstValue("X-Request-ID")).isPresent
    }

    @org.springframework.beans.factory.annotation.Autowired lateinit var limiter: com.tenantcore.security.RateLimiter

    @Test
    fun `the rate limiter answers 429 once a key exceeds its limit`() {
        val key = "test:" + UUID.randomUUID()
        repeat(5) { limiter.hit(key, 5) }
        val e = org.junit.jupiter.api.assertThrows<com.tenantcore.common.RateLimited> { limiter.hit(key, 5) }
        assertThat(e.status.value()).isEqualTo(429)
        assertThat(e.retryAfterSeconds).isBetween(1L, 60L)
    }
}
