package com.tenantcore.data

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tenantcore.audit.Audit
import com.tenantcore.common.Conflict
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

private const val KEY = "^[a-z][a-z0-9_]{0,62}$"
private const val KEY_MSG = "lowercase letters, digits and _ , starting with a letter (max 63)"

data class FieldIn(
    @field:Pattern(regexp = KEY, message = KEY_MSG) val key: String,
    @field:NotBlank @field:Size(max = 200) val label: String,
    @field:Pattern(regexp = "^(text|long_text|integer|decimal|boolean|date|datetime|email|url|phone|select|multi_select|reference|file|user)$",
        message = "unknown field type") val type: String,
    val required: Boolean = false,
    val unique: Boolean = false,
    val defaultValue: JsonNode? = null,
    @field:Size(max = 200) val choices: List<@Size(min = 1, max = 100) String>? = null,
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
    @field:Min(0) val minLength: Int? = null,
    @field:Min(1) val maxLength: Int? = null,
    @field:Min(0) @field:Max(6) val decimals: Int? = null,
    /** reference fields: the key of the table it points to. */
    @field:Pattern(regexp = KEY, message = KEY_MSG) val referenceTable: String? = null,
    @field:Pattern(regexp = "^(restrict|set_null)$", message = "must be restrict or set_null") val onDelete: String? = null,
    @field:Size(max = 500) val helpText: String? = null,
    val position: Int? = null,
)

data class FieldPatchIn(
    @field:Size(min = 1, max = 200) val label: String? = null,
    val required: Boolean? = null,
    val unique: Boolean? = null,
    val defaultValue: JsonNode? = null,
    val clearDefault: Boolean? = null,
    @field:Size(max = 200) val choices: List<@Size(min = 1, max = 100) String>? = null,
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
    @field:Min(0) val minLength: Int? = null,
    @field:Min(1) val maxLength: Int? = null,
    @field:Min(0) @field:Max(6) val decimals: Int? = null,
    @field:Pattern(regexp = "^(restrict|set_null)$", message = "must be restrict or set_null") val onDelete: String? = null,
    @field:Size(max = 500) val helpText: String? = null,
    val position: Int? = null,
    /** Option names to remove: min, max, min_length, max_length, help_text. */
    val clear: List<String>? = null,
)

data class TableIn(
    @field:Pattern(regexp = KEY, message = KEY_MSG) val key: String,
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Size(max = 1000) val description: String? = null,
    @field:Size(max = 100) val fields: List<@Valid FieldIn> = emptyList(),
    /** The field shown when another table points at a row of this one. */
    @field:Pattern(regexp = KEY, message = KEY_MSG) val displayField: String? = null,
)
data class TablePatchIn(
    @field:Size(min = 1, max = 200) val name: String? = null,
    @field:Size(max = 1000) val description: String? = null,
    @field:Pattern(regexp = KEY, message = KEY_MSG) val displayField: String? = null,
)

data class FieldOut(
    val key: String, val label: String, val type: String, val required: Boolean, val unique: Boolean, val defaultValue: JsonNode?,
    val choices: List<String>?, val min: BigDecimal?, val max: BigDecimal?, val minLength: Int?, val maxLength: Int?, val decimals: Int?,
    val referenceTable: String?, val onDelete: String?, val helpText: String?, val position: Int,
    /** For the signed-in user. */
    val canRead: Boolean, val canWrite: Boolean,
)
data class TableOut(
    val key: String, val name: String, val description: String?, val displayField: String?, val rowCount: Long, val fields: List<FieldOut>,
    val access: Map<String, Any?>, val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
)

/** Defines tables and fields. Every change is checked against the rows already stored. */
@Service
class SchemaService(private val db: Db, private val audit: Audit, private val mapper: ObjectMapper, private val modules: ModuleService) {

    private val reserved = setOf("id", "data", "version", "created_at", "updated_at", "created_by", "updated_by")

    // -- reading -----------------------------------------------------------------------------------

    fun all(j: JdbcClient): List<Table> {
        val fields = j.sql("""SELECT f.*, rt.key AS reference_key FROM data_fields f LEFT JOIN data_tables rt ON rt.id = f.reference_table_id
                              ORDER BY f.position, f.key""").query { rs, _ ->
            rs.getObject("table_id", UUID::class.java) to Field(rs.getObject("id", UUID::class.java), rs.getString("key"), rs.getString("label"),
                FieldType.byCode.getValue(rs.getString("type")), rs.getBoolean("required"), rs.getBoolean("is_unique"),
                rs.getString("default_value")?.let { mapper.readTree(it) }, mapper.readValue(rs.getString("options")),
                rs.getObject("reference_table_id", UUID::class.java), rs.getString("reference_key"), rs.getString("help_text"), rs.getInt("position"))
        }.list().groupBy({ it.first }, { it.second })
        return j.sql("SELECT * FROM data_tables ORDER BY name").query { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            Table(id, rs.getString("key"), rs.getString("name"), rs.getString("description"), rs.getString("display_field"), fields[id].orEmpty(),
                rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("updated_at", OffsetDateTime::class.java))
        }.list()
    }

    fun find(j: JdbcClient, key: String): Table? = all(j).firstOrNull { it.key == key }
    fun get(j: JdbcClient, key: String): Table = find(j, key) ?: throw NotFound("Table")

    fun rowCount(j: JdbcClient, tableId: UUID) = j.sql("SELECT count(*) FROM data_rows WHERE table_id = :t").param("t", tableId).query(Long::class.java).single()

    fun out(j: JdbcClient, p: UserPrincipal, t: Table): TableOut {
        val res = "data.${t.key}"
        val read = p.grant(res, Action.READ)
        val write = p.grant(res, Action.UPDATE) ?: p.grant(res, Action.CREATE)
        val schemaRead = p.can("data_schema", Action.READ)
        return TableOut(t.key, t.name, t.description, t.displayField, rowCount(j, t.id),
            t.fields.filter { schemaRead || read?.canRead(it.key) == true }.map { f ->
                FieldOut(f.key, f.label, f.type.code, f.required, f.unique, f.default, f.options.choices, f.options.min, f.options.max,
                    f.options.minLength, f.options.maxLength, f.options.decimals, f.referenceTable, f.options.onDelete, f.helpText, f.position,
                    read?.canRead(f.key) == true, write?.canWrite(f.key) == true)
            },
            mapOf("read" to (read != null), "create" to p.can(res, Action.CREATE), "update" to p.can(res, Action.UPDATE),
                "delete" to p.can(res, Action.DELETE), "own_rows_only" to (read?.rowScope == "own"), "manage_schema" to p.can("data_schema", Action.MANAGE)),
            t.createdAt, t.updatedAt)
    }

    /** Tables the user can read rows of, or every table for schema readers. */
    fun list(p: UserPrincipal): List<TableOut> {
        p.requireModule("data_tables")
        val schemaRead = p.can("data_schema", Action.READ)
        return db.tenant(p.tenantId) { j -> all(j).filter { schemaRead || p.can("data.${it.key}", Action.READ) }.map { out(j, p, it) } }
    }

    fun describe(p: UserPrincipal, key: String): TableOut {
        p.requireModule("data_tables")
        return db.tenant(p.tenantId) { j ->
            val t = get(j, key)
            if (!p.can("data_schema", Action.READ) && !p.can("data.${t.key}", Action.READ)) throw NotFound("Table")
            out(j, p, t)
        }
    }

    // -- validation --------------------------------------------------------------------------------

    private data class Def(val type: FieldType, val options: FieldOptions, val refId: UUID?, val default: JsonNode?)

    private fun checkDef(j: JdbcClient, tableId: UUID?, key: String, typeCode: String, required: Boolean, unique: Boolean, defaultValue: JsonNode?,
                         o: FieldOptions, referenceTable: String?): Def {
        if (key in reserved) throw Invalid("'$key' is a reserved name. Choose another key.")
        val type = FieldType.byCode[typeCode] ?: throw Invalid("Unknown field type '$typeCode'.")
        fun bad(msg: String): Nothing = throw Invalid("Field '$key': $msg")
        if (o.choices != null && type != FieldType.SELECT && type != FieldType.MULTI_SELECT) bad("choices are for select fields")
        if ((type == FieldType.SELECT || type == FieldType.MULTI_SELECT)) {
            val c = o.choices ?: bad("list the choices")
            if (c.isEmpty()) bad("list at least one choice")
            if (c.map { it.trim().lowercase() }.toSet().size != c.size) bad("each choice must be different")
            if (c.any { it.isBlank() || it.contains(';') }) bad("choices cannot be blank or contain ';'")
        }
        if ((o.min != null || o.max != null) && !type.numeric) bad("min and max are for number fields")
        if (o.min != null && o.max != null && o.min > o.max) bad("min is more than max")
        if ((o.minLength != null || o.maxLength != null) && type != FieldType.TEXT && type != FieldType.LONG_TEXT) bad("lengths are for text fields")
        val cap = if (type == FieldType.TEXT) 1000 else 20_000
        if ((o.maxLength ?: 0) > cap) bad("max_length can be at most $cap")
        if (o.minLength != null && o.maxLength != null && o.minLength > o.maxLength) bad("min_length is more than max_length")
        if (o.decimals != null && type != FieldType.DECIMAL) bad("decimals is for decimal fields")
        if (unique && !type.canBeUnique) bad("${type.code} fields cannot be unique")
        var refId: UUID? = null
        if (type == FieldType.REFERENCE) {
            val target = referenceTable ?: bad("say which table it points to (reference_table)")
            refId = j.sql("SELECT id FROM data_tables WHERE key = :k").param("k", target).query(UUID::class.java).optional().orElse(null)
                ?: bad("table '$target' not found")
            if (o.onDelete == "set_null" && required) bad("a required reference cannot be emptied when its row is deleted; use on_delete restrict")
        } else {
            if (referenceTable != null) bad("reference_table is for reference fields")
            if (o.onDelete != null) bad("on_delete is for reference fields")
        }
        val norm = defaultValue?.takeUnless { Values.isEmpty(it) }?.let {
            if (type.idLike) bad("reference, file and user fields cannot have a default")
            val probe = Field(UUID(0, 0), key, key, type, required, unique, null, o, refId, referenceTable, null, 0)
            try { Values.normalize(probe, it) } catch (e: FieldProblem) { bad("default value ${e.message}") }
        }
        if (unique && norm != null) bad("a unique field cannot have a default")
        val opts = if (type == FieldType.REFERENCE) o.copy(onDelete = o.onDelete ?: "restrict") else o
        return Def(type, opts, refId, norm)
    }

    private fun limits(j: JdbcClient): Pair<Int, Int> {
        val cfg = modules.config(j, "data_tables")
        return ((cfg["max_tables"] as? Number)?.toInt() ?: 50) to ((cfg["max_fields_per_table"] as? Number)?.toInt() ?: 100)
    }

    // -- tables ------------------------------------------------------------------------------------

    fun createTable(p: UserPrincipal, input: TableIn): TableOut {
        p.require("data_schema", Action.MANAGE)
        if (input.fields.map { it.key }.toSet().size != input.fields.size) throw Invalid("Each field needs a different key.")
        return db.tenant(p.tenantId) { j ->
            val (maxTables, maxFields) = limits(j)
            if (find(j, input.key) != null) throw Conflict("A table with key '${input.key}' already exists.")
            if (all(j).size >= maxTables) throw Conflict("This workspace can have at most $maxTables tables.")
            if (input.fields.size > maxFields) throw Invalid("A table can have at most $maxFields fields.")
            val id = j.sql("INSERT INTO data_tables (tenant_id, key, name, description, created_by) VALUES (:t, :k, :n, :d, :u) RETURNING id")
                .param("t", p.tenantId).param("k", input.key).param("n", input.name.trim()).param("d", input.description).param("u", p.userId)
                .query(UUID::class.java).single()
            input.fields.forEachIndexed { i, f -> insertField(j, p.tenantId, id, f, f.position ?: i) }
            input.displayField?.let { setDisplay(j, id, it, input.fields.map { f -> f.key }) }
            audit.record(j, p.tenantId, "data_table.create", "data_schema", input.key, mapOf("fields" to input.fields.map { it.key }))
            out(j, p, get(j, input.key))
        }
    }

    private fun setDisplay(j: JdbcClient, tableId: UUID, key: String, keys: List<String>) {
        if (key !in keys) throw Invalid("display_field '$key' is not a field of this table.")
        j.sql("UPDATE data_tables SET display_field = :f WHERE id = :id").param("f", key).param("id", tableId).update()
    }

    private fun insertField(j: JdbcClient, tenantId: UUID, tableId: UUID, f: FieldIn, position: Int) {
        val opts = FieldOptions(f.choices?.map { it.trim() }, f.min, f.max, f.minLength, f.maxLength, f.decimals, f.onDelete)
        val d = checkDef(j, tableId, f.key, f.type, f.required, f.unique, f.defaultValue, opts, f.referenceTable)
        j.sql("""INSERT INTO data_fields (tenant_id, table_id, key, label, type, required, is_unique, default_value, options, reference_table_id, help_text, position)
                 VALUES (:t, :tb, :k, :l, :ty, :r, :u, CAST(:d AS jsonb), CAST(:o AS jsonb), :ref, :h, :p)""")
            .param("t", tenantId).param("tb", tableId).param("k", f.key).param("l", f.label.trim()).param("ty", d.type.code).param("r", f.required)
            .param("u", f.unique).param("d", d.default?.let { mapper.writeValueAsString(it) }).param("o", mapper.writeValueAsString(d.options))
            .param("ref", d.refId).param("h", f.helpText).param("p", position).update()
    }

    fun patchTable(p: UserPrincipal, key: String, input: TablePatchIn): TableOut {
        p.require("data_schema", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            val t = get(j, key)
            j.sql("UPDATE data_tables SET name = :n, description = :d WHERE id = :id")
                .param("n", input.name?.trim() ?: t.name).param("d", input.description ?: t.description).param("id", t.id).update()
            input.displayField?.let { setDisplay(j, t.id, it, t.fields.map { f -> f.key }) }
            audit.record(j, p.tenantId, "data_table.update", "data_schema", key)
            out(j, p, get(j, key))
        }
    }

    /** Deletes the table, all its rows and the permissions that mention it. [confirm] must repeat the key. */
    fun deleteTable(p: UserPrincipal, key: String, confirm: String?) {
        p.require("data_schema", Action.MANAGE)
        if (confirm != key) throw Invalid("To delete a table and all of its rows, send confirm=$key.")
        db.tenant(p.tenantId) { j ->
            val t = get(j, key)
            val users = all(j).filter { it.id != t.id }.flatMap { o -> o.fields.filter { it.referenceTableId == t.id }.map { "${o.key}.${it.key}" } }
            if (users.isNotEmpty()) throw Conflict("Other tables point at this one (${users.joinToString()}). Remove those fields first.")
            val rows = rowCount(j, t.id)
            j.sql("DELETE FROM data_fields WHERE table_id = :id").param("id", t.id).update()
            j.sql("DELETE FROM data_tables WHERE id = :id").param("id", t.id).update()
            j.sql("DELETE FROM role_permissions WHERE resource = :r").param("r", "data.$key").update()
            audit.record(j, p.tenantId, "data_table.delete", "data_schema", key, mapOf("rows" to rows))
        }
    }

    // -- fields ------------------------------------------------------------------------------------

    private fun emptyCount(j: JdbcClient, tableId: UUID, key: String): Long =
        j.sql("""SELECT count(*) FROM data_rows WHERE table_id = :t AND (data -> :k IS NULL OR data -> :k = 'null'::jsonb
                 OR data ->> :k = '' OR data -> :k = '[]'::jsonb)""").param("t", tableId).param("k", key).query(Long::class.java).single()

    private fun backfill(j: JdbcClient, tableId: UUID, key: String, value: JsonNode) {
        j.sql("""UPDATE data_rows SET data = data || jsonb_build_object(CAST(:k AS text), CAST(:v AS jsonb)), version = version + 1
                 WHERE table_id = :t AND (data -> :k IS NULL OR data -> :k = 'null'::jsonb OR data ->> :k = '' OR data -> :k = '[]'::jsonb)""")
            .param("k", key).param("v", mapper.writeValueAsString(value)).param("t", tableId).update()
    }

    /** Fills the unique index for a field from the stored rows; fails on the first duplicate. */
    private fun buildUnique(j: JdbcClient, tenantId: UUID, t: Table, f: Field) {
        val rows = j.sql("SELECT id, data -> :k FROM data_rows WHERE table_id = :t AND data -> :k IS NOT NULL AND data -> :k <> 'null'::jsonb")
            .param("k", f.key).param("t", t.id).query { rs, _ -> rs.getObject(1, UUID::class.java) to mapper.readTree(rs.getString(2)) }.list()
        val seen = HashMap<String, UUID>()
        rows.forEach { (id, v) ->
            if (Values.isEmpty(v)) return@forEach
            val norm = Values.uniqueKey(f, v)
            if (seen.put(norm, id) != null) throw Conflict("'${f.label}' cannot be unique: the value '${v.asText()}' appears more than once.")
            j.sql("INSERT INTO data_unique_values (tenant_id, table_id, field_id, value_norm, row_id) VALUES (:t, :tb, :f, :v, :r)")
                .param("t", tenantId).param("tb", t.id).param("f", f.id).param("v", norm).param("r", id).update()
        }
    }

    fun addField(p: UserPrincipal, key: String, input: FieldIn): TableOut {
        p.require("data_schema", Action.MANAGE)
        return db.tenant(p.tenantId) { j ->
            val t = get(j, key)
            if (t.field(input.key) != null) throw Conflict("This table already has a field '${input.key}'.")
            if (t.fields.size >= limits(j).second) throw Conflict("A table can have at most ${limits(j).second} fields.")
            val rows = rowCount(j, t.id)
            insertField(j, p.tenantId, t.id, input, input.position ?: ((t.fields.maxOfOrNull { it.position } ?: -1) + 1))
            val f = get(j, key).field(input.key)!!
            if (f.required && rows > 0) {
                val d = f.default ?: throw Conflict("This table has $rows rows. Give a default_value so they can be filled in, or add the field as optional.")
                backfill(j, t.id, f.key, d)
            }
            audit.record(j, p.tenantId, "data_field.create", "data_schema", "$key.${input.key}", mapOf("type" to input.type))
            out(j, p, get(j, key))
        }
    }

    private val clearable = setOf("min", "max", "min_length", "max_length", "help_text")

    fun patchField(p: UserPrincipal, key: String, fieldKey: String, input: FieldPatchIn): TableOut {
        p.require("data_schema", Action.MANAGE)
        input.clear?.forEach { if (it !in clearable) throw Invalid("'$it' cannot be cleared. Clearable: ${clearable.joinToString()}.") }
        return db.tenant(p.tenantId) { j ->
            val t = get(j, key)
            val f = t.field(fieldKey) ?: throw NotFound("Field")
            fun cleared(n: String) = input.clear?.contains(n) == true
            val o = f.options
            val opts = FieldOptions(
                input.choices?.map { it.trim() } ?: o.choices,
                if (cleared("min")) null else input.min ?: o.min, if (cleared("max")) null else input.max ?: o.max,
                if (cleared("min_length")) null else input.minLength ?: o.minLength, if (cleared("max_length")) null else input.maxLength ?: o.maxLength,
                input.decimals ?: o.decimals, input.onDelete ?: o.onDelete)
            val required = input.required ?: f.required
            val unique = input.unique ?: f.unique
            val default = if (input.clearDefault == true) null else input.defaultValue ?: f.default
            val d = checkDef(j, t.id, f.key, f.type.code, required, unique, default, opts, f.referenceTable)

            // Removing a choice that rows still use would leave invalid data behind.
            if (input.choices != null) {
                val gone = (o.choices.orEmpty()).filter { old -> d.options.choices!!.none { it.equals(old, true) } }
                gone.forEach { c ->
                    val used = if (f.type == FieldType.SELECT)
                        j.sql("SELECT count(*) FROM data_rows WHERE table_id = :t AND data ->> :k = :c").param("t", t.id).param("k", f.key).param("c", c)
                    else j.sql("SELECT count(*) FROM data_rows WHERE table_id = :t AND data -> :k @> CAST(:c AS jsonb)").param("t", t.id).param("k", f.key)
                        .param("c", mapper.writeValueAsString(listOf(c)))
                    val n = used.query(Long::class.java).single()
                    if (n > 0) throw Conflict("$n rows use the choice '$c'. Change those rows before removing it.")
                }
            }
            if (required && !f.required) {
                val empty = emptyCount(j, t.id, f.key)
                if (empty > 0) backfill(j, t.id, f.key, d.default ?: throw Conflict("$empty rows have no '${f.label}'. Fill them in or give a default_value first."))
            }
            j.sql("""UPDATE data_fields SET label = :l, required = :r, is_unique = :u, default_value = CAST(:d AS jsonb), options = CAST(:o AS jsonb),
                     help_text = :h, position = :p WHERE id = :id""")
                .param("l", input.label?.trim() ?: f.label).param("r", required).param("u", unique)
                .param("d", d.default?.let { mapper.writeValueAsString(it) }).param("o", mapper.writeValueAsString(d.options))
                .param("h", if (cleared("help_text")) null else input.helpText ?: f.helpText).param("p", input.position ?: f.position).param("id", f.id).update()
            if (unique && !f.unique) {
                j.sql("SELECT pg_advisory_xact_lock(hashtext('data_unique:' || :t))").param("t", t.id.toString()).query().singleRow()
                buildUnique(j, p.tenantId, t, f)
            }
            if (!unique && f.unique) j.sql("DELETE FROM data_unique_values WHERE field_id = :f").param("f", f.id).update()
            audit.record(j, p.tenantId, "data_field.update", "data_schema", "$key.$fieldKey",
                mapOf("required" to input.required, "unique" to input.unique, "choices" to (input.choices != null)))
            out(j, p, get(j, key))
        }
    }

    /** Removes the field and its values from every row. [confirm] must repeat the field key. */
    fun deleteField(p: UserPrincipal, key: String, fieldKey: String, confirm: String?) {
        p.require("data_schema", Action.MANAGE)
        if (confirm != fieldKey) throw Invalid("To delete a field and its values in every row, send confirm=$fieldKey.")
        db.tenant(p.tenantId) { j ->
            val t = get(j, key)
            val f = t.field(fieldKey) ?: throw NotFound("Field")
            j.sql("UPDATE data_rows SET data = data - CAST(:k AS text) WHERE table_id = :t AND data -> :k IS NOT NULL")
                .param("k", f.key).param("t", t.id).update()
            j.sql("DELETE FROM data_fields WHERE id = :id").param("id", f.id).update()
            if (t.displayField == f.key) j.sql("UPDATE data_tables SET display_field = NULL WHERE id = :id").param("id", t.id).update()
            j.sql("""UPDATE role_permissions SET readable_fields = array_remove(readable_fields, CAST(:k AS text)),
                     writable_fields = array_remove(writable_fields, CAST(:k AS text)) WHERE resource = :r""")
                .param("k", f.key).param("r", "data.$key").update()
            audit.record(j, p.tenantId, "data_field.delete", "data_schema", "$key.$fieldKey")
        }
    }

    /** For the role editor: a data permission must name an existing table and its fields. */
    fun checkPermission(j: JdbcClient, resource: String, fields: Collection<String>) {
        val key = resource.removePrefix("data.")
        val t = find(j, key) ?: throw Invalid("Permission on $resource: there is no table '$key'.")
        val unknown = fields.filter { t.field(it) == null }
        if (unknown.isNotEmpty()) throw Invalid("Permission on $resource: unknown fields ${unknown.joinToString()}.")
    }
}
