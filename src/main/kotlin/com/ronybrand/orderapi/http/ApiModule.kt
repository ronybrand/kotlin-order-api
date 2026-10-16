package com.ronybrand.orderapi.http

import com.ronybrand.orderapi.auth.ROLE_ADMIN
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.net.URI

private const val PREFLIGHT_MAX_AGE_SECONDS = 600L

/** The whole HTTP application: routes plus the plugin stack, with everything it needs passed in. */
fun Application.orderApi(dependencies: ApiDependencies) {
    val config = dependencies.config
    val handlers = Handlers(dependencies.customers, dependencies.orders, dependencies.ping)
    val endpoints = handlers.endpoints()
    val log = LoggerFactory.getLogger("com.ronybrand.orderapi.http")

    // Installed first so that every later plugin runs inside the request id, the headers and the access log.
    install(RequestIdPlugin)
    install(SecurityHeadersPlugin)
    install(AccessLogPlugin)
    install(StatusPages) {
        exception<AppException> { call, error -> call.respondError(error) }
        exception<PayloadTooLargeException> { call, _ -> call.respondError(AppException.tooLarge(config.maxBodyBytes)) }
        exception<Throwable> { call, error ->
            log.error("unexpected error on {} {}", call.request.httpMethod.value, call.request.path(), error)
            call.respondInternalError()
        }
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) { call, _ -> call.respondUnmatched(endpoints) }
    }
    install(RequestBodyLimit) { bodyLimit { config.maxBodyBytes } }
    if (config.corsOrigins.isNotEmpty()) install(CORS) { allowOrigins(config.corsOrigins) }
    install(IpRateLimitPlugin) { limiter = KeyedLimiter(config.rateLimitPerIp, dependencies.nowMillis) }

    val userLimiter = KeyedLimiter(config.rateLimitPerUser, dependencies.nowMillis)
    val writeLimiter = KeyedLimiter(config.rateLimitWrites, dependencies.nowMillis)
    routing {
        for (endpoint in endpoints) {
            route(endpoint.path, endpoint.method) {
                if (endpoint.access != Access.PUBLIC) {
                    install(AuthenticatePlugin) {
                        verifier = dependencies.verifier
                        this.userLimiter = userLimiter
                        this.writeLimiter = writeLimiter
                    }
                }
                if (endpoint.access == Access.ADMIN) install(RequireRolePlugin) { role = ROLE_ADMIN }
                handle { endpoint.handler(call) }
            }
        }
    }
}

/** The routes of the API as method and path, for the test that compares them with the OpenAPI document. */
internal fun routeTable(dependencies: ApiDependencies): List<Triple<HttpMethod, String, Access>> =
    Handlers(dependencies.customers, dependencies.orders, dependencies.ping)
        .endpoints()
        .map { Triple(it.method, it.path, it.access) }

private fun io.ktor.server.plugins.cors.CORSConfig.allowOrigins(origins: List<String>) {
    for (origin in origins) {
        val uri = URI(origin)
        val host = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
        allowHost(host, schemes = listOf(uri.scheme))
    }
    for (method in listOf(
        HttpMethod.Get,
        HttpMethod.Post,
        HttpMethod.Put,
        HttpMethod.Patch,
        HttpMethod.Delete,
        HttpMethod.Options,
    )) {
        allowMethod(method)
    }
    allowHeader(HttpHeaders.Authorization)
    allowHeader(HttpHeaders.ContentType)
    allowHeader("X-Request-Id")
    exposeHeader("X-Request-Id")
    exposeHeader(HttpHeaders.RetryAfter)
    maxAgeInSeconds = PREFLIGHT_MAX_AGE_SECONDS
}

/** A path nothing serves is 404, a path served for other methods only is 405 with the methods in Allow. */
private suspend fun ApplicationCall.respondUnmatched(endpoints: List<Endpoint>) {
    val path = request.path()
    val allowed =
        endpoints
            .filter { it.method != request.httpMethod && matches(it.path, path) }
            .map { it.method.value }
            .distinct()
    if (allowed.isEmpty()) {
        respondEnvelope(HttpStatusCode.NotFound, Codes.ROUTE_NOT_FOUND, "Route not found")
    } else {
        response.headers.append(HttpHeaders.Allow, allowed.joinToString(", "))
        respondEnvelope(HttpStatusCode.MethodNotAllowed, Codes.METHOD_NOT_ALLOWED, "Method not allowed")
    }
}

private fun matches(
    template: String,
    path: String,
): Boolean {
    val expected = template.split('/')
    val actual = path.split('/')
    return expected.size == actual.size &&
        expected.zip(actual).all { (part, segment) ->
            (part.startsWith("{") && segment.isNotEmpty()) || part == segment
        }
}
