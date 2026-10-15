package com.tenantcore.integrations

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.tenantcore.audit.Audit
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.UserPrincipal
import com.tenantcore.tenants.ModuleService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ------------------------------------------------------------------------------------------------
// Firebase Cloud Messaging (HTTP v1)
// ------------------------------------------------------------------------------------------------

data class ServiceAccount(val projectId: String, val clientEmail: String, val privateKey: RSAPrivateKey)

sealed interface PushResult {
    data object Sent : PushResult
    /** The token is no longer valid (app uninstalled, token rotated). */
    data object InvalidToken : PushResult
    data class Retry(val reason: String) : PushResult
    data class Failed(val reason: String) : PushResult
}

/**
 * Sends through FCM with a service account: a short-lived OAuth token is obtained with a signed JWT
 * (RS256) and cached until shortly before it expires. The token and FCM addresses come from the
 * platform configuration, never from the uploaded JSON, so a tenant cannot redirect these calls.
 */
@Component
class Fcm(private val props: TcProperties, private val mapper: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val tokens = ConcurrentHashMap<String, Pair<String, Instant>>()

    fun parse(json: String): ServiceAccount {
        val n = runCatching { mapper.readTree(json) }.getOrNull() ?: throw Invalid("The service account file is not valid JSON.")
        if (n.path("type").asText() != "service_account") throw Invalid("This is not a Firebase service account key (type must be service_account).")
        val project = n.path("project_id").asText("").ifBlank { throw Invalid("project_id is missing.") }
        val email = n.path("client_email").asText("").ifBlank { throw Invalid("client_email is missing.") }
        if (!Regex("^[a-z0-9-]{4,40}$").matches(project)) throw Invalid("project_id looks wrong.")
        val pem = n.path("private_key").asText("")
        val key = runCatching {
            val der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").trim())
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
        }.getOrElse { throw Invalid("private_key is not a valid RSA private key.") }
        return ServiceAccount(project, email, key)
    }

    private fun accessToken(sa: ServiceAccount): String {
        tokens[sa.clientEmail]?.let { (t, exp) -> if (Instant.now().isBefore(exp.minusSeconds(60))) return t }
        val now = Instant.now()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).build(), JWTClaimsSet.Builder()
            .issuer(sa.clientEmail).audience(props.push.googleTokenUrl).claim("scope", "https://www.googleapis.com/auth/firebase.messaging")
            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build())
        jwt.sign(RSASSASigner(sa.privateKey))
        val body = "grant_type=${URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8)}&assertion=${jwt.serialize()}"
        val res = http.send(HttpRequest.newBuilder(URI(props.push.googleTokenUrl)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) throw IllegalStateException("Google rejected the service account (HTTP ${res.statusCode()}).")
        val node = mapper.readTree(res.body())
        val token = node.path("access_token").asText("").ifBlank { throw IllegalStateException("No access token from Google.") }
        tokens[sa.clientEmail] = token to now.plusSeconds(node.path("expires_in").asLong(3600))
        return token
    }

    fun send(sa: ServiceAccount, deviceToken: String, title: String, body: String, data: Map<String, String>): PushResult {
        val access = try { accessToken(sa) } catch (e: Exception) { return PushResult.Retry(e.message ?: "token error") }
        val msg = mapOf("message" to mapOf("token" to deviceToken, "notification" to mapOf("title" to title, "body" to body), "data" to data))
        val res = try {
            http.send(HttpRequest.newBuilder(URI("${props.push.fcmBaseUrl.trimEnd('/')}/v1/projects/${sa.projectId}/messages:send"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer $access").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(msg))).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) { return PushResult.Retry("${e.javaClass.simpleName}") }
        if (res.statusCode() == 200) return PushResult.Sent
        val err = runCatching { mapper.readTree(res.body()) }.getOrNull()
        val code = err?.path("error")?.path("details")?.firstOrNull { it.has("errorCode") }?.path("errorCode")?.asText() ?: err?.path("error")?.path("status")?.asText()
        return when {
            code == "UNREGISTERED" || (res.statusCode() == 404) -> PushResult.InvalidToken
            code == "INVALID_ARGUMENT" && err?.path("error")?.path("message")?.asText()?.contains("token", true) == true -> PushResult.InvalidToken
            res.statusCode() == 401 -> { tokens.remove(sa.clientEmail); PushResult.Retry("unauthorized") }
            res.statusCode() == 429 || res.statusCode() >= 500 -> PushResult.Retry("HTTP ${res.statusCode()}")
            else -> PushResult.Failed("HTTP ${res.statusCode()} ${code ?: ""}".trim())
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Devices, notifications
// ------------------------------------------------------------------------------------------------

data class DeviceIn(@field:NotBlank @field:Size(max = 4096) val token: String,
                    @field:Pattern(regexp = "^(android|ios|web)$", message = "android, ios or web") val platform: String)
data class DeviceRemoveIn(@field:NotBlank @field:Size(max = 4096) val token: String)

data class PushAudience(
    @field:Size(max = 1000) val customerIds: List<UUID>? = null,
    @field:Size(max = 1000) val userIds: List<UUID>? = null,
    val allCustomers: Boolean = false,
    val allUsers: Boolean = false,
)
data class NotificationIn(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:NotBlank @field:Size(max = 1000) val body: String,
    @field:Size(max = 20) val data: Map<String, @Size(max = 500) String>? = null,
    @field:Valid val audience: PushAudience,
)
data class PushSettingsIn(
    /** The Firebase service account JSON. Write-only; "" removes it. */
    @field:Size(max = 20_000) val serviceAccountJson: String? = null,
    val notifyOrderUpdates: Boolean? = null,
    val notifyDeliveryUpdates: Boolean? = null,
)
data class PushSettingsOut(val configured: Boolean, val projectId: String?, val clientEmail: String?, val notifyOrderUpdates: Boolean, val notifyDeliveryUpdates: Boolean)
data class NotificationOut(val id: UUID, val title: String, val body: String, val source: String, val targetCount: Int, val sent: Int, val failed: Int,
                           val pending: Int, val createdBy: UUID?, val createdAt: OffsetDateTime)

@Service
class PushService(
    private val db: Db, private val secrets: TenantSecrets, private val fcm: Fcm, private val jobs: JobQueue, private val audit: Audit,
    private val modules: ModuleService, private val mapper: ObjectMapper, private val customerAuth: CustomerAuth,
) {
    companion object { const val SECRET = "fcm_service_account"; const val MAX_TARGETS = 100_000 }

    fun on(j: JdbcClient) = j.sql("SELECT count(*) FROM tenant_modules WHERE module = 'push_notifications' AND enabled").query(Long::class.java).single() > 0

    private fun cfg(j: JdbcClient) = modules.config(j, "push_notifications")

    fun settings(j: JdbcClient, tenantId: UUID): PushSettingsOut {
        val sa = secrets.get(j, tenantId, SECRET)?.let { runCatching { fcm.parse(it) }.getOrNull() }
        val c = cfg(j)
        return PushSettingsOut(sa != null, sa?.projectId, sa?.clientEmail, c["notify_order_updates"] as? Boolean ?: true, c["notify_delivery_updates"] as? Boolean ?: true)
    }

    fun getSettings(p: UserPrincipal) = p.require("notifications", Action.READ).let { db.tenant(p.tenantId) { settings(it, p.tenantId) } }

    fun setSettings(p: UserPrincipal, input: PushSettingsIn): PushSettingsOut {
        p.require("notifications", Action.MANAGE)
        input.serviceAccountJson?.takeIf { it.isNotEmpty() }?.let { fcm.parse(it) }
        return db.tenant(p.tenantId) { j ->
            input.serviceAccountJson?.let { secrets.set(j, p.tenantId, SECRET, it.ifEmpty { null }) }
            val c = cfg(j).toMutableMap()
            input.notifyOrderUpdates?.let { c["notify_order_updates"] = it }
            input.notifyDeliveryUpdates?.let { c["notify_delivery_updates"] = it }
            modules.setConfig(j, p.tenantId, "push_notifications", c, mapper)
            audit.record(j, p.tenantId, "push.settings", "notifications", null, mapOf("service_account_changed" to (input.serviceAccountJson != null)))
            settings(j, p.tenantId)
        }
    }

    /** Registers (or moves) a device token. Registering again marks a previously invalid token usable. */
    fun register(j: JdbcClient, tenantId: UUID, ownerType: String, ownerId: UUID, input: DeviceIn) {
        if (!on(j)) throw com.tenantcore.common.Forbidden("Push notifications are not enabled for this workspace.")
        j.sql("""INSERT INTO devices (tenant_id, owner_type, owner_id, token, platform) VALUES (:t, :ot, :o, :tok, :p)
                 ON CONFLICT (tenant_id, md5(token)) DO UPDATE SET owner_type = EXCLUDED.owner_type, owner_id = EXCLUDED.owner_id,
                   platform = EXCLUDED.platform, last_seen_at = now(), invalid_at = NULL""")
            .param("t", tenantId).param("ot", ownerType).param("o", ownerId).param("tok", input.token).param("p", input.platform).update()
    }

    fun unregister(j: JdbcClient, ownerType: String, ownerId: UUID, token: String) {
        j.sql("DELETE FROM devices WHERE md5(token) = md5(:tok) AND owner_type = :ot AND owner_id = :o").param("tok", token).param("ot", ownerType).param("o", ownerId).update()
    }

    fun registerStaff(p: UserPrincipal, input: DeviceIn) { p.requireModule("push_notifications"); db.tenant(p.tenantId) { register(it, p.tenantId, "user", p.userId, input) } }
    fun unregisterStaff(p: UserPrincipal, token: String) = db.tenant(p.tenantId) { unregister(it, "user", p.userId, token) }

    fun registerCustomer(slug: String, header: String?, input: DeviceIn) {
        val t = customerAuth.shop(slug)
        val c = customerAuth.authenticate(header, t)
        db.tenant(t) { register(it, t, "customer", c.customerId, input) }
    }

    fun unregisterCustomer(slug: String, header: String?, token: String) {
        val t = customerAuth.shop(slug)
        val c = customerAuth.authenticate(header, t)
        db.tenant(t) { unregister(it, "customer", c.customerId, token) }
    }

    /** Creates the notification, one target per device, and a job that sends them. In the caller's transaction. */
    fun queue(j: JdbcClient, tenantId: UUID, title: String, body: String, data: Map<String, String>, audience: PushAudience, source: String, by: UUID?): UUID? {
        val clauses = buildList {
            if (audience.allCustomers) add("owner_type = 'customer'")
            else if (!audience.customerIds.isNullOrEmpty()) add("(owner_type = 'customer' AND owner_id IN (:cids))")
            if (audience.allUsers) add("owner_type = 'user'")
            else if (!audience.userIds.isNullOrEmpty()) add("(owner_type = 'user' AND owner_id IN (:uids))")
        }
        if (clauses.isEmpty()) return null
        val devices = j.sql("SELECT id FROM devices WHERE invalid_at IS NULL AND (${clauses.joinToString(" OR ")}) LIMIT ${MAX_TARGETS + 1}")
            .apply { if (!audience.allCustomers && !audience.customerIds.isNullOrEmpty()) param("cids", audience.customerIds)
                     if (!audience.allUsers && !audience.userIds.isNullOrEmpty()) param("uids", audience.userIds) }
            .query(UUID::class.java).list()
        if (devices.size > MAX_TARGETS) throw Invalid("A notification can go to at most $MAX_TARGETS devices.")
        if (devices.isEmpty()) return null
        val id = j.sql("""INSERT INTO notifications (tenant_id, title, body, data, audience, source, target_count, created_by)
                          VALUES (:t, :ti, :b, CAST(:d AS jsonb), CAST(:a AS jsonb), :s, :n, :u) RETURNING id""")
            .param("t", tenantId).param("ti", title).param("b", body).param("d", mapper.writeValueAsString(data)).param("a", mapper.writeValueAsString(audience))
            .param("s", source).param("n", devices.size).param("u", by).query(UUID::class.java).single()
        j.sql("""INSERT INTO notification_targets (tenant_id, notification_id, device_id)
                 SELECT :t, :n, id FROM devices WHERE id IN (:ids)""").param("t", tenantId).param("n", id).param("ids", devices).update()
        jobs.enqueue(j, tenantId, PushJob.KIND, mapOf("notification_id" to id), maxAttempts = 6)
        return id
    }

    fun send(p: UserPrincipal, input: NotificationIn): NotificationOut {
        p.require("notifications", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            if (!settings(j, p.tenantId).configured) throw Invalid("Add the Firebase service account in the push settings first.")
            val id = queue(j, p.tenantId, input.title, input.body, input.data.orEmpty(), input.audience, "manual", p.userId)
                ?: throw Invalid("No registered devices match this audience.")
            audit.record(j, p.tenantId, "push.send", "notifications", id, mapOf("title" to input.title))
            find(j, id)
        }
    }

    private fun find(j: JdbcClient, id: UUID) = list(j, id, 1, 0).firstOrNull() ?: throw NotFound("Notification")

    private fun list(j: JdbcClient, id: UUID?, limit: Int, offset: Int) =
        j.sql("""SELECT n.*, (SELECT count(*) FILTER (WHERE status = 'sent') FROM notification_targets t WHERE t.notification_id = n.id) AS sent,
                        (SELECT count(*) FILTER (WHERE status = 'failed') FROM notification_targets t WHERE t.notification_id = n.id) AS failed,
                        (SELECT count(*) FILTER (WHERE status = 'pending') FROM notification_targets t WHERE t.notification_id = n.id) AS pending
                 FROM notifications n ${if (id != null) "WHERE n.id = :id" else ""} ORDER BY n.created_at DESC LIMIT :l OFFSET :o""")
            .apply { if (id != null) param("id", id) }.param("l", limit).param("o", offset)
            .query { rs, _ -> NotificationOut(rs.getObject("id", UUID::class.java), rs.getString("title"), rs.getString("body"), rs.getString("source"),
                rs.getInt("target_count"), rs.getInt("sent"), rs.getInt("failed"), rs.getInt("pending"), rs.getObject("created_by", UUID::class.java),
                rs.getObject("created_at", OffsetDateTime::class.java)) }.list()

    fun history(p: UserPrincipal, limit: Int, offset: Int) = p.require("notifications", Action.READ).let { db.tenant(p.tenantId) { list(it, null, limit, offset) } }
    fun get(p: UserPrincipal, id: UUID) = p.require("notifications", Action.READ).let { db.tenant(p.tenantId) { find(it, id) } }
}

/**
 * Sends a notification's pending targets. Each device's result is committed on its own, so a retried
 * job only sends to devices that are still pending (a crash between sending and recording can still
 * repeat one message: at least once).
 */
@Component
class PushJob(private val db: Db, private val secrets: TenantSecrets, private val fcm: Fcm) : JobHandler {
    companion object { const val KIND = "push.send" }
    override val kind = KIND
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(job: JobContext) {
        val tenantId = job.tenantId ?: return
        val nid = UUID.fromString(job.payload.path("notification_id").asText())
        data class N(val title: String, val body: String, val data: Map<String, String>)
        val (n, sa) = db.tenant(tenantId) { j ->
            val n = j.sql("SELECT title, body, data::text FROM notifications WHERE id = :id").param("id", nid)
                .query { rs, _ -> N(rs.getString(1), rs.getString(2), ObjectMapper().readValue(rs.getString(3), Map::class.java).entries.associate { it.key.toString() to it.value.toString() }) }
                .optional().orElse(null)
            n to secrets.get(j, tenantId, PushService.SECRET)
        }
        if (n == null) return
        val account = sa?.let { runCatching { fcm.parse(it) }.getOrNull() }
        var retry: String? = null
        while (true) {
            val batch = db.tenant(tenantId) { j ->
                j.sql("""SELECT t.device_id, d.token FROM notification_targets t JOIN devices d ON d.id = t.device_id
                         WHERE t.notification_id = :n AND t.status = 'pending' ORDER BY t.device_id LIMIT 100""")
                    .param("n", nid).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list()
            }.filter { retry == null }
            if (batch.isEmpty()) break
            var progressed = false
            batch.forEach { (device, token) ->
                val result = if (account == null) PushResult.Failed("Push is not configured.") else fcm.send(account, token, n.title, n.body, n.data)
                if (result is PushResult.Retry && !job.isLastAttempt) { retry = result.reason; return@forEach }
                progressed = true
                db.tenant(tenantId) { j ->
                    val (status, error) = when (result) {
                        PushResult.Sent -> "sent" to null
                        PushResult.InvalidToken -> "failed" to "The device is no longer registered."
                        is PushResult.Retry -> "failed" to result.reason
                        is PushResult.Failed -> "failed" to result.reason
                    }
                    j.sql("UPDATE notification_targets SET status = :s, error = :e, sent_at = CASE WHEN :s = 'sent' THEN now() END WHERE notification_id = :n AND device_id = :d")
                        .param("s", status).param("e", error).param("n", nid).param("d", device).update()
                    if (result == PushResult.InvalidToken) j.sql("UPDATE devices SET invalid_at = now() WHERE id = :d").param("d", device).update()
                }
            }
            if (!progressed) break
        }
        retry?.let { log.info("push {} will retry: {}", nid, it); throw RetryLater(it) }
    }
}

/** Automatic notifications to a customer's devices when their order or delivery changes. */
@Component
class PushAutomation(private val push: PushService) : EventListener {
    override fun on(j: JdbcClient, tenantId: UUID, type: String, data: Map<String, Any?>) {
        if (type != "order.status_changed" && type != "delivery.status_changed") return
        if (!push.on(j)) return
        val s = push.settings(j, tenantId)
        if (!s.configured) return
        if (type == "order.status_changed" && !s.notifyOrderUpdates) return
        if (type == "delivery.status_changed" && !s.notifyDeliveryUpdates) return
        val orderId = (if (type == "order.status_changed") data["id"] else data["order_id"]) as? UUID ?: return
        val customer = j.sql("SELECT customer_id FROM orders WHERE id = :o").param("o", orderId).query { rs, _ -> rs.getObject(1, UUID::class.java) }
            .optional().orElse(null) ?: return
        val number = data["number"] ?: data["order_number"]
        val (title, body) = if (type == "order.status_changed") "Order #$number" to "Your order is now ${data["status_label"] ?: data["to"]}."
            else "Delivery for order #$number" to "Your delivery is ${data["status"].toString().replace('_', ' ')}."
        push.queue(j, tenantId, title, body, mapOf("type" to type, "order_id" to orderId.toString()), PushAudience(customerIds = listOf(customer)), "auto", null)
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}")
@Validated
class PushController(private val push: PushService) {
    @GetMapping("/notifications/settings") fun settings() = push.getSettings(Caller.user())
    @PutMapping("/notifications/settings") fun setSettings(@Valid @RequestBody body: PushSettingsIn) = push.setSettings(Caller.user(), body)
    @PostMapping("/notifications") @ResponseStatus(HttpStatus.CREATED) fun send(@Valid @RequestBody body: NotificationIn) = push.send(Caller.user(), body)
    @GetMapping("/notifications")
    fun history(@RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int, @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) =
        mapOf("items" to push.history(Caller.user(), limit, offset))
    @GetMapping("/notifications/{id}") fun get(@PathVariable id: UUID) = push.get(Caller.user(), id)
    /** Staff app devices. */
    @PostMapping("/devices") @ResponseStatus(HttpStatus.NO_CONTENT) fun register(@Valid @RequestBody body: DeviceIn) = push.registerStaff(Caller.user(), body)
    @PostMapping("/devices/remove") @ResponseStatus(HttpStatus.NO_CONTENT) fun remove(@Valid @RequestBody body: DeviceRemoveIn) { push.unregisterStaff(Caller.user(), body.token) }
}

@RestController
@RequestMapping("\${tc.api-prefix}/public/shop/{slug}/account/devices")
class CustomerDeviceController(private val push: PushService) {
    @PostMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    fun register(@PathVariable slug: String, @RequestHeader("Authorization", required = false) h: String?, @Valid @RequestBody body: DeviceIn) =
        push.registerCustomer(slug, h, body)
    @PostMapping("/remove") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable slug: String, @RequestHeader("Authorization", required = false) h: String?, @Valid @RequestBody body: DeviceRemoveIn) =
        push.unregisterCustomer(slug, h, body.token)
}
