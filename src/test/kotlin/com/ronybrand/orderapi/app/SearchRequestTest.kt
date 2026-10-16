package com.ronybrand.orderapi.app

import com.ronybrand.orderapi.assertCode
import com.ronybrand.orderapi.error.Codes
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchRequestTest {
    private fun eq(value: Any?) = Condition("eq", value)

    @Test
    fun `invalid requests are VALIDATION-01 or VALIDATION-05`() {
        val long = "x".repeat(MAX_FILTER_VALUE_LENGTH + 1)
        val manyFields = (1..MAX_FILTER_FIELDS + 1).associate { "f".repeat(it) to listOf(eq("x")) }
        val invalid01 =
            mapOf(
                "negative page" to SearchRequest(page = -1, size = 10),
                "size above the max" to SearchRequest(size = MAX_PAGE_SIZE + 1),
                "negative size" to SearchRequest(size = -5),
                "offset overflow" to SearchRequest(page = Int.MAX_VALUE, size = 100),
            )
        val invalid05 =
            mapOf(
                "too many fields" to SearchRequest(size = 10, filters = manyFields),
                "too many conditions" to
                    SearchRequest(size = 10, filters = mapOf("name" to List(MAX_CONDITIONS_PER_FIELD + 1) { eq("x") })),
                "value too long" to SearchRequest(size = 10, filters = mapOf("name" to listOf(eq(long)))),
                "long value in a list" to
                    SearchRequest(size = 10, filters = mapOf("name" to listOf(Condition("in", listOf("a", long))))),
                "list above the limit" to
                    SearchRequest(
                        size = 10,
                        filters =
                        mapOf("name" to listOf(Condition("in", List(MAX_IN_VALUES + 1) { "x" }))),
                    ),
            )

        for ((_, request) in invalid01) assertCode(Codes.INVALID_REQUEST) { request.validate() }
        for ((_, request) in invalid05) assertCode(Codes.INVALID_FILTER) { request.validate() }
    }

    @Test
    fun `the limits themselves are accepted`() {
        SearchRequest(page = 0, size = MAX_PAGE_SIZE).validate()
        SearchRequest(size = 10, filters = mapOf("name" to listOf(eq("x".repeat(MAX_FILTER_VALUE_LENGTH))))).validate()
        SearchRequest(
            size = 10,
            filters = mapOf("name" to listOf(Condition("in", List(MAX_IN_VALUES) { "x" }))),
        ).validate()
    }

    @Test
    fun `defaults fill in the page size only when it was left out`() {
        assertEquals(DEFAULT_PAGE_SIZE, SearchRequest().withDefaults().size)
        assertEquals(7, SearchRequest(size = 7).withDefaults().size)
    }

    @Test
    fun `the offset is page times size`() {
        assertEquals(60, SearchRequest(page = 3, size = 20).offset)
    }
}
