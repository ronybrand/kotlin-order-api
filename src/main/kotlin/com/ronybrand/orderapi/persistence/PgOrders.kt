package com.ronybrand.orderapi.persistence

import com.ronybrand.orderapi.app.OrderRepository
import com.ronybrand.orderapi.app.Page
import com.ronybrand.orderapi.app.SearchRequest
import com.ronybrand.orderapi.domain.Item
import com.ronybrand.orderapi.domain.Order
import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

private fun ResultRow.toOrder(items: List<Item>) =
    Order(
        id = this[Orders.id],
        customerId = this[Orders.customerId],
        status = OrderStatus.valueOf(this[Orders.status]),
        items = items,
        version = this[Orders.version],
        createdAt = this[Orders.createdAt].toInstant(),
        updatedAt = this[Orders.updatedAt].toInstant(),
        createdBy = this[Orders.createdBy],
        updatedBy = this[Orders.updatedBy],
        deletedAt = this[Orders.deletedAt]?.toInstant(),
        deletedBy = this[Orders.deletedBy],
    )

private fun ResultRow.toItem() =
    Item(
        id = this[OrderItems.id],
        orderId = this[OrderItems.orderId],
        description = this[OrderItems.description],
        unitPrice = this[OrderItems.unitPrice],
        quantity = this[OrderItems.quantity],
    )

internal class PgOrders(private val database: Database) : OrderRepository {
    private fun notFound(id: UUID) = AppException.notFound(Codes.ORDER_NOT_FOUND, "order not found", mapOf("id" to id))

    private fun active(id: UUID): Op<Boolean> = (Orders.id eq id) and Orders.deletedAt.isNull()

    override fun get(id: UUID): Order =
        database.tx {
            val row = Orders.selectAll().where { active(id) }.singleOrNull() ?: throw notFound(id)
            row.toOrder(itemsOf(listOf(id))[id].orEmpty())
        }

    /** The items of the given orders in insertion order (seq), grouped by order. */
    private fun JdbcTransaction.itemsOf(orderIds: List<UUID>): Map<UUID, List<Item>> =
        OrderItems
            .selectAll()
            .where { OrderItems.orderId inList orderIds }
            .orderBy(OrderItems.seq to SortOrder.ASC)
            .map { it.toItem() }
            .groupBy { it.orderId }

    override fun create(order: Order) {
        database.tx {
            Orders.insert {
                it[id] = order.id
                it[customerId] = order.customerId
                it[status] = order.status.name
                it[version] = order.version
                it[createdAt] = order.createdAt.toDb()
                it[updatedAt] = order.updatedAt.toDb()
                it[createdBy] = order.createdBy
                it[updatedBy] = order.updatedBy
            }
            order.items.forEach { insertItem(it) }
        }
    }

    private fun insertItem(item: Item) {
        OrderItems.insert {
            it[id] = item.id
            it[orderId] = item.orderId
            it[description] = item.description
            it[unitPrice] = item.unitPrice
            it[quantity] = item.quantity
        }
    }

    override fun save(order: Order): Order =
        database.tx {
            val changed =
                Orders.update({ active(order.id) and (Orders.version eq order.version) }) {
                    it[status] = order.status.name
                    it[updatedAt] = order.updatedAt.toDb()
                    it[updatedBy] = order.updatedBy
                    it[version] = order.version + 1
                }
            if (changed == 0) throw concurrentModification()
            syncItems(order)
            order.copy(version = order.version + 1)
        }

    /**
     * Makes the stored items equal to the aggregate's: rows no longer in it are deleted, new ones inserted, and the
     * ones whose data changed are updated. The order row was just updated, so it is locked for this transaction.
     */
    private fun syncItems(order: Order) {
        val stored =
            OrderItems
                .selectAll()
                .where { OrderItems.orderId eq order.id }
                .map { it.toItem() }
                .associateBy { it.id }
        OrderItems.deleteWhere { (orderId eq order.id) and (id notInList order.items.map { it.id }) }
        for (item in order.items) {
            val before = stored[item.id]
            when {
                before == null -> insertItem(item)
                before != item -> updateItem(item)
            }
        }
    }

    private fun updateItem(item: Item) {
        OrderItems.update({ OrderItems.id eq item.id }) {
            it[description] = item.description
            it[unitPrice] = item.unitPrice
            it[quantity] = item.quantity
        }
    }

    override fun softDelete(
        id: UUID,
        actor: String,
        at: Instant,
    ) {
        database.tx {
            val changed =
                Orders.update({ active(id) }) {
                    it[deletedAt] = at.toDb()
                    it[deletedBy] = actor
                    it[updatedAt] = at.toDb()
                    it[updatedBy] = actor
                    it[version] = Orders.version + 1
                }
            if (changed == 0) throw notFound(id)
        }
    }

    override fun hasActive(customerId: UUID): Boolean =
        database.tx {
            Orders
                .selectAll()
                .where { (Orders.customerId eq customerId) and Orders.deletedAt.isNull() }
                .limit(
                    1,
                ).count() >
                0
        }

    override fun search(request: SearchRequest): Page<Order> =
        database.tx {
            val plan = SearchEntity.orders.plan(request)
            val where = plan.whereOn(Orders, Orders.deletedAt)
            val total = Orders.selectAll().where { where }.count()
            val (sortExpression, sortOrder) = plan.sort.orderOn(Orders)
            val rows =
                Orders
                    .selectAll()
                    .where { where }
                    .orderBy(sortExpression to sortOrder, Orders.id to SortOrder.ASC)
                    .limit(request.size)
                    .offset(request.offset.toLong())
                    .toList()
            val items = if (rows.isEmpty()) emptyMap() else itemsOf(rows.map { it[Orders.id] })
            Page(rows.map { it.toOrder(items[it[Orders.id]].orEmpty()) }, total.toInt(), request.page, request.size)
        }
}
