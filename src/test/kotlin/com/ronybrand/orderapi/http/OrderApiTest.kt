package com.ronybrand.orderapi.http

import com.ronybrand.orderapi.domain.OrderStatus
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderApiTest {
    private fun orderBody(
        customerId: UUID,
        vararg items: String,
    ) = """{"customer_id":"$customerId","items":[${items.joinToString(",")}]}"""

    private fun item(
        price: String,
        quantity: Int = 1,
        description: String = "Pen",
    ) = """{"description":"$description","unit_price":$price,"quantity":$quantity}"""

    @Test
    fun `creating an order with numbers that would lose precision as floats keeps them exact`() {
        val fixture = ApiFixture()
        val customer = fixture.seedCustomer()
        fixture.run {
            val response = call("POST", "/orders", "user", orderBody(customer.id, item("0.10", 3), item("0.20", 1)))

            assertEquals(HttpStatusCode.Created, response.status)
            // The raw text of the money fields, not a re-encoded double.
            val text = response.bodyAsText()
            assertTrue(text.contains(""""total":0.50"""), text)
            assertTrue(text.contains(""""unit_price":0.10"""), text)
        }
    }

    @Test
    fun `the order response has the documented shape and derived amounts`() {
        val fixture = ApiFixture()
        val order = fixture.seedOrder()
        fixture.run {
            val response = call("GET", "/orders/${order.id}", "user")

            val body = response.json()
            assertEquals(setOf("id", "customer_id", "status", "version", "items", "total"), body.keys)
            assertEquals("OPEN", body.getValue("status").jsonPrimitive.content)
            assertEquals(1, body.getValue("version").jsonPrimitive.content.toInt())
            val item = body.getValue("items").jsonArray.single().jsonObject
            assertEquals(setOf("id", "order_id", "description", "unit_price", "quantity", "subtotal"), item.keys)
            assertEquals(BigDecimal("10.00"), BigDecimal(item.getValue("subtotal").jsonPrimitive.content))
            assertEquals(BigDecimal("10.00"), BigDecimal(body.getValue("total").jsonPrimitive.content))
        }
    }

    @Test
    fun `extreme exponents are rejected quickly and never reach the domain`() {
        val fixture = ApiFixture()
        val customer = fixture.seedCustomer()
        fixture.run {
            for (price in listOf(
                "1e999999999",
                "1e100000",
                "1e1000000",
                "1e-1000000",
                "0.0000e100000",
                "1e2147483647",
            )) {
                val elapsed =
                    measureTimeMillis {
                        val response = call("POST", "/orders", "user", orderBody(customer.id, item(price)))
                        assertEquals(HttpStatusCode.BadRequest, response.status, price)
                    }
                assertTrue(elapsed < 1_000, "$price took ${elapsed}ms")
            }
        }
    }

    @Test
    fun `a customer that does not exist is a 400 VALIDATION-07`() {
        ApiFixture().run {
            val response = call("POST", "/orders", "user", orderBody(UUID.randomUUID()))

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION-07", response.code())
        }
    }

    @Test
    fun `item routes recalculate the total`() {
        val fixture = ApiFixture()
        val order = fixture.seedOrder()
        val itemId = order.items.single().id
        fixture.run {
            val added = call("POST", "/orders/${order.id}/items", "user", item("5", 2))
            assertEquals(HttpStatusCode.OK, added.status)
            assertEquals(BigDecimal("20.00"), BigDecimal(added.json().getValue("total").jsonPrimitive.content))

            val patched = call("PATCH", "/orders/${order.id}/items/$itemId", "user", """{"quantity":1}""")
            assertEquals(BigDecimal("12.50"), BigDecimal(patched.json().getValue("total").jsonPrimitive.content))

            val removed = call("DELETE", "/orders/${order.id}/items/$itemId", "user")
            assertEquals(HttpStatusCode.OK, removed.status)
            assertEquals(BigDecimal("10.00"), BigDecimal(removed.json().getValue("total").jsonPrimitive.content))
            assertEquals(
                HttpStatusCode.NotFound,
                call("PATCH", "/orders/${order.id}/items/${UUID.randomUUID()}", "user", """{"quantity":1}""").status,
            )
        }
    }

    @Test
    fun `confirm and cancel move the status, announce it and keep their error codes`() {
        val fixture = ApiFixture()
        val order = fixture.seedOrder()
        fixture.run {
            val confirmed = call("POST", "/orders/${order.id}/confirm", "user")
            assertEquals(HttpStatusCode.OK, confirmed.status)
            assertEquals("CONFIRMED", confirmed.json().getValue("status").jsonPrimitive.content)
            assertEquals("VALIDATION-04", call("POST", "/orders/${order.id}/confirm", "user").code())
            assertEquals("VALIDATION-02", call("POST", "/orders/${order.id}/items", "user", item("1")).code())

            assertEquals(
                "CANCELED",
                call("POST", "/orders/${order.id}/cancel", "user").json().getValue("status").jsonPrimitive.content,
            )
            assertEquals("VALIDATION-08", call("POST", "/orders/${order.id}/cancel", "user").code())
        }
        assertEquals(listOf(OrderStatus.CONFIRMED, OrderStatus.CANCELED), fixture.publisher.events.map { it.newStatus })
    }

    @Test
    fun `an empty order cannot be confirmed`() {
        val fixture = ApiFixture()
        val order = fixture.orders.create(com.ronybrand.orderapi.app.Actor("u"), fixture.seedCustomer().id, emptyList())
        fixture.run {
            val response = call("POST", "/orders/${order.id}/confirm", "user")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION-03", response.code())
        }
    }

    @Test
    fun `delete is soft and answers 204, then the order is gone`() {
        val fixture = ApiFixture()
        val order = fixture.seedOrder()
        fixture.run {
            assertEquals(HttpStatusCode.NoContent, call("DELETE", "/orders/${order.id}", "user").status)
            val gone = call("GET", "/orders/${order.id}", "user")
            assertEquals(HttpStatusCode.NotFound, gone.status)
            assertEquals("RESOURCE-NOT-FOUND-02", gone.code())
        }
    }

    @Test
    fun `invalid items and unknown fields are VALIDATION-01`() {
        val fixture = ApiFixture()
        val customer = fixture.seedCustomer()
        fixture.run {
            val bodies =
                listOf(
                    orderBody(customer.id, item("0")),
                    orderBody(customer.id, item("1.001")),
                    orderBody(customer.id, item("1", 0)),
                    orderBody(customer.id, """{"description":"x","unit_price":"1.00","quantity":1}"""),
                    orderBody(customer.id, """{"description":"x","unit_price":1,"quantity":1.5}"""),
                    """{"customer_id":"nope","items":[]}""",
                    """{"customer_id":"${customer.id}","extra":true}""",
                )
            for (body in bodies) {
                val response = call("POST", "/orders", "user", body)
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                assertEquals("VALIDATION-01", response.code(), body)
            }
        }
    }
}
