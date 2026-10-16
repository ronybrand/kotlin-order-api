package com.ronybrand.orderapi.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Fired at the end of confirm and cancel, only when the customer has an e-mail (DOMAIN.md section 5). The JSON
 * form is the wire contract of the notification queue, shared by the API (producer) and the worker (consumer).
 *
 * [toString] keeps the customer's e-mail and name out of the logs: the order id is enough to trace the event.
 */
data class OrderStatusChanged(
    val orderId: UUID,
    val customerEmail: String,
    val customerName: String,
    val oldStatus: OrderStatus,
    val newStatus: OrderStatus,
    val totalAmount: BigDecimal,
    val changedAt: Instant,
) {
    override fun toString() = "OrderStatusChanged(orderId=$orderId, oldStatus=$oldStatus, newStatus=$newStatus)"
}
