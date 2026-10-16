package com.ronybrand.orderapi.http

import com.ronybrand.orderapi.app.Condition
import com.ronybrand.orderapi.app.Page
import com.ronybrand.orderapi.app.SearchRequest
import com.ronybrand.orderapi.domain.Customer
import com.ronybrand.orderapi.domain.CustomerInput
import com.ronybrand.orderapi.domain.Item
import com.ronybrand.orderapi.domain.ItemInput
import com.ronybrand.orderapi.domain.Order
import com.ronybrand.orderapi.domain.OrderStatus
import com.ronybrand.orderapi.error.AppException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

// The wire types are separate from the domain types on purpose: the JSON contract (snake_case, money as numbers,
// no personal data on customers) must not change because a domain class did.

/** Strict: unknown keys, trailing data and wrong types are all a decoding error, never silently accepted. */
internal val WireJson =
    Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        encodeDefaults = true
    }

private const val MONEY_SCALE = 2
private val CANONICAL_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

internal fun parseUuid(text: String): UUID? = text.takeIf { CANONICAL_UUID.matches(it) }?.let { UUID.fromString(it) }

internal object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UUID =
        parseUuid(decoder.decodeString()) ?: throw SerializationException("expected a UUID")

    override fun serialize(
        encoder: Encoder,
        value: UUID,
    ) = encoder.encodeString(value.toString())
}

/**
 * Money is read and written as a JSON number with the digits exactly as sent: no float is ever involved. Quoted
 * numbers are refused, and an exponent so large it would take memory to expand is refused by the parse itself.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object MoneySerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
        if (element == null || element.isString) throw SerializationException("expected a number")
        return try {
            BigDecimal(element.content)
        } catch (e: NumberFormatException) {
            throw SerializationException("expected a number", e)
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: BigDecimal,
    ) {
        val literal = JsonUnquotedLiteral(value.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString())
        (encoder as JsonEncoder).encodeJsonElement(literal)
    }
}

// --- requests -----------------------------------------------------------------------------------------------

@Serializable
internal class CustomerRequest(
    val name: String,
    @SerialName("tax_id") val taxId: String,
    @SerialName("passport_number") val passportNumber: String? = null,
    val email: String,
) {
    fun toInput() = CustomerInput(name, taxId, passportNumber, email)
}

@Serializable
internal class ItemRequest(
    val description: String,
    @SerialName("unit_price") @Serializable(MoneySerializer::class) val unitPrice: BigDecimal,
    val quantity: Int,
) {
    fun toInput() = ItemInput(description, unitPrice, quantity)
}

@Serializable
internal class OrderRequest(
    @SerialName("customer_id") @Serializable(UuidSerializer::class) val customerId: UUID,
    val items: List<ItemRequest> = emptyList(),
)

@Serializable
internal class QuantityRequest(val quantity: Int)

// --- responses ----------------------------------------------------------------------------------------------

/** Only id and name: tax id, passport and e-mail are personal data, and no consumer needs them back. */
@Serializable
internal class CustomerResponse(@Serializable(UuidSerializer::class) val id: UUID, val name: String)

internal fun Customer.toResponse() = CustomerResponse(id, name)

@Serializable
internal class ItemResponse(
    @Serializable(UuidSerializer::class) val id: UUID,
    @SerialName("order_id") @Serializable(UuidSerializer::class) val orderId: UUID,
    val description: String,
    @SerialName("unit_price") @Serializable(MoneySerializer::class) val unitPrice: BigDecimal,
    val quantity: Int,
    @Serializable(MoneySerializer::class) val subtotal: BigDecimal,
)

private fun Item.toResponse() = ItemResponse(id, orderId, description, unitPrice, quantity, subtotal)

@Serializable
internal class OrderResponse(
    @Serializable(UuidSerializer::class) val id: UUID,
    @SerialName("customer_id") @Serializable(UuidSerializer::class) val customerId: UUID,
    val status: OrderStatus,
    val version: Long,
    val items: List<ItemResponse>,
    @Serializable(MoneySerializer::class) val total: BigDecimal,
)

internal fun Order.toResponse() = OrderResponse(id, customerId, status, version, items.map { it.toResponse() }, total)

@Serializable
internal class PageResponse<T>(val items: List<T>, val total: Int, val page: Int, val size: Int)

internal fun <D, T> Page<D>.toResponse(convert: (D) -> T) = PageResponse(items.map(convert), total, page, size)

@Serializable
internal class ErrorResponse(
    val message: String,
    val code: String,
    val params: JsonObject,
    @SerialName("request_id") val requestId: String,
)

// --- search body --------------------------------------------------------------------------------------------

private val SEARCH_KEYS = setOf("page", "size", "sort", "filters")
private val CONDITION_KEYS = setOf("op", "value")

private fun bad(message: String): Nothing = throw AppException.invalidField("body", message)

/** Reads the POST form of a search from raw JSON: unknown keys and wrong shapes are VALIDATION-01. */
internal fun parseSearchBody(text: String): SearchRequest {
    val root =
        try {
            WireJson.parseToJsonElement(text)
        } catch (e: SerializationException) {
            bad("request body is not valid JSON")
        } as? JsonObject ?: bad("request body must be a JSON object")
    if (!SEARCH_KEYS.containsAll(root.keys)) bad("request body has unknown fields")
    return SearchRequest(
        page = root["page"]?.let { intOf(it, "page") } ?: 0,
        size = root["size"]?.let { intOf(it, "size") } ?: 0,
        sort =
        root["sort"]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: bad("sort must be a string")
        }
            ?: "",
        filters = root["filters"]?.let { filtersOf(it) } ?: emptyMap(),
    )
}

private fun intOf(
    element: JsonElement,
    field: String,
): Int {
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.isString) bad("$field must be an integer")
    return primitive.content.toIntOrNull() ?: bad("$field must be an integer")
}

private fun filtersOf(element: JsonElement): Map<String, List<Condition>> {
    val filters = element as? JsonObject ?: bad("filters must be an object")
    return filters.mapValues { (_, conditions) ->
        val list = conditions as? JsonArray ?: bad("each filter must be a list of conditions")
        list.map { condition ->
            val obj = condition as? JsonObject ?: bad("each condition must be an object")
            if (!CONDITION_KEYS.containsAll(obj.keys)) bad("a condition has unknown fields")
            val op =
                obj["op"]?.let {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                        ?: bad("op must be a string")
                }
                    ?: ""
            Condition(op, obj["value"]?.let { plainValue(it) })
        }
    }
}

/** A JSON value as plain Kotlin: numbers stay exact BigDecimals, so nothing depends on a float round trip. */
private fun plainValue(element: JsonElement): Any? =
    when (element) {
        is JsonNull -> null
        is JsonPrimitive -> if (element.isString) element.content else numberOrBoolean(element)
        is JsonArray -> element.map { plainValue(it) }
        is JsonObject -> element.mapValues { plainValue(it.value) }
    }

private fun numberOrBoolean(primitive: JsonPrimitive): Any =
    primitive.content.toBooleanStrictOrNull()
        ?: try {
            BigDecimal(primitive.content)
        } catch (e: NumberFormatException) {
            bad("a filter value is not valid")
        }

/** Converts the params of an AppException (plain Kotlin values) to JSON for the error envelope. */
internal fun jsonOf(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to jsonOf(it.value) })
        is Iterable<*> -> JsonArray(value.map { jsonOf(it) })
        else -> JsonPrimitive(value.toString())
    }
