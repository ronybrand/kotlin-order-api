package com.ronybrand.orderapi.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyedLimiterTest {
    private var now = 0L
    private val clock = { now }

    @Test
    fun `a key may spend its whole budget at once and no more`() {
        val limiter = KeyedLimiter(perMinute = 3, nowMillis = clock)

        assertTrue(limiter.allow("a"))
        assertTrue(limiter.allow("a"))
        assertTrue(limiter.allow("a"))
        assertFalse(limiter.allow("a"))
    }

    @Test
    fun `keys do not share a budget`() {
        val limiter = KeyedLimiter(perMinute = 1, nowMillis = clock)

        assertTrue(limiter.allow("a"))
        assertFalse(limiter.allow("a"))
        assertTrue(limiter.allow("b"))
    }

    @Test
    fun `tokens come back at the configured rate`() {
        val limiter = KeyedLimiter(perMinute = 60, nowMillis = clock)
        repeat(60) { assertTrue(limiter.allow("a")) }
        assertFalse(limiter.allow("a"))

        now += 1_000

        assertTrue(limiter.allow("a"), "one second at 60 per minute gives one token back")
        assertFalse(limiter.allow("a"))
    }

    @Test
    fun `the budget never exceeds the burst however long the key was idle`() {
        val limiter = KeyedLimiter(perMinute = 2, nowMillis = clock)
        now += 3_600_000

        assertTrue(limiter.allow("a"))
        assertTrue(limiter.allow("a"))
        assertFalse(limiter.allow("a"))
    }

    @Test
    fun `retry after is the time until one more token`() {
        assertEquals("2", KeyedLimiter(perMinute = 30, nowMillis = clock).retryAfterSeconds())
        assertEquals("60", KeyedLimiter(perMinute = 1, nowMillis = clock).retryAfterSeconds())
        assertEquals("1", KeyedLimiter(perMinute = 120, nowMillis = clock).retryAfterSeconds())
    }

    @Test
    fun `idle keys are forgotten so the map cannot grow forever`() {
        val limiter = KeyedLimiter(perMinute = 5, nowMillis = clock)
        repeat(100) { limiter.allow("key-$it") }
        assertEquals(100, limiter.size())

        now += 10 * 60_000
        limiter.allow("fresh")

        assertEquals(1, limiter.size())
    }
}
