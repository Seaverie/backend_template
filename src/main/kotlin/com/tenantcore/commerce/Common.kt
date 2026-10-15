package com.tenantcore.commerce

import com.fasterxml.jackson.databind.ObjectMapper
import com.tenantcore.common.ApiException
import com.tenantcore.common.Invalid
import com.tenantcore.db.Db
import com.tenantcore.security.sha256Hex
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Amounts are decimals with at most two places in the tenant's currency. */
object Money {
    private val MAX = BigDecimal("999999999999.99")

    fun check(v: BigDecimal, field: String): BigDecimal {
        if (v.signum() < 0) throw Invalid("$field cannot be negative.")
        if (v.stripTrailingZeros().scale() > 2) throw Invalid("$field can have at most 2 decimal places.")
        if (v > MAX) throw Invalid("$field is too large.")
        return v.setScale(2, RoundingMode.UNNECESSARY)
    }

    fun round(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)
    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(2)
}

class InsufficientStock(details: List<Map<String, Any?>>) :
    ApiException(HttpStatus.CONFLICT, "insufficient_stock", "There is not enough stock for some items.", details)

/** Small helpers shared by the commerce services. */
@Component
class Rows(private val mapper: ObjectMapper) {
    fun uuid(rs: ResultSet, col: String): UUID? = rs.getObject(col, UUID::class.java)
    fun time(rs: ResultSet, col: String): OffsetDateTime? = rs.getObject(col, OffsetDateTime::class.java)
    fun int(rs: ResultSet, col: String): Int? = rs.getObject(col)?.let { (it as Number).toInt() }

    @Suppress("UNCHECKED_CAST")
    fun json(rs: ResultSet, col: String): Map<String, Any?>? = rs.getString(col)?.let { mapper.readValue(it, Map::class.java) as Map<String, Any?> }

    fun jsonParam(v: Any?): String? = v?.let { mapper.writeValueAsString(it) }

    /** Free-form JSON objects (attributes, options, addresses) are capped so one row cannot grow without bound. */
    fun limitJson(v: Map<String, Any?>?, field: String, maxBytes: Int = 16_384): Map<String, Any?>? {
        if (v != null && mapper.writeValueAsString(v).length > maxBytes) throw Invalid("$field must be at most ${maxBytes / 1024} KB of JSON.")
        return v
    }

    /** Next value of a per-tenant sequence (order numbers etc.). Gap-free: it rolls back with the transaction. */
    fun next(j: JdbcClient, tenantId: UUID, name: String): Long =
        j.sql("""INSERT INTO tenant_counters (tenant_id, name, value) VALUES (:t, :n, 1)
                 ON CONFLICT (tenant_id, name) DO UPDATE SET value = tenant_counters.value + 1 RETURNING value""")
            .param("t", tenantId).param("n", name).query(Long::class.java).single()

    fun moduleOn(j: JdbcClient, module: String): Boolean =
        j.sql("SELECT count(*) FROM tenant_modules WHERE module = :m AND enabled").param("m", module).query(Long::class.java).single() > 0

    fun currency(j: JdbcClient): String =
        j.sql("SELECT currency FROM tenant_settings").query(String::class.java).optional().orElse("USD")

    fun like(q: String) = "%${q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
}

fun slugify(s: String, max: Int = 100): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "").lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(max).trim('-').ifEmpty { "item" }

/**
 * Idempotency keys for create endpoints. The first request with a key creates the record and stores
 * its id in the same transaction; a retry with the same key and the same body gets that record back;
 * the same key with a different body is refused. Two concurrent requests with one key serialize on the
 * key's primary key, so only one record is ever created.
 */
@Component
class Idempotency(private val mapper: ObjectMapper) {
    private val keyPattern = Regex("^[A-Za-z0-9_.:-]{8,100}$")

    /** Returns (id, replayed). */
    fun run(j: JdbcClient, tenantId: UUID, scope: String, key: String?, request: Any, create: () -> UUID): Pair<UUID, Boolean> {
        if (key == null) return create() to false
        if (!keyPattern.matches(key)) throw Invalid("Idempotency-Key must be 8-100 characters: letters, digits and _ . : -")
        val hash = sha256Hex(mapper.writeValueAsString(request))
        val inserted = j.sql("""INSERT INTO idempotency_keys (tenant_id, scope, key, request_hash) VALUES (:t, :s, :k, :h)
                                ON CONFLICT DO NOTHING""")
            .param("t", tenantId).param("s", scope).param("k", key).param("h", hash).update()
        if (inserted == 0) {
            val (storedHash, id) = j.sql("SELECT request_hash, result_id FROM idempotency_keys WHERE scope = :s AND key = :k")
                .param("s", scope).param("k", key).query { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }.single()
            if (storedHash != hash || id == null)
                throw ApiException(HttpStatus.CONFLICT, "idempotency_key_reused", "This Idempotency-Key was already used for a different request.")
            return id to true
        }
        val id = create()
        j.sql("UPDATE idempotency_keys SET result_id = :id WHERE scope = :s AND key = :k").param("id", id).param("s", scope).param("k", key).update()
        return id to false
    }
}

/** Keys are kept for 7 days, long enough for any sensible client retry. */
@Component
class IdempotencyCleanup(private val db: Db) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    fun purge() {
        runCatching { db.platform { it.sql("DELETE FROM idempotency_keys WHERE created_at < now() - interval '7 days'").update() } }
            .onFailure { log.warn("idempotency key cleanup failed: {}", it.message) }
    }
}
