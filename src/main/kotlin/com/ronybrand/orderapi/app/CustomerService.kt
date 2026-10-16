package com.ronybrand.orderapi.app

import com.ronybrand.orderapi.domain.Customer
import com.ronybrand.orderapi.domain.CustomerInput
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/**
 * The customer use cases. Authorization (who may call what) is decided by the HTTP layer; the rules here are
 * the ones that hold for every caller.
 */
class CustomerService(private val store: Store, private val clock: Clock = Clock.systemUTC()) {
    private val log = LoggerFactory.getLogger(CustomerService::class.java)

    /** Registers a customer, rejecting a duplicate tax id (CONFLICT-01) or passport (CONFLICT-02). */
    fun create(
        actor: Actor,
        input: CustomerInput,
    ): Customer {
        val customer = Customer.create(input, actor.id, clock.instant())
        val repo = store.repos().customers
        // The unique indexes are the real guarantee under concurrency: the check below only gives the common case a
        // readable error without a failed write, and the repository maps a violation to the same codes.
        ensureUnique(repo, customer, except = null)
        repo.create(customer)
        log.info("customer created: {} by {}", customer, actor.id)
        return customer
    }

    fun get(id: UUID): Customer = store.repos().customers.get(id)

    /** Replaces a customer's data, validating uniqueness against the other customers. */
    fun update(
        actor: Actor,
        id: UUID,
        input: CustomerInput,
    ): Customer {
        val repo = store.repos().customers
        val replaced = repo.get(id).replace(input, actor.id, clock.instant())
        ensureUnique(repo, replaced, except = id)
        val saved = repo.update(replaced)
        log.info("customer updated: {} by {}", saved, actor.id)
        return saved
    }

    /** Soft-deletes a customer that has no active orders (CONFLICT-03 otherwise). */
    fun delete(
        actor: Actor,
        id: UUID,
    ) {
        store.inTransaction { repos ->
            // FOR UPDATE first: an order being created for this customer holds the shared lock, so the check below sees
            // it, and one that starts afterwards waits for this transaction and then finds the customer gone.
            repos.customers.lockForUpdate(id)
            if (repos.orders.hasActive(id)) {
                throw AppException.conflict(
                    Codes.CUSTOMER_HAS_ORDERS,
                    "customer has orders and cannot be deleted",
                    mapOf("id" to id),
                )
            }
            repos.customers.softDelete(id, actor.id, clock.instant())
        }
        log.info("customer deleted: {} by {}", id, actor.id)
    }

    /** Filters, sorts and pages the active customers. */
    fun search(request: SearchRequest): Page<Customer> {
        val valid = request.withDefaults().also { it.validate() }
        return store.repos().customers.search(valid)
    }

    /** CONFLICT-01/02 without echoing the value: tax id and passport are personal data. */
    private fun ensureUnique(
        repo: CustomerRepository,
        customer: Customer,
        except: UUID?,
    ) {
        if (repo.taxIdTaken(customer.taxId, except)) {
            throw AppException.conflict(Codes.DUPLICATE_TAX_ID, "a customer with this tax_id already exists")
        }
        val passport = customer.passportNumber ?: return
        if (repo.passportTaken(passport, except)) {
            throw AppException.conflict(Codes.DUPLICATE_PASSPORT, "a customer with this passport_number already exists")
        }
    }
}
