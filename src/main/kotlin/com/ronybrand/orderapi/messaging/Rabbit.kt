package com.ronybrand.orderapi.messaging

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import com.ronybrand.orderapi.app.EventPublisher
import com.ronybrand.orderapi.domain.OrderStatusChanged
import org.slf4j.LoggerFactory
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TIMEOUT_MILLIS = 5_000
private const val RECONNECT_DELAY_MILLIS = 5_000L
private const val PERSISTENT = 2

internal fun connectionFactory(url: String) =
    ConnectionFactory().apply {
        setUri(url)
        // amqp://host/ is the default vhost "/" for every other client, and the URL of the compose file; the Java
        // client reads it as the empty vhost, which no broker has: the broker closes the connection
        // ("vhost  not found").
        if (virtualHost.isEmpty()) virtualHost = "/"
        connectionTimeout = TIMEOUT_MILLIS
        handshakeTimeout = TIMEOUT_MILLIS
        // The consumer reconnects by itself with its own policy; hidden recovery would reopen channels behind its back.
        isAutomaticRecoveryEnabled = false
    }

/**
 * Creates the queues idempotently: the main queue dead-letters to the DLQ through the default exchange, and both
 * survive a broker restart.
 */
internal fun declareTopology(channel: Channel) {
    channel.queueDeclare(DLQ_NAME, true, false, false, null)
    channel.queueDeclare(
        QUEUE_NAME,
        true,
        false,
        false,
        mapOf("x-dead-letter-exchange" to "", "x-dead-letter-routing-key" to DLQ_NAME),
    )
}

/** Publishes one persistent message and returns only once the broker has taken responsibility for it. */
private fun Channel.publishConfirmed(
    queue: String,
    properties: AMQP.BasicProperties,
    body: ByteArray,
) {
    basicPublish("", queue, properties, body)
    waitForConfirmsOrDie(TIMEOUT_MILLIS.toLong())
}

/**
 * The [EventPublisher] backed by RabbitMQ. It dials lazily and redials after the connection drops, so an unavailable
 * broker at startup does not stop the API (publishing is best effort anyway, ADR 0003).
 */
class RabbitPublisher(private val url: String) :
    EventPublisher,
    AutoCloseable {
    private var connection: Connection? = null
    private var channel: Channel? = null

    @Synchronized
    private fun channel(): Channel {
        channel?.takeIf { it.isOpen }?.let { return it }
        runCatching { connection?.close() }
        val newConnection = connectionFactory(url).newConnection("order-api-publisher")
        var ready = false
        try {
            val newChannel = newConnection.createChannel()
            declareTopology(newChannel)
            // Publisher confirms: a publish is only successful once the broker has it.
            newChannel.confirmSelect()
            connection = newConnection
            channel = newChannel
            ready = true
            return newChannel
        } finally {
            if (!ready) runCatching { newConnection.close() }
        }
    }

    @Synchronized
    override fun publishStatusChanged(event: OrderStatusChanged) {
        val properties =
            AMQP.BasicProperties
                .Builder()
                .contentType("application/json")
                .deliveryMode(PERSISTENT)
                .messageId(UUID.randomUUID().toString())
                .timestamp(Date())
                .build()
        channel().publishConfirmed(QUEUE_NAME, properties, EventWire.encode(event))
    }

    @Synchronized
    override fun close() {
        runCatching { connection?.close() }
    }
}

private fun retriesOf(properties: AMQP.BasicProperties?): Int =
    (properties?.headers?.get(RETRY_COUNT_HEADER) as? Number)?.toInt() ?: 0

/** Copies a delivery (same body and message_id, so dedupe still works) to another queue, confirmed by the broker. */
private class RabbitMover(private val channel: Channel) : Mover {
    override fun move(
        queue: String,
        delivery: Delivered,
        retries: Int,
    ) {
        val original = delivery.properties
        val headers = (original?.headers ?: emptyMap()).toMutableMap<String, Any?>()
        headers[RETRY_COUNT_HEADER] = retries
        val properties =
            AMQP.BasicProperties
                .Builder()
                .contentType(original?.contentType ?: "application/json")
                .deliveryMode(PERSISTENT)
                .messageId(delivery.messageId)
                .timestamp(original?.timestamp)
                .headers(headers)
                .build()
        channel.publishConfirmed(queue, properties, delivery.body)
    }
}

/** Reads the queue and applies the [DeliveryHandler]'s decision to each delivery, reconnecting after a failure. */
class RabbitConsumer(
    private val url: String,
    private val processor: Processor,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
) {
    private val log = LoggerFactory.getLogger(RabbitConsumer::class.java)

    /**
     * Consumes until [stopped] is released, reconnecting after a broker failure. It catches everything on purpose: a
     * consumer that dies on the first unexpected exception is a worker that silently stops sending e-mail.
     */
    @Suppress("TooGenericExceptionCaught")
    fun run(stopped: CountDownLatch) {
        while (stopped.count > 0) {
            try {
                consume(stopped)
            } catch (e: Exception) {
                if (stopped.count == 0L) return
                log.error("consumer stopped, reconnecting in {} ms: {}", RECONNECT_DELAY_MILLIS, e.javaClass.simpleName)
                stopped.await(RECONNECT_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun consume(stopped: CountDownLatch) {
        connectionFactory(url).newConnection("order-api-worker").use { connection ->
            val channel = connection.createChannel()
            declareTopology(channel)
            // Moving a message (retry, dead letter) must be confirmed by the broker before the original is acked.
            channel.confirmSelect()
            // One unacknowledged message at a time: the retry delay then really throttles the queue.
            channel.basicQos(1)
            val handler = DeliveryHandler(processor, RabbitMover(channel), retryDelayMillis)
            val deliver =
                DeliverCallback { _, delivery ->
                    val tag = delivery.envelope.deliveryTag
                    val outcome =
                        handler.handle(
                            Delivered(
                                delivery.properties.messageId,
                                delivery.body,
                                retriesOf(delivery.properties),
                                delivery.properties,
                            ),
                        )
                    if (outcome == Outcome.ACK) channel.basicAck(tag, false) else channel.basicNack(tag, false, true)
                }
            channel.basicConsume(QUEUE_NAME, false, deliver, CancelCallback { })
            log.info("consuming {}", QUEUE_NAME)
            while (stopped.count > 0 && connection.isOpen) stopped.await(1, TimeUnit.SECONDS)
            if (stopped.count > 0) error("the broker closed the connection")
        }
    }
}
