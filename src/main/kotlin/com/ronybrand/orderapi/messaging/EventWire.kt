package com.ronybrand.orderapi.messaging

import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.domain.OrderStatusChanged
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private const val MONEY_SCALE = 2

/**
 * The JSON form of [OrderStatusChanged]: the wire contract of the notification queue, shared by the API (producer)
 * and the worker (consumer). The amount travels as text so no consumer ever parses it as a float.
 */
object EventWire {
    @Serializable
    private class Payload(
        @SerialName("order_id") val orderId: String,
        @SerialName("customer_email") val customerEmail: String,
        @SerialName("customer_name") val customerName: String,
        @SerialName("old_status") val oldStatus: OrderStatus,
        @SerialName("new_status") val newStatus: OrderStatus,
        @SerialName("total_amount") val totalAmount: String,
        @SerialName("changed_at") val changedAt: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(event: OrderStatusChanged): ByteArray =
        json
            .encodeToString(
                Payload.serializer(),
                Payload(
                    event.orderId.toString(),
                    event.customerEmail,
                    event.customerName,
                    event.oldStatus,
                    event.newStatus,
                    event.totalAmount.setScale(MONEY_SCALE).toPlainString(),
                    event.changedAt.toString(),
                ),
            ).toByteArray()

    /** Null for anything that is not a valid event: the caller dead-letters it, retrying cannot fix it. */
    fun decode(body: ByteArray): OrderStatusChanged? =
        try {
            val payload = json.decodeFromString(Payload.serializer(), String(body))
            OrderStatusChanged(
                orderId = UUID.fromString(payload.orderId),
                customerEmail = payload.customerEmail,
                customerName = payload.customerName,
                oldStatus = payload.oldStatus,
                newStatus = payload.newStatus,
                totalAmount = BigDecimal(payload.totalAmount),
                changedAt = Instant.parse(payload.changedAt),
            )
        } catch (e: Exception) {
            // Malformed JSON, a missing field, an unknown status, a bad UUID or number: all the same for the caller.
            null
        }
}
