package com.ronybrand.orderapi.messaging

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.domain.OrderStatusChanged
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.ServerSocket
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The real SMTP exchange against an in-process server (GreenMail), no Docker needed. */
class SmtpMailerIntegrationTest {
    private lateinit var server: GreenMail
    private var port = 0

    @BeforeEach
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = GreenMail(ServerSetup(port, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)).also { it.start() }
    }

    @AfterEach
    fun stop() = server.stop()

    private fun mailer(requireTls: Boolean = false) =
        SmtpMailer(
            SmtpSettings(
                "127.0.0.1",
                port,
                user = null,
                password = null,
                from = "orders@example.com",
                requireTls = requireTls,
            ),
        )

    @Test
    fun `a mail is delivered with the sender, the recipient, the subject and the HTML body`() {
        mailer().send("ada@example.com", "Order CONFIRMED", "<p>Olá, Ada. Total: 10.30</p>")

        val message = server.receivedMessages.single()
        assertEquals("Order CONFIRMED", message.subject)
        assertEquals("orders@example.com", message.from.single().toString())
        assertEquals("ada@example.com", message.allRecipients.single().toString())
        assertTrue(message.contentType.startsWith("text/html"))
        assertTrue(String(message.inputStream.readAllBytes()).contains("Total: 10.30"))
    }

    @Test
    fun `the notifier and the mailer work together on a real exchange`() {
        EmailNotifier(mailer()).notify(sampleEvent())

        val message = server.receivedMessages.single()
        assertEquals("Order CONFIRMED", message.subject)
        assertTrue(String(message.inputStream.readAllBytes()).contains("Hello, Ada."))
    }

    @Test
    fun `when TLS is required and the server does not offer STARTTLS nothing is sent`() {
        assertFailsWith<Exception> { mailer(requireTls = true).send("ada@example.com", "Order OPEN", "<p>x</p>") }

        assertFalse(server.receivedMessages.isNotEmpty())
    }

    @Test
    fun `a server that is not there is a transient failure, not a permanent one`() {
        val unreachable = SmtpMailer(SmtpSettings("127.0.0.1", 1, null, null, "orders@example.com", requireTls = false))

        val error = assertFailsWith<Exception> { unreachable.send("ada@example.com", "Order OPEN", "<p>x</p>") }

        assertFalse(error is PermanentFailure)
    }

    private fun sampleEvent() =
        OrderStatusChanged(
            UUID.randomUUID(),
            "ada@example.com",
            "Ada",
            OrderStatus.OPEN,
            OrderStatus.CONFIRMED,
            BigDecimal("10.30"),
            Instant.parse("2026-10-14T12:00:00Z"),
        )
}
