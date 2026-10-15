package com.tenantcore.integrations

import com.tenantcore.audit.Audit
import com.tenantcore.commerce.Actor
import com.tenantcore.commerce.OrderService
import com.tenantcore.common.Conflict
import com.tenantcore.common.Forbidden
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.RateLimiter
import com.tenantcore.security.RequestInfo
import com.tenantcore.security.UserPrincipal
import com.tenantcore.security.constantTimeEquals
import com.tenantcore.tenants.ModuleService
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Public tracking links: an HMAC of the delivery id, so nothing is stored and links cannot be guessed. */
@Component
class TrackingTokens(props: TcProperties) {
    private val key = SecretKeySpec(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(props.encryptionKeyBytes(), "HmacSHA256")); doFinal("tenantcore/delivery-tracking/v1".toByteArray())
    }, "HmacSHA256")

    fun token(tenantId: UUID, deliveryId: UUID): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal("$tenantId:$deliveryId".toByteArray()))
}

data class DeliveryIn(
    val orderId: UUID,
    val driverUserId: UUID? = null,
    @field:DecimalMin("-90") @field:DecimalMax("90") val destinationLat: Double? = null,
    @field:DecimalMin("-180") @field:DecimalMax("180") val destinationLng: Double? = null,
    @field:Size(max = 500) val destinationLabel: String? = null,
    @field:Size(max = 1000) val note: String? = null,
)
data class DeliveryPatchIn(
    val driverUserId: UUID? = null,
    val unassignDriver: Boolean? = null,
    @field:Pattern(regexp = "^(assigned|picked_up|in_transit|delivered|failed|cancelled)$", message = "unknown status") val status: String? = null,
    @field:DecimalMin("-90") @field:DecimalMax("90") val destinationLat: Double? = null,
    @field:DecimalMin("-180") @field:DecimalMax("180") val destinationLng: Double? = null,
    @field:Size(max = 500) val destinationLabel: String? = null,
    @field:Size(max = 1000) val note: String? = null,
    /** A photo or signature uploaded as a file. */
    val proofFileId: UUID? = null,
)
data class PingIn(
    @field:DecimalMin("-90") @field:DecimalMax("90") val lat: Double,
    @field:DecimalMin("-180") @field:DecimalMax("180") val lng: Double,
    @field:DecimalMin("0") @field:DecimalMax("100000") val accuracyM: Double? = null,
    @field:DecimalMin("0") @field:DecimalMax("200") val speedMps: Double? = null,
    @field:DecimalMin("0") @field:DecimalMax("359.999") val heading: Double? = null,
    val recordedAt: OffsetDateTime,
)
data class PingsIn(@field:Size(min = 1, max = 100) val pings: List<@Valid PingIn>)

data class LatLng(val lat: Double, val lng: Double)
data class Located(val lat: Double, val lng: Double, val accuracyM: Double?, val at: OffsetDateTime?)
data class DeliveryOut(
    val id: UUID, val orderId: UUID, val orderNumber: Long, val driverUserId: UUID?, val status: String, val destination: LatLng?,
    val destinationLabel: String?, val note: String?, val lastLocation: Located?, val proofFileId: UUID?, val trackingToken: String,
    val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime, val deliveredAt: OffsetDateTime?, val recentPings: List<Located>? = null,
)

/**
 * Deliveries and live location. Drivers are workspace users; a driver may update the deliveries
 * assigned to them, anyone with deliveries:update may update any. Customers follow a delivery through a
 * signed public link that shows the driver's position only while the goods are on the way.
 */
@Service
class DeliveryService(
    private val db: Db, private val audit: Audit, private val events: Events, private val tokens: TrackingTokens, private val orders: OrderService,
    private val modules: ModuleService, private val limiter: RateLimiter,
) {
    private val next = mapOf(
        "assigned" to setOf("picked_up", "failed", "cancelled"),
        "picked_up" to setOf("in_transit", "delivered", "failed", "cancelled"),
        "in_transit" to setOf("delivered", "failed", "cancelled"),
        "failed" to setOf("assigned", "cancelled"),
    )
    private val moving = setOf("picked_up", "in_transit")

    private fun row(tenantId: UUID, rs: java.sql.ResultSet): DeliveryOut {
        val id = rs.getObject("id", UUID::class.java)
        val dLat = rs.getObject("destination_lat") as Double?; val dLng = rs.getObject("destination_lng") as Double?
        val lLat = rs.getObject("last_lat") as Double?; val lLng = rs.getObject("last_lng") as Double?
        return DeliveryOut(id, rs.getObject("order_id", UUID::class.java), rs.getLong("order_number"), rs.getObject("driver_user_id", UUID::class.java),
            rs.getString("status"), if (dLat != null && dLng != null) LatLng(dLat, dLng) else null, rs.getString("destination_label"), rs.getString("note"),
            if (lLat != null && lLng != null) Located(lLat, lLng, (rs.getObject("last_accuracy_m") as Float?)?.toDouble(), rs.getObject("last_ping_at", OffsetDateTime::class.java)) else null,
            rs.getObject("proof_file_id", UUID::class.java), tokens.token(tenantId, id), rs.getObject("created_at", OffsetDateTime::class.java),
            rs.getObject("updated_at", OffsetDateTime::class.java), rs.getObject("delivered_at", OffsetDateTime::class.java))
    }

    private val select = "SELECT d.*, o.number AS order_number FROM deliveries d JOIN orders o ON o.id = d.order_id"

    private fun find(j: JdbcClient, tenantId: UUID, id: UUID, lock: Boolean = false): DeliveryOut =
        j.sql("$select WHERE d.id = :id ${if (lock) "FOR UPDATE OF d" else ""}").param("id", id).query { rs, _ -> row(tenantId, rs) }.optional()
            .orElseThrow { NotFound("Delivery") }

    private fun checkDriver(j: JdbcClient, id: UUID) {
        if (j.sql("SELECT count(*) FROM app_users WHERE id = :id AND is_active").param("id", id).query(Long::class.java).single() == 0L)
            throw Invalid("The driver must be an active user of this workspace.")
    }

    /** May this user change this delivery? Its assigned driver, or anyone with deliveries:update. */
    private fun canChange(p: UserPrincipal, d: DeliveryOut) = p.can("deliveries", Action.UPDATE) || (d.driverUserId == p.userId && p.can("deliveries", Action.READ))

    private fun emit(j: JdbcClient, tenantId: UUID, type: String, d: DeliveryOut, from: String? = null) =
        events.emit(j, tenantId, type, mapOf("id" to d.id, "order_id" to d.orderId, "order_number" to d.orderNumber, "status" to d.status, "from" to from,
            "driver_user_id" to d.driverUserId, "tracking_token" to d.trackingToken))

    fun create(p: UserPrincipal, input: DeliveryIn): DeliveryOut {
        p.require("deliveries", Action.CREATE)
        if ((input.destinationLat == null) != (input.destinationLng == null)) throw Invalid("Send both destination_lat and destination_lng, or neither.")
        return db.tenant(p.tenantId) { j ->
            if (j.sql("SELECT count(*) FROM orders WHERE id = :o").param("o", input.orderId).query(Long::class.java).single() == 0L) throw Invalid("Order not found.")
            input.driverUserId?.let { checkDriver(j, it) }
            val id = j.sql("""INSERT INTO deliveries (tenant_id, order_id, driver_user_id, destination_lat, destination_lng, destination_label, note, created_by)
                              VALUES (:t, :o, :d, :la, :ln, :lb, :n, :u) RETURNING id""")
                .param("t", p.tenantId).param("o", input.orderId).param("d", input.driverUserId).param("la", input.destinationLat).param("ln", input.destinationLng)
                .param("lb", input.destinationLabel).param("n", input.note).param("u", p.userId).query(UUID::class.java).single()
            audit.record(j, p.tenantId, "delivery.create", "deliveries", id, mapOf("order_id" to input.orderId, "driver" to input.driverUserId))
            find(j, p.tenantId, id).also { emit(j, p.tenantId, "delivery.created", it) }
        }
    }

    fun list(p: UserPrincipal, status: String?, driver: UUID?, mine: Boolean, orderId: UUID?, limit: Int, offset: Int): Map<String, Any> {
        p.require("deliveries", Action.READ)
        return db.tenant(p.tenantId) { j ->
            val drv = if (mine) p.userId else driver
            val where = listOfNotNull(status?.let { "d.status = :s" }, drv?.let { "d.driver_user_id = :dr" }, orderId?.let { "d.order_id = :o" })
                .let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
            fun bind(s: JdbcClient.StatementSpec) = s.apply { status?.let { param("s", it) }; drv?.let { param("dr", it) }; orderId?.let { param("o", it) } }
            mapOf("items" to bind(j.sql("$select $where ORDER BY d.created_at DESC LIMIT :l OFFSET :off")).param("l", limit).param("off", offset)
                .query { rs, _ -> row(p.tenantId, rs) }.list(),
                "total" to bind(j.sql("SELECT count(*) FROM deliveries d $where")).query(Long::class.java).single())
        }
    }

    fun get(p: UserPrincipal, id: UUID): DeliveryOut {
        p.require("deliveries", Action.READ)
        return db.tenant(p.tenantId) { j ->
            find(j, p.tenantId, id).copy(recentPings = j.sql("""SELECT lat, lng, accuracy_m, recorded_at FROM delivery_pings WHERE delivery_id = :d
                                                               ORDER BY recorded_at DESC LIMIT 200""").param("d", id)
                .query { rs, _ -> Located(rs.getDouble(1), rs.getDouble(2), (rs.getObject(3) as Float?)?.toDouble(), rs.getObject(4, OffsetDateTime::class.java)) }.list())
        }
    }

    fun patch(p: UserPrincipal, id: UUID, input: DeliveryPatchIn): DeliveryOut {
        p.require("deliveries", Action.READ)
        return db.tenant(p.tenantId) { j ->
            val d = find(j, p.tenantId, id, lock = true)
            if (!canChange(p, d)) throw Forbidden("Only the assigned driver or someone with deliveries:update can change this delivery.")
            val managing = input.driverUserId != null || input.unassignDriver == true || input.destinationLat != null || input.destinationLng != null ||
                input.destinationLabel != null
            if (managing && !p.can("deliveries", Action.UPDATE)) throw Forbidden("Changing the driver or destination needs deliveries:update.")
            if ((input.destinationLat == null) != (input.destinationLng == null)) throw Invalid("Send both destination_lat and destination_lng, or neither.")
            input.driverUserId?.let { checkDriver(j, it) }
            input.proofFileId?.let {
                if (j.sql("SELECT count(*) FROM files WHERE id = :f").param("f", it).query(Long::class.java).single() == 0L) throw Invalid("File not found.")
            }
            val status = input.status?.takeIf { it != d.status }
            if (status != null && status !in next[d.status].orEmpty())
                throw Conflict("A delivery that is ${d.status} cannot become $status. Allowed: ${next[d.status].orEmpty().joinToString().ifEmpty { "none" }}.")
            j.sql("""UPDATE deliveries SET driver_user_id = :dr, status = :s, destination_lat = :la, destination_lng = :ln, destination_label = :lb,
                     note = :n, proof_file_id = :pf, delivered_at = CASE WHEN :s = 'delivered' THEN now() ELSE delivered_at END WHERE id = :id""")
                .param("dr", if (input.unassignDriver == true) null else input.driverUserId ?: d.driverUserId).param("s", status ?: d.status)
                .param("la", input.destinationLat ?: d.destination?.lat).param("ln", input.destinationLng ?: d.destination?.lng)
                .param("lb", input.destinationLabel ?: d.destinationLabel).param("n", input.note ?: d.note).param("pf", input.proofFileId ?: d.proofFileId)
                .param("id", id).update()
            audit.record(j, p.tenantId, "delivery.update", "deliveries", id, mapOf("status" to status, "driver" to input.driverUserId))
            val after = find(j, p.tenantId, id)
            if (status != null) {
                emit(j, p.tenantId, "delivery.status_changed", after, d.status)
                orderFollows(j, p, after, status)
            }
            after
        }
    }

    /** The order can follow the delivery (e.g. delivered → delivered), as set in the module options. */
    private fun orderFollows(j: JdbcClient, p: UserPrincipal, d: DeliveryOut, status: String) {
        val cfg = modules.config(j, "delivery_tracking")
        val target = when (status) {
            "picked_up" -> cfg["order_status_on_picked_up"] as? String
            "delivered" -> if (cfg.containsKey("order_status_on_delivered")) cfg["order_status_on_delivered"] as? String else "delivered"
            else -> null
        } ?: return
        val current = j.sql("SELECT status FROM orders WHERE id = :o").param("o", d.orderId).query(String::class.java).single()
        if (current != target && target in orders.lifecycle(j, p.tenantId).next(current))
            orders.transition(j, p.tenantId, d.orderId, target, "Delivery ${d.status}", Actor.of(p))
    }

    /** Location updates from the driver's phone, in batches (phones buffer while offline). */
    fun pings(p: UserPrincipal, id: UUID, input: PingsIn): Map<String, Any?> {
        p.require("deliveries", Action.READ)
        limiter.hit("pings:$id", 120)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        input.pings.forEach {
            if (it.recordedAt.isAfter(now.plusMinutes(2))) throw Invalid("recorded_at is in the future; check the phone's clock.")
            if (it.recordedAt.isBefore(now.minusHours(24))) throw Invalid("recorded_at is more than 24 hours old.")
        }
        return db.tenant(p.tenantId) { j ->
            val d = find(j, p.tenantId, id, lock = true)
            if (!canChange(p, d)) throw Forbidden("Only the assigned driver can send locations for this delivery.")
            if (d.status !in moving) throw Conflict("Locations are recorded only while a delivery is picked up or in transit (it is ${d.status}).")
            input.pings.forEach { pg ->
                j.sql("""INSERT INTO delivery_pings (tenant_id, delivery_id, lat, lng, accuracy_m, speed_mps, heading, recorded_at)
                         VALUES (:t, :d, :la, :ln, :a, :s, :h, :r)""")
                    .param("t", p.tenantId).param("d", id).param("la", pg.lat).param("ln", pg.lng).param("a", pg.accuracyM?.toFloat())
                    .param("s", pg.speedMps?.toFloat()).param("h", pg.heading?.toFloat()).param("r", pg.recordedAt).update()
            }
            val latest = input.pings.maxBy { it.recordedAt }
            j.sql("""UPDATE deliveries SET last_lat = :la, last_lng = :ln, last_accuracy_m = :a, last_ping_at = :r
                     WHERE id = :id AND (last_ping_at IS NULL OR last_ping_at < :r)""")
                .param("la", latest.lat).param("ln", latest.lng).param("a", latest.accuracyM?.toFloat()).param("r", latest.recordedAt).param("id", id).update()
            mapOf("accepted" to input.pings.size, "last_location" to find(j, p.tenantId, id).lastLocation)
        }
    }

    // -- public tracking ---------------------------------------------------------------------------

    fun track(slug: String, id: UUID, token: String): Map<String, Any?> {
        limiter.hit("track:${RequestInfo.clientIp() ?: "unknown"}", 120)
        val tenantId = db.anonymous { j ->
            j.sql("SELECT id FROM tenants WHERE slug = :s AND status = 'active'").param("s", slug).query(UUID::class.java).optional().orElse(null)
        } ?: throw NotFound("Delivery")
        if (!constantTimeEquals(tokens.token(tenantId, id), token)) throw NotFound("Delivery")
        return db.tenant(tenantId) { j ->
            if (j.sql("SELECT count(*) FROM tenant_modules WHERE module = 'delivery_tracking' AND enabled").query(Long::class.java).single() == 0L) throw NotFound("Delivery")
            val d = find(j, tenantId, id)
            mapOf("id" to d.id, "order_number" to d.orderNumber, "status" to d.status, "destination_label" to d.destinationLabel,
                // Where the driver is, only while the goods are on their way.
                "location" to if (d.status in moving) d.lastLocation else null, "delivered_at" to d.deliveredAt, "updated_at" to d.updatedAt)
        }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/deliveries")
@Validated
class DeliveryController(private val deliveries: DeliveryService) {
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@Valid @RequestBody body: DeliveryIn) = deliveries.create(Caller.user(), body)
    @GetMapping
    fun list(@RequestParam(required = false) @Pattern(regexp = "^(assigned|picked_up|in_transit|delivered|failed|cancelled)$") status: String?,
             @RequestParam(name = "driver_user_id", required = false) driver: UUID?,
             @RequestParam(defaultValue = "false") mine: Boolean,
             @RequestParam(name = "order_id", required = false) orderId: UUID?,
             @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
             @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = deliveries.list(Caller.user(), status, driver, mine, orderId, limit, offset)
    @GetMapping("/{id}") fun get(@PathVariable id: UUID) = deliveries.get(Caller.user(), id)
    @PatchMapping("/{id}") fun patch(@PathVariable id: UUID, @Valid @RequestBody body: DeliveryPatchIn) = deliveries.patch(Caller.user(), id, body)
    @PostMapping("/{id}/pings") fun pings(@PathVariable id: UUID, @Valid @RequestBody body: PingsIn) = deliveries.pings(Caller.user(), id, body)
}

@RestController
@RequestMapping("\${tc.api-prefix}/public/shop/{slug}/deliveries")
class DeliveryTrackingController(private val deliveries: DeliveryService) {
    @GetMapping("/{id}") fun track(@PathVariable slug: String, @PathVariable id: UUID, @RequestParam token: String) = deliveries.track(slug, id, token)
}
