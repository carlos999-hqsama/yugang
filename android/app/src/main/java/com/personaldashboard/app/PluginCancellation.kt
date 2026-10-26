package com.personaldashboard.app

/** Cooperative native cancellation, shared by the invocation, process and SDK calls. */
class PluginCancellation {
    @Volatile
    var cancelled = false
        private set

    private val hooks = mutableListOf<() -> Unit>()

    fun cancel() {
        val pending = synchronized(this) {
            if (cancelled) return
            cancelled = true
            hooks.toList().also { hooks.clear() }
        }
        // A child hook may detach from this parent. Never run external code under this lock.
        pending.forEach { runCatching(it) }
    }

    fun onCancel(hook: () -> Unit): () -> Unit {
        val invokeNow = synchronized(this) {
            if (cancelled) true else { hooks += hook;false }
        }
        if (invokeNow) hook()
        return { synchronized(this) { hooks.remove(hook); Unit } }
    }

    fun check() {
        check(!cancelled && !Thread.currentThread().isInterrupted) { "插件操作已取消；已被远端接收的操作需核对" }
    }
}
