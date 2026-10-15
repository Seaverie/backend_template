package com.tenantcore.integrations

import com.fasterxml.jackson.databind.ObjectMapper
import com.tenantcore.audit.Audit
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.Crypto
import com.tenantcore.security.UserPrincipal
import com.tenantcore.security.randomToken
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Lazy
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ------------------------------------------------------------------------------------------------
// Events
// ------------------------------------------------------------------------------------------------

/** Reacts to events inside the transaction that caused them (e.g. queue a push notification). */
interface EventListener {
    fun on(j: JdbcClient, tenantId: UUID, type: String, data: Map<String, Any?>)
}

object EventTypes {
    val fixed = listOf(
        "order.created", "order.status_changed", "order.paid",
        "payment.succeeded", "payment.failed", "payment.refunded",
        "return.created", "return.updated",
        "product.created", "product.updated", "product.deleted",
        "customer.created",
        "delivery.created", "delivery.status_changed",
    )
    private val prefixes = fixed.map { it.substringBefore('.') }.toSet() + "data"
    private val DATA = Regex("^data\\.[a-z][a-z0-9_]{0,62}\\.(created|updated|deleted|\\*)$")

    fun validPattern(p: String) = p == "*" || p in fixed || (p.endsWith(".*") && p.removeSuffix(".*") in prefixes) || DATA.matches(p)

    fun matches(pattern: String, type: String) = pattern == "*" || pattern == type ||
        (pattern.endsWith(".*") && type.startsWith(pattern.removeSuffix("*")))
}

/**
 * Records business events. For each active webhook endpoint that wants the event, a delivery and a
 * background job are written in the caller's transaction (a transactional outbox): if the change rolls
 * back, nothing is sent; if it commits, the delivery will be attempted.
 */
@Component
class Events(private val mapper: ObjectMapper, private val jobs: JobQueue, private val listeners: org.springframework.beans.factory.ObjectProvider<EventListener>) {

    fun emit(j: JdbcClient, tenantId: UUID, type: String, data: Map<String, Any?>) {
        listeners.orderedStream().forEach { it.on(j, tenantId, type, data) }
        val on = j.sql("SELECT count(*) FROM tenant_modules WHERE module = 'webhooks' AND enabled").query(Long::class.java).single() > 0
        if (!on) return
        val endpoints = j.sql("SELECT id, events FROM webhook_endpoints WHERE is_active")
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to (rs.getArray(2).array as Array<*>).map { it.toString() } }.list()
            .filter { (_, patterns) -> patterns.any { EventTypes.matches(it, type) } }
        if (endpoints.isEmpty()) return
        val eventId = record(j, tenantId, type, data)
        endpoints.forEach { (endpointId, _) -> deliver(j, tenantId, endpointId, eventId) }
    }

    fun record(j: JdbcClient, tenantId: UUID, type: String, data: Map<String, Any?>): UUID {
        val id = UUID.randomUUID()
        val payload = mapOf("id" to id, "type" to type, "created_at" to OffsetDateTime.now(ZoneOffset.UTC), "tenant_id" to tenantId, "data" to data)
        j.sql("INSERT INTO webhook_events (id, tenant_id, type, payload) VALUES (:id, :t, :ty, CAST(:p AS jsonb))")
            .param("id", id).param("t", tenantId).param("ty", type).param("p", mapper.writeValueAsString(payload)).update()
        return id
    }

    fun deliver(j: JdbcClient, tenantId: UUID, endpointId: UUID, eventId: UUID): UUID {
        val d = j.sql("INSERT INTO webhook_deliveries (tenant_id, endpoint_id, event_id) VALUES (:t, :e, :ev) RETURNING id")
            .param("t", tenantId).param("e", endpointId).param("ev", eventId).query(UUID::class.java).single()
        jobs.enqueue(j, tenantId, WebhookDeliveryHandler.KIND, mapOf("delivery_id" to d))
        return d
    }
}

// ------------------------------------------------------------------------------------------------
// Delivery
// ------------------------------------------------------------------------------------------------

/**
 * Sends one delivery. The body is the stored event JSON; the signature header is
 * `TenantCore-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<body>">` with the endpoint's
 * secret. Receivers should check it, reject old timestamps, and ignore a TenantCore-Delivery id they
 * have already processed (a delivery can arrive twice).
 */
@Component
class WebhookDeliveryHandler(
    private val db: Db, private val crypto: Crypto, private val guard: NetGuard, private val props: TcProperties,
) : JobHandler {
    companion object {
        const val KIND = "webhook.deliver"
        const val DISABLE_AFTER = 15
        fun sign(secret: String, t: Long, body: String): String =
            HexFormat.of().formatHex(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }.doFinal("$t.$body".toByteArray()))
    }
    override val kind = KIND

    private data class Target(val deliveryStatus: String, val endpointId: UUID, val url: String, val secretEnc: String, val active: Boolean, val type: String, val body: String)

    override fun run(job: JobContext) {
        val tenantId = job.tenantId ?: return
        val id = UUID.fromString(job.payload.path("delivery_id").asText())
        val t = db.tenant(tenantId) { j ->
            j.sql("""SELECT d.status, e.id, e.url, e.secret_enc, e.is_active, ev.type, ev.payload::text FROM webhook_deliveries d
                     JOIN webhook_endpoints e ON e.id = d.endpoint_id JOIN webhook_events ev ON ev.id = d.event_id WHERE d.id = :id""")
                .param("id", id).query { rs, _ -> Target(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4),
                    rs.getBoolean(5), rs.getString(6), rs.getString(7)) }.optional().orElse(null)
        } ?: return
        if (t.deliveryStatus == "succeeded") return
        if (!t.active) { finish(tenantId, id, t.endpointId, "dead", null, "The endpoint is disabled.", null, 0, count = false); return }

        val started = System.nanoTime()
        var code: Int? = null; var response: String? = null
        val error: String? = try {
            val ts = Instant.now().epochSecond
            val secret = crypto.decrypt(t.secretEnc, "webhook:$tenantId:${t.endpointId}")
            val res = guard.post(t.url, mapOf("Content-Type" to "application/json", "User-Agent" to "TenantCore-Webhooks/1.0",
                "TenantCore-Event" to t.type, "TenantCore-Delivery" to id.toString(), "TenantCore-Signature" to "t=$ts,v1=${sign(secret, ts, t.body)}"),
                t.body.toByteArray(Charsets.UTF_8), (props.webhooks.timeoutSeconds * 1000).toInt())
            code = res.status
            response = res.body
            if (code in 200..299) null else "HTTP $code"
        } catch (e: Invalid) { e.message } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message ?: ""}".take(500) }
        val ms = ((System.nanoTime() - started) / 1_000_000).toInt()
        when {
            error == null -> finish(tenantId, id, t.endpointId, "succeeded", code, null, response, ms, count = true)
            job.isLastAttempt -> finish(tenantId, id, t.endpointId, "dead", code, error, response, ms, count = true)
            else -> { finish(tenantId, id, t.endpointId, "failed", code, error, response, ms, count = true); throw RetryLater(error) }
        }
    }

    private fun finish(tenantId: UUID, id: UUID, endpointId: UUID, status: String, code: Int?, error: String?, response: String?, ms: Int, count: Boolean) =
        db.tenant(tenantId) { j ->
            j.sql("""UPDATE webhook_deliveries SET status = :s, attempts = attempts + CASE WHEN :c THEN 1 ELSE 0 END, last_status_code = :code,
                     last_error = :e, last_response = :r, duration_ms = :ms, delivered_at = CASE WHEN :s = 'succeeded' THEN now() END WHERE id = :id""")
                .param("s", status).param("c", count).param("code", code).param("e", error).param("r", response).param("ms", ms).param("id", id).update()
            when (status) {
                "succeeded" -> j.sql("UPDATE webhook_endpoints SET failure_streak = 0 WHERE id = :e").param("e", endpointId).update()
                // An endpoint that keeps failing is switched off rather than retried forever.
                "dead" -> if (count) j.sql("""UPDATE webhook_endpoints SET failure_streak = failure_streak + 1,
                              is_active = CASE WHEN failure_streak + 1 >= $DISABLE_AFTER THEN false ELSE is_active END,
                              disabled_reason = CASE WHEN failure_streak + 1 >= $DISABLE_AFTER THEN 'Disabled after $DISABLE_AFTER failed deliveries in a row.' ELSE disabled_reason END
                              WHERE id = :e""").param("e", endpointId).update()
            }
        }
}

// ------------------------------------------------------------------------------------------------
// Endpoints (dashboard)
// ------------------------------------------------------------------------------------------------

data class WebhookIn(
    @field:NotBlank @field:Size(max = 2000) val url: String,
    @field:Size(max = 300) val description: String? = null,
    @field:Size(min = 1, max = 50) val events: List<@Size(min = 1, max = 100) String>,
)
data class WebhookPatchIn(
    @field:Size(max = 2000) val url: String? = null,
    @field:Size(max = 300) val description: String? = null,
    @field:Size(min = 1, max = 50) val events: List<@Size(min = 1, max = 100) String>? = null,
    /** true re-enables an endpoint that was switched off after repeated failures. */
    val isActive: Boolean? = null,
)
data class WebhookOut(
    val id: UUID, val url: String, val description: String?, val events: List<String>, val isActive: Boolean, val failureStreak: Int,
    val disabledReason: String?, val createdAt: OffsetDateTime,
    /** Only returned when the endpoint is created or the secret rotated. */
    val secret: String? = null,
)
data class WebhookDeliveryOut(val id: UUID, val eventId: UUID, val eventType: String, val status: String, val attempts: Int, val lastStatusCode: Int?,
                       val lastError: String?, val lastResponse: String?, val durationMs: Int?, val createdAt: OffsetDateTime, val deliveredAt: OffsetDateTime?)

@Service
class WebhookService(
    private val db: Db, private val crypto: Crypto, private val guard: NetGuard, private val events: Events, private val audit: Audit,
) {
    private fun row(rs: java.sql.ResultSet) = WebhookOut(rs.getObject("id", UUID::class.java), rs.getString("url"), rs.getString("description"),
        (rs.getArray("events").array as Array<*>).map { it.toString() }, rs.getBoolean("is_active"), rs.getInt("failure_streak"),
        rs.getString("disabled_reason"), rs.getObject("created_at", OffsetDateTime::class.java))

    private fun find(j: JdbcClient, id: UUID) = j.sql("SELECT * FROM webhook_endpoints WHERE id = :id").param("id", id)
        .query { rs, _ -> row(rs) }.optional().orElseThrow { NotFound("Webhook") }

    private fun checkEvents(list: List<String>) {
        val bad = list.filter { !EventTypes.validPattern(it) }
        if (bad.isNotEmpty()) throw Invalid("Unknown events: ${bad.joinToString()}. See GET /webhooks/event-types.")
    }

    private fun newSecret() = "whsec_" + randomToken(24)

    fun list(p: UserPrincipal) = p.require("webhooks", Action.READ).let {
        db.tenant(p.tenantId) { j -> j.sql("SELECT * FROM webhook_endpoints ORDER BY created_at").query { rs, _ -> row(rs) }.list() }
    }

    fun create(p: UserPrincipal, input: WebhookIn): WebhookOut {
        p.require("webhooks", Action.MANAGE)
        checkEvents(input.events)
        guard.checkUrl(input.url.trim())
        val secret = newSecret()
        return db.tenant(p.tenantId) { j ->
            if (j.sql("SELECT count(*) FROM webhook_endpoints").query(Long::class.java).single() >= 20) throw Invalid("A workspace can have at most 20 webhook endpoints.")
            val id = UUID.randomUUID()
            j.sql("INSERT INTO webhook_endpoints (id, tenant_id, url, description, events, secret_enc) VALUES (:id, :t, :u, :d, :e, :s)")
                .param("id", id).param("t", p.tenantId).param("u", input.url.trim()).param("d", input.description)
                .param("e", input.events.distinct().toTypedArray()).param("s", crypto.encrypt(secret, "webhook:${p.tenantId}:$id")).update()
            audit.record(j, p.tenantId, "webhook.create", "webhooks", id, mapOf("url" to input.url, "events" to input.events))
            find(j, id).copy(secret = secret)
        }
    }

    fun patch(p: UserPrincipal, id: UUID, input: WebhookPatchIn): WebhookOut {
        p.require("webhooks", Action.MANAGE)
        input.events?.let { checkEvents(it) }
        input.url?.let { guard.checkUrl(it.trim()) }
        return db.tenant(p.tenantId) { j ->
            val w = find(j, id)
            j.sql("""UPDATE webhook_endpoints SET url = :u, description = :d, events = :e, is_active = :a,
                     failure_streak = CASE WHEN :a AND NOT is_active THEN 0 ELSE failure_streak END,
                     disabled_reason = CASE WHEN :a THEN NULL ELSE disabled_reason END WHERE id = :id""")
                .param("u", input.url?.trim() ?: w.url).param("d", input.description ?: w.description)
                .param("e", (input.events?.distinct() ?: w.events).toTypedArray()).param("a", input.isActive ?: w.isActive).param("id", id).update()
            audit.record(j, p.tenantId, "webhook.update", "webhooks", id, mapOf("is_active" to input.isActive, "url_changed" to (input.url != null)))
            find(j, id)
        }
    }

    fun rotate(p: UserPrincipal, id: UUID): WebhookOut {
        p.require("webhooks", Action.MANAGE)
        val secret = newSecret()
        return db.tenant(p.tenantId) { j ->
            find(j, id)
            j.sql("UPDATE webhook_endpoints SET secret_enc = :s WHERE id = :id").param("s", crypto.encrypt(secret, "webhook:${p.tenantId}:$id")).param("id", id).update()
            audit.record(j, p.tenantId, "webhook.rotate_secret", "webhooks", id)
            find(j, id).copy(secret = secret)
        }
    }

    fun delete(p: UserPrincipal, id: UUID) {
        p.require("webhooks", Action.MANAGE)
        db.tenant(p.tenantId) { j ->
            if (j.sql("DELETE FROM webhook_endpoints WHERE id = :id").param("id", id).update() == 0) throw NotFound("Webhook")
            audit.record(j, p.tenantId, "webhook.delete", "webhooks", id)
        }
    }

    /** Sends a "ping" event to this endpoint only. */
    fun test(p: UserPrincipal, id: UUID): WebhookDeliveryOut {
        p.require("webhooks", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            find(j, id)
            val ev = events.record(j, p.tenantId, "ping", mapOf("message" to "Test event from TenantCore"))
            val d = events.deliver(j, p.tenantId, id, ev)
            deliveries(j, id, d).single()
        }
    }

    private fun deliveries(j: JdbcClient, endpointId: UUID, only: UUID? = null, limit: Int = 50, offset: Int = 0) =
        j.sql("""SELECT d.*, ev.type FROM webhook_deliveries d JOIN webhook_events ev ON ev.id = d.event_id WHERE d.endpoint_id = :e
                 ${if (only != null) "AND d.id = :d" else ""} ORDER BY d.created_at DESC LIMIT :l OFFSET :o""")
            .param("e", endpointId).apply { if (only != null) param("d", only) }.param("l", limit).param("o", offset)
            .query { rs, _ -> WebhookDeliveryOut(rs.getObject("id", UUID::class.java), rs.getObject("event_id", UUID::class.java), rs.getString("type"),
                rs.getString("status"), rs.getInt("attempts"), rs.getObject("last_status_code") as Int?, rs.getString("last_error"),
                rs.getString("last_response"), rs.getObject("duration_ms") as Int?, rs.getObject("created_at", OffsetDateTime::class.java),
                rs.getObject("delivered_at", OffsetDateTime::class.java)) }.list()

    fun listDeliveries(p: UserPrincipal, id: UUID, limit: Int, offset: Int) = p.require("webhooks", Action.READ).let {
        db.tenant(p.tenantId) { j -> find(j, id); deliveries(j, id, limit = limit, offset = offset) }
    }

    /** Sends an event again as a new delivery (e.g. after fixing the receiving server). */
    fun redeliver(p: UserPrincipal, deliveryId: UUID): WebhookDeliveryOut {
        p.require("webhooks", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            val (endpoint, event) = j.sql("SELECT endpoint_id, event_id FROM webhook_deliveries WHERE id = :id").param("id", deliveryId)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }.optional().orElseThrow { NotFound("Delivery") }
            val d = events.deliver(j, p.tenantId, endpoint, event)
            audit.record(j, p.tenantId, "webhook.redeliver", "webhooks", endpoint, mapOf("delivery_id" to deliveryId))
            deliveries(j, endpoint, d).single()
        }
    }

    fun eventTypes(p: UserPrincipal): Map<String, Any> {
        p.require("webhooks", Action.READ)
        val tables = db.tenant(p.tenantId) { j -> j.sql("SELECT key FROM data_tables ORDER BY key").query(String::class.java).list() }
        return mapOf("items" to EventTypes.fixed + tables.flatMap { t -> listOf("created", "updated", "deleted").map { "data.$t.$it" } },
            "wildcards" to listOf("*", "order.*", "payment.*", "return.*", "product.*", "customer.*", "delivery.*", "data.*", "data.<table>.*"))
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/webhooks")
@Validated
class WebhookController(private val webhooks: WebhookService) {
    @GetMapping fun list() = mapOf("items" to webhooks.list(Caller.user()))
    @GetMapping("/event-types") fun types() = webhooks.eventTypes(Caller.user())
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@Valid @RequestBody body: WebhookIn) = webhooks.create(Caller.user(), body)
    @PatchMapping("/{id}") fun patch(@PathVariable id: UUID, @Valid @RequestBody body: WebhookPatchIn) = webhooks.patch(Caller.user(), id, body)
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun delete(@PathVariable id: UUID) = webhooks.delete(Caller.user(), id)
    @PostMapping("/{id}/rotate-secret") fun rotate(@PathVariable id: UUID) = webhooks.rotate(Caller.user(), id)
    @PostMapping("/{id}/test") fun test(@PathVariable id: UUID) = webhooks.test(Caller.user(), id)
    @GetMapping("/{id}/deliveries")
    fun deliveries(@PathVariable id: UUID, @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
                   @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = mapOf("items" to webhooks.listDeliveries(Caller.user(), id, limit, offset))
    @PostMapping("/deliveries/{deliveryId}/redeliver") fun redeliver(@PathVariable deliveryId: UUID) = webhooks.redeliver(Caller.user(), deliveryId)
}
