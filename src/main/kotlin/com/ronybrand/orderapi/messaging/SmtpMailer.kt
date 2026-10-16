package com.ronybrand.orderapi.messaging

import com.ronybrand.orderapi.domain.EmailAddress
import jakarta.mail.Authenticator
import jakarta.mail.Message.RecipientType
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

private const val MAX_PORT = 65_535
private const val SMTP_TIMEOUT_MILLIS = 30_000

/** The connection to the mail server. The password never appears in [toString]. */
class SmtpSettings(
    val host: String,
    val port: Int,
    val user: String?,
    val password: String?,
    val from: String,
    /**
     * Makes STARTTLS mandatory: if the server does not offer it the mail is not sent, instead of going out (and the
     * credentials with it) in clear text. Production sets it.
     */
    val requireTls: Boolean,
) {
    override fun toString() = "SmtpSettings(host=$host, port=$port, from=$from, requireTls=$requireTls)"
}

/** Sends mail through an SMTP server with Jakarta Mail. */
class SmtpMailer(private val settings: SmtpSettings) : Mailer {
    init {
        require(settings.host.isNotBlank() && settings.port in 1..MAX_PORT) {
            "smtp host and a valid port are required"
        }
        require(EmailAddress.isValid(settings.from)) { "smtp sender must be a bare e-mail address" }
    }

    internal fun sessionProperties() =
        Properties().apply {
            put("mail.smtp.host", settings.host)
            put("mail.smtp.port", settings.port.toString())
            put("mail.smtp.auth", (!settings.user.isNullOrEmpty()).toString())
            // Use STARTTLS when the server offers it; refuse to go on without it when it is required.
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", settings.requireTls.toString())
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MILLIS.toString())
            put("mail.smtp.timeout", SMTP_TIMEOUT_MILLIS.toString())
            put("mail.smtp.writetimeout", SMTP_TIMEOUT_MILLIS.toString())
        }

    override fun send(
        to: String,
        subject: String,
        htmlBody: String,
    ) {
        // Header values with CR or LF are refused: they are how a header injection adds a Bcc.
        if (listOf(to, subject).any { '\r' in it || '\n' in it }) throw PermanentFailure("invalid header")
        val authenticator =
            settings.user?.takeIf { it.isNotEmpty() }?.let { user ->
                object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(user, settings.password.orEmpty())
                }
            }
        val session = Session.getInstance(sessionProperties(), authenticator)
        val message =
            MimeMessage(session).apply {
                setFrom(InternetAddress(settings.from))
                setRecipient(RecipientType.TO, InternetAddress(to, false))
                setSubject(subject, "UTF-8")
                setContent(htmlBody, "text/html; charset=UTF-8")
            }
        Transport.send(message)
    }
}
