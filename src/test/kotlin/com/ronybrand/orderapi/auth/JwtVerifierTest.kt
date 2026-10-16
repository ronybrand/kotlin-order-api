package com.ronybrand.orderapi.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val AUDIENCE = "order-api"
private const val ISSUER = "https://idp.example"

private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

class JwtVerifierTest {
    private val pair = newKeyPair()
    private val other = newKeyPair()
    private val verifier = JwtVerifier(pair.public as RSAPublicKey, AUDIENCE, ISSUER)

    /** A token that is valid unless the test changes something; a null argument leaves the claim out. */
    private fun token(
        subject: String? = "user-1",
        audience: String? = AUDIENCE,
        issuer: String? = ISSUER,
        expiresAt: Instant? = Instant.now().plus(Duration.ofHours(1)),
        notBefore: Instant? = null,
        roles: Any? = listOf("admin"),
        algorithm: Algorithm = Algorithm.RSA256(null, pair.private as RSAPrivateKey),
    ): String {
        val builder = JWT.create()
        subject?.let { builder.withSubject(it) }
        audience?.let { builder.withAudience(it) }
        issuer?.let { builder.withIssuer(it) }
        expiresAt?.let { builder.withExpiresAt(it) }
        notBefore?.let { builder.withNotBefore(it) }
        when (roles) {
            null -> Unit
            is List<*> -> builder.withClaim("roles", roles)
            is String -> builder.withClaim("roles", roles)
            else -> error("unsupported roles in the test")
        }
        return builder.sign(algorithm)
    }

    private fun assertRejected(token: String) {
        val error = assertFailsWith<AppException> { verifier.verify(token) }
        assertEquals(Codes.UNAUTHENTICATED, error.code)
    }

    @Test
    fun `a well formed token yields the subject and the roles`() {
        val claims = verifier.verify(token(roles = listOf("user", "admin")))

        assertEquals("user-1", claims.subject)
        assertEquals(setOf("user", "admin"), claims.roles)
        assertTrue(claims.hasRole(ROLE_ADMIN))
    }

    @Test
    fun `without a roles claim the caller is a plain user`() {
        assertEquals(setOf(ROLE_USER), verifier.verify(token(roles = null)).roles)
    }

    @Test
    fun `untrustworthy tokens are all the same 401`() {
        val cases =
            mapOf(
                "garbage" to "not-a-jwt",
                "empty" to "",
                "no exp, valid forever" to token(expiresAt = null),
                "expired beyond the skew" to token(expiresAt = Instant.now().minus(Duration.ofMinutes(2))),
                "not valid yet" to token(notBefore = Instant.now().plus(Duration.ofMinutes(2))),
                "wrong audience" to token(audience = "someone-else"),
                "no audience" to token(audience = null),
                "wrong issuer" to token(issuer = "https://evil.example"),
                "no issuer" to token(issuer = null),
                "no subject" to token(subject = null),
                "blank subject" to token(subject = "   "),
                "subject too long" to token(subject = "x".repeat(201)),
                "roles as a string" to token(roles = "admin"),
                "roles with a number" to token(roles = listOf("user", 1)),
                "signed by another key" to token(algorithm = Algorithm.RSA256(null, other.private as RSAPrivateKey)),
                "alg none" to token(algorithm = Algorithm.none()),
                "HS256 signed with the public key as the secret" to
                    token(algorithm = Algorithm.HMAC256(pair.public.encoded)),
            )

        for ((name, value) in cases) {
            val error = assertFailsWith<AppException>(name) { verifier.verify(value) }
            assertEquals(Codes.UNAUTHENTICATED, error.code, name)
        }
    }

    @Test
    fun `a clock that is a little off is tolerated`() {
        verifier.verify(token(expiresAt = Instant.now().minusSeconds(10)))
        verifier.verify(token(notBefore = Instant.now().plusSeconds(10)))
    }

    @Test
    fun `a subject at the audit column limit is accepted`() {
        assertEquals(200, verifier.verify(token(subject = "x".repeat(200))).subject.length)
    }

    @Test
    fun `a token tampered with after signing is rejected`() {
        val parts = token().split('.')
        val payload = String(Base64.getUrlDecoder().decode(parts[1])).replace("user-1", "admin-9")
        val forged =
            parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + "." +
                parts[2]

        assertRejected(forged)
    }

    @Test
    fun `the key is read from a PEM and the configuration is validated`() {
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"

        assertEquals("user-1", JwtVerifier.fromPem(pem, AUDIENCE, ISSUER).verify(token()).subject)
        assertFailsWith<IllegalArgumentException> { JwtVerifier.fromPem("not a key", AUDIENCE, ISSUER) }
        assertFailsWith<IllegalArgumentException> { JwtVerifier.fromPem(pem, "", ISSUER) }
        assertFailsWith<IllegalArgumentException> { JwtVerifier.fromPem(pem, AUDIENCE, "") }
    }
}
