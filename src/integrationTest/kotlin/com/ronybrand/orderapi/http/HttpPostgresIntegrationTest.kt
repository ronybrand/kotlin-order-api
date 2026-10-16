package com.ronybrand.orderapi.http

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.ronybrand.orderapi.PostgresFixture
import com.ronybrand.orderapi.TestDb
import com.ronybrand.orderapi.app.CustomerService
import com.ronybrand.orderapi.app.OrderService
import com.ronybrand.orderapi.auth.JwtVerifier
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val AUDIENCE = "order-api"
private const val ISSUER = "https://idp.example"

/** The whole stack, minus the broker: real HTTP routing, real use cases, real PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpPostgresIntegrationTest {
    private lateinit var db: TestDb
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @BeforeAll
    fun start() {
        db = PostgresFixture.newDatabase()
    }

    @AfterAll
    fun stop() = db.close()

    private fun token(
        subject: String,
        vararg roles: String,
    ): String =
        JWT
            .create()
            .withSubject(subject)
            .withAudience(AUDIENCE)
            .withIssuer(ISSUER)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
            .withClaim("roles", roles.toList())
            .sign(Algorithm.RSA256(null, keys.private as RSAPrivateKey))

    private val admin by lazy { token("admin-1", "user", "admin") }
    private val user by lazy { token("user-1", "user") }

    private fun run(block: suspend HttpClient.() -> Unit) {
        val verifier = JwtVerifier(keys.public as RSAPublicKey, AUDIENCE, ISSUER)
        val dependencies =
            ApiDependencies(
                CustomerService(db.store),
                OrderService(db.store, null),
                TokenVerifier(verifier::verify),
                { db.pool.connection.use { check(it.isValid(2)) } },
                HttpConfig(rateLimitPerUser = 10_000, rateLimitWrites = 10_000, rateLimitPerIp = 10_000),
            )
        testApplication {
            application { orderApi(dependencies) }
            block(client)
        }
    }

    private suspend fun HttpClient.call(
        method: String,
        path: String,
        bearer: String?,
        body: String? = null,
    ): HttpResponse =
        request(path) {
            this.method = HttpMethod.parse(method)
            bearer?.let { header("Authorization", "Bearer $it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun newCustomerBody(taxId: String) = """{"name":"Ana","tax_id":"$taxId","email":"ana@example.com"}"""

    @Test
    fun `health reports the real database`() =
        run {
            assertEquals(HttpStatusCode.OK, call("GET", "/health", null).status)
        }

    @Test
    fun `a customer and an order go through the whole lifecycle on a real database`() =
        run {
            val customer = call("POST", "/customers", admin, newCustomerBody("LIFE-0001"))
            assertEquals(HttpStatusCode.Created, customer.status)
            val customerId = customer.obj().getValue("id").jsonPrimitive.content

            val created =
                call(
                    "POST",
                    "/orders",
                    user,
                    """{"customer_id":"$customerId","items":[{"description":"Pen","unit_price":0.10,"quantity":3}]}""",
                )
            assertEquals(HttpStatusCode.Created, created.status)
            val orderId = created.obj().getValue("id").jsonPrimitive.content

            val withItem =
                call(
                    "POST",
                    "/orders/$orderId/items",
                    user,
                    """{"description":"Book","unit_price":19.99,"quantity":2}""",
                )
            assertEquals("40.28", withItem.obj().getValue("total").jsonPrimitive.content)

            val confirmed = call("POST", "/orders/$orderId/confirm", user)
            assertEquals("CONFIRMED", confirmed.obj().getValue("status").jsonPrimitive.content)
            assertEquals("3", confirmed.obj().getValue("version").jsonPrimitive.content)

            val found =
                call(
                    "POST",
                    "/orders/search",
                    user,
                    """{"filters":{"customer_id":[{"op":"eq","value":"$customerId"}],"status":[{"op":"eq","value":"CONFIRMED"}]}}""",
                )
            assertEquals(1, found.obj().getValue("items").jsonArray.size)

            assertEquals(HttpStatusCode.Conflict, call("DELETE", "/customers/$customerId", admin).status)
            assertEquals(HttpStatusCode.NoContent, call("DELETE", "/orders/$orderId", user).status)
            assertEquals(HttpStatusCode.NoContent, call("DELETE", "/customers/$customerId", admin).status)
        }

    @Test
    fun `a duplicate tax id is CONFLICT-01 from the unique index and the value is not echoed`() =
        run {
            call("POST", "/customers", admin, newCustomerBody("DUP-0001"))

            val duplicate = call("POST", "/customers", admin, newCustomerBody("dup0001"))

            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertEquals("CONFLICT-01", duplicate.obj().getValue("code").jsonPrimitive.content)
            assertTrue(!duplicate.bodyAsText().contains("DUP"))
        }

    @Test
    fun `the audit columns record who did what`() =
        run {
            val id = call(
                "POST",
                "/customers",
                token("auditor-9", "user", "admin"),
                newCustomerBody("AUD-0001"),
            ).obj().getValue("id").jsonPrimitive.content

            val createdBy =
                db.pool.connection.use { connection ->
                    connection.prepareStatement(
                        "SELECT created_by FROM customers WHERE id = ?::uuid",
                    ).use { statement ->
                        statement.setString(1, id)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
                }
            assertEquals("auditor-9", createdBy)
        }

    @Test
    fun `concurrent writes to one order never overwrite each other`() =
        run {
            val customerId = call(
                "POST",
                "/customers",
                admin,
                newCustomerBody("RACE-0001"),
            ).obj().getValue("id").jsonPrimitive.content
            val orderId = call(
                "POST",
                "/orders",
                user,
                """{"customer_id":"$customerId"}""",
            ).obj().getValue("id").jsonPrimitive.content

            val statuses =
                coroutineScope {
                    (1..8)
                        .map { index ->
                            async {
                                call(
                                    "POST",
                                    "/orders/$orderId/items",
                                    user,
                                    """{"description":"Item $index","unit_price":1.00,"quantity":1}""",
                                ).status
                            }
                        }.awaitAll()
                }

            val stored = call("GET", "/orders/$orderId", user).obj().getValue("items").jsonArray.size
            assertTrue(statuses.all { it == HttpStatusCode.OK || it == HttpStatusCode.Conflict }, statuses.toString())
            assertEquals(
                statuses.count {
                    it == HttpStatusCode.OK
                },
                stored,
                "every accepted write is stored and no rejected one is",
            )
        }
}
