package com.ronybrand.orderapi.app

import com.ronybrand.orderapi.domain.Customer
import com.ronybrand.orderapi.domain.Order
import com.ronybrand.orderapi.domain.OrderStatusChanged
import java.time.Instant
import java.util.UUID

/**
 * The interfaces the use cases talk to. The infrastructure packages (postgres, messaging) implement them, and
 * they are declared here, where they are consumed. They are blocking on purpose: JDBC is, and the HTTP layer
 * runs the use cases on the IO dispatcher instead of pretending otherwise.
 *
 * Every read only sees active (non soft-deleted) rows, and a missing row is reported as RESOURCE-NOT-FOUND-01.
 */
interface CustomerRepository {
    fun get(id: UUID): Customer

    /**
     * Reads the customer and takes a row lock that excludes every other lock on it. Deleting a customer uses it so
     * the "has orders?" check and the soft delete cannot interleave with an order creation.
     */
    fun lockForUpdate(id: UUID): Customer

    /**
     * Reads the customer and takes a shared row lock: many orders can be created for one customer at once, but none
     * while that customer is being deleted (and vice versa).
     */
    fun lockForShare(id: UUID): Customer

    fun create(customer: Customer)

    /** Writes the customer if its version still matches the row (CONFLICT-00 otherwise) and returns it with the next version. */
    fun update(customer: Customer): Customer

    fun softDelete(
        id: UUID,
        actor: String,
        at: Instant,
    )

    /** True when another active customer has this tax id; [except] is the customer to ignore (itself on update). */
    fun taxIdTaken(
        taxId: String,
        except: UUID?,
    ): Boolean

    fun passportTaken(
        passport: String,
        except: UUID?,
    ): Boolean

    fun search(request: SearchRequest): Page<Customer>
}

/** Persists orders together with their items. */
interface OrderRepository {
    fun get(id: UUID): Order

    fun create(order: Order)

    /**
     * Writes the status and the items of an order that was read earlier. It checks and increments the version: a
     * concurrent change fails with CONFLICT-00 instead of being overwritten silently. Returns the order with the
     * next version.
     */
    fun save(order: Order): Order

    fun softDelete(
        id: UUID,
        actor: String,
        at: Instant,
    )

    /** True when the customer has any non soft-deleted order. */
    fun hasActive(customerId: UUID): Boolean

    fun search(request: SearchRequest): Page<Order>
}

/** The set of repositories bound to one connection or one transaction. */
data class Repositories(val customers: CustomerRepository, val orders: OrderRepository)

/** Gives the use cases their repositories, either directly or inside a transaction. */
interface Store {
    /** Repositories that run each statement on its own. */
    fun repos(): Repositories

    /** Runs [block] in a transaction: commits when it returns, rolls back when it throws. */
    fun <T> inTransaction(block: (Repositories) -> T): T
}

/**
 * Announces order events. Publishing happens after the database commit and is best effort: see
 * docs/adr/0002 for why this is not a transactional outbox.
 */
interface EventPublisher {
    fun publishStatusChanged(event: OrderStatusChanged)
}
