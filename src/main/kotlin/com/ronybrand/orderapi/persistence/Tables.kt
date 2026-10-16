package com.ronybrand.orderapi.persistence

import com.ronybrand.orderapi.domain.MAX_ACTOR_LENGTH
import com.ronybrand.orderapi.domain.MAX_EMAIL_LENGTH
import com.ronybrand.orderapi.domain.MAX_ITEM_DESCRIPTION
import com.ronybrand.orderapi.domain.MAX_NAME_LENGTH
import com.ronybrand.orderapi.domain.MAX_PASSPORT_LENGTH
import com.ronybrand.orderapi.domain.MAX_TAX_ID_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// The schema is owned by the Flyway migrations (src/main/resources/db/migration); these definitions only describe
// it to the query DSL, and the integration tests fail if the two drift apart.

private const val STATUS_LENGTH = 20
private const val PRICE_PRECISION = 10
private const val MONEY_SCALE = 2

internal object Customers : Table("customers") {
    val id = javaUUID("id")
    val name = varchar("name", MAX_NAME_LENGTH)
    val taxId = varchar("tax_id", MAX_TAX_ID_LENGTH)
    val passportNumber = varchar("passport_number", MAX_PASSPORT_LENGTH).nullable()
    val email = varchar("email", MAX_EMAIL_LENGTH)
    val version = long("version")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val createdBy = varchar("created_by", MAX_ACTOR_LENGTH)
    val updatedBy = varchar("updated_by", MAX_ACTOR_LENGTH)
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    val deletedBy = varchar("deleted_by", MAX_ACTOR_LENGTH).nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object Orders : Table("orders") {
    val id = javaUUID("id")
    val customerId = javaUUID("customer_id")
    val status = varchar("status", STATUS_LENGTH)
    val version = long("version")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val createdBy = varchar("created_by", MAX_ACTOR_LENGTH)
    val updatedBy = varchar("updated_by", MAX_ACTOR_LENGTH)
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    val deletedBy = varchar("deleted_by", MAX_ACTOR_LENGTH).nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object OrderItems : Table("order_items") {
    val id = javaUUID("id")

    /** Insertion order of the items of an order, assigned by the database. */
    val seq = long("seq").databaseGenerated()
    val orderId = javaUUID("order_id")
    val description = varchar("description", MAX_ITEM_DESCRIPTION)
    val unitPrice = decimal("unit_price", PRICE_PRECISION, MONEY_SCALE)
    val quantity = integer("quantity")

    override val primaryKey = PrimaryKey(id)
}
