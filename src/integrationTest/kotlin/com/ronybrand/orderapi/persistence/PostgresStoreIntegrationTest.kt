package com.ronybrand.orderapi.persistence

import com.ronybrand.orderapi.PostgresFixture
import com.ronybrand.orderapi.TestDb
import com.ronybrand.orderapi.app.Actor
import com.ronybrand.orderapi.app.Condition
import com.ronybrand.orderapi.app.CustomerService
import com.ronybrand.orderapi.app.OrderService
import com.ronybrand.orderapi.app.SearchRequest
import com.ronybrand.orderapi.domain.Customer
import com.ronybrand.orderapi.domain.CustomerInput
import com.ronybrand.orderapi.domain.ItemInput
import com.ronybrand.orderapi.domain.Order
import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val NOW: Instant = Instant.parse("2026-10-14T12:00:00Z")
private val ADMIN = Actor("admin-1", setOf("user", "admin"))

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresStoreIntegrationTest {
    private lateinit var db: TestDb

    @BeforeAll
    fun start() {
        db = PostgresFixture.newDatabase()
    }

    @AfterAll
    fun stop() = db.close()

    private val store get() = db.store

    private fun newCustomer(
        taxId: String = "TAX-${UUID.randomUUID().toString().take(8)}",
        passport: String? = null,
    ): Customer {
        val customer =
            Customer.create(CustomerInput("Ana", taxId, passport, "ana@example.com"), "seed", NOW)
        store.repos().customers.create(customer)
        return customer
    }

    private fun newOrder(
        customerId: UUID,
        vararg items: ItemInput,
    ): Order {
        val order = Order.create(customerId, items.toList(), "seed", NOW)
        store.repos().orders.create(order)
        return order
    }

    private fun item(
        price: String,
        quantity: Int,
        description: String = "Widget",
    ) = ItemInput(description, BigDecimal(price), quantity)

    private fun codeOf(block: () -> Unit): String = assertFailsWith<AppException> { block() }.code

    // --- customers ------------------------------------------------------------------------------------

    @Test
    fun `a customer round-trips with its audit fields`() {
        val created = newCustomer(passport = "AB123456")

        val loaded = store.repos().customers.get(created.id)

        assertEquals(created, loaded)
    }

    @Test
    fun `an unknown customer is RESOURCE-NOT-FOUND-01`() {
        assertEquals(Codes.CUSTOMER_NOT_FOUND, codeOf { store.repos().customers.get(UUID.randomUUID()) })
    }

    @Test
    fun `uniqueness is enforced by the database even when the application check is bypassed`() {
        newCustomer(taxId = "UNIQ0001", passport = "UQ123456")

        assertEquals(Codes.DUPLICATE_TAX_ID, codeOf { newCustomer(taxId = "UNIQ0001") })
        assertEquals(Codes.DUPLICATE_PASSPORT, codeOf { newCustomer(taxId = "UNIQ0002", passport = "UQ123456") })
    }

    @Test
    fun `a soft-deleted customer is hidden and releases its tax id and passport`() {
        val customer = newCustomer(taxId = "GONE0001", passport = "ZZ123456")

        store.repos().customers.softDelete(customer.id, "admin", NOW)

        assertEquals(Codes.CUSTOMER_NOT_FOUND, codeOf { store.repos().customers.get(customer.id) })
        assertFalse(store.repos().customers.taxIdTaken("GONE0001", null))
        newCustomer(taxId = "GONE0001", passport = "ZZ123456")
        assertEquals(Codes.CUSTOMER_NOT_FOUND, codeOf { store.repos().customers.softDelete(customer.id, "x", NOW) })
    }

    @Test
    fun `taxIdTaken and passportTaken can ignore the customer itself`() {
        val customer = newCustomer(taxId = "SELF0001", passport = "SE123456")

        assertTrue(store.repos().customers.taxIdTaken("SELF0001", null))
        assertFalse(store.repos().customers.taxIdTaken("SELF0001", customer.id))
        assertTrue(store.repos().customers.passportTaken("SE123456", null))
        assertFalse(store.repos().customers.passportTaken("SE123456", customer.id))
    }

    @Test
    fun `update checks the version and returns the next one`() {
        val customer = newCustomer()
        val renamed = customer.replace(CustomerInput("Renamed", customer.taxId, null, "ana@example.com"), "editor", NOW)

        val saved = store.repos().customers.update(renamed)

        assertEquals(2L, saved.version)
        assertEquals(
            "Renamed",
            store
                .repos()
                .customers
                .get(customer.id)
                .name,
        )
        // The same stale copy again: the row already moved on.
        assertEquals(Codes.CONCURRENT_MODIFICATION, codeOf { store.repos().customers.update(renamed) })
    }

    // --- orders ---------------------------------------------------------------------------------------

    @Test
    fun `an order round-trips with its items in insertion order and an exact total`() {
        val customer = newCustomer()
        val order =
            newOrder(customer.id, item("0.10", 1, "first"), item("0.20", 1, "second"), item("19.99", 3, "third"))

        val loaded = store.repos().orders.get(order.id)

        assertEquals(order, loaded)
        assertEquals(listOf("first", "second", "third"), loaded.items.map { it.description })
        assertEquals(BigDecimal("60.27"), loaded.total)
    }

    @Test
    fun `an unknown order is RESOURCE-NOT-FOUND-02`() {
        assertEquals(Codes.ORDER_NOT_FOUND, codeOf { store.repos().orders.get(UUID.randomUUID()) })
    }

    @Test
    fun `save applies quantity changes and removals`() {
        val customer = newCustomer()
        val order = newOrder(customer.id, item("10", 1, "keep"), item("5", 2, "drop"))
        val changed = order.updateItemQuantity(order.items[0].id, 4, "u", NOW).removeItem(order.items[1].id, "u", NOW)

        val saved = store.repos().orders.save(changed)

        assertEquals(2L, saved.version)
        val loaded = store.repos().orders.get(order.id)
        assertEquals(listOf("keep"), loaded.items.map { it.description })
        assertEquals(4, loaded.items.single().quantity)
        assertEquals(BigDecimal("40.00"), loaded.total)
    }

    @Test
    fun `save mixes updated, untouched and new items in one go`() {
        val customer = newCustomer()
        val order = newOrder(customer.id, item("1", 1, "a"), item("2", 1, "b"), item("3", 1, "c"))
        val changed =
            order
                .updateItemQuantity(order.items[1].id, 7, "u", NOW)
                .addItem(item("4", 2, "d"), "u", NOW)

        store.repos().orders.save(changed)

        val loaded = store.repos().orders.get(order.id)
        assertEquals(listOf("a", "b", "c", "d"), loaded.items.map { it.description })
        assertEquals(listOf(1, 7, 1, 2), loaded.items.map { it.quantity })
        assertEquals(changed.items.map { it.id }, loaded.items.map { it.id })
    }

    @Test
    fun `save rejects a stale version and applies nothing`() {
        val customer = newCustomer()
        val order = newOrder(customer.id, item("1", 1))
        val first = order.addItem(item("2", 1), "u", NOW)
        val second = order.addItem(item("3", 1), "u", NOW)

        store.repos().orders.save(first)

        assertEquals(Codes.CONCURRENT_MODIFICATION, codeOf { store.repos().orders.save(second) })
        assertEquals(
            2,
            store
                .repos()
                .orders
                .get(order.id)
                .items.size,
        )
    }

    @Test
    fun `an order for a customer that does not exist is a data integrity conflict`() {
        val orphan = Order.create(UUID.randomUUID(), emptyList(), "seed", NOW)

        assertEquals(Codes.DATA_INTEGRITY, codeOf { store.repos().orders.create(orphan) })
    }

    @Test
    fun `hasActive ignores deleted orders`() {
        val customer = newCustomer()
        val order = newOrder(customer.id)

        val before = store.repos().orders.hasActive(customer.id)
        store.repos().orders.softDelete(order.id, "u", NOW)
        val after = store.repos().orders.hasActive(customer.id)

        assertTrue(before)
        assertFalse(after)
        assertEquals(Codes.ORDER_NOT_FOUND, codeOf { store.repos().orders.get(order.id) })
    }

    // --- search ---------------------------------------------------------------------------------------

    private fun orderSearch(
        field: String,
        condition: Condition,
        size: Int = 50,
    ) = store.repos().orders.search(SearchRequest(size = size, filters = mapOf(field to listOf(condition))))

    @Test
    fun `order search filters by status, sorts and pages, loading the items`() {
        val customer = newCustomer()
        newOrder(customer.id, item("1", 1))
        val second = newOrder(customer.id, item("2", 1))
        store.repos().orders.save(second.confirm("u", NOW).order)

        val confirmed = orderSearch("status", Condition("eq", "CONFIRMED"))
        val customerOrders = orderSearch("customer_id", Condition("eq", customer.id.toString()))
        val paged =
            store.repos().orders.search(
                SearchRequest(
                    size = 1,
                    page = 1,
                    sort = "-created_at",
                    filters =
                    mapOf("customer_id" to listOf(Condition("eq", customer.id.toString()))),
                ),
            )

        assertTrue(confirmed.items.any { it.id == second.id && it.items.size == 1 })
        assertTrue(confirmed.items.all { it.status == OrderStatus.CONFIRMED })
        assertEquals(2, customerOrders.total)
        assertEquals(2, paged.total)
        assertEquals(1, paged.items.size)
    }

    @Test
    fun `every operator runs against the real database`() {
        val customer = newCustomer(taxId = "OPS00001")
        val id = customer.id.toString()
        val search = { field: String, op: String, value: Any? ->
            store
                .repos()
                .customers
                .search(
                    SearchRequest(
                        size = 50,
                        filters = mapOf("id" to listOf(Condition("eq", id)), field to listOf(Condition(op, value))),
                    ),
                ).total
        }

        assertEquals(1, search("name", "eq", "Ana"))
        assertEquals(0, search("name", "neq", "Ana"))
        assertEquals(1, search("version", "gte", 1))
        assertEquals(0, search("version", "gt", 1))
        assertEquals(1, search("version", "lte", 1))
        assertEquals(0, search("version", "lt", 1))
        assertEquals(1, search("version", "between", listOf(1, 3)))
        assertEquals(1, search("name", "in", listOf("Ana", "Bea")))
        assertEquals(1, search("name", "lk", "n"))
        assertEquals(1, search("created_at", "gte", "2026-10-14"))
        assertEquals(0, search("created_at", "gt", "2026-10-15"))
        assertEquals(1, search("created_by", "eq", "seed"))
    }

    @Test
    fun `lk is a contains match and escapes wildcards`() {
        newCustomer(taxId = "LIKE0001")
        val hit =
            store.repos().customers.search(
                SearchRequest(
                    size = 10,
                    filters =
                    mapOf("name" to listOf(Condition("lk", "n"))),
                ),
            )
        val miss =
            store.repos().customers.search(
                SearchRequest(
                    size = 10,
                    filters =
                    mapOf("name" to listOf(Condition("lk", "%"))),
                ),
            )

        assertTrue(hit.total >= 1)
        assertEquals(0, miss.total)
    }

    @Test
    fun `sorting by every searchable field works`() {
        newCustomer()
        for (field in SearchEntity.customers.fields.keys) {
            store.repos().customers.search(SearchRequest(size = 5, sort = field))
            store.repos().customers.search(SearchRequest(size = 5, sort = "-$field"))
        }
        for (field in SearchEntity.orders.fields.keys) {
            store.repos().orders.search(SearchRequest(size = 5, sort = field))
        }
    }

    @Test
    fun `the search whitelist covers every column of the real schema except the soft delete markers`() {
        val notSearchable = setOf("deleted_at", "deleted_by")

        for (entity in listOf(SearchEntity.customers, SearchEntity.orders)) {
            val columns =
                db.pool.connection.use { connection ->
                    connection.metaData.getColumns(null, "public", entity.name, null).use { rs ->
                        buildSet { while (rs.next()) add(rs.getString("COLUMN_NAME")) }
                    }
                }
            assertEquals(
                columns - notSearchable,
                entity.fields.values
                    .map { it.column }
                    .toSet(),
                entity.name,
            )
        }
    }

    // --- transactions and locks -----------------------------------------------------------------------

    @Test
    fun `a transaction rolls back when the block throws`() {
        val customer = newCustomer()
        val before =
            store
                .repos()
                .orders
                .search(SearchRequest(size = 100))
                .total

        val code =
            codeOf {
                store.inTransaction { repos ->
                    repos.orders.create(Order.create(customer.id, emptyList(), "u", NOW))
                    throw AppException.validation("X", "boom")
                }
            }

        assertEquals("X", code)
        assertEquals(
            before,
            store
                .repos()
                .orders
                .search(SearchRequest(size = 100))
                .total,
        )
    }

    @Test
    fun `repository calls inside a transaction share it`() {
        val customer = newCustomer()

        store.inTransaction { repos ->
            val order = Order.create(customer.id, listOf(item("1", 1)), "u", NOW)
            repos.orders.create(order)
            // Visible to the same transaction before the commit.
            assertEquals(
                1,
                repos.orders
                    .get(order.id)
                    .items.size,
            )
        }
    }

    // The race the locking exists for: deleting a customer while an order is being created for it must leave
    // either (order exists, delete refused) or (customer deleted, order refused), never an order under a deleted customer.
    @Test
    fun `deleting a customer versus creating an order never leaves an orphan`() {
        val orders = OrderService(store, null)
        val customers = CustomerService(store)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                val customer = newCustomer()
                val create =
                    pool.submit<Throwable?> {
                        runCatching { orders.create(ADMIN, customer.id, emptyList()) }.exceptionOrNull()
                    }
                val delete =
                    pool.submit<Throwable?> {
                        runCatching {
                            customers.delete(
                                ADMIN,
                                customer.id,
                            )
                        }.exceptionOrNull()
                    }
                val createError = create.get(30, TimeUnit.SECONDS)
                val deleteError = delete.get(30, TimeUnit.SECONDS)

                assertFalse(
                    createError == null && deleteError == null,
                    "both the order was created and the customer deleted",
                )
                if (createError != null) assertTrue(createError is AppException)
                if (deleteError != null) assertTrue(deleteError is AppException)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `migrating twice is harmless and leaves one applied migration`() {
        Db.migrate(db.pool)
        Db.migrate(db.pool)

        val applied =
            db.pool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        assertEquals(1, applied)
    }
}
