package com.ronybrand.orderapi.http

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiddlewareTest {
    // --- request id and baseline headers --------------------------------------------------------------

    @Test
    fun `a valid X-Request-Id is kept and anything else is replaced by a fresh UUID`() {
        val valid = UUID.randomUUID().toString()
        ApiFixture().run {
            assertEquals(valid, call("GET", "/health", null) { header("X-Request-Id", valid) }.headers["X-Request-Id"])
            assertEquals(
                valid.uppercase(),
                call("GET", "/health", null) {
                    header("X-Request-Id", valid.uppercase())
                }.headers["X-Request-Id"],
            )
            for (bad in listOf("not-a-uuid", "x".repeat(300), "1-1-1-1-1", "$valid\nInjected: yes")) {
                val id = runCatching {
                    call("GET", "/health", null) { header("X-Request-Id", bad) }.headers["X-Request-Id"]
                }.getOrNull()
                if (id != null) {
                    assertNotEquals(bad, id)
                    UUID.fromString(id)
                }
            }
            val generated = call("GET", "/health", null).headers["X-Request-Id"]!!
            UUID.fromString(generated)
        }
    }

    @Test
    fun `the error body carries the same request id as the header`() {
        ApiFixture().run {
            val response = call("GET", "/orders/${UUID.randomUUID()}", "user")

            assertEquals(response.headers["X-Request-Id"], response.json().getValue("request_id").jsonPrimitive.content)
        }
    }

    @Test
    fun `every response carries the security headers`() {
        ApiFixture().run {
            for (response in listOf(
                call("GET", "/health", null),
                call("GET", "/orders/search", null),
                call("GET", "/nope", "user"),
            )) {
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                assertEquals("DENY", response.headers["X-Frame-Options"])
                assertEquals("no-referrer", response.headers["Referrer-Policy"])
                assertEquals("no-store", response.headers["Cache-Control"])
                assertEquals("default-src 'none'; frame-ancestors 'none'", response.headers["Content-Security-Policy"])
                assertTrue(response.headers["Strict-Transport-Security"]!!.startsWith("max-age="))
                assertEquals("geolocation=(), microphone=(), camera=()", response.headers["Permissions-Policy"])
            }
        }
    }

    // --- health ---------------------------------------------------------------------------------------

    @Test
    fun `health is public and reports the database`() {
        val fixture = ApiFixture()
        fixture.run {
            val ok = call("GET", "/health", null)
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals("ok", ok.json().getValue("status").jsonPrimitive.content)

            fixture.ping = { error("connection refused: secret detail") }
            val down = call("GET", "/health", null)
            assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
            assertEquals("unavailable", down.json().getValue("status").jsonPrimitive.content)
            assertFalse(down.bodyAsText().contains("secret"))
        }
    }

    // --- authentication and roles ---------------------------------------------------------------------

    private val protectedRoutes =
        listOf(
            "POST" to "/customers",
            "GET" to "/customers/search",
            "POST" to "/customers/search",
            "GET" to "/customers/00000000-0000-0000-0000-000000000001",
            "PUT" to "/customers/00000000-0000-0000-0000-000000000001",
            "DELETE" to "/customers/00000000-0000-0000-0000-000000000001",
            "POST" to "/orders",
            "GET" to "/orders/search",
            "POST" to "/orders/search",
            "GET" to "/orders/00000000-0000-0000-0000-000000000001",
            "DELETE" to "/orders/00000000-0000-0000-0000-000000000001",
            "POST" to "/orders/00000000-0000-0000-0000-000000000001/items",
            "PATCH" to "/orders/00000000-0000-0000-0000-000000000001/items/00000000-0000-0000-0000-000000000002",
            "DELETE" to "/orders/00000000-0000-0000-0000-000000000001/items/00000000-0000-0000-0000-000000000002",
            "POST" to "/orders/00000000-0000-0000-0000-000000000001/confirm",
            "POST" to "/orders/00000000-0000-0000-0000-000000000001/cancel",
        )

    @Test
    fun `every protected route requires authentication, none is open by omission`() {
        ApiFixture().run {
            for ((method, path) in protectedRoutes) {
                val response = call(
                    method,
                    path,
                    null,
                    body = if (method == "GET" ||
                        method == "DELETE"
                    ) {
                        null
                    } else {
                        "{}"
                    },
                )
                assertEquals(HttpStatusCode.Unauthorized, response.status, "$method $path")
                assertEquals("AUTH-01", response.code(), "$method $path")
            }
        }
    }

    @Test
    fun `the 401 says Bearer and a rejected token says invalid_token`() {
        ApiFixture().run {
            assertEquals("Bearer", call("GET", "/orders/search", null).headers[HttpHeaders.WWWAuthenticate])
            assertEquals(
                """Bearer error="invalid_token"""",
                call("GET", "/orders/search", "not-a-jwt").headers[HttpHeaders.WWWAuthenticate],
            )
        }
    }

    @Test
    fun `only the Bearer scheme with one credential is accepted`() {
        val fixture = ApiFixture()
        fixture.run {
            val token = fixture.token()
            for (value in listOf("Basic $token", token, "Bearer", "Bearer $token extra", "")) {
                val response = call("GET", "/orders/search", null) { header("Authorization", value) }
                assertEquals(HttpStatusCode.Unauthorized, response.status, value)
            }
            assertEquals(
                HttpStatusCode.OK,
                call("GET", "/orders/search", null) {
                    header("Authorization", "bearer $token")
                }.status,
            )
        }
    }

    @Test
    fun `an authenticated actor is recorded in the audit columns`() {
        val fixture = ApiFixture()
        val customer = fixture.seedCustomer()
        fixture.run {
            call("POST", "/orders", fixture.token("auditor-7"), """{"customer_id":"${customer.id}"}""")
        }
        assertEquals("auditor-7", fixture.store.data.orders.values.single().createdBy)
    }

    // --- errors ---------------------------------------------------------------------------------------

    @Test
    fun `an unknown route and a known path with another method use the error envelope`() {
        ApiFixture().run {
            val notFound = call("GET", "/nope", "user")
            val wrongMethod = call("PUT", "/orders", "user", "{}")

            assertEquals(HttpStatusCode.NotFound, notFound.status)
            assertEquals("RESOURCE-NOT-FOUND-00", notFound.code())
            assertEquals(HttpStatusCode.MethodNotAllowed, wrongMethod.status)
            assertEquals("VALIDATION-13", wrongMethod.code())
            assertTrue(wrongMethod.headers[HttpHeaders.Allow]!!.contains("POST"))
            for (response in listOf(notFound, wrongMethod)) {
                assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
                assertTrue(response.json().containsKey("request_id"))
            }
        }
    }

    @Test
    fun `an unexpected failure is INTERNAL-00 and never leaks its message`() {
        val fixture = ApiFixture()
        fixture.ping = { throw IllegalStateException("secret detail") }
        fixture.run {
            val response = call("GET", "/health", null)

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }
        val broken = ApiFixture()
        broken.store.beforeOrderSave = { throw IllegalStateException("secret detail from the store") }
        val order = broken.seedOrder()
        broken.run {
            val response = call("POST", "/orders/${order.id}/confirm", "user")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("INTERNAL-00", response.code())
            assertFalse(response.bodyAsText().contains("secret"))
        }
    }

    @Test
    fun `the access log records a request that failed, without the query string`() {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(ACCESS_LOG_NAME) as Logger
        logger.addAppender(appender)
        try {
            val broken = ApiFixture()
            broken.store.beforeOrderSave = { throw IllegalStateException("boom") }
            val order = broken.seedOrder()
            broken.run {
                call("POST", "/orders/${order.id}/confirm", "user")
                call("GET", "/orders/search?page=0&size=1&secret=ada@example.com", "user")
            }

            val lines = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
            assertTrue(lines.any { "status=500" in it && "/confirm" in it }, lines.toString())
            assertTrue(lines.none { "ada@example.com" in it || "secret=" in it }, lines.toString())
            assertTrue(lines.all { "request_id=" in it && "duration_ms=" in it })
        } finally {
            logger.detachAppender(appender)
        }
    }

    // --- body limit -----------------------------------------------------------------------------------

    @Test
    fun `a body above the limit is 413 VALIDATION-12, declared or chunked`() {
        ApiFixture(HttpConfig(maxBodyBytes = 10)).run {
            val declared = call("POST", "/orders", "user", "x".repeat(11))
            assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
            assertEquals("VALIDATION-12", declared.code())

            // A chunked body has no Content-Length: the limit must count the bytes actually read.
            val chunked =
                call("POST", "/orders", "user") {
                    setBody(
                        object : OutgoingContent.WriteChannelContent() {
                            override val contentType = ContentType.Application.Json

                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                channel.writeStringUtf8("x".repeat(11))
                            }
                        },
                    )
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
            assertEquals("VALIDATION-12", chunked.code())

            assertEquals(HttpStatusCode.BadRequest, call("POST", "/orders", "user", "1234567890").status)
        }
    }

    // --- CORS -----------------------------------------------------------------------------------------

    @Test
    fun `CORS allows exactly the configured origins`() {
        ApiFixture(HttpConfig(corsOrigins = listOf("https://app.example.com"))).run {
            val allowed = call("GET", "/health", null) { header("Origin", "https://app.example.com") }
            val other = call("GET", "/health", null) { header("Origin", "https://evil.example") }
            val preflight =
                call("OPTIONS", "/orders", null) {
                    header("Origin", "https://app.example.com")
                    header("Access-Control-Request-Method", "POST")
                    header("Access-Control-Request-Headers", "authorization,content-type")
                }

            assertEquals("https://app.example.com", allowed.headers["Access-Control-Allow-Origin"])
            assertTrue(allowed.headers["Access-Control-Expose-Headers"]!!.contains("X-Request-Id"))
            assertNull(other.headers["Access-Control-Allow-Origin"])
            assertEquals("https://app.example.com", preflight.headers["Access-Control-Allow-Origin"])
            assertTrue(preflight.status.value in 200..204)
            assertTrue(preflight.headers["Access-Control-Allow-Methods"]!!.contains("PATCH"))
        }
    }

    @Test
    fun `without configured origins no CORS header is ever sent`() {
        ApiFixture().run {
            val response = call("GET", "/health", null) { header("Origin", "https://app.example.com") }

            assertNull(response.headers["Access-Control-Allow-Origin"])
        }
    }

    // --- rate limiting --------------------------------------------------------------------------------

    @Test
    fun `the write budget is separate from the general budget and per user`() {
        val fixture = ApiFixture(HttpConfig(rateLimitWrites = 2, rateLimitPerUser = 100))
        val order = fixture.seedOrder()
        fixture.run {
            val codes = (1..3).map { call("POST", "/orders/${order.id}/confirm", "user").status.value }
            val read = call("GET", "/orders/${order.id}", "user")
            val otherUser = call("POST", "/orders/${order.id}/confirm", "admin")

            assertEquals(listOf(200, 400, 429), codes)
            assertEquals(HttpStatusCode.OK, read.status)
            assertNotEquals(HttpStatusCode.TooManyRequests, otherUser.status)
        }
    }

    @Test
    fun `a limited response says RATE-00 and when to retry`() {
        ApiFixture(HttpConfig(rateLimitPerUser = 1)).run {
            call("GET", "/orders/search", "user")
            val limited = call("GET", "/orders/search", "user")

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("RATE-00", limited.code())
            assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun `health is exempt from the rate limits`() {
        ApiFixture(HttpConfig(rateLimitPerIp = 1)).run {
            repeat(5) { assertEquals(HttpStatusCode.OK, call("GET", "/health", null).status) }
        }
    }

    @Test
    fun `the per-IP limit applies before authentication`() {
        ApiFixture(HttpConfig(rateLimitPerIp = 2)).run {
            val codes = (1..3).map { call("GET", "/orders/search", null).status.value }

            assertEquals(listOf(401, 401, 429), codes)
        }
    }

    @Test
    fun `the JSON body of an error names exactly message, code, params and request_id`() {
        ApiFixture().run {
            val body = call("GET", "/orders/${UUID.randomUUID()}", "user").json()

            assertEquals(setOf("message", "code", "params", "request_id"), body.keys)
            assertTrue(body.getValue("params").jsonObject.containsKey("id"))
        }
    }
}
