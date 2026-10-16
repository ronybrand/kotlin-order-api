package com.ronybrand.orderapi.http

private const val MILLIS_PER_MINUTE = 60_000.0
private const val SECONDS_PER_MINUTE = 60
private const val IDLE_TTL_MILLIS = 2 * 60_000L
private const val PURGE_INTERVAL_MILLIS = 60_000L

/**
 * One token bucket per key (user, client address): [perMinute] requests per minute, with a burst of the same size.
 * Idle keys are dropped so the map cannot grow forever.
 */
class KeyedLimiter(private val perMinute: Int, private val nowMillis: () -> Long) {
    private class Bucket(var tokens: Double, var lastRefill: Long)

    private val buckets = HashMap<String, Bucket>()
    private var lastPurge = nowMillis()

    @Synchronized
    fun allow(key: String): Boolean {
        val now = nowMillis()
        purgeIdle(now)
        val bucket = buckets.getOrPut(key) { Bucket(perMinute.toDouble(), now) }
        bucket.tokens =
            minOf(perMinute.toDouble(), bucket.tokens + (now - bucket.lastRefill) * perMinute / MILLIS_PER_MINUTE)
        bucket.lastRefill = now
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    /** How long until one more token is available, for the Retry-After header. */
    fun retryAfterSeconds(): String = ((SECONDS_PER_MINUTE + perMinute - 1) / perMinute).toString()

    @Synchronized
    fun size() = buckets.size

    private fun purgeIdle(now: Long) {
        if (now - lastPurge <= PURGE_INTERVAL_MILLIS) return
        buckets.entries.removeIf { now - it.value.lastRefill > IDLE_TTL_MILLIS }
        lastPurge = now
    }
}
