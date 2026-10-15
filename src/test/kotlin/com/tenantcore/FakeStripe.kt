package com.tenantcore

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A stand-in for the few Stripe REST endpoints the app calls, so the payment code path (request
 * format, auth header, idempotency keys, webhooks) is exercised without the real Stripe. It is not
 * Stripe: behaviour beyond these endpoints is not simulated.
 */
class FakeStripe {
    data class Call(val method: String, val path: String, val auth: String?, val idempotencyKey: String?, val form: Map<String, String>)

    val calls = CopyOnWriteArrayList<Call>()
    private val byKey = ConcurrentHashMap<String, String>()
    private val secrets = ConcurrentHashMap<String, String>()
    private val n = AtomicInteger()
    @Volatile var failNextIntent: String? = null
    private lateinit var server: HttpServer

    val baseUrl get() = "http://localhost:${server.address.port}"

    fun start() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val form = body.split("&").filter { it.contains("=") }.associate {
                val (k, v) = it.split("=", limit = 2); URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
            val path = ex.requestURI.path
            val key = ex.requestHeaders.getFirst("Idempotency-Key")
            calls += Call(ex.requestMethod, path, ex.requestHeaders.getFirst("Authorization"), key, form)
            val (status, json) = when {
                ex.requestHeaders.getFirst("Authorization") != "Bearer sk_test_fake" ->
                    401 to """{"error":{"message":"Invalid API Key provided"}}"""
                ex.requestMethod == "POST" && path == "/v1/payment_intents" -> failNextIntent?.let { failNextIntent = null; 402 to """{"error":{"message":"$it"}}""" }
                    ?: (200 to byKey.computeIfAbsent(key ?: "none-${n.incrementAndGet()}") {
                        val id = "pi_${n.incrementAndGet()}"
                        secrets[id] = "${id}_secret_x"
                        """{"id":"$id","object":"payment_intent","status":"requires_payment_method","client_secret":"${id}_secret_x","amount":${form["amount"]}}"""
                    })
                ex.requestMethod == "GET" && path.startsWith("/v1/payment_intents/") -> path.removePrefix("/v1/payment_intents/").let { id ->
                    secrets[id]?.let { 200 to """{"id":"$id","client_secret":"$it"}""" } ?: (404 to """{"error":{"message":"No such payment_intent"}}""")
                }
                ex.requestMethod == "POST" && path == "/v1/refunds" -> 200 to byKey.computeIfAbsent(key ?: "none-${n.incrementAndGet()}") {
                    """{"id":"re_${n.incrementAndGet()}","object":"refund","status":"pending","amount":${form["amount"]}}"""
                }
                else -> 404 to """{"error":{"message":"not found"}}"""
            }
            val bytes = json.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    /** A Stripe-Signature header for [payload], as Stripe computes it. */
    fun sign(payload: String, secret: String, t: Long = System.currentTimeMillis() / 1000): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return "t=$t,v1=" + mac.doFinal("$t.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
