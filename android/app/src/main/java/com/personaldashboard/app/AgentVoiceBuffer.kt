package com.personaldashboard.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Only live, consented plain assistant text. No event log, reasoning, tools or historical replay.
 */
internal class AgentVoiceBuffer(private val clock: () -> Long) {
    private data class Scope(val bot: String, val session: String)

    private data class Subscription(
        val owner: String,
        val scope: Scope,
        val grantedAt: Long,
        val queue: MutableList<JSONObject> = mutableListOf(),
        var attempt: String? = null,
        var index: Int = 0,
        var sentence: String = "",
        var firstTokenAt: Long? = null,
        var firstSentenceAt: Long? = null,
    )

    private var scope: Scope? = null
    private val subscriptions = linkedMapOf<String, Subscription>()

    @Synchronized
    fun scope(bot: String, session: String) {
        val next = Scope(bot, session)
        if (next != scope) subscriptions.clear()
        scope = next
    }

    @Synchronized
    fun current(): JSONObject? = scope?.let {
        JSONObject().put("botId", it.bot).put("sessionId", it.session)
    }

    @Synchronized
    fun subscribe(
        owner: String,
        expected: JSONObject,
        id: String = java.util.UUID.randomUUID().toString(),
    ): JSONObject {
        val selected = scope ?: error("请先选择一个 Bot 对话")
        require(
            selected.bot == expected.getString("botId") &&
                selected.session == expected.getString("sessionId"),
        ) {
            "当前 Bot 已变化，请重新开启语音会话"
        }
        subscriptions.entries.removeAll { it.value.owner == owner }
        require(id !in subscriptions) { "语音订阅标识已使用" }
        subscriptions[id] = Subscription(owner, selected, clock())
        return JSONObject()
            .put("id", id)
            .put("botId", selected.bot)
            .put("sessionId", selected.session)
            .put("clock", "elapsedRealtimeMs")
    }

    @Synchronized fun active(): Boolean = subscriptions.isNotEmpty()

    @Synchronized
    fun cancel(owner: String, id: String? = null) {
        subscriptions.entries.removeAll { it.value.owner == owner && (id == null || it.key == id) }
    }

    @Synchronized
    fun poll(owner: String, id: String): JSONObject {
        val s = subscriptions[id]?.takeIf { it.owner == owner } ?: error("语音订阅已关闭")
        val events = JSONArray(s.queue.toList())
        s.queue.clear()
        return JSONObject().put("id", id).put("events", events)
    }

    @Synchronized
    fun feed(bot: String, session: String, frame: JSONObject, receivedAt: Long = clock()) {
        if (scope != Scope(bot, session)) return
        val at = receivedAt / 1_000_000
        for (s in subscriptions.values) {
            if (receivedAt < s.grantedAt) continue
            when (frame.getString("type")) {
                "start" -> {
                    s.attempt = frame.getString("attemptId")
                    s.index = 0
                    s.sentence = ""
                    s.firstTokenAt = null
                    s.firstSentenceAt = null
                }
                "text" -> {
                    if (s.attempt != frame.optString("attemptId")) continue
                    val text = frame.getString("text")
                    require(text.length <= 32768) { "语音文本块过大" }
                    if (text.isEmpty()) continue
                    if (s.firstTokenAt == null) s.firstTokenAt = at
                    enqueue(
                        s,
                        JSONObject()
                            .put("type", "token")
                            .put("text", text)
                            .put("index", s.index++)
                            .put("at", at)
                            .put("attemptId", s.attempt)
                            .put("firstTokenAt", s.firstTokenAt),
                    )
                    if (s.attempt == null) continue
                    for (point in text.codePoints().toArray()) {
                        if (s.attempt == null) break
                        s.sentence += String(Character.toChars(point))
                        if (
                            point in "。！？!?；;\n".codePoints().toArray() ||
                                (s.sentence.codePointCount(0, s.sentence.length) >= 160 &&
                                    !Character.isHighSurrogate(point.toChar()))
                        )
                            sentence(s, at)
                    }
                }
                "end" -> {
                    if (s.attempt != frame.optString("attemptId")) continue
                    sentence(s, at)
                    enqueue(
                        s,
                        JSONObject()
                            .put("type", "end")
                            .put("at", at)
                            .put("attemptId", s.attempt)
                            .put("firstTokenAt", s.firstTokenAt ?: JSONObject.NULL)
                            .put("firstSentenceAt", s.firstSentenceAt ?: JSONObject.NULL),
                    )
                    s.attempt = null
                }
                "error" -> {
                    if (s.attempt == null) continue
                    enqueue(
                        s,
                        JSONObject().put("type", "error").put("error", "当前回复流已中断").put("at", at),
                    )
                    s.attempt = null
                    s.sentence = ""
                }
                else -> error("不支持的语音帧")
            }
        }
    }

    private fun sentence(s: Subscription, at: Long) {
        val text = s.sentence.trim()
        s.sentence = ""
        if (text.isEmpty()) return
        if (s.firstSentenceAt == null) s.firstSentenceAt = at
        enqueue(
            s,
            JSONObject()
                .put("type", "sentence")
                .put("text", text)
                .put("at", at)
                .put("attemptId", s.attempt)
                .put("firstTokenAt", s.firstTokenAt)
                .put("firstSentenceAt", s.firstSentenceAt),
        )
    }

    private fun enqueue(s: Subscription, event: JSONObject) {
        if (
            s.queue.size >= 256 ||
                s.queue.sumOf { it.toString().length } + event.toString().length > 65536
        ) {
            s.queue.clear()
            s.queue.add(
                JSONObject()
                    .put("type", "error")
                    .put("error", "语音消费者过慢，订阅已停止接收当前回复")
                    .put("at", clock() / 1_000_000),
            )
            s.attempt = null
            s.sentence = ""
        } else s.queue.add(event)
    }
}
