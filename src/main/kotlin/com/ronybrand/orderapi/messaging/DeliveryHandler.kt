package com.ronybrand.orderapi.messaging

import com.rabbitmq.client.AMQP
import org.slf4j.LoggerFactory

/** A delivery from the broker, reduced to what the handler needs. */
class Delivered(
    val messageId: String?,
    val body: ByteArray,
    val retries: Int,
    /** The original properties, kept so a moved copy has the same content type, timestamp and headers. */
    val properties: AMQP.BasicProperties? = null,
)

/** What the consumer does with the delivery after the handler decided. */
enum class Outcome { ACK, REQUEUE }

/** Moves a delivery to another queue and returns only once the broker has confirmed it. */
interface Mover {
    fun move(
        queue: String,
        delivery: Delivered,
        retries: Int,
    )
}

/**
 * The consumer's decision logic, free of the AMQP connection: apply the [Processor]'s verdict, move the message when
 * it must be retried or dead-lettered, and report whether the original may be acknowledged. A message is never
 * acknowledged before its copy is confirmed, so a failure while moving it puts it back instead of losing it.
 */
class DeliveryHandler(
    private val processor: Processor,
    private val mover: Mover,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    private val log = LoggerFactory.getLogger(DeliveryHandler::class.java)

    // Whatever goes wrong while moving the message (a closed channel, a timeout, a nack from the broker), the answer is
    // the same: put the original back instead of acknowledging it.
    @Suppress("TooGenericExceptionCaught")
    fun handle(delivery: Delivered): Outcome {
        val action = processor.process(Message(delivery.messageId, delivery.body, delivery.retries))
        return try {
            when (action) {
                Action.ACK -> Unit

                Action.RETRY -> {
                    sleeper(retryDelayMillis)
                    mover.move(QUEUE_NAME, delivery, delivery.retries + 1)
                }

                Action.DEAD_LETTER -> mover.move(DLQ_NAME, delivery, delivery.retries)
            }
            Outcome.ACK
        } catch (_: InterruptedException) {
            // Shutting down: leave the message for the next start.
            Thread.currentThread().interrupt()
            Outcome.REQUEUE
        } catch (e: Exception) {
            log.error(
                "could not move message {} ({}), requeueing: {}",
                delivery.messageId,
                action,
                e.javaClass.simpleName,
            )
            Outcome.REQUEUE
        }
    }
}
