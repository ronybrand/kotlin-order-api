package com.ronybrand.orderapi.persistence

import com.ronybrand.orderapi.app.Repositories
import com.ronybrand.orderapi.app.Store
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException

// Constraint names from V1__init.sql that map to a client-facing code.
private const val TAX_ID_INDEX = "customers_tax_id_active_uq"
private const val PASSPORT_INDEX = "customers_passport_active_uq"

// PostgreSQL error codes (SQLSTATE) the repositories translate.
private const val UNIQUE_VIOLATION = "23505"
private const val FOREIGN_KEY_VIOLATION = "23503"
private const val CHECK_VIOLATION = "23514"
private const val STRING_TOO_LONG = "22001"
private const val SERIALIZATION_FAILURE = "40001"
private const val DEADLOCK_DETECTED = "40P01"

/**
 * The [Store] backed by PostgreSQL through Exposed. A repository call made inside [inTransaction] joins that
 * transaction (Exposed reuses the one open on the thread); outside of one, each call runs in its own.
 */
class PostgresStore(private val database: Database) : Store {
    override fun repos() = Repositories(PgCustomers(database), PgOrders(database))

    override fun <T> inTransaction(block: (Repositories) -> T): T =
        translating {
            transaction(
                database,
            ) { block(repos()) }
        }
}

/** Runs a statement block in a transaction and reports database errors the client should understand. */
internal fun <T> Database.tx(block: JdbcTransaction.() -> T): T = translating { transaction(this) { block() } }

/**
 * Converts the database errors the client should understand into [AppException] values and leaves everything else
 * untouched (the HTTP layer reports those as INTERNAL-00 without their text).
 */
internal inline fun <T> translating(block: () -> T): T =
    try {
        block()
    } catch (e: ExposedSQLException) {
        throw translate(e)
    }

private fun translate(e: ExposedSQLException): Throwable {
    val cause = e.cause as? PSQLException
    return when (e.sqlState) {
        UNIQUE_VIOLATION -> {
            when (cause?.serverErrorMessage?.constraint) {
                TAX_ID_INDEX -> {
                    AppException.conflict(Codes.DUPLICATE_TAX_ID, "a customer with this tax_id already exists")
                }

                PASSPORT_INDEX -> {
                    AppException.conflict(
                        Codes.DUPLICATE_PASSPORT,
                        "a customer with this passport_number already exists",
                    )
                }

                else -> {
                    integrityError()
                }
            }
        }

        FOREIGN_KEY_VIOLATION, CHECK_VIOLATION, STRING_TOO_LONG -> {
            integrityError()
        }

        SERIALIZATION_FAILURE, DEADLOCK_DETECTED -> {
            concurrentModification()
        }

        else -> {
            e
        }
    }
}

private fun integrityError() =
    AppException.conflict(
        Codes.DATA_INTEGRITY,
        "the operation violates a data integrity rule",
    )

internal fun concurrentModification() =
    AppException.conflict(
        Codes.CONCURRENT_MODIFICATION,
        "the resource was modified by someone else, reload it and try again",
    )
