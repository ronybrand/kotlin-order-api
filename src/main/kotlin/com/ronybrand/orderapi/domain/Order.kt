package com.ronybrand.orderapi.domain

import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

// Order rules (DOMAIN.md section 4).
const val MAX_ITEMS_PER_ORDER = 200
const val MAX_ITEM_DESCRIPTION = 255
const val MAX_QUANTITY = 10_000
private const val MONEY_SCALE = 2

/** The largest value the numeric(10,2) column holds. */
val MAX_UNIT_PRICE: BigDecimal = BigDecimal("99999999.99")

/** The lifecycle of an order: OPEN to CONFIRMED to CANCELED, or OPEN to CANCELED. */
enum class OrderStatus { OPEN, CONFIRMED, CANCELED }

/** An order line. Its subtotal is derived, never stored. */
data class Item(
    val id: UUID,
    val orderId: UUID,
    val description: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
) {
    val subtotal: BigDecimal get() = unitPrice.multiply(BigDecimal(quantity))
}

/** The data a client supplies for a new item. */
data class ItemInput(
    val description: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
) {
    internal fun validate() {
        if (description.isBlank()) throw AppException.invalidField("description", "description must not be blank")
        if (description.codePointCount(0, description.length) > MAX_ITEM_DESCRIPTION) {
            throw AppException.invalidField("description", "description is too long")
        }
        if (unitPrice.signum() <=
            0
        ) {
            throw AppException.invalidField("unit_price", "unit_price must be greater than zero")
        }
        if (unitPrice > MAX_UNIT_PRICE) throw AppException.invalidField("unit_price", "unit_price is too large")
        if (unitPrice.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw AppException.invalidField("unit_price", "unit_price must have at most 2 decimal places")
        }
        validateQuantity(quantity)
    }

    internal fun toItem(orderId: UUID) =
        Item(
            UUID.randomUUID(),
            orderId,
            description,
            unitPrice.setScale(MONEY_SCALE),
            quantity,
        )
}

private fun validateQuantity(quantity: Int) {
    if (quantity < 1 || quantity > MAX_QUANTITY) {
        throw AppException.invalidField("quantity", "quantity must be between 1 and 10000")
    }
}

/** The result of a status transition: the order after it and the status it had before. */
data class StatusChange(
    val order: Order,
    val previous: OrderStatus,
)

/**
 * The aggregate root: it owns its items and keeps the total consistent with them. It is immutable; every
 * change returns the new state and leaves the original alone, so a failed rule never leaves a half-changed order.
 *
 * [version] is the optimistic-concurrency token: a write that changes status or items must carry the version it
 * read, and the repository fails with CONFLICT-00 if the row moved on.
 */
data class Order(
    val id: UUID,
    val customerId: UUID,
    val status: OrderStatus,
    val items: List<Item>,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: String,
    val updatedBy: String,
    val deletedAt: Instant? = null,
    val deletedBy: String? = null,
) {
    /** Derived from the current items on every call (rule 1): there is no stored total to go stale. */
    val total: BigDecimal
        get() =
            items
                .fold(
                    BigDecimal.ZERO,
                ) { sum, item -> sum.add(item.subtotal) }
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP)

    /** Items may still change (rule 2). */
    val isEditable: Boolean get() = status == OrderStatus.OPEN

    val isDeleted: Boolean get() = deletedAt != null

    fun addItem(
        input: ItemInput,
        actor: String,
        now: Instant,
    ): Order {
        requireEditable()
        if (items.size >= MAX_ITEMS_PER_ORDER) throw itemLimitError()
        input.validate()
        return copy(items = items + input.toItem(id)).touched(actor, now)
    }

    fun updateItemQuantity(
        itemId: UUID,
        quantity: Int,
        actor: String,
        now: Instant,
    ): Order {
        requireEditable()
        val index = indexOf(itemId)
        validateQuantity(quantity)
        return copy(items = items.mapIndexed { i, item -> if (i == index) item.copy(quantity = quantity) else item })
            .touched(actor, now)
    }

    fun removeItem(
        itemId: UUID,
        actor: String,
        now: Instant,
    ): Order {
        requireEditable()
        val index = indexOf(itemId)
        return copy(items = items.filterIndexed { i, _ -> i != index }).touched(actor, now)
    }

    /** OPEN to CONFIRMED (rule 3). */
    fun confirm(
        actor: String,
        now: Instant,
    ): StatusChange {
        if (status != OrderStatus.OPEN) {
            throw AppException.validation(
                Codes.INVALID_TRANSITION,
                "cannot confirm an order that is not OPEN",
                mapOf("id" to id, "status" to status),
            )
        }
        if (items.isEmpty()) {
            throw AppException.validation(
                Codes.ORDER_EMPTY,
                "an order without items cannot be confirmed",
                mapOf("id" to id),
            )
        }
        return transition(OrderStatus.CONFIRMED, actor, now)
    }

    /** OPEN or CONFIRMED to CANCELED (rule 4). */
    fun cancel(
        actor: String,
        now: Instant,
    ): StatusChange {
        if (status == OrderStatus.CANCELED) {
            throw AppException.validation(
                Codes.ALREADY_CANCELED,
                "order is already canceled",
                mapOf("id" to id, "status" to status),
            )
        }
        return transition(OrderStatus.CANCELED, actor, now)
    }

    private fun transition(
        to: OrderStatus,
        actor: String,
        now: Instant,
    ) = StatusChange(copy(status = to).touched(actor, now), previous = status)

    private fun touched(
        actor: String,
        now: Instant,
    ) = copy(updatedBy = actor, updatedAt = now)

    private fun requireEditable() {
        if (!isEditable) {
            throw AppException.validation(
                Codes.ORDER_NOT_EDITABLE,
                "order is not editable in its current status",
                mapOf("id" to id, "status" to status),
            )
        }
    }

    private fun indexOf(itemId: UUID): Int {
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            throw AppException.notFound(
                Codes.ITEM_NOT_FOUND,
                "item not found in this order",
                mapOf("order_id" to id, "item_id" to itemId),
            )
        }
        return index
    }

    companion object {
        /** Builds an OPEN order from the requested items (rules 5 and 6). */
        fun create(
            customerId: UUID,
            items: List<ItemInput>,
            actor: String,
            now: Instant,
            id: UUID = UUID.randomUUID(),
        ): Order {
            if (items.size > MAX_ITEMS_PER_ORDER) throw itemLimitError()
            items.forEach { it.validate() }
            return Order(
                id = id,
                customerId = customerId,
                status = OrderStatus.OPEN,
                items = items.map { it.toItem(id) },
                version = 1,
                createdAt = now,
                updatedAt = now,
                createdBy = actor,
                updatedBy = actor,
            )
        }
    }
}

private fun itemLimitError() =
    AppException.validation(
        Codes.ITEM_LIMIT,
        "an order can have at most 200 items",
        mapOf("max" to MAX_ITEMS_PER_ORDER),
    )
