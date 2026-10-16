package com.ronybrand.orderapi.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The consumer's decision logic, with the broker replaced by a recording mover. */
class ConsumerHandleTest {
    private class RecordingMover(var failure: Exception? = null) : Mover {
        val moves = mutableListOf<Triple<String, Int, String?>>()

        override fun move(
            queue: String,
            delivery: Delivered,
            retries: Int,
        ) {
            failure?.let { throw it }
            moves += Triple(queue, retries, delivery.messageId)
        }
    }

    private val notifier = RecordingNotifier()
    private val mover = RecordingMover()
    private val sleeps = mutableListOf<Long>()
    private val handler = DeliveryHandler(Processor(notifier), mover, retryDelayMillis = 2_000) { sleeps += it }

    private fun delivered(
        event: com.ronybrand.orderapi.domain.OrderStatusChanged = sampleEvent(),
        retries: Int = 0,
        id: String = "m-1",
    ) = Delivered(id, EventWire.encode(event), retries)

    @Test
    fun `a delivered message is acknowledged and nothing is moved`() {
        assertEquals(Outcome.ACK, handler.handle(delivered()))
        assertTrue(mover.moves.isEmpty())
    }

    @Test
    fun `a transient failure waits, republishes with the retry count plus one, then acknowledges the original`() {
        notifier.failure = IllegalStateException("smtp down")

        assertEquals(Outcome.ACK, handler.handle(delivered(retries = 1)))

        assertEquals(listOf(2_000L), sleeps)
        assertEquals(listOf<Triple<String, Int, String?>>(Triple(QUEUE_NAME, 2, "m-1")), mover.moves)
    }

    @Test
    fun `an exhausted message goes to the dead letter queue keeping its retry count`() {
        notifier.failure = IllegalStateException("smtp down")

        assertEquals(Outcome.ACK, handler.handle(delivered(retries = MAX_RETRIES)))

        assertEquals(listOf<Triple<String, Int, String?>>(Triple(DLQ_NAME, MAX_RETRIES, "m-1")), mover.moves)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `a malformed message is dead-lettered without waiting`() {
        assertEquals(Outcome.ACK, handler.handle(Delivered("bad", "garbage".toByteArray(), 0)))

        assertEquals(listOf<Triple<String, Int, String?>>(Triple(DLQ_NAME, 0, "bad")), mover.moves)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `when the move cannot be confirmed the original is put back instead of lost`() {
        notifier.failure = PermanentFailure("invalid recipient")
        mover.failure = java.io.IOException("broker down")

        assertEquals(Outcome.REQUEUE, handler.handle(delivered()))
    }

    @Test
    fun `a shutdown during the retry wait leaves the message for the next start`() {
        notifier.failure = IllegalStateException("smtp down")
        val interrupted =
            DeliveryHandler(Processor(notifier), mover, retryDelayMillis = 2_000) { throw InterruptedException() }

        assertEquals(Outcome.REQUEUE, interrupted.handle(delivered()))
        assertTrue(mover.moves.isEmpty())
    }
}
