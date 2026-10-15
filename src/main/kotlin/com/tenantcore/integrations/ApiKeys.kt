package com.tenantcore.integrations

import com.tenantcore.audit.Audit
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.UserPrincipal
import com.tenantcore.security.randomToken
import com.tenantcore.security.sha256Hex
import com.tenantcore.users.UserService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

data class ApiKeyIn(
    @field:NotBlank @field:Size(max = 100) val name: String,
    /** The role whose permissions the key gets. Without one the key can do nothing. */
    val roleId: UUID?,
    /** Optional lifetime; the key stops working afterwards. */
    @field:Min(1) @field:Max(3650) val expiresInDays: Int? = null,
)

data class ApiKeyOut(
    val id: UUID, val name: String, val hint: String, val roleId: UUID?, val roleName: String?, val createdBy: UUID?, val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime?, val lastUsedAt: OffsetDateTime?, val revokedAt: OffsetDateTime?,
    /** The key itself: returned once, when it is created. Store it like a password. */
    val key: String? = null,
)

/**
 * API keys let other systems (an accounting tool, a website backend) call the API. A key carries a
 * role's permissions; the creator can only hand out a role they could assign to a user, so a key can
 * never do more than its creator.
 */
@Service
class ApiKeyService(private val db: Db, private val audit: Audit, private val users: UserService) {

    private fun list(j: JdbcClient, id: UUID? = null) =
        j.sql("""SELECT k.*, r.name AS role_name FROM api_keys k LEFT JOIN roles r ON r.id = k.role_id
                 ${if (id != null) "WHERE k.id = :id" else ""} ORDER BY k.created_at DESC""")
            .apply { if (id != null) param("id", id) }
            .query { rs, _ -> ApiKeyOut(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("hint"), rs.getObject("role_id", UUID::class.java),
                rs.getString("role_name"), rs.getObject("created_by", UUID::class.java), rs.getObject("created_at", OffsetDateTime::class.java),
                rs.getObject("expires_at", OffsetDateTime::class.java), rs.getObject("last_used_at", OffsetDateTime::class.java),
                rs.getObject("revoked_at", OffsetDateTime::class.java)) }.list()

    fun list(p: UserPrincipal) = p.require("api_keys", Action.READ).let { db.tenant(p.tenantId) { list(it) } }

    fun create(p: UserPrincipal, input: ApiKeyIn): ApiKeyOut {
        p.require("api_keys", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            input.roleId?.let { users.checkAssignable(j, p, listOf(it)) }
            if (j.sql("SELECT count(*) FROM api_keys WHERE revoked_at IS NULL").query(Long::class.java).single() >= 50)
                throw Invalid("A workspace can have at most 50 active API keys.")
            val key = "tck_${p.tenantId.toString().replace("-", "")}_${randomToken(32)}"
            val id = j.sql("""INSERT INTO api_keys (tenant_id, name, hint, key_hash, role_id, created_by, expires_at)
                              VALUES (:t, :n, :h, :k, :r, :u, CASE WHEN CAST(:d AS integer) IS NULL THEN NULL ELSE now() + make_interval(days => CAST(:d AS integer)) END)
                              RETURNING id""")
                .param("t", p.tenantId).param("n", input.name.trim()).param("h", "…" + key.takeLast(4)).param("k", sha256Hex(key))
                .param("r", input.roleId).param("u", p.userId).param("d", input.expiresInDays).query(UUID::class.java).single()
            audit.record(j, p.tenantId, "api_key.create", "api_keys", id, mapOf("name" to input.name, "role_id" to input.roleId))
            list(j, id).single().copy(key = key)
        }
    }

    fun revoke(p: UserPrincipal, id: UUID) {
        p.require("api_keys", Action.MANAGE)
        db.tenant(p.tenantId) { j ->
            if (j.sql("UPDATE api_keys SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL").param("id", id).update() == 0) throw NotFound("API key")
            audit.record(j, p.tenantId, "api_key.revoke", "api_keys", id)
        }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/api-keys")
class ApiKeyController(private val keys: ApiKeyService) {
    @GetMapping fun list() = mapOf("items" to keys.list(Caller.user()))
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@Valid @RequestBody body: ApiKeyIn) = keys.create(Caller.user(), body)
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun revoke(@PathVariable id: UUID) = keys.revoke(Caller.user(), id)
}
