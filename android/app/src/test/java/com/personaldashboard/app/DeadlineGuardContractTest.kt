package com.personaldashboard.app

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class DeadlineGuardContractTest {
    @Test
    fun bindingAfterFinishDoesNotCancelCompletedWork() {
        val cancelled = AtomicInteger()
        val closed = AtomicInteger()
        HostDeadlineGuard.Scope("deadline-contract").use { scope ->
            val guard = scope.arm(1_000) { closed.incrementAndGet() }
            guard.finish()
            guard.bindCancellation { cancelled.incrementAndGet() }
            assertEquals("normal finish must not become expiry", 0, cancelled.get())
            assertEquals(0, closed.get())
        }
    }

    @Test
    fun lateBindWaitsUntilSocketCloseHasReturned() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        HostDeadlineGuard.Scope("deadline-order-contract").use { scope ->
            val guard =
                scope.arm(10) {
                    entered.countDown()
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            try {
                org.junit.Assert.assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                guard.bindCancellation { cancelled.countDown() }
                assertEquals("cancel must follow completed close", 1L, cancelled.count)
            } finally {
                release.countDown()
            }
            org.junit.Assert.assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS))
        }
    }
}
