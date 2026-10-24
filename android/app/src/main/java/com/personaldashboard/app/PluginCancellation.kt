package com.personaldashboard.app

/** Cooperative native cancellation, shared by the invocation, process and SDK calls. */
class PluginCancellation {
    @Volatile
    var cancelled = false
        private set

    private val hooks = mutableListOf<() -> Unit>()

    @Synchronized
    fun cancel() {
        if (cancelled) return
        cancelled = true
        val pending = hooks.toList()
        hooks.clear()
        pending.forEach { runCatching(it) }
    }

    @Synchronized
    fun onCancel(hook: () -> Unit): () -> Unit {
        if (cancelled) hook() else hooks += hook
        return { synchronized(this) { hooks.remove(hook); Unit } }
    }

    fun check() {
        check(!cancelled && !Thread.currentThread().isInterrupted) { "插件操作已取消；已被远端接收的操作需核对" }
    }
}
