package com.tenantcore.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Base64

@ConfigurationProperties("tc")
data class TcProperties(
    val environment: String = "dev",
    val apiPrefix: String = "/api/v1",
    val db: Db = Db(),
    val jwt: Jwt = Jwt(),
    val encryptionKey: String = "",
    val platformMfaRequired: String = "",
    val seed: Seed = Seed(),
    val rateLimit: RateLimit = RateLimit(),
    val corsOrigins: String = "",
    /** Default base URL of the dashboard for links in emails; editable later in platform settings. */
    val publicUrl: String = "",
    val storageDir: String = "./storage",
    val files: Files = Files(),
    val email: Email = Email(),
    val payments: Payments = Payments(),
    val jobs: Jobs = Jobs(),
    val webhooks: Webhooks = Webhooks(),
    val push: Push = Push(),
    val maps: Maps = Maps(),
    val deliveries: Deliveries = Deliveries(),
    val backups: Backups = Backups(),
) {
    data class Jobs(val pollMs: Long = 1000, val workerEnabled: Boolean = true)
    /** allowPrivateTargets lets webhooks reach private/loopback addresses: for local development only. */
    data class Webhooks(val allowPrivateTargets: Boolean = false, val timeoutSeconds: Long = 10)
    /** Provider addresses are configuration, never tenant input, so a tenant cannot point them elsewhere. */
    data class Push(val fcmBaseUrl: String = "https://fcm.googleapis.com", val googleTokenUrl: String = "https://oauth2.googleapis.com/token")
    data class Maps(val nominatimUrl: String = "https://nominatim.openstreetmap.org", val googleUrl: String = "https://maps.googleapis.com",
                    val cacheDays: Int = 30, val providerCallsPerMin: Int = 60)
    data class Deliveries(val pingRetentionDays: Int = 30)
    /** Where the backup service writes (mounted read-only into the API so the dashboard can show backup health). */
    data class Backups(val dir: String = "/app/backups", val intervalHours: Int = 24)
    data class Files(val maxFileMb: Int = 20, val defaultQuotaMb: Long = 1024, val linkTtlSeconds: Long = 3600)
    /** stripeApiBase is only changed for tests (a local fake) or a Stripe-compatible proxy. */
    data class Payments(val stripeApiBase: String = "https://api.stripe.com", val webhookToleranceSeconds: Long = 300)
    data class Email(val pollMs: Long = 5000, val maxAttempts: Int = 8, val workerEnabled: Boolean = true)

    data class Db(
        val url: String = "",
        val appUser: String = "tc_app",
        val appPassword: String = "",
        val ownerUser: String = "tc_owner",
        val ownerPassword: String = "",
        val poolSize: Int = 10,
        val statementTimeoutMs: Int = 5000,
    )

    data class Jwt(
        val keys: String = "",
        val activeKid: String = "",
        val issuer: String = "tenantcore",
        val accessTtlSeconds: Long = 600,
        val refreshTtlSeconds: Long = 1_209_600,
        val platformTtlSeconds: Long = 3600,
    )

    data class Seed(val adminEmail: String = "", val adminPassword: String = "")

    data class RateLimit(
        val loginPerMin: Int = 10,
        val principalPerMin: Int = 300,
        val tenantDefaultPerMin: Int = 1200,
        val tenantMaxPerMin: Int = 12000,
    )

    val isProduction get() = environment == "production"

    /** MFA is required for platform admins in production unless explicitly configured. */
    val mfaRequired get() = platformMfaRequired.ifBlank { isProduction.toString() }.toBoolean()

    fun jwtKeys(): Map<String, ByteArray> {
        require(jwt.keys.isNotBlank()) { "TC_JWT_KEYS is required" }
        val parsed: Map<String, String> = ObjectMapper().readValue(jwt.keys)
        require(parsed.isNotEmpty()) { "TC_JWT_KEYS must list at least one key" }
        return parsed.mapValues { (kid, secret) ->
            require(secret.toByteArray().size >= 32) { "JWT key '$kid' must be at least 32 bytes" }
            require(!secret.contains("CHANGE_ME")) { "Replace the CHANGE_ME placeholder in TC_JWT_KEYS" }
            secret.toByteArray()
        }
    }

    fun activeKid(): String {
        val keys = jwtKeys()
        val kid = jwt.activeKid.ifBlank { keys.keys.single() }
        require(kid in keys) { "TC_JWT_ACTIVE_KID '$kid' is not in TC_JWT_KEYS" }
        return kid
    }

    fun encryptionKeyBytes(): ByteArray {
        require(encryptionKey.isNotBlank() && "CHANGE_ME" !in encryptionKey) { "TC_ENCRYPTION_KEY is required: base64 of 32 random bytes (openssl rand -base64 32)" }
        val bytes = Base64.getDecoder().decode(encryptionKey.trim())
        require(bytes.size == 32) { "TC_ENCRYPTION_KEY must decode to exactly 32 bytes" }
        return bytes
    }
}
