package com.tenantcore

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

/**
 * Boots the whole application against real PostgreSQL 16 and Redis 7 containers (shared by all test
 * classes) and talks to it over HTTP, exactly like the dashboard does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var mapper: ObjectMapper

    companion object {
        const val PASSWORD = "Correct-Horse-Battery-9"
        const val ADMIN_EMAIL = "root@example.com"
        const val ADMIN_PASSWORD = "Root-Passw0rd-For-Tests"

        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withDatabaseName("tenantcore").withUsername("postgres").withPassword("postgres")
            .withCopyFileToContainer(MountableFile.forClasspathResource("init-roles.sql"), "/docker-entrypoint-initdb.d/01-roles.sql")
            .also { it.start() }

        val redis: GenericContainer<*> = GenericContainer("redis:7").withExposedPorts(6379).also { it.start() }

        /** SMTP server with an HTTP API to read what was "sent". */
        val mailpit: GenericContainer<*> = GenericContainer("axllent/mailpit:v1.27").withExposedPorts(1025, 8025).also { it.start() }

        /** S3-compatible object store. */
        val s3: GenericContainer<*> = GenericContainer("adobe/s3mock:4.7.0").withExposedPorts(9090)
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "tc-test").also { it.start() }

        /** Stand-in for the Stripe API (see FakeStripe). */
        val stripe = FakeStripe().also { it.start() }

        /** Stand-ins for Google OAuth, FCM, geocoders and a webhook receiver (see FakeApis). */
        val apis = FakeApis().also { it.start() }

        val backupDir: java.nio.file.Path = java.nio.file.Files.createTempDirectory("tc-backups")
        val storageDir: java.nio.file.Path = java.nio.file.Files.createTempDirectory("tc-storage")

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("tc.db.url") { postgres.jdbcUrl }
            r.add("tc.db.app-password") { "app_test_pw" }
            r.add("tc.db.owner-password") { "owner_test_pw" }
            r.add("spring.data.redis.url") { "redis://${redis.host}:${redis.getMappedPort(6379)}/0" }
            r.add("tc.jwt.keys") { """{"k1":"test-key-0123456789abcdef0123456789abcdef","k0":"old-key-0123456789abcdef0123456789abcdef"}""" }
            r.add("tc.jwt.active-kid") { "k1" }
            r.add("tc.encryption-key") { Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }) }
            r.add("tc.environment") { "test" }
            r.add("tc.seed.admin-email") { ADMIN_EMAIL }
            r.add("tc.seed.admin-password") { ADMIN_PASSWORD }
            // Generous limits so tests are not throttled; the rate-limit test sets its own keys.
            r.add("tc.rate-limit.login-per-min") { "1000" }
            r.add("tc.rate-limit.principal-per-min") { "100000" }
            r.add("tc.storage-dir") { storageDir.toString() }
            r.add("tc.public-url") { "https://admin.example.test" }
            r.add("tc.email.poll-ms") { "300" }
            r.add("tc.payments.stripe-api-base") { stripe.baseUrl }
            r.add("tc.imports.poll-ms") { "200" }
            r.add("tc.jobs.poll-ms") { "200" }
            r.add("tc.backups.dir") { backupDir.toString() }
            // The webhook receiver is on localhost; production refuses private addresses (see NetGuardTest).
            r.add("tc.webhooks.allow-private-targets") { "true" }
            r.add("tc.push.fcm-base-url") { apis.baseUrl }
            r.add("tc.push.google-token-url") { "${apis.baseUrl}/token" }
            r.add("tc.maps.nominatim-url") { apis.baseUrl }
            r.add("tc.maps.google-url") { apis.baseUrl }
        }

        /** A superuser connection, which bypasses RLS: used only to check what really is in the tables. */
        fun superuser() = DriverManager.getConnection(postgres.jdbcUrl, "postgres", "postgres")
        /** The runtime role, subject to RLS, for checking isolation below the API. */
        fun appRole() = DriverManager.getConnection(postgres.jdbcUrl, "tc_app", "app_test_pw")
    }

    private val http = HttpClient.newHttpClient()

    data class Res(val status: Int, val body: JsonNode, val headers: java.net.http.HttpHeaders) {
        operator fun get(field: String): JsonNode = body.path(field)
        override fun toString() = "$status $body"
    }

    fun call(method: HttpMethod, path: String, body: Any? = null, token: String? = null, headers: Map<String, String> = emptyMap()): Res {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method.name(), if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        if (token != null) b.header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        val json = if (r.body().isBlank()) mapper.createObjectNode() else runCatching { mapper.readTree(r.body()) }.getOrElse { mapper.createObjectNode().put("raw", r.body()) }
        return Res(r.statusCode(), json, r.headers())
    }

    fun get(path: String, token: String? = null) = call(HttpMethod.GET, "/api/v1$path", token = token)
    fun post(path: String, body: Any? = null, token: String? = null) = call(HttpMethod.POST, "/api/v1$path", body, token)
    fun put(path: String, body: Any?, token: String? = null) = call(HttpMethod.PUT, "/api/v1$path", body, token)
    fun patch(path: String, body: Any?, token: String? = null) = call(HttpMethod.PATCH, "/api/v1$path", body, token)
    fun delete(path: String, token: String? = null) = call(HttpMethod.DELETE, "/api/v1$path", token = token)

    /** multipart/form-data upload of [bytes] as part "file". */
    fun upload(token: String, filename: String, bytes: ByteArray, query: String = ""): Res = uploadTo("/api/v1/files$query", token, filename, bytes)

    fun uploadTo(path: String, token: String, filename: String, bytes: ByteArray): Res {
        val boundary = "----tc" + UUID.randomUUID().toString().replace("-", "")
        val body = java.io.ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val req = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=$boundary")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").build()
        val r = http.send(req, HttpResponse.BodyHandlers.ofString())
        return Res(r.statusCode(), if (r.body().isBlank()) mapper.createObjectNode() else mapper.readTree(r.body()), r.headers())
    }

    /** Raw GET returning status, bytes and headers (for file downloads). */
    fun raw(path: String, token: String? = null): HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET()
        if (token != null) b.header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    // -- Mailpit --------------------------------------------------------------------------------------
    private fun mailApi(path: String, method: String = "GET"): JsonNode {
        val r = http.send(HttpRequest.newBuilder(URI("http://${mailpit.host}:${mailpit.getMappedPort(8025)}/api/v1$path"))
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        return if (r.body().isBlank()) mapper.createObjectNode() else mapper.readTree(r.body())
    }

    /** Points the platform's SMTP settings at Mailpit. */
    fun usePlatformMailpit() {
        val r = put("/platform/email/settings", mapOf("settings" to mapOf("host" to mailpit.host, "port" to mailpit.getMappedPort(1025),
            "security" to "none", "from_address" to "no-reply@platform.example.test", "from_name" to "Platform")), platformToken())
        check(r.status == 200) { r.toString() }
    }

    data class Mail(val subject: String, val html: String, val text: String)

    /** Waits for an email to [to] whose subject contains [subject]. */
    fun awaitMail(to: String, subject: String = "", timeoutMs: Long = 15_000): Mail {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hit = mailApi("/messages?limit=200")["messages"].firstOrNull { m ->
                m["To"].any { it["Address"].asText().equals(to, true) } && m["Subject"].asText().contains(subject)
            }
            if (hit != null) {
                val full = mailApi("/message/${hit["ID"].asText()}")
                return Mail(full["Subject"].asText(), full["HTML"].asText(), full["Text"].asText())
            }
            Thread.sleep(200)
        }
        error("no email to $to with subject containing '$subject' within ${timeoutMs}ms")
    }

    fun mailCount(to: String): Int = mailApi("/messages?limit=500")["messages"].count { m -> m["To"].any { it["Address"].asText().equals(to, true) } }

    fun platformToken(): String {
        val r = post("/platform/auth/login", mapOf("email" to ADMIN_EMAIL, "password" to ADMIN_PASSWORD))
        check(r.status == 200) { r.toString() }
        return r["access_token"].asText()
    }

    data class Tenant(val id: String, val slug: String, val ownerEmail: String, val token: String, val refresh: String)

    fun newTenant(modules: List<String>? = null): Tenant {
        val slug = "t-" + UUID.randomUUID().toString().take(8)
        val email = "owner@$slug.example.com"
        val body = buildMap {
            put("slug", slug); put("name", "Tenant $slug"); put("owner_email", email); put("owner_password", PASSWORD)
            if (modules != null) put("modules", modules)
        }
        val r = post("/platform/tenants", body, platformToken())
        check(r.status == 201) { r.toString() }
        val login = login(slug, email)
        return Tenant(r["tenant"]["id"].asText(), slug, email, login["access_token"].asText(), login["refresh_token"].asText())
    }

    fun login(slug: String, email: String, password: String = PASSWORD): Res {
        val r = post("/auth/login", mapOf("tenant" to slug, "email" to email, "password" to password))
        check(r.status == 200) { r.toString() }
        return r
    }

    /** Creates a user with the given roles and returns (user id, access token). */
    fun newUser(t: Tenant, roleIds: List<String> = emptyList(), admin: Boolean = false): Pair<String, String> {
        val email = "u-${UUID.randomUUID().toString().take(8)}@${t.slug}.example.com"
        val r = post("/users", mapOf("email" to email, "password" to PASSWORD, "role_ids" to roleIds, "is_tenant_admin" to admin), t.token)
        check(r.status == 201) { r.toString() }
        return r["id"].asText() to login(t.slug, email)["access_token"].asText()
    }
}
