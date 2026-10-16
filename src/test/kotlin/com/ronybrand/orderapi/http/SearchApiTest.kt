package com.ronybrand.orderapi.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchApiTest {
    @Test
    fun `GET search reads page, size and sort only and answers the page envelope`() {
        val fixture = ApiFixture()
        repeat(3) { fixture.seedCustomer(taxId = "TAX-000$it") }
        fixture.run {
            val response = call("GET", "/customers/search?page=1&size=2&sort=-created_at", "user")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.json()
            assertEquals(setOf("items", "total", "page", "size"), body.keys)
            assertEquals(3, body.getValue("total").jsonPrimitive.content.toInt())
            assertEquals(1, body.getValue("page").jsonPrimitive.content.toInt())
            assertEquals(2, body.getValue("size").jsonPrimitive.content.toInt())
            assertEquals(1, body.getValue("items").jsonArray.size)
        }
    }

    @Test
    fun `GET search defaults the size`() {
        ApiFixture().run {
            assertEquals(
                20,
                call("GET", "/orders/search", "user").json().getValue("size").jsonPrimitive.content.toInt(),
            )
        }
    }

    @Test
    fun `GET search rejects non numeric paging`() {
        ApiFixture().run {
            for (query in listOf("page=abc", "size=x", "page=1.5", "size=")) {
                val response = call("GET", "/customers/search?$query", "user")
                if (query == "size=") {
                    assertEquals(HttpStatusCode.OK, response.status)
                } else {
                    assertEquals(HttpStatusCode.BadRequest, response.status, query)
                    assertEquals("VALIDATION-01", response.code(), query)
                }
            }
        }
    }

    @Test
    fun `POST search forwards typed filters to the use case`() {
        val fixture = ApiFixture()
        fixture.seedOrder()
        fixture.run {
            val response =
                call(
                    "POST",
                    "/orders/search",
                    "user",
                    """{"size":5,"sort":"-created_at","filters":{"status":[{"op":"eq","value":"OPEN"}],"version":[{"op":"gte","value":1}]}}""",
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, response.json().getValue("total").jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `POST search rejects unknown fields and malformed filters`() {
        ApiFixture().run {
            val bodies =
                listOf(
                    "{",
                    "",
                    "[]",
                    """{"size":10,"extra":1}""",
                    """{"size":"10"}""",
                    """{"size":10.5}""",
                    """{"filters":[]}""",
                    """{"filters":{"name":{"op":"eq"}}}""",
                    """{"filters":{"name":[{"op":"eq","value":"x","extra":1}]}}""",
                    """{"filters":{"name":["x"]}}""",
                    """{"sort":5}""",
                )
            for (body in bodies) {
                val response = call("POST", "/customers/search", "user", body)
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                assertEquals("VALIDATION-01", response.code(), body)
            }
        }
    }

    @Test
    fun `search limits are VALIDATION codes of the catalog`() {
        ApiFixture().run {
            assertEquals("VALIDATION-01", call("POST", "/customers/search", "user", """{"size":1000}""").code())
            assertEquals("VALIDATION-01", call("GET", "/customers/search?page=-1", "user").code())
        }
    }
}
