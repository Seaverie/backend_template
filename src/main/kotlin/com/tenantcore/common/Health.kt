package com.tenantcore.common

import com.tenantcore.db.Db
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(private val db: Db, private val redis: StringRedisTemplate) {
    /** Liveness: the process is up. */
    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")

    /** Readiness: the database and Redis answer. */
    @GetMapping("/ready")
    fun ready(): ResponseEntity<Map<String, Any>> {
        val dbOk = runCatching { db.anonymous { it.sql("SELECT 1").query(Int::class.java).single() } == 1 }.getOrDefault(false)
        val redisOk = runCatching { redis.connectionFactory!!.connection.use { it.ping() } == "PONG" }.getOrDefault(false)
        val body = mapOf("status" to if (dbOk && redisOk) "ready" else "degraded", "database" to dbOk, "redis" to redisOk)
        return ResponseEntity.status(if (dbOk && redisOk) 200 else 503).body(body)
    }
}
