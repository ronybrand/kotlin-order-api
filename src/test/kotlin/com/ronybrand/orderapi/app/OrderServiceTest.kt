package com.ronybrand.orderapi.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ronybrand.orderapi.NOW
import com.ronybrand.orderapi.assertCode
import com.ronybrand.orderapi.domain.Customer
import com.ronybrand.orderapi.domain.ItemInput
import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.domain.OrderStatusChanged
import com.ronybrand.orderapi.error.Codes
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingPublisher(private val failure: Exception? = null) : EventPublisher {
    val events = mutableListOf<OrderStatusChanged>()

    override fun publishStatusChanged(event: OrderStatusChanged) {
        failure?.let { throw it }
        events += event
    }
}

class OrderServiceTest {
    private val store = MemStore()

    private fun service(publisher: EventPublisher? = null) = OrderService(store, publisher, FIXED_CLOCK)

    private fun widget(
        price: String,
        quantity: Int,
    ) = ItemInput("Widget", BigDecimal(price), quantity)

    private fun customer(email: String = "ada@example.com") =
        store.seedCustomer(
            Customer(
                id = UUID.randomUUID(),
                name = "Ada",
                taxId = "TAX0001",
                passportNumber = null,
                email = email,
                version = 1,
                createdAt = NOW,
                updatedAt = NOW,
                createdBy = "seed",
                updatedBy = "seed",
            ),
        )

    @Test
    fun `create opens an order locking the customer FOR SHARE in the same transaction`() {
        val customer = customer()

        val order = service().create(user("user-1"), customer.id, listOf(widget("2.50", 4)))

        assertEquals(OrderStatus.OPEN, order.status)
        assertEquals(BigDecimal("10.00"), order.total)
        assertEquals("user-1", order.createdBy)
        assertEquals(listOf("begin", "customer.lock-share", "order.create", "commit"), store.calls)
        assertEquals(order, service().get(order.id))
    }

    @Test
    fun `a customer that does not exist is VALIDATION-07 and not 404`() {
        assertCode(Codes.CUSTOMER_NOT_FOUND_IN_BODY) { service().create(user("u"), UUID.randomUUID(), emptyList()) }

        assertEquals("rollback", store.lastCall())
    }

    @Test
    fun `a soft-deleted customer is treated as missing`() {
        val customer = store.seedCustomer(customer().copy(deletedAt = NOW, deletedBy = "x"))

        assertCode(Codes.CUSTOMER_NOT_FOUND_IN_BODY) { service().create(user("u"), customer.id, emptyList()) }
    }

    @Test
    fun `an invalid item stores nothing`() {
        val customer = customer()

        assertCode(Codes.INVALID_REQUEST) { service().create(user("u"), customer.id, listOf(widget("0", 1))) }

        assertTrue(store.data.orders.isEmpty())
    }

    @Test
    fun `item operations recalculate the total and bump the version`() {
        val svc = service()
        var order = svc.create(user("u"), customer().id, listOf(widget("10", 1)))

        order = svc.addItem(user("u"), order.id, widget("5", 2))
        assertEquals(BigDecimal("20.00"), order.total)
        assertEquals(2L, order.version)

        order = svc.updateItemQuantity(user("u"), order.id, order.items[0].id, 3)
        assertEquals(BigDecimal("40.00"), order.total)

        order = svc.removeItem(user("u"), order.id, order.items[0].id)
        assertEquals(BigDecimal("10.00"), order.total)
        assertEquals(1, order.items.size)
        assertEquals(BigDecimal("10.00"), svc.get(order.id).total)
    }

    @Test
    fun `item operations on unknown things keep their codes`() {
        val svc = service()
        val order = svc.create(user("u"), customer().id, listOf(widget("10", 1)))

        assertCode(Codes.ITEM_NOT_FOUND) { svc.updateItemQuantity(user("u"), order.id, UUID.randomUUID(), 1) }
        assertCode(Codes.ORDER_NOT_FOUND) { svc.addItem(user("u"), UUID.randomUUID(), widget("1", 1)) }
    }

    @Test
    fun `confirm and cancel publish the event`() {
        val publisher = RecordingPublisher()
        val svc = service(publisher)
        val order = svc.create(user("u"), customer().id, listOf(widget("2.50", 4)))

        val confirmed = svc.confirm(user("approver"), order.id)

        assertEquals(OrderStatus.CONFIRMED, confirmed.status)
        val event = publisher.events.single()
        assertEquals(order.id, event.orderId)
        assertEquals("ada@example.com", event.customerEmail)
        assertEquals("Ada", event.customerName)
        assertEquals(OrderStatus.OPEN, event.oldStatus)
        assertEquals(OrderStatus.CONFIRMED, event.newStatus)
        assertEquals(BigDecimal("10.00"), event.totalAmount)
        assertEquals(NOW, event.changedAt)

        svc.cancel(user("approver"), order.id)

        assertEquals(2, publisher.events.size)
        assertEquals(OrderStatus.CONFIRMED, publisher.events[1].oldStatus)
        assertEquals(OrderStatus.CANCELED, publisher.events[1].newStatus)
    }

    @Test
    fun `no event for a customer without an e-mail`() {
        val publisher = RecordingPublisher()
        val svc = service(publisher)
        val order = svc.create(user("u"), customer(email = "").id, listOf(widget("1", 1)))

        svc.confirm(user("u"), order.id)

        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `a failed transition publishes nothing`() {
        val publisher = RecordingPublisher()
        val svc = service(publisher)
        val empty = svc.create(user("u"), customer().id, emptyList())

        assertCode(Codes.ORDER_EMPTY) { svc.confirm(user("u"), empty.id) }

        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `without a publisher the transition still works`() {
        val svc = service(null)
        val order = svc.create(user("u"), customer().id, listOf(widget("1", 1)))

        assertEquals(OrderStatus.CONFIRMED, svc.confirm(user("u"), order.id).status)
    }

    @Test
    fun `a broker failure never fails the transition`() {
        val svc = service(RecordingPublisher(failure = IllegalStateException("broker down")))
        val order = svc.create(user("u"), customer().id, listOf(widget("1", 1)))

        val confirmed = svc.confirm(user("u"), order.id)

        assertEquals(OrderStatus.CONFIRMED, confirmed.status)
        assertEquals(OrderStatus.CONFIRMED, svc.get(order.id).status)
    }

    @Test
    fun `when the customer cannot be loaded the failure is logged without personal data and nothing is published`() {
        val logs = captureLogs()
        val publisher = RecordingPublisher()
        val svc = service(publisher)
        val customer = customer()
        val order = svc.create(user("u"), customer.id, listOf(widget("1", 1)))
        store.repos().customers.softDelete(customer.id, "x", NOW)

        val confirmed = svc.confirm(user("u"), order.id)

        assertEquals(OrderStatus.CONFIRMED, confirmed.status)
        assertTrue(publisher.events.isEmpty())
        val errors = logs.list.filter { it.level == Level.ERROR }
        assertTrue(errors.isNotEmpty(), "expected an error log")
        assertTrue(errors.none { "ada@example.com" in it.formattedMessage })
    }

    @Test
    fun `a publish failure is logged without the e-mail`() {
        val logs = captureLogs()
        val svc = service(RecordingPublisher(failure = IllegalStateException("broker down")))
        val order = svc.create(user("u"), customer().id, listOf(widget("1", 1)))

        svc.confirm(user("u"), order.id)

        val errors = logs.list.filter { it.level == Level.ERROR }
        assertTrue(errors.isNotEmpty())
        assertFalse(errors.any { "ada@example.com" in it.formattedMessage })
    }

    @Test
    fun `rule violations keep their codes`() {
        val svc = service()
        val order = svc.create(user("u"), customer().id, listOf(widget("1", 1)))
        svc.confirm(user("u"), order.id)

        assertCode(Codes.INVALID_TRANSITION) { svc.confirm(user("u"), order.id) }
        assertCode(Codes.ORDER_NOT_EDITABLE) { svc.addItem(user("u"), order.id, widget("1", 1)) }
        svc.cancel(user("u"), order.id)
        assertCode(Codes.ALREADY_CANCELED) { svc.cancel(user("u"), order.id) }
    }

    @Test
    fun `a concurrent modification is a conflict and not an overwrite`() {
        val svc = service()
        val order = svc.create(user("u"), customer().id, listOf(widget("1", 1)))
        // Someone else commits a change to the same order between our read and our save.
        store.beforeOrderSave = { data -> data.orders[order.id] = data.orders.getValue(order.id).copy(version = 2) }

        assertCode(Codes.CONCURRENT_MODIFICATION) { svc.addItem(user("u"), order.id, widget("1", 1)) }

        assertEquals(
            1,
            store.data.orders
                .getValue(order.id)
                .items.size,
        )
    }

    @Test
    fun `delete soft-deletes and the order disappears`() {
        val svc = service()
        val order = svc.create(user("u"), customer().id, emptyList())

        svc.delete(user("u"), order.id)

        assertCode(Codes.ORDER_NOT_FOUND) { svc.get(order.id) }
        assertCode(Codes.ORDER_NOT_FOUND) { svc.delete(user("u"), order.id) }
    }

    @Test
    fun `search validates the request`() {
        assertCode(Codes.INVALID_REQUEST) { service().search(SearchRequest(page = -1, size = 10)) }
        assertEquals(DEFAULT_PAGE_SIZE, service().search(SearchRequest()).size)
    }

    private fun captureLogs(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        (LoggerFactory.getLogger(OrderService::class.java) as Logger).addAppender(appender)
        return appender
    }
}
