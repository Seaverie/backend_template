package com.tenantcore.integrations

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tenantcore.audit.Audit
import com.tenantcore.common.ApiException
import com.tenantcore.common.Invalid
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.platform.PlatformSettingsStore
import com.tenantcore.security.Action
import com.tenantcore.security.RateLimiter
import com.tenantcore.security.UserPrincipal
import com.tenantcore.security.sha256Hex
import com.tenantcore.tenants.ModuleService
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ------------------------------------------------------------------------------------------------
// Maps
// ------------------------------------------------------------------------------------------------

data class Place(val label: String, val lat: Double, val lng: Double, val countryCode: String?)
data class MapsSettingsIn(
    @field:Pattern(regexp = "^(nominatim|google)$", message = "nominatim or google") val provider: String? = null,
    /** Google Maps API key. Write-only; "" removes it. */
    @field:Size(max = 200) val googleApiKey: String? = null,
    @field:Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$", message = "a language like th or en") val language: String? = null,
)
data class MapsSettingsOut(val provider: String, val googleKeySet: Boolean, val language: String?)
data class Point(@field:DecimalMin("-90") @field:DecimalMax("90") val lat: Double, @field:DecimalMin("-180") @field:DecimalMax("180") val lng: Double)
data class DistanceIn(@field:Valid val from: Point, @field:Valid val to: Point)

/**
 * Geocoding through OpenStreetMap Nominatim (no key, light use only, per its usage policy) or the
 * Google Geocoding API (the tenant's own key). Provider addresses are platform configuration.
 */
@Component
class Geocoder(private val props: TcProperties, private val mapper: ObjectMapper, private val platform: PlatformSettingsStore) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun get(url: String): com.fasterxml.jackson.databind.JsonNode {
        val res = try {
            http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "TenantCore/1.0 (+${platform.general(props).publicUrl})").GET().build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) { throw ApiException(HttpStatus.BAD_GATEWAY, "maps_unavailable", "The map service could not be reached.") }
        if (res.statusCode() != 200) throw ApiException(HttpStatus.BAD_GATEWAY, "maps_unavailable", "The map service answered HTTP ${res.statusCode()}.")
        return mapper.readTree(res.body())
    }

    private fun google(node: com.fasterxml.jackson.databind.JsonNode): List<Place> {
        when (node.path("status").asText()) {
            "OK", "ZERO_RESULTS" -> Unit
            "REQUEST_DENIED" -> throw ApiException(HttpStatus.BAD_GATEWAY, "maps_unavailable", "Google refused the request; check the API key.")
            else -> throw ApiException(HttpStatus.BAD_GATEWAY, "maps_unavailable", "Google answered ${node.path("status").asText()}.")
        }
        return node.path("results").map { r ->
            Place(r.path("formatted_address").asText(), r.path("geometry").path("location").path("lat").asDouble(), r.path("geometry").path("location").path("lng").asDouble(),
                r.path("address_components").firstOrNull { c -> c.path("types").any { it.asText() == "country" } }?.path("short_name")?.asText()?.lowercase())
        }
    }

    fun search(provider: String, key: String?, q: String, limit: Int, lang: String?): List<Place> = when (provider) {
        "google" -> google(get("${props.maps.googleUrl.trimEnd('/')}/maps/api/geocode/json?address=${enc(q)}&key=${enc(key ?: "")}${lang?.let { "&language=$it" } ?: ""}")).take(limit)
        else -> get("${props.maps.nominatimUrl.trimEnd('/')}/search?q=${enc(q)}&format=jsonv2&addressdetails=1&limit=$limit${lang?.let { "&accept-language=$it" } ?: ""}")
            .map { Place(it.path("display_name").asText(), it.path("lat").asText().toDouble(), it.path("lon").asText().toDouble(), it.path("address").path("country_code").asText(null)) }
    }

    fun reverse(provider: String, key: String?, lat: Double, lng: Double, lang: String?): Place? = when (provider) {
        "google" -> google(get("${props.maps.googleUrl.trimEnd('/')}/maps/api/geocode/json?latlng=$lat,$lng&key=${enc(key ?: "")}${lang?.let { "&language=$it" } ?: ""}")).firstOrNull()
        else -> get("${props.maps.nominatimUrl.trimEnd('/')}/reverse?lat=$lat&lon=$lng&format=jsonv2${lang?.let { "&accept-language=$it" } ?: ""}")
            .takeIf { it.has("display_name") }
            ?.let { Place(it.path("display_name").asText(), it.path("lat").asText().toDouble(), it.path("lon").asText().toDouble(), it.path("address").path("country_code").asText(null)) }
    }
}

@Service
class MapService(
    private val db: Db, private val geocoder: Geocoder, private val secrets: TenantSecrets, private val modules: ModuleService, private val mapper: ObjectMapper,
    private val limiter: RateLimiter, private val props: TcProperties, private val audit: Audit,
) {
    private fun settings(j: JdbcClient): MapsSettingsOut {
        val c = modules.config(j, "maps")
        return MapsSettingsOut(c["provider"] as? String ?: "nominatim", secrets.has(j, "google_maps_key"), c["language"] as? String)
    }

    fun getSettings(p: UserPrincipal) = p.require("maps", Action.READ).let { db.tenant(p.tenantId) { settings(it) } }

    fun setSettings(p: UserPrincipal, input: MapsSettingsIn): MapsSettingsOut {
        p.require("maps", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            input.googleApiKey?.let { secrets.set(j, p.tenantId, "google_maps_key", it.ifEmpty { null }) }
            val c = modules.config(j, "maps").toMutableMap()
            input.provider?.let { c["provider"] = it }
            input.language?.let { c["language"] = it }
            if (c["provider"] == "google" && !secrets.has(j, "google_maps_key")) throw Invalid("Add a Google Maps API key to use Google.")
            modules.setConfig(j, p.tenantId, "maps", c, mapper)
            audit.record(j, p.tenantId, "maps.settings", "maps", null, mapOf("provider" to c["provider"], "key_changed" to (input.googleApiKey != null)))
            settings(j)
        }
    }

    /** Results are cached per tenant; only cache misses count against the per-tenant provider limit. */
    private fun <T> cached(p: UserPrincipal, kind: String, query: String, type: com.fasterxml.jackson.core.type.TypeReference<T>, fetch: (String, String?, String?) -> T): T {
        val (s, key) = db.tenant(p.tenantId) { j -> settings(j) to secrets.get(j, p.tenantId, "google_maps_key") }
        val hash = sha256Hex("${s.language}|$query")
        db.tenant(p.tenantId) { j ->
            j.sql("SELECT result::text FROM geocode_cache WHERE provider = :p AND kind = :k AND query_hash = :h AND created_at > now() - make_interval(days => :d)")
                .param("p", s.provider).param("k", kind).param("h", hash).param("d", props.maps.cacheDays).query(String::class.java).optional().orElse(null)
        }?.let { return mapper.readValue(it, type) }
        limiter.hit("maps:${p.tenantId}", props.maps.providerCallsPerMin)
        val result = fetch(s.provider, key, s.language)
        db.tenant(p.tenantId) { j ->
            j.sql("""INSERT INTO geocode_cache (tenant_id, provider, kind, query_hash, result) VALUES (:t, :p, :k, :h, CAST(:r AS jsonb))
                     ON CONFLICT (tenant_id, provider, kind, query_hash) DO UPDATE SET result = EXCLUDED.result, created_at = now()""")
                .param("t", p.tenantId).param("p", s.provider).param("k", kind).param("h", hash).param("r", mapper.writeValueAsString(result)).update()
        }
        return result
    }

    fun geocode(p: UserPrincipal, q: String, limit: Int): List<Place> {
        p.require("maps", Action.READ)
        val query = q.trim().replace(Regex("\\s+"), " ")
        if (query.length < 3) throw Invalid("Type at least 3 characters.")
        return cached(p, "search", "${query.lowercase()}|$limit", object : com.fasterxml.jackson.core.type.TypeReference<List<Place>>() {}) { prov, key, lang ->
            geocoder.search(prov, key, query, limit, lang)
        }
    }

    fun reverse(p: UserPrincipal, lat: Double, lng: Double): Place? {
        p.require("maps", Action.READ)
        // About 1 m precision is plenty for an address and makes nearby lookups share the cache.
        val la = "%.5f".format(java.util.Locale.ROOT, lat).toDouble(); val ln = "%.5f".format(java.util.Locale.ROOT, lng).toDouble()
        return cached(p, "reverse", "$la,$ln", object : com.fasterxml.jackson.core.type.TypeReference<Place?>() {}) { prov, key, lang ->
            geocoder.reverse(prov, key, la, ln, lang)
        }
    }

    /** Straight-line (great-circle) distance; road distance needs a routing service. */
    fun distance(p: UserPrincipal, input: DistanceIn): Map<String, Any> {
        p.require("maps", Action.READ)
        val r = 6371.0088
        fun rad(d: Double) = Math.toRadians(d)
        val a = sin(rad(input.to.lat - input.from.lat) / 2).pow(2) +
            cos(rad(input.from.lat)) * cos(rad(input.to.lat)) * sin(rad(input.to.lng - input.from.lng) / 2).pow(2)
        val km = 2 * r * asin(sqrt(a))
        return mapOf("km" to Math.round(km * 1000) / 1000.0, "kind" to "straight_line")
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/maps")
@Validated
class MapController(private val maps: MapService) {
    @GetMapping("/settings") fun settings() = maps.getSettings(Caller.user())
    @PutMapping("/settings") fun setSettings(@Valid @RequestBody body: MapsSettingsIn) = maps.setSettings(Caller.user(), body)
    @GetMapping("/geocode")
    fun geocode(@RequestParam @NotBlank @Size(max = 300) q: String, @RequestParam(defaultValue = "5") @Min(1) @Max(10) limit: Int) =
        mapOf("items" to maps.geocode(Caller.user(), q, limit))
    @GetMapping("/reverse")
    fun reverse(@RequestParam @DecimalMin("-90") @DecimalMax("90") lat: Double, @RequestParam @DecimalMin("-180") @DecimalMax("180") lng: Double) =
        mapOf("place" to maps.reverse(Caller.user(), lat, lng))
    @PostMapping("/distance") fun distance(@Valid @RequestBody body: DistanceIn) = maps.distance(Caller.user(), body)
}

// ------------------------------------------------------------------------------------------------
// QR codes
// ------------------------------------------------------------------------------------------------

object Qr {
    fun matrix(data: String, size: Int, margin: Int, ecc: String): BitMatrix =
        QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to margin,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.valueOf(ecc)))

    fun png(m: BitMatrix): ByteArray {
        val img = java.awt.image.BufferedImage(m.width, m.height, java.awt.image.BufferedImage.TYPE_BYTE_BINARY)
        for (y in 0 until m.height) for (x in 0 until m.width) img.setRGB(x, y, if (m[x, y]) 0x000000 else 0xFFFFFF)
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** One path of unit squares; contains nothing from the encoded text. */
    fun svg(m: BitMatrix): String {
        val path = StringBuilder()
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) path.append("M$x ${y}h1v1h-1z")
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${m.width} ${m.height}" width="${m.width}" height="${m.height}" shape-rendering="crispEdges">""" +
            """<rect width="100%" height="100%" fill="#fff"/><path fill="#000" d="$path"/></svg>"""
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/qr")
@Validated
class QrController(private val modules: ModuleService) {
    @GetMapping
    fun qr(@RequestParam @NotBlank @Size(max = 2000) data: String,
           @RequestParam(defaultValue = "png") @Pattern(regexp = "^(png|svg)$") format: String,
           @RequestParam(defaultValue = "320") @Min(64) @Max(2048) size: Int,
           @RequestParam(defaultValue = "2") @Min(0) @Max(10) margin: Int,
           @RequestParam(defaultValue = "M") @Pattern(regexp = "^(L|M|Q|H)$") ecc: String): ResponseEntity<ByteArray> {
        Caller.user().requireModule("qr_codes")
        val m = runCatching { Qr.matrix(data, size, margin, ecc) }.getOrElse { throw Invalid("This text is too long for a QR code at that error correction level.") }
        val (type, bytes) = if (format == "svg") "image/svg+xml" to Qr.svg(m).toByteArray() else MediaType.IMAGE_PNG_VALUE to Qr.png(m)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
            .header("Cache-Control", "private, max-age=3600").body(bytes)
    }
}


// ------------------------------------------------------------------------------------------------
// PromptPay (Thai QR payments)
// ------------------------------------------------------------------------------------------------

/**
 * Builds a PromptPay payload (EMVCo merchant-presented QR, as Thai banking apps read it). The
 * customer's bank app fills in the payee and, for orders, the amount. Payment confirmation is not
 * automatic: there is no callback from the banks, so staff record the payment once it arrives.
 */
object PromptPay {
    private fun field(id: String, value: String) = id + "%02d".format(value.length) + value

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), as the EMVCo QR specification requires. */
    fun crc16(s: String): String {
        var crc = 0xFFFF
        for (b in s.toByteArray(Charsets.US_ASCII)) {
            crc = crc xor ((b.toInt() and 0xff) shl 8)
            repeat(8) { crc = (if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1) and 0xFFFF }
        }
        return "%04X".format(crc)
    }

    /** Phone number (10 digits from 0), national/tax id (13 digits) or e-wallet id (15 digits). */
    fun target(id: String): Pair<String, String> {
        val d = id.filter { it.isDigit() }
        return when {
            d.length == 10 && d.startsWith("0") -> "01" to ("0066" + d.drop(1))
            d.length == 13 -> "02" to d
            d.length == 15 -> "03" to d
            else -> throw Invalid("A PromptPay id is a mobile number (10 digits), a national or tax id (13 digits) or an e-wallet id (15 digits).")
        }
    }

    fun payload(id: String, amount: java.math.BigDecimal?): String {
        val (tag, value) = target(id)
        val body = field("00", "01") + field("01", if (amount != null) "12" else "11") +
            field("29", field("00", "A000000677010111") + field(tag, value)) +
            field("58", "TH") + field("53", "764") +
            (amount?.let { field("54", it.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString()) } ?: "") + "6304"
        return body + crc16(body)
    }
}

data class QrSettingsIn(
    /** "" removes it. */
    @field:Size(max = 20) val promptpayId: String? = null,
    @field:Size(max = 100) val promptpayName: String? = null,
)
data class QrSettingsOut(val promptpayId: String?, val promptpayName: String?)

@Service
class PromptPayService(
    private val db: Db, private val modules: ModuleService, private val mapper: ObjectMapper, private val audit: Audit,
    private val orders: com.tenantcore.commerce.OrderService, private val storefront: com.tenantcore.commerce.StorefrontService,
    private val limiter: RateLimiter,
) {
    fun settings(j: JdbcClient): QrSettingsOut = modules.config(j, "qr_codes").let { QrSettingsOut(it["promptpay_id"] as? String, it["promptpay_name"] as? String) }

    fun getSettings(p: UserPrincipal) = p.requireModule("qr_codes").let { db.tenant(p.tenantId) { settings(it) } }

    fun setSettings(p: UserPrincipal, input: QrSettingsIn): QrSettingsOut {
        p.requireModule("qr_codes"); p.require("settings", Action.MANAGE)
        input.promptpayId?.takeIf { it.isNotEmpty() }?.let { PromptPay.target(it) }
        return db.tenant(p.tenantId) { j ->
            val c = modules.config(j, "qr_codes").toMutableMap()
            input.promptpayId?.let { c["promptpay_id"] = it.filter { ch -> ch.isDigit() }.ifEmpty { null } }
            input.promptpayName?.let { c["promptpay_name"] = it.ifEmpty { null } }
            modules.setConfig(j, p.tenantId, "qr_codes", c, mapper)
            audit.record(j, p.tenantId, "qr.settings", "settings", null, mapOf("promptpay_set" to (c["promptpay_id"] != null)))
            settings(j)
        }
    }

    /** The amount still due on an order, as a PromptPay payload. Thai baht orders only. */
    fun forOrder(j: JdbcClient, tenantId: UUID, orderId: UUID): Map<String, Any?> {
        if (j.sql("SELECT count(*) FROM tenant_modules WHERE module = 'qr_codes' AND enabled").query(Long::class.java).single() == 0L)
            throw com.tenantcore.common.Forbidden("The QR codes module is not enabled for this workspace.")
        val s = settings(j)
        val id = s.promptpayId ?: throw com.tenantcore.common.Conflict("Set the shop's PromptPay id first (QR settings).")
        val o = orders.detail(j, tenantId, orderId)
        if (o.currency != "THB") throw com.tenantcore.common.Conflict("PromptPay is for orders in Thai baht (this order is in ${o.currency}).")
        if (o.balanceDue.signum() <= 0) throw com.tenantcore.common.Conflict("Nothing is due on this order.")
        return mapOf("payload" to PromptPay.payload(id, o.balanceDue), "amount" to o.balanceDue, "currency" to "THB", "payee_name" to s.promptpayName,
            "order_number" to o.number)
    }

    fun staff(p: UserPrincipal, orderId: UUID) = p.require("orders", Action.READ).let { db.tenant(p.tenantId) { forOrder(it, p.tenantId, orderId) } }

    fun public(slug: String, orderId: UUID, token: String): Map<String, Any?> {
        limiter.hit("promptpay:${com.tenantcore.security.RequestInfo.clientIp() ?: "unknown"}", 60)
        val tenantId = db.anonymous { j -> j.sql("SELECT id FROM tenants WHERE slug = :s AND status = 'active'").param("s", slug).query(UUID::class.java).optional().orElse(null) }
            ?: throw com.tenantcore.common.NotFound("Order")
        if (!com.tenantcore.security.constantTimeEquals(storefront.lookupToken(tenantId, orderId), token)) throw com.tenantcore.common.NotFound("Order")
        return db.tenant(tenantId) { forOrder(it, tenantId, orderId) }
    }

    fun render(data: Map<String, Any?>, format: String, size: Int): ResponseEntity<*> {
        if (format == "json") return ResponseEntity.ok(data)
        val m = Qr.matrix(data["payload"] as String, size, 2, "M")
        val (type, bytes) = if (format == "svg") "image/svg+xml" to Qr.svg(m).toByteArray() else MediaType.IMAGE_PNG_VALUE to Qr.png(m)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox").header("Cache-Control", "no-store").body(bytes)
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}")
@Validated
class PromptPayController(private val pp: PromptPayService) {
    @GetMapping("/qr/settings") fun settings() = pp.getSettings(Caller.user())
    @PutMapping("/qr/settings") fun setSettings(@Valid @RequestBody body: QrSettingsIn) = pp.setSettings(Caller.user(), body)

    @GetMapping("/orders/{id}/promptpay")
    fun order(@org.springframework.web.bind.annotation.PathVariable id: UUID,
              @RequestParam(defaultValue = "json") @Pattern(regexp = "^(json|png|svg)$") format: String,
              @RequestParam(defaultValue = "400") @Min(64) @Max(2048) size: Int) = pp.render(pp.staff(Caller.user(), id), format, size)

    @GetMapping("/public/shop/{slug}/orders/{id}/promptpay")
    fun public(@org.springframework.web.bind.annotation.PathVariable slug: String, @org.springframework.web.bind.annotation.PathVariable id: UUID,
               @RequestParam token: String,
               @RequestParam(defaultValue = "json") @Pattern(regexp = "^(json|png|svg)$") format: String,
               @RequestParam(defaultValue = "400") @Min(64) @Max(2048) size: Int) = pp.render(pp.public(slug, id, token), format, size)
}
