package com.ronybrand.orderapi.http

import com.ronybrand.orderapi.app.CustomerService
import com.ronybrand.orderapi.app.OrderService
import com.ronybrand.orderapi.auth.Claims

private const val DEFAULT_MAX_BODY_BYTES = 1L shl 20
private const val DEFAULT_RATE_PER_USER = 120
private const val DEFAULT_RATE_WRITES = 30
private const val DEFAULT_RATE_PER_IP = 600

/** The HTTP-layer settings; the defaults are the ones of the other implementations. */
data class HttpConfig(
    /** Exactly these origins get CORS headers; empty means CORS is off. */
    val corsOrigins: List<String> = emptyList(),
    /** Counts the bytes actually read, so a chunked body cannot skip it. */
    val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    /** Requests per minute per authenticated user, any method. */
    val rateLimitPerUser: Int = DEFAULT_RATE_PER_USER,
    /** Requests per minute per authenticated user, POST/PUT/PATCH/DELETE only. */
    val rateLimitWrites: Int = DEFAULT_RATE_WRITES,
    /** Requests per minute per client address, applied before authentication. */
    val rateLimitPerIp: Int = DEFAULT_RATE_PER_IP,
)

/** What authentication needs from the auth package, declared here where it is consumed. */
fun interface TokenVerifier {
    /** Throws an unauthenticated AppException for any token that cannot be trusted. */
    fun verify(token: String): Claims
}

/** Everything the HTTP layer needs, passed in: nothing is read from globals. */
class ApiDependencies(
    val customers: CustomerService,
    val orders: OrderService,
    val verifier: TokenVerifier,
    /** Checks the database for /health; throws when it is unavailable. */
    val ping: () -> Unit,
    val config: HttpConfig = HttpConfig(),
    val nowMillis: () -> Long = System::currentTimeMillis,
)
