package com.tenantcore.platform

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.tenantcore.audit.Audit
import com.tenantcore.common.Invalid
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.security.Crypto
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** Settings shown on the platform dashboard. */
data class GeneralSettings(
    @field:Size(min = 1, max = 100) val platformName: String = "TenantCore",
    /** Base URL of the admin dashboard; used to build links in emails (invitations, password resets). */
    @field:Size(max = 300) val publicUrl: String = "",
)

/**
 * Stores platform settings as JSON per key, with an optional encrypted secret beside them. Reads are
 * cached for a few seconds because the sending worker and upload path consult them constantly.
 */
@Service
class PlatformSettingsStore(private val db: Db, private val mapper: ObjectMapper, private val crypto: Crypto) {
    private data class Cached(val at: Long, val value: Map<String, Any?>, val secret: String?)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    fun read(key: String): Pair<Map<String, Any?>, String?> {
        cache[key]?.let { if (System.currentTimeMillis() - it.at < 5_000) return it.value to it.secret }
        val row = db.platform { j -> read(j, key) }
        cache[key] = Cached(System.currentTimeMillis(), row.first, row.second)
        return row
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(j: JdbcClient, key: String): Pair<Map<String, Any?>, String?> =
        j.sql("SELECT value::text, secret_enc FROM platform_settings WHERE key = :k").param("k", key)
            .query { rs, _ -> (mapper.readValue(rs.getString(1), Map::class.java) as Map<String, Any?>) to rs.getString(2) }
            .optional().orElse(emptyMap<String, Any?>() to null)
            .let { (v, enc) -> v to enc?.let { crypto.decrypt(it, "platform:$key") } }

    /** [secret]: null keeps the stored secret, "" clears it, anything else replaces it. */
    fun write(j: JdbcClient, key: String, value: Map<String, Any?>, secret: String?) {
        j.sql("""INSERT INTO platform_settings (key, value, secret_enc) VALUES (:k, CAST(:v AS jsonb), :s)
                 ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(),
                   secret_enc = CASE WHEN :keep THEN platform_settings.secret_enc ELSE EXCLUDED.secret_enc END""")
            .param("k", key).param("v", mapper.writeValueAsString(value))
            .param("s", if (secret.isNullOrEmpty()) null else crypto.encrypt(secret, "platform:$key"))
            .param("keep", secret == null)
            .update()
        cache.remove(key)
    }

    fun general(defaults: TcProperties): GeneralSettings {
        val (v, _) = read("general")
        val stored: GeneralSettings = if (v.isEmpty()) GeneralSettings() else mapper.convertValue(v)
        return stored.copy(publicUrl = stored.publicUrl.ifBlank { defaults.publicUrl })
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/platform/settings")
class PlatformSettingsController(
    private val store: PlatformSettingsStore, private val db: Db, private val audit: Audit, private val mapper: ObjectMapper,
    private val props: TcProperties,
) {
    @GetMapping("/general")
    fun general() = store.general(props)

    @PutMapping("/general")
    fun putGeneral(@Valid @RequestBody body: GeneralSettings): GeneralSettings {
        if (body.publicUrl.isNotBlank()) {
            val uri = runCatching { URI(body.publicUrl) }.getOrNull()
            if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.query != null || uri.fragment != null)
                throw Invalid("public_url must be a plain http(s) address such as https://admin.example.com")
        }
        db.platform { j ->
            store.write(j, "general", mapper.convertValue(body.copy(publicUrl = body.publicUrl.trimEnd('/'))), null)
            audit.record(j, null, "platform_settings.update", "platform_settings", "general")
        }
        return store.general(props)
    }
}
