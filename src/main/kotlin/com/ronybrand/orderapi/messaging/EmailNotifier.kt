package com.ronybrand.orderapi.messaging

import com.ronybrand.orderapi.domain.EmailAddress
import com.ronybrand.orderapi.domain.OrderStatusChanged
import java.math.RoundingMode
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val MONEY_SCALE = 2
private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

/** Sends one HTML e-mail. The SMTP implementation is [SmtpMailer]. */
interface Mailer {
    fun send(
        to: String,
        subject: String,
        htmlBody: String,
    )
}

/** Renders the status e-mail and hands it to a [Mailer]. */
class EmailNotifier(private val mailer: Mailer) : Notifier {
    /**
     * Validates the recipient (a bad one never becomes valid by retrying: [PermanentFailure]) and sends the mail. Any
     * other failure of the mailer is transient and reaches the processor unchanged, to be retried.
     */
    override fun notify(event: OrderStatusChanged) {
        if (!EmailAddress.isValid(event.customerEmail)) throw PermanentFailure("invalid recipient")
        mailer.send(event.customerEmail, "Order ${event.newStatus}", render(event))
    }

    // Every interpolated value is escaped for its HTML context, so a customer named "<script>..." cannot inject markup.
    private fun render(event: OrderStatusChanged): String {
        val total = event.totalAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString()
        return """
            <!doctype html>
            <html lang="en">
            <body style="font-family: sans-serif">
              <p>Hello, ${escape(event.customerName)}.</p>
              <p>Your order <strong>${event.orderId}</strong> changed from <strong>${event.oldStatus}</strong> to <strong>${event.newStatus}</strong>.</p>
              <p>Total: $total</p>
              <p>Updated at ${TIME_FORMAT.format(event.changedAt)}.</p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escape(text: String) =
        buildString {
            for (char in text) {
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(char)
                }
            }
        }
}
