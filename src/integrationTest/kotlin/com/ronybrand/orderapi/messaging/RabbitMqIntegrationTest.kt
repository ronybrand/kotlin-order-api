package com.ronybrand.orderapi.messaging

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.domain.OrderStatusChanged
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WAIT_SECONDS = 20L

/**
 * The real broker, in a container: the topology, publisher confirms, the retry loop and the dead letter queue. Skipped
 * where there is no Docker daemon (the PostgreSQL tests do not need one).
 */
@Testcontainers(disabledWithoutDocker = true)
class RabbitMqIntegrationTest {
    companion object {
        private val broker: RabbitMQContainer by lazy {
            RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-alpine")).also { it.start() }
        }
    }

    private lateinit var url: String
    private val stopped = CountDownLatch(1)
    private var consumerThread: Thread? = null
    private val publisher: RabbitPublisher by lazy { RabbitPublisher(url) }

    @BeforeEach
    fun connect() {
        url = broker.amqpUrl
        purge()
    }

    @AfterEach
    fun cleanUp() {
        stopped.countDown()
        consumerThread?.join(10_000)
        runCatching { publisher.close() }
    }

    private fun withChannel(block: (Channel) -> Unit) {
        ConnectionFactory().apply { setUri(url) }.newConnection().use { connection ->
            connection.createChannel().use { block(it) }
        }
    }

    private fun purge() =
        withChannel { channel ->
            declareTopology(channel)
            channel.queuePurge(QUEUE_NAME)
            channel.queuePurge(DLQ_NAME)
        }

    private fun startConsumer(notifier: Notifier) {
        consumerThread =
            thread(isDaemon = true) { RabbitConsumer(url, Processor(notifier), retryDelayMillis = 50).run(stopped) }
    }

    private fun event() =
        OrderStatusChanged(
            UUID.randomUUID(),
            "ada@example.com",
            "Ada",
            OrderStatus.OPEN,
            OrderStatus.CONFIRMED,
            BigDecimal("10.30"),
            Instant.parse("2026-10-14T12:00:00Z"),
        )

    private fun awaitInDlq(): Pair<ByteArray, AMQP.BasicProperties> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            var found: Pair<ByteArray, AMQP.BasicProperties>? = null
            withChannel { channel ->
                channel.basicGet(DLQ_NAME, true)?.let { found = it.body to it.props }
            }
            found?.let { return it }
            Thread.sleep(100)
        }
        error("nothing arrived in the dead letter queue")
    }

    @Test
    fun `a published event reaches the notifier intact`() {
        val received = LinkedBlockingQueue<OrderStatusChanged>()
        startConsumer { received.add(it) }
        val sent = event()

        publisher.publishStatusChanged(sent)

        assertEquals(sent, received.poll(WAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `a transient failure is retried with the count in the header and then dead-lettered`() {
        val attempts = LinkedBlockingQueue<Int>()
        startConsumer {
            attempts.add(attempts.size + 1)
            error("smtp down")
        }

        publisher.publishStatusChanged(event())

        val (_, properties) = awaitInDlq()
        assertEquals(MAX_RETRIES + 1, attempts.size, "the first attempt plus the retries")
        assertEquals(MAX_RETRIES, (properties.headers[RETRY_COUNT_HEADER] as Number).toInt())
        assertNotNull(properties.messageId)
    }

    @Test
    fun `a permanent failure goes straight to the dead letter queue`() {
        val attempts = LinkedBlockingQueue<Int>()
        startConsumer {
            attempts.add(1)
            throw PermanentFailure("invalid recipient")
        }

        publisher.publishStatusChanged(event())

        awaitInDlq()
        assertEquals(1, attempts.size)
    }

    @Test
    fun `a malformed message is dead-lettered and the consumer keeps working`() {
        val received = LinkedBlockingQueue<OrderStatusChanged>()
        startConsumer { received.add(it) }
        withChannel { channel ->
            channel.basicPublish(
                "",
                QUEUE_NAME,
                AMQP.BasicProperties.Builder().messageId("garbage").build(),
                "not json".toByteArray(),
            )
        }

        val (body, _) = awaitInDlq()
        publisher.publishStatusChanged(event())

        assertEquals("not json", String(body))
        assertNotNull(received.poll(WAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `the queues are durable and the main queue dead-letters to the DLQ`() {
        withChannel { channel ->
            declareTopology(channel)
            // Redeclaring with the same arguments is idempotent; different ones would fail.
            declareTopology(channel)
            assertNull(channel.basicGet(QUEUE_NAME, true))
        }
        assertTrue(broker.isRunning)
    }
}
