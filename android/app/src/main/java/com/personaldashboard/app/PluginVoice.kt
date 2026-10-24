package com.personaldashboard.app

import android.app.AlertDialog
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Consent, visibility and scope changes share one lock; no hidden page can acquire a late grant.
 */
internal class PluginVoice(private val activity: MainActivity) {
    private val lock = Any()
    private val buffer = AgentVoiceBuffer(SystemClock::elapsedRealtimeNanos)
    private val visible = mutableSetOf<String>()

    private class Pending(val key: String, val id: String) {
        val latch = CountDownLatch(1)
        var accepted = false
        var cancelled = false
        var dialog: AlertDialog? = null
    }

    private val pending = mutableMapOf<String, Pending>()
    // An unsubscribe can reach a cached executor before its subscribe. Bound short-lived tombstones
    // prevent such an explicitly cancelled request from opening a consent dialog afterwards.
    private val cancelled = linkedMapOf<Pair<String, String>, Long>()

    private fun owner(id: String, revision: String) = "$id:$revision"

    private fun cancelLocked(key: String, id: String? = null) {
        pending.values
            .filter { it.key == key && (id == null || it.id == id) }
            .toList()
            .forEach {
                it.cancelled = true
                it.latch.countDown()
                activity.runOnUiThread { it.dialog?.dismiss() }
            }
        buffer.cancel(key, id)
        if (id != null) {
            val now = SystemClock.elapsedRealtime()
            cancelled.entries.removeAll { now - it.value > 60000 }
            cancelled[key to id] = now
            while (cancelled.size > 64) cancelled.remove(cancelled.keys.first())
        }
    }

    fun pageVisible(id: String, revision: String, shown: Boolean) =
        synchronized(lock) {
            val key = owner(id, revision)
            if (shown) visible.add(key)
            else {
                visible.remove(key)
                cancelLocked(key)
            }
            Unit
        }

    fun scope(request: JSONObject) =
        synchronized(lock) {
            val old = buffer.current()
            buffer.scope(request.getString("botId"), request.optString("sessionId"))
            if (old?.toString() != buffer.current()?.toString()) {
                pending.values.map { it.key }.distinct().forEach { cancelLocked(it) }
            }
        }

    fun feed(request: JSONObject, receivedAt: Long) =
        synchronized(lock) {
            buffer.feed(
                request.getString("botId"),
                request.getString("sessionId"),
                request.getJSONObject("frame"),
                receivedAt,
            )
        }

    fun call(
        id: String,
        revision: String,
        request: JSONObject,
        cancellation: PluginCancellation,
    ): JSONObject {
        val key = owner(id, revision)
        val op = request.optString("op")
        val requestId = request.getString(if (op == "subscribe") "requestId" else "id")
        require(requestId.matches(Regex("[0-9a-fA-F-]{36}"))) { "语音订阅标识无效" }
        // Cancellation is always allowed for this exact owner, including a pending dialog.
        if (op == "unsubscribe")
            return synchronized(lock) {
                cancelLocked(key, requestId)
                JSONObject().put("ok", true)
            }
        val store = PluginPlatform.store(activity)
        store.requireCapability(id, "plugin.voice")
        require(store.activeBundle(id).getString("_revision") == revision) { "插件版本已变化" }
        cancellation.check()
        if (op == "poll")
            return synchronized(lock) {
                require(key in visible) { "插件已隐藏" }
                buffer.poll(key, requestId)
            }
        require(op == "subscribe") { "不支持的语音会话操作" }
        val current =
            synchronized(lock) {
                require(key in visible) { "只允许当前可见插件开启语音会话" }
                buffer.current() ?: error("请先选择一个 Bot 对话")
            }
        require(current.optString("sessionId").isNotEmpty()) { "请先开始一个 Bot 对话" }
        val bots =
            BotWorkspaces.get(activity).call(JSONObject().put("op", "list")).getJSONArray("bots")
        val bot =
            (0 until bots.length())
                .map { bots.getJSONObject(it) }
                .firstOrNull { it.getString("id") == current.getString("botId") }
                ?: error("当前 Bot 不存在")
        val sessions = bot.getJSONArray("sessionIds")
        require(
            (0 until sessions.length()).any {
                sessions.getString(it) == current.getString("sessionId")
            },
        ) {
            "当前对话未归属此 Bot"
        }
        val p = Pending(key, requestId)
        synchronized(lock) {
            cancellation.check()
            require(
                key in visible &&
                    (cancelled[key to requestId]?.let {
                        SystemClock.elapsedRealtime() - it < 60000
                    } != true),
            ) {
                "语音会话已取消"
            }
            require(key !in pending) { "已有语音开启请求" }
            pending[key] = p
        }
        val detach = cancellation.onCancel { synchronized(lock) { cancelLocked(key, requestId) } }
        activity.runOnUiThread {
            synchronized(lock) {
                if (p.cancelled || key !in visible) p.latch.countDown()
                else
                    p.dialog =
                        AlertDialog.Builder(activity)
                            .setTitle("开启「${bot.getString("name") }」的语音会话？")
                            .setMessage("允许插件「$id」接收当前 Bot 接下来生成的回复文字。不会读取历史、推理或其他 Bot；离开插件后自动停止。\n\n朗读优先使用系统中文语音，系统语音可能联网；不可用时会使用百度在线语音。使用在线语音时，待朗读文字会发送给对应语音服务。")
                            .setPositiveButton("开启") { _, _ ->
                                synchronized(lock) {
                                    if (!p.cancelled) p.accepted = true
                                    p.latch.countDown()
                                }
                            }
                            .setNegativeButton("取消") { _, _ -> p.latch.countDown() }
                            .setOnCancelListener { p.latch.countDown() }
                            .show()
            }
        }
        try {
            check(p.latch.await(120, TimeUnit.SECONDS)) { "语音确认已超时" }
            return PluginPlatform.withActiveRevision(activity, id, revision) {
                synchronized(lock) {
                    cancellation.check()
                    check(p.accepted && !p.cancelled && key in visible) { "语音会话未开启" }
                    buffer.subscribe(key, current, requestId)
                }
            }
        } finally {
            synchronized(lock) {
                p.cancelled = true
                if (pending[key] === p) pending.remove(key)
            }
            detach()
            activity.runOnUiThread { p.dialog?.dismiss() }
        }
    }
}
