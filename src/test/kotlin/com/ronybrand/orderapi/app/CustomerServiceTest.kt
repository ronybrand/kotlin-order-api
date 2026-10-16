package com.ronybrand.orderapi.app

import com.ronybrand.orderapi.NOW
import com.ronybrand.orderapi.assertCode
import com.ronybrand.orderapi.domain.Customer
import com.ronybrand.orderapi.domain.CustomerInput
import com.ronybrand.orderapi.domain.Order
import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

fun user(id: String) = Actor(id, setOf("user"))

val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

class CustomerServiceTest {
    private val store = MemStore()
    private val service = CustomerService(store, FIXED_CLOCK)

    private fun input(taxId: String) =
        CustomerInput(
            name = "Ada",
            taxId = taxId,
            passportNumber = null,
            email = "ada@example.com",
        )

    @Test
    fun `create stores the audit from the actor`() {
        val customer = service.create(user("admin-1"), input("AB123456"))

        assertEquals("admin-1", customer.createdBy)
        assertEquals(NOW, customer.createdAt)
        assertEquals(customer, service.get(customer.id))
    }

    @Test
    fun `the system actor is recorded when nobody is authenticated`() {
        assertEquals(SYSTEM_ACTOR, service.create(Actor.SYSTEM, input("AB123456")).createdBy)
    }

    @Test
    fun `create rejects a duplicate tax id even when written with other punctuation`() {
        service.create(user("a"), input("TAX-0001"))

        assertCode(Codes.DUPLICATE_TAX_ID) { service.create(user("a"), input("tax0001")) }
    }

    @Test
    fun `create rejects a duplicate passport number`() {
        service.create(user("a"), input("TAX-0001").copy(passportNumber = "AB123456"))

        assertCode(
            Codes.DUPLICATE_PASSPORT,
        ) { service.create(user("a"), input("TAX-0002").copy(passportNumber = "AB123456")) }
    }

    @Test
    fun `customers without a passport never conflict on it`() {
        service.create(user("a"), input("TAX-0003"))

        assertEquals("TAX0004", service.create(user("a"), input("TAX-0004")).taxId)
    }

    @Test
    fun `the conflict never echoes the value because it is personal data`() {
        service.create(user("a"), input("TAX-0001"))

        val error = assertCode(Codes.DUPLICATE_TAX_ID) { service.create(user("a"), input("TAX-0001")) }

        assertFalse(error.message.contains("TAX"))
        assertTrue(error.params.isEmpty())
    }

    @Test
    fun `invalid input is rejected before the store is touched`() {
        assertCode(Codes.INVALID_REQUEST) { service.create(user("a"), input("AB123456").copy(name = "")) }

        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `update keeps its own tax id without a conflict and bumps the version`() {
        val created = service.create(user("a"), input("TAX-0001"))

        val updated = service.update(user("editor"), created.id, input("TAX-0001").copy(name = "Renamed"))

        assertEquals("Renamed", updated.name)
        assertEquals("editor", updated.updatedBy)
        assertEquals(2L, updated.version)
    }

    @Test
    fun `update cannot take another customer's tax id`() {
        service.create(user("a"), input("TAX-0001"))
        val other = service.create(user("a"), input("TAX-0002"))

        assertCode(Codes.DUPLICATE_TAX_ID) { service.update(user("e"), other.id, input("TAX-0001")) }
    }

    @Test
    fun `update of an unknown customer is RESOURCE-NOT-FOUND-01`() {
        assertCode(Codes.CUSTOMER_NOT_FOUND) { service.update(user("e"), UUID.randomUUID(), input("TAX-0009")) }
    }

    @Test
    fun `get of an unknown customer is RESOURCE-NOT-FOUND-01`() {
        assertCode(Codes.CUSTOMER_NOT_FOUND) { service.get(UUID.randomUUID()) }
    }

    @Test
    fun `delete soft-deletes a customer without orders under a row lock in one transaction`() {
        val customer = service.create(user("a"), input("TAX-0001"))
        store.calls.clear()

        service.delete(user("admin"), customer.id)

        assertEquals(
            listOf("begin", "customer.lock-update", "order.has-active", "customer.soft-delete", "commit"),
            store.calls,
        )
        assertCode(Codes.CUSTOMER_NOT_FOUND) { service.get(customer.id) }
    }

    @Test
    fun `delete is blocked by an active order and rolls back`() {
        val customer = store.seedCustomer(seedCustomer())
        store.seedOrder(seedOrder(customer.id))

        assertCode(Codes.CUSTOMER_HAS_ORDERS) { service.delete(user("admin"), customer.id) }

        assertEquals("rollback", store.lastCall())
    }

    @Test
    fun `a soft-deleted order does not block the deletion`() {
        val customer = store.seedCustomer(seedCustomer())
        store.seedOrder(seedOrder(customer.id).copy(deletedAt = NOW, deletedBy = "x"))

        service.delete(user("admin"), customer.id)

        assertCode(Codes.CUSTOMER_NOT_FOUND) { service.get(customer.id) }
    }

    @Test
    fun `delete of an unknown customer is RESOURCE-NOT-FOUND-01`() {
        assertCode(Codes.CUSTOMER_NOT_FOUND) { service.delete(user("admin"), UUID.randomUUID()) }
    }

    @Test
    fun `a released tax id can be registered again`() {
        val customer = service.create(user("a"), input("TAX-0001"))
        service.delete(user("a"), customer.id)

        assertEquals("TAX0001", service.create(user("a"), input("TAX-0001")).taxId)
    }

    @Test
    fun `search validates the request and fills in the default size`() {
        assertFailsWith<AppException> { service.search(SearchRequest(page = -1, size = 10)) }
        assertEquals(DEFAULT_PAGE_SIZE, service.search(SearchRequest()).size)
        assertEquals(MAX_PAGE_SIZE, service.search(SearchRequest(size = MAX_PAGE_SIZE)).size)
    }

    private fun seedCustomer() =
        Customer(
            id = UUID.randomUUID(),
            name = "Ada",
            taxId = "TAX0001",
            passportNumber = null,
            email = "ada@example.com",
            version = 1,
            createdAt = NOW,
            updatedAt = NOW,
            createdBy = "seed",
            updatedBy = "seed",
        )

    private fun seedOrder(customerId: UUID) =
        Order(
            id = UUID.randomUUID(),
            customerId = customerId,
            status = OrderStatus.OPEN,
            items = emptyList(),
            version = 1,
            createdAt = NOW,
            updatedAt = NOW,
            createdBy = "seed",
            updatedBy = "seed",
        )
}
