package com.personaldashboard.app

import org.json.JSONException
import org.json.JSONObject

/**
 * App 内 Agent 唯一可用的设置协议。这里只做无 Android 依赖的解析与严格校验， 便于 JUnit 覆盖；实际持久化由 [Config.updateAppControl] 一次
 * commit 完成。
 */
object AppControlPolicy {
    const val MAX_REQUEST_BYTES = 8192

    const val FIELD_KEEP_AWAKE = "keepAwakeWhileRunning"

    sealed class Command {
        object Get : Command()

        data class BotContext(val sessionId: String, val cwd: String) : Command()

        data class BotRoute(
            val sessionId: String,
            val cwd: String,
            val targetId: String?,
            val targetSessionId: String?,
            val admission: String?,
            val deliveryId: String?,
        ) : Command()

        data class Update(val patch: Patch) : Command()
    }

    /** null 表示本次没有提供该字段。 */
    data class Patch(
        val keepAwakeWhileRunning: Boolean? = null,
    )

    data class Settings(
        val keepAwakeWhileRunning: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().put(FIELD_KEEP_AWAKE, keepAwakeWhileRunning)
    }

    class Rejected(message: String) : IllegalArgumentException(message)

    /** 完整校验请求后才返回命令；错误文本不包含请求值。 */
    fun parseRequest(input: String): Command {
        if (input.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) reject("请求超过 8192 字节")
        if (input.any { it == '\n' || it == '\r' }) reject("请求必须是单行 JSON")

        val request =
            try {
                JSONObject(input)
            } catch (_: JSONException) {
                reject("请求不是有效的 JSON 对象")
            }
        val rootKeys = keysOf(request)
        if (!request.has("action")) reject("请求缺少 action")
        val action = request.get("action")
        if (action !is String) reject("action 必须是字符串")

        return when (action) {
            "bot-route" -> {
                val allowed =
                    setOf(
                        "action",
                        "sessionId",
                        "cwd",
                        "targetId",
                        "targetSessionId",
                        "admission",
                        "deliveryId",
                    )
                if (!allowed.containsAll(rootKeys)) reject("Bot 路由字段无效")
                fun field(name: String, limit: Int): String {
                    val value = request.opt(name)
                    if (
                        value !is String ||
                            value.isBlank() ||
                            value.length > limit ||
                            value.any { it.isISOControl() }
                    )
                        reject("Bot 路由标识无效")
                    return value
                }
                val sessionId = field("sessionId", 256)
                val cwd = field("cwd", 4096)
                if (!cwd.startsWith("/")) reject("Bot 工作区无效")
                val target = if (request.has("targetId")) field("targetId", 80) else null
                val targetSession =
                    if (request.has("targetSessionId")) field("targetSessionId", 256) else null
                if (targetSession != null && target == null) reject("回复缺少目标 Bot")
                val admission = if (request.has("admission")) field("admission", 8) else null
                val deliveryId = if (request.has("deliveryId")) field("deliveryId", 64) else null
                if (
                    (admission == null) != (deliveryId == null) ||
                        (admission != null &&
                            (admission !in setOf("begin", "check", "end") ||
                                target == null ||
                                targetSession == null ||
                                !Regex("bm-[0-9a-f]{32}").matches(deliveryId!!)))
                )
                    reject("Bot 派发事务无效")
                Command.BotRoute(sessionId, cwd, target, targetSession, admission, deliveryId)
            }
            "bot-context" -> {
                if (rootKeys != setOf("action", "sessionId", "cwd")) reject("Bot 请求字段无效")
                val sessionId = request.get("sessionId")
                val cwd = request.get("cwd")
                if (
                    sessionId !is String ||
                        sessionId.isBlank() ||
                        sessionId.length > 256 ||
                        sessionId.any { it.isISOControl() }
                )
                    reject("Bot 会话标识无效")
                if (
                    cwd !is String ||
                        !cwd.startsWith("/") ||
                        cwd.length > 4096 ||
                        cwd.any { it.isISOControl() }
                )
                    reject("Bot 工作区无效")
                Command.BotContext(sessionId, cwd)
            }
            "get" -> {
                if (rootKeys != setOf("action")) reject("get 请求包含不支持的字段")
                Command.Get
            }
            "update" -> {
                if (rootKeys != setOf("action", "patch")) reject("update 请求只能包含 action 和 patch")
                val rawPatch = request.get("patch")
                if (rawPatch !is JSONObject) reject("patch 必须是 JSON 对象")
                parsePatch(rawPatch)
            }
            else -> reject("不支持该操作")
        }
    }

    /** Config 的公共写入入口也会调用，防止绕过协议直接构造非法 Patch。 */
    fun validatePatch(patch: Patch) {
        if (patch.keepAwakeWhileRunning == null) {
            reject("更新至少需要一个字段")
        }
    }

    private fun parsePatch(patch: JSONObject): Command.Update {
        val keys = keysOf(patch)
        if (keys.isEmpty()) reject("更新至少需要一个字段")
        if (!ALLOWED_PATCH_FIELDS.containsAll(keys)) reject("请求包含不支持的字段")

        val keepAwake =
            if (patch.has(FIELD_KEEP_AWAKE)) {
                val value = patch.get(FIELD_KEEP_AWAKE)
                if (value !is Boolean) reject("keepAwakeWhileRunning 必须是布尔值")
                value
            } else null

        val result = Patch(keepAwake)
        validatePatch(result)
        return Command.Update(result)
    }

    private fun keysOf(obj: JSONObject): Set<String> =
        LinkedHashSet<String>().apply {
            val keys = obj.keys()
            while (keys.hasNext()) add(keys.next())
        }

    private fun reject(message: String): Nothing = throw Rejected(message)

    private val ALLOWED_PATCH_FIELDS = setOf(FIELD_KEEP_AWAKE)
}
