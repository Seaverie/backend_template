package com.tenantcore.security

import com.tenantcore.common.ApiException
import com.tenantcore.common.ErrorBody
import com.tenantcore.common.RateLimited
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Request id (honours a sane incoming X-Request-ID) and client IP for logs and the audit trail. */
@Component
@Order(-200)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val incoming = req.getHeader("X-Request-ID")
        val id = if (incoming != null && Regex("^[A-Za-z0-9._-]{8,64}$").matches(incoming)) incoming else UUID.randomUUID().toString().replace("-", "")
        MDC.put("request_id", id)
        res.setHeader("X-Request-ID", id)
        try { chain.doFilter(req, res) } finally { MDC.remove("request_id") }
    }
}

object RequestInfo {
    fun requestId(): String? = MDC.get("request_id")

    private fun current(): HttpServletRequest? = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request

    /** Cloudflare Tunnel puts the visitor's address in CF-Connecting-IP; otherwise X-Forwarded-For is applied by Spring. */
    fun clientIp(): String? {
        val r = current() ?: return null
        val cf = r.getHeader("CF-Connecting-IP")?.trim()
        val ip = if (!cf.isNullOrEmpty()) cf else r.remoteAddr
        return ip?.takeIf { Regex("^[0-9a-fA-F:.]{2,45}$").matches(it) }
    }

    fun userAgent(): String? = current()?.getHeader("User-Agent")?.take(300)
}

/**
 * Fixed-window counters in Redis (atomic INCR + EXPIRE). If Redis is unreachable each instance falls
 * back to an in-memory window at a quarter of the limit, so a Redis outage neither blocks the API
 * nor removes rate limiting.
 */
@Component
class RateLimiter(private val redis: StringRedisTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val script = DefaultRedisScript(
        "local n = redis.call('INCR', KEYS[1]) if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end return n",
        Long::class.java,
    )
    private val local = ConcurrentHashMap<String, Pair<Long, Int>>()

    /** Throws [RateLimited] when [key] exceeded [limit] hits in the current [windowSeconds] window. */
    fun hit(key: String, limit: Int, windowSeconds: Long = 60) {
        val window = Instant.now().epochSecond / windowSeconds
        val retry = windowSeconds - Instant.now().epochSecond % windowSeconds
        val count = try {
            redis.execute(script, listOf("rl:$key:$window"), (windowSeconds + 1).toString()) ?: 0L
        } catch (e: Exception) {
            log.warn("rate limiter falling back to local counters: {}", e.javaClass.simpleName)
            val entry = local.compute(key) { _, v -> if (v == null || v.first != window) window to 1 else window to v.second + 1 }!!
            if (local.size > 100_000) local.clear()
            return if (entry.second > maxOf(1, limit / 4)) throw RateLimited(retry) else Unit
        }
        if (count > limit) throw RateLimited(retry)
    }
}

/** Resolves the bearer token to a [Principal], re-checking the session and account on every request. */
@Component
class PrincipalLoader(private val db: Db, private val tokens: Tokens, private val props: TcProperties) {

    fun loadPlatform(token: String): PlatformPrincipal {
        val c = tokens.verify(token, Audience.PLATFORM)
        return db.platform { j ->
            j.sql("""SELECT a.email FROM platform_sessions s JOIN platform_admins a ON a.id = s.admin_id
                     WHERE s.id = :sid AND s.admin_id = :aid AND s.revoked_at IS NULL AND s.expires_at > now() AND a.is_active""")
                .param("sid", c.sessionId).param("aid", c.subject)
                .query(String::class.java).optional()
                .map { PlatformPrincipal(c.subject, c.sessionId, it, c.mfa) }
                .orElseThrow { com.tenantcore.common.Unauthorized("Session expired. Sign in again.") }
        }
    }

    fun loadUser(token: String): UserPrincipal {
        val c = tokens.verify(token, Audience.TENANT)
        val tenantId = c.tenantId ?: throw com.tenantcore.common.Unauthorized("Invalid token.")
        return db.tenant(tenantId) { j ->
            val row = j.sql("""SELECT u.email, u.is_tenant_admin, t.status
                               FROM auth_sessions s JOIN app_users u ON u.tenant_id = s.tenant_id AND u.id = s.user_id
                               JOIN tenants t ON t.id = u.tenant_id
                               WHERE s.id = :sid AND s.user_id = :uid AND s.revoked_at IS NULL AND s.expires_at > now() AND u.is_active""")
                .param("sid", c.sessionId).param("uid", c.subject)
                .query { rs, _ -> Triple(rs.getString(1), rs.getBoolean(2), rs.getString(3)) }
                .optional().orElseThrow { com.tenantcore.common.Unauthorized("Session expired. Sign in again.") }
            if (row.third != "active") throw com.tenantcore.common.Unauthorized("This workspace is ${row.third}.")
            val modules = j.sql("SELECT module FROM tenant_modules WHERE enabled").query(String::class.java).set()
            val grants = if (row.second) emptyMap() else loadGrants(j, c.subject)
            UserPrincipal(tenantId, c.subject, c.sessionId, row.first, row.second, grants, modules)
        }
    }

    /**
     * The current permissions of a user, without a session: for background work done on a user's behalf
     * (imports). Fails if the user or the workspace is no longer active.
     */
    fun forUser(tenantId: UUID, userId: UUID): UserPrincipal = db.tenant(tenantId) { j ->
        val row = j.sql("""SELECT u.email, u.is_tenant_admin, t.status FROM app_users u JOIN tenants t ON t.id = u.tenant_id
                           WHERE u.id = :uid AND u.is_active""")
            .param("uid", userId).query { rs, _ -> Triple(rs.getString(1), rs.getBoolean(2), rs.getString(3)) }
            .optional().orElseThrow { com.tenantcore.common.Forbidden("The user who started this is no longer active.") }
        if (row.third != "active") throw com.tenantcore.common.Forbidden("This workspace is ${row.third}.")
        val modules = j.sql("SELECT module FROM tenant_modules WHERE enabled").query(String::class.java).set()
        UserPrincipal(tenantId, userId, UUID(0, 0), row.first, row.second, if (row.second) emptyMap() else loadGrants(j, userId), modules)
    }

    /**
     * An API key ("tck_<tenant>_<secret>"): only the SHA-256 is stored. It acts with its role's
     * permissions and never as a workspace admin.
     */
    fun loadApiKey(token: String): UserPrincipal {
        val parts = token.split('_', limit = 3)
        val tenantId = parts.getOrNull(1)?.takeIf { it.length == 32 }?.let {
            runCatching { UUID.fromString(it.replaceFirst(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5")) }.getOrNull()
        } ?: throw com.tenantcore.common.Unauthorized("Invalid API key.")
        return db.tenant(tenantId) { j ->
            data class K(val id: UUID, val name: String, val role: UUID?, val usable: Boolean, val tenantStatus: String, val touch: Boolean)
            val k = j.sql("""SELECT k.id, k.name, k.role_id, k.revoked_at IS NULL AND (k.expires_at IS NULL OR k.expires_at > now()), t.status,
                                      k.last_used_at IS NULL OR k.last_used_at < now() - interval '1 minute'
                               FROM api_keys k JOIN tenants t ON t.id = k.tenant_id WHERE k.key_hash = :h""")
                .param("h", sha256Hex(token)).query { rs, _ -> K(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java),
                    rs.getBoolean(4), rs.getString(5), rs.getBoolean(6)) }.optional().orElse(null)
            if (k == null || !k.usable) throw com.tenantcore.common.Unauthorized("Invalid or expired API key.")
            if (k.tenantStatus != "active") throw com.tenantcore.common.Unauthorized("This workspace is ${k.tenantStatus}.")
            if (k.touch) j.sql("UPDATE api_keys SET last_used_at = now() WHERE id = :id").param("id", k.id).update()
            val modules = j.sql("SELECT module FROM tenant_modules WHERE enabled").query(String::class.java).set()
            val grants = if (k.role == null) emptyMap() else loadGrants(j, k.role, byRole = true)
            UserPrincipal(tenantId, k.id, k.id, "api-key:${k.name}", false, grants, modules, apiKeyId = k.id)
        }
    }

    private fun loadGrants(j: org.springframework.jdbc.core.simple.JdbcClient, id: UUID, byRole: Boolean = false): Map<Pair<String, Action>, Grant> {
        val out = HashMap<Pair<String, Action>, Grant>()
        j.sql(if (byRole) "SELECT p.resource, p.action, p.row_scope, p.readable_fields, p.writable_fields FROM role_permissions p WHERE p.role_id = :u"
              else """SELECT p.resource, p.action, p.row_scope, p.readable_fields, p.writable_fields
                 FROM user_roles ur JOIN role_permissions p ON p.role_id = ur.role_id WHERE ur.user_id = :u""")
            .param("u", id)
            .query { rs, _ ->
                val action = Action.of(rs.getString(2)) ?: return@query
                val g = Grant(rs.getString(3), arrayOrNull(rs.getArray(4)), arrayOrNull(rs.getArray(5)))
                // Several roles may grant the same permission: combine to the most permissive.
                out.merge(rs.getString(1) to action, g) { a, b ->
                    Grant(if (a.rowScope == "all" || b.rowScope == "all") "all" else "own",
                        union(a.readableFields, b.readableFields), union(a.writableFields, b.writableFields))
                }
            }.list()
        return out
    }

    /** Per-tenant API limit set by the platform admin; null means the platform default. */
    fun tenantRateLimit(tenantId: UUID): Int? = db.anonymous { j ->
        j.sql("SELECT rate_limit_per_min FROM tenants WHERE id = :id").param("id", tenantId)
            .query { rs, _ -> rs.getObject(1) as Int? }.list().firstOrNull()
    }

    private fun union(a: List<String>?, b: List<String>?) = if (a == null || b == null) null else (a + b).distinct()

    @Suppress("UNCHECKED_CAST")
    private fun arrayOrNull(a: java.sql.Array?): List<String>? = (a?.array as? Array<String>)?.toList()
}

/** Bearer-token authentication. Platform routes accept platform tokens only, everything else tenant tokens. */
@Component
class AuthFilter(
    private val loader: PrincipalLoader,
    private val limiter: RateLimiter,
    private val props: TcProperties,
) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val header = req.getHeader("Authorization")
        // Public endpoints (shop, customer accounts, provider webhooks) authenticate themselves.
        val publicRoute = req.requestURI.startsWith("${props.apiPrefix}/public/")
        if (publicRoute || header == null || !header.startsWith("Bearer ", ignoreCase = true)) { chain.doFilter(req, res); return }
        val token = header.substring(7).trim()
        val platformRoute = req.requestURI.startsWith("${props.apiPrefix}/platform/")
        try {
            val apiKey = token.startsWith("tck_")
            val principal: Principal = when {
                platformRoute -> loader.loadPlatform(token)
                apiKey -> loader.loadApiKey(token)
                else -> loader.loadUser(token)
            }
            // A key is not a person: it cannot sign out, change a password or manage API keys.
            if (apiKey && (req.requestURI.startsWith("${props.apiPrefix}/auth/") || req.requestURI.startsWith("${props.apiPrefix}/api-keys"))) {
                ErrorBody.write(res, 403, "forbidden", "API keys cannot use this endpoint.")
                return
            }
            when (principal) {
                is UserPrincipal -> {
                    limiter.hit("u:${principal.userId}", props.rateLimit.principalPerMin)
                    limiter.hit("t:${principal.tenantId}", tenantLimit(principal.tenantId))
                }
                is PlatformPrincipal -> {
                    limiter.hit("p:${principal.adminId}", props.rateLimit.principalPerMin * 10)
                    // Until a required second factor is verified, only MFA enrolment and sign-out are reachable.
                    val mfaPath = req.requestURI.startsWith("${props.apiPrefix}/platform/auth/")
                    if (props.mfaRequired && !principal.mfaVerified && !mfaPath) {
                        ErrorBody.write(res, 403, "mfa_required", "Set up two-factor authentication to continue.")
                        return
                    }
                }
            }
            val role = if (principal is PlatformPrincipal) "ROLE_PLATFORM" else "ROLE_TENANT"
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(principal, null, listOf(SimpleGrantedAuthority(role)))
            chain.doFilter(req, res)
        } catch (e: ApiException) {
            if (e is RateLimited) res.setHeader("Retry-After", e.retryAfterSeconds.toString())
            ErrorBody.write(res, e.status.value(), e.code, e.message)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private val tenantLimits = ConcurrentHashMap<UUID, Pair<Long, Int>>()

    /** Per-tenant limit set by the platform admin (cached for a minute), capped by the platform maximum. */
    private fun tenantLimit(tenantId: UUID): Int {
        val now = System.currentTimeMillis()
        tenantLimits[tenantId]?.let { (at, v) -> if (now - at < 60_000) return v }
        val v = loader.tenantRateLimit(tenantId) ?: props.rateLimit.tenantDefaultPerMin
        return minOf(v, props.rateLimit.tenantMaxPerMin).also { tenantLimits[tenantId] = now to it }
    }
}
