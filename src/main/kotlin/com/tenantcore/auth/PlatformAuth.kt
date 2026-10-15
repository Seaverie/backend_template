package com.tenantcore.auth

import com.tenantcore.audit.Audit
import com.tenantcore.common.ApiException
import com.tenantcore.common.Conflict
import com.tenantcore.common.Invalid
import com.tenantcore.common.Unauthorized
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.AccessClaims
import com.tenantcore.security.Audience
import com.tenantcore.security.Crypto
import com.tenantcore.security.Passwords
import com.tenantcore.security.RateLimiter
import com.tenantcore.security.RequestInfo
import com.tenantcore.security.Tokens
import com.tenantcore.security.Totp
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class PlatformLoginIn(
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String,
    @field:NotBlank @field:Size(max = 256) val password: String,
    @field:Pattern(regexp = "^[0-9]{6}$", message = "must be 6 digits") val otp: String? = null,
)
data class MfaConfirmIn(@field:Pattern(regexp = "^[0-9]{6}$", message = "must be 6 digits") val code: String)

class OtpRequired : ApiException(HttpStatus.UNAUTHORIZED, "otp_required", "Enter the 6-digit code from your authenticator app.")

@Service
class PlatformAuthService(
    private val db: Db,
    private val tokens: Tokens,
    private val passwords: Passwords,
    private val crypto: Crypto,
    private val limiter: RateLimiter,
    private val audit: Audit,
    private val props: TcProperties,
) {
    private fun mfaContext(id: UUID) = "mfa:$id"

    fun login(input: PlatformLoginIn): TokenPair {
        val email = input.email.trim().lowercase()
        val ip = RequestInfo.clientIp() ?: "unknown"
        limiter.hit("plogin:ip:$ip", props.rateLimit.loginPerMin * 3)
        limiter.hit("plogin:acct:$email", props.rateLimit.loginPerMin)

        data class A(val id: UUID, val hash: String, val active: Boolean, val mfa: Boolean, val secret: String?, val lastStep: Long?,
                     val failed: Int, val lockedUntil: OffsetDateTime?)
        val a = db.platform { j ->
            j.sql("""SELECT id, password_hash, is_active, mfa_enabled, mfa_secret_enc, mfa_last_step, failed_logins, locked_until
                     FROM platform_admins WHERE lower(email) = :e""").param("e", email)
                .query { rs, _ -> A(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), rs.getString(5),
                    rs.getObject(6) as Long?, rs.getInt(7), rs.getObject(8, OffsetDateTime::class.java)) }
                .optional().orElse(null)
        }
        val locked = a?.lockedUntil?.isAfter(OffsetDateTime.now(ZoneOffset.UTC)) == true
        if (!passwords.verify(a?.hash, input.password) || a == null || !a.active || locked) {
            if (a != null && !locked) recordFailure(a.id, a.failed)
            throw Unauthorized("Invalid credentials.")
        }
        var step: Long? = null
        if (a.mfa) {
            val otp = input.otp ?: throw OtpRequired()
            step = Totp.verify(crypto.decrypt(a.secret!!, mfaContext(a.id)), otp, a.lastStep)
            if (step == null) { recordFailure(a.id, a.failed); throw Unauthorized("Invalid authenticator code.") }
        }
        return db.platform { j ->
            j.sql("UPDATE platform_admins SET failed_logins = 0, locked_until = NULL, last_login_at = now(), mfa_last_step = coalesce(:s, mfa_last_step) WHERE id = :id")
                .param("s", step).param("id", a.id).update()
            val sid = UUID.randomUUID()
            j.sql("INSERT INTO platform_sessions (id, admin_id, mfa_verified, expires_at) VALUES (:id, :a, :m, now() + make_interval(secs => :ttl))")
                .param("id", sid).param("a", a.id).param("m", a.mfa).param("ttl", props.jwt.platformTtlSeconds.toDouble()).update()
            audit.record(j, null, "platform.login", "platform_admins", a.id, actorType = "platform", actorId = a.id)
            TokenPair(tokens.issue(AccessClaims(a.id, Audience.PLATFORM, sid, null, a.mfa), props.jwt.platformTtlSeconds), null,
                expiresIn = props.jwt.platformTtlSeconds)
        }
    }

    private fun recordFailure(id: UUID, failed: Int) = db.platform { j ->
        val lock = failed + 1 >= MAX_FAILED_LOGINS
        j.sql("""UPDATE platform_admins SET failed_logins = CASE WHEN :lock THEN 0 ELSE failed_logins + 1 END,
                 locked_until = CASE WHEN :lock THEN now() + make_interval(mins => :m) ELSE locked_until END WHERE id = :id""")
            .param("lock", lock).param("m", LOCK_MINUTES.toInt()).param("id", id).update()
        audit.record(j, null, if (lock) "platform.locked" else "platform.login_failed", "platform_admins", id, actorType = "platform", actorId = id)
    }

    fun logout() {
        val p = Caller.platform()
        db.platform { j ->
            j.sql("UPDATE platform_sessions SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL").param("id", p.sessionId).update()
            audit.record(j, null, "platform.logout", "platform_admins", p.adminId)
        }
    }

    /** Generates a secret (stored encrypted, inactive until confirmed). Returned once for the authenticator app. */
    fun mfaSetup(): Map<String, String> {
        val p = Caller.platform()
        val secret = Totp.newSecret()
        db.platform { j ->
            val enabled = j.sql("SELECT mfa_enabled FROM platform_admins WHERE id = :id FOR UPDATE").param("id", p.adminId).query(Boolean::class.java).single()
            if (enabled) throw Conflict("Two-factor authentication is already on. Another platform admin must reset it first.")
            j.sql("UPDATE platform_admins SET mfa_secret_enc = :s, mfa_last_step = NULL WHERE id = :id")
                .param("s", crypto.encrypt(secret, mfaContext(p.adminId))).param("id", p.adminId).update()
        }
        val uri = Totp.uri(secret, p.email, "TenantCore")
        // The QR code is drawn here, so the secret never goes to a third-party QR service.
        return mapOf("secret" to secret, "otpauth_uri" to uri, "qr_svg" to com.tenantcore.integrations.Qr.svg(com.tenantcore.integrations.Qr.matrix(uri, 256, 2, "M")))
    }

    /** Activates MFA with a valid code and returns a fresh, MFA-verified token for this session. */
    fun mfaConfirm(code: String): TokenPair {
        val p = Caller.platform()
        return db.platform { j ->
            val (enabled, enc, last) = j.sql("SELECT mfa_enabled, mfa_secret_enc, mfa_last_step FROM platform_admins WHERE id = :id FOR UPDATE")
                .param("id", p.adminId).query { rs, _ -> Triple(rs.getBoolean(1), rs.getString(2), rs.getObject(3) as Long?) }.single()
            if (enabled || enc == null) throw Conflict("Start two-factor setup first.")
            val step = Totp.verify(crypto.decrypt(enc, mfaContext(p.adminId)), code, last) ?: throw Invalid("That code is not valid. Check the time on your phone and try again.")
            j.sql("UPDATE platform_admins SET mfa_enabled = true, mfa_last_step = :s WHERE id = :id").param("s", step).param("id", p.adminId).update()
            j.sql("UPDATE platform_sessions SET mfa_verified = true WHERE id = :id").param("id", p.sessionId).update()
            audit.record(j, null, "platform.mfa_enabled", "platform_admins", p.adminId)
            TokenPair(tokens.issue(AccessClaims(p.adminId, Audience.PLATFORM, p.sessionId, null, true), props.jwt.platformTtlSeconds), null,
                expiresIn = props.jwt.platformTtlSeconds)
        }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/platform/auth")
class PlatformAuthController(private val auth: PlatformAuthService, private val db: Db, private val props: TcProperties) {
    @PostMapping("/login")
    fun login(@Valid @RequestBody body: PlatformLoginIn) = auth.login(body)

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout() = auth.logout()

    @PostMapping("/mfa/setup")
    fun mfaSetup() = auth.mfaSetup()

    @PostMapping("/mfa/confirm")
    fun mfaConfirm(@Valid @RequestBody body: MfaConfirmIn) = auth.mfaConfirm(body.code)

    @GetMapping("/me")
    fun me(): Map<String, Any?> {
        val p = Caller.platform()
        return db.platform { j ->
            j.sql("SELECT email, full_name, mfa_enabled FROM platform_admins WHERE id = :id").param("id", p.adminId)
                .query { rs, _ ->
                    mapOf("kind" to "platform", "id" to p.adminId, "email" to rs.getString(1), "full_name" to rs.getString(2),
                        "mfa_enabled" to rs.getBoolean(3), "mfa_verified" to p.mfaVerified, "mfa_required" to props.mfaRequired)
                }.single()
        }
    }
}
