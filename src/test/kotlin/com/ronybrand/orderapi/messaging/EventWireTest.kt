package com.ronybrand.orderapi.messaging

import com.ronybrand.orderapi.NOW
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventWireTest {
    @Test
    fun `the event round-trips and keeps the amount exact`() {
        val event = sampleEvent().copy(totalAmount = BigDecimal("0.30"))

        assertEquals(event, EventWire.decode(EventWire.encode(event)))
    }

    @Test
    fun `the JSON is the snake_case contract of the queue, with the amount as text`() {
        val json = Json.parseToJsonElement(String(EventWire.encode(sampleEvent()))).jsonObject

        assertEquals(
            setOf(
                "order_id",
                "customer_email",
                "customer_name",
                "old_status",
                "new_status",
                "total_amount",
                "changed_at",
            ),
            json.keys,
        )
        assertEquals("10.30", json.getValue("total_amount").jsonPrimitive.content)
        assertEquals("OPEN", json.getValue("old_status").jsonPrimitive.content)
        assertEquals(NOW.toString(), json.getValue("changed_at").jsonPrimitive.content)
    }

    @Test
    fun `anything that is not a valid event decodes to null`() {
        for (body in listOf(
            "",
            "x",
            "[]",
            """{"order_id":"nope"}""",
            """{"order_id":"${java.util.UUID.randomUUID()}","new_status":"SHIPPED"}""",
        )) {
            assertNull(EventWire.decode(body.toByteArray()), body)
        }
    }
}
