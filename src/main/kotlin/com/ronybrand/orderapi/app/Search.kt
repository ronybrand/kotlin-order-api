package com.ronybrand.orderapi.app

import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes

// Limits of a search request: one request must not carry hundreds of predicates.
const val DEFAULT_PAGE_SIZE = 20
const val MAX_PAGE_SIZE = 100
const val MAX_FILTER_FIELDS = 10
const val MAX_CONDITIONS_PER_FIELD = 10
const val MAX_IN_VALUES = 100
const val MAX_FILTER_VALUE_LENGTH = 200

/** One predicate on a field, e.g. `gte` with the value 10. An empty [op] means `eq`. */
data class Condition(val op: String = "", val value: Any? = null)

/**
 * The filter, sort and page of a search. [page] is 0-based; [sort] is a field name with a `-` prefix for
 * descending order. Filter values are plain Kotlin values (String, Number, Boolean, List) decoded from JSON.
 */
data class SearchRequest(
    val page: Int = 0,
    val size: Int = 0,
    val sort: String = "",
    val filters: Map<String, List<Condition>> = emptyMap(),
) {
    /** The number of rows to skip. */
    val offset: Int get() = page * size

    /** Fills in the page size when the client left it out. */
    fun withDefaults() = if (size == 0) copy(size = DEFAULT_PAGE_SIZE) else this

    /**
     * Checks the structural limits of the request. Field names and value types are checked later, against the
     * columns of the entity being searched (they are known only to the repository).
     */
    fun validate() {
        if (page < 0) throw invalidField("page", "page must not be negative")
        if (size < 1 || size > MAX_PAGE_SIZE) throw invalidField("size", "size must be between 1 and 100")
        if (page.toLong() * size > Int.MAX_VALUE) throw invalidField("page", "page is too large")
        if (filters.size > MAX_FILTER_FIELDS) throw invalidFilter("too many filter fields")
        for ((field, conditions) in filters) {
            if (conditions.size > MAX_CONDITIONS_PER_FIELD) {
                throw invalidFilter("too many conditions on one field", field)
            }
            if (conditions.any { !withinValueLimits(it.value) }) {
                throw invalidFilter("filter value exceeds the allowed limits", field)
            }
        }
    }

    private fun invalidField(
        field: String,
        message: String,
    ) = AppException.invalidField(field, message)

    private fun invalidFilter(
        message: String,
        field: String? = null,
    ): AppException {
        val params = if (field == null) emptyMap() else mapOf("field" to field)
        return AppException.validation(Codes.INVALID_FILTER, message, params)
    }

    private fun withinValueLimits(value: Any?): Boolean =
        when (value) {
            is String -> value.length <= MAX_FILTER_VALUE_LENGTH
            is List<*> -> value.size <= MAX_IN_VALUES && value.all { withinValueLimits(it) }
            else -> true
        }
}

/** One page of results plus the total number of matches. */
data class Page<T>(val items: List<T>, val total: Int, val page: Int, val size: Int)
