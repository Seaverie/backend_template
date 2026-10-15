package com.tenantcore.integrations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.Crypto
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

// ------------------------------------------------------------------------------------------------
// Outgoing requests to addresses a tenant chose (webhooks)
// ------------------------------------------------------------------------------------------------

data class OutboundResponse(val status: Int, val body: String)

/**
 * Stops a tenant from using the platform to reach internal services (SSRF). An address must be https
 * and its host must resolve only to public IPs. Requests then connect to the exact IP that was
 * checked (the TLS certificate is still verified against the host name), so DNS cannot be switched to
 * an internal address between the check and the connection. No proxy is used and redirects are never
 * followed.
 */
@Component
class NetGuard(private val props: TcProperties) {

    fun isPublic(a: InetAddress): Boolean {
        val b = a.address.map { it.toInt() and 0xff }
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return false
        if (b.size == 4) return !(b[0] == 0 || b[0] >= 240 ||                         // "this network", reserved, broadcast
            (b[0] == 100 && (b[1] and 0xc0) == 64) ||                                    // carrier-grade NAT 100.64/10
            (b[0] == 192 && b[1] == 0 && b[2] == 0) ||                                    // 192.0.0/24 protocol assignments
            (b[0] == 198 && (b[1] and 0xfe) == 18))                                       // 198.18/15 benchmarking
        if ((b[0] and 0xfe) == 0xfc) return false                                         // fc00::/7 unique local
        // 64:ff9b::/96 (NAT64) carries an IPv4 address in its last four bytes.
        if (b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b && b.subList(4, 12).all { it == 0 })
            return isPublic(InetAddress.getByAddress(a.address.copyOfRange(12, 16)))
        return true
    }

    private fun host(u: URI) = u.host.removePrefix("[").removeSuffix("]")

    private fun resolve(host: String): InetAddress {
        val addrs = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { throw Invalid("Could not resolve $host.") }
        if (!props.webhooks.allowPrivateTargets && addrs.any { !isPublic(it) }) throw Invalid("The address must be on the public internet.")
        return addrs.first()
    }

    fun checkUrl(url: String): URI {
        val u = runCatching { URI(url) }.getOrNull() ?: throw Invalid("Not a valid address.")
        val private = props.webhooks.allowPrivateTargets
        if (u.scheme !in (if (private) setOf("https", "http") else setOf("https"))) throw Invalid("The address must start with https://.")
        if (u.host.isNullOrBlank() || u.userInfo != null) throw Invalid("The address must have a host name and no user name or password.")
        resolve(host(u))
        return u
    }

    /** POSTs [body] and returns the status code and the first 1000 bytes of the answer. */
    fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): OutboundResponse {
        val u = checkUrl(url)
        val host = host(u)
        val https = u.scheme == "https"
        val port = if (u.port > 0) u.port else if (https) 443 else 80
        val address = resolve(host)
        val raw = java.net.Socket(java.net.Proxy.NO_PROXY)
        raw.connect(java.net.InetSocketAddress(address, port), timeoutMs)
        raw.soTimeout = timeoutMs
        val socket: java.net.Socket = if (!https) raw else {
            val ssl = javax.net.ssl.SSLContext.getDefault().socketFactory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"          // certificate must match the host name
                if (!host.contains(':') && !host.all { it.isDigit() || it == '.' }) serverNames = listOf(javax.net.ssl.SNIHostName(host))
            }
            ssl.startHandshake(); ssl
        }
        socket.use { s ->
            val path = (u.rawPath?.ifEmpty { null } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            val head = StringBuilder("POST $path HTTP/1.1\r\nHost: ${if (u.port > 0) "${u.host}:${u.port}" else u.host}\r\n")
                .append("Content-Length: ${body.size}\r\nConnection: close\r\n")
            headers.forEach { (k, v) ->
                require(k.none { it == '\r' || it == '\n' || it == ':' } && v.none { it == '\r' || it == '\n' }) { "bad header" }
                head.append("$k: $v\r\n")
            }
            head.append("\r\n")
            val out = s.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1)); out.write(body); out.flush()
            val input = java.io.BufferedInputStream(s.getInputStream())
            fun line(): String? {
                val b = java.io.ByteArrayOutputStream()
                while (true) {
                    val c = input.read()
                    if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.ISO_8859_1)
                    if (c == '\n'.code) return b.toString(Charsets.ISO_8859_1).trimEnd('\r')
                    if (b.size() > 8192) throw java.io.IOException("response header too long")
                    b.write(c)
                }
            }
            val status = line()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("not an HTTP response")
            var n = 0
            while (line()?.isNotEmpty() == true) if (++n > 200) throw java.io.IOException("too many response headers")
            return OutboundResponse(status, String(input.readNBytes(1000), Charsets.UTF_8))
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Per-tenant secrets
// ------------------------------------------------------------------------------------------------

/** Encrypted integration secrets. The context binds each ciphertext to its tenant and name. */
@Component
class TenantSecrets(private val crypto: Crypto) {
    private fun ctx(tenantId: UUID, name: String) = "tenant_secret:$tenantId:$name"

    fun get(j: JdbcClient, tenantId: UUID, name: String): String? =
        j.sql("SELECT secret_enc FROM tenant_secrets WHERE name = :n").param("n", name).query(String::class.java).optional().orElse(null)
            ?.let { crypto.decrypt(it, ctx(tenantId, name)) }

    fun has(j: JdbcClient, name: String) = j.sql("SELECT count(*) FROM tenant_secrets WHERE name = :n").param("n", name).query(Long::class.java).single() > 0

    /** null value removes the secret. */
    fun set(j: JdbcClient, tenantId: UUID, name: String, value: String?) {
        if (value == null) { j.sql("DELETE FROM tenant_secrets WHERE name = :n").param("n", name).update(); return }
        j.sql("""INSERT INTO tenant_secrets (tenant_id, name, secret_enc) VALUES (:t, :n, :s)
                 ON CONFLICT (tenant_id, name) DO UPDATE SET secret_enc = EXCLUDED.secret_enc, updated_at = now()""")
            .param("t", tenantId).param("n", name).param("s", crypto.encrypt(value, ctx(tenantId, name))).update()
    }
}

// ------------------------------------------------------------------------------------------------
// Background jobs
// ------------------------------------------------------------------------------------------------

data class JobContext(val id: UUID, val tenantId: UUID?, val payload: JsonNode, val attempt: Int, val maxAttempts: Int) {
    val isLastAttempt get() = attempt >= maxAttempts
}

/** Throw to try again later (with backoff), unless this was the last attempt. */
class RetryLater(message: String) : RuntimeException(message)

interface JobHandler {
    val kind: String
    /** Must be safe to run more than once for the same job: delivery is at least once. */
    fun run(job: JobContext)
}

@Component
class JobQueue(private val mapper: ObjectMapper) {
    /** Adds a job in the caller's transaction: it exists only if that transaction commits. */
    fun enqueue(j: JdbcClient, tenantId: UUID?, kind: String, payload: Map<String, Any?>, runAt: OffsetDateTime? = null, maxAttempts: Int = 8): UUID =
        j.sql("""INSERT INTO jobs (tenant_id, kind, payload, run_at, max_attempts) VALUES (CAST(:t AS uuid), :k, CAST(:p AS jsonb), coalesce(:r, now()), :m)
                 RETURNING id""")
            .param("t", tenantId?.toString()).param("k", kind).param("p", mapper.writeValueAsString(payload)).param("r", runAt).param("m", maxAttempts)
            .query(UUID::class.java).single()
}

/**
 * Runs due jobs. Leasing uses FOR UPDATE SKIP LOCKED, so any number of API instances can run workers
 * without two taking the same job; a job whose worker died is taken again when its lease expires.
 */
@Component
class JobWorker(private val db: Db, private val mapper: ObjectMapper, private val props: TcProperties, handlers: List<JobHandler>) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byKind = handlers.associateBy { it.kind }

    @Scheduled(fixedDelayString = "\${tc.jobs.poll-ms:1000}", initialDelay = 2000)
    fun tick() {
        if (!props.jobs.workerEnabled) return
        runCatching { while (drain() > 0) Unit }.onFailure { log.warn("job worker: {}", it.message) }
    }

    fun drain(): Int {
        val leased = db.platform { j ->
            j.sql("""UPDATE jobs SET status = 'running', attempts = attempts + 1, lease_until = now() + interval '2 minutes'
                     WHERE id IN (SELECT id FROM jobs WHERE (status = 'queued' AND run_at <= now()) OR (status = 'running' AND lease_until < now())
                                  ORDER BY run_at LIMIT 10 FOR UPDATE SKIP LOCKED)
                     RETURNING id, tenant_id, kind, payload::text, attempts, max_attempts""")
                .query { rs, _ -> Triple(rs.getString(3), JobContext(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java),
                    mapper.readTree(rs.getString(4)), rs.getInt(5), rs.getInt(6)), Unit) }.list()
        }
        leased.forEach { (kind, ctx, _) ->
            val handler = byKind[kind]
            val error = if (handler == null) IllegalStateException("No handler for job kind '$kind'.")
                else runCatching { handler.run(ctx) }.exceptionOrNull()
            db.platform { j ->
                when {
                    error == null -> j.sql("UPDATE jobs SET status = 'done', finished_at = now(), last_error = NULL WHERE id = :id").param("id", ctx.id).update()
                    error is RetryLater && !ctx.isLastAttempt -> {
                        val backoff = minOf(15L * (1L shl minOf(ctx.attempt - 1, 20)), 6 * 3600L)
                        j.sql("UPDATE jobs SET status = 'queued', lease_until = NULL, last_error = :e, run_at = now() + make_interval(secs => :b) WHERE id = :id")
                            .param("e", error.message?.take(1000)).param("b", backoff.toDouble()).param("id", ctx.id).update()
                    }
                    else -> {
                        if (error !is RetryLater) log.warn("job {} ({}) failed: {}", ctx.id, kind, error.toString())
                        j.sql("UPDATE jobs SET status = 'dead', finished_at = now(), last_error = :e WHERE id = :id")
                            .param("e", (error.message ?: error.javaClass.simpleName).take(1000)).param("id", ctx.id).update()
                    }
                }
            }
        }
        return leased.size
    }
}

data class JobOut(val id: UUID, val tenantId: UUID?, val kind: String, val status: String, val attempts: Int, val maxAttempts: Int,
                  val runAt: OffsetDateTime, val lastError: String?, val createdAt: OffsetDateTime, val finishedAt: OffsetDateTime?)

/** The platform admin's view of the queue: what is waiting, what failed, and retrying failed jobs. */
@RestController
@RequestMapping("\${tc.api-prefix}/platform/jobs")
@Validated
class PlatformJobController(private val db: Db) {
    @GetMapping
    fun list(@RequestParam(required = false) @Pattern(regexp = "^(queued|running|done|dead)$") status: String?,
             @RequestParam(required = false) @Pattern(regexp = "^[a-z._]{1,40}$") kind: String?,
             @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int): Map<String, Any> = db.platform { j ->
        val where = listOfNotNull(status?.let { "status = :s" }, kind?.let { "kind = :k" }).let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
        val counts = j.sql("SELECT status, count(*) FROM jobs GROUP BY status").query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
        mapOf("counts" to counts, "items" to j.sql("""SELECT id, tenant_id, kind, status, attempts, max_attempts, run_at, last_error, created_at, finished_at
                                                       FROM jobs $where ORDER BY created_at DESC LIMIT :l""")
            .apply { status?.let { param("s", it) }; kind?.let { param("k", it) } }.param("l", limit)
            .query { rs, _ -> JobOut(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getInt(6), rs.getObject(7, OffsetDateTime::class.java), rs.getString(8), rs.getObject(9, OffsetDateTime::class.java),
                rs.getObject(10, OffsetDateTime::class.java)) }.list())
    }

    @PostMapping("/{id}/retry")
    fun retry(@PathVariable id: UUID): Map<String, Any> = db.platform { j ->
        val n = j.sql("UPDATE jobs SET status = 'queued', attempts = 0, run_at = now(), lease_until = NULL, finished_at = NULL WHERE id = :id AND status = 'dead'")
            .param("id", id).update()
        if (n == 0) throw NotFound("Failed job")
        mapOf("retried" to true)
    }
}

/**
 * Housekeeping: removes finished jobs, old delivery locations, expired cache entries and tokens.
 * Tenant tables are cleaned inside each tenant's own context.
 */
@Component
class Housekeeping(private val db: Db, private val props: TcProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    fun run() {
        runCatching {
            db.platform { j ->
                j.sql("DELETE FROM jobs WHERE (status = 'done' AND finished_at < now() - interval '7 days') OR (status = 'dead' AND finished_at < now() - interval '30 days')").update()
                j.sql("DELETE FROM delivery_pings WHERE received_at < now() - make_interval(days => :d)").param("d", props.deliveries.pingRetentionDays).update()
            }
            val tenants = db.anonymous { j -> j.sql("SELECT id FROM tenants").query(UUID::class.java).list() }
            tenants.forEach { t ->
                db.tenant(t) { j ->
                    j.sql("DELETE FROM geocode_cache WHERE created_at < now() - make_interval(days => :d)").param("d", props.maps.cacheDays).update()
                    j.sql("DELETE FROM webhook_events WHERE created_at < now() - interval '30 days'").update()
                    j.sql("DELETE FROM customer_tokens WHERE expires_at < now() - interval '7 days'").update()
                    j.sql("DELETE FROM customer_sessions WHERE expires_at < now() - interval '7 days'").update()
                }
            }
        }.onFailure { log.warn("housekeeping failed: {}", it.message) }
    }
}
