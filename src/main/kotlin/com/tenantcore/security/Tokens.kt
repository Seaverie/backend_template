package com.tenantcore.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.tenantcore.common.Unauthorized
import com.tenantcore.config.TcProperties
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import java.util.UUID

enum class Audience(val value: String) { TENANT("tenant"), PLATFORM("platform"), CUSTOMER("customer") }

data class AccessClaims(
    val subject: UUID,
    val audience: Audience,
    val sessionId: UUID,
    val tenantId: UUID?,
    val mfa: Boolean,
)

/** HS256 access tokens with a `kid` header so keys can be rotated without signing everyone out. */
@Component
class Tokens(private val props: TcProperties) {
    private val keys = props.jwtKeys()
    private val activeKid = props.activeKid()

    fun issue(c: AccessClaims, ttlSeconds: Long): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(props.jwt.issuer)
            .subject(c.subject.toString())
            .audience("tenantcore:${c.audience.value}")
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
            .jwtID(UUID.randomUUID().toString())
            .claim("sid", c.sessionId.toString())
            .claim("tid", c.tenantId?.toString())
            .claim("mfa", c.mfa)
            .claim("typ", "access")
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID(activeKid).build(), claims)
        jwt.sign(MACSigner(keys.getValue(activeKid)))
        return jwt.serialize()
    }

    fun verify(token: String, expected: Audience): AccessClaims {
        val jwt = runCatching { SignedJWT.parse(token) }.getOrElse { throw Unauthorized("Invalid token.") }
        if (jwt.header.algorithm != JWSAlgorithm.HS256) throw Unauthorized("Invalid token.")
        val key = keys[jwt.header.keyID] ?: throw Unauthorized("Invalid token.")
        if (!jwt.verify(MACVerifier(key))) throw Unauthorized("Invalid token.")
        val c = jwt.jwtClaimsSet
        val now = Date()
        if (c.issuer != props.jwt.issuer || c.audience != listOf("tenantcore:${expected.value}")) throw Unauthorized("Invalid token.")
        if (c.expirationTime == null || c.expirationTime.before(now)) throw Unauthorized("Token expired.")
        if (c.notBeforeTime != null && c.notBeforeTime.after(Date(now.time + 30_000))) throw Unauthorized("Invalid token.")
        if (c.getStringClaim("typ") != "access") throw Unauthorized("Invalid token.")
        return runCatching {
            AccessClaims(
                subject = UUID.fromString(c.subject),
                audience = expected,
                sessionId = UUID.fromString(c.getStringClaim("sid")),
                tenantId = c.getStringClaim("tid")?.let(UUID::fromString),
                mfa = c.getBooleanClaim("mfa") ?: false,
            )
        }.getOrElse { throw Unauthorized("Invalid token.") }
    }
}
