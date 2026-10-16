package com.ronybrand.orderapi.http

import com.ronybrand.orderapi.app.Actor
import com.ronybrand.orderapi.error.AppException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.util.UUID

internal val RequestIdKey = AttributeKey<String>("RequestId")
private val ActorKey = AttributeKey<Actor>("Actor")
private val StartKey = AttributeKey<Long>("RequestStart")

internal const val ACCESS_LOG_NAME = "access"
internal const val HEALTH_PATH = "/health"
private const val REQUEST_ID_HEADER = "X-Request-Id"

/** The authenticated caller of a protected route. */
internal val ApplicationCall.actor: Actor get() = attributes[ActorKey]

/**
 * Keeps an incoming X-Request-Id only when it is a canonical UUID: anything else (log injection, absurdly long
 * values) is replaced by a fresh one.
 */
internal val RequestIdPlugin =
    createApplicationPlugin("RequestId") {
        onCall { call ->
            val incoming = call.request.headers[REQUEST_ID_HEADER]
            val id = incoming?.takeIf { parseUuid(it) != null } ?: UUID.randomUUID().toString()
            call.attributes.put(RequestIdKey, id)
            call.response.headers.append(REQUEST_ID_HEADER, id)
        }
    }

/** The baseline response headers of an API that serves JSON only. */
internal val SecurityHeadersPlugin =
    createApplicationPlugin("SecurityHeaders") {
        onCall { call ->
            with(call.response.headers) {
                append("X-Content-Type-Options", "nosniff")
                append("X-Frame-Options", "DENY")
                append("Referrer-Policy", "no-referrer")
                append(HttpHeaders.CacheControl, "no-store")
                append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                append("Strict-Transport-Security", "max-age=63072000; includeSubDomains")
                append("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
            }
        }
    }

/**
 * One line per request. The query string is left out on purpose: search filters can carry personal data (an e-mail,
 * a tax id).
 */
internal val AccessLogPlugin =
    createApplicationPlugin("AccessLog") {
        val log = LoggerFactory.getLogger(ACCESS_LOG_NAME)
        onCall { call -> call.attributes.put(StartKey, System.nanoTime()) }
        on(ResponseSent) { call ->
            val millis =
                (System.nanoTime() - (call.attributes.getOrNull(StartKey) ?: System.nanoTime())) / NANOS_PER_MILLI
            log.info(
                "request method={} path={} status={} duration_ms={} request_id={}",
                call.request.httpMethod.value,
                call.request.path(),
                call.response.status()?.value,
                millis,
                call.attributes.getOrNull(RequestIdKey),
            )
        }
    }

private const val NANOS_PER_MILLI = 1_000_000L

private fun rateLimited(
    call: ApplicationCall,
    limiter: KeyedLimiter,
): Nothing {
    call.response.headers.append(HttpHeaders.RetryAfter, limiter.retryAfterSeconds())
    throw AppException.rateLimited()
}

internal class IpLimitConfig {
    lateinit var limiter: KeyedLimiter
}

/** A coarse limit per client address, before authentication; /health is exempt so probes never get throttled. */
internal val IpRateLimitPlugin =
    createApplicationPlugin("IpRateLimit", ::IpLimitConfig) {
        val limiter = pluginConfig.limiter
        onCall { call ->
            if (call.request.path() != HEALTH_PATH && !limiter.allow(call.request.origin.remoteAddress)) {
                rateLimited(call, limiter)
            }
        }
    }

internal class AuthConfig {
    lateinit var verifier: TokenVerifier
    lateinit var userLimiter: KeyedLimiter
    lateinit var writeLimiter: KeyedLimiter
}

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/**
 * Requires a valid bearer token, puts the caller on the call, and applies the per-user limits (a general one, and a
 * separate one for writes). Every route is installed with this or declared public: a route is never open by omission.
 */
internal val AuthenticatePlugin =
    createRouteScopedPlugin("Authenticate", ::AuthConfig) {
        val config = pluginConfig
        onCall { call ->
            val fields = call.request.headers[HttpHeaders.Authorization].orEmpty().trim().split(Regex("\\s+"))
            if (fields.size != 2 || !fields[0].equals("Bearer", ignoreCase = true)) {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
                throw AppException.unauthenticated()
            }
            val claims =
                try {
                    config.verifier.verify(fields[1])
                } catch (e: AppException) {
                    call.response.headers.append(HttpHeaders.WWWAuthenticate, """Bearer error="invalid_token"""")
                    throw e
                }
            val actor = Actor(claims.subject, claims.roles)
            call.attributes.put(ActorKey, actor)
            if (!config.userLimiter.allow(actor.id)) rateLimited(call, config.userLimiter)
            if (call.request.httpMethod !in SAFE_METHODS && !config.writeLimiter.allow(actor.id)) {
                rateLimited(call, config.writeLimiter)
            }
        }
    }

internal class RoleConfig {
    lateinit var role: String
}

/** Must be installed after [AuthenticatePlugin]: with no actor on the call it denies. */
internal val RequireRolePlugin =
    createRouteScopedPlugin("RequireRole", ::RoleConfig) {
        val role = pluginConfig.role
        onCall { call ->
            val actor = call.attributes.getOrNull(ActorKey)
            if (actor == null || !actor.hasRole(role)) throw AppException.forbidden()
        }
    }
