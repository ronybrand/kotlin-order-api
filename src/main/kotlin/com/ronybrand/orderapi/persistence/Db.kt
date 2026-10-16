package com.ronybrand.orderapi.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

private const val DEFAULT_POOL_SIZE = 10

/** Connection pool, schema migration and the Exposed handle, in the order the process needs them. */
object Db {
    /** A pool for a JDBC URL such as `jdbc:postgresql://host:5432/orders`. */
    fun pool(
        jdbcUrl: String,
        user: String? = null,
        password: String? = null,
        maxPoolSize: Int = DEFAULT_POOL_SIZE,
    ): HikariDataSource {
        val config =
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                user?.let { username = it }
                password?.let { this.password = it }
                maximumPoolSize = maxPoolSize
                // Every use case that needs atomicity opens its own transaction; a statement outside of one commits alone.
                isAutoCommit = false
                // A pool that hands out a broken connection turns one network blip into a failed request.
                connectionTestQuery = "SELECT 1"
            }
        return HikariDataSource(config)
    }

    /** Applies the migrations under `db/migration`; a database already at the latest version is left as it is. */
    fun migrate(dataSource: DataSource) {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun connect(dataSource: DataSource): Database = Database.connect(dataSource)
}
