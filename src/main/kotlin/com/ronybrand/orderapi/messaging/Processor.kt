package com.ronybrand.orderapi.messaging

import com.ronybrand.orderapi.domain.OrderStatusChanged
import org.slf4j.LoggerFactory
import java.util.UUID

// Queue topology and retry policy (DOMAIN.md section 5, ADR 0003).
const val QUEUE_NAME = "order.status.changed"
const val DLQ_NAME = "$QUEUE_NAME.dlq"
const val MAX_RETRIES = 3
const val RETRY_DELAY_MILLIS = 2_000L
const val RETRY_COUNT_HEADER = "x-retry-count"
const val SEEN_TTL_MILLIS = 3_600_000L
const val SEEN_CAPACITY = 10_000

/** Marks a failure that retrying cannot fix (an invalid recipient, say): the message goes straight to the DLQ. */
class PermanentFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A delivery as the processor sees it, independent of the AMQP library. */
class Message(
    /** The AMQP message_id, used to drop redeliveries. */
    val id: String?,
    val body: ByteArray,
    /** The value of the x-retry-count header. */
    val retries: Int,
)

/** What the consumer must do with the delivery. */
enum class Action {
    /** Done, or a duplicate: acknowledge. */
    ACK,

    /** Transient failure: republish with the retry count incremented, after the retry delay. */
    RETRY,

    /** Unrecoverable: publish to the dead letter queue. */
    DEAD_LETTER,
}

/** Delivers the notification of one event. */
fun interface Notifier {
    fun notify(event: OrderStatusChanged)
}

/**
 * Decides what happens to each message. It owns no connection, so the retry, dedupe and dead letter rules are tested
 * without a broker. Delivery is at-least-once, so a message already completed (same message_id) is acknowledged
 * without notifying again; only a successful notification marks the id as done.
 */
class Processor(private val notifier: Notifier, nowMillis: () -> Long = System::currentTimeMillis) {
    private val log = LoggerFactory.getLogger(Processor::class.java)
    private val seen = SeenSet(SEEN_TTL_MILLIS, SEEN_CAPACITY, nowMillis)

    fun process(message: Message): Action {
        val event = EventWire.decode(message.body)
        if (event == null || event.orderId == UUID(0, 0)) {
            log.error("discarding malformed message {}", message.id)
            return Action.DEAD_LETTER
        }
        if (message.id != null && seen.has(message.id)) {
            log.info("duplicate message ignored: {}", message.id)
            return Action.ACK
        }
        try {
            notifier.notify(event)
        } catch (e: PermanentFailure) {
            log.error("notification failed permanently for {}: {}", event, e.message)
            return Action.DEAD_LETTER
        } catch (e: Exception) {
            if (message.retries >= MAX_RETRIES) {
                log.error("notification failed, retries exhausted for {}: {}", event, e.javaClass.simpleName)
                return Action.DEAD_LETTER
            }
            log.warn("notification failed for {}, will retry ({}): {}", event, message.retries, e.javaClass.simpleName)
            return Action.RETRY
        }
        message.id?.let { seen.add(it) }
        log.info("notification sent for {}", event)
        return Action.ACK
    }
}

/** Remembers recently completed message ids: bounded in size and in time, so it cannot leak memory. */
class SeenSet(private val ttlMillis: Long, private val capacity: Int, private val nowMillis: () -> Long) {
    private val entries = HashMap<String, Long>()

    @Synchronized
    fun has(id: String): Boolean {
        val at = entries[id] ?: return false
        if (nowMillis() - at > ttlMillis) {
            entries.remove(id)
            return false
        }
        return true
    }

    @Synchronized
    fun add(id: String) {
        val now = nowMillis()
        if (entries.size >= capacity) evict(now)
        entries[id] = now
    }

    @Synchronized
    fun size() = entries.size

    /** Drops the expired entries and, if that was not enough, the oldest one. */
    private fun evict(now: Long) {
        entries.entries.removeIf { now - it.value > ttlMillis }
        if (entries.size >= capacity) {
            entries.entries.minByOrNull { it.value }?.let { entries.remove(it.key) }
        }
    }
}
