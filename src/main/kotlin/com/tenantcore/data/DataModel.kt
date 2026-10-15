package com.tenantcore.data

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.tenantcore.common.FieldError
import com.tenantcore.common.Invalid
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class FieldType(val code: String) {
    TEXT("text"), LONG_TEXT("long_text"), INTEGER("integer"), DECIMAL("decimal"), BOOLEAN("boolean"), DATE("date"),
    DATETIME("datetime"), EMAIL("email"), URL("url"), PHONE("phone"), SELECT("select"), MULTI_SELECT("multi_select"),
    REFERENCE("reference"), FILE("file"), USER("user");

    /** Stored as a JSON string and searchable with q / contains. */
    val textLike get() = this in setOf(TEXT, LONG_TEXT, EMAIL, URL, PHONE, SELECT)
    val numeric get() = this == INTEGER || this == DECIMAL
    val ordered get() = numeric || this == DATE || this == DATETIME
    val idLike get() = this in setOf(REFERENCE, FILE, USER)
    val canBeUnique get() = this !in setOf(LONG_TEXT, BOOLEAN, MULTI_SELECT)

    companion object {
        val byCode = entries.associateBy { it.code }
    }
}

data class FieldOptions(
    val choices: List<String>? = null,
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val decimals: Int? = null,
    /** reference fields: what happens when the referenced row is deleted (restrict or set_null). */
    val onDelete: String? = null,
)

data class Field(
    val id: UUID, val key: String, val label: String, val type: FieldType, val required: Boolean, val unique: Boolean,
    val default: JsonNode?, val options: FieldOptions, val referenceTableId: UUID?, val referenceTable: String?,
    val helpText: String?, val position: Int,
)

data class Table(
    val id: UUID, val key: String, val name: String, val description: String?, val displayField: String?, val fields: List<Field>,
    val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
) {
    fun field(key: String) = fields.firstOrNull { it.key == key }
    val hasUnique get() = fields.any { it.unique }
}

/** Thrown for one invalid value; collected into a 422 with one entry per field. */
class FieldProblem(message: String) : RuntimeException(message)

class ValueErrors(val errors: MutableList<FieldError> = mutableListOf()) {
    fun add(key: String, msg: String) { errors += FieldError(listOf("body", "data", key), msg) }
    fun throwIfAny() { if (errors.isNotEmpty()) throw Invalid("Some values are not valid.", errors) }
}

/**
 * Validation and normalisation of values. Every value is checked against its field before it is
 * stored, so stored JSON always has the shape its type promises (dates as ISO strings, numbers as
 * numbers, ids as UUID strings), which is what lets filters cast safely.
 */
object Values {
    private val nf = JsonNodeFactory.instance
    private val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$")
    private val PHONE = Regex("^\\+?[0-9 ()\\-.]{3,30}$")
    private val MAX_DECIMAL = BigDecimal("1000000000000000")

    fun isEmpty(n: JsonNode?) = n == null || n.isNull || (n.isTextual && n.asText().isEmpty()) || (n.isArray && n.isEmpty)

    private fun fail(msg: String): Nothing = throw FieldProblem(msg)

    private fun text(n: JsonNode): String = if (n.isTextual) n.asText() else fail("must be text")

    private fun range(f: Field, v: BigDecimal) {
        f.options.min?.let { if (v < it) fail("must be at least ${it.toPlainString()}") }
        f.options.max?.let { if (v > it) fail("must be at most ${it.toPlainString()}") }
    }

    /** Returns the stored form of a non-empty value, or throws [FieldProblem]. */
    fun normalize(f: Field, n: JsonNode): JsonNode = when (f.type) {
        FieldType.TEXT, FieldType.LONG_TEXT -> {
            val s = text(n)
            val max = f.options.maxLength ?: if (f.type == FieldType.TEXT) 1000 else 20_000
            if (s.length > max) fail("must be at most $max characters")
            f.options.minLength?.let { if (s.length < it) fail("must be at least $it characters") }
            if (f.type == FieldType.TEXT && s.any { it == '\u0000' }) fail("contains invalid characters")
            nf.textNode(s)
        }
        FieldType.INTEGER -> {
            if (!n.isNumber) fail("must be a whole number")
            val v = n.decimalValue()
            if (v.stripTrailingZeros().scale() > 0) fail("must be a whole number")
            val l = runCatching { v.longValueExact() }.getOrElse { fail("is too large") }
            range(f, BigDecimal(l))
            nf.numberNode(l)
        }
        FieldType.DECIMAL -> {
            if (!n.isNumber) fail("must be a number")
            val v = n.decimalValue()
            val places = f.options.decimals ?: 2
            if (v.stripTrailingZeros().scale() > places) fail("can have at most $places decimal places")
            if (v.abs() >= MAX_DECIMAL) fail("is too large")
            range(f, v)
            nf.numberNode(v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it })
        }
        FieldType.BOOLEAN -> if (n.isBoolean) n else fail("must be true or false")
        FieldType.DATE -> nf.textNode(runCatching { LocalDate.parse(text(n)).toString() }.getOrElse { fail("must be a date like 2026-12-31") })
        FieldType.DATETIME -> nf.textNode(runCatching { OffsetDateTime.parse(text(n)).toInstant().toString() }
            .getOrElse { fail("must be a date and time with a timezone, like 2026-12-31T09:30:00+07:00") })
        FieldType.EMAIL -> text(n).trim().lowercase().let { if (it.length <= 320 && EMAIL.matches(it)) nf.textNode(it) else fail("must be an email address") }
        FieldType.URL -> {
            val s = text(n).trim()
            val u = runCatching { URI(s) }.getOrNull()
            // Only web addresses: a javascript: or data: link shown in a dashboard would be a script.
            if (s.length > 2000 || u == null || u.scheme?.lowercase() !in setOf("http", "https") || u.host.isNullOrBlank()) fail("must be an http or https address")
            nf.textNode(s)
        }
        FieldType.PHONE -> text(n).trim().let { if (PHONE.matches(it)) nf.textNode(it) else fail("must be a phone number") }
        FieldType.SELECT -> nf.textNode(choice(f, text(n)))
        FieldType.MULTI_SELECT -> {
            if (!n.isArray) fail("must be a list of options")
            val picked = n.map { choice(f, text(it)) }
            if (picked.toSet().size != picked.size) fail("lists an option twice")
            nf.arrayNode().apply { picked.forEach { add(it) } }
        }
        FieldType.REFERENCE, FieldType.FILE, FieldType.USER ->
            nf.textNode(runCatching { UUID.fromString(text(n)).toString() }.getOrElse { fail("must be an id") })
    }

    private fun choice(f: Field, s: String): String =
        f.options.choices?.firstOrNull { it.equals(s.trim(), ignoreCase = true) } ?: fail("must be one of: ${f.options.choices?.joinToString()}")

    /** Comparable text for unique checks: case and surrounding spaces do not make a value different. */
    fun uniqueKey(f: Field, v: JsonNode): String = when {
        f.type.numeric -> v.decimalValue().stripTrailingZeros().toPlainString()
        f.type.textLike -> v.asText().trim().lowercase()
        else -> v.asText()
    }

    /** Typed SQL parameter for comparisons (the value has been normalised). */
    fun param(f: FieldType, v: JsonNode): Any = when {
        f.numeric -> v.decimalValue()
        f == FieldType.DATE -> LocalDate.parse(v.asText())
        f == FieldType.DATETIME -> Instant.parse(v.asText()).atOffset(ZoneOffset.UTC)
        f == FieldType.BOOLEAN -> v.asBoolean()
        else -> v.asText()
    }

    // -- text from spreadsheets ---------------------------------------------------------------------

    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    /**
     * Converts a spreadsheet cell to JSON for [normalize]. Dates may be ISO, day/month/year or
     * month/day/year as chosen, or an Excel date serial number.
     */
    fun fromText(f: Field, raw: String, dateFormat: String, lookup: (Field, String) -> String?): JsonNode? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return when (f.type) {
            FieldType.INTEGER, FieldType.DECIMAL -> {
                val clean = s.replace(" ", "").let { if (Regex("^-?\\d{1,3}(,\\d{3})+(\\.\\d+)?$").matches(it)) it.replace(",", "") else it }
                nf.numberNode(runCatching { BigDecimal(clean) }.getOrElse { fail("must be a number") })
            }
            FieldType.BOOLEAN -> when (s.lowercase()) {
                "true", "yes", "y", "1", "x", "✓" -> nf.booleanNode(true)
                "false", "no", "n", "0", "-" -> nf.booleanNode(false)
                else -> fail("must be yes or no")
            }
            FieldType.DATE -> nf.textNode(parseDate(s, dateFormat).toString())
            FieldType.DATETIME -> nf.textNode(
                runCatching { OffsetDateTime.parse(s).toInstant().toString() }.getOrNull()
                    ?: s.toDoubleOrNull()?.let { serial -> EXCEL_EPOCH.atStartOfDay().plusSeconds((serial * 86400).toLong()).toInstant(ZoneOffset.UTC).toString() }
                    ?: parseDate(s, dateFormat).atStartOfDay().toInstant(ZoneOffset.UTC).toString())
            FieldType.MULTI_SELECT -> nf.arrayNode().apply { s.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) } }
            FieldType.REFERENCE, FieldType.USER -> nf.textNode(
                runCatching { UUID.fromString(s).toString() }.getOrNull() ?: lookup(f, s) ?: fail("no matching record for '$s'"))
            else -> nf.textNode(s)
        }
    }

    private fun parseDate(s: String, format: String): LocalDate {
        s.toDoubleOrNull()?.let { if (it in 1.0..80000.0) return EXCEL_EPOCH.plusDays(it.toLong()) }
        val pattern = when (format) { "dmy" -> "d/M/uuuu"; "mdy" -> "M/d/uuuu"; else -> null }
        return runCatching {
            if (pattern == null) LocalDate.parse(s.take(10))
            else LocalDate.parse(s.replace('-', '/').replace('.', '/'), DateTimeFormatter.ofPattern(pattern))
        }.getOrElse { fail(if (pattern == null) "must be a date like 2026-12-31" else "must be a date like ${if (format == "dmy") "31/12/2026" else "12/31/2026"}") }
    }
}
