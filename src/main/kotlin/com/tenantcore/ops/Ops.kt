package com.tenantcore.ops

import com.fasterxml.jackson.databind.ObjectMapper
import com.tenantcore.audit.Audit
import com.tenantcore.common.Conflict
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.files.StorageService
import com.tenantcore.integrations.JobContext
import com.tenantcore.integrations.JobHandler
import com.tenantcore.integrations.JobQueue
import com.tenantcore.security.UserPrincipal
import org.slf4j.LoggerFactory
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ------------------------------------------------------------------------------------------------
// Exports
// ------------------------------------------------------------------------------------------------

data class ExportOut(
    val id: UUID, val tenantId: UUID, val tenantSlug: String, val requestedByType: String, val requestedBy: UUID?, val status: String,
    val sizeBytes: Long?, val error: String?, val createdAt: OffsetDateTime, val finishedAt: OffsetDateTime?, val expiresAt: OffsetDateTime?,
)

/**
 * A complete copy of one tenant's data: every tenant table as JSON lines (passwords, token hashes and
 * encrypted secrets left out) plus the files, in one zip. Built by a background job and kept for
 * [EXPORT_DAYS] days.
 */
@Service
class ExportService(private val db: Db, private val jobs: JobQueue, private val storage: StorageService, private val audit: Audit) {
    companion object { const val EXPORT_DAYS = 7L }

    private fun row(rs: java.sql.ResultSet) = ExportOut(rs.getObject("id", UUID::class.java), rs.getObject("tenant_id", UUID::class.java),
        rs.getString("tenant_slug"), rs.getString("requested_by_type"), rs.getObject("requested_by", UUID::class.java), rs.getString("status"),
        rs.getObject("size_bytes") as Long?, rs.getString("error"), rs.getObject("created_at", OffsetDateTime::class.java),
        rs.getObject("finished_at", OffsetDateTime::class.java), rs.getObject("expires_at", OffsetDateTime::class.java))

    fun request(j: JdbcClient, tenantId: UUID, byType: String, by: UUID?): ExportOut {
        val slug = j.sql("SELECT slug FROM tenants WHERE id = :t").param("t", tenantId).query(String::class.java).optional().orElseThrow { NotFound("Tenant") }
        if (j.sql("SELECT count(*) FROM tenant_exports WHERE tenant_id = :t AND status IN ('queued', 'running')").param("t", tenantId).query(Long::class.java).single() > 0)
            throw Conflict("An export of this workspace is already being prepared.")
        val id = j.sql("INSERT INTO tenant_exports (tenant_id, tenant_slug, requested_by_type, requested_by) VALUES (:t, :s, :bt, :b) RETURNING id")
            .param("t", tenantId).param("s", slug).param("bt", byType).param("b", by).query(UUID::class.java).single()
        jobs.enqueue(j, tenantId, ExportJob.KIND, mapOf("export_id" to id), maxAttempts = 3)
        audit.record(j, if (byType == "user") tenantId else null, "tenant.export_requested", "tenants", tenantId, mapOf("export_id" to id))
        return find(j, id)
    }

    fun find(j: JdbcClient, id: UUID): ExportOut =
        j.sql("SELECT * FROM tenant_exports WHERE id = :id").param("id", id).query { rs, _ -> row(rs) }.optional().orElseThrow { NotFound("Export") }

    fun list(j: JdbcClient, tenantId: UUID): List<ExportOut> =
        j.sql("SELECT * FROM tenant_exports WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 20").param("t", tenantId).query { rs, _ -> row(rs) }.list()

    fun download(j: JdbcClient, id: UUID): ResponseEntity<InputStreamResource> {
        data class F(val status: String, val store: String?, val key: String?, val size: Long?, val slug: String, val expires: OffsetDateTime?, val at: OffsetDateTime)
        val f = j.sql("SELECT status, storage, storage_key, size_bytes, tenant_slug, expires_at, created_at FROM tenant_exports WHERE id = :id").param("id", id)
            .query { rs, _ -> F(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4) as Long?, rs.getString(5),
                rs.getObject(6, OffsetDateTime::class.java), rs.getObject(7, OffsetDateTime::class.java)) }.optional().orElseThrow { NotFound("Export") }
        if (f.status != "done" || f.key == null || f.store == null) throw Conflict("This export is not ready.")
        if (f.expires != null && f.expires.isBefore(OffsetDateTime.now())) throw NotFound("Export")
        val name = "tenantcore-export-${f.slug}-${f.at.toLocalDate()}.zip"
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .apply { f.size?.let { contentLength(it) } }
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(InputStreamResource(storage.byName(f.store).open(f.key)))
    }

    /** Removes expired exports and their files. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    fun expire() {
        runCatching {
            val old = db.platform { j ->
                j.sql("SELECT id, storage, storage_key FROM tenant_exports WHERE expires_at < now() OR (status = 'failed' AND created_at < now() - interval '7 days')")
                    .query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)) }.list()
            }
            old.forEach { (id, store, key) ->
                if (store != null) storage.deleteQuietly(store, key)
                db.platform { j -> j.sql("DELETE FROM tenant_exports WHERE id = :id").param("id", id).update() }
            }
        }
    }
}

@Component
class ExportJob(private val db: Db, private val storage: StorageService, private val mapper: ObjectMapper) : JobHandler {
    companion object {
        const val KIND = "tenant.export"
        /** Sessions, one-time tokens, caches and the job queue are not the tenant's data. */
        val SKIP_TABLES = setOf("auth_sessions", "customer_sessions", "user_tokens", "customer_tokens", "idempotency_keys", "jobs",
            "tenant_secrets", "geocode_cache", "tenant_exports")
        /** Secrets stay behind: password hashes, token hashes, encrypted credentials, storage locations. */
        val SKIP_COLUMNS = setOf("password_hash", "key_hash", "token_hash", "refresh_hash", "previous_hash", "secret_enc", "password_enc",
            "webhook_secret_enc", "storage_key", "thumb_key")
    }
    override val kind = KIND
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(job: JobContext) {
        val tenantId = job.tenantId ?: return
        val id = UUID.fromString(job.payload.path("export_id").asText())
        val status = db.tenant(tenantId) { j ->
            j.sql("UPDATE tenant_exports SET status = 'running' WHERE id = :id AND status IN ('queued', 'running') RETURNING status").param("id", id)
                .query(String::class.java).optional().orElse(null)
        } ?: return
        if (status != "running") return
        val tmp = Files.createTempFile("export-", ".zip")
        try {
            val manifest = linkedMapOf<String, Any?>("tenant_id" to tenantId, "created_at" to Instant.now().toString(), "format" to "One JSON object per line, per table.")
            ZipOutputStream(Files.newOutputStream(tmp)).use { zip ->
                val counts = linkedMapOf<String, Long>()
                val files = ArrayList<Triple<UUID, String, Pair<String, String>>>()
                db.tenant(tenantId) { j ->
                    j.sql("SET LOCAL statement_timeout = '15min'").update()
                    val tables = j.sql("""SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t
                                          ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                                          WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND t.table_type = 'BASE TABLE' ORDER BY 1""")
                        .query(String::class.java).list().filter { it !in SKIP_TABLES }
                    tables.forEach { table ->
                        // Table and column names come from the database catalogue, never from a request.
                        val cols = j.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = :t ORDER BY ordinal_position")
                            .param("t", table).query(String::class.java).list().filter { it !in SKIP_COLUMNS }
                        val select = cols.joinToString(", ") { "\"" + it.replace("\"", "") + "\"" }
                        zip.putNextEntry(ZipEntry("tables/$table.jsonl"))
                        var n = 0L
                        j.sql("SELECT row_to_json(x)::text FROM (SELECT $select FROM \"${table.replace("\"", "")}\") x").query { rs ->
                            zip.write(rs.getString(1).toByteArray()); zip.write('\n'.code); n++
                        }
                        zip.closeEntry()
                        counts[table] = n
                    }
                    j.sql("SELECT id, filename, storage, storage_key FROM files ORDER BY created_at").query { rs ->
                        files += Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3) to rs.getString(4))
                    }
                }
                var missing = 0
                files.forEach { (fid, name, loc) ->
                    val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(150)
                    try {
                        storage.byName(loc.first).open(loc.second).use { input ->
                            zip.putNextEntry(ZipEntry("files/$fid/$safe")); input.transferTo(zip); zip.closeEntry()
                        }
                    } catch (e: Exception) { missing++ }
                }
                manifest["tables"] = counts
                manifest["files"] = files.size - missing
                manifest["files_missing"] = missing
                manifest["left_out"] = mapOf("tables" to SKIP_TABLES.sorted(), "columns" to SKIP_COLUMNS.sorted())
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest))
                zip.closeEntry()
            }
            val store = storage.current()
            val key = "exports/$tenantId/$id.zip"
            store.put(key, tmp, "application/zip")
            db.tenant(tenantId) { j ->
                j.sql("""UPDATE tenant_exports SET status = 'done', storage = :s, storage_key = :k, size_bytes = :n, finished_at = now(),
                         expires_at = now() + make_interval(days => :d) WHERE id = :id""")
                    .param("s", store.name).param("k", key).param("n", Files.size(tmp)).param("d", ExportService.EXPORT_DAYS.toInt()).param("id", id).update()
            }
        } catch (e: Exception) {
            log.warn("export {} failed: {}", id, e.toString())
            db.tenant(tenantId) { j ->
                j.sql("UPDATE tenant_exports SET status = 'failed', error = :e, finished_at = now() WHERE id = :id")
                    .param("e", (e.message ?: e.javaClass.simpleName).take(1000)).param("id", id).update()
            }
        } finally { Files.deleteIfExists(tmp) }
    }
}

// ------------------------------------------------------------------------------------------------
// Offboarding and purge
// ------------------------------------------------------------------------------------------------

data class ConfirmIn(val confirm: String, val force: Boolean = false)

@Service
class OffboardingService(private val db: Db, private val exports: ExportService, private val jobs: JobQueue, private val audit: Audit) {

    private fun slugOf(j: JdbcClient, id: UUID): Pair<String, String> =
        j.sql("SELECT slug, status FROM tenants WHERE id = :id").param("id", id).query { rs, _ -> rs.getString(1) to rs.getString(2) }
            .optional().orElseThrow { NotFound("Tenant") }

    /** Locks the tenant out (staff, customers, API keys, the public shop) and starts a final export. */
    fun offboard(id: UUID, input: ConfirmIn): Map<String, Any?> = db.platform(id) { j ->
        val (slug, status) = slugOf(j, id)
        if (input.confirm != slug) throw Invalid("Type the workspace's slug ($slug) to confirm.")
        if (status == "offboarding") throw Conflict("This workspace is already being offboarded.")
        j.sql("UPDATE tenants SET status = 'offboarding' WHERE id = :id").param("id", id).update()
        j.sql("UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'offboarding' WHERE revoked_at IS NULL").update()
        j.sql("UPDATE customer_sessions SET revoked_at = now(), revoked_reason = 'offboarding' WHERE revoked_at IS NULL").update()
        audit.record(j, null, "tenant.offboard", "tenants", id, mapOf("slug" to slug))
        mapOf("status" to "offboarding", "export" to exports.request(j, id, "platform", Caller.platform().adminId))
    }

    fun cancel(id: UUID): Map<String, Any?> = db.platform(id) { j ->
        val (slug, status) = slugOf(j, id)
        if (status != "offboarding") throw Conflict("This workspace is not being offboarded.")
        j.sql("UPDATE tenants SET status = 'active' WHERE id = :id").param("id", id).update()
        audit.record(j, null, "tenant.offboard_cancelled", "tenants", id, mapOf("slug" to slug))
        mapOf("status" to "active")
    }

    /**
     * Permanently deletes the tenant. Only from offboarding, with the slug typed again, and only after a
     * finished export unless [ConfirmIn.force] is set. The export itself is kept until it expires.
     */
    fun purge(id: UUID, input: ConfirmIn): Map<String, Any?> = db.platform(id) { j ->
        val (slug, status) = slugOf(j, id)
        if (status != "offboarding") throw Conflict("Offboard the workspace first; only an offboarded workspace can be deleted.")
        if (input.confirm != slug) throw Invalid("Type the workspace's slug ($slug) to confirm.")
        val exported = j.sql("SELECT count(*) FROM tenant_exports WHERE tenant_id = :t AND status = 'done'").param("t", id).query(Long::class.java).single() > 0
        if (!exported && !input.force) throw Conflict("There is no finished export of this workspace yet. Wait for it, or send force=true to delete without one.")
        val job = jobs.enqueue(j, null, PurgeJob.KIND, mapOf("tenant_id" to id, "slug" to slug), maxAttempts = 3)
        audit.record(j, null, "tenant.purge_requested", "tenants", id, mapOf("slug" to slug, "without_export" to !exported))
        mapOf("job_id" to job, "status" to "deleting")
    }
}

/**
 * Deletes every row of one tenant, children before parents (the order comes from the foreign keys in
 * the catalogue), then the tenant itself, in one transaction run as the schema owner. Stored files are
 * deleted after the commit.
 */
@Component
class PurgeJob(private val db: Db, private val storage: StorageService, private val audit: Audit) : JobHandler {
    companion object { const val KIND = "tenant.purge" }
    override val kind = KIND
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(job: JobContext) {
        val id = UUID.fromString(job.payload.path("tenant_id").asText())
        val slug = job.payload.path("slug").asText()
        val objects = db.owner(id) { j ->
            val status = j.sql("SELECT status FROM tenants WHERE id = :id").param("id", id).query(String::class.java).optional().orElse(null)
                ?: return@owner emptyList<Pair<String, String>>()
            if (status != "offboarding") throw IllegalStateException("The tenant is $status, not offboarding; nothing was deleted.")
            j.sql("SELECT set_config('app.purging', 'on', true)").query().singleRow()
            j.sql("SET LOCAL statement_timeout = 0").update()
            val objects = j.sql("SELECT storage, storage_key, thumb_key FROM files").query { rs, _ ->
                listOfNotNull(rs.getString(1) to rs.getString(2), rs.getString(3)?.let { rs.getString(1) to it })
            }.list().flatten()
            deleteOrder(j).forEach { table ->
                if (table == "payments") j.sql("DELETE FROM payments WHERE parent_id IS NOT NULL").update()  // refunds before the charges they point at
                j.sql("DELETE FROM \"$table\" WHERE tenant_id = :t").param("t", id).update()
            }
            j.sql("DELETE FROM tenants WHERE id = :id").param("id", id).update()
            objects
        }
        objects.forEach { (store, key) -> storage.deleteQuietly(store, key) }
        db.platform { j -> audit.record(j, null, "tenant.purged", "tenants", id, mapOf("slug" to slug, "files_deleted" to objects.size), actorType = "system") }
        log.info("tenant {} ({}) purged", slug, id)
    }

    /** Tables holding tenant rows, ordered so that each comes before the tables it references. */
    fun deleteOrder(j: JdbcClient): List<String> {
        val tables = j.sql("""SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t
                              ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                              WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND t.table_type = 'BASE TABLE'""")
            .query(String::class.java).list().filter { it != "tenant_exports" && it != "tenants" }.toSet()
        val edges = j.sql("""SELECT cl.relname, pl.relname FROM pg_constraint c JOIN pg_class cl ON cl.oid = c.conrelid JOIN pg_class pl ON pl.oid = c.confrelid
                             WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace""")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
            .filter { (child, parent) -> child != parent && child in tables && parent in tables }
        val remaining = tables.toMutableSet()
        val order = ArrayList<String>()
        while (remaining.isNotEmpty()) {
            // A table can go once no remaining table still references it.
            val free = remaining.filter { t -> edges.none { (child, parent) -> parent == t && child in remaining } }.sorted()
            check(free.isNotEmpty()) { "Circular references between ${remaining.sorted()}" }
            order += free; remaining -= free.toSet()
        }
        return order
    }
}

// ------------------------------------------------------------------------------------------------
// Backups (made by the backup service in docker-compose; listed here for the platform dashboard)
// ------------------------------------------------------------------------------------------------

data class BackupFile(val name: String, val sizeBytes: Long, val createdAt: OffsetDateTime)

@Service
class BackupStatus(private val props: TcProperties) {
    private val names = Regex("^(tenantcore|storage)-\\d{8}-\\d{6}\\.(dump|tar\\.gz)$")

    fun status(): Map<String, Any?> {
        val dir = Paths.get(props.backups.dir)
        if (!Files.isDirectory(dir)) return mapOf("configured" to false, "dir" to props.backups.dir, "items" to emptyList<Any>())
        val files = Files.list(dir).use { s ->
            s.filter { Files.isRegularFile(it) && names.matches(it.fileName.toString()) }.map {
                BackupFile(it.fileName.toString(), Files.size(it), Files.getLastModifiedTime(it).toInstant().atOffset(ZoneOffset.UTC))
            }.toList()
        }.sortedByDescending { it.createdAt }
        val latest = files.firstOrNull { it.name.endsWith(".dump") }?.createdAt
        val ageHours = latest?.let { java.time.Duration.between(it, OffsetDateTime.now(ZoneOffset.UTC)).toMinutes() / 60.0 }
        return mapOf("configured" to true, "dir" to props.backups.dir, "interval_hours" to props.backups.intervalHours,
            "latest_database_backup_at" to latest, "age_hours" to ageHours,
            // Late means more than two intervals without a database backup.
            "healthy" to (ageHours != null && ageHours <= props.backups.intervalHours * 2.0), "items" to files.take(100))
    }
}

// ------------------------------------------------------------------------------------------------
// HTTP
// ------------------------------------------------------------------------------------------------

@RestController
@RequestMapping("\${tc.api-prefix}/platform")
class PlatformOpsController(private val db: Db, private val exports: ExportService, private val offboarding: OffboardingService, private val backups: BackupStatus) {
    @GetMapping("/tenants/{id}/exports") fun exports(@PathVariable id: UUID) = mapOf("items" to db.platform(id) { exports.list(it, id) })
    @PostMapping("/tenants/{id}/exports") @ResponseStatus(HttpStatus.CREATED)
    fun export(@PathVariable id: UUID) = db.platform(id) { exports.request(it, id, "platform", Caller.platform().adminId) }
    @GetMapping("/exports/{id}/download") fun download(@PathVariable id: UUID) = db.platform { exports.download(it, id) }
    @PostMapping("/tenants/{id}/offboard") fun offboard(@PathVariable id: UUID, @RequestBody body: ConfirmIn) = offboarding.offboard(id, body)
    @PostMapping("/tenants/{id}/cancel-offboarding") fun cancel(@PathVariable id: UUID) = offboarding.cancel(id)
    @DeleteMapping("/tenants/{id}") @ResponseStatus(HttpStatus.ACCEPTED)
    fun purge(@PathVariable id: UUID, @RequestParam confirm: String, @RequestParam(defaultValue = "false") force: Boolean) = offboarding.purge(id, ConfirmIn(confirm, force))
    @GetMapping("/backups") fun backups() = backups.status()
}

/** A workspace admin can take a full copy of their own data at any time. */
@RestController
@RequestMapping("\${tc.api-prefix}/workspace/exports")
class WorkspaceExportController(private val db: Db, private val exports: ExportService) {
    private fun admin(): UserPrincipal = Caller.user().also { it.requireAdmin() }
    @GetMapping fun list() = admin().let { p -> mapOf("items" to db.tenant(p.tenantId) { exports.list(it, p.tenantId) }) }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create() = admin().let { p -> db.tenant(p.tenantId) { exports.request(it, p.tenantId, "user", p.userId) } }
    @GetMapping("/{id}/download") fun download(@PathVariable id: UUID) = admin().let { p ->
        db.tenant(p.tenantId) { j -> exports.find(j, id).also { if (it.tenantId != p.tenantId) throw NotFound("Export") }; exports.download(j, id) }
    }
}
