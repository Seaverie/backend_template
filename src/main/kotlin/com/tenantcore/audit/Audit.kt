package com.tenantcore.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.tenantcore.config.Caller
import com.tenantcore.db.Db
import com.tenantcore.security.PlatformPrincipal
import com.tenantcore.security.RequestInfo
import com.tenantcore.security.UserPrincipal
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

data class AuditEntry(
    val id: UUID, val tenantId: UUID?, val actorType: String, val actorId: UUID?, val action: String,
    val resource: String, val resourceId: String?, val requestId: String?, val detail: Any?, val createdAt: OffsetDateTime,
)

/** Writes audit rows inside the caller's transaction, so the record commits (or rolls back) with the change. */
@Component
class Audit(private val mapper: ObjectMapper) {

    fun record(j: JdbcClient, tenantId: UUID?, action: String, resource: String, resourceId: Any? = null,
               detail: Map<String, Any?> = emptyMap(), actorType: String? = null, actorId: UUID? = null) {
        val principal = runCatching { Caller.principal() }.getOrNull()
        val (type, id) = when {
            actorType != null -> actorType to actorId
            principal is UserPrincipal -> (if (principal.apiKeyId != null) "api_key" else "user") to principal.userId
            principal is PlatformPrincipal -> "platform" to principal.adminId
            else -> "system" to null
        }
        j.sql("""INSERT INTO audit_logs (tenant_id, actor_type, actor_id, action, resource, resource_id, request_id, ip, detail)
                 VALUES (:t, :at, :ai, :a, :r, :rid, :req, CAST(:ip AS inet), CAST(:d AS jsonb))""")
            .param("t", tenantId).param("at", type).param("ai", id).param("a", action).param("r", resource)
            .param("rid", resourceId?.toString()).param("req", RequestInfo.requestId()).param("ip", RequestInfo.clientIp())
            .param("d", mapper.writeValueAsString(detail))
            .update()
    }

    fun list(j: JdbcClient, limit: Int, offset: Int, platformOnly: Boolean = false): List<AuditEntry> =
        j.sql("""SELECT id, tenant_id, actor_type, actor_id, action, resource, resource_id, request_id, detail::text, created_at
                 FROM audit_logs ${if (platformOnly) "WHERE tenant_id IS NULL" else ""}
                 ORDER BY created_at DESC, id LIMIT :l OFFSET :o""")
            .param("l", limit).param("o", offset)
            .query { rs, _ ->
                AuditEntry(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3),
                    rs.getObject(4, UUID::class.java), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    mapper.readValue(rs.getString(9), Any::class.java), rs.getObject(10, OffsetDateTime::class.java))
            }.list()
}

@RestController
@Validated
class AuditController(private val db: Db, private val audit: Audit) {

    @GetMapping("\${tc.api-prefix}/audit-logs")
    fun tenantLogs(@RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
                   @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) offset: Int): Map<String, Any> {
        val p = Caller.user()
        p.require("audit_logs", com.tenantcore.security.Action.READ)
        return mapOf("items" to db.tenant(p.tenantId) { audit.list(it, limit, offset) })
    }

    @GetMapping("\${tc.api-prefix}/platform/audit-logs")
    fun platformLogs(@RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
                     @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) offset: Int): Map<String, Any> {
        Caller.platform()
        return mapOf("items" to db.platform { audit.list(it, limit, offset, platformOnly = true) })
    }
}
