package com.ronybrand.orderapi

import com.ronybrand.orderapi.error.AppException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val NOW: Instant = Instant.parse("2026-10-14T12:00:00Z")

/** Runs [block], expects it to fail with the given error code and returns the exception for further checks. */
inline fun assertCode(
    code: String,
    block: () -> Unit,
): AppException {
    val error = assertFailsWith<AppException> { block() }
    assertEquals(code, error.code)
    return error
}
