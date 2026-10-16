package com.ronybrand.orderapi.app

import com.ronybrand.orderapi.domain.Customer
import com.ronybrand.orderapi.domain.ItemInput
import com.ronybrand.orderapi.domain.Order
import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.domain.OrderStatusChanged
import com.ronybrand.orderapi.domain.StatusChange
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/** The order use cases. The [publisher] may be null when no notification is wanted. */
class OrderService(
    private val store: Store,
    private val publisher: EventPublisher?,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(OrderService::class.java)

    /** Opens an order for an existing customer (VALIDATION-07 if the customer does not exist). */
    fun create(
        actor: Actor,
        customerId: UUID,
        items: List<ItemInput>,
    ): Order {
        val order =
            store.inTransaction { repos ->
                // FOR SHARE on the customer, in the same transaction that saves the order: a concurrent delete of this
                // customer waits for us (or we wait for it and then find nothing), so an order is never created under
                // a customer that was deleted in between.
                try {
                    repos.customers.lockForShare(customerId)
                } catch (e: AppException) {
                    if (e.code == Codes.CUSTOMER_NOT_FOUND) {
                        throw AppException.validation(
                            Codes.CUSTOMER_NOT_FOUND_IN_BODY,
                            "customer does not exist",
                            mapOf("customer_id" to customerId),
                        )
                    }
                    throw e
                }
                Order.create(customerId, items, actor.id, clock.instant()).also { repos.orders.create(it) }
            }
        log.info("order created: {} by {}", order.id, actor.id)
        return order
    }

    fun get(id: UUID): Order = store.repos().orders.get(id)

    fun delete(
        actor: Actor,
        id: UUID,
    ) {
        val orders = store.repos().orders
        orders.get(id)
        orders.softDelete(id, actor.id, clock.instant())
        log.info("order deleted: {} by {}", id, actor.id)
    }

    fun addItem(
        actor: Actor,
        orderId: UUID,
        input: ItemInput,
    ) = mutate(orderId) { it.addItem(input, actor.id, clock.instant()) }

    fun updateItemQuantity(
        actor: Actor,
        orderId: UUID,
        itemId: UUID,
        quantity: Int,
    ) = mutate(orderId) { it.updateItemQuantity(itemId, quantity, actor.id, clock.instant()) }

    fun removeItem(
        actor: Actor,
        orderId: UUID,
        itemId: UUID,
    ) = mutate(orderId) { it.removeItem(itemId, actor.id, clock.instant()) }

    /** Moves an OPEN order to CONFIRMED and announces the change. */
    fun confirm(
        actor: Actor,
        orderId: UUID,
    ) = transition(orderId) { it.confirm(actor.id, clock.instant()) }

    /** Moves an OPEN or CONFIRMED order to CANCELED and announces the change. */
    fun cancel(
        actor: Actor,
        orderId: UUID,
    ) = transition(orderId) { it.cancel(actor.id, clock.instant()) }

    /** Filters, sorts and pages the active orders. */
    fun search(request: SearchRequest): Page<Order> {
        val valid = request.withDefaults().also { it.validate() }
        return store.repos().orders.search(valid)
    }

    /** Loads the order, applies the change and saves it with the version it read. */
    private fun mutate(
        orderId: UUID,
        change: (Order) -> Order,
    ): Order =
        store.inTransaction { repos ->
            repos.orders.save(change(repos.orders.get(orderId)))
        }

    private fun transition(
        orderId: UUID,
        change: (Order) -> StatusChange,
    ): Order {
        var previous: OrderStatus? = null
        val order =
            mutate(orderId) { current ->
                val result = change(current)
                previous = result.previous
                result.order
            }
        log.info("order status changed: {} from {} to {}", order.id, previous, order.status)
        announce(order, previous ?: order.status)
        return order
    }

    /**
     * Publishes the status change after the commit. A broker failure is logged and never reaches the caller: the
     * transition already happened and must not fail because a notification could not be queued.
     */
    private fun announce(
        order: Order,
        previous: OrderStatus,
    ) {
        val publisher = publisher ?: return
        val customer = customerToNotify(order) ?: return
        if (customer.email.isEmpty()) return
        val event =
            OrderStatusChanged(
                orderId = order.id,
                customerEmail = customer.email,
                customerName = customer.name,
                oldStatus = previous,
                newStatus = order.status,
                totalAmount = order.total,
                changedAt = clock.instant(),
            )
        publishBestEffort(publisher, event)
    }

    private fun customerToNotify(order: Order): Customer? =
        try {
            store.repos().customers.get(order.customerId)
        } catch (e: AppException) {
            log.error("could not load the customer to announce the status change of order {}: {}", order.id, e.code)
            null
        }

    // Best effort by design (ADR 0003): whatever the broker client throws, the transition already happened and must not
    // fail. The exception is logged by class, not by message, because a driver message can quote the payload.
    @Suppress("TooGenericExceptionCaught")
    private fun publishBestEffort(
        publisher: EventPublisher,
        event: OrderStatusChanged,
    ) {
        try {
            publisher.publishStatusChanged(event)
        } catch (e: Exception) {
            log.error("failed to publish {}: {}", event, e.javaClass.simpleName)
        }
    }
}
