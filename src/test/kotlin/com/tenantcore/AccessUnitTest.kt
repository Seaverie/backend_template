package com.tenantcore

import com.tenantcore.common.Forbidden
import com.tenantcore.security.Action
import com.tenantcore.security.Grant
import com.tenantcore.security.Totp
import com.tenantcore.security.UserPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Fast checks that need no database. */
class AccessUnitTest {
    private fun user(admin: Boolean, modules: Set<String>, grants: Map<Pair<String, Action>, Grant> = emptyMap()) =
        UserPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a@b.c", admin, grants, modules)

    @Test
    fun `a disabled module blocks even workspace admins`() {
        val admin = user(true, setOf("catalog"))
        admin.require("products", Action.DELETE)
        val e = assertThrows<Forbidden> { admin.require("orders", Action.READ) }
        assertThat(e.message).contains("Orders module is not enabled")
        assertThrows<Forbidden> { admin.require("data.customers", Action.READ) }
        admin.require("users", Action.MANAGE) // administrative resources need no module
    }

    @Test
    fun `manage implies read, nothing else`() {
        val u = user(false, setOf("orders", "catalog"), mapOf(("orders" to Action.MANAGE) to Grant("all", null, null)))
        assertThat(u.can("orders", Action.READ)).isTrue()
        assertThat(u.can("orders", Action.UPDATE)).isFalse()
        assertThat(u.can("products", Action.READ)).isFalse()
    }

    @Test
    fun `TOTP matches the RFC 6238 test vector`() {
        // RFC 6238 appendix B, SHA-1 secret "12345678901234567890", T = 59 s -> 94287082 (8 digits); 6-digit form 287082.
        val key = "12345678901234567890".toByteArray()
        assertThat(Totp.codeAt(key, 59L / 30)).isEqualTo("287082")
        assertThat(Totp.unbase32(Totp.base32(key))).isEqualTo(key)
        val secret = Totp.base32(key)
        assertThat(Totp.verify(secret, "287082", null, 59)).isEqualTo(1L)
        assertThat(Totp.verify(secret, "287082", 1L, 59)).isNull() // replay of a used step
        assertThat(Totp.verify(secret, "12345", null, 59)).isNull()
    }
}
