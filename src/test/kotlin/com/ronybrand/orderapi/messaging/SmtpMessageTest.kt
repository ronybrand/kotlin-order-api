package com.ronybrand.orderapi.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmtpMessageTest {
    private fun config(
        host: String = "mail.internal",
        port: Int = 587,
        from: String = "orders@example.com",
        requireTls: Boolean = false,
    ) = SmtpSettings(host, port, user = null, password = null, from = from, requireTls = requireTls)

    @Test
    fun `the configuration is validated up front`() {
        SmtpMailer(config())

        assertFailsWith<IllegalArgumentException> { SmtpMailer(config(host = "")) }
        assertFailsWith<IllegalArgumentException> { SmtpMailer(config(port = 0)) }
        assertFailsWith<IllegalArgumentException> { SmtpMailer(config(port = 70_000)) }
        assertFailsWith<IllegalArgumentException> { SmtpMailer(config(from = "Orders <orders@example.com>")) }
        assertFailsWith<IllegalArgumentException> { SmtpMailer(config(from = "")) }
    }

    @Test
    fun `header values with a line break are refused because that is how a Bcc is injected`() {
        val mailer = SmtpMailer(config())

        assertFailsWith<PermanentFailure> { mailer.send("a@example.com\r\nBcc: x@y.com", "Subject", "<p>x</p>") }
        assertFailsWith<PermanentFailure> { mailer.send("a@example.com", "Subject\nBcc: x@y.com", "<p>x</p>") }
    }

    @Test
    fun `the session requires STARTTLS only when asked to`() {
        assertEquals("true", SmtpMailer(config(requireTls = true)).sessionProperties()["mail.smtp.starttls.required"])
        assertEquals("false", SmtpMailer(config(requireTls = false)).sessionProperties()["mail.smtp.starttls.required"])
        assertTrue(
            SmtpMailer(config()).sessionProperties().getValue("mail.smtp.connectiontimeout").toString().toInt() > 0,
        )
    }
}
