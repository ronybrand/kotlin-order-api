package com.ronybrand.orderapi.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.ronybrand.orderapi.domain.MAX_ACTOR_LENGTH
import com.ronybrand.orderapi.error.AppException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The roles the API knows: authenticated user versus admin, nothing finer. */
const val ROLE_USER = "user"
const val ROLE_ADMIN = "admin"

private const val CLOCK_SKEW_SECONDS = 30L

/** What the API needs from a verified token. */
data class Claims(val subject: String, val roles: Set<String>) {
    fun hasRole(role: String) = role in roles
}

/**
 * Verifies the bearer tokens of the API. The service only verifies tokens, it never issues them: it holds the public
 * half of the issuer's RS256 key pair, so leaking it grants no ability to mint one.
 */
class JwtVerifier(key: RSAPublicKey, audience: String, issuer: String) {
    private val verifier: JWTVerifier

    init {
        require(audience.isNotBlank() && issuer.isNotBlank()) { "audience and issuer are required" }
        // Only RS256: an HS256 token "signed" with the (public) key, or alg=none, never validates.
        verifier =
            JWT
                .require(Algorithm.RSA256(key, null))
                .withAudience(audience)
                .withIssuer(issuer)
                .acceptLeeway(CLOCK_SKEW_SECONDS)
                .build()
    }

    /**
     * Validates the token (signature, audience, issuer, exp and nbf) and extracts the claims. A token with no
     * subject, or whose subject does not fit the audit columns, is rejected; one without roles is a plain user.
     * Every failure is the same 401: neither the caller nor the client learns why a token failed.
     */
    @Suppress("TooGenericExceptionCaught")
    fun verify(token: String): Claims =
        try {
            val decoded = verifier.verify(token)
            // Without this a token with no exp would be valid forever.
            if (decoded.expiresAt == null) throw AppException.unauthenticated()
            val subject = decoded.subject
            if (subject.isNullOrBlank() || subject.length > MAX_ACTOR_LENGTH) throw AppException.unauthenticated()
            Claims(subject, rolesOf(decoded.payload))
        } catch (e: AppException) {
            throw e
        } catch (_: Exception) {
            // The library's message says why (expired, wrong audience...); the client must not learn it.
            throw AppException.unauthenticated()
        }

    /** Reads `roles` strictly from the raw payload: a string, or an array with a non-string, is not a role list. */
    private fun rolesOf(payload: String): Set<String> {
        val json = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload))) as JsonObject
        val roles = json["roles"] ?: return setOf(ROLE_USER)
        if (roles is JsonPrimitive && roles.content == "null") return setOf(ROLE_USER)
        if (roles !is JsonArray) throw AppException.unauthenticated()
        return roles
            .map { element ->
                val primitive = element as? JsonPrimitive
                if (primitive == null || !primitive.isString) throw AppException.unauthenticated()
                primitive.content
            }.toSet()
    }

    companion object {
        private fun notAPublicKey(cause: Exception) =
            IllegalArgumentException("the JWT public key is not a PEM-encoded RSA public key", cause)

        /** Builds a verifier from a PEM-encoded RSA public key (`-----BEGIN PUBLIC KEY-----`). */
        fun fromPem(
            pem: String,
            audience: String,
            issuer: String,
        ): JwtVerifier {
            val body =
                pem
                    .lineSequence()
                    .filterNot { it.startsWith("-----") }
                    .joinToString("")
                    .trim()
            val key =
                try {
                    KeyFactory
                        .getInstance("RSA")
                        .generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(body))) as RSAPublicKey
                } catch (e: GeneralSecurityException) {
                    throw notAPublicKey(e)
                } catch (e: IllegalArgumentException) {
                    throw notAPublicKey(e)
                }
            return JwtVerifier(key, audience, issuer)
        }
    }
}
