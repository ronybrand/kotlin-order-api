package com.ronybrand.orderapi

import com.ronybrand.orderapi.persistence.Db
import com.ronybrand.orderapi.persistence.PostgresStore
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.atomic.AtomicInteger

/** One migrated database of the embedded PostgreSQL, with its pool and a store. Close it when the test is done. */
class TestDb(val pool: HikariDataSource, val database: Database, val store: PostgresStore, val jdbcUrl: String) :
    AutoCloseable {
    override fun close() = pool.close()
}

/**
 * A real PostgreSQL started once per JVM (the binaries come from Maven Central, no Docker needed) and one fresh,
 * migrated database per test class, so a test never sees another one's rows.
 */
object PostgresFixture {
    private val server: EmbeddedPostgres by lazy {
        EmbeddedPostgres.builder().start().also { started ->
            Runtime.getRuntime().addShutdownHook(Thread { started.close() })
        }
    }
    private val counter = AtomicInteger()

    fun newDatabase(): TestDb {
        val name = "orders_test_${counter.incrementAndGet()}"
        server.postgresDatabase.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        val url = server.getJdbcUrl("postgres", name)
        val pool = Db.pool(url, user = "postgres", password = "postgres", maxPoolSize = 8)
        Db.migrate(pool)
        val database = Db.connect(pool)
        return TestDb(pool, database, PostgresStore(database), url)
    }
}
