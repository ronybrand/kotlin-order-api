package com.ronybrand.orderapi.persistence

import com.ronybrand.orderapi.app.Condition
import com.ronybrand.orderapi.app.MAX_IN_VALUES
import com.ronybrand.orderapi.app.SearchRequest
import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

internal enum class FieldType { STRING, UUID, INT, TIME, ENUM }

/**
 * One searchable column. Values are coerced to the column's type before they reach SQL, so a wrong type is a
 * VALIDATION-05 for the client instead of a driver error.
 */
internal class SearchField(val column: String, val type: FieldType, val allowed: Set<String> = emptySet())

internal enum class Operator { EQ, NEQ, GT, GTE, LT, LTE, IN, BETWEEN, LIKE }

/** A validated predicate: the field, the operator and the values already converted to the column's type. */
internal data class Predicate(val field: SearchField, val operator: Operator, val values: List<Any>)

internal data class SortSpec(val field: SearchField, val descending: Boolean)

/** What a search request means for one entity, validated and typed, with no SQL in it. */
internal data class SearchPlan(val predicates: List<Predicate>, val sort: SortSpec)

/**
 * The whitelist of what a search can filter and sort on. Only columns are listed (never the soft-delete markers):
 * the query only ever uses columns from here, and values are always bound parameters.
 */
internal class SearchEntity(val name: String, val fields: Map<String, SearchField>) {
    /**
     * Turns a request into a plan. Unknown filter fields are ignored (a client may send a superset of what an entity
     * knows); an unknown sort field is VALIDATION-06.
     */
    fun plan(request: SearchRequest): SearchPlan {
        val predicates =
            request.filters.toSortedMap().flatMap { (name, conditions) ->
                val field = fields[name] ?: return@flatMap emptyList()
                conditions.map { predicateOf(name, field, it) }
            }
        return SearchPlan(predicates, sortOf(request.sort))
    }

    private fun sortOf(spec: String): SortSpec {
        if (spec.isEmpty()) return SortSpec(fields.getValue("created_at"), descending = true)
        val name = spec.removePrefix("-")
        val field =
            fields[name] ?: throw AppException.validation(
                Codes.UNKNOWN_SORT,
                "cannot sort by this field",
                mapOf("field" to name, "allowed" to fields.keys.sorted()),
            )
        return SortSpec(field, descending = spec.startsWith("-"))
    }

    private fun predicateOf(
        name: String,
        field: SearchField,
        condition: Condition,
    ): Predicate =
        PredicateBuilder(name, field, parseOperator(condition.op.ifEmpty { "eq" }, name), condition.value).build()

    private fun parseOperator(
        raw: String,
        field: String,
    ): Operator =
        when (raw.lowercase()) {
            "eq" -> Operator.EQ

            "neq" -> Operator.NEQ

            "gt" -> Operator.GT

            "gte" -> Operator.GTE

            "lt" -> Operator.LT

            "lte" -> Operator.LTE

            "in" -> Operator.IN

            "between" -> Operator.BETWEEN

            "lk" -> Operator.LIKE

            else -> throw AppException.validation(
                Codes.INVALID_FILTER,
                "unknown operator",
                mapOf("field" to field, "op" to raw.lowercase()),
            )
        }

    companion object {
        val customers =
            SearchEntity(
                "customers",
                fieldsOf(
                    "id" to SearchField("id", FieldType.UUID),
                    "name" to SearchField("name", FieldType.STRING),
                    "tax_id" to SearchField("tax_id", FieldType.STRING),
                    "passport_number" to SearchField("passport_number", FieldType.STRING),
                    "email" to SearchField("email", FieldType.STRING),
                    "version" to SearchField("version", FieldType.INT),
                    "created_at" to SearchField("created_at", FieldType.TIME),
                    "updated_at" to SearchField("updated_at", FieldType.TIME),
                    "created_by" to SearchField("created_by", FieldType.STRING),
                    "updated_by" to SearchField("updated_by", FieldType.STRING),
                ),
            )

        val orders =
            SearchEntity(
                "orders",
                fieldsOf(
                    "id" to SearchField("id", FieldType.UUID),
                    "customer_id" to SearchField("customer_id", FieldType.UUID),
                    "status" to SearchField("status", FieldType.ENUM, OrderStatus.entries.map { it.name }.toSet()),
                    "version" to SearchField("version", FieldType.INT),
                    "created_at" to SearchField("created_at", FieldType.TIME),
                    "updated_at" to SearchField("updated_at", FieldType.TIME),
                    "created_by" to SearchField("created_by", FieldType.STRING),
                    "updated_by" to SearchField("updated_by", FieldType.STRING),
                ),
            )

        private fun fieldsOf(vararg pairs: Pair<String, SearchField>) = linkedMapOf(*pairs)
    }
}

private val CANONICAL_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private const val EXACT_DOUBLE_LIMIT = 9_007_199_254_740_992.0

private fun expectation(field: SearchField) =
    when (field.type) {
        FieldType.STRING -> "expected a string"
        FieldType.UUID -> "expected a UUID"
        FieldType.INT -> "expected an integer"
        FieldType.TIME -> "expected an RFC 3339 timestamp or a YYYY-MM-DD date"
        FieldType.ENUM -> "expected one of ${field.allowed.sorted().joinToString(", ")}"
    }

/** Converts a decoded JSON value to the type of the column, or null when it does not fit. */
private fun coerce(
    field: SearchField,
    value: Any?,
): Any? =
    when (field.type) {
        FieldType.STRING -> value as? String
        FieldType.UUID -> (value as? String)?.takeIf { CANONICAL_UUID.matches(it) }?.let { UUID.fromString(it) }
        FieldType.INT -> coerceLong(value)
        FieldType.TIME -> (value as? String)?.let { parseTime(it) }
        FieldType.ENUM -> (value as? String)?.takeIf { it in field.allowed }
    }

private fun coerceLong(value: Any?): Long? =
    when (value) {
        is Long -> {
            value
        }

        is Int -> {
            value.toLong()
        }

        is BigDecimal -> {
            value
                .takeIf {
                    it.stripTrailingZeros().scale() <= 0
                }?.let { runCatching { it.longValueExact() }.getOrNull() }
        }

        is Double -> {
            value.takeIf { it == Math.floor(it) && Math.abs(it) <= EXACT_DOUBLE_LIMIT }?.toLong()
        }

        is String -> {
            value.toLongOrNull()
        }

        else -> {
            null
        }
    }

private fun parseTime(text: String): Instant? =
    try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        try {
            LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }

private const val BETWEEN_VALUES = 2

/** Neutralizes the LIKE wildcards: "50%" must match the text "50%", not everything starting with 50. */
private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** Turns one condition into a [Predicate], rejecting what does not fit the field's type as VALIDATION-05. */
private class PredicateBuilder(
    private val name: String,
    private val field: SearchField,
    private val operator: Operator,
    private val value: Any?,
) {
    fun build(): Predicate =
        when (operator) {
            Operator.EQ, Operator.NEQ -> single()
            Operator.GT, Operator.GTE, Operator.LT, Operator.LTE -> ordered { single() }
            Operator.IN -> inList()
            Operator.BETWEEN -> ordered { Predicate(field, operator, coercedList(BETWEEN_VALUES)) }
            Operator.LIKE -> like()
        }

    private fun single() = Predicate(field, operator, listOf(coerced(value)))

    private fun ordered(build: () -> Predicate): Predicate {
        if (field.type == FieldType.ENUM) bad("ordering operators are not supported on this field")
        return build()
    }

    private fun inList(): Predicate {
        val values = coercedList(exactly = null)
        if (values.isEmpty() || values.size > MAX_IN_VALUES) bad("in expects between 1 and 100 values")
        return Predicate(field, operator, values)
    }

    private fun like(): Predicate {
        if (field.type != FieldType.STRING) bad("lk is only supported on text fields")
        val text = value as? String ?: bad("lk expects a string")
        return Predicate(field, operator, listOf("%${escapeLike(text)}%"))
    }

    private fun coerced(raw: Any?): Any = coerce(field, raw) ?: bad(expectation(field))

    private fun coercedList(exactly: Int?): List<Any> {
        val list = value as? List<*> ?: bad("expected a list of values")
        if (exactly != null && list.size != exactly) bad("expected exactly $exactly values")
        return list.map { coerced(it) }
    }

    private fun bad(reason: String): Nothing =
        throw AppException.validation(
            Codes.INVALID_FILTER,
            reason,
            mapOf("field" to name, "op" to operator.name.lowercase()),
        )
}
