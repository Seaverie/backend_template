package com.tenantcore

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RbacTest : IntegrationTest() {

    private fun role(t: Tenant, name: String, vararg perms: Map<String, Any?>): String {
        val r = post("/roles", mapOf("name" to name, "permissions" to perms.toList()), t.token)
        check(r.status == 201) { r.toString() }
        return r["id"].asText()
    }
    private fun perm(resource: String, action: String, extra: Map<String, Any?> = emptyMap()) = mapOf("resource" to resource, "action" to action) + extra

    @Test
    fun `roles grant exactly what they list`() {
        val t = newTenant()
        val reader = role(t, "User reader", perm("users", "read"))
        val (_, token) = newUser(t, listOf(reader))
        assertThat(get("/users", token).status).isEqualTo(200)
        assertThat(get("/roles", token).status).isEqualTo(403)
        assertThat(post("/users", mapOf("email" to "x@example.com", "password" to PASSWORD), token).status).isEqualTo(403)
        assertThat(get("/audit-logs", token).status).isEqualTo(403)
        val me = get("/auth/me", token)
        assertThat(me["is_tenant_admin"].asBoolean()).isFalse()
        assertThat(me["permissions"].map { it.asText() }).containsExactly("users:read")
    }

    @Test
    fun `permission rules are validated`() {
        val t = newTenant(listOf("data_tables"))
        post("/data/tables", mapOf("key" to "customers", "name" to "Customers", "fields" to listOf(
            mapOf("key" to "name", "label" to "Name", "type" to "text"), mapOf("key" to "email", "label" to "Email", "type" to "email"))), t.token)
        fun create(vararg p: Map<String, Any?>) = post("/roles", mapOf("name" to "r${System.nanoTime()}", "permissions" to p.toList()), t.token)
        assertThat(create(perm("users", "delete")).status).isEqualTo(422)           // admin resources: read/manage only
        assertThat(create(perm("nonsense", "read")).status).isEqualTo(422)          // unknown resource
        assertThat(create(perm("data.customers", "manage")).status).isEqualTo(422)  // no manage on data
        assertThat(create(perm("orders", "read", mapOf("row_scope" to "own"))).status).isEqualTo(422) // own rows: data only
        assertThat(create(perm("orders", "read"), perm("orders", "read")).status).isEqualTo(422)      // duplicates
        assertThat(create(perm("data.customers", "read", mapOf("readable_fields" to listOf("name", "email")))).status).isEqualTo(201)
        assertThat(create(perm("data.customers", "read", mapOf("readable_fields" to listOf("name", "secret")))).status).isEqualTo(422) // unknown field
        assertThat(create(perm("data.nothing", "read")).status).isEqualTo(422)                                                     // unknown table
        assertThat(post("/roles", mapOf("name" to "bad name!", "permissions" to emptyList<Any>()), t.token).status).isEqualTo(422)
    }

    @Test
    fun `nobody can grant more than they hold`() {
        val t = newTenant()
        val manager = role(t, "Manager", perm("users", "manage"), perm("roles", "manage"), perm("orders", "read"))
        val (_, token) = newUser(t, listOf(manager))
        // Can create a role with a subset of their own permissions...
        assertThat(post("/roles", mapOf("name" to "Viewer", "permissions" to listOf(perm("orders", "read"))), token).status).isEqualTo(201)
        // ...but not one with permissions they lack.
        assertThat(post("/roles", mapOf("name" to "Escalate", "permissions" to listOf(perm("settings", "manage"))), token).status).isEqualTo(422)
        val owner = role(t, "Settings owner", perm("settings", "manage"))
        // ...nor assign a role that holds more than they do, nor create admins.
        assertThat(post("/users", mapOf("email" to "e@example.com", "password" to PASSWORD, "role_ids" to listOf(owner)), token).status).isEqualTo(403)
        assertThat(post("/users", mapOf("email" to "e@example.com", "password" to PASSWORD, "is_tenant_admin" to true), token).status).isEqualTo(403)
        // ...nor edit or delete a role stronger than themselves.
        assertThat(patch("/roles/$owner", mapOf("description" to "x"), token).status).isEqualTo(422)
        assertThat(delete("/roles/$owner", token).status).isEqualTo(422)
        // ...nor change a workspace admin.
        val ownerId = get("/users", t.token)["items"].first { it["email"].asText() == t.ownerEmail }["id"].asText()
        assertThat(patch("/users/$ownerId", mapOf("is_active" to false), token).status).isEqualTo(403)
    }

    @Test
    fun `roles can be edited and deleted, and changes apply on the next request`() {
        val t = newTenant()
        val r = role(t, "Editable", perm("users", "read"))
        val (userId, token) = newUser(t, listOf(r))
        assertThat(get("/roles", token).status).isEqualTo(403)
        val edited = patch("/roles/$r", mapOf("name" to "Edited", "permissions" to listOf(perm("users", "read"), perm("roles", "read"))), t.token)
        assertThat(edited["name"].asText()).isEqualTo("Edited")
        assertThat(get("/roles", token).status).isEqualTo(200)
        assertThat(get("/roles", t.token)["items"].first { it["id"].asText() == r }["user_count"].asInt()).isEqualTo(1)
        assertThat(delete("/roles/$r", t.token).status).isEqualTo(204)
        assertThat(get("/users", token).status).isEqualTo(403)
        assertThat(get("/users", t.token)["items"].first { it["id"].asText() == userId }["role_ids"].size()).isZero()
    }

    @Test
    fun `user management - disable, re-enable, reset password, self-protection`() {
        val t = newTenant()
        val (userId, token) = newUser(t)
        assertThat(patch("/users/$userId", mapOf("is_active" to false), t.token)["is_active"].asBoolean()).isFalse()
        assertThat(get("/auth/me", token).status).isEqualTo(401) // disabled: sessions end at once
        val email = get("/users?q=u-", t.token)["items"][0]["email"].asText()
        assertThat(post("/auth/login", mapOf("tenant" to t.slug, "email" to email, "password" to PASSWORD)).status).isEqualTo(401)
        patch("/users/$userId", mapOf("is_active" to true), t.token)
        assertThat(post("/users/$userId/reset-password", mapOf("password" to "Brand-New-Passw0rd"), t.token).status).isEqualTo(204)
        login(t.slug, email, "Brand-New-Passw0rd")

        val ownerId = get("/users", t.token)["items"].first { it["email"].asText() == t.ownerEmail }["id"].asText()
        assertThat(patch("/users/$ownerId", mapOf("is_active" to false), t.token).status).isEqualTo(403)
        assertThat(patch("/users/$ownerId", mapOf("is_tenant_admin" to false), t.token).status).isEqualTo(403)
        assertThat(post("/users", mapOf("email" to t.ownerEmail.uppercase(), "password" to PASSWORD), t.token).status).isEqualTo(409)
        assertThat(post("/users", mapOf("email" to "weak@example.com", "password" to "aaaaaaaaaaaa"), t.token).status).isEqualTo(422)

        val actions = get("/audit-logs", t.token)["items"].map { it["action"].asText() }
        assertThat(actions).contains("user.create", "user.update", "user.password_reset", "auth.login")
    }

    @Test
    fun `permission catalog shows which modules are on`() {
        val t = newTenant(modules = listOf("catalog"))
        val items = get("/permissions/catalog", t.token)["items"].associateBy { it["resource"].asText() }
        assertThat(items.getValue("products")["module_enabled"].asBoolean()).isTrue()
        assertThat(items.getValue("orders")["module_enabled"].asBoolean()).isFalse()
        assertThat(items.getValue("users")["actions"].map { it.asText() }).containsExactly("read", "manage")
    }
}
