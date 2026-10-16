package com.ronybrand.orderapi.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class RabbitConnectionTest {
    @Test
    fun `a URL ending in a slash means the default vhost, as in every other client and in the compose file`() {
        // The Java client reads amqp://host/ as the empty vhost, which no broker has ("vhost  not found").
        assertEquals("/", connectionFactory("amqp://guest:guest@rabbitmq:5672/").virtualHost)
    }

    @Test
    fun `a URL without a path also uses the default vhost`() {
        assertEquals("/", connectionFactory("amqp://guest:guest@rabbitmq:5672").virtualHost)
    }

    @Test
    fun `an explicit vhost is kept`() {
        assertEquals("/", connectionFactory("amqp://guest:guest@rabbitmq:5672/%2F").virtualHost)
        assertEquals("orders", connectionFactory("amqp://guest:guest@rabbitmq:5672/orders").virtualHost)
    }

    @Test
    fun `credentials and the TLS scheme are read from the URL`() {
        val factory = connectionFactory("amqps://user:secret@broker.example:5671/%2F")

        assertEquals("broker.example", factory.host)
        assertEquals(5671, factory.port)
        assertEquals("user", factory.username)
        assertEquals(true, factory.isSSL)
    }

    @Test
    fun `the connection never recovers by itself, the consumer has its own reconnect policy`() {
        assertEquals(false, connectionFactory("amqp://h/").isAutomaticRecoveryEnabled)
    }
}
