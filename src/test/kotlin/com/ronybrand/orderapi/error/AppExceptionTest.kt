package com.ronybrand.orderapi.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppExceptionTest {
    @Test
    fun `each factory sets the kind and the catalog code`() {
        assertEquals(ErrorKind.VALIDATION, AppException.validation(Codes.ORDER_EMPTY, "m").kind)
        assertEquals(ErrorKind.NOT_FOUND, AppException.notFound(Codes.ORDER_NOT_FOUND, "m").kind)
        assertEquals(ErrorKind.CONFLICT, AppException.conflict(Codes.CUSTOMER_HAS_ORDERS, "m").kind)
        assertEquals(ErrorKind.UNAUTHENTICATED, AppException.unauthenticated().kind)
        assertEquals(Codes.UNAUTHENTICATED, AppException.unauthenticated().code)
        assertEquals(ErrorKind.FORBIDDEN, AppException.forbidden().kind)
        assertEquals(Codes.FORBIDDEN, AppException.forbidden().code)
        assertEquals(ErrorKind.RATE_LIMITED, AppException.rateLimited().kind)
        assertEquals(Codes.RATE_LIMIT, AppException.rateLimited().code)
    }

    @Test
    fun `invalid field is VALIDATION-01 naming the field`() {
        val error = AppException.invalidField("tax_id", "tax_id has an invalid format")

        assertEquals(Codes.INVALID_REQUEST, error.code)
        assertEquals("tax_id", error.params["field"])
    }

    @Test
    fun `too large carries the limit`() {
        val error = AppException.tooLarge(1024)

        assertEquals(ErrorKind.TOO_LARGE, error.kind)
        assertEquals(Codes.BODY_TOO_LARGE, error.code)
        assertEquals(1024L, error.params["max_bytes"])
    }

    @Test
    fun `the message is what the client sees and the stack trace is not filled in`() {
        val error = AppException.notFound(Codes.CUSTOMER_NOT_FOUND, "customer not found")

        assertEquals("customer not found", error.message)
        assertEquals(0, error.stackTrace.size)
    }

    @Test
    fun `params default to empty and never to null`() {
        assertTrue(AppException.forbidden().params.isEmpty())
        assertNull(AppException.forbidden().cause)
    }

    @Test
    fun `codes are never reused for another meaning`() {
        val all =
            Codes::class.java.declaredFields.filter { it.type == String::class.java }.map {
                it.get(
                    null,
                ) as String
            }

        assertEquals(all.size, all.toSet().size)
    }
}
