package com.personaldashboard.app

import java.util.LinkedHashMap

/** Bounded, exact-request cancellation tombstones for cancel-before-request registration. */
internal class PendingRequestCancellations(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val expiresAt = LinkedHashMap<String, Long>()

    @Synchronized
    fun mark(requestId: String): Boolean {
        if (requestId.isEmpty()) return false
        val now = nowNanos()
        prune(now)
        if (expiresAt.containsKey(requestId)) return true
        while (expiresAt.size >= MAX_PENDING_CANCELS) {
            val oldest = expiresAt.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        expiresAt[requestId] = now + PENDING_CANCEL_NANOS
        return true
    }

    @Synchronized
    fun consume(requestId: String): Boolean {
        if (requestId.isEmpty()) return false
        prune(nowNanos())
        return expiresAt.remove(requestId) != null
    }

    @Synchronized
    fun clear() {
        expiresAt.clear()
    }

    @Synchronized
    fun sizeForTest(): Int = expiresAt.size

    private fun prune(now: Long) {
        expiresAt.entries.removeIf { now >= it.value }
    }

    private companion object {
        const val MAX_PENDING_CANCELS = 128
        const val PENDING_CANCEL_NANOS = 120_000L * 1_000_000L
    }
}
