package com.tenantcore.email

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.tenantcore.audit.Audit
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.config.TcProperties
import com.tenantcore.db.Db
import com.tenantcore.platform.PlatformSettingsStore
import com.tenantcore.security.Action
import com.tenantcore.security.Crypto
import com.tenantcore.security.UserPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.InetAddress
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID

// ------------------------------------------------------------------------------------------------
// Templates: built-in defaults, overridable platform-wide and per tenant from the dashboard.
// ------------------------------------------------------------------------------------------------

data class TemplateDef(val key: String, val label: String, val variables: List<String>, val subject: String, val html: String, val text: String)

object Templates {
    private fun layout(body: String) = """
        <div style="font-family:Arial,Helvetica,sans-serif;max-width:560px;margin:0 auto;padding:24px;color:#101828">
        <p style="font-size:18px;font-weight:bold;margin:0 0 16px">{{business_name}}</p>
        $body
        <p style="font-size:12px;color:#667085;margin-top:32px">Sent by {{business_name}}.</p></div>""".trimIndent()

    private fun button(label: String) =
        """<p><a href="{{link}}" style="display:inline-block;background:{{brand_color}};color:#ffffff;padding:10px 18px;border-radius:6px;text-decoration:none">$label</a></p>
           <p style="font-size:12px;color:#667085">Or paste this address into your browser:<br>{{link}}</p>"""

    val all: Map<String, TemplateDef> = listOf(
        TemplateDef("user_invite", "Invitation", listOf("business_name", "user_name", "inviter_name", "link", "expires_hours", "brand_color"),
            "You're invited to {{business_name}}",
            layout("<p>Hello {{user_name}},</p><p>{{inviter_name}} invited you to join <b>{{business_name}}</b>. Choose a password to get started.</p>" +
                button("Accept invitation") + "<p>This link expires in {{expires_hours}} hours.</p>"),
            "Hello {{user_name}},\n\n{{inviter_name}} invited you to join {{business_name}}. Choose a password here:\n{{link}}\n\nThis link expires in {{expires_hours}} hours."),
        TemplateDef("password_reset", "Password reset", listOf("business_name", "user_name", "link", "expires_minutes", "brand_color"),
            "Reset your {{business_name}} password",
            layout("<p>Hello {{user_name}},</p><p>We received a request to reset your password.</p>" + button("Choose a new password") +
                "<p>This link expires in {{expires_minutes}} minutes. If you did not ask for this, you can ignore this email.</p>"),
            "Hello {{user_name}},\n\nReset your password here:\n{{link}}\n\nThis link expires in {{expires_minutes}} minutes. If you did not ask for this, ignore this email."),
        TemplateDef("password_changed", "Password changed", listOf("business_name", "user_name", "brand_color"),
            "Your {{business_name}} password was changed",
            layout("<p>Hello {{user_name}},</p><p>Your password was just changed and you were signed out everywhere else.</p>" +
                "<p>If this was not you, contact your administrator straight away.</p>"),
            "Hello {{user_name}},\n\nYour password was just changed and you were signed out everywhere else. If this was not you, contact your administrator."),
        TemplateDef("order_confirmation", "Order confirmation",
            listOf("business_name", "customer_name", "order_number", "order_total", "currency", "items", "brand_color"),
            "Your {{business_name}} order #{{order_number}}",
            layout("<p>Hello {{customer_name}},</p><p>Thank you for your order <b>#{{order_number}}</b>.</p>" +
                "<div style=\"white-space:pre-line;background:#f9fafb;padding:12px;border-radius:6px\">{{items}}</div>" +
                "<p>Total: <b>{{order_total}} {{currency}}</b></p><p>We will let you know when it is on its way.</p>"),
            "Hello {{customer_name}},\n\nThank you for your order #{{order_number}}.\n\n{{items}}\n\nTotal: {{order_total}} {{currency}}"),
        TemplateDef("customer_verify_email", "Customer: confirm email", listOf("business_name", "customer_name", "link", "expires_hours", "brand_color"),
            "Confirm your email for {{business_name}}",
            layout("<p>Hello {{customer_name}},</p><p>Please confirm your email address to finish setting up your account.</p>" +
                button("Confirm email") + "<p>This link expires in {{expires_hours}} hours.</p>"),
            "Hello {{customer_name}},\n\nConfirm your email address here:\n{{link}}\n\nThis link expires in {{expires_hours}} hours."),
        TemplateDef("customer_password_reset", "Customer: password reset", listOf("business_name", "customer_name", "link", "expires_minutes", "brand_color"),
            "Reset your {{business_name}} password",
            layout("<p>Hello {{customer_name}},</p><p>We received a request to reset your password.</p>" + button("Choose a new password") +
                "<p>This link expires in {{expires_minutes}} minutes. If you did not ask for this, you can ignore this email.</p>"),
            "Hello {{customer_name}},\n\nReset your password here:\n{{link}}\n\nThis link expires in {{expires_minutes}} minutes."),
        TemplateDef("test", "Test message", listOf("business_name", "brand_color"),
            "Test email from {{business_name}}",
            layout("<p>Email delivery works.</p>"),
            "Email delivery works."),
    ).associateBy { it.key }

    private val VAR = Regex("\\{\\{\\s*([a-z_]+)\\s*}}")

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /** Plain substitution, no logic: values are HTML-escaped in the HTML body; subjects lose line breaks. */
    fun render(subject: String, html: String, text: String, vars: Map<String, String>): Triple<String, String, String> {
        fun sub(t: String, esc: (String) -> String) = VAR.replace(t) { m -> esc(vars[m.groupValues[1]] ?: "") }
        return Triple(sub(subject) { it }.replace(Regex("[\\r\\n]+"), " ").take(300), sub(html, ::escape), sub(text) { it })
    }
}

data class TemplateOut(val key: String, val label: String, val variables: List<String>, val subject: String, val html: String, val text: String,
                       val source: String)
data class TemplateIn(@field:NotBlank @field:Size(max = 300) val subject: String,
                      @field:NotBlank @field:Size(max = 100_000) val html: String,
                      @field:NotBlank @field:Size(max = 50_000) val text: String)

// ------------------------------------------------------------------------------------------------
// SMTP settings
// ------------------------------------------------------------------------------------------------

data class SmtpSettings(
    @field:NotBlank @field:Size(max = 255) @field:Pattern(regexp = "^[A-Za-z0-9.-]+$", message = "must be a host name") val host: String = "",
    @field:Min(1) @field:Max(65535) val port: Int = 587,
    @field:Size(max = 320) val username: String? = null,
    @field:Pattern(regexp = "^(starttls|ssl|none)$", message = "must be starttls, ssl or none") val security: String = "starttls",
    @field:NotBlank @field:Email @field:Size(max = 320) val fromAddress: String = "",
    @field:Size(max = 200) val fromName: String? = null,
)
data class SmtpIn(@field:Valid val settings: SmtpSettings, /** Write-only; omit to keep the stored password. */ @field:Size(max = 500) val password: String? = null)
data class SmtpOut(val configured: Boolean, val settings: SmtpSettings?, val passwordSet: Boolean)
data class TestEmailIn(@field:NotBlank @field:Email val to: String)

data class OutboxOut(val id: UUID, val tenantId: UUID?, val toAddress: String, val subject: String, val templateKey: String?, val status: String,
                     val attempts: Int, val lastError: String?, val createdAt: OffsetDateTime, val sentAt: OffsetDateTime?)

@Service
class EmailService(
    private val db: Db, private val platformSettings: PlatformSettingsStore, private val crypto: Crypto, private val mapper: ObjectMapper,
    private val audit: Audit, private val props: TcProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // -- templates --------------------------------------------------------------------------------

    /** Tenant override, then platform override, then the built-in default. */
    fun template(j: JdbcClient, tenantId: UUID?, key: String): TemplateOut {
        val def = Templates.all[key] ?: throw NotFound("Template")
        val rows = j.sql("SELECT tenant_id, subject, html, text FROM email_templates WHERE key = :k AND (tenant_id IS NULL OR tenant_id = CAST(:t AS uuid))")
            .param("k", key).param("t", tenantId)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to Triple(rs.getString(2), rs.getString(3), rs.getString(4)) }.list()
        val tenantRow = rows.firstOrNull { it.first != null && it.first == tenantId }
        val platformRow = rows.firstOrNull { it.first == null }
        val (src, t) = when {
            tenantRow != null -> "tenant" to tenantRow.second
            platformRow != null -> "platform" to platformRow.second
            else -> "default" to Triple(def.subject, def.html, def.text)
        }
        return TemplateOut(def.key, def.label, def.variables, t.first, t.second, t.third, src)
    }

    fun saveTemplate(j: JdbcClient, tenantId: UUID?, key: String, input: TemplateIn): TemplateOut {
        if (key !in Templates.all) throw NotFound("Template")
        j.sql("DELETE FROM email_templates WHERE key = :k AND tenant_id IS NOT DISTINCT FROM CAST(:t AS uuid)").param("k", key).param("t", tenantId).update()
        j.sql("INSERT INTO email_templates (tenant_id, key, subject, html, text) VALUES (CAST(:t AS uuid), :k, :s, :h, :x)")
            .param("t", tenantId).param("k", key).param("s", input.subject).param("h", input.html).param("x", input.text).update()
        audit.record(j, tenantId, "email_template.update", "settings", key)
        return template(j, tenantId, key)
    }

    fun resetTemplate(j: JdbcClient, tenantId: UUID?, key: String): TemplateOut {
        if (key !in Templates.all) throw NotFound("Template")
        j.sql("DELETE FROM email_templates WHERE key = :k AND tenant_id IS NOT DISTINCT FROM CAST(:t AS uuid)").param("k", key).param("t", tenantId).update()
        audit.record(j, tenantId, "email_template.reset", "settings", key)
        return template(j, tenantId, key)
    }

    /** Common variables: the business name and brand colour come from the tenant's settings. */
    fun baseVars(j: JdbcClient, tenantId: UUID?): Map<String, String> {
        if (tenantId == null) return mapOf("business_name" to platformSettings.general(props).platformName, "brand_color" to "#4f46e5")
        return j.sql("SELECT coalesce(s.display_name, t.name), coalesce(s.primary_color, '#4f46e5') FROM tenants t LEFT JOIN tenant_settings s ON s.tenant_id = t.id WHERE t.id = :t")
            .param("t", tenantId).query { rs, _ -> mapOf("business_name" to rs.getString(1), "brand_color" to rs.getString(2)) }.single()
    }

    /** Renders [key] and queues it in the caller's transaction, so the email exists only if the change commits. */
    fun queue(j: JdbcClient, tenantId: UUID?, to: String, key: String, vars: Map<String, String>): UUID {
        val t = template(j, tenantId, key)
        val (subject, html, text) = Templates.render(t.subject, t.html, t.text, baseVars(j, tenantId) + vars)
        return j.sql("INSERT INTO email_outbox (tenant_id, to_address, subject, html, text, template_key) VALUES (CAST(:t AS uuid), :to, :s, :h, :x, :k) RETURNING id")
            .param("t", tenantId).param("to", to).param("s", subject).param("h", html).param("x", text).param("k", key)
            .query(UUID::class.java).single()
    }

    fun log(j: JdbcClient, limit: Int, offset: Int): List<OutboxOut> =
        j.sql("""SELECT id, tenant_id, to_address, subject, template_key, status, attempts, last_error, created_at, sent_at
                 FROM email_outbox ORDER BY created_at DESC, id LIMIT :l OFFSET :o""").param("l", limit).param("o", offset)
            .query { rs, _ -> OutboxOut(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getObject(9, OffsetDateTime::class.java), rs.getObject(10, OffsetDateTime::class.java)) }
            .list()

    // -- SMTP -------------------------------------------------------------------------------------

    fun platformSmtp(): Pair<SmtpSettings, String?>? {
        val (v, secret) = platformSettings.read("email")
        if (v.isEmpty()) return null
        return mapper.convertValue<SmtpSettings>(v) to secret
    }

    fun tenantSmtp(j: JdbcClient): Pair<SmtpSettings, String?>? =
        j.sql("SELECT host, port, username, security, from_address, from_name, password_enc, tenant_id FROM tenant_smtp").query { rs, _ ->
            SmtpSettings(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) to
                rs.getString(7)?.let { crypto.decrypt(it, "smtp:${rs.getObject(8, UUID::class.java)}") }
        }.optional().orElse(null)

    /**
     * A tenant's own mail server must be on the public internet: otherwise a tenant admin could make
     * the platform connect to internal services. Checked on save and again before every send.
     */
    fun checkPublicHost(host: String) {
        val addrs = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { throw Invalid("Could not resolve the mail server '$host'.") }
        val bad = addrs.firstOrNull { a ->
            a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress ||
                (a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) ||
                (a.address.size == 4 && (a.address[0].toInt() and 0xff) == 100 && (a.address[1].toInt() and 0xc0) == 64)
        }
        if (bad != null) throw Invalid("The mail server must have a public address.")
    }

    private fun sender(s: SmtpSettings, password: String?) = JavaMailSenderImpl().apply {
        host = s.host; port = s.port
        if (!s.username.isNullOrBlank()) { username = s.username; this.password = password }
        javaMailProperties = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.auth", (!s.username.isNullOrBlank()).toString())
            put("mail.smtp.connectiontimeout", "10000"); put("mail.smtp.timeout", "15000"); put("mail.smtp.writetimeout", "15000")
            when (s.security) {
                "starttls" -> { put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true") }
                "ssl" -> { put("mail.smtp.ssl.enable", "true"); put("mail.smtp.ssl.checkserveridentity", "true") }
            }
        }
    }

    /** Sends right away (no queue). Used by the worker and by "send test email". */
    fun deliver(smtp: SmtpSettings, password: String?, fromName: String?, to: String, subject: String, html: String, text: String) {
        val mail = sender(smtp, password)
        val msg = mail.createMimeMessage()
        MimeMessageHelper(msg, true, "UTF-8").apply {
            setFrom(smtp.fromAddress, fromName ?: smtp.fromName ?: smtp.fromAddress)
            setTo(to); setSubject(subject); setText(text, html)
        }
        mail.send(msg)
    }

    /** Resolves which server a tenant's mail goes through: its own, or the platform's. */
    fun route(tenantId: UUID?): Triple<SmtpSettings, String?, String?>? = db.platform(tenantId) { j ->
        val own = if (tenantId != null) tenantSmtp(j) else null
        if (own != null) { checkPublicHost(own.first.host); Triple(own.first, own.second, own.first.fromName) }
        else platformSmtp()?.let { (s, p) -> Triple(s, p, if (tenantId != null) baseVars(j, tenantId)["business_name"] else s.fromName) }
    }

    fun sendTest(tenantId: UUID?, to: String): Map<String, Any?> {
        val route = route(tenantId) ?: return mapOf("ok" to false, "error" to "Email is not configured yet.")
        val vars = db.platform(tenantId) { baseVars(it, tenantId) }
        val t = db.platform(tenantId) { template(it, tenantId, "test") }
        val (subject, html, text) = Templates.render(t.subject, t.html, t.text, vars)
        return runCatching { deliver(route.first, route.second, route.third, to, subject, html, text); mapOf<String, Any?>("ok" to true) }
            .getOrElse { mapOf("ok" to false, "error" to (it.message ?: it.javaClass.simpleName).take(500)) }
    }
}

/**
 * Sends queued mail. Rows are leased (next attempt pushed out) and committed before any network
 * call, so several API instances can run this safely and a crash mid-send only delays a retry.
 * Failures back off exponentially; after the last attempt the row is marked dead and kept for the log.
 */
@Component
class EmailWorker(private val db: Db, private val email: EmailService, private val props: TcProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${tc.email.poll-ms:5000}", initialDelay = 2000)
    fun tick() { if (props.email.workerEnabled) runCatching { drain() }.onFailure { log.warn("email worker: {}", it.message) } }

    data class Job(val id: UUID, val tenantId: UUID?, val to: String, val subject: String, val html: String, val text: String, val attempts: Int)

    fun drain(): Int {
        val jobs = db.platform { j ->
            j.sql("""UPDATE email_outbox SET next_attempt_at = now() + interval '5 minutes', attempts = attempts + 1
                     WHERE id IN (SELECT id FROM email_outbox WHERE status IN ('pending', 'failed') AND next_attempt_at <= now()
                                  ORDER BY next_attempt_at LIMIT 20 FOR UPDATE SKIP LOCKED)
                     RETURNING id, tenant_id, to_address, subject, html, text, attempts""")
                .query { rs, _ -> Job(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getInt(7)) }.list()
        }
        jobs.forEach { job ->
            val error = runCatching {
                val route = email.route(job.tenantId) ?: error("Email is not configured. Set it up in the platform email settings.")
                email.deliver(route.first, route.second, route.third, job.to, job.subject, job.html, job.text)
            }.exceptionOrNull()
            db.platform { j ->
                if (error == null) j.sql("UPDATE email_outbox SET status = 'sent', sent_at = now(), last_error = NULL WHERE id = :id").param("id", job.id).update()
                else {
                    val dead = job.attempts >= props.email.maxAttempts
                    val backoff = minOf(30L * (1L shl minOf(job.attempts - 1, 20)), 6 * 3600L)
                    j.sql("UPDATE email_outbox SET status = :s, last_error = :e, next_attempt_at = now() + make_interval(secs => :b) WHERE id = :id")
                        .param("s", if (dead) "dead" else "failed").param("e", (error.message ?: error.javaClass.simpleName).take(1000))
                        .param("b", backoff.toDouble()).param("id", job.id).update()
                }
            }
        }
        return jobs.size
    }
}

// ------------------------------------------------------------------------------------------------
// HTTP
// ------------------------------------------------------------------------------------------------

@RestController
@RequestMapping("\${tc.api-prefix}/platform/email")
@Validated
class PlatformEmailController(
    private val email: EmailService, private val settings: PlatformSettingsStore, private val db: Db, private val audit: Audit,
    private val mapper: ObjectMapper,
) {
    @GetMapping("/settings")
    fun get(): SmtpOut = email.platformSmtp().let { SmtpOut(it != null, it?.first, !it?.second.isNullOrEmpty()) }

    @PutMapping("/settings")
    fun put(@Valid @RequestBody body: SmtpIn): SmtpOut {
        db.platform { j ->
            settings.write(j, "email", mapper.convertValue(body.settings), body.password)
            audit.record(j, null, "platform_settings.update", "platform_settings", "email", mapOf("host" to body.settings.host))
        }
        return get()
    }

    @PostMapping("/test")
    fun test(@Valid @RequestBody body: TestEmailIn) = email.sendTest(null, body.to)

    @GetMapping("/log")
    fun log(@RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int, @RequestParam(defaultValue = "0") @Min(0) offset: Int) =
        mapOf("items" to db.platform { email.log(it, limit, offset) })

    @GetMapping("/templates")
    fun templates() = mapOf("items" to db.platform { j -> Templates.all.keys.map { email.template(j, null, it) } })

    @PutMapping("/templates/{key}")
    fun save(@PathVariable key: String, @Valid @RequestBody body: TemplateIn) = db.platform { email.saveTemplate(it, null, key, body) }

    @DeleteMapping("/templates/{key}")
    fun reset(@PathVariable key: String) = db.platform { email.resetTemplate(it, null, key) }
}

@RestController
@RequestMapping("\${tc.api-prefix}/email")
@Validated
class TenantEmailController(private val email: EmailService, private val db: Db, private val audit: Audit, private val crypto: Crypto) {

    private fun admin(): UserPrincipal = Caller.user().also { it.requireModule("email"); it.require("settings", Action.MANAGE) }

    /** The workspace's own mail server, if any. Without one, mail goes through the platform's server. */
    @GetMapping("/settings")
    fun get(): SmtpOut { val p = admin(); return db.tenant(p.tenantId) { j -> email.tenantSmtp(j).let { SmtpOut(it != null, it?.first, !it?.second.isNullOrEmpty()) } } }

    @PutMapping("/settings")
    fun put(@Valid @RequestBody body: SmtpIn): SmtpOut {
        val p = admin()
        if (body.settings.port !in setOf(25, 465, 587, 2525)) throw Invalid("Use a standard mail port: 25, 465, 587 or 2525.")
        if (body.settings.security == "none") throw Invalid("A workspace mail server must use TLS (starttls or ssl).")
        email.checkPublicHost(body.settings.host)
        db.tenant(p.tenantId) { j ->
            val s = body.settings
            val enc = body.password?.takeIf { it.isNotEmpty() }?.let { crypto.encrypt(it, "smtp:${p.tenantId}") }
            j.sql("""INSERT INTO tenant_smtp (tenant_id, host, port, username, password_enc, security, from_address, from_name)
                     VALUES (:t, :h, :p, :u, :pw, :sec, :f, :fn)
                     ON CONFLICT (tenant_id) DO UPDATE SET host = EXCLUDED.host, port = EXCLUDED.port, username = EXCLUDED.username,
                       security = EXCLUDED.security, from_address = EXCLUDED.from_address, from_name = EXCLUDED.from_name, updated_at = now(),
                       password_enc = CASE WHEN :keep THEN tenant_smtp.password_enc ELSE EXCLUDED.password_enc END""")
                .param("t", p.tenantId).param("h", s.host).param("p", s.port).param("u", s.username).param("pw", enc).param("sec", s.security)
                .param("f", s.fromAddress).param("fn", s.fromName).param("keep", body.password == null).update()
            audit.record(j, p.tenantId, "email_settings.update", "settings", "smtp", mapOf("host" to s.host))
        }
        return get()
    }

    @DeleteMapping("/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete() {
        val p = admin()
        db.tenant(p.tenantId) { j ->
            j.sql("DELETE FROM tenant_smtp").update()
            audit.record(j, p.tenantId, "email_settings.reset", "settings", "smtp")
        }
    }

    @PostMapping("/test")
    fun test(@Valid @RequestBody body: TestEmailIn) = email.sendTest(admin().tenantId, body.to)

    @GetMapping("/log")
    fun log(@RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int, @RequestParam(defaultValue = "0") @Min(0) offset: Int): Map<String, Any> {
        val p = admin()
        return mapOf("items" to db.tenant(p.tenantId) { email.log(it, limit, offset) })
    }

    @GetMapping("/templates")
    fun templates(): Map<String, Any> { val p = admin(); return mapOf("items" to db.tenant(p.tenantId) { j -> Templates.all.keys.map { email.template(j, p.tenantId, it) } }) }

    @PutMapping("/templates/{key}")
    fun save(@PathVariable key: String, @Valid @RequestBody body: TemplateIn): TemplateOut { val p = admin(); return db.tenant(p.tenantId) { email.saveTemplate(it, p.tenantId, key, body) } }

    @DeleteMapping("/templates/{key}")
    fun reset(@PathVariable key: String): TemplateOut { val p = admin(); return db.tenant(p.tenantId) { email.resetTemplate(it, p.tenantId, key) } }

    /** Renders a template with sample values, without sending anything. */
    @PostMapping("/templates/{key}/preview")
    fun preview(@PathVariable key: String, @RequestBody(required = false) body: TemplateIn?): Map<String, String> {
        val p = admin()
        return db.tenant(p.tenantId) { j ->
            val t = email.template(j, p.tenantId, key)
            val sample = mapOf("user_name" to "Alex Sample", "inviter_name" to "Sam Admin", "link" to "https://example.com/link",
                "expires_hours" to "72", "expires_minutes" to "60")
            val (s, h, x) = Templates.render(body?.subject ?: t.subject, body?.html ?: t.html, body?.text ?: t.text, email.baseVars(j, p.tenantId) + sample)
            mapOf("subject" to s, "html" to h, "text" to x)
        }
    }
}
