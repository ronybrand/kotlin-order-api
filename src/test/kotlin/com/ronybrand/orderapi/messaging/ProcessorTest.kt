package com.ronybrand.orderapi.messaging

import com.ronybrand.orderapi.NOW
import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.domain.OrderStatusChanged
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingNotifier(var failure: Exception? = null) : Notifier {
    val events = mutableListOf<OrderStatusChanged>()

    override fun notify(event: OrderStatusChanged) {
        failure?.let { throw it }
        events += event
    }
}

fun sampleEvent(orderId: UUID = UUID.randomUUID()) =
    OrderStatusChanged(
        orderId = orderId,
        customerEmail = "ada@example.com",
        customerName = "Ada",
        oldStatus = OrderStatus.OPEN,
        newStatus = OrderStatus.CONFIRMED,
        totalAmount = BigDecimal("10.30"),
        changedAt = NOW,
    )

class ProcessorTest {
    private var clock = 0L
    private val notifier = RecordingNotifier()
    private val processor = Processor(notifier) { clock }

    private fun message(
        event: OrderStatusChanged = sampleEvent(),
        id: String? = "m-1",
        retries: Int = 0,
    ) = Message(id, EventWire.encode(event), retries)

    @Test
    fun `a delivered notification is acknowledged`() {
        val event = sampleEvent()

        assertEquals(Action.ACK, processor.process(message(event)))
        assertEquals(listOf(event), notifier.events)
    }

    @Test
    fun `a malformed message goes straight to the dead letter queue`() {
        for (body in listOf("not json", "{}", """{"order_id":"00000000-0000-0000-0000-000000000000"}""", "")) {
            assertEquals(Action.DEAD_LETTER, processor.process(Message("x", body.toByteArray(), 0)), body)
        }
        assertTrue(notifier.events.isEmpty())
    }

    @Test
    fun `a redelivery of a completed message is acknowledged without notifying again`() {
        val first = message(id = "same")

        processor.process(first)
        val second = processor.process(first)

        assertEquals(Action.ACK, second)
        assertEquals(1, notifier.events.size)
    }

    @Test
    fun `a failed notification does not mark the message as done`() {
        notifier.failure = IllegalStateException("smtp down")
        assertEquals(Action.RETRY, processor.process(message(id = "retry-me")))

        notifier.failure = null
        assertEquals(Action.ACK, processor.process(message(id = "retry-me", retries = 1)))
        assertEquals(1, notifier.events.size)
    }

    @Test
    fun `a message without an id is never deduplicated`() {
        processor.process(message(id = null))
        processor.process(message(id = null))

        assertEquals(2, notifier.events.size)
    }

    @Test
    fun `a transient failure is retried until the budget is spent and then dead-lettered`() {
        notifier.failure = IllegalStateException("smtp down")

        assertEquals(Action.RETRY, processor.process(message(retries = 0)))
        assertEquals(Action.RETRY, processor.process(message(retries = MAX_RETRIES - 1)))
        assertEquals(Action.DEAD_LETTER, processor.process(message(retries = MAX_RETRIES)))
    }

    @Test
    fun `a permanent failure is never retried`() {
        notifier.failure = PermanentFailure("invalid recipient")

        assertEquals(Action.DEAD_LETTER, processor.process(message(retries = 0)))
    }

    @Test
    fun `the done set forgets a message after its time to live`() {
        processor.process(message(id = "old"))
        clock += SEEN_TTL_MILLIS + 1

        processor.process(message(id = "old"))

        assertEquals(2, notifier.events.size)
    }

    @Test
    fun `the done set is bounded and evicts the oldest entry`() {
        val set = SeenSet(ttlMillis = 1_000, capacity = 3) { clock }
        for (id in listOf("a", "b", "c")) {
            set.add(id)
            clock += 10
        }

        set.add("d")

        assertEquals(3, set.size())
        assertTrue(!set.has("a"))
        assertTrue(set.has("d"))
    }

    @Test
    fun `eviction drops the expired entries first`() {
        val set = SeenSet(ttlMillis = 100, capacity = 2) { clock }
        set.add("old")
        clock += 200
        set.add("fresh")

        set.add("newest")

        assertTrue(set.has("fresh"))
        assertTrue(set.has("newest"))
        assertEquals(2, set.size())
    }
}
