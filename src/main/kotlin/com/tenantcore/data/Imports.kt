package com.tenantcore.data

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tenantcore.audit.Audit
import com.tenantcore.common.ApiException
import com.tenantcore.common.Conflict
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.PrincipalLoader
import com.tenantcore.security.UserPrincipal
import com.tenantcore.tenants.ModuleService
import jakarta.validation.constraints.Pattern
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipFile

// ------------------------------------------------------------------------------------------------
// Parsing
// ------------------------------------------------------------------------------------------------

data class Sheet(val headers: List<String>, val rows: List<Pair<Int, List<String>>>)

class ImportLimits(val maxRows: Int, val maxColumns: Int = 200, val maxCell: Int = 20_000)

object Spreadsheets {

    /** RFC 4180 CSV: quoted fields, doubled quotes, line breaks inside quotes. The delimiter (, ; or tab) is detected. */
    fun csv(text: String, limits: ImportLimits): Sheet {
        val body = text.removePrefix("﻿")
        val firstLine = body.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> firstLine.count { it == d } }
        val out = ArrayList<Pair<Int, List<String>>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var line = 1
        var rowStart = 1
        var i = 0
        fun endCell() {
            if (cell.length > limits.maxCell) throw Invalid("Row $rowStart has a cell longer than ${limits.maxCell} characters.")
            row.add(cell.toString()); cell.setLength(0)
            if (row.size > limits.maxColumns) throw Invalid("Files can have at most ${limits.maxColumns} columns.")
        }
        fun endRow() {
            endCell()
            if (row.any { it.isNotBlank() }) {
                out += rowStart to row
                if (out.size > limits.maxRows + 1) throw Invalid("Files can have at most ${limits.maxRows} rows.")
            }
            row = ArrayList(); rowStart = line
        }
        while (i < body.length) {
            val c = body[i]
            when {
                quoted && c == '"' && i + 1 < body.length && body[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                !quoted && c == delimiter -> endCell()
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < body.length && body[i + 1] == '\n') i++
                    line++; endRow()
                }
                else -> { if (c == '\n') line++; cell.append(c) }
            }
            i++
        }
        if (quoted) throw Invalid("The file ends inside a quoted value (a \" is not closed).")
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return sheet(out)
    }

    /**
     * First (or named) worksheet of an .xlsx file, read as a stream. The archive's declared sizes are
     * checked first so a small upload cannot expand into gigabytes.
     */
    fun xlsx(bytes: ByteArray, sheetName: String?, limits: ImportLimits): Sheet {
        val tmp = Files.createTempFile("import-", ".xlsx")
        try {
            Files.write(tmp, bytes)
            val expanded = runCatching { ZipFile(tmp.toFile()).use { z -> z.entries().asSequence().sumOf { e -> if (e.size < 0) Long.MAX_VALUE / 4 else e.size } } }
                .getOrElse { throw Invalid("The file is not a valid .xlsx workbook.") }
            if (expanded > 200L * 1024 * 1024) throw Invalid("The workbook expands to more than 200 MB; save a smaller copy or use CSV.")
            val out = ArrayList<Pair<Int, List<String>>>()
            try {
                ReadableWorkbook(tmp.toFile()).use { wb ->
                    val sheet = if (sheetName != null) wb.findSheet(sheetName).orElseThrow { Invalid("There is no sheet named '$sheetName'.") } else wb.firstSheet
                    sheet.openStream().use { stream ->
                        val it = stream.iterator()
                        while (it.hasNext()) {
                            val r = it.next()
                            if (r.cellCount > limits.maxColumns) throw Invalid("Files can have at most ${limits.maxColumns} columns.")
                            val cells = (0 until r.cellCount).map { c ->
                                val cell = r.getCell(c)
                                (cell?.text ?: "").also { v -> if (v.length > limits.maxCell) throw Invalid("Row ${r.rowNum} has a cell longer than ${limits.maxCell} characters.") }
                            }
                            if (cells.any { it.isNotBlank() }) out += r.rowNum to cells
                            if (out.size > limits.maxRows + 1) throw Invalid("Files can have at most ${limits.maxRows} rows.")
                        }
                    }
                }
            } catch (e: ApiException) { throw e }
            catch (e: Exception) { throw Invalid("The workbook could not be read: ${e.javaClass.simpleName}.") }
            return sheet(out)
        } finally { Files.deleteIfExists(tmp) }
    }

    /** The first row is the header; duplicate or blank header names get a suffix. */
    private fun sheet(rows: List<Pair<Int, List<String>>>): Sheet {
        if (rows.isEmpty()) throw Invalid("The file is empty.")
        val seen = HashMap<String, Int>()
        val headers = rows[0].second.mapIndexed { i, h ->
            val base = h.trim().ifEmpty { "Column ${i + 1}" }
            val n = seen.merge(base.lowercase(), 1, Int::plus)!!
            if (n == 1) base else "$base ($n)"
        }
        return Sheet(headers, rows.drop(1).map { (n, cells) -> n to headers.indices.map { cells.getOrElse(it) { "" } } })
    }

    fun decode(bytes: ByteArray, encoding: String?): String {
        val cs = runCatching { Charset.forName(encoding ?: "UTF-8") }.getOrElse { throw Invalid("Unknown encoding '$encoding'.") }
        return runCatching {
            cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse {
            throw Invalid("The file is not valid ${cs.name()} text. Save it as CSV UTF-8, or say which encoding it uses (e.g. encoding=windows-874 for Thai Excel files).")
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Model
// ------------------------------------------------------------------------------------------------

data class ImportStartIn(
    /** Column header → field key. Columns left out are ignored. */
    val mapping: Map<String, String>,
    @field:Pattern(regexp = "^(insert|upsert)$", message = "insert or upsert") val mode: String = "insert",
    /** upsert: the unique field that identifies an existing row. */
    @field:Pattern(regexp = "^[a-z][a-z0-9_]{0,62}$") val matchField: String? = null,
    @field:Pattern(regexp = "^(iso|dmy|mdy)$", message = "iso, dmy or mdy") val dateFormat: String = "iso",
    /** Check every row without saving anything. */
    val dryRun: Boolean = false,
)

data class ImportError(val row: Int, val field: String?, val message: String)

data class ImportOut(
    val id: UUID, val table: String, val filename: String, val format: String, val status: String, val headers: List<String>,
    val totalRows: Int, val mode: String?, val dryRun: Boolean, val matchField: String?, val dateFormat: String?, val mapping: Map<String, String>?,
    val processedRows: Int, val insertedRows: Int, val updatedRows: Int, val failedRows: Int, val errors: List<ImportError>, val lastError: String?,
    val createdBy: UUID?, val createdAt: OffsetDateTime, val startedAt: OffsetDateTime?, val finishedAt: OffsetDateTime?,
    val preview: List<List<String>>? = null, val suggestedMapping: Map<String, String>? = null,
)

// ------------------------------------------------------------------------------------------------
// Service
// ------------------------------------------------------------------------------------------------

@Service
class ImportService(
    private val db: Db, private val schema: SchemaService, private val rows: RowService, private val audit: Audit,
    private val mapper: ObjectMapper, private val modules: ModuleService,
) {
    private fun cfg(j: JdbcClient) = modules.config(j, "imports")
    private fun maxBytes(j: JdbcClient) = ((cfg(j)["max_file_mb"] as? Number)?.toLong() ?: 10L) * 1024 * 1024
    private fun maxRows(j: JdbcClient) = (cfg(j)["max_rows"] as? Number)?.toInt() ?: 50_000

    private val cols = """i.id, t.key AS table_key, i.filename, i.format, i.status, i.headers::text AS headers, i.total_rows, i.mode, i.dry_run, i.match_field,
                          i.date_format, i.mapping::text AS mapping, i.processed_rows, i.inserted_rows, i.updated_rows, i.failed_rows, i.errors::text AS errors,
                          i.last_error, i.created_by, i.created_at, i.started_at, i.finished_at"""

    private fun row(rs: java.sql.ResultSet) = ImportOut(rs.getObject("id", UUID::class.java), rs.getString("table_key"), rs.getString("filename"),
        rs.getString("format"), rs.getString("status"), mapper.readValue(rs.getString("headers"), mapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
        rs.getInt("total_rows"), rs.getString("mode"), rs.getBoolean("dry_run"), rs.getString("match_field"), rs.getString("date_format"),
        rs.getString("mapping")?.let { mapper.readValue(it, mapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java)) },
        rs.getInt("processed_rows"), rs.getInt("inserted_rows"), rs.getInt("updated_rows"), rs.getInt("failed_rows"),
        mapper.readValue(rs.getString("errors"), mapper.typeFactory.constructCollectionType(List::class.java, ImportError::class.java)),
        rs.getString("last_error"), rs.getObject("created_by", UUID::class.java), rs.getObject("created_at", OffsetDateTime::class.java),
        rs.getObject("started_at", OffsetDateTime::class.java), rs.getObject("finished_at", OffsetDateTime::class.java))

    fun find(j: JdbcClient, id: UUID): ImportOut =
        j.sql("SELECT $cols FROM data_imports i JOIN data_tables t ON t.id = i.table_id WHERE i.id = :id").param("id", id)
            .query { rs, _ -> row(rs) }.optional().orElseThrow { NotFound("Import") }

    private fun suggest(headers: List<String>, t: Table): Map<String, String> {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val used = HashSet<String>()
        return headers.mapNotNull { h ->
            t.fields.firstOrNull { f -> f.key !in used && (norm(f.key) == norm(h) || norm(f.label) == norm(h) || f.label.equals(h.trim(), true)) }
                ?.let { used += it.key; h to it.key }
        }.toMap()
    }

    /** Step 1: upload and read the file. Nothing is written to the table yet. */
    fun upload(p: UserPrincipal, key: String, file: MultipartFile, encoding: String?, sheet: String?): ImportOut {
        p.require("imports", Action.CREATE)
        val (table, limit, rowLimit) = db.tenant(p.tenantId) { j ->
            Triple(rows.access(j, p, key, Action.CREATE).table, maxBytes(j), maxRows(j))
        }
        if (file.isEmpty) throw Invalid("The file is empty.")
        if (file.size > limit) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "Import files can be at most ${limit / 1024 / 1024} MB.")
        val bytes = file.bytes
        val isZip = bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        val limits = ImportLimits(rowLimit)
        val parsed = if (isZip) Spreadsheets.xlsx(bytes, sheet, limits) else Spreadsheets.csv(Spreadsheets.decode(bytes, encoding), limits)
        if (parsed.rows.isEmpty()) throw Invalid("The file has a header row but no data.")
        val filename = (file.originalFilename ?: "import").substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }.take(200).ifBlank { "import" }
        return db.tenant(p.tenantId) { j ->
            val id = j.sql("""INSERT INTO data_imports (tenant_id, table_id, filename, format, headers, rows, total_rows, created_by)
                              VALUES (:t, :tb, :f, :fmt, CAST(:h AS jsonb), CAST(:r AS jsonb), :n, :u) RETURNING id""")
                .param("t", p.tenantId).param("tb", table.id).param("f", filename).param("fmt", if (isZip) "xlsx" else "csv")
                .param("h", mapper.writeValueAsString(parsed.headers))
                .param("r", mapper.writeValueAsString(parsed.rows.map { (n, c) -> mapOf("n" to n, "c" to c) }))
                .param("n", parsed.rows.size).param("u", p.userId).query(UUID::class.java).single()
            audit.record(j, p.tenantId, "import.upload", "imports", id, mapOf("table" to key, "rows" to parsed.rows.size, "filename" to filename))
            find(j, id).copy(preview = parsed.rows.take(10).map { it.second }, suggestedMapping = suggest(parsed.headers, table))
        }
    }

    /** Step 2: choose the mapping and start (or dry-run) the import. A background worker does the writing. */
    fun start(p: UserPrincipal, id: UUID, input: ImportStartIn): ImportOut {
        p.require("imports", Action.CREATE)
        return db.tenant(p.tenantId) { j ->
            val imp = find(j, id)
            val a = rows.access(j, p, imp.table, Action.CREATE)
            val restartable = imp.status == "uploaded" || (imp.dryRun && imp.status in setOf("done", "failed", "cancelled"))
            if (!restartable) throw Conflict("This import is ${imp.status}; upload the file again to import it again.")
            if (input.mapping.isEmpty()) throw Invalid("Map at least one column to a field.")
            input.mapping.forEach { (col, fk) ->
                if (col !in imp.headers) throw Invalid("The file has no column '$col'.")
                if (a.table.field(fk) == null) throw Invalid("${a.table.name} has no field '$fk'.")
            }
            val targets = input.mapping.values
            if (targets.toSet().size != targets.size) throw Invalid("Each field can be filled from one column only.")
            val denied = targets.filter { !a.grant.canWrite(it) }
            if (denied.isNotEmpty()) throw com.tenantcore.common.Forbidden("Your role cannot set ${denied.joinToString()}.")
            if (input.mode == "upsert") {
                val g = p.require("data.${imp.table}", Action.UPDATE)
                val mf = input.matchField?.let { a.table.field(it) } ?: throw Invalid("Upsert needs match_field: a unique field that identifies existing rows.")
                if (!mf.unique) throw Invalid("match_field must be a unique field.")
                if (mf.key !in targets) throw Invalid("Map a column to the match field '${mf.key}'.")
                targets.filter { !g.canWrite(it) && it != mf.key }.takeIf { it.isNotEmpty() }?.let { throw com.tenantcore.common.Forbidden("Your role cannot update ${it.joinToString()}.") }
            } else {
                val missing = a.table.fields.filter { it.required && it.default == null && it.key !in targets }.map { it.key }
                if (missing.isNotEmpty()) throw Invalid("Map columns to the required fields: ${missing.joinToString()}.")
            }
            if (!input.dryRun && input.mode == "insert") rows.checkRoom(j, a, imp.totalRows)
            j.sql("""UPDATE data_imports SET status = 'queued', mode = :m, dry_run = :d, mapping = CAST(:map AS jsonb), match_field = :mf, date_format = :df,
                     processed_rows = 0, inserted_rows = 0, updated_rows = 0, failed_rows = 0, errors = '[]', last_error = NULL, lease_until = NULL,
                     attempts = 0, started_at = NULL, finished_at = NULL WHERE id = :id""")
                .param("m", input.mode).param("d", input.dryRun).param("map", mapper.writeValueAsString(input.mapping)).param("mf", input.matchField)
                .param("df", input.dateFormat).param("id", id).update()
            audit.record(j, p.tenantId, "import.start", "imports", id, mapOf("mode" to input.mode, "dry_run" to input.dryRun))
            find(j, id)
        }
    }

    fun cancel(p: UserPrincipal, id: UUID): ImportOut {
        p.require("imports", Action.CREATE)
        return db.tenant(p.tenantId) { j ->
            val imp = find(j, id)
            if (imp.status !in setOf("uploaded", "queued", "running")) throw Conflict("This import is already ${imp.status}.")
            j.sql("UPDATE data_imports SET status = 'cancelled', finished_at = now() WHERE id = :id").param("id", id).update()
            audit.record(j, p.tenantId, "import.cancel", "imports", id, mapOf("processed" to imp.processedRows))
            find(j, id)
        }
    }

    fun get(p: UserPrincipal, id: UUID): ImportOut { p.require("imports", Action.READ); return db.tenant(p.tenantId) { find(it, id) } }

    fun list(p: UserPrincipal, limit: Int, offset: Int): Map<String, Any> {
        p.require("imports", Action.READ)
        return db.tenant(p.tenantId) { j ->
            mapOf("items" to j.sql("SELECT $cols FROM data_imports i JOIN data_tables t ON t.id = i.table_id ORDER BY i.created_at DESC, i.id LIMIT :l OFFSET :o")
                .param("l", limit).param("o", offset).query { rs, _ -> row(rs).copy(errors = emptyList()) }.list(),
                "total" to j.sql("SELECT count(*) FROM data_imports").query(Long::class.java).single())
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Worker
// ------------------------------------------------------------------------------------------------

/**
 * Writes queued imports in batches of [BATCH] rows. Each batch, its counters and the progress marker
 * commit in one transaction, so a job interrupted at any point resumes after the last committed batch
 * and no row is written twice. Permissions are those the importing user has when each batch runs.
 */
@Component
class ImportWorker(
    private val db: Db, private val rows: RowService, private val schema: SchemaService, private val loader: PrincipalLoader,
    private val audit: Audit, private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    companion object { const val BATCH = 250; const val MAX_ERRORS = 500; const val MAX_ATTEMPTS = 5 }

    @Scheduled(fixedDelayString = "\${tc.imports.poll-ms:2000}", initialDelay = 3000)
    fun tick() {
        repeat(3) {
            val job = runCatching { lease() }.onFailure { log.warn("import lease failed: {}", it.message) }.getOrNull() ?: return
            runCatching { process(job.first, job.second, job.third) }.onFailure { e ->
                log.warn("import {} failed: {}", job.first, e.toString())
                runCatching { fail(job.second, job.first, (e.message ?: e.javaClass.simpleName).take(1000)) }
            }
        }
    }

    /** Claims one due job: queued, or running with an expired lease (its worker died). */
    private fun lease(): Triple<UUID, UUID, UUID>? = db.platform { j ->
        j.sql("""UPDATE data_imports SET status = 'running', lease_until = now() + interval '2 minutes', attempts = attempts + 1,
                        started_at = coalesce(started_at, now())
                 WHERE id = (SELECT id FROM data_imports WHERE status IN ('queued', 'running') AND (lease_until IS NULL OR lease_until < now())
                             ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                 RETURNING id, tenant_id, created_by, attempts""")
            .query { rs, _ -> listOf(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getInt(4)) }
            .optional().orElse(null)
            ?.let { (id, t, u, attempts) ->
                if ((attempts as Int) > MAX_ATTEMPTS) {
                    j.sql("UPDATE data_imports SET status = 'failed', last_error = 'Gave up after repeated interruptions.', finished_at = now() WHERE id = :id")
                        .param("id", id).update()
                    null
                } else Triple(id as UUID, t as UUID, u as UUID)
            }
    }

    private fun fail(tenantId: UUID, id: UUID, message: String) = db.tenant(tenantId) { j ->
        j.sql("UPDATE data_imports SET status = 'failed', last_error = :e, finished_at = now() WHERE id = :id AND status = 'running'")
            .param("e", message).param("id", id).update()
    }

    private fun process(id: UUID, tenantId: UUID, userId: UUID) {
        while (true) {
            val p = loader.forUser(tenantId, userId)
            val more = db.tenant(tenantId) { j -> batch(j, p, id) }
            if (!more) return
        }
    }

    /** One batch in one transaction. Returns whether more rows remain. */
    private fun batch(j: JdbcClient, p: UserPrincipal, id: UUID): Boolean {
        data class Job(val status: String, val tableId: UUID, val total: Int, val processed: Int, val mode: String, val dryRun: Boolean,
                       val mapping: Map<String, String>, val headers: List<String>, val matchField: String?, val dateFormat: String)
        val job = j.sql("""SELECT status, table_id, total_rows, processed_rows, mode, dry_run, mapping::text, headers::text, match_field, date_format
                           FROM data_imports WHERE id = :id FOR UPDATE""").param("id", id).query { rs, _ ->
            Job(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getBoolean(6),
                mapper.readValue(rs.getString(7), mapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java)),
                mapper.readValue(rs.getString(8), mapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
                rs.getString(9), rs.getString(10))
        }.optional().orElse(null) ?: return false
        if (job.status != "running") return false

        val table = schema.all(j).firstOrNull { it.id == job.tableId } ?: throw IllegalStateException("The table was deleted.")
        p.require("imports", Action.CREATE)
        val create = Access(p, table, p.require("data.${table.key}", Action.CREATE))
        val update = if (job.mode == "upsert") Access(p, table, p.require("data.${table.key}", Action.UPDATE)) else null
        if (!job.dryRun && job.mode == "insert") rows.checkRoom(j, create, minOf(BATCH, job.total - job.processed))

        val slice = j.sql("""SELECT coalesce(jsonb_agg(e ORDER BY i), '[]'::jsonb)::text FROM data_imports,
                             jsonb_array_elements(rows) WITH ORDINALITY AS x(e, i) WHERE id = :id AND i > :from AND i <= :to""")
            .param("id", id).param("from", job.processed).param("to", job.processed + BATCH).query(String::class.java).single()
            .let { mapper.readTree(it) }
        val columns = job.mapping.mapKeys { (h, _) -> job.headers.indexOf(h) }
        val errors = ArrayList<ImportError>()
        var inserted = 0; var updated = 0
        val lookup = { f: Field, s: String -> lookup(j, p, f, s) }

        slice.forEach { r ->
            val line = r.path("n").asInt()
            val cells = r.path("c")
            // Empty cells are "not given": a new row gets the field's default, an updated row keeps its value.
            val input = LinkedHashMap<String, JsonNode?>()
            val rowErrors = ArrayList<ImportError>()
            columns.forEach { (col, fk) ->
                val f = table.field(fk) ?: return@forEach
                try { Values.fromText(f, cells.path(col).asText(""), job.dateFormat, lookup)?.let { input[fk] = it } }
                catch (e: FieldProblem) { rowErrors += ImportError(line, fk, e.message ?: "invalid") }
            }
            fun report(e: ApiException) {
                @Suppress("UNCHECKED_CAST")
                val details = e.details as? List<com.tenantcore.common.FieldError>
                if (details.isNullOrEmpty()) rowErrors += ImportError(line, null, e.message)
                else details.forEach { d -> rowErrors += ImportError(line, d.loc.lastOrNull(), d.msg) }
            }
            if (rowErrors.isNotEmpty()) {
                // Still check the cells that could be read, so one pass shows every problem in the row.
                runCatching { rows.validateOnly(j, create, input.filterKeys { k -> rowErrors.none { it.field == k } }, null) }
                    .exceptionOrNull()?.let { if (it is ApiException) report(it) }
                errors += rowErrors.distinctBy { it.field to it.message }
                return@forEach
            }
            try {
                val existing = if (update != null) {
                    val mf = table.field(job.matchField!!)!!
                    val v = input[mf.key]?.let { Values.normalize(mf, it) } ?: throw Invalid("The match field is empty.")
                    j.sql("SELECT row_id FROM data_unique_values WHERE field_id = :f AND value_norm = :v").param("f", mf.id).param("v", Values.uniqueKey(mf, v))
                        .query(UUID::class.java).optional().orElse(null)?.let { rid ->
                            runCatching { rows.find(j, update, rid, lock = true) }.getOrElse { throw Invalid("It matches a row you cannot change.") }
                        }
                } else null
                if (job.dryRun) rows.validateOnly(j, if (existing != null) update!! else create, input, existing)
                else rows.write(j, if (existing != null) update!! else create, input, existing, null, record = false)
                if (existing != null) updated++ else inserted++
            } catch (e: ApiException) {
                report(e)
                errors += rowErrors
            }
        }

        val processed = job.processed + slice.size()
        val done = processed >= job.total
        j.sql("""UPDATE data_imports SET processed_rows = :p, inserted_rows = inserted_rows + :i, updated_rows = updated_rows + :u,
                        failed_rows = failed_rows + :f, lease_until = now() + interval '2 minutes',
                        errors = CASE WHEN jsonb_array_length(errors) >= $MAX_ERRORS THEN errors
                                      ELSE errors || CAST(:e AS jsonb) END,
                        status = CASE WHEN :done THEN 'done' ELSE status END, finished_at = CASE WHEN :done THEN now() END
                 WHERE id = :id""")
            .param("p", processed).param("i", inserted).param("u", updated).param("f", errors.map { it.row }.toSet().size)
            .param("e", mapper.writeValueAsString(errors.take(MAX_ERRORS))).param("done", done).param("id", id).update()
        if (done) audit.record(j, p.tenantId, if (job.dryRun) "import.dry_run" else "import.done", "imports", id,
            mapOf("table" to table.key), actorType = p.actorType, actorId = p.userId)
        return !done
    }

    /** A spreadsheet may name a related record by its display value (or a user by email) instead of an id. */
    private fun lookup(j: JdbcClient, p: UserPrincipal, f: Field, s: String): String? = when (f.type) {
        FieldType.USER -> j.sql("SELECT id::text FROM app_users WHERE lower(email) = lower(:e)").param("e", s).query(String::class.java).optional().orElse(null)
        FieldType.REFERENCE -> {
            val target = schema.all(j).firstOrNull { it.id == f.referenceTableId }
            val df = target?.displayField
            if (target == null || df == null || p.grant("data.${target.key}", Action.READ)?.canRead(df) != true) null
            else j.sql("SELECT id::text FROM data_rows WHERE table_id = :t AND lower(data ->> :k) = lower(:v) LIMIT 2")
                .param("t", target.id).param("k", df).param("v", s).query(String::class.java).list().singleOrNull()
        }
        else -> null
    }
}
