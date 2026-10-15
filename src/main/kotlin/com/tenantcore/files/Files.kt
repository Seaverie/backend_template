package com.tenantcore.files

import com.tenantcore.audit.Audit
import com.tenantcore.common.ApiException
import com.tenantcore.common.Forbidden
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.UserPrincipal
import com.tenantcore.security.constantTimeEquals
import com.tenantcore.tenants.ModuleService
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO

// ------------------------------------------------------------------------------------------------
// Type detection: trust the bytes, not the client's Content-Type or the file extension alone.
// ------------------------------------------------------------------------------------------------

object FileTypes {
    val IMAGES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    private val OFFICE = mapOf(
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "zip" to "application/zip",
    )
    private val TEXT = mapOf("txt" to "text/plain", "csv" to "text/csv", "md" to "text/markdown", "json" to "application/json")

    /** Returns the content type, or null when the file is not an allowed kind. HTML, SVG and executables are refused. */
    fun detect(head: ByteArray, filename: String): String? {
        val ext = filename.substringAfterLast('.', "").lowercase()
        fun starts(vararg b: Int) = head.size >= b.size && b.indices.all { (head[it].toInt() and 0xff) == b[it] }
        fun ascii(at: Int, s: String) = head.size >= at + s.length && String(head, at, s.length, Charsets.ISO_8859_1) == s
        return when {
            starts(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "%PDF-") -> "application/pdf"
            starts(0x50, 0x4B, 0x03, 0x04) -> OFFICE[ext] ?: OFFICE.getValue("zip")
            isPlainText(head) -> if (ext in setOf("html", "htm", "svg", "xml", "xhtml", "js") || looksLikeMarkup(head)) null else TEXT[ext] ?: "text/plain"
            else -> null
        }
    }

    private fun isPlainText(head: ByteArray): Boolean {
        if (head.isEmpty() || head.any { it == 0.toByte() }) return false
        return runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(head, 0, (head.size - 3).coerceAtLeast(1)))
        }.isSuccess
    }

    private fun looksLikeMarkup(head: ByteArray): Boolean {
        val s = String(head, Charsets.UTF_8).trimStart().lowercase()
        return s.startsWith("<!doctype") || s.startsWith("<html") || s.startsWith("<svg") || s.startsWith("<?xml") || "<script" in s
    }
}

// ------------------------------------------------------------------------------------------------
// Model
// ------------------------------------------------------------------------------------------------

data class FileOut(
    val id: UUID, val filename: String, val contentType: String, val sizeBytes: Long, val sha256: String, val purpose: String,
    val isPublic: Boolean, val width: Int?, val height: Int?, val hasThumbnail: Boolean, val uploadedBy: UUID?,
    val createdAt: OffsetDateTime, val url: String?, val thumbnailUrl: String?,
)

private data class FileRow(
    val id: UUID, val tenantId: UUID, val filename: String, val contentType: String, val size: Long, val sha256: String,
    val purpose: String, val isPublic: Boolean, val storage: String, val key: String, val thumbKey: String?,
    val width: Int?, val height: Int?, val uploadedBy: UUID?, val createdAt: OffsetDateTime,
)

data class FilePatchIn(
    @field:Size(min = 1, max = 255) val filename: String? = null,
    val isPublic: Boolean? = null,
    @field:Pattern(regexp = "^[a-z][a-z0-9_]{0,31}$", message = "lowercase letters, digits and _") val purpose: String? = null,
)

class TooLarge(message: String) : ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", message)

@Service
class FileService(
    private val db: Db, private val storage: StorageService, private val audit: Audit, private val modules: ModuleService,
    private val props: TcProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val linkKey = SecretKeySpec(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(props.encryptionKeyBytes(), "HmacSHA256")); doFinal("tenantcore/file-links/v1".toByteArray())
    }, "HmacSHA256")

    private val cols = "id, tenant_id, filename, content_type, size_bytes, sha256, purpose, is_public, storage, storage_key, thumb_key, width, height, uploaded_by, created_at"
    private fun row(rs: java.sql.ResultSet) = FileRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4),
        rs.getLong(5), rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getString(9), rs.getString(10), rs.getString(11),
        rs.getObject(12) as Int?, rs.getObject(13) as Int?, rs.getObject(14, UUID::class.java), rs.getObject(15, OffsetDateTime::class.java))

    private fun find(j: JdbcClient, id: UUID): FileRow =
        j.sql("SELECT $cols FROM files WHERE id = :id").param("id", id).query { rs, _ -> row(rs) }.optional().orElseThrow { NotFound("File") }

    fun publicPath(tenantId: UUID, id: UUID, thumb: Boolean = false) =
        "${props.apiPrefix}/public/files/$tenantId/$id${if (thumb) "?thumb=1" else ""}"

    private fun out(r: FileRow) = FileOut(r.id, r.filename, r.contentType, r.size, r.sha256, r.purpose, r.isPublic, r.width, r.height,
        r.thumbKey != null, r.uploadedBy, r.createdAt,
        if (r.isPublic) publicPath(r.tenantId, r.id) else null,
        if (r.isPublic && r.thumbKey != null) publicPath(r.tenantId, r.id, thumb = true) else null)

    // -- limits ------------------------------------------------------------------------------------

    data class Limits(val maxFileBytes: Long, val quotaBytes: Long)

    /** Platform defaults, overridable per tenant in the files module options (max_file_mb, quota_mb). */
    fun limits(j: JdbcClient): Limits {
        val cfg = modules.config(j, "files")
        val maxMb = (cfg["max_file_mb"] as? Number)?.toLong() ?: props.files.maxFileMb.toLong()
        val quotaMb = (cfg["quota_mb"] as? Number)?.toLong() ?: props.files.defaultQuotaMb
        return Limits(maxMb * 1024 * 1024, quotaMb * 1024 * 1024)
    }

    fun usage(p: UserPrincipal): Map<String, Any> = db.tenant(p.tenantId) { j ->
        val (count, used) = j.sql("SELECT count(*), coalesce(sum(size_bytes), 0) FROM files").query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.single()
        val l = limits(j)
        mapOf("file_count" to count, "used_bytes" to used, "quota_bytes" to l.quotaBytes, "max_file_bytes" to l.maxFileBytes)
    }

    // -- upload -----------------------------------------------------------------------------------

    fun upload(p: UserPrincipal, file: MultipartFile, purpose: String, isPublic: Boolean): FileOut {
        p.require("files", Action.CREATE)
        if (!Regex("^[a-z][a-z0-9_]{0,31}$").matches(purpose)) throw Invalid("purpose must be lowercase letters, digits and _ (e.g. attachment, image, logo).")
        val filename = sanitizeName(file.originalFilename)
        val limits = db.tenant(p.tenantId) { limits(it) }
        if (file.isEmpty) throw Invalid("The file is empty.")
        if (file.size > limits.maxFileBytes) throw TooLarge("Files can be at most ${limits.maxFileBytes / 1024 / 1024} MB.")

        val tmp = Files.createTempFile("upload-", ".bin")
        var thumbTmp: Path? = null
        try {
            // Copy to a temp file while hashing, counting and keeping the first bytes for type detection.
            val digest = MessageDigest.getInstance("SHA-256")
            val head = java.io.ByteArrayOutputStream()
            var size = 0L
            file.inputStream.use { input ->
                Files.newOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        size += n
                        if (size > limits.maxFileBytes) throw TooLarge("Files can be at most ${limits.maxFileBytes / 1024 / 1024} MB.")
                        digest.update(buf, 0, n)
                        if (head.size() < 8192) head.write(buf, 0, minOf(n, 8192 - head.size()))
                        output.write(buf, 0, n)
                    }
                }
            }
            val type = FileTypes.detect(head.toByteArray(), filename)
                ?: throw ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_type",
                    "This kind of file is not allowed. Upload images (JPEG, PNG, GIF, WebP), PDF, Office documents, CSV or plain text.")

            var width: Int? = null; var height: Int? = null
            if (type in FileTypes.IMAGES) {
                val dims = Images.dimensions(tmp)
                if (dims != null) {
                    if (dims.first.toLong() * dims.second > 50_000_000L) throw Invalid("Images can be at most 50 megapixels.")
                    width = dims.first; height = dims.second
                    thumbTmp = Images.thumbnail(tmp, 480)
                } else if (type != "image/webp") throw Invalid("The image could not be read; it may be damaged.")
            }

            // Quota is checked again inside the insert transaction so parallel uploads cannot overshoot it.
            val store = storage.current()
            val id = UUID.randomUUID()
            val day = java.time.LocalDate.now(ZoneOffset.UTC)
            val key = "${p.tenantId}/${day.year}/${"%02d".format(day.monthValue)}/$id"
            val thumbKey = thumbTmp?.let { "$key.thumb.png" }
            store.put(key, tmp, type)
            thumbTmp?.let { store.put(thumbKey!!, it, "image/png") }
            try {
                return db.tenant(p.tenantId) { j ->
                    j.sql("SELECT pg_advisory_xact_lock(hashtext('files:' || :t))").param("t", p.tenantId.toString()).query().singleRow()
                    val used = j.sql("SELECT coalesce(sum(size_bytes), 0) FROM files").query(Long::class.java).single()
                    if (used + size > limits.quotaBytes) throw TooLarge("Storage quota reached (${limits.quotaBytes / 1024 / 1024} MB). Delete files or ask the platform admin for more space.")
                    j.sql("""INSERT INTO files (id, tenant_id, uploaded_by, filename, content_type, size_bytes, sha256, purpose, is_public, storage, storage_key, thumb_key, width, height)
                             VALUES (:id, :t, :u, :f, :ct, :s, :h, :p, :pub, :st, :k, :tk, :w, :hh)""")
                        .param("id", id).param("t", p.tenantId).param("u", p.userId).param("f", filename).param("ct", type).param("s", size)
                        .param("h", digest.digest().joinToString("") { "%02x".format(it) }).param("p", purpose).param("pub", isPublic)
                        .param("st", store.name).param("k", key).param("tk", thumbKey).param("w", width).param("hh", height).update()
                    audit.record(j, p.tenantId, "file.upload", "files", id, mapOf("filename" to filename, "content_type" to type, "size_bytes" to size, "purpose" to purpose))
                    out(find(j, id))
                }
            } catch (e: Exception) {
                storage.deleteQuietly(store.name, key); storage.deleteQuietly(store.name, thumbKey)
                throw e
            }
        } finally {
            Files.deleteIfExists(tmp); thumbTmp?.let { Files.deleteIfExists(it) }
        }
    }

    private fun sanitizeName(name: String?): String {
        val base = (name ?: "file").substringAfterLast('/').substringAfterLast('\\')
        val clean = base.filter { it >= ' ' && it != '"' && it.code != 127 }.trim().take(200)
        return clean.ifBlank { "file" }
    }

    // -- read / change / delete --------------------------------------------------------------------

    fun list(p: UserPrincipal, purpose: String?, q: String?, limit: Int, offset: Int): Map<String, Any> {
        p.require("files", Action.READ)
        return db.tenant(p.tenantId) { j ->
            val where = buildList {
                if (purpose != null) add("purpose = :p")
                if (!q.isNullOrBlank()) add("filename ILIKE :q")
            }.let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
            fun bind(s: JdbcClient.StatementSpec) = s.apply {
                if (purpose != null) param("p", purpose)
                if (!q.isNullOrBlank()) param("q", "%${q.replace("%", "\\%").replace("_", "\\_")}%")
            }
            mapOf(
                "items" to bind(j.sql("SELECT $cols FROM files $where ORDER BY created_at DESC, id LIMIT :l OFFSET :o")).param("l", limit).param("o", offset)
                    .query { rs, _ -> out(row(rs)) }.list(),
                "total" to bind(j.sql("SELECT count(*) FROM files $where")).query(Long::class.java).single(),
            )
        }
    }

    fun get(p: UserPrincipal, id: UUID): FileOut { p.require("files", Action.READ); return db.tenant(p.tenantId) { out(find(it, id)) } }

    fun patch(p: UserPrincipal, id: UUID, input: FilePatchIn): FileOut {
        p.require("files", Action.UPDATE)
        return db.tenant(p.tenantId) { j ->
            find(j, id)
            input.filename?.let { j.sql("UPDATE files SET filename = :f WHERE id = :id").param("f", sanitizeName(it)).param("id", id).update() }
            input.isPublic?.let { j.sql("UPDATE files SET is_public = :v WHERE id = :id").param("v", it).param("id", id).update() }
            input.purpose?.let { j.sql("UPDATE files SET purpose = :v WHERE id = :id").param("v", it).param("id", id).update() }
            audit.record(j, p.tenantId, "file.update", "files", id, mapOf("is_public" to input.isPublic, "renamed" to (input.filename != null)))
            out(find(j, id))
        }
    }

    /** Uploaders may delete their own files; deleting anyone's needs files:delete. Bytes go after the commit. */
    fun delete(p: UserPrincipal, id: UUID) {
        p.requireModule("files")
        db.tenant(p.tenantId) { j ->
            val r = find(j, id)
            if (r.uploadedBy != p.userId && !p.can("files", Action.DELETE)) throw Forbidden("You can only delete files you uploaded.")
            j.sql("DELETE FROM files WHERE id = :id").param("id", id).update()
            audit.record(j, p.tenantId, "file.delete", "files", id, mapOf("filename" to r.filename))
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() { storage.deleteQuietly(r.storage, r.key); storage.deleteQuietly(r.storage, r.thumbKey) }
            })
        }
    }

    // -- serving ------------------------------------------------------------------------------------

    fun content(p: UserPrincipal, id: UUID, thumb: Boolean): ResponseEntity<InputStreamResource> {
        p.require("files", Action.READ)
        return serve(db.tenant(p.tenantId) { find(it, id) }, thumb)
    }

    /** A time-limited link anyone can open (for sharing or for an <img> tag), signed with HMAC-SHA256. */
    fun signedLink(p: UserPrincipal, id: UUID, ttlSeconds: Long): Map<String, Any> {
        p.require("files", Action.READ)
        val r = db.tenant(p.tenantId) { find(it, id) }
        val exp = Instant.now().epochSecond + ttlSeconds.coerceIn(60, 7 * 86_400)
        return mapOf("url" to "${publicPath(r.tenantId, r.id)}?exp=$exp&sig=${sign(r.tenantId, r.id, exp)}",
            "expires_at" to Instant.ofEpochSecond(exp).atOffset(ZoneOffset.UTC))
    }

    private fun sign(tenantId: UUID, id: UUID, exp: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            Mac.getInstance("HmacSHA256").apply { init(linkKey) }.doFinal("$tenantId:$id:$exp".toByteArray()))

    /** Public files are served to anyone; private ones only with a valid, unexpired signature. */
    fun servePublic(tenantId: UUID, id: UUID, exp: Long?, sig: String?, thumb: Boolean): ResponseEntity<InputStreamResource> {
        val r = db.tenant(tenantId) { j ->
            j.sql("SELECT $cols FROM files WHERE id = :id").param("id", id).query { rs, _ -> row(rs) }.optional().orElse(null)
        } ?: throw NotFound("File")
        val signed = exp != null && sig != null && exp >= Instant.now().epochSecond && constantTimeEquals(sign(tenantId, id, exp), sig)
        if (!r.isPublic && !signed) throw NotFound("File")
        return serve(r, thumb, cacheSeconds = if (r.isPublic) 3600 else 0)
    }

    private fun serve(r: FileRow, thumb: Boolean, cacheSeconds: Int = 0): ResponseEntity<InputStreamResource> {
        val key = if (thumb) r.thumbKey ?: throw NotFound("Thumbnail") else r.key
        val type = if (thumb) "image/png" else r.contentType
        val stream: InputStream = storage.byName(r.storage).open(key)
        val inline = type in FileTypes.IMAGES || type == "application/pdf"
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(type))
            .apply { if (!thumb) contentLength(r.size) }
            .header(HttpHeaders.CONTENT_DISPOSITION,
                (if (inline) ContentDisposition.inline() else ContentDisposition.attachment()).filename(r.filename, Charsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox")
            .header(HttpHeaders.CACHE_CONTROL, if (cacheSeconds > 0) "public, max-age=$cacheSeconds" else "private, no-store")
            .header(HttpHeaders.ETAG, "\"${r.sha256}${if (thumb) "-t" else ""}\"")
            .body(InputStreamResource(stream))
    }

    /** For settings: the logo must be one of the tenant's images; it becomes public so the sign-in page can show it. */
    fun useAsLogo(j: JdbcClient, id: UUID) {
        val r = find(j, id)
        if (r.contentType !in FileTypes.IMAGES) throw Invalid("The logo must be an image.")
        j.sql("UPDATE files SET is_public = true, purpose = 'logo' WHERE id = :id").param("id", id).update()
    }
}

object Images {
    /** Width and height from the header only, so a decompression bomb is rejected before decoding. */
    fun dimensions(path: Path): Pair<Int, Int>? = ImageIO.createImageInputStream(path.toFile()).use { iis ->
        if (iis == null) return null
        val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: return null
        try { reader.input = iis; reader.getWidth(0) to reader.getHeight(0) } catch (e: Exception) { null } finally { reader.dispose() }
    }

    /** A PNG no larger than [max] px on either side. Re-encoding also drops metadata such as GPS location. */
    fun thumbnail(path: Path, max: Int): Path? {
        val src = runCatching { ImageIO.read(path.toFile()) }.getOrNull() ?: return null
        val scale = minOf(1.0, max.toDouble() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * scale).toInt()); val h = maxOf(1, (src.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        val target = Files.createTempFile("thumb-", ".png")
        ImageIO.write(out, "png", target.toFile())
        return target
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/files")
@Validated
class FileController(private val files: FileService, private val props: TcProperties) {

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(@RequestPart("file") file: MultipartFile,
               @RequestParam(defaultValue = "attachment") purpose: String,
               @RequestParam(name = "public", defaultValue = "false") public: Boolean) = files.upload(Caller.user(), file, purpose, public)

    @GetMapping
    fun list(@RequestParam(required = false) @Pattern(regexp = "^[a-z][a-z0-9_]{0,31}$") purpose: String?,
             @RequestParam(required = false) @Size(max = 100) q: String?,
             @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
             @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = files.list(Caller.user(), purpose, q, limit, offset)

    @GetMapping("/usage")
    fun usage() = Caller.user().also { it.requireModule("files") }.let { files.usage(it) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = files.get(Caller.user(), id)

    @GetMapping("/{id}/content")
    fun content(@PathVariable id: UUID) = files.content(Caller.user(), id, thumb = false)

    @GetMapping("/{id}/thumbnail")
    fun thumbnail(@PathVariable id: UUID) = files.content(Caller.user(), id, thumb = true)

    @PostMapping("/{id}/link")
    fun link(@PathVariable id: UUID, @RequestParam(name = "ttl_seconds", required = false) ttlSeconds: Long?) =
        files.signedLink(Caller.user(), id, ttlSeconds ?: props.files.linkTtlSeconds)

    @PatchMapping("/{id}")
    fun patch(@PathVariable id: UUID, @jakarta.validation.Valid @RequestBody body: FilePatchIn) = files.patch(Caller.user(), id, body)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = files.delete(Caller.user(), id)
}

/** No sign-in needed: public files (logos, product images) and signed links. */
@RestController
@RequestMapping("\${tc.api-prefix}/public/files")
class PublicFileController(private val files: FileService) {
    @GetMapping("/{tenantId}/{id}")
    fun get(@PathVariable tenantId: UUID, @PathVariable id: UUID,
            @RequestParam(required = false) exp: Long?, @RequestParam(required = false) sig: String?,
            @RequestParam(required = false, defaultValue = "0") thumb: Int) = files.servePublic(tenantId, id, exp, sig, thumb == 1)
}
