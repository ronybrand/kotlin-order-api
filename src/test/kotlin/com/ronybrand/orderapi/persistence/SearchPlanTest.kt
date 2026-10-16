package com.ronybrand.orderapi.persistence

import com.ronybrand.orderapi.app.Condition
import com.ronybrand.orderapi.app.SearchRequest
import com.ronybrand.orderapi.assertCode
import com.ronybrand.orderapi.error.Codes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchPlanTest {
    private fun filters(
        field: String,
        vararg conditions: Condition,
    ) = SearchRequest(filters = mapOf(field to conditions.toList()))

    private fun single(
        entity: SearchEntity,
        request: SearchRequest,
    ): Predicate = entity.plan(request).predicates.single()

    @Test
    fun `no filter and no sort means no predicates and the default order`() {
        val plan = SearchEntity.customers.plan(SearchRequest())

        assertTrue(plan.predicates.isEmpty())
        assertEquals(SortSpec(SearchEntity.customers.fields.getValue("created_at"), descending = true), plan.sort)
    }

    @Test
    fun `the default operator is eq`() {
        val predicate = single(SearchEntity.customers, filters("name", Condition(value = "Ana")))

        assertEquals(Operator.EQ, predicate.operator)
        assertEquals(listOf<Any>("Ana"), predicate.values)
    }

    @Test
    fun `operators are case insensitive`() {
        assertEquals(Operator.NEQ, single(SearchEntity.orders, filters("status", Condition("NEQ", "OPEN"))).operator)
    }

    @Test
    fun `integers are coerced from numbers and numeric strings`() {
        for (value in listOf<Any>(2L, 2, BigDecimal("2"), BigDecimal("2.0"), 2.0, "2")) {
            val predicate = single(SearchEntity.orders, filters("version", Condition("gte", value)))
            assertEquals(listOf<Any>(2L), predicate.values, "value $value")
        }
    }

    @Test
    fun `uuids are coerced and must be canonical`() {
        val id = UUID.randomUUID()

        assertEquals(
            listOf<Any>(id),
            single(SearchEntity.orders, filters("customer_id", Condition("in", listOf(id.toString())))).values,
        )
        assertCode(Codes.INVALID_FILTER) { SearchEntity.customers.plan(filters("id", Condition(value = "not-a-uuid"))) }
        assertCode(Codes.INVALID_FILTER) { SearchEntity.customers.plan(filters("id", Condition(value = "1-1-1-1-1"))) }
    }

    @Test
    fun `timestamps accept RFC 3339 and plain dates`() {
        val withTime = single(SearchEntity.customers, filters("created_at", Condition("gt", "2026-10-14T12:00:00Z")))
        val dateOnly = single(SearchEntity.customers, filters("created_at", Condition("gt", "2026-10-14")))

        assertEquals(listOf<Any>(Instant.parse("2026-10-14T12:00:00Z")), withTime.values)
        assertEquals(listOf<Any>(Instant.parse("2026-10-14T00:00:00Z")), dateOnly.values)
    }

    @Test
    fun `between takes exactly two values and in takes a list`() {
        val between = single(SearchEntity.customers, filters("version", Condition("between", listOf(1, 5))))
        val inList = single(SearchEntity.customers, filters("name", Condition("in", listOf("a", "b"))))

        assertEquals(listOf<Any>(1L, 5L), between.values)
        assertEquals(listOf<Any>("a", "b"), inList.values)
    }

    @Test
    fun `lk becomes an escaped contains pattern`() {
        val predicate = single(SearchEntity.customers, filters("name", Condition("lk", "50%_\\")))

        assertEquals(Operator.LIKE, predicate.operator)
        assertEquals(listOf<Any>("%50\\%\\_\\\\%"), predicate.values)
    }

    @Test
    fun `unknown filter fields are ignored`() {
        assertTrue(
            SearchEntity.customers
                .plan(filters("nope", Condition(value = "x")))
                .predicates
                .isEmpty(),
        )
    }

    @Test
    fun `the soft delete markers are not searchable`() {
        assertTrue(
            SearchEntity.customers
                .plan(filters("deleted_at", Condition(value = "2026-01-01")))
                .predicates
                .isEmpty(),
        )
    }

    @Test
    fun `invalid filters are VALIDATION-05`() {
        val customers =
            mapOf(
                "string where int expected" to filters("version", Condition(value = "abc")),
                "fractional int" to filters("version", Condition(value = 1.5)),
                "int beyond the long range" to filters("version", Condition(value = BigDecimal("1e30"))),
                "bad uuid" to filters("id", Condition(value = "not-a-uuid")),
                "bad timestamp" to filters("created_at", Condition("gt", "yesterday")),
                "between with three values" to filters("version", Condition("between", listOf(1, 2, 3))),
                "between with a scalar" to filters("version", Condition("between", 1)),
                "in with a scalar" to filters("name", Condition("in", "Ana")),
                "in with no values" to filters("name", Condition("in", emptyList<Any>())),
                "lk on a non text field" to filters("version", Condition("lk", "1")),
                "lk with a number" to filters("name", Condition("lk", 1)),
                "unknown operator" to filters("name", Condition("regex", ".*")),
                "a number where a string is expected" to filters("name", Condition(value = 5)),
                "sql in the value is only data" to filters("version", Condition(value = "1; DROP TABLE customers")),
            )
        val orders =
            mapOf(
                "unknown enum value" to filters("status", Condition(value = "SHIPPED")),
                "ordering operator on enum" to filters("status", Condition("gt", "OPEN")),
                "between on enum" to filters("status", Condition("between", listOf("OPEN", "CANCELED"))),
            )

        for ((_, request) in customers) assertCode(Codes.INVALID_FILTER) { SearchEntity.customers.plan(request) }
        for ((_, request) in orders) assertCode(Codes.INVALID_FILTER) { SearchEntity.orders.plan(request) }
    }

    @Test
    fun `the enum accepts its own values`() {
        assertEquals(
            listOf<Any>("CONFIRMED"),
            single(SearchEntity.orders, filters("status", Condition("eq", "CONFIRMED"))).values,
        )
    }

    @Test
    fun `sort accepts a field and a minus prefix for descending`() {
        val asc = SearchEntity.customers.plan(SearchRequest(sort = "name")).sort
        val desc = SearchEntity.customers.plan(SearchRequest(sort = "-created_at")).sort

        assertEquals(SortSpec(SearchEntity.customers.fields.getValue("name"), descending = false), asc)
        assertEquals(SortSpec(SearchEntity.customers.fields.getValue("created_at"), descending = true), desc)
    }

    @Test
    fun `sorting by an unknown or soft delete field is VALIDATION-06 and lists the allowed ones`() {
        val unknown =
            assertCode(
                Codes.UNKNOWN_SORT,
            ) { SearchEntity.customers.plan(SearchRequest(sort = "name; DROP TABLE customers")) }
        assertCode(Codes.UNKNOWN_SORT) { SearchEntity.customers.plan(SearchRequest(sort = "deleted_at")) }

        assertTrue((unknown.params["allowed"] as List<*>).contains("tax_id"))
    }

    @Test
    fun `every column of the exposed tables is searchable except the soft delete markers`() {
        val notSearchable = setOf("deleted_at", "deleted_by")

        for ((entity, table) in listOf(SearchEntity.customers to Customers, SearchEntity.orders to Orders)) {
            val columns = table.columns.map { it.name }.toSet() - notSearchable
            assertEquals(
                columns,
                entity.fields.values
                    .map { it.column }
                    .toSet(),
                entity.name,
            )
        }
    }
}
