package com.tenantcore.auth

import com.tenantcore.audit.Audit
import com.tenantcore.common.Forbidden
import com.tenantcore.common.Unauthorized
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.AccessClaims
import com.tenantcore.security.Audience
import com.tenantcore.security.Passwords
import com.tenantcore.security.RateLimiter
import com.tenantcore.security.RequestInfo
import com.tenantcore.security.Tokens
import com.tenantcore.security.constantTimeEquals
import com.tenantcore.security.randomToken
import com.tenantcore.security.sha256Hex
import com.tenantcore.tenants.SettingsService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class TokenPair(val accessToken: String, val refreshToken: String?, val tokenType: String = "bearer", val expiresIn: Long)

const val MAX_FAILED_LOGINS = 5
const val LOCK_MINUTES = 15L

data class LoginIn(
    @field:NotBlank @field:Size(max = 63) val tenant: String,
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String,
    @field:NotBlank @field:Size(max = 256) val password: String,
)
data class RefreshIn(@field:NotBlank @field:Size(max = 300) val refreshToken: String)
data class ChangePasswordIn(@field:NotBlank val currentPassword: String, @field:NotBlank val newPassword: String)

@Service
class TenantAuthService(
    private val db: Db,
    private val tokens: Tokens,
    private val passwords: Passwords,
    private val limiter: RateLimiter,
    private val audit: Audit,
    private val props: TcProperties,
) {
    private fun now() = OffsetDateTime.now(ZoneOffset.UTC)

    fun login(input: LoginIn): TokenPair {
        val email = input.email.trim().lowercase()
        val ip = RequestInfo.clientIp() ?: "unknown"
        limiter.hit("login:ip:$ip", props.rateLimit.loginPerMin * 3)
        limiter.hit("login:acct:${input.tenant}:$email", props.rateLimit.loginPerMin)

        val tenant = db.anonymous { j ->
            j.sql("SELECT id, status FROM tenants WHERE slug = :s").param("s", input.tenant.trim().lowercase())
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.optional().orElse(null)
        }
        if (tenant == null) { passwords.verify(null, input.password); throw Unauthorized("Invalid credentials.") }
        val (tenantId, status) = tenant

        data class U(val id: UUID, val hash: String, val active: Boolean, val failed: Int, val lockedUntil: OffsetDateTime?, val passwordSet: Boolean)
        val user = db.tenant(tenantId) { j ->
            j.sql("SELECT id, password_hash, is_active, failed_logins, locked_until, password_set FROM app_users WHERE lower(email) = :e")
                .param("e", email)
                .query { rs, _ -> U(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getBoolean(3), rs.getInt(4), rs.getObject(5, OffsetDateTime::class.java), rs.getBoolean(6)) }
                .optional().orElse(null)
        }
        val locked = user?.lockedUntil?.isAfter(now()) == true
        // Invited users have no usable password until they accept the invitation.
        val ok = passwords.verify(user?.hash, input.password) && user != null && user.active && user.passwordSet && !locked
        if (!ok) {
            if (user != null && !locked) recordFailure(tenantId, user.id, user.failed)
            throw Unauthorized("Invalid credentials.")
        }
        if (status != "active") throw Forbidden("This workspace is $status. Contact the platform administrator.")
        return db.tenant(tenantId) { j ->
            j.sql("UPDATE app_users SET failed_logins = 0, locked_until = NULL, last_login_at = now() WHERE id = :id").param("id", user!!.id).update()
            audit.record(j, tenantId, "auth.login", "users", user.id, actorType = "user", actorId = user.id)
            newSession(j, tenantId, user.id)
        }
    }

    private fun recordFailure(tenantId: UUID, userId: UUID, failed: Int) = db.tenant(tenantId) { j ->
        val lock = failed + 1 >= MAX_FAILED_LOGINS
        j.sql("""UPDATE app_users SET failed_logins = CASE WHEN :lock THEN 0 ELSE failed_logins + 1 END,
                 locked_until = CASE WHEN :lock THEN now() + make_interval(mins => :m) ELSE locked_until END WHERE id = :id""")
            .param("lock", lock).param("m", LOCK_MINUTES.toInt()).param("id", userId).update()
        audit.record(j, tenantId, if (lock) "auth.locked" else "auth.login_failed", "users", userId, actorType = "user", actorId = userId)
    }

    /** Refresh token format: "<tenant id>.<session id>.<secret>"; only SHA-256 of the whole token is stored. */
    fun newSession(j: JdbcClient, tenantId: UUID, userId: UUID): TokenPair {
        val sessionId = UUID.randomUUID()
        val refresh = "$tenantId.$sessionId.${randomToken()}"
        j.sql("""INSERT INTO auth_sessions (id, tenant_id, user_id, refresh_hash, expires_at, user_agent, ip)
                 VALUES (:id, :t, :u, :h, :exp, :ua, CAST(:ip AS inet))""")
            .param("id", sessionId).param("t", tenantId).param("u", userId).param("h", sha256Hex(refresh))
            .param("exp", now().plusSeconds(props.jwt.refreshTtlSeconds)).param("ua", RequestInfo.userAgent()).param("ip", RequestInfo.clientIp())
            .update()
        return TokenPair(access(tenantId, userId, sessionId), refresh, expiresIn = props.jwt.accessTtlSeconds)
    }

    private fun access(tenantId: UUID, userId: UUID, sessionId: UUID) =
        tokens.issue(AccessClaims(userId, Audience.TENANT, sessionId, tenantId, mfa = false), props.jwt.accessTtlSeconds)

    /**
     * Rotates the refresh token. Presenting the previous (already rotated) token means it was copied:
     * the whole session is revoked, signing out both the thief and the user.
     */
    fun refresh(token: String): TokenPair {
        val parts = token.split(".")
        if (parts.size != 3) throw Unauthorized("Invalid refresh token.")
        val tenantId = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: throw Unauthorized("Invalid refresh token.")
        val sessionId = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: throw Unauthorized("Invalid refresh token.")
        limiter.hit("refresh:$sessionId", 30)
        val hash = sha256Hex(token)
        val result: Any = db.tenant(tenantId) { j ->
            data class S(val user: UUID, val current: String, val previous: String?, val revoked: Boolean, val expired: Boolean, val active: Boolean, val tenantActive: Boolean)
            val s = j.sql("""SELECT s.user_id, s.refresh_hash, s.previous_hash, s.revoked_at IS NOT NULL, s.expires_at <= now(), u.is_active, t.status = 'active'
                             FROM auth_sessions s JOIN app_users u ON u.tenant_id = s.tenant_id AND u.id = s.user_id
                             JOIN tenants t ON t.id = s.tenant_id WHERE s.id = :id FOR UPDATE OF s""")
                .param("id", sessionId)
                .query { rs, _ -> S(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7)) }
                .optional().orElse(null) ?: return@tenant "invalid"
            if (s.revoked || s.expired || !s.active || !s.tenantActive) return@tenant "invalid"
            if (s.previous != null && constantTimeEquals(s.previous, hash)) {
                j.sql("UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'refresh_reuse' WHERE id = :id").param("id", sessionId).update()
                audit.record(j, tenantId, "auth.refresh_reuse", "users", s.user, actorType = "user", actorId = s.user)
                return@tenant "reused"
            }
            if (!constantTimeEquals(s.current, hash)) return@tenant "invalid"
            val next = "$tenantId.$sessionId.${randomToken()}"
            j.sql("UPDATE auth_sessions SET previous_hash = refresh_hash, refresh_hash = :h, last_used_at = now() WHERE id = :id")
                .param("h", sha256Hex(next)).param("id", sessionId).update()
            TokenPair(access(tenantId, s.user, sessionId), next, expiresIn = props.jwt.accessTtlSeconds)
        }
        return result as? TokenPair ?: throw Unauthorized(if (result == "reused") "This sign-in was ended for security. Sign in again." else "Invalid refresh token.")
    }

    fun logout() {
        val p = Caller.user()
        db.tenant(p.tenantId) { j ->
            j.sql("UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'logout' WHERE id = :id AND revoked_at IS NULL").param("id", p.sessionId).update()
            audit.record(j, p.tenantId, "auth.logout", "users", p.userId)
        }
    }

    fun changePassword(input: ChangePasswordIn) {
        val p = Caller.user()
        passwords.validatePolicy(input.newPassword)
        val hash = db.tenant(p.tenantId) { j -> j.sql("SELECT password_hash FROM app_users WHERE id = :id").param("id", p.userId).query(String::class.java).single() }
        if (!passwords.verify(hash, input.currentPassword)) throw Unauthorized("Your current password is incorrect.")
        val newHash = passwords.hash(input.newPassword)
        db.tenant(p.tenantId) { j ->
            j.sql("UPDATE app_users SET password_hash = :h WHERE id = :id").param("h", newHash).param("id", p.userId).update()
            // Every other sign-in of this user ends; this one stays.
            j.sql("UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'password_changed' WHERE user_id = :u AND id <> :s AND revoked_at IS NULL")
                .param("u", p.userId).param("s", p.sessionId).update()
            audit.record(j, p.tenantId, "auth.password_changed", "users", p.userId)
        }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/auth")
class TenantAuthController(private val auth: TenantAuthService, private val db: Db, private val settings: SettingsService) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody body: LoginIn) = auth.login(body)

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody body: RefreshIn) = auth.refresh(body.refreshToken)

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout() = auth.logout()

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@Valid @RequestBody body: ChangePasswordIn) = auth.changePassword(body)

    /** Who am I, what can I do, which modules are on, and how should the dashboard look. */
    @GetMapping("/me")
    fun me(): Map<String, Any?> {
        val p = Caller.user()
        val (tenant, user) = db.tenant(p.tenantId) { j ->
            val t = j.sql("SELECT slug, name FROM tenants WHERE id = :id").param("id", p.tenantId)
                .query { rs, _ -> mapOf("id" to p.tenantId, "slug" to rs.getString(1), "name" to rs.getString(2)) }.single()
            val u = j.sql("SELECT email, full_name FROM app_users WHERE id = :id").param("id", p.userId)
                .query { rs, _ -> mapOf("id" to p.userId, "email" to rs.getString(1), "full_name" to rs.getString(2)) }.single()
            t to u
        }
        return mapOf(
            "kind" to "user",
            "tenant" to tenant,
            "user" to user,
            "tenant_id" to p.tenantId,
            "user_id" to p.userId,
            "is_tenant_admin" to p.isAdmin,
            "modules" to p.modules.sorted(),
            "permissions" to if (p.isAdmin) listOf("*") else p.grants.keys.map { "${it.first}:${it.second.value}" }.sorted(),
            "settings" to settings.get(p.tenantId),
        )
    }
}
