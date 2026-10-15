package com.tenantcore.data

import com.tenantcore.config.Caller
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

private const val K = "^[a-z][a-z0-9_]{0,62}$"

@RestController
@RequestMapping("\${tc.api-prefix}/data")
@Validated
class DataController(private val schema: SchemaService, private val rows: RowService, private val imports: ImportService) {

    // -- tables and fields (data_schema) -----------------------------------------------------------

    @GetMapping("/tables")
    fun tables() = mapOf("items" to schema.list(Caller.user()))

    @PostMapping("/tables")
    @ResponseStatus(HttpStatus.CREATED)
    fun createTable(@Valid @RequestBody body: TableIn) = schema.createTable(Caller.user(), body)

    @GetMapping("/tables/{key}")
    fun table(@PathVariable @Pattern(regexp = K) key: String) = schema.describe(Caller.user(), key)

    @PatchMapping("/tables/{key}")
    fun patchTable(@PathVariable @Pattern(regexp = K) key: String, @Valid @RequestBody body: TablePatchIn) = schema.patchTable(Caller.user(), key, body)

    @DeleteMapping("/tables/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTable(@PathVariable @Pattern(regexp = K) key: String, @RequestParam(required = false) confirm: String?) = schema.deleteTable(Caller.user(), key, confirm)

    @PostMapping("/tables/{key}/fields")
    @ResponseStatus(HttpStatus.CREATED)
    fun addField(@PathVariable @Pattern(regexp = K) key: String, @Valid @RequestBody body: FieldIn) = schema.addField(Caller.user(), key, body)

    @PatchMapping("/tables/{key}/fields/{field}")
    fun patchField(@PathVariable @Pattern(regexp = K) key: String, @PathVariable @Pattern(regexp = K) field: String, @Valid @RequestBody body: FieldPatchIn) =
        schema.patchField(Caller.user(), key, field, body)

    @DeleteMapping("/tables/{key}/fields/{field}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteField(@PathVariable @Pattern(regexp = K) key: String, @PathVariable @Pattern(regexp = K) field: String,
                    @RequestParam(required = false) confirm: String?) = schema.deleteField(Caller.user(), key, field, confirm)

    // -- rows (data.<table>) -----------------------------------------------------------------------

    @GetMapping("/tables/{key}/rows")
    fun rows(@PathVariable @Pattern(regexp = K) key: String,
             @RequestParam(required = false) @Size(max = 200) q: String?,
             @RequestParam(required = false) @Size(max = 200) sort: String?,
             @RequestParam(defaultValue = "50") @Min(1) @Max(500) limit: Int,
             @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = rows.list(Caller.user(), key, q, sort, limit, offset)

    /** Filters ({field, op, value}), sorting, search and paging in one JSON body. */
    @PostMapping("/tables/{key}/rows/query")
    fun query(@PathVariable @Pattern(regexp = K) key: String, @Valid @RequestBody body: QueryIn) = rows.query(Caller.user(), key, body)

    @PostMapping("/tables/{key}/rows")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable @Pattern(regexp = K) key: String, @RequestBody body: RowIn,
               @RequestHeader("Idempotency-Key", required = false) idem: String?) = rows.create(Caller.user(), key, body, idem)

    @GetMapping("/tables/{key}/rows/{id}")
    fun row(@PathVariable @Pattern(regexp = K) key: String, @PathVariable id: UUID) = rows.get(Caller.user(), key, id)

    @PatchMapping("/tables/{key}/rows/{id}")
    fun update(@PathVariable @Pattern(regexp = K) key: String, @PathVariable id: UUID, @RequestBody body: RowPatchIn) = rows.update(Caller.user(), key, id, body)

    @DeleteMapping("/tables/{key}/rows/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable @Pattern(regexp = K) key: String, @PathVariable id: UUID) { rows.delete(Caller.user(), key, id) }

    @GetMapping("/tables/{key}/export")
    fun export(@PathVariable @Pattern(regexp = K) key: String, @RequestParam(required = false) @Size(max = 200) q: String?): ResponseEntity<ByteArray> {
        val (name, bytes) = rows.exportCsv(Caller.user(), key, q)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes)
    }

    // -- imports -----------------------------------------------------------------------------------

    @PostMapping("/tables/{key}/imports", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(@PathVariable @Pattern(regexp = K) key: String, @RequestPart("file") file: MultipartFile,
               @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z0-9_-]{2,30}$") encoding: String?,
               @RequestParam(required = false) @Size(max = 100) sheet: String?) = imports.upload(Caller.user(), key, file, encoding, sheet)

    @GetMapping("/imports")
    fun importsList(@RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
                    @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = imports.list(Caller.user(), limit, offset)

    @GetMapping("/imports/{id}")
    fun import(@PathVariable id: UUID) = imports.get(Caller.user(), id)

    @PostMapping("/imports/{id}/start")
    fun start(@PathVariable id: UUID, @Valid @RequestBody body: ImportStartIn) = imports.start(Caller.user(), id, body)

    @PostMapping("/imports/{id}/cancel")
    fun cancel(@PathVariable id: UUID) = imports.cancel(Caller.user(), id)
}
