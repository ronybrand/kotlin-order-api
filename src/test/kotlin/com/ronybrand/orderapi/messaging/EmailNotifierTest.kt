package com.ronybrand.orderapi.messaging

import com.ronybrand.orderapi.domain.OrderStatus
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailNotifierTest {
    private class RecordingMailer(var failure: Exception? = null) : Mailer {
        val sent = mutableListOf<Triple<String, String, String>>()

        override fun send(
            to: String,
            subject: String,
            htmlBody: String,
        ) {
            failure?.let { throw it }
            sent += Triple(to, subject, htmlBody)
        }
    }

    private val mailer = RecordingMailer()
    private val notifier = EmailNotifier(mailer)

    @Test
    fun `the e-mail names the order, the transition and the total`() {
        val event = sampleEvent().copy(newStatus = OrderStatus.CANCELED, totalAmount = BigDecimal("20.5"))

        notifier.notify(event)

        val (to, subject, body) = mailer.sent.single()
        assertEquals("ada@example.com", to)
        assertEquals("Order CANCELED", subject)
        assertTrue(body.contains(event.orderId.toString()))
        assertTrue(body.contains("OPEN") && body.contains("CANCELED"))
        assertTrue(body.contains("Total: 20.50"))
        assertTrue(body.contains("Hello, Ada."))
    }

    @Test
    fun `a customer name is escaped so it cannot inject markup`() {
        notifier.notify(sampleEvent().copy(customerName = "<script>alert('x')</script> & Co"))

        val body = mailer.sent.single().third
        assertFalse(body.contains("<script>"))
        assertTrue(body.contains("&lt;script&gt;"))
        assertTrue(body.contains("&amp; Co"))
    }

    @Test
    fun `a bad recipient is permanent because retrying cannot fix it`() {
        for (email in listOf("", "no-at-sign", "Ada <ada@example.com>", "a@example.com\r\nBcc: evil@example.com")) {
            assertFailsWith<PermanentFailure>(email) { notifier.notify(sampleEvent().copy(customerEmail = email)) }
        }
        assertTrue(mailer.sent.isEmpty())
    }

    @Test
    fun `a delivery failure is transient and keeps its cause`() {
        mailer.failure = java.io.IOException("connection reset")

        val error = assertFailsWith<Exception> { notifier.notify(sampleEvent()) }

        assertFalse(error is PermanentFailure)
        assertEquals("connection reset", error.cause?.message ?: error.message)
    }
}
