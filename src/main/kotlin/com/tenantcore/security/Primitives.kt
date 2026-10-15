package com.tenantcore.security

import com.tenantcore.common.Invalid
import com.tenantcore.config.TcProperties
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()

fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
fun randomToken(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes))
fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

/** Argon2id with OWASP-recommended parameters (19 MiB, 2 iterations, 1 lane). */
@Component
class Passwords {
    private val encoder = Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)

    /** Used when the account does not exist, so response time does not reveal which emails are registered. */
    private val dummyHash: String by lazy { encoder.encode(randomToken()) }

    fun hash(password: String): String = encoder.encode(password)

    fun verify(hash: String?, password: String): Boolean {
        if (hash == null) { encoder.matches(password, dummyHash); return false }
        return runCatching { encoder.matches(password, hash) }.getOrDefault(false)
    }

    /** Staff passwords need 12 characters; shop customers' accounts use a shorter minimum (8). */
    fun validatePolicy(password: String, minLength: Int = 12) {
        val problems = buildList {
            if (password.length < minLength) add("at least $minLength characters")
            if (password.length > 256) add("at most 256 characters")
            if (password.isNotEmpty() && password.toSet().size < 5) add("at least 5 different characters")
        }
        if (problems.isNotEmpty()) throw Invalid("Password must have ${problems.joinToString(", ")}.",
            listOf(mapOf("loc" to listOf("body", "password"), "msg" to problems.joinToString(", "))))
    }
}

/**
 * AES-256-GCM for secrets stored in the database. The context string (e.g. "mfa:<admin id>") is bound
 * as associated data, so a ciphertext copied to another row or purpose fails to decrypt.
 * Format: "v1:" + base64(12-byte nonce || ciphertext+tag).
 */
@Component
class Crypto(props: TcProperties) {
    private val key = SecretKeySpec(props.encryptionKeyBytes(), "AES")

    fun encrypt(plain: String, context: String): String {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(context.toByteArray())
        }
        return "v1:" + Base64.getEncoder().encodeToString(nonce + c.doFinal(plain.toByteArray()))
    }

    fun decrypt(token: String, context: String): String {
        require(token.startsWith("v1:")) { "unknown ciphertext version" }
        val raw = Base64.getDecoder().decode(token.removePrefix("v1:"))
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, 12))); updateAAD(context.toByteArray())
        }
        return String(c.doFinal(raw.copyOfRange(12, raw.size)))
    }
}

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s), accepting one step of clock drift and rejecting reuse. */
object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun newSecret(): String = base32(randomBytes(20))

    fun uri(secret: String, account: String, issuer: String): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secret&issuer=${enc(issuer)}&algorithm=SHA1&digits=6&period=30"
    }

    /** Returns the matched time step, or null. A step at or before [lastUsedStep] is rejected (replay). */
    fun verify(secret: String, code: String, lastUsedStep: Long?, nowSeconds: Long = System.currentTimeMillis() / 1000): Long? {
        if (!Regex("^\\d{6}$").matches(code)) return null
        val key = unbase32(secret)
        val current = nowSeconds / 30
        for (step in listOf(current, current - 1, current + 1)) {
            if (lastUsedStep != null && step <= lastUsedStep) continue
            if (constantTimeEquals(codeAt(key, step), code)) return step
        }
        return null
    }

    fun codeAt(key: ByteArray, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val o = h.last().toInt() and 0x0f
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or
            ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return "%06d".format(bin % 1_000_000)
    }

    fun base32(data: ByteArray): String {
        val sb = StringBuilder(); var buffer = 0; var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun unbase32(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(); var buffer = 0; var bits = 0
        for (c in s.uppercase().filter { it != '=' && !it.isWhitespace() }) {
            val v = ALPHABET.indexOf(c); require(v >= 0) { "invalid base32" }
            buffer = (buffer shl 5) or v; bits += 5
            if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xff); bits -= 8 }
        }
        return out.toByteArray()
    }
}
