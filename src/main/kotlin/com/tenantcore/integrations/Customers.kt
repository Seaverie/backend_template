package com.tenantcore.integrations

import com.tenantcore.audit.Audit
import com.tenantcore.auth.LOCK_MINUTES
import com.tenantcore.auth.MAX_FAILED_LOGINS
import com.tenantcore.auth.TokenPair
import com.tenantcore.commerce.OrderService
import com.tenantcore.commerce.StorefrontService
import com.tenantcore.common.ApiException
import com.tenantcore.common.Conflict
import com.tenantcore.common.Forbidden
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.common.Unauthorized
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.email.EmailService
import com.tenantcore.platform.PlatformSettingsStore
import com.tenantcore.security.AccessClaims
import com.tenantcore.security.Action
import com.tenantcore.security.Audience
import com.tenantcore.security.Passwords
import com.tenantcore.security.RateLimiter
import com.tenantcore.security.RequestInfo
import com.tenantcore.security.Tokens
import com.tenantcore.security.UserPrincipal
import com.tenantcore.security.constantTimeEquals
import com.tenantcore.security.randomToken
import com.tenantcore.security.sha256Hex
import com.tenantcore.tenants.ModuleService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

const val CUSTOMER_MIN_PASSWORD = 8

data class CustomerPrincipal(val tenantId: UUID, val customerId: UUID, val sessionId: UUID)

/** Resolves shops and customer bearer tokens on the public routes (which the staff auth filter skips). */
@Component
class CustomerAuth(private val db: Db, private val tokens: Tokens) {

    /** The tenant behind a shop slug, if it is active and has customer accounts switched on. */
    fun shop(slug: String): UUID {
        val (id, status) = db.anonymous { j ->
            j.sql("SELECT id, status FROM tenants WHERE slug = :s").param("s", slug).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }
                .optional().orElse(null)
        } ?: throw NotFound("Shop")
        val on = db.tenant(id) { j -> j.sql("SELECT count(*) FROM tenant_modules WHERE module = 'customer_accounts' AND enabled").query(Long::class.java).single() > 0 }
        if (status != "active" || !on) throw NotFound("Shop")
        return id
    }

    /** A valid customer token for this shop, with a live session and an active account. */
    fun authenticate(header: String?, tenantId: UUID): CustomerPrincipal {
        if (header == null || !header.startsWith("Bearer ", true)) throw Unauthorized("Sign in to continue.")
        val c = tokens.verify(header.substring(7).trim(), Audience.CUSTOMER)
        if (c.tenantId != tenantId) throw Unauthorized("Invalid token.")
        val ok = db.tenant(tenantId) { j ->
            j.sql("""SELECT count(*) FROM customer_sessions s JOIN customers c ON c.id = s.customer_id
                     WHERE s.id = :sid AND s.customer_id = :cid AND s.revoked_at IS NULL AND s.expires_at > now() AND c.is_active""")
                .param("sid", c.sessionId).param("cid", c.subject).query(Long::class.java).single() > 0
        }
        if (!ok) throw Unauthorized("Session expired. Sign in again.")
        return CustomerPrincipal(tenantId, c.subject, c.sessionId)
    }
}

data class RegisterIn(
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String,
    @field:NotBlank @field:Size(max = 256) val password: String,
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Size(max = 40) val phone: String? = null,
    val marketingOptIn: Boolean = false,
)
data class CustomerLoginIn(@field:NotBlank @field:Email @field:Size(max = 320) val email: String, @field:NotBlank @field:Size(max = 256) val password: String)
data class CustomerRefreshIn(@field:NotBlank @field:Size(max = 300) val refreshToken: String)
data class CustomerPatchIn(@field:Size(min = 1, max = 200) val name: String? = null, @field:Size(max = 40) val phone: String? = null, val marketingOptIn: Boolean? = null)
data class CustomerPasswordIn(@field:NotBlank val currentPassword: String, @field:NotBlank val newPassword: String)
data class CustomerDeleteIn(@field:NotBlank val password: String)
data class TokenIn(@field:NotBlank @field:Size(max = 200) val token: String)
data class EmailIn(@field:NotBlank @field:Email @field:Size(max = 320) val email: String)
data class ResetIn(@field:NotBlank @field:Size(max = 200) val token: String, @field:NotBlank @field:Size(max = 256) val password: String)

data class CustomerOut(
    val id: UUID, val email: String, val name: String?, val phone: String?, val marketingOptIn: Boolean, val emailVerified: Boolean,
    val isActive: Boolean, val createdAt: OffsetDateTime, val lastLoginAt: OffsetDateTime?, val orderCount: Int? = null,
)

data class CustomerSettings(val allowSignup: Boolean, val requireVerifiedEmail: Boolean, val storefrontUrl: String?)
data class CustomerSettingsIn(
    val allowSignup: Boolean? = null,
    /** Customers must click the link in the confirmation email before they can sign in. */
    val requireVerifiedEmail: Boolean? = null,
    /** The shop website; email links point at {storefront_url}/verify-email and /reset-password. "" clears it. */
    @field:Size(max = 300) val storefrontUrl: String? = null,
)

@Service
class CustomerService(
    private val db: Db, private val auth: CustomerAuth, private val tokens: Tokens, private val passwords: Passwords,
    private val limiter: RateLimiter, private val audit: Audit, private val props: TcProperties, private val email: EmailService,
    private val modules: ModuleService, private val platform: PlatformSettingsStore, private val events: Events,
    private val orders: OrderService, private val storefront: StorefrontService, private val tracking: TrackingTokens,
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
) {
    private val verifyHours = 48L
    private val resetMinutes = 60L
    private fun now() = OffsetDateTime.now(ZoneOffset.UTC)
    private fun ip() = RequestInfo.clientIp() ?: "unknown"

    fun settings(j: JdbcClient): CustomerSettings {
        val c = modules.config(j, "customer_accounts")
        return CustomerSettings(c["allow_signup"] as? Boolean ?: true, c["require_verified_email"] as? Boolean ?: false,
            (c["storefront_url"] as? String)?.trimEnd('/'))
    }

    private fun link(j: JdbcClient, slug: String, path: String, token: String): String {
        val base = settings(j).storefrontUrl ?: "${platform.general(props).publicUrl.trimEnd('/')}/shop/$slug"
        return "$base/$path?token=${java.net.URLEncoder.encode(token, Charsets.UTF_8)}"
    }

    private fun out(rs: java.sql.ResultSet, withCount: Boolean = false) = CustomerOut(rs.getObject("id", UUID::class.java), rs.getString("email"),
        rs.getString("name"), rs.getString("phone"), rs.getBoolean("marketing_opt_in"), rs.getObject("email_verified_at") != null,
        rs.getBoolean("is_active"), rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("last_login_at", OffsetDateTime::class.java),
        if (withCount) rs.getInt("order_count") else null)

    private fun find(j: JdbcClient, id: UUID) = j.sql("SELECT * FROM customers WHERE id = :id AND deleted_at IS NULL").param("id", id)
        .query { rs, _ -> out(rs) }.optional().orElseThrow { NotFound("Customer") }

    private fun newSession(j: JdbcClient, tenantId: UUID, customerId: UUID): TokenPair {
        val sessionId = UUID.randomUUID()
        val refresh = "$tenantId.$sessionId.${randomToken()}"
        j.sql("""INSERT INTO customer_sessions (id, tenant_id, customer_id, refresh_hash, expires_at, user_agent, ip)
                 VALUES (:id, :t, :c, :h, :exp, :ua, CAST(:ip AS inet))""")
            .param("id", sessionId).param("t", tenantId).param("c", customerId).param("h", sha256Hex(refresh))
            .param("exp", now().plusSeconds(props.jwt.refreshTtlSeconds)).param("ua", RequestInfo.userAgent()).param("ip", RequestInfo.clientIp()).update()
        return TokenPair(access(tenantId, customerId, sessionId), refresh, expiresIn = props.jwt.accessTtlSeconds)
    }

    private fun access(tenantId: UUID, customerId: UUID, sessionId: UUID) =
        tokens.issue(AccessClaims(customerId, Audience.CUSTOMER, sessionId, tenantId, mfa = false), props.jwt.accessTtlSeconds)

    private fun issueToken(j: JdbcClient, tenantId: UUID, customerId: UUID, purpose: String, ttlMinutes: Long): String {
        j.sql("UPDATE customer_tokens SET used_at = now() WHERE customer_id = :c AND purpose = :p AND used_at IS NULL").param("c", customerId).param("p", purpose).update()
        val token = "$tenantId.${randomToken(32)}"
        j.sql("INSERT INTO customer_tokens (tenant_id, customer_id, purpose, token_hash, expires_at) VALUES (:t, :c, :p, :h, now() + make_interval(mins => :m))")
            .param("t", tenantId).param("c", customerId).param("p", purpose).param("h", sha256Hex(token)).param("m", ttlMinutes.toInt()).update()
        return token
    }

    /** Consumes a one-time token; returns the customer it belongs to. */
    private fun useToken(j: JdbcClient, token: String, purpose: String): UUID {
        val (id, customer) = j.sql("""SELECT id, customer_id FROM customer_tokens WHERE token_hash = :h AND purpose = :p AND used_at IS NULL AND expires_at > now()
                                      FOR UPDATE""").param("h", sha256Hex(token)).param("p", purpose)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }.optional()
            .orElseThrow { Invalid("This link is invalid or has expired. Ask for a new one.") }
        j.sql("UPDATE customer_tokens SET used_at = now() WHERE id = :id").param("id", id).update()
        return customer
    }

    private fun emailOn(j: JdbcClient) = j.sql("SELECT count(*) FROM tenant_modules WHERE module = 'email' AND enabled").query(Long::class.java).single() > 0

    private fun sendVerification(j: JdbcClient, slug: String, tenantId: UUID, customerId: UUID, to: String, name: String?) {
        if (!emailOn(j)) return
        email.queue(j, tenantId, to, "customer_verify_email", mapOf("customer_name" to (name ?: ""),
            "link" to link(j, slug, "verify-email", issueToken(j, tenantId, customerId, "verify", verifyHours * 60)), "expires_hours" to verifyHours.toString()))
    }

    // -- sign-up and sign-in -----------------------------------------------------------------------

    fun register(slug: String, input: RegisterIn): TokenPair {
        val t = auth.shop(slug)
        limiter.hit("cust:register:${ip()}", 10)
        passwords.validatePolicy(input.password, CUSTOMER_MIN_PASSWORD)
        val hash = passwords.hash(input.password)
        val mail = input.email.trim().lowercase()
        return db.tenant(t) { j ->
            if (!settings(j).allowSignup) throw Forbidden("This shop does not take new sign-ups.")
            if (j.sql("SELECT count(*) FROM customers WHERE lower(email) = :e").param("e", mail).query(Long::class.java).single() > 0)
                throw Conflict("An account with this email already exists. Sign in or reset your password.")
            val id = j.sql("""INSERT INTO customers (tenant_id, email, password_hash, name, phone, marketing_opt_in) VALUES (:t, :e, :h, :n, :p, :m) RETURNING id""")
                .param("t", t).param("e", mail).param("h", hash).param("n", input.name.trim()).param("p", input.phone).param("m", input.marketingOptIn)
                .query(UUID::class.java).single()
            sendVerification(j, slug, t, id, mail, input.name)
            audit.record(j, t, "customer.register", "customers", id, actorType = "customer", actorId = id)
            events.emit(j, t, "customer.created", mapOf("id" to id, "email" to mail, "name" to input.name.trim(), "marketing_opt_in" to input.marketingOptIn))
            newSession(j, t, id)
        }
    }

    fun login(slug: String, input: CustomerLoginIn): TokenPair {
        val t = auth.shop(slug)
        val mail = input.email.trim().lowercase()
        limiter.hit("cust:login:ip:${ip()}", props.rateLimit.loginPerMin * 3)
        limiter.hit("cust:login:$t:$mail", props.rateLimit.loginPerMin)
        data class C(val id: UUID, val hash: String, val active: Boolean, val failed: Int, val locked: Boolean, val verified: Boolean)
        val c = db.tenant(t) { j ->
            j.sql("SELECT id, password_hash, is_active, failed_logins, locked_until > now(), email_verified_at IS NOT NULL FROM customers WHERE lower(email) = :e AND deleted_at IS NULL")
                .param("e", mail).query { rs, _ -> C(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getBoolean(3), rs.getInt(4), rs.getBoolean(5), rs.getBoolean(6)) }
                .optional().orElse(null)
        }
        val ok = passwords.verify(c?.hash, input.password) && c != null && c.active && !c.locked
        if (!ok) {
            if (c != null && !c.locked) db.tenant(t) { j ->
                val lock = c.failed + 1 >= MAX_FAILED_LOGINS
                j.sql("""UPDATE customers SET failed_logins = CASE WHEN :l THEN 0 ELSE failed_logins + 1 END,
                         locked_until = CASE WHEN :l THEN now() + make_interval(mins => :m) ELSE locked_until END WHERE id = :id""")
                    .param("l", lock).param("m", LOCK_MINUTES.toInt()).param("id", c.id).update()
            }
            throw Unauthorized("Invalid credentials.")
        }
        return db.tenant(t) { j ->
            if (settings(j).requireVerifiedEmail && !c!!.verified)
                throw ApiException(HttpStatus.FORBIDDEN, "email_not_verified", "Confirm your email address first. We can send the link again.")
            j.sql("UPDATE customers SET failed_logins = 0, locked_until = NULL, last_login_at = now() WHERE id = :id").param("id", c!!.id).update()
            newSession(j, t, c.id)
        }
    }

    /** Same rotation and reuse detection as staff sessions. */
    fun refresh(slug: String, token: String): TokenPair {
        val t = auth.shop(slug)
        val parts = token.split(".")
        if (parts.size != 3 || parts[0] != t.toString()) throw Unauthorized("Invalid refresh token.")
        val sessionId = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: throw Unauthorized("Invalid refresh token.")
        limiter.hit("cust:refresh:$sessionId", 30)
        val hash = sha256Hex(token)
        val result: Any = db.tenant(t) { j ->
            data class S(val customer: UUID, val current: String, val previous: String?, val usable: Boolean)
            val s = j.sql("""SELECT s.customer_id, s.refresh_hash, s.previous_hash, s.revoked_at IS NULL AND s.expires_at > now() AND c.is_active
                             FROM customer_sessions s JOIN customers c ON c.id = s.customer_id WHERE s.id = :id FOR UPDATE OF s""")
                .param("id", sessionId).query { rs, _ -> S(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getBoolean(4)) }
                .optional().orElse(null) ?: return@tenant "invalid"
            if (!s.usable) return@tenant "invalid"
            if (s.previous != null && constantTimeEquals(s.previous, hash)) {
                j.sql("UPDATE customer_sessions SET revoked_at = now(), revoked_reason = 'refresh_reuse' WHERE id = :id").param("id", sessionId).update()
                return@tenant "reused"
            }
            if (!constantTimeEquals(s.current, hash)) return@tenant "invalid"
            val next = "$t.$sessionId.${randomToken()}"
            j.sql("UPDATE customer_sessions SET previous_hash = refresh_hash, refresh_hash = :h, last_used_at = now() WHERE id = :id")
                .param("h", sha256Hex(next)).param("id", sessionId).update()
            TokenPair(access(t, s.customer, sessionId), next, expiresIn = props.jwt.accessTtlSeconds)
        }
        return result as? TokenPair ?: throw Unauthorized(if (result == "reused") "This sign-in was ended for security. Sign in again." else "Invalid refresh token.")
    }

    fun logout(slug: String, header: String?) {
        val c = auth.authenticate(header, auth.shop(slug))
        db.tenant(c.tenantId) { j -> j.sql("UPDATE customer_sessions SET revoked_at = now(), revoked_reason = 'logout' WHERE id = :id").param("id", c.sessionId).update() }
    }

    // -- the signed-in customer --------------------------------------------------------------------

    fun me(slug: String, header: String?): CustomerOut {
        val c = auth.authenticate(header, auth.shop(slug))
        return db.tenant(c.tenantId) { find(it, c.customerId) }
    }

    fun patchMe(slug: String, header: String?, input: CustomerPatchIn): CustomerOut {
        val c = auth.authenticate(header, auth.shop(slug))
        return db.tenant(c.tenantId) { j ->
            val cur = find(j, c.customerId)
            j.sql("UPDATE customers SET name = :n, phone = :p, marketing_opt_in = :m WHERE id = :id")
                .param("n", input.name?.trim() ?: cur.name).param("p", input.phone ?: cur.phone).param("m", input.marketingOptIn ?: cur.marketingOptIn)
                .param("id", c.customerId).update()
            find(j, c.customerId)
        }
    }

    fun changePassword(slug: String, header: String?, input: CustomerPasswordIn) {
        val c = auth.authenticate(header, auth.shop(slug))
        passwords.validatePolicy(input.newPassword, CUSTOMER_MIN_PASSWORD)
        val hash = db.tenant(c.tenantId) { j -> j.sql("SELECT password_hash FROM customers WHERE id = :id").param("id", c.customerId).query(String::class.java).single() }
        if (!passwords.verify(hash, input.currentPassword)) throw Unauthorized("Your current password is incorrect.")
        val newHash = passwords.hash(input.newPassword)
        db.tenant(c.tenantId) { j ->
            j.sql("UPDATE customers SET password_hash = :h WHERE id = :id").param("h", newHash).param("id", c.customerId).update()
            j.sql("UPDATE customer_sessions SET revoked_at = now(), revoked_reason = 'password_changed' WHERE customer_id = :c AND id <> :s AND revoked_at IS NULL")
                .param("c", c.customerId).param("s", c.sessionId).update()
        }
    }

    /**
     * Closes the account: personal details are erased and every sign-in ends. Orders stay (the business
     * needs them for its records) but are no longer linked to the account.
     */
    fun deleteMe(slug: String, header: String?, input: CustomerDeleteIn) {
        val c = auth.authenticate(header, auth.shop(slug))
        val hash = db.tenant(c.tenantId) { j -> j.sql("SELECT password_hash FROM customers WHERE id = :id").param("id", c.customerId).query(String::class.java).single() }
        if (!passwords.verify(hash, input.password)) throw Unauthorized("Your password is incorrect.")
        db.tenant(c.tenantId) { j ->
            j.sql("UPDATE orders SET customer_id = NULL WHERE customer_id = :c").param("c", c.customerId).update()
            j.sql("DELETE FROM devices WHERE owner_type = 'customer' AND owner_id = :c").param("c", c.customerId).update()
            j.sql("""UPDATE customers SET email = :e, name = NULL, phone = NULL, marketing_opt_in = false, is_active = false, password_hash = :h,
                     deleted_at = now() WHERE id = :id""")
                .param("e", "deleted-${c.customerId}@invalid").param("h", passwords.hash(randomToken(32))).param("id", c.customerId).update()
            j.sql("UPDATE customer_sessions SET revoked_at = now(), revoked_reason = 'account_deleted' WHERE customer_id = :c AND revoked_at IS NULL")
                .param("c", c.customerId).update()
            audit.record(j, c.tenantId, "customer.delete", "customers", c.customerId, actorType = "customer", actorId = c.customerId)
        }
    }

    fun resendVerification(slug: String, header: String?) {
        val c = auth.authenticate(header, auth.shop(slug))
        limiter.hit("cust:verify:${c.customerId}", 3)
        db.tenant(c.tenantId) { j ->
            val me = find(j, c.customerId)
            if (me.emailVerified) throw Conflict("Your email is already confirmed.")
            sendVerification(j, slug, c.tenantId, c.customerId, me.email, me.name)
        }
    }

    fun verifyEmail(slug: String, token: String) {
        val t = auth.shop(slug)
        limiter.hit("cust:verify-token:${ip()}", 20)
        db.tenant(t) { j ->
            val id = useToken(j, token, "verify")
            j.sql("UPDATE customers SET email_verified_at = coalesce(email_verified_at, now()) WHERE id = :id").param("id", id).update()
        }
    }

    /** Always answers the same way, so it does not reveal which emails have accounts. */
    fun forgot(slug: String, mail: String) {
        val t = auth.shop(slug)
        limiter.hit("cust:forgot:${ip()}", 5)
        db.tenant(t) { j ->
            if (!emailOn(j)) return@tenant
            val c = j.sql("SELECT id, email, name FROM customers WHERE lower(email) = :e AND is_active AND deleted_at IS NULL").param("e", mail.trim().lowercase())
                .query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)) }.optional().orElse(null) ?: return@tenant
            email.queue(j, t, c.second, "customer_password_reset", mapOf("customer_name" to (c.third ?: ""),
                "link" to link(j, slug, "reset-password", issueToken(j, t, c.first, "reset", resetMinutes)), "expires_minutes" to resetMinutes.toString()))
        }
    }

    fun reset(slug: String, input: ResetIn) {
        val t = auth.shop(slug)
        limiter.hit("cust:reset:${ip()}", 20)
        passwords.validatePolicy(input.password, CUSTOMER_MIN_PASSWORD)
        val hash = passwords.hash(input.password)
        db.tenant(t) { j ->
            val id = useToken(j, input.token, "reset")
            // Receiving the email proves the address too.
            j.sql("""UPDATE customers SET password_hash = :h, failed_logins = 0, locked_until = NULL, email_verified_at = coalesce(email_verified_at, now())
                     WHERE id = :id""").param("h", hash).param("id", id).update()
            j.sql("UPDATE customer_sessions SET revoked_at = now(), revoked_reason = 'password_reset' WHERE customer_id = :c AND revoked_at IS NULL").param("c", id).update()
        }
    }

    // -- the customer's orders ---------------------------------------------------------------------

    fun myOrders(slug: String, header: String?, limit: Int, offset: Int): Map<String, Any> {
        val c = auth.authenticate(header, auth.shop(slug))
        return db.tenant(c.tenantId) { j ->
            val ids = j.sql("SELECT id FROM orders WHERE customer_id = :c ORDER BY placed_at DESC LIMIT :l OFFSET :o")
                .param("c", c.customerId).param("l", limit).param("o", offset).query(UUID::class.java).list()
            mapOf("items" to ids.map { storefront.publicOrder(orders.detail(j, c.tenantId, it)) },
                "total" to j.sql("SELECT count(*) FROM orders WHERE customer_id = :c").param("c", c.customerId).query(Long::class.java).single())
        }
    }

    fun myOrder(slug: String, header: String?, id: UUID): Map<String, Any?> {
        val c = auth.authenticate(header, auth.shop(slug))
        return db.tenant(c.tenantId) { j ->
            val owner = j.sql("SELECT customer_id FROM orders WHERE id = :id").param("id", id).query { rs, _ -> rs.getObject(1, UUID::class.java) }.optional().orElse(null)
            if (owner != c.customerId) throw NotFound("Order")
            val deliveries = j.sql("SELECT id, status FROM deliveries WHERE order_id = :o ORDER BY created_at").param("o", id)
                .query { rs, _ -> val d = rs.getObject(1, UUID::class.java)
                    mapOf("id" to d, "status" to rs.getString(2), "tracking_token" to tracking.token(c.tenantId, d)) }.list()
            mapOf("order" to storefront.publicOrder(orders.detail(j, c.tenantId, id)), "lookup_token" to storefront.lookupToken(c.tenantId, id),
                "deliveries" to deliveries)
        }
    }

    // -- the business's view (dashboard) -----------------------------------------------------------

    fun list(p: UserPrincipal, q: String?, limit: Int, offset: Int): Map<String, Any> {
        p.require("customers", Action.READ)
        return db.tenant(p.tenantId) { j ->
            val where = "WHERE c.deleted_at IS NULL" + if (!q.isNullOrBlank()) " AND (c.email ILIKE :q OR c.name ILIKE :q OR c.phone ILIKE :q)" else ""
            fun bind(s: JdbcClient.StatementSpec) = s.apply { if (!q.isNullOrBlank()) param("q", "%${q.trim().replace("%", "\\%").replace("_", "\\_")}%") }
            mapOf("items" to bind(j.sql("""SELECT c.*, (SELECT count(*) FROM orders o WHERE o.customer_id = c.id) AS order_count FROM customers c $where
                                          ORDER BY c.created_at DESC LIMIT :l OFFSET :o""")).param("l", limit).param("o", offset).query { rs, _ -> out(rs, true) }.list(),
                "total" to bind(j.sql("SELECT count(*) FROM customers c $where")).query(Long::class.java).single())
        }
    }

    fun get(p: UserPrincipal, id: UUID): Map<String, Any?> {
        p.require("customers", Action.READ)
        return db.tenant(p.tenantId) { j ->
            val c = find(j, id)
            val recent = j.sql("SELECT id FROM orders WHERE customer_id = :c ORDER BY placed_at DESC LIMIT 20").param("c", id).query(UUID::class.java).list()
                .map { orders.summary(j, it) }
            mapOf("customer" to c, "recent_orders" to recent)
        }
    }

    fun getSettings(p: UserPrincipal) = p.require("customers", Action.READ).let { db.tenant(p.tenantId) { settings(it) } }

    fun setSettings(p: UserPrincipal, input: CustomerSettingsIn): CustomerSettings {
        p.require("customers", Action.MANAGE)
        input.storefrontUrl?.takeIf { it.isNotEmpty() }?.let {
            val u = runCatching { java.net.URI(it) }.getOrNull()
            if (u == null || u.scheme !in setOf("https", "http") || u.host.isNullOrBlank()) throw Invalid("storefront_url must be an http(s) address.")
        }
        return db.tenant(p.tenantId) { j ->
            val c = modules.config(j, "customer_accounts").toMutableMap()
            input.allowSignup?.let { c["allow_signup"] = it }
            input.requireVerifiedEmail?.let { c["require_verified_email"] = it }
            input.storefrontUrl?.let { c["storefront_url"] = it.ifEmpty { null } }
            modules.setConfig(j, p.tenantId, "customer_accounts", c, mapper)
            settings(j)
        }
    }

    fun setActive(p: UserPrincipal, id: UUID, active: Boolean): CustomerOut {
        p.require("customers", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            find(j, id)
            j.sql("UPDATE customers SET is_active = :a WHERE id = :id").param("a", active).param("id", id).update()
            if (!active) j.sql("UPDATE customer_sessions SET revoked_at = now(), revoked_reason = 'deactivated' WHERE customer_id = :c AND revoked_at IS NULL")
                .param("c", id).update()
            audit.record(j, p.tenantId, if (active) "customer.activate" else "customer.deactivate", "customers", id)
            find(j, id)
        }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/public/shop/{slug}/account")
@Validated
class CustomerAccountController(private val customers: CustomerService) {

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    fun register(@PathVariable slug: String, @Valid @RequestBody body: RegisterIn) = customers.register(slug, body)
    @PostMapping("/login") fun login(@PathVariable slug: String, @Valid @RequestBody body: CustomerLoginIn) = customers.login(slug, body)
    @PostMapping("/refresh") fun refresh(@PathVariable slug: String, @Valid @RequestBody body: CustomerRefreshIn) = customers.refresh(slug, body.refreshToken)
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@PathVariable slug: String, @RequestHeader("Authorization", required = false) h: String?) = customers.logout(slug, h)
    @GetMapping("/me") fun me(@PathVariable slug: String, @RequestHeader("Authorization", required = false) h: String?) = customers.me(slug, h)
    @PatchMapping("/me") fun patchMe(@PathVariable slug: String, @RequestHeader("Authorization", required = false) h: String?, @Valid @RequestBody body: CustomerPatchIn) =
        customers.patchMe(slug, h, body)
    @PostMapping("/me/change-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@PathVariable slug: String, @RequestHeader("Authorization", required = false) h: String?, @Valid @RequestBody body: CustomerPasswordIn) =
        customers.changePassword(slug, h, body)
    @PostMapping("/me/delete") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMe(@PathVariable slug: String, @RequestHeader("Authorization", required = false) h: String?, @Valid @RequestBody body: CustomerDeleteIn) =
        customers.deleteMe(slug, h, body)
    @PostMapping("/resend-verification") @ResponseStatus(HttpStatus.ACCEPTED)
    fun resend(@PathVariable slug: String, @RequestHeader("Authorization", required = false) h: String?) = customers.resendVerification(slug, h)
    @PostMapping("/verify-email") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun verify(@PathVariable slug: String, @Valid @RequestBody body: TokenIn) = customers.verifyEmail(slug, body.token)
    @PostMapping("/forgot-password") @ResponseStatus(HttpStatus.ACCEPTED)
    fun forgot(@PathVariable slug: String, @Valid @RequestBody body: EmailIn) = customers.forgot(slug, body.email)
    @PostMapping("/reset-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reset(@PathVariable slug: String, @Valid @RequestBody body: ResetIn) = customers.reset(slug, body)
    @GetMapping("/orders")
    fun orders(@PathVariable slug: String, @RequestHeader("Authorization", required = false) h: String?,
               @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int, @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) offset: Int) =
        customers.myOrders(slug, h, limit, offset)
    @GetMapping("/orders/{id}") fun order(@PathVariable slug: String, @RequestHeader("Authorization", required = false) h: String?, @PathVariable id: UUID) =
        customers.myOrder(slug, h, id)
}

data class CustomerActiveIn(val isActive: Boolean)

@RestController
@RequestMapping("\${tc.api-prefix}/customers")
@Validated
class CustomerAdminController(private val customers: CustomerService) {
    @GetMapping
    fun list(@RequestParam(required = false) @Size(max = 100) q: String?,
             @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
             @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = customers.list(Caller.user(), q, limit, offset)
    @GetMapping("/settings") fun settings() = customers.getSettings(Caller.user())
    @org.springframework.web.bind.annotation.PutMapping("/settings") fun setSettings(@Valid @RequestBody body: CustomerSettingsIn) = customers.setSettings(Caller.user(), body)
    @GetMapping("/{id}") fun get(@PathVariable id: UUID) = customers.get(Caller.user(), id)
    @PatchMapping("/{id}") fun patch(@PathVariable id: UUID, @RequestBody body: CustomerActiveIn) = customers.setActive(Caller.user(), id, body.isActive)
}
