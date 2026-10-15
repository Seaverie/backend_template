package com.tenantcore

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local stand-ins for Google OAuth, Firebase Cloud Messaging, Nominatim and the Google Geocoding API,
 * plus a webhook receiver. They answer the few calls the app makes, in the documented formats; they
 * are not the real services.
 */
class FakeApis {
    data class Hit(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)

    private val mapper = ObjectMapper()
    val hits = CopyOnWriteArrayList<Hit>()
    val webhookStatus = ConcurrentHashMap<String, Int>()
    private val n = AtomicInteger()
    private lateinit var server: HttpServer

    /** The service account the tests upload; the fake checks the OAuth assertion against its public key. */
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val serviceAccountJson: String = mapper.writeValueAsString(mapOf(
        "type" to "service_account", "project_id" to "demo-shop-app", "client_email" to "push@demo-shop-app.iam.gserviceaccount.com",
        "private_key" to "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) + "\n-----END PRIVATE KEY-----\n"))

    val baseUrl get() = "http://localhost:${server.address.port}"
    fun calls(prefix: String) = hits.filter { it.path.startsWith(prefix) }

    fun start() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val path = ex.requestURI.path
            hits += Hit(ex.requestMethod, path, ex.requestURI.rawQuery, ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }, body)
            val (status, json) = when {
                path == "/token" -> oauth(body)
                path.startsWith("/v1/projects/") -> fcm(ex, body)
                path == "/search" -> 200 to """[{"display_name":"Siam Paragon, Bangkok, Thailand","lat":"13.7462","lon":"100.5347","address":{"country_code":"th"}}]"""
                path == "/reverse" -> 200 to """{"display_name":"Rama I Road, Bangkok, Thailand","lat":"13.7462","lon":"100.5347","address":{"country_code":"th"}}"""
                path == "/maps/api/geocode/json" ->
                    if (ex.requestURI.rawQuery?.contains("key=good-key") == true)
                        200 to """{"status":"OK","results":[{"formatted_address":"Chiang Mai, Thailand","geometry":{"location":{"lat":18.7883,"lng":98.9853}},
                                  "address_components":[{"short_name":"TH","types":["country"]}]}]}"""
                    else 200 to """{"status":"REQUEST_DENIED","results":[]}"""
                path.startsWith("/hooks/") -> (webhookStatus[path] ?: 200) to """{"ok":true}"""
                else -> 404 to "{}"
            }
            val bytes = json.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun oauth(body: String): Pair<Int, String> {
        val form = body.split("&").associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        if (form["grant_type"] != "urn:ietf:params:oauth:grant-type:jwt-bearer") return 400 to """{"error":"unsupported_grant_type"}"""
        val jwt = runCatching { SignedJWT.parse(form["assertion"]) }.getOrNull() ?: return 400 to """{"error":"invalid_grant"}"""
        val ok = jwt.verify(RSASSAVerifier(keyPair.public as RSAPublicKey)) &&
            jwt.jwtClaimsSet.getStringClaim("scope") == "https://www.googleapis.com/auth/firebase.messaging" &&
            jwt.jwtClaimsSet.issuer == "push@demo-shop-app.iam.gserviceaccount.com"
        return if (ok) 200 to """{"access_token":"ya29.fake-${n.incrementAndGet()}","expires_in":3600,"token_type":"Bearer"}"""
        else 400 to """{"error":"invalid_grant"}"""
    }

    private fun fcm(ex: HttpExchange, body: String): Pair<Int, String> {
        if (ex.requestHeaders.getFirst("Authorization")?.startsWith("Bearer ya29.fake-") != true) return 401 to """{"error":{"status":"UNAUTHENTICATED"}}"""
        val token = mapper.readTree(body).path("message").path("token").asText()
        return if (token.startsWith("bad-"))
            404 to """{"error":{"code":404,"status":"NOT_FOUND","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}"""
        else 200 to """{"name":"projects/demo-shop-app/messages/${n.incrementAndGet()}"}"""
    }
}
