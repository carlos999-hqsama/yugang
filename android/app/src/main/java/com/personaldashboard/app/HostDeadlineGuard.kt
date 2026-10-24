package com.personaldashboard.app

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure JVM total-deadline guard for host service connections.
 *
 * A [Scope] owns at most one daemon scheduler thread (started lazily) and every connection arms one
 * guard. A guard expires at most once: the connection is closed first so a blocked read or write
 * fails, then the cancellation bound by [bindCancellation] runs so the request's real work stops.
 * [finish] disarms a normally completed request and [Scope.close] disarms every live guard when the
 * service stops, so scheduler tasks, threads and socket references never outlive their scope. The
 * helper holds no Android type, keeping deadline and cleanup behavior unit testable on a plain JVM.
 */
class HostDeadlineGuard
private constructor(
    private val scope: Scope,
    private val state: AtomicInteger,
    private val future: ScheduledFuture<*>,
    private val closeConnection: () -> Unit,
) {
    private val cancelWork = AtomicReference<(() -> Unit)?>()
    private val cancellationBound = AtomicBoolean()

    /**
     * Bind the running request's real-work cancellation. Expiry runs it after the connection close;
     * when the guard already expired, it runs immediately on the caller instead. Runs at most once
     * and never after [finish]. Bind once per guard.
     */
    fun bindCancellation(cancel: () -> Unit) {
        check(cancellationBound.compareAndSet(false, true)) { "取消回调只能绑定一次" }
        if (state.get() == FINISHED) return
        cancelWork.set(cancel)
        when (state.get()) {
            FINISHED -> cancelWork.set(null)
            EXPIRED -> cancelWork.getAndSet(null)?.let { runCatching(it) }
        }
    }

    /** Disarm after normal completion; a later expiry becomes a no-op. Idempotent. */
    fun finish() {
        if (state.compareAndSet(ACTIVE, FINISHED)) {
            cancelWork.set(null)
            future.cancel(false)
            scope.live -= this
        }
    }

    private fun expire() {
        if (!state.compareAndSet(ACTIVE, EXPIRING)) return
        scope.live -= this
        runCatching(closeConnection)
        // A late bind must not cancel work until closing has unblocked the socket writer.
        state.set(EXPIRED)
        cancelWork.getAndSet(null)?.let { runCatching(it) }
    }

    private companion object {
        const val ACTIVE = 0
        const val EXPIRING = 1
        const val EXPIRED = 2
        const val FINISHED = 3
    }

    /** One running service instance; [close] releases every live guard and the scheduler. */
    class Scope(threadName: String) : Closeable {
        internal val live = ConcurrentHashMap.newKeySet<HostDeadlineGuard>()
        private val lock = Any()
        private var closed = false
        private val scheduler =
            ScheduledThreadPoolExecutor(1) { r ->
                    Thread(r, threadName).apply { isDaemon = true }
                }
                .apply {
                    removeOnCancelPolicy = true
                    executeExistingDelayedTasksAfterShutdownPolicy = false
                    continueExistingPeriodicTasksAfterShutdownPolicy = false
                }

        /** Scheduled expiries still queued; finished or closed guards leave none behind. */
        internal val queuedExpiries: Int
            get() = scheduler.queue.size

        /**
         * Arm a guard that closes the connection via [closeConnection] once after [timeoutMs].
         *
         * @throws RejectedExecutionException the scope is closed; fail the connection instead.
         */
        fun arm(timeoutMs: Long, closeConnection: () -> Unit): HostDeadlineGuard {
            require(timeoutMs > 0L) { "期限必须为正数" }
            val state = AtomicInteger(ACTIVE)
            synchronized(lock) {
                if (closed) throw RejectedExecutionException("宿主期限守卫已停止")
                // The callback takes this lock before reading self: even a delayed arming
                // thread cannot let the scheduled expiry run before guard publication.
                val self = AtomicReference<HostDeadlineGuard?>()
                val future =
                    scheduler.schedule(
                        { synchronized(lock) { self.get() }?.expire() },
                        timeoutMs,
                        TimeUnit.MILLISECONDS,
                    )
                val guard = HostDeadlineGuard(this, state, future, closeConnection)
                self.set(guard)
                live += guard
                return guard
            }
        }

        /** Disarm every live guard and stop the scheduler thread. Idempotent. */
        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
            }
            live.toList().forEach { it.finish() }
            scheduler.shutdownNow()
        }
    }
}
