package com.ronybrand.orderapi.error

/** Groups errors by the HTTP status family they map to. */
enum class ErrorKind {
    VALIDATION,
    NOT_FOUND,
    CONFLICT,
    UNAUTHENTICATED,
    FORBIDDEN,
    TOO_LARGE,
    RATE_LIMITED,
}

/** The error catalog of the spec (DOMAIN.md section 6). A code is never reused for another meaning. */
object Codes {
    const val INVALID_REQUEST = "VALIDATION-01"
    const val ORDER_NOT_EDITABLE = "VALIDATION-02"
    const val ORDER_EMPTY = "VALIDATION-03"
    const val INVALID_TRANSITION = "VALIDATION-04"
    const val INVALID_FILTER = "VALIDATION-05"
    const val UNKNOWN_SORT = "VALIDATION-06"
    const val CUSTOMER_NOT_FOUND_IN_BODY = "VALIDATION-07"
    const val ALREADY_CANCELED = "VALIDATION-08"
    const val ITEM_LIMIT = "VALIDATION-09"
    const val BODY_TOO_LARGE = "VALIDATION-12"

    // Additions of this implementation: unmatched routes and methods are answered in the same envelope.
    const val METHOD_NOT_ALLOWED = "VALIDATION-13"
    const val ROUTE_NOT_FOUND = "RESOURCE-NOT-FOUND-00"

    const val CUSTOMER_NOT_FOUND = "RESOURCE-NOT-FOUND-01"
    const val ORDER_NOT_FOUND = "RESOURCE-NOT-FOUND-02"
    const val ITEM_NOT_FOUND = "RESOURCE-NOT-FOUND-03"

    const val CONCURRENT_MODIFICATION = "CONFLICT-00"
    const val DUPLICATE_TAX_ID = "CONFLICT-01"
    const val DUPLICATE_PASSPORT = "CONFLICT-02"
    const val CUSTOMER_HAS_ORDERS = "CONFLICT-03"
    const val DATA_INTEGRITY = "CONFLICT-04"

    const val UNAUTHENTICATED = "AUTH-01"
    const val FORBIDDEN = "AUTH-02"

    const val RATE_LIMIT = "RATE-00"
    const val INTERNAL = "INTERNAL-00"
}

/**
 * A failure the client is allowed to see: a stable code, a readable message and optional parameters. Params
 * never carry personal data (tax id, e-mail...): they exist for interpolation, not for debugging.
 *
 * Anything that is not an [AppException] is an unexpected failure and is reported as INTERNAL-00 without its
 * message. The stack trace is not filled in: these are control flow, not bugs, and building one per rejected
 * request is wasted work.
 */
class AppException(
    val kind: ErrorKind,
    val code: String,
    override val message: String,
    val params: Map<String, Any?> = emptyMap(),
) : RuntimeException(message, null, false, false) {
    companion object {
        fun validation(
            code: String,
            message: String,
            params: Map<String, Any?> = emptyMap(),
        ) = AppException(ErrorKind.VALIDATION, code, message, params)

        /** The common VALIDATION-01 for a single offending field. */
        fun invalidField(
            field: String,
            message: String,
        ) = validation(Codes.INVALID_REQUEST, message, mapOf("field" to field))

        fun notFound(
            code: String,
            message: String,
            params: Map<String, Any?> = emptyMap(),
        ) = AppException(ErrorKind.NOT_FOUND, code, message, params)

        fun conflict(
            code: String,
            message: String,
            params: Map<String, Any?> = emptyMap(),
        ) = AppException(ErrorKind.CONFLICT, code, message, params)

        fun unauthenticated() =
            AppException(
                ErrorKind.UNAUTHENTICATED,
                Codes.UNAUTHENTICATED,
                "Authentication required",
            )

        fun forbidden() = AppException(ErrorKind.FORBIDDEN, Codes.FORBIDDEN, "Access denied")

        fun tooLarge(maxBytes: Long) =
            AppException(
                ErrorKind.TOO_LARGE,
                Codes.BODY_TOO_LARGE,
                "Request body too large",
                mapOf("max_bytes" to maxBytes),
            )

        fun rateLimited() = AppException(ErrorKind.RATE_LIMITED, Codes.RATE_LIMIT, "Rate limit exceeded")
    }
}
