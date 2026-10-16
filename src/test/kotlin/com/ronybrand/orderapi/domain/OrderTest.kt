package com.ronybrand.orderapi.domain

import com.ronybrand.orderapi.NOW
import com.ronybrand.orderapi.assertCode
import com.ronybrand.orderapi.error.Codes
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderTest {
    private fun item(
        price: String,
        quantity: Int,
    ) = ItemInput(description = "Widget", unitPrice = BigDecimal(price), quantity = quantity)

    private fun openOrder(vararg items: ItemInput) = Order.create(UUID.randomUUID(), items.toList(), "user-1", NOW)

    private fun Order.withStatus(status: OrderStatus) = copy(status = status)

    @Test
    fun `a new order is OPEN, version 1, with the total of its items`() {
        val order = openOrder(item("2.50", 4), item("10", 1))

        assertEquals(OrderStatus.OPEN, order.status)
        assertEquals(1L, order.version)
        assertEquals(2, order.items.size)
        assertEquals(BigDecimal("20.00"), order.total)
        assertTrue(order.items.all { it.orderId == order.id })
        assertEquals("user-1", order.createdBy)
    }

    @Test
    fun `the total is always derived from the current items`() {
        var order = openOrder(item("10", 2))

        order = order.addItem(item("0.10", 3), "u", NOW)
        assertEquals(BigDecimal("20.30"), order.total)

        order = order.updateItemQuantity(order.items[0].id, 1, "u", NOW)
        assertEquals(BigDecimal("10.30"), order.total)

        order = order.removeItem(order.items[0].id, "u", NOW)
        assertEquals(BigDecimal("0.30"), order.total)
    }

    @Test
    fun `the total does not lose cents`() {
        // 0.1 + 0.2 in binary floating point is 0.30000000000000004.
        assertEquals(BigDecimal("0.30"), openOrder(item("0.10", 1), item("0.20", 1)).total)
    }

    @Test
    fun `an item subtotal is the unit price times the quantity`() {
        assertEquals(BigDecimal("7.50"), openOrder(item("2.50", 3)).items[0].subtotal)
    }

    @Test
    fun `invalid items are VALIDATION-01`() {
        val cases =
            mapOf(
                "blank description" to ItemInput("  ", BigDecimal("1"), 1),
                "long description" to ItemInput("a".repeat(256), BigDecimal("1"), 1),
                "zero price" to item("0", 1),
                "negative price" to item("-1", 1),
                "three decimals" to item("1.001", 1),
                "price above column" to item("100000000", 1),
                "zero quantity" to item("1", 0),
                "negative quantity" to item("1", -3),
                "quantity above max" to item("1", MAX_QUANTITY + 1),
            )

        for ((name, input) in cases) {
            assertCode(Codes.INVALID_REQUEST) { Order.create(UUID.randomUUID(), listOf(input), "u", NOW) }
            assertTrue(name.isNotEmpty())
        }
    }

    @Test
    fun `trailing zeros after the cents are not extra decimal places`() {
        assertEquals(BigDecimal("1.50"), openOrder(item("1.500", 1)).items[0].unitPrice.setScale(2))
        assertEquals(BigDecimal("99999999.99"), openOrder(item("99999999.99", 1)).items[0].unitPrice)
    }

    @Test
    fun `the item limit is exact`() {
        val atLimit = List(MAX_ITEMS_PER_ORDER) { item("1", 1) }

        assertEquals(MAX_ITEMS_PER_ORDER, Order.create(UUID.randomUUID(), atLimit, "u", NOW).items.size)
        assertCode(Codes.ITEM_LIMIT) { Order.create(UUID.randomUUID(), atLimit + item("1", 1), "u", NOW) }
    }

    @Test
    fun `adding an item respects the limit too`() {
        val full = Order.create(UUID.randomUUID(), List(MAX_ITEMS_PER_ORDER) { item("1", 1) }, "u", NOW)

        assertCode(Codes.ITEM_LIMIT) { full.addItem(item("1", 1), "u", NOW) }
    }

    @Test
    fun `items are editable only while the order is OPEN`() {
        for (status in listOf(OrderStatus.CONFIRMED, OrderStatus.CANCELED)) {
            val order = openOrder(item("1", 1)).withStatus(status)
            val itemId = order.items[0].id

            assertFalse(order.isEditable)
            assertCode(Codes.ORDER_NOT_EDITABLE) { order.addItem(item("1", 1), "u", NOW) }
            assertCode(Codes.ORDER_NOT_EDITABLE) { order.updateItemQuantity(itemId, 2, "u", NOW) }
            assertCode(Codes.ORDER_NOT_EDITABLE) { order.removeItem(itemId, "u", NOW) }
            assertEquals(1, order.items.size)
        }
    }

    @Test
    fun `operations on an unknown item are RESOURCE-NOT-FOUND-03`() {
        val order = openOrder(item("1", 1))

        assertCode(Codes.ITEM_NOT_FOUND) { order.updateItemQuantity(UUID.randomUUID(), 2, "u", NOW) }
        assertCode(Codes.ITEM_NOT_FOUND) { order.removeItem(UUID.randomUUID(), "u", NOW) }
    }

    @Test
    fun `the quantity of an item is validated`() {
        val order = openOrder(item("1", 1))
        val itemId = order.items[0].id

        assertCode(Codes.INVALID_REQUEST) { order.updateItemQuantity(itemId, 0, "u", NOW) }
        assertCode(Codes.INVALID_REQUEST) { order.updateItemQuantity(itemId, MAX_QUANTITY + 1, "u", NOW) }
        assertEquals(MAX_QUANTITY, order.updateItemQuantity(itemId, MAX_QUANTITY, "u", NOW).items[0].quantity)
    }

    @Test
    fun `item operations stamp the audit fields and leave the original alone`() {
        val order = openOrder(item("1", 1))
        val later = NOW.plus(Duration.ofMinutes(5))

        val updated = order.addItem(item("2", 1), "editor", later)

        assertEquals("editor", updated.updatedBy)
        assertEquals(later, updated.updatedAt)
        assertEquals(1, order.items.size)
        assertEquals("user-1", order.updatedBy)
    }

    @Test
    fun `confirm moves OPEN to CONFIRMED and reports the previous status`() {
        val change = openOrder(item("1", 1)).confirm("approver", NOW.plus(Duration.ofMinutes(1)))

        assertEquals(OrderStatus.OPEN, change.previous)
        assertEquals(OrderStatus.CONFIRMED, change.order.status)
        assertEquals("approver", change.order.updatedBy)
    }

    @Test
    fun `an empty order cannot be confirmed`() {
        assertCode(Codes.ORDER_EMPTY) { openOrder().confirm("u", NOW) }
    }

    @Test
    fun `confirm is only valid from OPEN`() {
        for (status in listOf(OrderStatus.CONFIRMED, OrderStatus.CANCELED)) {
            assertCode(Codes.INVALID_TRANSITION) { openOrder(item("1", 1)).withStatus(status).confirm("u", NOW) }
        }
    }

    @Test
    fun `cancel works from OPEN and from CONFIRMED`() {
        for (from in listOf(OrderStatus.OPEN, OrderStatus.CONFIRMED)) {
            val change = openOrder(item("1", 1)).withStatus(from).cancel("u", NOW)

            assertEquals(from, change.previous)
            assertEquals(OrderStatus.CANCELED, change.order.status)
        }
    }

    @Test
    fun `an already canceled order cannot be canceled again`() {
        assertCode(Codes.ALREADY_CANCELED) { openOrder(item("1", 1)).withStatus(OrderStatus.CANCELED).cancel("u", NOW) }
    }

    @Test
    fun `an order without a deletion mark is active`() {
        assertFalse(openOrder().isDeleted)
        assertTrue(openOrder().copy(deletedAt = NOW, deletedBy = "u").isDeleted)
    }
}
