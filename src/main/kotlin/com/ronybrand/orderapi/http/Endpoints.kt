package com.ronybrand.orderapi.http

import com.ronybrand.orderapi.app.CustomerService
import com.ronybrand.orderapi.app.OrderService
import com.ronybrand.orderapi.app.SearchRequest
import com.ronybrand.orderapi.error.AppException
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.util.UUID

/** Who may call an endpoint. Declared for every one of them: there is no default. */
internal enum class Access { PUBLIC, USER, ADMIN }

internal class Endpoint(
    val method: HttpMethod,
    val path: String,
    val access: Access,
    val handler: suspend ApplicationCall.() -> Unit,
)

private fun invalidBody() = AppException.invalidField("body", "request body is not valid for this endpoint")

private suspend inline fun <reified T> ApplicationCall.body(): T {
    val text = receiveText()
    return try {
        WireJson.decodeFromString<T>(text)
    } catch (_: SerializationException) {
        throw invalidBody()
    } catch (_: IllegalArgumentException) {
        throw invalidBody()
    }
}

private fun ApplicationCall.pathId(name: String): UUID =
    parseUuid(parameters[name].orEmpty()) ?: throw AppException.invalidField(name, "$name must be a valid UUID")

/** The use cases are blocking (JDBC), so they run on the IO dispatcher and never on an event-loop thread. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/** Reads the GET form of a search: page, size and sort only. Filters need the POST form. */
private fun ApplicationCall.searchFromQuery(): SearchRequest {
    fun number(name: String): Int {
        val raw = request.queryParameters[name]
        if (raw.isNullOrEmpty()) return 0
        return raw.toIntOrNull() ?: throw AppException.invalidField(name, "$name must be an integer")
    }
    return SearchRequest(page = number("page"), size = number("size"), sort = request.queryParameters["sort"].orEmpty())
}

/** Translates between HTTP and the use cases. It holds no business rule. */
internal class Handlers(
    private val customers: CustomerService,
    private val orders: OrderService,
    private val ping: () -> Unit,
) {
    private val log = LoggerFactory.getLogger(Handlers::class.java)

    /** Every route of the API, with the access it requires. */
    fun endpoints(): List<Endpoint> =
        listOf(
            Endpoint(HttpMethod.Get, HEALTH_PATH, Access.PUBLIC) { health() },
            Endpoint(HttpMethod.Post, "/customers", Access.ADMIN) { createCustomer() },
            Endpoint(HttpMethod.Get, "/customers/search", Access.USER) { searchCustomers(searchFromQuery()) },
            Endpoint(HttpMethod.Post, "/customers/search", Access.USER) {
                searchCustomers(parseSearchBody(receiveText()))
            },
            Endpoint(HttpMethod.Get, "/customers/{id}", Access.USER) { getCustomer() },
            Endpoint(HttpMethod.Put, "/customers/{id}", Access.ADMIN) { updateCustomer() },
            Endpoint(HttpMethod.Delete, "/customers/{id}", Access.ADMIN) { deleteCustomer() },
            Endpoint(HttpMethod.Post, "/orders", Access.USER) { createOrder() },
            Endpoint(HttpMethod.Get, "/orders/search", Access.USER) { searchOrders(searchFromQuery()) },
            Endpoint(HttpMethod.Post, "/orders/search", Access.USER) { searchOrders(parseSearchBody(receiveText())) },
            Endpoint(HttpMethod.Get, "/orders/{id}", Access.USER) { getOrder() },
            Endpoint(HttpMethod.Delete, "/orders/{id}", Access.USER) { deleteOrder() },
            Endpoint(HttpMethod.Post, "/orders/{id}/items", Access.USER) { addItem() },
            Endpoint(HttpMethod.Patch, "/orders/{id}/items/{item_id}", Access.USER) { updateItem() },
            Endpoint(HttpMethod.Delete, "/orders/{id}/items/{item_id}", Access.USER) { removeItem() },
            Endpoint(HttpMethod.Post, "/orders/{id}/confirm", Access.USER) { confirmOrder() },
            Endpoint(HttpMethod.Post, "/orders/{id}/cancel", Access.USER) { cancelOrder() },
        )

    // /health answers 503 for any failure of the probe, whatever its type: the caller only needs to know it is down.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ApplicationCall.health() {
        try {
            io { ping() }
            respondJson(HttpStatusCode.OK, mapOf("status" to "ok"))
        } catch (e: Exception) {
            // The reason (a connection string, a host name) stays in the log and out of the response.
            log.error("health check failed: {}", e.javaClass.simpleName)
            respondJson(HttpStatusCode.ServiceUnavailable, mapOf("status" to "unavailable"))
        }
    }

    // --- customers ---------------------------------------------------------------------------------------

    private suspend fun ApplicationCall.createCustomer() {
        val request = body<CustomerRequest>()
        val customer = io { customers.create(actor, request.toInput()) }
        respondJson(HttpStatusCode.Created, customer.toResponse())
    }

    private suspend fun ApplicationCall.getCustomer() {
        val id = pathId("id")
        respondJson(HttpStatusCode.OK, io { customers.get(id) }.toResponse())
    }

    private suspend fun ApplicationCall.updateCustomer() {
        val id = pathId("id")
        val request = body<CustomerRequest>()
        respondJson(HttpStatusCode.OK, io { customers.update(actor, id, request.toInput()) }.toResponse())
    }

    private suspend fun ApplicationCall.deleteCustomer() {
        val id = pathId("id")
        io { customers.delete(actor, id) }
        respond(HttpStatusCode.NoContent)
    }

    private suspend fun ApplicationCall.searchCustomers(request: SearchRequest) {
        val page = io { customers.search(request) }
        respondJson(HttpStatusCode.OK, page.toResponse { it.toResponse() })
    }

    // --- orders ------------------------------------------------------------------------------------------

    private suspend fun ApplicationCall.createOrder() {
        val request = body<OrderRequest>()
        val order = io { orders.create(actor, request.customerId, request.items.map { it.toInput() }) }
        respondJson(HttpStatusCode.Created, order.toResponse())
    }

    private suspend fun ApplicationCall.getOrder() {
        val id = pathId("id")
        respondJson(HttpStatusCode.OK, io { orders.get(id) }.toResponse())
    }

    private suspend fun ApplicationCall.deleteOrder() {
        val id = pathId("id")
        io { orders.delete(actor, id) }
        respond(HttpStatusCode.NoContent)
    }

    private suspend fun ApplicationCall.addItem() {
        val id = pathId("id")
        val request = body<ItemRequest>()
        respondJson(HttpStatusCode.OK, io { orders.addItem(actor, id, request.toInput()) }.toResponse())
    }

    private suspend fun ApplicationCall.updateItem() {
        val orderId = pathId("id")
        val itemId = pathId("item_id")
        val request = body<QuantityRequest>()
        respondJson(
            HttpStatusCode.OK,
            io {
                orders.updateItemQuantity(actor, orderId, itemId, request.quantity)
            }.toResponse(),
        )
    }

    private suspend fun ApplicationCall.removeItem() {
        val orderId = pathId("id")
        val itemId = pathId("item_id")
        respondJson(HttpStatusCode.OK, io { orders.removeItem(actor, orderId, itemId) }.toResponse())
    }

    private suspend fun ApplicationCall.confirmOrder() {
        val id = pathId("id")
        respondJson(HttpStatusCode.OK, io { orders.confirm(actor, id) }.toResponse())
    }

    private suspend fun ApplicationCall.cancelOrder() {
        val id = pathId("id")
        respondJson(HttpStatusCode.OK, io { orders.cancel(actor, id) }.toResponse())
    }

    private suspend fun ApplicationCall.searchOrders(request: SearchRequest) {
        val page = io { orders.search(request) }
        respondJson(HttpStatusCode.OK, page.toResponse { it.toResponse() })
    }
}
