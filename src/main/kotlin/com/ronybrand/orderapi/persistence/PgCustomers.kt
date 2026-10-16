package com.ronybrand.orderapi.persistence

import com.ronybrand.orderapi.app.CustomerRepository
import com.ronybrand.orderapi.app.Page
import com.ronybrand.orderapi.app.SearchRequest
import com.ronybrand.orderapi.domain.Customer
import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal fun Instant.toDb() = atOffset(ZoneOffset.UTC)

private fun ResultRow.toCustomer() =
    Customer(
        id = this[Customers.id],
        name = this[Customers.name],
        taxId = this[Customers.taxId],
        passportNumber = this[Customers.passportNumber],
        email = this[Customers.email],
        version = this[Customers.version],
        createdAt = this[Customers.createdAt].toInstant(),
        updatedAt = this[Customers.updatedAt].toInstant(),
        createdBy = this[Customers.createdBy],
        updatedBy = this[Customers.updatedBy],
        deletedAt = this[Customers.deletedAt]?.toInstant(),
        deletedBy = this[Customers.deletedBy],
    )

internal class PgCustomers(private val database: Database) : CustomerRepository {
    private fun notFound(id: UUID) =
        AppException.notFound(
            Codes.CUSTOMER_NOT_FOUND,
            "customer not found",
            mapOf("id" to id),
        )

    private fun active(id: UUID): Op<Boolean> = (Customers.id eq id) and Customers.deletedAt.isNull()

    override fun get(id: UUID): Customer =
        database.tx {
            Customers
                .selectAll()
                .where { active(id) }
                .singleOrNull()
                ?.toCustomer() ?: throw notFound(id)
        }

    override fun lockForUpdate(id: UUID): Customer =
        database.tx {
            Customers
                .selectAll()
                .where { active(id) }
                .forUpdate()
                .singleOrNull()
                ?.toCustomer() ?: throw notFound(id)
        }

    override fun lockForShare(id: UUID): Customer =
        database.tx {
            Customers
                .selectAll()
                .where { active(id) }
                .forUpdate(ForUpdateOption.PostgreSQL.ForShare)
                .singleOrNull()
                ?.toCustomer() ?: throw notFound(id)
        }

    override fun create(customer: Customer) {
        database.tx {
            Customers.insert {
                it[id] = customer.id
                it[name] = customer.name
                it[taxId] = customer.taxId
                it[passportNumber] = customer.passportNumber
                it[email] = customer.email
                it[version] = customer.version
                it[createdAt] = customer.createdAt.toDb()
                it[updatedAt] = customer.updatedAt.toDb()
                it[createdBy] = customer.createdBy
                it[updatedBy] = customer.updatedBy
            }
        }
    }

    override fun update(customer: Customer): Customer =
        database.tx {
            val changed =
                Customers.update({ active(customer.id) and (Customers.version eq customer.version) }) {
                    it[name] = customer.name
                    it[taxId] = customer.taxId
                    it[passportNumber] = customer.passportNumber
                    it[email] = customer.email
                    it[updatedAt] = customer.updatedAt.toDb()
                    it[updatedBy] = customer.updatedBy
                    it[version] = customer.version + 1
                }
            if (changed == 0) throw concurrentModification()
            customer.copy(version = customer.version + 1)
        }

    override fun softDelete(
        id: UUID,
        actor: String,
        at: Instant,
    ) {
        database.tx {
            val changed =
                Customers.update({ active(id) }) {
                    it[deletedAt] = at.toDb()
                    it[deletedBy] = actor
                    it[updatedAt] = at.toDb()
                    it[updatedBy] = actor
                    it[version] = Customers.version + 1
                }
            if (changed == 0) throw notFound(id)
        }
    }

    override fun taxIdTaken(
        taxId: String,
        except: UUID?,
    ) = taken(Customers.taxId eq taxId, except)

    override fun passportTaken(
        passport: String,
        except: UUID?,
    ) = taken(Customers.passportNumber eq passport, except)

    private fun taken(
        match: Op<Boolean>,
        except: UUID?,
    ): Boolean =
        database.tx {
            val others = if (except == null) Op.TRUE else Customers.id neq except
            Customers
                .selectAll()
                .where { match and Customers.deletedAt.isNull() and others }
                .limit(1)
                .count() > 0
        }

    override fun search(request: SearchRequest): Page<Customer> =
        database.tx {
            val plan = SearchEntity.customers.plan(request)
            val where = plan.whereOn(Customers, Customers.deletedAt)
            val total = Customers.selectAll().where { where }.count()
            val (sortExpression, sortOrder) = plan.sort.orderOn(Customers)
            val items =
                Customers
                    .selectAll()
                    .where { where }
                    .orderBy(sortExpression to sortOrder, Customers.id to SortOrder.ASC)
                    .limit(request.size)
                    .offset(request.offset.toLong())
                    .map { it.toCustomer() }
            Page(items, total.toInt(), request.page, request.size)
        }
}
