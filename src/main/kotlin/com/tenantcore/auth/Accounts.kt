package com.tenantcore.auth

import com.tenantcore.audit.Audit
import com.tenantcore.common.Conflict
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.email.EmailService
import com.tenantcore.platform.PlatformSettingsStore
import com.tenantcore.security.Action
import com.tenantcore.security.Passwords
import com.tenantcore.security.RateLimiter
import com.tenantcore.security.RequestInfo
import com.tenantcore.security.randomToken
import com.tenantcore.security.sha256Hex
import com.tenantcore.users.UserOut
import com.tenantcore.users.UserService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.util.UUID

data class InviteIn(
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String,
    @field:Size(max = 200) val fullName: String? = null,
    val isTenantAdmin: Boolean = false,
    @field:Size(max = 50) val roleIds: List<UUID> = emptyList(),
)
data class AcceptInviteIn(@field:NotBlank @field:Size(max = 300) val token: String, @field:NotBlank @field:Size(max = 256) val password: String,
                          @field:Size(max = 200) val fullName: String? = null)
data class ForgotIn(@field:NotBlank @field:Size(max = 63) val tenant: String, @field:NotBlank @field:Email @field:Size(max = 320) val email: String)
data class ResetIn(@field:NotBlank @field:Size(max = 300) val token: String, @field:NotBlank @field:Size(max = 256) val password: String)

const val INVITE_HOURS = 72L
const val RESET_MINUTES = 60L

/**
 * Invitations and self-service password resets. Tokens are "<tenant id>.<random>", single-use, short
 * lived, and only their SHA-256 is stored. Emails are queued in the same transaction as the token.
 */
@Service
class AccountService(
    private val db: Db, private val users: UserService, private val passwords: Passwords, private val email: EmailService,
    private val audit: Audit, private val auth: TenantAuthService, private val limiter: RateLimiter,
    private val platformSettings: PlatformSettingsStore, private val props: TcProperties,
) {
    private fun link(path: String, token: String): String {
        val base = platformSettings.general(props).publicUrl.trimEnd('/')
        return "$base/$path?token=${URLEncoder.encode(token, Charsets.UTF_8)}"
    }

    private fun newToken(j: JdbcClient, tenantId: UUID, userId: UUID, purpose: String, minutes: Long): String {
        // Any earlier unused token of the same kind stops working.
        j.sql("UPDATE user_tokens SET used_at = now() WHERE user_id = :u AND purpose = :p AND used_at IS NULL").param("u", userId).param("p", purpose).update()
        val token = "$tenantId.${randomToken(32)}"
        j.sql("INSERT INTO user_tokens (tenant_id, user_id, purpose, token_hash, expires_at) VALUES (:t, :u, :p, :h, now() + make_interval(mins => :m))")
            .param("t", tenantId).param("u", userId).param("p", purpose).param("h", sha256Hex(token)).param("m", minutes.toInt()).update()
        return token
    }

    private fun inviterName(j: JdbcClient, userId: UUID) =
        j.sql("SELECT coalesce(full_name, email) FROM app_users WHERE id = :id").param("id", userId).query(String::class.java).single()

    fun invite(input: InviteIn): UserOut {
        val p = Caller.user()
        p.require("users", Action.MANAGE)
        p.requireModule("email")
        if (input.isTenantAdmin) p.requireAdmin()
        val unusable = passwords.hash(randomToken(32))
        return db.tenant(p.tenantId) { j ->
            val addr = input.email.trim().lowercase()
            if (j.sql("SELECT count(*) FROM app_users WHERE lower(email) = :e").param("e", addr).query(Long::class.java).single() > 0)
                throw Conflict("A user with this email already exists.")
            users.checkAssignable(j, p, input.roleIds)
            val id = j.sql("""INSERT INTO app_users (tenant_id, email, password_hash, full_name, is_tenant_admin, password_set, invited_at)
                              VALUES (:t, :e, :h, :n, :a, false, now()) RETURNING id""")
                .param("t", p.tenantId).param("e", addr).param("h", unusable).param("n", input.fullName).param("a", input.isTenantAdmin)
                .query(UUID::class.java).single()
            users.setRoles(j, p.tenantId, id, input.roleIds)
            val token = newToken(j, p.tenantId, id, "invite", INVITE_HOURS * 60)
            email.queue(j, p.tenantId, addr, "user_invite", mapOf("user_name" to (input.fullName ?: addr), "inviter_name" to inviterName(j, p.userId),
                "link" to link("accept-invite", token), "expires_hours" to INVITE_HOURS.toString()))
            audit.record(j, p.tenantId, "user.invite", "users", id, mapOf("email" to addr, "is_tenant_admin" to input.isTenantAdmin, "role_ids" to input.roleIds))
            users.list(j, id).single()
        }
    }

    fun resendInvite(userId: UUID) {
        val p = Caller.user()
        p.require("users", Action.MANAGE)
        p.requireModule("email")
        db.tenant(p.tenantId) { j ->
            val u = users.list(j, userId).singleOrNull() ?: throw NotFound("User")
            if (!u.invitationPending) throw Conflict("This user has already accepted their invitation.")
            if (u.isTenantAdmin && !p.isAdmin) throw com.tenantcore.common.Forbidden("Only workspace admins can change workspace admins.")
            val token = newToken(j, p.tenantId, userId, "invite", INVITE_HOURS * 60)
            email.queue(j, p.tenantId, u.email, "user_invite", mapOf("user_name" to (u.fullName ?: u.email), "inviter_name" to inviterName(j, p.userId),
                "link" to link("accept-invite", token), "expires_hours" to INVITE_HOURS.toString()))
            audit.record(j, p.tenantId, "user.invite_resent", "users", userId)
        }
    }

    private data class Claimed(val tenantId: UUID, val userId: UUID, val email: String, val name: String?)

    /** Validates and consumes a token inside [j]. Returns null when it is unknown, used, expired or the account is unusable. */
    private fun claim(j: JdbcClient, tenantId: UUID, token: String, purpose: String): Claimed? =
        j.sql("""SELECT k.id, u.id, u.email, u.full_name FROM user_tokens k JOIN app_users u ON u.tenant_id = k.tenant_id AND u.id = k.user_id
                 JOIN tenants t ON t.id = k.tenant_id
                 WHERE k.token_hash = :h AND k.purpose = :p AND k.used_at IS NULL AND k.expires_at > now() AND u.is_active AND t.status = 'active'
                 FOR UPDATE OF k""")
            .param("h", sha256Hex(token)).param("p", purpose)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to Claimed(tenantId, rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4)) }
            .optional().orElse(null)
            ?.let { (tokenId, c) -> j.sql("UPDATE user_tokens SET used_at = now() WHERE id = :id").param("id", tokenId).update(); c }

    private fun tenantOf(token: String): UUID {
        val id = token.substringBefore('.', "")
        return runCatching { UUID.fromString(id) }.getOrNull() ?: throw Invalid("This link is not valid.")
    }

    /** Sets the password chosen by the invited user and signs them in. */
    fun acceptInvite(input: AcceptInviteIn): TokenPair {
        limiter.hit("accept:ip:${RequestInfo.clientIp() ?: "unknown"}", 30)
        passwords.validatePolicy(input.password)
        val hash = passwords.hash(input.password)
        val tenantId = tenantOf(input.token)
        return db.tenant(tenantId) { j ->
            val c = claim(j, tenantId, input.token, "invite") ?: throw Invalid("This invitation link is invalid or has expired. Ask for a new one.")
            j.sql("UPDATE app_users SET password_hash = :h, password_set = true, full_name = coalesce(:n, full_name), failed_logins = 0, locked_until = NULL WHERE id = :id")
                .param("h", hash).param("n", input.fullName).param("id", c.userId).update()
            audit.record(j, tenantId, "user.invite_accepted", "users", c.userId, actorType = "user", actorId = c.userId)
            auth.newSession(j, tenantId, c.userId)
        }
    }

    /**
     * Always answers the same way, whether or not the account exists, so it cannot be used to discover
     * who has an account. A reset email is only queued when the account can actually sign in.
     */
    fun forgot(input: ForgotIn) {
        val addr = input.email.trim().lowercase()
        val ip = RequestInfo.clientIp() ?: "unknown"
        limiter.hit("forgot:ip:$ip", props.rateLimit.loginPerMin * 3)
        limiter.hit("forgot:acct:${input.tenant}:$addr", 3, 900)
        val tenantId = db.anonymous { j ->
            j.sql("SELECT id FROM tenants WHERE slug = :s AND status = 'active'").param("s", input.tenant.trim().lowercase())
                .query(UUID::class.java).optional().orElse(null)
        } ?: return
        db.tenant(tenantId) { j ->
            val emailOn = j.sql("SELECT count(*) FROM tenant_modules WHERE module = 'email' AND enabled").query(Long::class.java).single() > 0
            if (!emailOn) return@tenant
            val user = j.sql("SELECT id, full_name FROM app_users WHERE lower(email) = :e AND is_active AND password_set").param("e", addr)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.optional().orElse(null) ?: return@tenant
            val token = newToken(j, tenantId, user.first, "reset", RESET_MINUTES)
            email.queue(j, tenantId, addr, "password_reset", mapOf("user_name" to (user.second ?: addr),
                "link" to link("reset-password", token), "expires_minutes" to RESET_MINUTES.toString()))
            audit.record(j, tenantId, "auth.reset_requested", "users", user.first, actorType = "user", actorId = user.first)
        }
    }

    /** Sets a new password from a reset link, clears any lock and signs the user out everywhere. */
    fun reset(input: ResetIn) {
        limiter.hit("reset:ip:${RequestInfo.clientIp() ?: "unknown"}", 30)
        passwords.validatePolicy(input.password)
        val hash = passwords.hash(input.password)
        val tenantId = tenantOf(input.token)
        db.tenant(tenantId) { j ->
            val c = claim(j, tenantId, input.token, "reset") ?: throw Invalid("This reset link is invalid or has expired. Request a new one.")
            j.sql("UPDATE app_users SET password_hash = :h, failed_logins = 0, locked_until = NULL WHERE id = :id").param("h", hash).param("id", c.userId).update()
            j.sql("UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'password_reset' WHERE user_id = :u AND revoked_at IS NULL").param("u", c.userId).update()
            email.queue(j, tenantId, c.email, "password_changed", mapOf("user_name" to (c.name ?: c.email)))
            audit.record(j, tenantId, "auth.password_reset", "users", c.userId, actorType = "user", actorId = c.userId)
        }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}")
class AccountController(private val accounts: AccountService) {

    @PostMapping("/users/invite")
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(@Valid @RequestBody body: InviteIn) = accounts.invite(body)

    @PostMapping("/users/{id}/resend-invite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resend(@PathVariable id: UUID) = accounts.resendInvite(id)

    @PostMapping("/auth/accept-invite")
    fun accept(@Valid @RequestBody body: AcceptInviteIn) = accounts.acceptInvite(body)

    @PostMapping("/auth/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun forgot(@Valid @RequestBody body: ForgotIn): Map<String, String> {
        accounts.forgot(body)
        return mapOf("status" to "If an account exists for that email, a reset link is on its way.")
    }

    @PostMapping("/auth/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reset(@Valid @RequestBody body: ResetIn) = accounts.reset(body)
}
