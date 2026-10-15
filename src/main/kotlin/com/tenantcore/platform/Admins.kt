package com.tenantcore.platform

import com.tenantcore.audit.Audit
import com.tenantcore.common.Conflict
import com.tenantcore.common.Forbidden
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.Passwords
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

data class PlatformAdminOut(
    val id: UUID, val email: String, val fullName: String?, val isActive: Boolean, val mfaEnabled: Boolean,
    val lastLoginAt: OffsetDateTime?, val createdAt: OffsetDateTime,
)
data class AdminCreateIn(
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String,
    @field:Size(max = 200) val fullName: String? = null,
    @field:NotBlank @field:Size(max = 256) val password: String,
)
data class AdminPatchIn(val isActive: Boolean? = null, @field:Size(max = 200) val fullName: String? = null, val resetMfa: Boolean? = null)

/** Platform admins ("super admins") manage each other: add, deactivate, reset a lost authenticator. */
@RestController
@RequestMapping("\${tc.api-prefix}/platform/admins")
class PlatformAdminController(private val db: Db, private val passwords: Passwords, private val audit: Audit) {

    private fun list(j: org.springframework.jdbc.core.simple.JdbcClient, id: UUID? = null) =
        j.sql("SELECT id, email, full_name, is_active, mfa_enabled, last_login_at, created_at FROM platform_admins ${if (id != null) "WHERE id = :id" else ""} ORDER BY created_at")
            .apply { if (id != null) param("id", id) }
            .query { rs, _ -> PlatformAdminOut(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5),
                rs.getObject(6, OffsetDateTime::class.java), rs.getObject(7, OffsetDateTime::class.java)) }.list()

    @GetMapping
    fun all() = mapOf("items" to db.platform { list(it) })

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: AdminCreateIn): PlatformAdminOut {
        Caller.platform()
        passwords.validatePolicy(body.password)
        val hash = passwords.hash(body.password)
        return db.platform { j ->
            val exists = j.sql("SELECT count(*) FROM platform_admins WHERE lower(email) = :e").param("e", body.email.lowercase()).query(Long::class.java).single()
            if (exists > 0) throw Conflict("A platform admin with this email already exists.")
            val id = j.sql("INSERT INTO platform_admins (email, full_name, password_hash) VALUES (:e, :n, :h) RETURNING id")
                .param("e", body.email.trim().lowercase()).param("n", body.fullName).param("h", hash).query(UUID::class.java).single()
            audit.record(j, null, "platform_admin.create", "platform_admins", id, mapOf("email" to body.email.lowercase()))
            list(j, id).single()
        }
    }

    @PatchMapping("/{id}")
    fun patch(@PathVariable id: UUID, @Valid @RequestBody body: AdminPatchIn): PlatformAdminOut {
        val me = Caller.platform()
        if (id == me.adminId && (body.isActive == false || body.resetMfa == true))
            throw Forbidden("You cannot deactivate yourself or reset your own two-factor authentication.")
        return db.platform { j ->
            if (list(j, id).isEmpty()) throw NotFound("Platform admin")
            body.fullName?.let { j.sql("UPDATE platform_admins SET full_name = :n WHERE id = :id").param("n", it).param("id", id).update() }
            body.isActive?.let {
                j.sql("UPDATE platform_admins SET is_active = :a WHERE id = :id").param("a", it).param("id", id).update()
                if (!it) j.sql("UPDATE platform_sessions SET revoked_at = now() WHERE admin_id = :id AND revoked_at IS NULL").param("id", id).update()
            }
            if (body.resetMfa == true) {
                j.sql("UPDATE platform_admins SET mfa_enabled = false, mfa_secret_enc = NULL, mfa_last_step = NULL WHERE id = :id").param("id", id).update()
                j.sql("UPDATE platform_sessions SET revoked_at = now() WHERE admin_id = :id AND revoked_at IS NULL").param("id", id).update()
            }
            audit.record(j, null, "platform_admin.update", "platform_admins", id,
                mapOf("is_active" to body.isActive, "reset_mfa" to body.resetMfa, "full_name_changed" to (body.fullName != null)))
            list(j, id).single()
        }
    }
}

/**
 * Creates the first platform admin from TC_SEED_ADMIN_EMAIL / TC_SEED_ADMIN_PASSWORD at startup.
 * Skipped when unset; an existing admin is never modified (its password is not overwritten).
 */
@Component
class SeedAdmin(private val props: TcProperties, private val db: Db, private val passwords: Passwords, private val audit: Audit) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val email = props.seed.adminEmail.trim().lowercase()
        val password = props.seed.adminPassword
        if (email.isEmpty() && password.isEmpty()) { log.info("seed: TC_SEED_ADMIN_EMAIL/TC_SEED_ADMIN_PASSWORD not set; nothing to do"); return }
        require(email.isNotEmpty() && password.isNotEmpty()) { "seed: set both TC_SEED_ADMIN_EMAIL and TC_SEED_ADMIN_PASSWORD" }
        require("@" in email) { "seed: TC_SEED_ADMIN_EMAIL is not an email address" }
        require("CHANGE_ME" !in password) { "seed: replace the CHANGE_ME placeholder in TC_SEED_ADMIN_PASSWORD" }
        passwords.validatePolicy(password)
        val hash = passwords.hash(password)
        val created = db.owner { j ->
            j.sql("SELECT pg_advisory_xact_lock(hashtext('tenantcore.seed_admin'))").query().singleRow()
            val exists = j.sql("SELECT count(*) FROM platform_admins WHERE lower(email) = :e").param("e", email).query(Long::class.java).single() > 0
            if (!exists) {
                val id = j.sql("INSERT INTO platform_admins (email, password_hash) VALUES (:e, :h) RETURNING id").param("e", email).param("h", hash)
                    .query(UUID::class.java).single()
                audit.record(j, null, "platform_admin.seed", "platform_admins", id, actorType = "system")
            }
            !exists
        }
        log.info("seed: platform admin {} {}", email, if (created) "created" else "already exists; left unchanged")
    }
}
