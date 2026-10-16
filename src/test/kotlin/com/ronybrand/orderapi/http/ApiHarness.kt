package com.ronybrand.orderapi.http

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.ronybrand.orderapi.app.CustomerService
import com.ronybrand.orderapi.app.FIXED_CLOCK
import com.ronybrand.orderapi.app.MemStore
import com.ronybrand.orderapi.app.OrderService
import com.ronybrand.orderapi.app.RecordingPublisher
import com.ronybrand.orderapi.auth.JwtVerifier
import com.ronybrand.orderapi.domain.Customer
import com.ronybrand.orderapi.domain.CustomerInput
import com.ronybrand.orderapi.domain.ItemInput
import com.ronybrand.orderapi.domain.Order
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

const val TEST_AUDIENCE = "order-api"
const val TEST_ISSUER = "https://idp.example"

private val KEYS = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/** Everything an HTTP test needs: a store, the services over it, a verifier and a way to mint tokens. */
class ApiFixture(
    val config: HttpConfig = HttpConfig(),
    var ping: () -> Unit = {},
    val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val store = MemStore()
    val publisher = RecordingPublisher()
    val customers = CustomerService(store, FIXED_CLOCK)
    val orders = OrderService(store, publisher, FIXED_CLOCK)
    val verifier = JwtVerifier(KEYS.public as RSAPublicKey, TEST_AUDIENCE, TEST_ISSUER)

    fun dependencies() = ApiDependencies(customers, orders, TokenVerifier(verifier::verify), {
        ping()
    }, config, nowMillis)

    /** `who` is "user", "admin" or a ready-made token; null sends no credentials at all. */
    fun token(
        subject: String = "user-1",
        roles: List<String> = listOf("user"),
    ): String =
        JWT
            .create()
            .withSubject(subject)
            .withAudience(TEST_AUDIENCE)
            .withIssuer(TEST_ISSUER)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
            .withClaim("roles", roles)
            .sign(Algorithm.RSA256(null, KEYS.private as RSAPrivateKey))

    private fun bearer(who: String?): String? =
        when (who) {
            null -> null
            "user" -> token("user-1", listOf("user"))
            "admin" -> token("admin-1", listOf("user", "admin"))
            else -> who
        }

    fun seedCustomer(
        taxId: String = "TAX0001",
        email: String = "ada@example.com",
    ): Customer = customers.create(com.ronybrand.orderapi.app.Actor("seed"), CustomerInput("Ada", taxId, null, email))

    fun seedOrder(
        customerId: java.util.UUID = seedCustomer().id,
        vararg items: ItemInput = arrayOf(ItemInput("Widget", BigDecimal("2.50"), 4)),
    ): Order = orders.create(com.ronybrand.orderapi.app.Actor("seed"), customerId, items.toList())

    fun run(block: suspend Api.() -> Unit) {
        testApplication {
            application { orderApi(dependencies()) }
            block(Api(client, this@ApiFixture::bearer))
        }
    }
}

/** A thin client: one call per request, with the credentials as an argument. */
class Api(val http: HttpClient, private val bearer: (String?) -> String?) {
    suspend fun call(
        method: String,
        path: String,
        who: String? = "user",
        body: String? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse =
        http.request(path) {
            this.method = HttpMethod.parse(method)
            bearer(who)?.let { header("Authorization", "Bearer $it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            configure()
        }
}

val TEST_JSON = Json

suspend fun HttpResponse.json(): JsonObject = TEST_JSON.parseToJsonElement(bodyAsText()).jsonObject

suspend fun HttpResponse.jsonArray(): JsonArray = TEST_JSON.parseToJsonElement(bodyAsText()).jsonArray

suspend fun HttpResponse.code(): String = json().getValue("code").toString().trim('"')
