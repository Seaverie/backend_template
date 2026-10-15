package com.tenantcore.data

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tenantcore.audit.Audit
import com.tenantcore.common.Conflict
import com.tenantcore.common.Forbidden
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.Grant
import com.tenantcore.security.UserPrincipal
import com.tenantcore.tenants.ModuleService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

data class RowIn(val data: Map<String, JsonNode?>)
data class RowPatchIn(
    val data: Map<String, JsonNode?>,
    /** Optional: refuse the change if someone else changed the row since this version was read. */
    val expectedVersion: Int? = null,
)

private const val FIELD = "^[a-z][a-z0-9_]{0,62}$"

data class FilterIn(
    @field:Pattern(regexp = FIELD, message = "a field key") val field: String,
    @field:Pattern(regexp = "^(eq|ne|lt|lte|gt|gte|contains|starts_with|in|is_empty|is_not_empty|has)$", message = "unknown operator") val op: String,
    val value: JsonNode? = null,
)
data class SortIn(@field:Pattern(regexp = FIELD, message = "a field key") val field: String,
                  @field:Pattern(regexp = "^(asc|desc)$", message = "asc or desc") val direction: String = "asc")
data class QueryIn(
    @field:Size(max = 20) val filters: List<@Valid FilterIn> = emptyList(),
    @field:Size(max = 3) val sort: List<@Valid SortIn> = emptyList(),
    @field:Size(max = 200) val q: String? = null,
    @field:Min(1) @field:Max(500) val limit: Int = 50,
    @field:Min(0) @field:Max(1_000_000) val offset: Int = 0,
)

data class RefOut(val id: String, val display: String?)
data class RowOut(
    val id: UUID, val data: Map<String, JsonNode?>, val refs: Map<String, RefOut>, val version: Int, val createdBy: UUID?, val updatedBy: UUID?,
    val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
)

data class StoredRow(val id: UUID, val data: Map<String, JsonNode>, val version: Int, val createdBy: UUID?, val updatedBy: UUID?,
                     val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime)

/** Who is acting on which table, with which permission. */
class Access(val p: UserPrincipal, val table: Table, val grant: Grant) {
    val ownOnly get() = !p.isAdmin && grant.rowScope == "own"
    fun canRead(f: Field) = grant.canRead(f.key)
}

/**
 * Rows of custom tables. Everything a role says is enforced here: row scope ("own rows only"), which
 * fields can be read (others are left out of responses and cannot be filtered, sorted or searched on)
 * and which fields can be written.
 */
@Service
class RowService(
    private val db: Db, private val schema: SchemaService, private val audit: Audit, private val mapper: ObjectMapper,
    private val modules: ModuleService, private val idempotency: com.tenantcore.commerce.Idempotency,
    @org.springframework.context.annotation.Lazy private val events: com.tenantcore.integrations.Events,
) {
    private val builtIn = mapOf("id" to "r.id", "created_at" to "r.created_at", "updated_at" to "r.updated_at", "created_by" to "r.created_by")

    fun access(j: JdbcClient, p: UserPrincipal, key: String, action: Action): Access {
        p.requireModule("data_tables")
        val t = schema.find(j, key) ?: throw NotFound("Table")
        return Access(p, t, p.require("data.$key", action))
    }

    // -- reading -----------------------------------------------------------------------------------

    private fun stored(rs: java.sql.ResultSet): StoredRow {
        val node = mapper.readTree(rs.getString("data"))
        return StoredRow(rs.getObject("id", UUID::class.java), node.properties().associate { it.key to it.value }, rs.getInt("version"),
            rs.getObject("created_by", UUID::class.java), rs.getObject("updated_by", UUID::class.java),
            rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("updated_at", OffsetDateTime::class.java))
    }

    private val cols = "r.id, r.data::text AS data, r.version, r.created_by, r.updated_by, r.created_at, r.updated_at"

    fun find(j: JdbcClient, a: Access, id: UUID, lock: Boolean = false): StoredRow =
        j.sql("SELECT $cols FROM data_rows r WHERE r.id = :id AND r.table_id = :t ${if (a.ownOnly) "AND r.created_by = :u" else ""} ${if (lock) "FOR UPDATE" else ""}")
            .param("id", id).param("t", a.table.id).apply { if (a.ownOnly) param("u", a.p.userId) }
            .query { rs, _ -> stored(rs) }.optional().orElseThrow { NotFound("Row") }

    /** Shapes rows for the caller: unreadable fields removed, references labelled where the caller may read the target. */
    fun out(j: JdbcClient, a: Access, rows: List<StoredRow>): List<RowOut> {
        val readable = a.table.fields.filter { a.canRead(it) }
        val refs = HashMap<String, Map<String, String?>>()
        readable.filter { it.type == FieldType.REFERENCE }.forEach { f ->
            val ids = rows.mapNotNull { it.data[f.key]?.takeIf { v -> v.isTextual }?.asText() }.toSet()
            if (ids.isEmpty()) return@forEach
            val target = schema.all(j).firstOrNull { it.id == f.referenceTableId } ?: return@forEach
            val g = a.p.grant("data.${target.key}", Action.READ) ?: return@forEach
            val display = target.displayField?.takeIf { g.canRead(it) }
            val own = !a.p.isAdmin && g.rowScope == "own"
            refs[f.key] = j.sql("""SELECT id::text, ${if (display != null) "data ->> :df" else "NULL"} FROM data_rows
                                   WHERE table_id = :t AND id IN (:ids) ${if (own) "AND created_by = :u" else ""}""")
                .param("t", target.id).param("ids", ids.map { UUID.fromString(it) })
                .apply { if (display != null) param("df", display); if (own) param("u", a.p.userId) }
                .query { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()
        }
        return rows.map { r ->
            RowOut(r.id, readable.associate { it.key to r.data[it.key] },
                buildMap { readable.filter { it.type == FieldType.REFERENCE }.forEach { f ->
                    val id = r.data[f.key]?.asText() ?: return@forEach
                    refs[f.key]?.let { m -> if (m.containsKey(id)) put(f.key, RefOut(id, m[id])) }
                } },
                r.version, r.createdBy, r.updatedBy, r.createdAt, r.updatedAt)
        }
    }

    private class Sql {
        val where = mutableListOf<String>()
        val params = LinkedHashMap<String, Any?>()
        fun p(v: Any?): String { val n = "p${params.size}"; params[n] = v; return ":$n" }
        fun bind(s: JdbcClient.StatementSpec) = s.apply { params.forEach { (k, v) -> param(k, v) } }
    }

    private fun readableField(a: Access, key: String, what: String): Field {
        val f = a.table.field(key) ?: throw Invalid("Unknown field '$key'.")
        if (!a.canRead(f)) throw Forbidden("You cannot $what on '$key'.")
        return f
    }

    private fun expr(s: Sql, f: Field): String {
        val raw = "(r.data ->> ${s.p(f.key)})"
        return when {
            f.type.numeric -> "$raw::numeric"
            f.type == FieldType.DATE -> "$raw::date"
            f.type == FieldType.DATETIME -> "$raw::timestamptz"
            f.type == FieldType.BOOLEAN -> "$raw::boolean"
            else -> raw
        }
    }

    private fun like(v: String) = v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun filterValue(f: Field, v: JsonNode?): Any {
        if (v == null || v.isNull) throw Invalid("Filter on '${f.key}' needs a value.")
        return try { Values.param(f.type, Values.normalize(f.copy(options = f.options.copy(min = null, max = null, minLength = null, maxLength = null)), v)) }
            catch (e: FieldProblem) { throw Invalid("Filter on '${f.key}': value ${e.message}.") }
    }

    private fun build(a: Access, query: QueryIn): Pair<Sql, String> {
        val s = Sql()
        s.where += "r.table_id = ${s.p(a.table.id)}"
        if (a.ownOnly) s.where += "r.created_by = ${s.p(a.p.userId)}"
        query.filters.forEach { flt ->
            builtIn[flt.field]?.let { col ->
                val v = flt.value?.asText() ?: throw Invalid("Filter on '${flt.field}' needs a value.")
                val typed: Any = if (flt.field.endsWith("_at")) runCatching { OffsetDateTime.parse(v) }.getOrElse { throw Invalid("${flt.field} needs a date and time.") }
                    else runCatching { UUID.fromString(v) }.getOrElse { throw Invalid("${flt.field} needs an id.") }
                val op = mapOf("eq" to "=", "ne" to "<>", "lt" to "<", "lte" to "<=", "gt" to ">", "gte" to ">=")[flt.op]
                    ?: throw Invalid("Operator ${flt.op} does not apply to ${flt.field}.")
                s.where += "$col $op ${s.p(typed)}"
                return@forEach
            }
            val f = readableField(a, flt.field, "filter")
            val e = expr(s, f)
            val cmp = mapOf("eq" to "=", "ne" to "IS DISTINCT FROM", "lt" to "<", "lte" to "<=", "gt" to ">", "gte" to ">=")
            s.where += when (flt.op) {
                "is_empty", "is_not_empty" -> {
                    val k = s.p(f.key)
                    val empty = "(r.data -> $k IS NULL OR r.data -> $k = 'null'::jsonb OR r.data ->> $k = '' OR r.data -> $k = '[]'::jsonb)"
                    if (flt.op == "is_empty") empty else "NOT $empty"
                }
                "eq", "ne" -> {
                    if (f.type == FieldType.MULTI_SELECT) throw Invalid("Use 'has' to filter a multi-select field.")
                    val v = filterValue(f, flt.value)
                    if (f.type.textLike && f.type != FieldType.SELECT) "lower($e) ${cmp[flt.op]} lower(${s.p(v)})" else "$e ${cmp[flt.op]} ${s.p(v)}"
                }
                "lt", "lte", "gt", "gte" -> {
                    if (!f.type.ordered) throw Invalid("'${f.key}' cannot be compared with ${flt.op}.")
                    "$e ${cmp[flt.op]} ${s.p(filterValue(f, flt.value))}"
                }
                "contains", "starts_with" -> {
                    if (!f.type.textLike) throw Invalid("'${f.key}' is not a text field.")
                    val v = flt.value?.takeIf { it.isTextual }?.asText() ?: throw Invalid("Filter on '${f.key}' needs text.")
                    "$e ILIKE ${s.p(if (flt.op == "contains") "%${like(v)}%" else "${like(v)}%")}"
                }
                "in" -> {
                    val list = flt.value?.takeIf { it.isArray && it.size() in 1..100 } ?: throw Invalid("'in' needs a list of 1 to 100 values.")
                    if (f.type == FieldType.MULTI_SELECT) throw Invalid("Use 'has' to filter a multi-select field.")
                    "$e IN (${s.p(list.map { filterValue(f, it) })})"
                }
                "has" -> {
                    if (f.type != FieldType.MULTI_SELECT) throw Invalid("'has' is for multi-select fields.")
                    val v = filterValue(f.copy(type = FieldType.SELECT), flt.value)
                    "r.data -> ${s.p(f.key)} @> CAST(${s.p(mapper.writeValueAsString(listOf(v)))} AS jsonb)"
                }
                else -> throw Invalid("Unknown operator ${flt.op}.")
            }
        }
        query.q?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            val searchable = a.table.fields.filter { it.type.textLike && a.canRead(it) }
            val pat = s.p("%${like(q)}%")
            s.where += if (searchable.isEmpty()) "false" else searchable.joinToString(" OR ", "(", ")") { "(r.data ->> ${s.p(it.key)}) ILIKE $pat" }
        }
        val order = (query.sort.map { st ->
            val dir = if (st.direction == "desc") "DESC" else "ASC"
            val col = builtIn[st.field] ?: expr(s, readableField(a, st.field, "sort"))
            "$col $dir NULLS LAST"
        } + listOf("r.created_at DESC", "r.id")).joinToString(", ")
        return s to order
    }

    fun query(p: UserPrincipal, key: String, query: QueryIn): Map<String, Any> = db.tenant(p.tenantId) { j ->
        val a = access(j, p, key, Action.READ)
        val (s, order) = build(a, query)
        val where = s.where.joinToString(" AND ")
        val total = s.bind(j.sql("SELECT count(*) FROM data_rows r WHERE $where")).query(Long::class.java).single()
        val rows = s.bind(j.sql("SELECT $cols FROM data_rows r WHERE $where ORDER BY $order LIMIT :lim OFFSET :off"))
            .param("lim", query.limit).param("off", query.offset).query { rs, _ -> stored(rs) }.list()
        mapOf("items" to out(j, a, rows), "total" to total)
    }

    /** GET form: sort is "field" or "-field", comma separated. */
    fun list(p: UserPrincipal, key: String, q: String?, sort: String?, limit: Int, offset: Int) =
        query(p, key, QueryIn(q = q, limit = limit, offset = offset, sort = sort?.split(",")?.filter { it.isNotBlank() }?.take(3)?.map {
            val t = it.trim()
            if (!Regex("^-?[a-z][a-z0-9_]{0,62}$").matches(t)) throw Invalid("sort takes field keys, e.g. sort=-created_at,name")
            SortIn(t.removePrefix("-"), if (t.startsWith("-")) "desc" else "asc")
        }.orEmpty()))

    fun get(p: UserPrincipal, key: String, id: UUID): RowOut = db.tenant(p.tenantId) { j ->
        val a = access(j, p, key, Action.READ)
        out(j, a, listOf(find(j, a, id))).single()
    }

    // -- writing -----------------------------------------------------------------------------------

    /**
     * Validates [input] for a new row ([existing] null) or a change to one, then writes it. Shared by the
     * API and imports. Returns the row id.
     */
    fun write(j: JdbcClient, a: Access, input: Map<String, JsonNode?>, existing: StoredRow?, expectedVersion: Int?, record: Boolean, dryRun: Boolean = false): UUID? {
        val t = a.table
        val errors = ValueErrors()
        val unknown = input.keys.filter { t.field(it) == null }
        unknown.forEach { errors.add(it, "is not a field of ${t.name}") }
        errors.throwIfAny()
        val denied = input.keys.filter { !a.grant.canWrite(it) }
        if (denied.isNotEmpty()) throw Forbidden("Your role cannot change ${denied.joinToString()}.")

        val changes = LinkedHashMap<String, JsonNode?>()
        input.forEach { (k, v) ->
            val f = t.field(k)!!
            if (Values.isEmpty(v)) {
                if (f.required) errors.add(k, "is required") else changes[k] = null
            } else try { changes[k] = Values.normalize(f, v!!) } catch (e: FieldProblem) { errors.add(k, e.message!!) }
        }
        if (existing == null) t.fields.filter { it.key !in input }.forEach { f ->
            when {
                f.default != null -> changes[f.key] = f.default
                f.required -> errors.add(f.key, if (a.grant.canWrite(f.key)) "is required" else "is required, but your role cannot set it")
            }
        }
        // Ids must point at things in this workspace (row-level security hides everything else) that the
        // caller may see.
        changes.forEach { (k, v) ->
            if (v == null) return@forEach
            val f = t.field(k)!!
            val id = when (f.type) { FieldType.REFERENCE, FieldType.FILE, FieldType.USER -> UUID.fromString(v.asText()); else -> return@forEach }
            val ok = when (f.type) {
                FieldType.REFERENCE -> {
                    val target = schema.all(j).first { it.id == f.referenceTableId }
                    val g = a.p.grant("data.${target.key}", Action.READ)
                    if (g == null) { errors.add(k, "you cannot read the ${target.name} table"); return@forEach }
                    val own = !a.p.isAdmin && g.rowScope == "own"
                    j.sql("SELECT count(*) FROM data_rows WHERE id = :id AND table_id = :t ${if (own) "AND created_by = :u" else ""}")
                        .param("id", id).param("t", target.id).apply { if (own) param("u", a.p.userId) }.query(Long::class.java).single() > 0
                }
                FieldType.FILE -> j.sql("SELECT count(*) FROM files WHERE id = :id").param("id", id).query(Long::class.java).single() > 0
                else -> j.sql("SELECT count(*) FROM app_users WHERE id = :id").param("id", id).query(Long::class.java).single() > 0
            }
            if (!ok) errors.add(k, "points at something that does not exist")
        }
        errors.throwIfAny()

        val merged = LinkedHashMap<String, JsonNode>(existing?.data.orEmpty())
        changes.forEach { (k, v) -> if (v == null) merged.remove(k) else merged[k] = v }
        val json = mapper.writeValueAsString(merged)
        if (json.length > 65_536) throw Invalid("A row can hold at most 64 KB of data.")

        val uniques = t.fields.filter { it.unique && it.key in changes }
        if (uniques.isNotEmpty()) {
            // Writers to a table with unique fields take turns, so two can never claim the same value.
            j.sql("SELECT pg_advisory_xact_lock(hashtext('data_unique:' || :t))").param("t", t.id.toString()).query().singleRow()
            uniques.forEach { f ->
                val v = changes[f.key] ?: return@forEach
                val holder = j.sql("SELECT row_id FROM data_unique_values WHERE field_id = :f AND value_norm = :v").param("f", f.id)
                    .param("v", Values.uniqueKey(f, v)).query(UUID::class.java).optional().orElse(null)
                if (holder != null && holder != existing?.id) throw Conflict("'${f.label}' must be unique, and '${v.asText()}' is already used.")
            }
        }

        if (dryRun) return null
        val id = if (existing == null) {
            j.sql("INSERT INTO data_rows (tenant_id, table_id, data, created_by, updated_by) VALUES (:t, :tb, CAST(:d AS jsonb), :u, :u) RETURNING id")
                .param("t", a.p.tenantId).param("tb", t.id).param("d", json).param("u", a.p.userId).query(UUID::class.java).single()
        } else {
            val n = j.sql("UPDATE data_rows SET data = CAST(:d AS jsonb), version = version + 1, updated_by = :u WHERE id = :id AND version = :v")
                .param("d", json).param("u", a.p.userId).param("id", existing.id).param("v", expectedVersion ?: existing.version).update()
            if (n == 0) throw Conflict("This row was changed by someone else. Reload it and try again.")
            existing.id
        }
        uniques.forEach { f ->
            j.sql("DELETE FROM data_unique_values WHERE field_id = :f AND row_id = :r").param("f", f.id).param("r", id).update()
            changes[f.key]?.let { v ->
                j.sql("INSERT INTO data_unique_values (tenant_id, table_id, field_id, value_norm, row_id) VALUES (:t, :tb, :f, :v, :r)")
                    .param("t", a.p.tenantId).param("tb", t.id).param("f", f.id).param("v", Values.uniqueKey(f, v)).param("r", id).update()
            }
        }
        // Webhook payloads carry the whole row: they go to endpoints the workspace's admins configured.
        if (record) events.emit(j, a.p.tenantId, "data.${t.key}.${if (existing == null) "created" else "updated"}",
            mapOf("table" to t.key, "id" to id, "changed" to changes.keys, "data" to merged))
        if (record) audit.record(j, a.p.tenantId, if (existing == null) "data_row.create" else "data_row.update", "data.${t.key}", id,
            mapOf("fields" to changes.keys), actorType = a.p.actorType, actorId = a.p.userId)
        return id
    }

    /** Every check [write] makes, without saving: for import dry runs. */
    fun validateOnly(j: JdbcClient, a: Access, input: Map<String, JsonNode?>, existing: StoredRow?) {
        write(j, a, input, existing, null, record = false, dryRun = true)
    }

    fun maxRows(j: JdbcClient): Long = (modules.config(j, "data_tables")["max_rows_per_table"] as? Number)?.toLong() ?: 100_000

    fun checkRoom(j: JdbcClient, a: Access, adding: Int) {
        val max = maxRows(j)
        if (schema.rowCount(j, a.table.id) + adding > max) throw Conflict("${a.table.name} can hold at most $max rows.")
    }

    fun create(p: UserPrincipal, key: String, input: RowIn, idempotencyKey: String?): RowOut = db.tenant(p.tenantId) { j ->
        val a = access(j, p, key, Action.CREATE)
        val (id, _) = idempotency.run(j, p.tenantId, "data_row", idempotencyKey, mapOf("table" to key, "data" to input.data)) {
            checkRoom(j, a, 1)
            write(j, a, input.data, null, null, record = true)!!
        }
        // A replayed key returns the row even if the caller can no longer see it all; reading applies their read grant.
        out(j, Access(p, a.table, p.grant("data.$key", Action.READ) ?: a.grant), listOf(j.sql("SELECT $cols FROM data_rows r WHERE r.id = :id")
            .param("id", id).query { rs, _ -> stored(rs) }.single())).single()
    }

    fun update(p: UserPrincipal, key: String, id: UUID, input: RowPatchIn): RowOut = db.tenant(p.tenantId) { j ->
        val a = access(j, p, key, Action.UPDATE)
        val row = find(j, a, id, lock = true)
        if (input.expectedVersion != null && input.expectedVersion != row.version) throw Conflict("This row was changed by someone else. Reload it and try again.")
        write(j, a, input.data, row, input.expectedVersion, record = true)
        out(j, Access(p, a.table, p.grant("data.$key", Action.READ) ?: a.grant), listOf(find(j, a, id))).single()
    }

    /** Rows elsewhere that point at this one are handled as their field says: refuse, or empty the reference. */
    fun delete(p: UserPrincipal, key: String, id: UUID) = db.tenant(p.tenantId) { j ->
        val a = access(j, p, key, Action.DELETE)
        find(j, a, id, lock = true)
        schema.all(j).forEach { other ->
            other.fields.filter { it.referenceTableId == a.table.id }.forEach { f ->
                if (f.options.onDelete == "set_null") {
                    j.sql("""UPDATE data_rows SET data = data - CAST(:k AS text), version = version + 1
                             WHERE table_id = :t AND data ->> :k = :id""").param("k", f.key).param("t", other.id).param("id", id.toString()).update()
                } else {
                    val n = j.sql("SELECT count(*) FROM data_rows WHERE table_id = :t AND data ->> :k = :id")
                        .param("t", other.id).param("k", f.key).param("id", id.toString()).query(Long::class.java).single()
                    if (n > 0) throw Conflict("$n rows in ${other.name} (${f.label}) point at this row. Change or delete them first.")
                }
            }
        }
        j.sql("DELETE FROM data_rows WHERE id = :id").param("id", id).update()
        audit.record(j, p.tenantId, "data_row.delete", "data.$key", id, actorType = p.actorType, actorId = p.userId)
        events.emit(j, p.tenantId, "data.$key.deleted", mapOf("table" to key, "id" to id))
    }

    // -- export ------------------------------------------------------------------------------------

    /**
     * CSV of the rows the caller can read, with the fields they can read. Text that a spreadsheet would
     * run as a formula (starting with = + - @) is prefixed with ' so opening the file is safe.
     */
    fun exportCsv(p: UserPrincipal, key: String, q: String?): Pair<String, ByteArray> = db.tenant(p.tenantId) { j ->
        val a = access(j, p, key, Action.READ)
        val fields = a.table.fields.filter { a.canRead(it) }
        val (s, order) = build(a, QueryIn(q = q))
        val sb = StringBuilder("﻿")
        fun cell(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        fun safe(v: String) = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
        sb.append((listOf("id") + fields.map { it.key } + listOf("created_at", "updated_at")).joinToString(",") { cell(it) }).append("\r\n")
        var count = 0
        s.bind(j.sql("SELECT $cols FROM data_rows r WHERE ${s.where.joinToString(" AND ")} ORDER BY $order LIMIT ${maxRows(j)}")).query { rs, _ ->
            val r = stored(rs)
            val values = fields.map { f ->
                val v = r.data[f.key]
                when {
                    v == null || v.isNull -> ""
                    v.isArray -> safe(v.joinToString("; ") { it.asText() })
                    f.type.textLike -> safe(v.asText())
                    else -> v.asText()
                }
            }
            sb.append((listOf(r.id.toString()) + values + listOf(r.createdAt.toString(), r.updatedAt.toString())).joinToString(",") { cell(it) }).append("\r\n")
            count++
        }.list()
        audit.record(j, p.tenantId, "data.export", "data.$key", null, mapOf("rows" to count), actorType = p.actorType, actorId = p.userId)
        "${key}.csv" to sb.toString().toByteArray(Charsets.UTF_8)
    }
}
