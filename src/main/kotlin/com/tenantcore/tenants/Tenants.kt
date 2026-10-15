package com.tenantcore.tenants

import com.fasterxml.jackson.databind.ObjectMapper
import com.tenantcore.audit.Audit
import com.tenantcore.common.Conflict
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.Module
import com.tenantcore.security.Passwords
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

// ------------------------------------------------------------------------------------------------
// Settings
// ------------------------------------------------------------------------------------------------

data class Settings(
    val displayName: String?, val primaryColor: String?, val currency: String, val timezone: String, val locale: String,
    val extra: Map<String, Any?>, val updatedAt: OffsetDateTime?, val logoFileId: UUID? = null, val logoUrl: String? = null,
)

data class SettingsIn(
    @field:Size(max = 200) val displayName: String? = null,
    @field:Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "must be a colour like #4f46e5") val primaryColor: String? = null,
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter currency code like USD") val currency: String? = null,
    @field:Size(max = 64) val timezone: String? = null,
    @field:Pattern(regexp = "^[a-z]{2,3}(-[A-Z]{2})?$", message = "must be a locale like en or th-TH") val locale: String? = null,
    val extra: Map<String, Any?>? = null,
    /** An uploaded image to use as the logo (it becomes public). */
    val logoFileId: UUID? = null,
    val removeLogo: Boolean? = null,
)

@Service
class SettingsService(
    private val db: Db, private val mapper: ObjectMapper, private val audit: Audit,
    @org.springframework.context.annotation.Lazy private val files: com.tenantcore.files.FileService,
) {

    fun read(j: JdbcClient, tenantId: UUID): Settings =
        j.sql("SELECT display_name, primary_color, currency, timezone, locale, extra::text, updated_at, logo_file_id FROM tenant_settings WHERE tenant_id = :t")
            .param("t", tenantId)
            .query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                Settings(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    mapper.readValue(rs.getString(6), Map::class.java) as Map<String, Any?>, rs.getObject(7, OffsetDateTime::class.java),
                    rs.getObject(8, UUID::class.java), rs.getObject(8, UUID::class.java)?.let { files.publicPath(tenantId, it) })
            }.optional().orElse(Settings(null, null, "USD", "UTC", "en", emptyMap(), null))

    fun get(tenantId: UUID): Settings = db.tenant(tenantId) { read(it, tenantId) }

    /** Only fields present in the request change. Used by both the workspace admin and the platform admin. */
    fun update(j: JdbcClient, tenantId: UUID, input: SettingsIn): Settings {
        input.timezone?.let { tz -> if (runCatching { ZoneId.of(tz) }.isFailure) throw Invalid("Unknown timezone '$tz'. Use a name like Asia/Bangkok.") }
        input.extra?.let { if (mapper.writeValueAsString(it).length > 16_384) throw Invalid("extra must be at most 16 KB of JSON.") }
        val current = read(j, tenantId)
        j.sql("""INSERT INTO tenant_settings (tenant_id, display_name, primary_color, currency, timezone, locale, extra)
                 VALUES (:t, :dn, :pc, :cur, :tz, :loc, CAST(:ex AS jsonb))
                 ON CONFLICT (tenant_id) DO UPDATE SET display_name = EXCLUDED.display_name, primary_color = EXCLUDED.primary_color,
                   currency = EXCLUDED.currency, timezone = EXCLUDED.timezone, locale = EXCLUDED.locale, extra = EXCLUDED.extra""")
            .param("t", tenantId)
            .param("dn", input.displayName ?: current.displayName)
            .param("pc", input.primaryColor ?: current.primaryColor)
            .param("cur", input.currency ?: current.currency)
            .param("tz", input.timezone ?: current.timezone)
            .param("loc", input.locale ?: current.locale)
            .param("ex", mapper.writeValueAsString(input.extra ?: current.extra))
            .update()
        if (input.removeLogo == true) j.sql("UPDATE tenant_settings SET logo_file_id = NULL WHERE tenant_id = :t").param("t", tenantId).update()
        else input.logoFileId?.let {
            files.useAsLogo(j, it)
            j.sql("UPDATE tenant_settings SET logo_file_id = :f WHERE tenant_id = :t").param("f", it).param("t", tenantId).update()
        }
        audit.record(j, tenantId, "settings.update", "settings", tenantId, mapOf("fields" to changedFields(input)))
        return read(j, tenantId)
    }

    private fun changedFields(i: SettingsIn) = listOfNotNull(
        i.displayName?.let { "display_name" }, i.primaryColor?.let { "primary_color" }, i.currency?.let { "currency" },
        i.timezone?.let { "timezone" }, i.locale?.let { "locale" }, i.extra?.let { "extra" },
        if (i.logoFileId != null || i.removeLogo == true) "logo" else null)
}

// ------------------------------------------------------------------------------------------------
// Modules
// ------------------------------------------------------------------------------------------------

data class ModuleOut(val code: String, val label: String, val description: String, val requires: List<String>, val enabled: Boolean)
data class ModulesIn(val modules: Map<String, Boolean>)

@Service
class ModuleService(private val audit: Audit) {

    fun enabled(j: JdbcClient): Set<String> = j.sql("SELECT module FROM tenant_modules WHERE enabled").query(String::class.java).set()

    fun list(j: JdbcClient): List<ModuleOut> {
        val on = enabled(j)
        return Module.entries.map { ModuleOut(it.code, it.label, it.description, it.requires, it.code in on) }
    }

    /**
     * Applies switches (code -> on/off). The resulting set must be consistent: a module cannot be on
     * while a module it needs is off. Nothing is switched implicitly, so the admin sees exactly what changes.
     */
    fun set(j: JdbcClient, tenantId: UUID, changes: Map<String, Boolean>): List<ModuleOut> {
        val unknown = changes.keys.filter { it !in Module.byCode }
        if (unknown.isNotEmpty()) throw Invalid("Unknown modules: ${unknown.joinToString()}.")
        val result = enabled(j).toMutableSet()
        changes.forEach { (code, on) -> if (on) result.add(code) else result.remove(code) }
        val problems = result.flatMap { code ->
            Module.byCode.getValue(code).requires.filter { it !in result }.map { "${label(code)} needs ${label(it)}" }
        }
        if (problems.isNotEmpty()) throw Invalid("These modules depend on others: ${problems.joinToString("; ")}.")
        changes.forEach { (code, on) ->
            j.sql("""INSERT INTO tenant_modules (tenant_id, module, enabled) VALUES (:t, :m, :e)
                     ON CONFLICT (tenant_id, module) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()""")
                .param("t", tenantId).param("m", code).param("e", on).update()
        }
        audit.record(j, tenantId, "modules.update", "modules", tenantId, mapOf("changes" to changes))
        return list(j)
    }

    private fun label(code: String) = Module.byCode[code]?.label ?: code

    /** Options for one module (e.g. file quota). Empty when never set. */
    @Suppress("UNCHECKED_CAST")
    fun config(j: JdbcClient, code: String): Map<String, Any?> =
        j.sql("SELECT config::text FROM tenant_modules WHERE module = :m").param("m", code)
            .query { rs, _ -> com.fasterxml.jackson.databind.ObjectMapper().readValue(rs.getString(1), Map::class.java) as Map<String, Any?> }
            .optional().orElse(emptyMap())

    fun setConfig(j: JdbcClient, tenantId: UUID, code: String, config: Map<String, Any?>, mapper: ObjectMapper): Map<String, Any?> {
        if (code !in Module.byCode) throw NotFound("Module")
        val json = mapper.writeValueAsString(config)
        if (json.length > 16_384) throw Invalid("Module options must be at most 16 KB of JSON.")
        j.sql("""INSERT INTO tenant_modules (tenant_id, module, enabled, config) VALUES (:t, :m, false, CAST(:c AS jsonb))
                 ON CONFLICT (tenant_id, module) DO UPDATE SET config = EXCLUDED.config, updated_at = now()""")
            .param("t", tenantId).param("m", code).param("c", json).update()
        audit.record(j, tenantId, "modules.config", "modules", code, mapOf("keys" to config.keys))
        return config(j, code)
    }
}

// ------------------------------------------------------------------------------------------------
// Tenants (platform)
// ------------------------------------------------------------------------------------------------

data class TenantOut(
    val id: UUID, val slug: String, val name: String, val status: String, val rateLimitPerMin: Int?,
    val createdAt: OffsetDateTime, val userCount: Int? = null, val modules: List<String>? = null,
)

data class TenantCreateIn(
    @field:Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$", message = "lowercase letters, digits and dashes (2-63)") val slug: String,
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:NotBlank @field:Email @field:Size(max = 320) val ownerEmail: String,
    @field:NotBlank @field:Size(max = 256) val ownerPassword: String,
    @field:Size(max = 200) val ownerFullName: String? = null,
    @field:Positive val rateLimitPerMin: Int? = null,
    /** Modules to switch on; defaults to [Module.defaultsForNewTenant]. */
    val modules: List<String>? = null,
)

data class TenantPatchIn(
    @field:Size(min = 1, max = 200) val name: String? = null,
    @field:Pattern(regexp = "^(active|suspended)$", message = "must be active or suspended") val status: String? = null,
    @field:Positive val rateLimitPerMin: Int? = null,
    /** True resets the rate limit to the platform default. */
    val useDefaultRateLimit: Boolean? = null,
)

@Service
class TenantService(
    private val db: Db, private val passwords: Passwords, private val audit: Audit, private val modules: ModuleService,
    private val settings: SettingsService, private val props: TcProperties,
) {
    private val cols = "t.id, t.slug, t.name, t.status, t.rate_limit_per_min, t.created_at"

    private fun row(rs: java.sql.ResultSet) = TenantOut(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4),
        rs.getObject(5) as Int?, rs.getObject(6, OffsetDateTime::class.java))

    fun list(q: String?, status: String?, limit: Int, offset: Int): Map<String, Any> = db.platform { j ->
        val where = buildList {
            if (!q.isNullOrBlank()) add("(t.slug ILIKE :q OR t.name ILIKE :q)")
            if (!status.isNullOrBlank()) add("t.status = :s")
        }.let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
        fun bind(s: JdbcClient.StatementSpec) = s.apply {
            if (!q.isNullOrBlank()) param("q", "%${q.replace("%", "\\%").replace("_", "\\_")}%")
            if (!status.isNullOrBlank()) param("s", status)
        }
        val total = bind(j.sql("SELECT count(*) FROM tenants t $where")).query(Long::class.java).single()
        val items = bind(j.sql("SELECT $cols FROM tenants t $where ORDER BY t.created_at DESC, t.id LIMIT :l OFFSET :o"))
            .param("l", limit).param("o", offset).query { rs, _ -> row(rs) }.list()
        mapOf("items" to items, "total" to total)
    }

    fun get(id: UUID): TenantOut = db.platform(id) { j ->
        val t = j.sql("SELECT $cols FROM tenants t WHERE t.id = :id").param("id", id).query { rs, _ -> row(rs) }.optional()
            .orElseThrow { NotFound("Tenant") }
        t.copy(
            userCount = j.sql("SELECT count(*) FROM app_users").query(Int::class.java).single(),
            modules = modules.enabled(j).sorted(),
        )
    }

    fun create(input: TenantCreateIn): Map<String, Any> {
        passwords.validatePolicy(input.ownerPassword)
        input.rateLimitPerMin?.let { if (it > props.rateLimit.tenantMaxPerMin) throw Invalid("Rate limit cannot exceed the platform maximum of ${props.rateLimit.tenantMaxPerMin}/min.") }
        val wanted = input.modules ?: Module.defaultsForNewTenant.toList()
        val hash = passwords.hash(input.ownerPassword)
        val tenantId = UUID.randomUUID()
        return db.platform(tenantId) { j ->
            val taken = j.sql("SELECT count(*) FROM tenants WHERE slug = :s").param("s", input.slug).query(Long::class.java).single()
            if (taken > 0) throw Conflict("The slug '${input.slug}' is already used by another tenant.")
            j.sql("INSERT INTO tenants (id, slug, name, rate_limit_per_min) VALUES (:id, :s, :n, :rl)")
                .param("id", tenantId).param("s", input.slug).param("n", input.name.trim()).param("rl", input.rateLimitPerMin).update()
            j.sql("INSERT INTO tenant_settings (tenant_id, display_name) VALUES (:t, :n)").param("t", tenantId).param("n", input.name.trim()).update()
            modules.set(j, tenantId, wanted.associateWith { true })
            val ownerId = j.sql("""INSERT INTO app_users (tenant_id, email, password_hash, full_name, is_tenant_admin)
                                   VALUES (:t, :e, :h, :n, true) RETURNING id""")
                .param("t", tenantId).param("e", input.ownerEmail.trim().lowercase()).param("h", hash).param("n", input.ownerFullName)
                .query(UUID::class.java).single()
            audit.record(j, null, "tenant.create", "tenants", tenantId, mapOf("slug" to input.slug, "modules" to wanted))
            audit.record(j, tenantId, "user.create", "users", ownerId, mapOf("email" to input.ownerEmail.lowercase(), "is_tenant_admin" to true))
            mapOf("tenant" to j.sql("SELECT $cols FROM tenants t WHERE t.id = :id").param("id", tenantId).query { rs, _ -> row(rs) }.single(),
                "owner_user_id" to ownerId)
        }
    }

    fun patch(id: UUID, input: TenantPatchIn): TenantOut {
        input.rateLimitPerMin?.let { if (it > props.rateLimit.tenantMaxPerMin) throw Invalid("Rate limit cannot exceed the platform maximum of ${props.rateLimit.tenantMaxPerMin}/min.") }
        db.platform(id) { j ->
            val status = j.sql("SELECT status FROM tenants WHERE id = :id FOR UPDATE").param("id", id).query(String::class.java).optional()
                .orElseThrow { NotFound("Tenant") }
            if (status == "offboarding") throw Conflict("This tenant is being offboarded and can no longer be changed.")
            input.name?.let { j.sql("UPDATE tenants SET name = :n WHERE id = :id").param("n", it.trim()).param("id", id).update() }
            input.status?.let { j.sql("UPDATE tenants SET status = :s WHERE id = :id").param("s", it).param("id", id).update() }
            if (input.useDefaultRateLimit == true) j.sql("UPDATE tenants SET rate_limit_per_min = NULL WHERE id = :id").param("id", id).update()
            else input.rateLimitPerMin?.let { j.sql("UPDATE tenants SET rate_limit_per_min = :r WHERE id = :id").param("r", it).param("id", id).update() }
            if (input.status == "suspended") {
                // Suspension signs everyone in the tenant out immediately.
                j.sql("UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'tenant_suspended' WHERE revoked_at IS NULL").update()
            }
            audit.record(j, null, "tenant.update", "tenants", id,
                mapOf("name" to input.name, "status" to input.status, "rate_limit_per_min" to input.rateLimitPerMin, "use_default_rate_limit" to input.useDefaultRateLimit))
        }
        return get(id)
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/platform")
@Validated
class PlatformTenantController(
    private val tenants: TenantService, private val db: Db, private val modules: ModuleService, private val settings: SettingsService,
    private val mapper: ObjectMapper,
) {
    @GetMapping("/tenants")
    fun list(@RequestParam(required = false) @Size(max = 100) q: String?,
             @RequestParam(required = false) @Pattern(regexp = "^(active|suspended|offboarding)$") status: String?,
             @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
             @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) offset: Int) = tenants.list(q, status, limit, offset)

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: TenantCreateIn) = tenants.create(body)

    @GetMapping("/tenants/{id}")
    fun get(@PathVariable id: UUID) = tenants.get(id)

    @PatchMapping("/tenants/{id}")
    fun patch(@PathVariable id: UUID, @Valid @RequestBody body: TenantPatchIn) = tenants.patch(id, body)

    @GetMapping("/modules")
    fun catalog() = mapOf("items" to Module.entries.map { mapOf("code" to it.code, "label" to it.label, "description" to it.description, "requires" to it.requires) })

    @GetMapping("/tenants/{id}/modules")
    fun tenantModules(@PathVariable id: UUID) = mapOf("items" to db.platform(id) { j -> exists(j, id); modules.list(j) })

    @PutMapping("/tenants/{id}/modules")
    fun setModules(@PathVariable id: UUID, @RequestBody body: ModulesIn) = mapOf("items" to db.platform(id) { j -> exists(j, id); modules.set(j, id, body.modules) })

    @GetMapping("/tenants/{id}/modules/{code}/config")
    fun moduleConfig(@PathVariable id: UUID, @PathVariable code: String) = db.platform(id) { j -> exists(j, id); modules.config(j, code) }

    @PutMapping("/tenants/{id}/modules/{code}/config")
    fun setModuleConfig(@PathVariable id: UUID, @PathVariable code: String, @RequestBody body: Map<String, Any?>) =
        db.platform(id) { j -> exists(j, id); modules.setConfig(j, id, code, body, mapper) }

    @GetMapping("/tenants/{id}/settings")
    fun tenantSettings(@PathVariable id: UUID) = db.platform(id) { j -> exists(j, id); settings.read(j, id) }

    @PutMapping("/tenants/{id}/settings")
    fun setTenantSettings(@PathVariable id: UUID, @Valid @RequestBody body: SettingsIn) = db.platform(id) { j -> exists(j, id); settings.update(j, id, body) }

    private fun exists(j: JdbcClient, id: UUID) {
        if (j.sql("SELECT count(*) FROM tenants WHERE id = :id").param("id", id).query(Long::class.java).single() == 0L) throw NotFound("Tenant")
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}")
class TenantSettingsController(private val db: Db, private val settings: SettingsService, private val modules: ModuleService) {

    /** Any signed-in user can read the settings (the dashboard needs branding and currency). */
    @GetMapping("/settings")
    fun get() = settings.get(Caller.user().tenantId)

    @PutMapping("/settings")
    fun put(@Valid @RequestBody body: SettingsIn): Settings {
        val p = Caller.user()
        p.require("settings", Action.MANAGE)
        return db.tenant(p.tenantId) { settings.update(it, p.tenantId, body) }
    }

    /** Modules are switched by the platform admin; workspace users can see which are on. */
    @GetMapping("/modules")
    fun modules() = mapOf("items" to db.tenant(Caller.user().tenantId) { modules.list(it) })
}
