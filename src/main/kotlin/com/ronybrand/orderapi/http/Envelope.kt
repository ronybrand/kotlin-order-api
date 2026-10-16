package com.ronybrand.orderapi.http

import com.ronybrand.orderapi.error.AppException
import com.ronybrand.orderapi.error.Codes
import com.ronybrand.orderapi.error.ErrorKind
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import java.util.UUID

internal fun statusOf(kind: ErrorKind): HttpStatusCode =
    when (kind) {
        ErrorKind.VALIDATION -> HttpStatusCode.BadRequest
        ErrorKind.NOT_FOUND -> HttpStatusCode.NotFound
        ErrorKind.CONFLICT -> HttpStatusCode.Conflict
        ErrorKind.UNAUTHENTICATED -> HttpStatusCode.Unauthorized
        ErrorKind.FORBIDDEN -> HttpStatusCode.Forbidden
        ErrorKind.TOO_LARGE -> HttpStatusCode.PayloadTooLarge
        ErrorKind.RATE_LIMITED -> HttpStatusCode.TooManyRequests
    }

internal suspend inline fun <reified T> ApplicationCall.respondJson(
    status: HttpStatusCode,
    body: T,
) = respondText(WireJson.encodeToString(body), ContentType.Application.Json, status)

private fun ApplicationCall.requestId(): String = attributes.getOrNull(RequestIdKey) ?: UUID.randomUUID().toString()

/** The error body of the whole API: `{message, code, params, request_id}`. */
internal suspend fun ApplicationCall.respondEnvelope(
    status: HttpStatusCode,
    code: String,
    message: String,
    params: Map<String, Any?> = emptyMap(),
) {
    val json = JsonObject(params.mapValues { jsonOf(it.value) })
    respondJson(status, ErrorResponse(message, code, json, requestId()))
}

internal suspend fun ApplicationCall.respondError(error: AppException) =
    respondEnvelope(statusOf(error.kind), error.code, error.message, error.params)

/** An unexpected failure: the client only ever sees INTERNAL-00, never the underlying message. */
internal suspend fun ApplicationCall.respondInternalError() =
    respondEnvelope(HttpStatusCode.InternalServerError, Codes.INTERNAL, "Internal error")
