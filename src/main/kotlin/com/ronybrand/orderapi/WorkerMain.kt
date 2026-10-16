package com.ronybrand.orderapi

import com.ronybrand.orderapi.config.Config
import com.ronybrand.orderapi.config.ConfigException
import com.ronybrand.orderapi.messaging.EmailNotifier
import com.ronybrand.orderapi.messaging.Processor
import com.ronybrand.orderapi.messaging.RabbitConsumer
import com.ronybrand.orderapi.messaging.SmtpMailer
import com.ronybrand.orderapi.messaging.SmtpSettings
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** The notification worker: consumes order.status.changed and sends the e-mail. */
object WorkerMain {
    @JvmStatic
    fun main(args: Array<String>) = run()

    private fun run() {
        val log = LoggerFactory.getLogger("com.ronybrand.orderapi.Worker")
        val config =
            try {
                Config.fromEnvironment().also { it.validateWorker() }
            } catch (e: ConfigException) {
                e.errors.forEach { log.error("configuration error: {}", it) }
                exitProcess(1)
            }

        val mailer =
            SmtpMailer(
                SmtpSettings(
                    host = requireNotNull(config.smtp.host),
                    port = config.smtp.port,
                    user = config.smtp.user,
                    password = config.smtp.password,
                    from = requireNotNull(config.smtp.from),
                    // In production a mail never goes out (with its credentials) in clear text.
                    requireTls = config.isProduction,
                ),
            )
        val consumer = RabbitConsumer(requireNotNull(config.brokerUrl), Processor(EmailNotifier(mailer)))

        val stopped = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { stopped.countDown() })
        log.info("worker started ({})", config.environment)
        consumer.run(stopped)
    }
}
