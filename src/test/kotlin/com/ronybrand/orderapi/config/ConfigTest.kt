package com.ronybrand.orderapi.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTest {
    private val development =
        mapOf(
            "DATABASE_URL" to "postgres://orders:secret@db.internal:5433/orders?sslmode=disable",
            "JWT_PUBLIC_KEY_PATH" to "/keys/jwt_public.pem",
            "JWT_AUDIENCE" to "order-api",
            "JWT_ISSUER" to "https://idp.example",
        )

    private val production =
        development +
            mapOf(
                "APP_ENV" to "production",
                "CORS_ORIGINS" to "https://app.example.com",
            )

    private fun load(env: Map<String, String>) = Config.load { env[it] }

    private fun errorsOf(env: Map<String, String>): List<String> = assertFailsWith<ConfigException> { load(env) }.errors

    @Test
    fun `the defaults are the ones of the other implementations`() {
        val config = load(development)

        assertEquals(Environment.DEVELOPMENT, config.environment)
        assertEquals(8080, config.httpPort)
        assertEquals(1L shl 20, config.http.maxBodyBytes)
        assertEquals(120, config.http.rateLimitPerUser)
        assertEquals(30, config.http.rateLimitWrites)
        assertEquals(600, config.http.rateLimitPerIp)
        assertEquals(15, config.shutdownTimeoutSeconds)
        assertTrue(config.http.corsOrigins.isEmpty())
        assertNull(config.brokerUrl)
        assertEquals(587, config.smtp.port)
    }

    @Test
    fun `values are read, trimmed and lists are split`() {
        val config =
            load(
                development +
                    mapOf(
                        "HTTP_ADDR" to " :9090 ",
                        "CORS_ORIGINS" to "https://a.example, https://b.example ,",
                        "MAX_BODY_BYTES" to "2048",
                        "RATE_LIMIT_PER_USER" to "7",
                    ),
            )

        assertEquals(9090, config.httpPort)
        assertEquals(listOf("https://a.example", "https://b.example"), config.http.corsOrigins)
        assertEquals(2048L, config.http.maxBodyBytes)
        assertEquals(7, config.http.rateLimitPerUser)
    }

    @Test
    fun `the database URL may be a postgres URL or already JDBC`() {
        val postgres = load(development).database
        val jdbc = load(development + ("DATABASE_URL" to "jdbc:postgresql://localhost/orders")).database

        assertEquals("jdbc:postgresql://db.internal:5433/orders?sslmode=disable", postgres.jdbcUrl)
        assertEquals("orders", postgres.user)
        assertEquals("secret", postgres.password)
        assertEquals("jdbc:postgresql://localhost/orders", jdbc.jdbcUrl)
        assertNull(jdbc.user)
    }

    @Test
    fun `a postgres URL without a port or credentials still converts`() {
        val target = DatabaseTarget.parse("postgresql://localhost/orders")

        assertEquals("jdbc:postgresql://localhost/orders", target.jdbcUrl)
        assertNull(target.user)
        assertNull(target.password)
    }

    @Test
    fun `credentials with escaped characters are decoded`() {
        val target = DatabaseTarget.parse("postgres://us%40er:p%2Fss@h:5432/db")

        assertEquals("us@er", target.user)
        assertEquals("p/ss", target.password)
    }

    @Test
    fun `the password never shows up in toString`() {
        val config = load(development)

        assertFalse(config.database.toString().contains("secret"))
        assertFalse(config.toString().contains("secret"))
    }

    @Test
    fun `the database URL is required and must be understood`() {
        assertTrue(errorsOf(emptyMap()).any { "DATABASE_URL is required" in it })
        assertTrue(errorsOf(development + ("DATABASE_URL" to "mysql://x/y")).any { "DATABASE_URL" in it })
    }

    @Test
    fun `an unknown environment and bad numbers are reported together`() {
        val errors =
            errorsOf(development + mapOf("APP_ENV" to "staging", "MAX_BODY_BYTES" to "lots", "SMTP_PORT" to "x"))

        assertTrue(errors.any { "APP_ENV" in it })
        assertTrue(errors.any { "MAX_BODY_BYTES must be an integer" in it })
        assertTrue(errors.any { "SMTP_PORT must be an integer" in it })
    }

    @Test
    fun `production accepts a safe configuration`() {
        val config = load(production)

        assertTrue(config.isProduction)
    }

    @Test
    fun `production refuses to start with an unsafe combination`() {
        assertTrue(errorsOf(production - "JWT_AUDIENCE").any { "JWT_AUDIENCE and JWT_ISSUER" in it })
        assertTrue(errorsOf(production - "JWT_ISSUER").any { "JWT_AUDIENCE and JWT_ISSUER" in it })
        assertTrue(errorsOf(production - "CORS_ORIGINS").any { "CORS_ORIGINS must list" in it })
        assertTrue(errorsOf(production + ("CORS_ORIGINS" to "*")).any { "must not contain *" in it })
        assertTrue(errorsOf(production + ("BROKER_URL" to "amqp://guest:guest@rabbit/")).any { "amqps://" in it })
        assertEquals(Environment.PRODUCTION, load(production + ("BROKER_URL" to "amqps://u:p@rabbit/")).environment)
    }

    @Test
    fun `development tolerates what production refuses`() {
        val config = load(development + ("CORS_ORIGINS" to "*") + ("BROKER_URL" to "amqp://guest:guest@rabbit/"))

        assertEquals(Environment.DEVELOPMENT, config.environment)
    }

    @Test
    fun `the API needs a key, an audience, an issuer and positive limits`() {
        load(development).validateApi()

        assertFailsWith<ConfigException> { load(development - "JWT_PUBLIC_KEY_PATH").validateApi() }
        assertFailsWith<ConfigException> { load(development - "JWT_AUDIENCE").validateApi() }
        assertFailsWith<ConfigException> { load(development + ("RATE_LIMIT_WRITES" to "0")).validateApi() }
    }

    @Test
    fun `the worker needs a broker and an SMTP server`() {
        val worker =
            development +
                mapOf(
                    "BROKER_URL" to "amqp://guest:guest@rabbit/",
                    "SMTP_HOST" to "mailpit",
                    "SMTP_FROM" to "orders@example.com",
                )

        load(worker).validateWorker()

        assertFailsWith<ConfigException> { load(development).validateWorker() }
        assertFailsWith<ConfigException> { load(worker - "SMTP_FROM").validateWorker() }
    }

    @Test
    fun `an invalid body limit is refused`() {
        assertTrue(errorsOf(development + ("MAX_BODY_BYTES" to "0")).any { "MAX_BODY_BYTES must be positive" in it })
    }
}
