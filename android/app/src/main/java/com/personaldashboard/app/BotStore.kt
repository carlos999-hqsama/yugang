package com.personaldashboard.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistent metadata for fixed bots. Session contents remain owned by the dashboard runtime; this
 * file only records names, pinning, and session mapping.
 */
class BotStore(private val file: File) {
    private var registry: JSONObject

    init {
        registry =
            when {
                file.isFile -> load()
                file.exists() -> throw IOException("bots.json 不是普通文件: $file")
                else -> defaultRegistry()
            }
    }

    @Synchronized
    fun call(input: JSONObject): JSONObject {
        require(input.has(OP)) { "缺少 op" }
        require(input.get(OP) is String) { "op 必须是字符串" }
        return when (input.getString(OP)) {
            LIST -> list()
            CREATE -> create(input)
            RENAME -> rename(input)
            PIN -> pin(input)
            BIND -> bind(input)
            SELECT -> select(input)
            DELETE -> delete(input)
            else -> throw IllegalArgumentException("未知 op: ${input.getString(OP)}")
        }
    }

    private fun list(): JSONObject = copy(registry)

    private fun create(input: JSONObject): JSONObject {
        val id = requiredString(input, ID)
        require(id != DEFAULT_BOT_ID) { "default 是保留机器人 ID" }
        requireBotId(id)
        val name = cleanName(input)
        val avatar = cleanAvatar(input)
        val fish = cleanFish(input, id)
        val color = cleanColor(input)
        val next = copy(registry)
        val existing = findBot(next, id)

        if (existing != null) {
            require(existing.getString(NAME) == name) { "同 ID 机器人已存在且名称不同" }
            require(existing.getString(AVATAR) == avatar) { "同 ID 机器人已存在且头像不同" }
            require(existing.getString(FISH) == fish) { "同 ID 机器人已存在且鱼类图标不同" }
            require(existing.getString(COLOR) == color) { "同 ID 机器人已存在且头像颜色不同" }
            return copy(next)
        }

        next
            .getJSONArray(BOTS)
            .put(
                JSONObject()
                    .put(ID, id)
                    .put(NAME, name)
                    .put(AVATAR, avatar)
                    .put(FISH, fish)
                    .put(COLOR, color)
                    .put(PINNED, false)
                    .put(SESSION_IDS, JSONArray())
                    .put(SELECTED_SESSION_ID, JSONObject.NULL),
            )
        commit(next)
        return copy(next)
    }

    private fun rename(input: JSONObject): JSONObject {
        val next = copy(registry)
        val bot = existingBot(next, requiredString(input, ID))
        bot.put(NAME, cleanName(input))
        if (input.has(AVATAR)) bot.put(AVATAR, cleanAvatar(input))
        if (input.has(FISH)) bot.put(FISH, cleanFish(input, bot.getString(ID)))
        if (input.has(COLOR)) bot.put(COLOR, cleanColor(input))
        commit(next)
        return copy(next)
    }

    private fun delete(input: JSONObject): JSONObject {
        val id = requiredString(input, ID)
        require(id != DEFAULT_BOT_ID) { "默认 Bot 不能删除" }
        require(id != PluginDeveloperBot.ID) { "插件 Bot 是内置身份，不能删除" }

        val next = copy(registry)
        existingBot(next, id)

        val bots = next.getJSONArray(BOTS)
        for (index in 0 until bots.length()) {
            if (bots.getJSONObject(index).getString(ID) == id) {
                bots.remove(index)
                break
            }
        }
        commit(next)
        return copy(next)
    }

    private fun pin(input: JSONObject): JSONObject {
        require(input.has(PINNED)) { "缺少 pinned" }
        val pinnedValue = input.get(PINNED)
        require(pinnedValue is Boolean) { "pinned 必须是布尔值" }

        val next = copy(registry)
        existingBot(next, requiredString(input, ID)).put(PINNED, pinnedValue)
        commit(next)
        return copy(next)
    }

    private fun bind(input: JSONObject): JSONObject {
        val id = requiredString(input, ID)
        val sessionId = cleanSessionId(input)
        val next = copy(registry)
        val bot = existingBot(next, id)
        val owner = findSessionOwner(next, sessionId)

        if (owner != null && owner.getString(ID) != id) {
            require(owner.getString(ID) == DEFAULT_BOT_ID) { "会话已绑定到其他非默认机器人" }

            val defaultSessions = owner.getJSONArray(SESSION_IDS)
            for (index in 0 until defaultSessions.length()) {
                if (defaultSessions.getString(index) == sessionId) {
                    defaultSessions.remove(index)
                    break
                }
            }
            if (selectedSessionId(owner) == sessionId) {
                owner.put(SELECTED_SESSION_ID, JSONObject.NULL)
            }
        }

        if (owner == null || owner.getString(ID) != id) {
            bot.getJSONArray(SESSION_IDS).put(sessionId)
        }
        if (id != DEFAULT_BOT_ID) {
            val default = existingBot(next, DEFAULT_BOT_ID)
            if (selectedSessionId(default) == sessionId)
                default.put(SELECTED_SESSION_ID, JSONObject.NULL)
        }
        bot.put(SELECTED_SESSION_ID, sessionId)
        commit(next)
        return copy(next)
    }

    private fun select(input: JSONObject): JSONObject {
        val id = requiredString(input, ID)
        val sessionId = cleanSessionId(input)
        val next = copy(registry)
        val bot = existingBot(next, id)
        val owner = findSessionOwner(next, sessionId)

        if (id == DEFAULT_BOT_ID) {
            require(owner == null || owner.getString(ID) == DEFAULT_BOT_ID) {
                "默认机器人不能选择其他机器人已绑定的会话"
            }
        } else {
            require(owner?.getString(ID) == id) { "非默认机器人必须先绑定会话" }
        }

        bot.put(SELECTED_SESSION_ID, sessionId)
        commit(next)
        return copy(next)
    }

    private fun commit(next: JSONObject) {
        validateRegistry(next)
        save(next)
        registry = next
    }

    private fun save(next: JSONObject) {
        val parent = file.parentFile ?: throw IOException("bots.json 必须位于目录内: $file")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建 bots.json 所在目录: $parent")
        }

        val temporary = File(parent, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(next.toString(2).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun load(): JSONObject {
        val loaded =
            try {
                JSONObject(file.readText(Charsets.UTF_8))
            } catch (error: JSONException) {
                throw IllegalStateException("bots.json 已损坏", error)
            } catch (error: IOException) {
                throw IllegalStateException("无法读取 bots.json", error)
            }
        validateRegistry(loaded)
        return withDefaultAvatars(loaded)
    }

    private fun defaultRegistry(): JSONObject =
        JSONObject()
            .put(DEFAULT_BOT_ID_FIELD, DEFAULT_BOT_ID)
            .put(
                BOTS,
                JSONArray()
                    .put(
                        JSONObject()
                            .put(ID, DEFAULT_BOT_ID)
                            .put(NAME, DEFAULT_BOT_NAME)
                            .put(AVATAR, DEFAULT_AVATAR)
                            .put(FISH, "whale")
                            .put(COLOR, "ink")
                            .put(PINNED, true)
                            .put(SESSION_IDS, JSONArray())
                            .put(SELECTED_SESSION_ID, JSONObject.NULL),
                    ),
            )

    private fun copy(value: JSONObject): JSONObject = JSONObject(value.toString())

    private fun existingBot(registry: JSONObject, id: String): JSONObject =
        requireNotNull(findBot(registry, id)) { "未知机器人: $id" }

    private fun findBot(registry: JSONObject, id: String): JSONObject? {
        val bots = registry.getJSONArray(BOTS)
        for (index in 0 until bots.length()) {
            val bot = bots.getJSONObject(index)
            if (bot.getString(ID) == id) return bot
        }
        return null
    }

    private fun findSessionOwner(registry: JSONObject, sessionId: String): JSONObject? {
        val bots = registry.getJSONArray(BOTS)
        for (index in 0 until bots.length()) {
            val sessions = bots.getJSONObject(index).getJSONArray(SESSION_IDS)
            for (sessionIndex in 0 until sessions.length()) {
                if (sessions.getString(sessionIndex) == sessionId) return bots.getJSONObject(index)
            }
        }
        return null
    }

    private fun selectedSessionId(bot: JSONObject): String? {
        val value = bot.get(SELECTED_SESSION_ID)
        return if (value == JSONObject.NULL) null else value as? String
    }

    private fun cleanName(input: JSONObject): String {
        val value = requiredString(input, NAME).trim()
        require(value.isNotEmpty()) { "name 不能为空" }
        require(value.none { it.isISOControl() }) { "name 不能包含控制字符" }
        require(value.length <= MAX_NAME_LENGTH) { "name 最长 $MAX_NAME_LENGTH 个字符" }
        return value
    }

    private fun cleanAvatar(input: JSONObject): String {
        if (!input.has(AVATAR)) return DEFAULT_AVATAR
        val value = input.get(AVATAR)
        require(value is String) { "avatar 必须是字符串" }
        require(value in KNOWN_AVATARS) { "不支持的头像形状" }
        return value
    }

    private fun cleanColor(input: JSONObject): String {
        if (!input.has(COLOR)) return "ink"
        val value = input.get(COLOR)
        require(value is String && value in KNOWN_COLORS) { "不支持的头像颜色" }
        return value
    }

    private fun defaultFish(id: String): String =
        when (id) {
            DEFAULT_BOT_ID -> "whale"
            PluginDeveloperBot.ID -> "swordfish"
            else -> "goldfish"
        }

    private fun cleanFish(input: JSONObject, id: String): String {
        if (!input.has(FISH)) return defaultFish(id)
        val value = input.get(FISH)
        require(value is String && value in KNOWN_FISH) { "不支持的鱼类图标" }
        return value
    }

    private fun cleanSessionId(input: JSONObject): String {
        val value = requiredString(input, SESSION_ID)
        require(value.isNotBlank()) { "sessionId 不能为空" }
        require(value.length <= MAX_SESSION_ID_LENGTH) {
            "sessionId 最长 $MAX_SESSION_ID_LENGTH 个字符"
        }
        require(value.none { it.isISOControl() }) { "sessionId 不能包含控制字符" }
        return value
    }

    private fun requireBotId(id: String) {
        require(UUID_PATTERN.matches(id)) { "机器人 ID 必须是 UUID" }
        // Parsing additionally rejects syntactically impossible UUID-shaped strings.
        UUID.fromString(id)
    }

    private fun validateRegistry(registry: JSONObject) {
        requireRegistryString(registry, DEFAULT_BOT_ID_FIELD)
        check(registry.getString(DEFAULT_BOT_ID_FIELD) == DEFAULT_BOT_ID) {
            "bots.json 已损坏: defaultBotId 必须是 default"
        }
        check(registry.has(BOTS) && registry.get(BOTS) is JSONArray) {
            "bots.json 已损坏: bots 必须是数组"
        }

        val bots = registry.getJSONArray(BOTS)
        val botIds = mutableSetOf<String>()
        val sessionIds = mutableSetOf<String>()
        var defaultCount = 0

        for (index in 0 until bots.length()) {
            val bot = bots.optJSONObject(index) ?: error("bots.json 已损坏: bots[${index}] 必须是对象")
            val id = registryString(bot, ID)
            check(id !in botIds) { "bots.json 已损坏: 机器人 ID 重复" }
            botIds += id
            if (id == DEFAULT_BOT_ID) {
                defaultCount += 1
            } else {
                try {
                    requireBotId(id)
                } catch (error: IllegalArgumentException) {
                    throw IllegalStateException("bots.json 已损坏: 非法机器人 ID", error)
                }
            }

            val name = registryString(bot, NAME).trim()
            check(name.isNotEmpty() && name.length <= MAX_NAME_LENGTH) {
                "bots.json 已损坏: 非法 name"
            }
            check(bot.has(PINNED) && bot.get(PINNED) is Boolean) {
                "bots.json 已损坏: pinned 必须是布尔值"
            }
            if (bot.has(AVATAR)) {
                val avatar = bot.get(AVATAR)
                check(avatar is String && avatar in KNOWN_AVATARS) {
                    "bots.json 已损坏: avatar 必须是支持的头像形状"
                }
            }
            if (bot.has(COLOR)) {
                val color = bot.get(COLOR)
                check(color is String && color in KNOWN_COLORS) { "bots.json 已损坏: 不支持的头像颜色" }
            }
            if (bot.has(FISH)) {
                val fish = bot.get(FISH)
                check(fish is String && fish in KNOWN_FISH) { "bots.json 已损坏: 不支持的鱼类图标" }
            }
            check(bot.has(SESSION_IDS) && bot.get(SESSION_IDS) is JSONArray) {
                "bots.json 已损坏: sessionIds 必须是数组"
            }

            val sessions = bot.getJSONArray(SESSION_IDS)
            for (sessionIndex in 0 until sessions.length()) {
                val sessionId =
                    try {
                        sessions.getString(sessionIndex)
                    } catch (error: JSONException) {
                        throw IllegalStateException("bots.json 已损坏: sessionIds 必须是字符串", error)
                    }
                check(sessionId.isNotBlank() && sessionId.length <= MAX_SESSION_ID_LENGTH) {
                    "bots.json 已损坏: 非法 sessionId"
                }
                check(sessionId.none { it.isISOControl() }) {
                    "bots.json 已损坏: sessionId 包含控制字符"
                }
                check(sessionId !in sessionIds) {
                    "bots.json 已损坏: sessionId 重复绑定"
                }
                sessionIds += sessionId
            }

            check(bot.has(SELECTED_SESSION_ID)) {
                "bots.json 已损坏: 缺少 selectedSessionId"
            }
            val selectedValue = bot.get(SELECTED_SESSION_ID)
            check(selectedValue == JSONObject.NULL || selectedValue is String) {
                "bots.json 已损坏: selectedSessionId 必须为 null 或字符串"
            }
            val selected = selectedSessionId(bot)
            if (selected != null) {
                val selectedOwner = findSessionOwner(registry, selected)
                if (id == DEFAULT_BOT_ID) {
                    check(selectedOwner == null || selectedOwner.getString(ID) == DEFAULT_BOT_ID) {
                        "bots.json 已损坏: default 选择了其他机器人会话"
                    }
                } else {
                    check(selectedOwner?.getString(ID) == id) {
                        "bots.json 已损坏: selectedSessionId 未绑定到机器人"
                    }
                }
            }
        }

        check(defaultCount == 1) { "bots.json 已损坏: 必须恰好有一个 default 机器人" }
    }

    private fun withDefaultAvatars(registry: JSONObject): JSONObject {
        val bots = registry.getJSONArray(BOTS)
        for (index in 0 until bots.length()) {
            val bot = bots.getJSONObject(index)
            if (!bot.has(AVATAR)) bot.put(AVATAR, DEFAULT_AVATAR)
            if (!bot.has(FISH)) bot.put(FISH, defaultFish(bot.getString(ID)))
            if (!bot.has(COLOR)) bot.put(COLOR, "ink")
        }
        return registry
    }

    private fun requiredString(input: JSONObject, key: String): String {
        require(input.has(key)) { "缺少 $key" }
        val value = input.get(key)
        require(value is String) { "$key 必须是字符串" }
        return value
    }

    private fun requireRegistryString(value: JSONObject, key: String): String {
        check(value.has(key)) { "bots.json 已损坏: 缺少 $key" }
        val item = value.get(key)
        check(item is String) { "bots.json 已损坏: $key 必须是字符串" }
        return item
    }

    private fun registryString(value: JSONObject, key: String): String {
        check(value.has(key)) { "bots.json 已损坏: 缺少 $key" }
        val item = value.get(key)
        check(item is String) { "bots.json 已损坏: $key 必须是字符串" }
        return item
    }

    private companion object {
        const val BOTS = "bots"
        const val OP = "op"
        const val LIST = "list"
        const val CREATE = "create"
        const val RENAME = "rename"
        const val PIN = "pin"
        const val BIND = "bind"
        const val SELECT = "select"
        const val DELETE = "delete"
        const val ID = "id"
        const val NAME = "name"
        const val AVATAR = "avatar"
        const val FISH = "fish"
        const val COLOR = "color"
        val KNOWN_COLORS =
            setOf(
                "ink",
                "brown",
                "red",
                "orange",
                "amber",
                "green",
                "teal",
                "blue",
                "violet",
                "pink",
                "stone",
                "slate",
            )
        val KNOWN_FISH =
            setOf("whale", "swordfish", "goldfish", "angelfish", "koi", "betta", "puffer", "ray")
        const val PINNED = "pinned"
        const val SESSION_ID = "sessionId"
        const val SESSION_IDS = "sessionIds"
        const val SELECTED_SESSION_ID = "selectedSessionId"
        const val DEFAULT_BOT_ID_FIELD = "defaultBotId"
        const val DEFAULT_BOT_ID = "default"
        const val DEFAULT_BOT_NAME = "主 Bot"
        const val DEFAULT_AVATAR = "circle"
        const val MAX_NAME_LENGTH = 80
        const val MAX_SESSION_ID_LENGTH = 256
        val KNOWN_AVATARS =
            setOf(
                "circle",
                "rounded-square",
                "diamond",
                "pentagon",
                "hexagon",
                "squircle",
                "pebble",
                "bean",
                "clover",
                "flower",
                "droplet",
                "capsule",
            )
        val UUID_PATTERN =
            Regex(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            )
    }
}
