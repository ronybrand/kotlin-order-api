package com.ronybrand.orderapi.persistence

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.stringParam
import java.time.Instant
import java.time.ZoneOffset

/** `column ILIKE pattern ESCAPE '\'`: a case-insensitive match where the plan has already escaped the wildcards. */
private class ILikeOp(private val expression: Expression<*>, private val pattern: String) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(expression)
        queryBuilder.append(" ILIKE ")
        queryBuilder.append(stringParam(pattern))
        queryBuilder.append(" ESCAPE '\\'")
    }
}

/** The WHERE condition of a plan on [table]: the soft-delete filter plus every predicate. */
internal fun SearchPlan.whereOn(
    table: Table,
    deletedAt: Column<*>,
): Op<Boolean> =
    predicates.fold<Predicate, Op<Boolean>>(deletedAt.isNull()) { acc, predicate ->
        acc and
            predicate.toOp(table)
    }

internal fun SortSpec.orderOn(table: Table): Pair<Expression<*>, SortOrder> =
    columnOf(table, field) to if (descending) SortOrder.DESC else SortOrder.ASC

private fun columnOf(
    table: Table,
    field: SearchField,
): Column<Any> {
    @Suppress("UNCHECKED_CAST")
    return table.columns.first { it.name == field.column } as Column<Any>
}

/** Timestamps are timestamptz columns: the plan carries an Instant, the driver wants an offset date-time. */
private fun Any.forColumn(): Any = if (this is Instant) atOffset(ZoneOffset.UTC) else this

private fun Predicate.toOp(table: Table): Op<Boolean> {
    val column = columnOf(table, field)
    val values = values.map { it.forColumn() }

    @Suppress("UNCHECKED_CAST")
    val ordered = column as Column<Comparable<Any>>

    @Suppress("UNCHECKED_CAST")
    val first = values[0] as Comparable<Any>
    return when (operator) {
        Operator.EQ -> column eq values[0]
        Operator.NEQ -> column neq values[0]
        Operator.GT -> ordered greater first
        Operator.GTE -> ordered greaterEq first
        Operator.LT -> ordered less first
        Operator.LTE -> ordered lessEq first
        Operator.IN -> column inList values
        Operator.BETWEEN -> column.between(values[0], values[1])
        Operator.LIKE -> ILikeOp(column, values[0] as String)
    }
}
