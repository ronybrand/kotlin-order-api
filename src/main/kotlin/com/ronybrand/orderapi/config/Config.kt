package com.ronybrand.orderapi.config

import com.ronybrand.orderapi.http.HttpConfig
import java.net.URI
import java.net.URLDecoder

private const val DEFAULT_PORT = 8080
private const val DEFAULT_SMTP_PORT = 587
private const val DEFAULT_SHUTDOWN_SECONDS = 15
private const val DEFAULT_BODY_BYTES = 1 shl 20
private const val DEFAULT_RATE_PER_USER = 120
private const val DEFAULT_RATE_WRITES = 30
private const val DEFAULT_RATE_PER_IP = 600

enum class Environment { DEVELOPMENT, PRODUCTION }

/** Every problem found, reported at once: fixing a deployment one error per restart is miserable. */
class ConfigException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

/** Where the database is: a JDBC URL plus credentials. The password never appears in [toString]. */
data class DatabaseTarget(val jdbcUrl: String, val user: String? = null, val password: String? = null) {
    override fun toString() = "DatabaseTarget(jdbcUrl=$jdbcUrl)"

    companion object {
        /** Accepts `postgres://user:pass@host:5432/db?sslmode=disable` (the format of the other implementations) or JDBC. */
        fun parse(raw: String): DatabaseTarget {
            if (raw.startsWith("jdbc:postgresql:")) return DatabaseTarget(raw)
            val uri = runCatching { URI(raw) }.getOrNull()
            if (uri == null || uri.scheme !in setOf("postgres", "postgresql") || uri.host.isNullOrEmpty()) {
                throw ConfigException(listOf("DATABASE_URL must be a postgres:// or jdbc:postgresql: URL"))
            }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val credentials = uri.rawUserInfo?.split(":", limit = 2)
            return DatabaseTarget(
                jdbcUrl = "jdbc:postgresql://${uri.host}$port${uri.rawPath.orEmpty()}$query",
                user = credentials?.getOrNull(0)?.let { decode(it) },
                password = credentials?.getOrNull(1)?.let { decode(it) },
            )
        }

        private fun decode(part: String) = URLDecoder.decode(part.replace("+", "%2B"), Charsets.UTF_8)
    }
}

/** The SMTP server of the worker. The password never appears in [toString]. */
data class SmtpConfig(val host: String?, val port: Int, val user: String?, val password: String?, val from: String?) {
    override fun toString() = "SmtpConfig(host=$host, port=$port, from=$from)"
}

/** The whole configuration of the API and the worker, read from environment variables. */
class Config(
    val environment: Environment,
    val httpHost: String,
    val httpPort: Int,
    val database: DatabaseTarget,
    val jwtPublicKeyPath: String?,
    val jwtAudience: String?,
    val jwtIssuer: String?,
    val http: HttpConfig,
    val brokerUrl: String?,
    val smtp: SmtpConfig,
    val shutdownTimeoutSeconds: Int,
) {
    val isProduction: Boolean get() = environment == Environment.PRODUCTION

    // The broker URL carries a password, so nothing here is printed.
    override fun toString() = "Config(environment=$environment)"

    /** Checks what only the HTTP API needs. */
    fun validateApi() {
        val errors = mutableListOf<String>()
        if (jwtPublicKeyPath.isNullOrEmpty()) errors += "JWT_PUBLIC_KEY_PATH is required"
        if (jwtAudience.isNullOrEmpty() ||
            jwtIssuer.isNullOrEmpty()
        ) {
            errors += "JWT_AUDIENCE and JWT_ISSUER are required"
        }
        if (http.rateLimitPerUser < 1 || http.rateLimitWrites < 1 || http.rateLimitPerIp < 1) {
            errors += "rate limits must be positive"
        }
        if (errors.isNotEmpty()) throw ConfigException(errors)
    }

    /** Checks what only the notification worker needs. */
    fun validateWorker() {
        val errors = mutableListOf<String>()
        if (brokerUrl.isNullOrEmpty()) errors += "BROKER_URL is required"
        if (smtp.host.isNullOrEmpty() || smtp.from.isNullOrEmpty()) errors += "SMTP_HOST and SMTP_FROM are required"
        if (errors.isNotEmpty()) throw ConfigException(errors)
    }

    companion object {
        /** Reads the process environment. */
        fun fromEnvironment(): Config = load(System::getenv)

        fun load(lookup: (String) -> String?): Config {
            val reader = Reader(lookup)
            val environment = reader.environment()
            val (host, port) = reader.address("HTTP_ADDR", DEFAULT_PORT)
            val database = reader.database()
            val config =
                Config(
                    environment = environment,
                    httpHost = host,
                    httpPort = port,
                    database = database,
                    jwtPublicKeyPath = reader.str("JWT_PUBLIC_KEY_PATH"),
                    jwtAudience = reader.str("JWT_AUDIENCE"),
                    jwtIssuer = reader.str("JWT_ISSUER"),
                    http =
                    HttpConfig(
                        corsOrigins = reader.list("CORS_ORIGINS"),
                        maxBodyBytes = reader.integer("MAX_BODY_BYTES", DEFAULT_BODY_BYTES).toLong(),
                        rateLimitPerUser = reader.integer("RATE_LIMIT_PER_USER", DEFAULT_RATE_PER_USER),
                        rateLimitWrites = reader.integer("RATE_LIMIT_WRITES", DEFAULT_RATE_WRITES),
                        rateLimitPerIp = reader.integer("RATE_LIMIT_PER_IP", DEFAULT_RATE_PER_IP),
                    ),
                    brokerUrl = reader.str("BROKER_URL"),
                    smtp =
                    SmtpConfig(
                        host = reader.str("SMTP_HOST"),
                        port = reader.integer("SMTP_PORT", DEFAULT_SMTP_PORT),
                        user = reader.str("SMTP_USER"),
                        password = reader.str("SMTP_PASSWORD"),
                        from = reader.str("SMTP_FROM"),
                    ),
                    shutdownTimeoutSeconds = reader.integer("SHUTDOWN_TIMEOUT_SECONDS", DEFAULT_SHUTDOWN_SECONDS),
                )
            val errors = reader.errors + problemsOf(config, reader.hasDatabase)
            if (errors.isNotEmpty()) throw ConfigException(errors)
            return config
        }

        /** What both processes need; [validateApi] and [validateWorker] add the rest. */
        private fun problemsOf(
            config: Config,
            hasDatabase: Boolean,
        ): List<String> {
            val errors = mutableListOf<String>()
            if (!hasDatabase) errors += "DATABASE_URL is required"
            if (config.http.maxBodyBytes < 1) errors += "MAX_BODY_BYTES must be positive"
            if (config.isProduction) errors += productionProblems(config)
            return errors
        }

        private fun productionProblems(config: Config): List<String> {
            val errors = mutableListOf<String>()
            if (config.jwtAudience.isNullOrEmpty() || config.jwtIssuer.isNullOrEmpty()) {
                errors += "JWT_AUDIENCE and JWT_ISSUER are required in production"
            }
            if (config.http.corsOrigins.isEmpty()) errors += "CORS_ORIGINS must list the allowed origins in production"
            if ("*" in config.http.corsOrigins) errors += "CORS_ORIGINS must not contain * in production"
            val broker = config.brokerUrl
            if (!broker.isNullOrEmpty() && runCatching { URI(broker).scheme }.getOrNull() != "amqps") {
                errors += "BROKER_URL must use amqps:// in production"
            }
            return errors
        }
    }
}

private class Reader(private val lookup: (String) -> String?) {
    val errors = mutableListOf<String>()
    var hasDatabase = false
        private set

    fun str(key: String): String? = lookup(key)?.trim()?.takeIf { it.isNotEmpty() }

    fun integer(
        key: String,
        fallback: Int,
    ): Int {
        val raw = str(key) ?: return fallback
        return raw.toIntOrNull() ?: fallback.also { errors += "$key must be an integer" }
    }

    fun list(key: String): List<String> = str(key)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun environment(): Environment =
        when (val raw = str("APP_ENV")?.lowercase()) {
            null, "development" -> Environment.DEVELOPMENT

            "production" -> Environment.PRODUCTION

            else -> Environment.DEVELOPMENT.also {
                errors +=
                    "APP_ENV must be \"development\" or \"production\" (got \"$raw\")"
            }
        }

    /** `:8080` or `0.0.0.0:8080`. */
    fun address(
        key: String,
        defaultPort: Int,
    ): Pair<String, Int> {
        val raw = str(key) ?: return "0.0.0.0" to defaultPort
        val colon = raw.lastIndexOf(':')
        val host = raw.substring(0, maxOf(colon, 0)).ifEmpty { "0.0.0.0" }
        val port = raw.substring(colon + 1).toIntOrNull()
        if (port == null) errors += "$key must look like :8080 or host:8080"
        return host to (port ?: defaultPort)
    }

    fun database(): DatabaseTarget {
        val raw = str("DATABASE_URL")
        hasDatabase = raw != null
        if (raw == null) return DatabaseTarget("")
        return try {
            DatabaseTarget.parse(raw)
        } catch (e: ConfigException) {
            errors += e.errors
            DatabaseTarget("")
        }
    }
}
