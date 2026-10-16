package com.ronybrand.orderapi.app

import com.ronybrand.orderapi.domain.Customer
import com.ronybrand.orderapi.domain.Order
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MemData(
    val customers: MutableMap<UUID, Customer> = mutableMapOf(),
    val orders: MutableMap<UUID, Order> = mutableMapOf(),
) {
    fun copy() = MemData(customers.toMutableMap(), orders.toMutableMap())
}

/**
 * An in-memory [Store] for the service and HTTP tests. A transaction works on a copy of the data and swaps it in on
 * commit (so a failed transaction leaves nothing behind), and only one transaction runs at a time, which is what the
 * row locks of the real database guarantee for the code under test.
 */
class MemStore : Store {
    private val lock = ReentrantLock()
    var data = MemData()

    /** The ordered log of repository calls: it proves locks are taken before the writes they protect. */
    val calls = mutableListOf<String>()

    /** Runs once, just before the next order save, to simulate a concurrent writer. */
    var beforeOrderSave: ((MemData) -> Unit)? = null

    fun lastCall() = calls.last()

    override fun repos() = Repositories(MemCustomers(this, null), MemOrders(this, null))

    override fun <T> inTransaction(block: (Repositories) -> T): T =
        lock.withLock {
            val work = data.copy()
            calls += "begin"
            val result =
                try {
                    block(Repositories(MemCustomers(this, work), MemOrders(this, work)))
                } catch (e: Throwable) {
                    calls += "rollback"
                    throw e
                }
            data = work
            calls += "commit"
            result
        }

    fun seedCustomer(customer: Customer): Customer {
        data.customers[customer.id] = customer
        return customer
    }

    fun seedOrder(order: Order): Order {
        data.orders[order.id] = order
        return order
    }
}

private class MemCustomers(private val store: MemStore, private val work: MemData?) : CustomerRepository {
    private val data get() = work ?: store.data

    private fun notFound(id: UUID) =
        AppException.notFound(
            Codes.CUSTOMER_NOT_FOUND,
            "customer not found",
            mapOf("id" to id),
        )

    override fun get(id: UUID): Customer = data.customers[id]?.takeUnless { it.isDeleted } ?: throw notFound(id)

    override fun lockForUpdate(id: UUID): Customer {
        store.calls += "customer.lock-update"
        return get(id)
    }

    override fun lockForShare(id: UUID): Customer {
        store.calls += "customer.lock-share"
        return get(id)
    }

    override fun create(customer: Customer) {
        store.calls += "customer.create"
        data.customers[customer.id] = customer
    }

    override fun update(customer: Customer): Customer {
        store.calls += "customer.update"
        val stored = get(customer.id)
        if (stored.version != customer.version) {
            throw AppException.conflict(Codes.CONCURRENT_MODIFICATION, "the resource was modified by someone else")
        }
        return customer.copy(version = customer.version + 1).also { data.customers[it.id] = it }
    }

    override fun softDelete(
        id: UUID,
        actor: String,
        at: Instant,
    ) {
        store.calls += "customer.soft-delete"
        val stored = get(id)
        data.customers[id] =
            stored.copy(
                deletedAt = at,
                deletedBy = actor,
                updatedAt = at,
                updatedBy = actor,
                version =
                stored.version + 1,
            )
    }

    override fun taxIdTaken(
        taxId: String,
        except: UUID?,
    ) = data.customers.values.any { !it.isDeleted && it.taxId == taxId && it.id != except }

    override fun passportTaken(
        passport: String,
        except: UUID?,
    ) = data.customers.values.any { !it.isDeleted && it.passportNumber == passport && it.id != except }

    override fun search(request: SearchRequest): Page<Customer> {
        val all =
            data.customers.values
                .filter { !it.isDeleted }
                .sortedByDescending { it.createdAt }
        return Page(all.drop(request.offset).take(request.size), all.size, request.page, request.size)
    }
}

private class MemOrders(private val store: MemStore, private val work: MemData?) : OrderRepository {
    private val data get() = work ?: store.data

    private fun notFound(id: UUID) = AppException.notFound(Codes.ORDER_NOT_FOUND, "order not found", mapOf("id" to id))

    override fun get(id: UUID): Order = data.orders[id]?.takeUnless { it.isDeleted } ?: throw notFound(id)

    override fun create(order: Order) {
        store.calls += "order.create"
        data.orders[order.id] = order
    }

    override fun save(order: Order): Order {
        store.calls += "order.save"
        store.beforeOrderSave?.let {
            store.beforeOrderSave = null
            it(data)
        }
        val stored = get(order.id)
        if (stored.version != order.version) {
            throw AppException.conflict(Codes.CONCURRENT_MODIFICATION, "the resource was modified by someone else")
        }
        return order.copy(version = order.version + 1).also { data.orders[it.id] = it }
    }

    override fun softDelete(
        id: UUID,
        actor: String,
        at: Instant,
    ) {
        store.calls += "order.soft-delete"
        val stored = get(id)
        data.orders[id] =
            stored.copy(
                deletedAt = at,
                deletedBy = actor,
                updatedAt = at,
                updatedBy = actor,
                version =
                stored.version + 1,
            )
    }

    override fun hasActive(customerId: UUID): Boolean {
        store.calls += "order.has-active"
        return data.orders.values.any { it.customerId == customerId && !it.isDeleted }
    }

    override fun search(request: SearchRequest): Page<Order> {
        val all =
            data.orders.values
                .filter { !it.isDeleted }
                .sortedByDescending { it.createdAt }
        return Page(all.drop(request.offset).take(request.size), all.size, request.page, request.size)
    }
}
