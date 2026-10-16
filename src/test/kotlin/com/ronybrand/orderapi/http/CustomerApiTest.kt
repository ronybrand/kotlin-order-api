package com.ronybrand.orderapi.http

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CustomerApiTest {
    private val validBody =
        """{"name":"Ana","tax_id":"123.456-78","passport_number":"AB123456","email":"ana@example.com"}"""

    @Test
    fun `creating a customer returns only the id and the name`() {
        ApiFixture().run {
            val response = call("POST", "/customers", "admin", validBody)

            assertEquals(HttpStatusCode.Created, response.status)
            val text = response.bodyAsText()
            val body = TEST_JSON.parseToJsonElement(text).jsonObject
            assertEquals(setOf("id", "name"), body.keys)
            assertEquals("Ana", body.getValue("name").jsonPrimitive.content)
            assertFalse(text.contains("ana@example.com"))
            assertFalse(text.contains("12345678"))
        }
    }

    @Test
    fun `customer mutations require the admin role`() {
        val fixture = ApiFixture()
        val customer = fixture.seedCustomer()
        fixture.run {
            assertEquals(HttpStatusCode.Forbidden, call("POST", "/customers", "user", validBody).status)
            assertEquals(HttpStatusCode.Forbidden, call("PUT", "/customers/${customer.id}", "user", validBody).status)
            assertEquals(HttpStatusCode.Forbidden, call("DELETE", "/customers/${customer.id}", "user").status)
            assertEquals("AUTH-02", call("POST", "/customers", "user", validBody).code())
        }
    }

    @Test
    fun `any authenticated user can read a customer`() {
        val fixture = ApiFixture()
        val customer = fixture.seedCustomer()
        fixture.run {
            val response = call("GET", "/customers/${customer.id}", "user")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(customer.id.toString(), response.json().getValue("id").jsonPrimitive.content)
        }
    }

    @Test
    fun `unknown fields, malformed JSON and an empty body are VALIDATION-01`() {
        ApiFixture().run {
            val bodies =
                listOf(
                    """{"name":"Ana","tax_id":"12345678","email":"a@b.co","admin":true}""",
                    "{",
                    "",
                    """{"name":"Ana","tax_id":"12345678","email":"a@b.co"} {"extra":1}""",
                    """{"name":1,"tax_id":"12345678","email":"a@b.co"}""",
                    """{"tax_id":"12345678","email":"a@b.co"}""",
                )
            for (body in bodies) {
                val response = call("POST", "/customers", "admin", body)
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                assertEquals("VALIDATION-01", response.code(), body)
            }
        }
    }

    @Test
    fun `domain validation errors come back as VALIDATION-01 naming the field`() {
        ApiFixture().run {
            val response = call("POST", "/customers", "admin", """{"name":"Ana","tax_id":"1","email":"a@b.co"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                "tax_id",
                response.json().getValue("params").jsonObject.getValue("field").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `a duplicate tax id is a 409 CONFLICT-01 that does not echo it`() {
        val fixture = ApiFixture()
        fixture.seedCustomer(taxId = "12345678")
        fixture.run {
            val response = call("POST", "/customers", "admin", validBody)

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("CONFLICT-01", response.code())
            assertFalse(response.bodyAsText().contains("12345678"))
        }
    }

    @Test
    fun `update replaces the data and answers 404 for an unknown customer`() {
        val fixture = ApiFixture()
        val customer = fixture.seedCustomer()
        fixture.run {
            val response = call("PUT", "/customers/${customer.id}", "admin", validBody)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Ana", response.json().getValue("name").jsonPrimitive.content)
            val missing = call("PUT", "/customers/${UUID.randomUUID()}", "admin", validBody)
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals("RESOURCE-NOT-FOUND-01", missing.code())
        }
    }

    @Test
    fun `delete answers 204 with no body and propagates the conflict of a customer with orders`() {
        val fixture = ApiFixture()
        val free = fixture.seedCustomer(taxId = "FREE0001")
        val busy = fixture.seedCustomer(taxId = "BUSY0001")
        fixture.seedOrder(busy.id)
        fixture.run {
            val ok = call("DELETE", "/customers/${free.id}", "admin")
            val conflict = call("DELETE", "/customers/${busy.id}", "admin")

            assertEquals(HttpStatusCode.NoContent, ok.status)
            assertEquals("", ok.bodyAsText())
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("CONFLICT-03", conflict.code())
            assertEquals(HttpStatusCode.NotFound, call("GET", "/customers/${free.id}", "user").status)
        }
    }

    @Test
    fun `path ids must be UUIDs`() {
        ApiFixture().run {
            val requests = listOf(
                "GET" to "/customers/123",
                "GET" to "/customers/not-a-uuid",
                "GET" to "/orders/1-1-1-1-1",
                "POST" to "/orders/xyz/confirm",
            )
            for ((method, path) in requests) {
                val response = call(method, path, "user")
                assertEquals(HttpStatusCode.BadRequest, response.status, path)
                assertEquals("VALIDATION-01", response.code(), path)
            }
        }
    }
}
