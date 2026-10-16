package com.ronybrand.orderapi

import com.ronybrand.orderapi.app.CustomerService
import com.ronybrand.orderapi.app.OrderService
import com.ronybrand.orderapi.auth.JwtVerifier
import com.ronybrand.orderapi.config.Config
import com.ronybrand.orderapi.config.ConfigException
import com.ronybrand.orderapi.http.ApiDependencies
import com.ronybrand.orderapi.http.TokenVerifier
import com.ronybrand.orderapi.http.orderApi
import com.ronybrand.orderapi.messaging.RabbitPublisher
import com.ronybrand.orderapi.persistence.Db
import com.ronybrand.orderapi.persistence.PostgresStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess

private const val HEALTH_TIMEOUT_SECONDS = 2
private const val SHUTDOWN_GRACE_MILLIS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L

/** The API process: configuration, database, migrations, the use cases and the HTTP server, in that order. */
object ApiMain {
    @JvmStatic
    fun main(args: Array<String>) = run()

    private fun run() {
        val log = LoggerFactory.getLogger("com.ronybrand.orderapi.Api")
        val config =
            try {
                Config.fromEnvironment().also { it.validateApi() }
            } catch (e: ConfigException) {
                // Refusing to start beats running with a quietly insecure configuration.
                e.errors.forEach { log.error("configuration error: {}", it) }
                exitProcess(1)
            }

        val pool = Db.pool(config.database.jdbcUrl, config.database.user, config.database.password)
        Db.migrate(pool)
        val store = PostgresStore(Db.connect(pool))

        val verifier =
            JwtVerifier.fromPem(
                File(requireNotNull(config.jwtPublicKeyPath)).readText(),
                config.jwtAudience!!,
                config.jwtIssuer!!,
            )
        // Without a broker the API still works; it just does not announce status changes.
        val publisher = config.brokerUrl?.let { RabbitPublisher(it) }

        val dependencies =
            ApiDependencies(
                customers = CustomerService(store),
                orders = OrderService(store, publisher),
                verifier = TokenVerifier(verifier::verify),
                ping = { check(pool.connection.use { it.isValid(HEALTH_TIMEOUT_SECONDS) }) { "database unavailable" } },
                config = config.http,
            )

        val server = embeddedServer(Netty, port = config.httpPort, host = config.httpHost) { orderApi(dependencies) }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                log.info("shutting down")
                server.stop(SHUTDOWN_GRACE_MILLIS, config.shutdownTimeoutSeconds * MILLIS_PER_SECOND)
                publisher?.close()
                pool.close()
            },
        )
        log.info("listening on {}:{} ({})", config.httpHost, config.httpPort, config.environment)
        server.start(wait = true)
    }
}
