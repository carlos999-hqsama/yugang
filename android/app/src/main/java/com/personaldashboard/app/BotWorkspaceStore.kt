package com.personaldashboard.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Owns per-bot workspace files and shared persona settings while delegating the bot registry to
 * [BotStore]. The caller keeps one instance per process.
 */
class BotWorkspaceStore(
    private val filesDir: File,
    private val personaSeed: String,
    private val admissionClock: () -> Long = System::nanoTime,
) {
    private val botStore = BotStore(File(filesDir, BOTS_FILE))

    private data class MessageAdmission(
        val sender: String,
        val target: String,
        val sessionId: String,
        val until: Long,
    )

    private data class LearnedPersona(
        val revision: String,
        val traits: Map<String, String>,
        val evidenceCount: Int,
        val updatedAt: String,
    )

    private val messageAdmissions = mutableMapOf<Pair<String, String>, MessageAdmission>()

    @Synchronized
    fun call(input: JSONObject): JSONObject {
        requireSafeExistingRoot()
        BotDirectoryLayout.prepare(filesDir)
        require(input.has(OP)) { "缺少 op" }
        val op = requiredString(input, OP)
        return when (op) {
            LIST,
            CREATE -> {
                requireSafeExistingRoot()
                val result = withWorkspace(botStore.call(input))
                ensureSharedPersona()
                ensureRegisteredWorkspaces(result)
                result
            }

            RENAME,
            PIN,
            BIND,
            SELECT -> {
                requireSafeExistingRoot()
                withWorkspace(botStore.call(input))
            }

            SETTINGS -> settings(input)
            SAVE_SETTINGS -> saveSettings(input)
            PERSONA -> persona(input)
            SAVE_PERSONA -> savePersona(input)
            LEARNING -> learning(input)
            CONTEXT -> context(input)
            "messageRoute" -> messageRoute(input)
            DELETE -> delete(input)
            else -> throw IllegalArgumentException("未知 op: $op")
        }
    }

    /** Resolves only registered Bot identities. Message bodies remain in the existing DSH queue. */
    private fun messageRoute(input: JSONObject): JSONObject {
        requireOnlyFields(
            input,
            OP,
            SESSION_ID,
            CWD,
            "targetId",
            "targetSessionId",
            "admission",
            "deliveryId",
        )
        require(input.has("admission") == input.has("deliveryId")) { "Bot 派发事务字段不完整" }
        val sender =
            context(
                JSONObject()
                    .put(OP, CONTEXT)
                    .put(SESSION_ID, requiredString(input, SESSION_ID))
                    .put(CWD, requiredString(input, CWD)),
            )
        val senderBot = requireKnownBot(sender.getString(ID))
        fun identity(bot: JSONObject) =
            JSONObject().put(ID, bot.getString(ID)).put("name", bot.getString("name"))
        val result =
            JSONObject()
                .put(
                    "sender",
                    identity(senderBot).put(SESSION_IDS, sender.getJSONArray(SESSION_IDS)),
                )
        if (!input.has("targetId")) {
            require(!input.has("targetSessionId")) { "回复缺少目标 Bot" }
            require(!input.has("admission") && !input.has("deliveryId")) { "派发事务缺少目标 Bot" }
            val all = botStore.call(JSONObject().put(OP, LIST)).getJSONArray(BOTS)
            val directory = JSONArray()
            for (i in 0 until all.length()) directory.put(identity(all.getJSONObject(i)))
            return result.put(BOTS, directory)
        }
        val targetId = requiredString(input, "targetId")
        require(
            (0 until sender.getJSONArray(SESSION_IDS).length()).any {
                sender.getJSONArray(SESSION_IDS).getString(it) == requiredString(input, SESSION_ID)
            },
        ) {
            "Bot 消息须由已绑定的 Bot 对话发出，子会话请先向所属 Bot 汇报"
        }
        require(targetId != sender.getString(ID)) { "不能给自己发送 Bot 消息" }
        val target = requireKnownBot(targetId)
        val sessions = target.getJSONArray(SESSION_IDS)
        val sessionId =
            if (input.has("targetSessionId")) {
                requiredString(input, "targetSessionId").also { requested ->
                    require(
                        (0 until sessions.length()).any { sessions.getString(it) == requested },
                    ) {
                        "回复会话不属于目标 Bot"
                    }
                }
            } else {
                val selected =
                    target.optString("selectedSessionId").takeIf { it.isNotBlank() && it != "null" }
                selected
                    ?: if (sessions.length() > 0) sessions.getString(sessions.length() - 1)
                    else
                        "session-${UUID.randomUUID()}"
                            .also { reserved ->
                                botStore.call(
                                    JSONObject()
                                        .put(OP, BIND)
                                        .put(ID, targetId)
                                        .put(SESSION_ID, reserved),
                                )
                            }
            }
        ensureWorkspace(targetId)
        messageAdmissions.entries.removeAll { admissionClock() - it.value.until >= 0 }
        if (input.has("admission")) {
            val deliveryId = requiredString(input, "deliveryId")
            require(Regex("bm-[0-9a-f]{32}").matches(deliveryId)) { "Bot 消息身份无效" }
            val key = requiredString(input, SESSION_ID) to deliveryId
            val current = messageAdmissions[key]
            when (requiredString(input, "admission")) {
                "begin" -> {
                    require(
                        current == null ||
                            (current.target == targetId && current.sessionId == sessionId),
                    ) {
                        "派发目标已变化"
                    }
                    messageAdmissions[key] =
                        MessageAdmission(
                            sender.getString(ID),
                            targetId,
                            sessionId,
                            admissionClock() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5),
                        )
                }
                "check",
                "end" -> {
                    require(
                        current != null &&
                            current.sender == sender.getString(ID) &&
                            current.target == targetId &&
                            current.sessionId == sessionId,
                    ) {
                        "Bot 派发事务已结束或过期"
                    }
                    if (input.getString("admission") == "end") messageAdmissions.remove(key)
                }
                else -> error("未知 Bot 派发事务")
            }
        }
        return result.put(
            "target",
            identity(target).put("workspace", "$BOTS_ROOT/$targetId").put(SESSION_ID, sessionId),
        )
    }

    private fun settings(input: JSONObject): JSONObject {
        requireOnlyFields(input, OP, ID)
        val id = requiredString(input, ID)
        requireKnownBot(id)
        ensureWorkspace(id)
        val persona = ensureSharedPersona()
        return settingsResponse(
            id = id,
            config = readConfig(id),
            memory = readMemory(id),
            persona = persona,
        )
    }

    private fun saveSettings(input: JSONObject): JSONObject {
        requireOnlyFields(input, OP, ID, INSTRUCTIONS, MEMORY, ENABLED_TOOLS, COMPACTION_THRESHOLD)
        val id = requiredString(input, ID)
        val instructions = requiredString(input, INSTRUCTIONS)
        require(instructions.length <= MAX_INSTRUCTIONS_LENGTH) {
            "instructions 最长 $MAX_INSTRUCTIONS_LENGTH 个字符"
        }
        val memory = requiredString(input, MEMORY)
        require(memory.length <= MAX_MEMORY_LENGTH) {
            "memory 最长 $MAX_MEMORY_LENGTH 个字符"
        }
        val tools = requiredTools(input)
        requireKnownBot(id)

        ensureWorkspace(id)
        val persona = ensureSharedPersona()
        val previousConfigJson = readConfig(id)
        val compactionThreshold =
            if (input.has(COMPACTION_THRESHOLD)) {
                cleanCompactionThreshold(input)
            } else {
                previousConfigJson.optDouble(COMPACTION_THRESHOLD, DEFAULT_COMPACTION_THRESHOLD)
            }
        val config =
            JSONObject()
                .put(INSTRUCTIONS, instructions)
                .put(ENABLED_TOOLS, toolsToJson(tools))
                .put(COMPACTION_THRESHOLD, compactionThreshold)

        val configFile = configPath(id)
        val memoryFile = memoryPath(id)
        val previousConfig = readText(configFile, "bot-config.json 无法读取", 100_000)
        atomicWrite(configFile, config.toString(2), replaceExisting = true)
        try {
            atomicWrite(memoryFile, memory, replaceExisting = true)
        } catch (failure: Exception) {
            // Calls share this monitor, so runtime readers never observe a partial save.
            try {
                atomicWrite(configFile, previousConfig, replaceExisting = true)
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
        return settingsResponse(id, config, memory, persona)
    }

    private fun persona(input: JSONObject): JSONObject {
        requireOnlyFields(input, OP)
        return JSONObject().put(TEXT, ensureSharedPersona())
    }

    private fun savePersona(input: JSONObject): JSONObject {
        requireOnlyFields(input, OP, TEXT)
        val text = requiredString(input, TEXT)
        require(text.length <= MAX_PERSONA_LENGTH) {
            "text 最长 $MAX_PERSONA_LENGTH 个字符"
        }
        ensureSharedPersona()
        atomicWrite(personaPath(), text, replaceExisting = true)
        return JSONObject().put(TEXT, text)
    }

    /**
     * Accepts one structured candidate from the trusted same-UID private adapter.
     *
     * Core persists and projects the candidate; it does not inspect memories or call a model here.
     */
    private fun learning(input: JSONObject): JSONObject {
        requireOnlyFields(input, OP, PROFILE)
        val candidate = cleanLearnedPersona(requiredProfile(input))
        val path = learnedPersonaPath()
        val applied = if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            atomicWrite(path, learnedPersonaJson(candidate).toString(2), replaceExisting = false)
            true
        } else {
            val existing = readLearnedPersona()
            if (existing.revision == candidate.revision) {
                require(existing == candidate) { "learning revision 已用于不同候选" }
                false
            } else {
                atomicWrite(path, learnedPersonaJson(candidate).toString(2), replaceExisting = true)
                true
            }
        }
        return learningResponse(applied, candidate.revision)
    }

    private fun context(input: JSONObject): JSONObject {
        requireOnlyFields(input, OP, SESSION_ID, CWD)
        val sessionId = requiredString(input, SESSION_ID)
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        require(sessionId.length <= MAX_SESSION_ID_LENGTH) {
            "sessionId 最长 $MAX_SESSION_ID_LENGTH 个字符"
        }
        require(sessionId.none { it.isISOControl() }) {
            "sessionId 不能包含控制字符"
        }
        val cwd = requiredString(input, CWD)
        require(cwd.isNotBlank()) { "cwd 不能为空" }
        require(cwd.none { it.isISOControl() }) { "cwd 不能包含控制字符" }

        val registry = botStore.call(JSONObject().put(OP, LIST))
        val bots = registry.getJSONArray(BOTS)
        val owner = findSessionOwner(bots, sessionId)
        val cwdOwner = resolveCwdBot(bots, cwd).getString(ID)
        val id = owner?.getString(ID) ?: cwdOwner
        require(id == cwdOwner) { "会话与工作区归属不一致" }

        ensureWorkspace(id)
        val persona = ensureSharedPersona()
        val learned = readLearnedPersonaIfExists()
        val learnedPersona = learned?.let(::learnedPersonaText)
        val sessionIds = JSONArray()
        val excludedSessionIds = JSONArray()
        for (index in 0 until bots.length()) {
            val bot = bots.getJSONObject(index)
            val target = if (bot.getString(ID) == id) sessionIds else excludedSessionIds
            val mapped = bot.getJSONArray(SESSION_IDS)
            for (sessionIndex in 0 until mapped.length()) {
                target.put(mapped.getString(sessionIndex))
            }
        }

        val response = settingsResponse(
                id = id,
                config = readConfig(id),
                memory = readMemory(id),
                persona = persona,
            )
            .put(SESSION_IDS, sessionIds)
            .put(EXCLUDED_SESSION_IDS, excludedSessionIds)
            .put(LEGACY_MEMORY, id == DEFAULT_BOT_ID)
            .put(DEFAULT_PERSONA, personaSeed)
        if (learnedPersona != null) {
            response
                .put(LEARNED_PERSONA, learnedPersona)
                .put(LEARNED_PERSONA_REVISION, requireNotNull(learned).revision)
        }
        return response
    }

    private fun settingsResponse(
        id: String,
        config: JSONObject,
        memory: String,
        persona: String,
    ): JSONObject =
        JSONObject()
            .put(ID, id)
            .put(WORKSPACE, virtualWorkspace(id))
            .put(INSTRUCTIONS, config.getString(INSTRUCTIONS))
            .put(MEMORY, memory)
            .put(ENABLED_TOOLS, toolsToJson(config.getJSONArray(ENABLED_TOOLS)))
            .put(
                COMPACTION_THRESHOLD,
                config.optDouble(COMPACTION_THRESHOLD, DEFAULT_COMPACTION_THRESHOLD),
            )
            .put(SHARED_PERSONA, persona)

    private fun withWorkspace(response: JSONObject): JSONObject {
        val bots = response.getJSONArray(BOTS)
        for (index in 0 until bots.length()) {
            val bot = bots.getJSONObject(index)
            bot.put(WORKSPACE, virtualWorkspace(bot.getString(ID)))
            if (!bot.has(AVATAR)) bot.put(AVATAR, DEFAULT_AVATAR)
        }
        return response
    }

    private fun delete(input: JSONObject): JSONObject {
        requireOnlyFields(input, OP, ID, CONFIRM_DELETE)
        val id = requiredString(input, ID)
        messageAdmissions.entries.removeAll { admissionClock() - it.value.until >= 0 }
        require(messageAdmissions.values.none { it.sender == id || it.target == id }) {
            "这个 Bot 正在交接消息，请稍后再删除"
        }
        require(input.has(CONFIRM_DELETE)) { "缺少 $CONFIRM_DELETE" }
        val confirmation = input.get(CONFIRM_DELETE)
        require(confirmation is Boolean && confirmation) { "必须确认删除 Bot" }

        requireKnownBot(id)
        require(id != DEFAULT_BOT_ID && id != PluginDeveloperBot.ID) { "内置 Bot 不能删除" }
        val workspace = archiveWorkspace(id)
        val result =
            try {
                botStore.call(JSONObject().put(OP, DELETE).put(ID, id))
            } catch (registryFailure: Exception) {
                val rollback = runCatching {
                    Files.move(workspace.archive, workspace.source, StandardCopyOption.ATOMIC_MOVE)
                }
                if (rollback.isFailure) {
                    val failure = IOException("注册表删除失败且工作区回滚失败")
                    registryFailure.addSuppressed(
                        rollback.exceptionOrNull() ?: IOException("未知回滚错误"),
                    )
                    throw failure
                }
                throw registryFailure
            }
        return withWorkspace(result)
    }

    private class ArchivedWorkspace(val source: Path, val archive: Path)

    private fun archiveWorkspace(id: String): ArchivedWorkspace {
        val source =
            safeFile(*(workspaceComponents(id) + CONFIG_FILE)).parent
                ?: throw IOException("无法定位 Bot 工作区")
        if (Files.isSymbolicLink(source)) throw IOException("Bot 工作区不能是符号链接")
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Bot 工作区不是目录")
        }

        val archiveParent = ensureDirectory(BOT_WORKSPACES_DIR, DELETED_WORKSPACES_DIR)
        val archive = archiveParent.resolve(id)
        requireInsideRoot(archive)
        if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("已存在同 ID 的删除归档")
        }

        Files.move(source, archive, StandardCopyOption.ATOMIC_MOVE)
        return ArchivedWorkspace(source, archive)
    }

    private fun ensureRegisteredWorkspaces(response: JSONObject) {
        val bots = response.getJSONArray(BOTS)
        for (index in 0 until bots.length()) {
            ensureWorkspace(bots.getJSONObject(index).getString(ID))
        }
    }

    private fun ensureWorkspace(id: String) {
        val components = workspaceComponents(id)
        ensureDirectory(*components)

        val configFile = configPath(id)
        val configExists = Files.exists(configFile, LinkOption.NOFOLLOW_LINKS)
        if (configExists) readConfig(id)

        ensureDirectory(*(components + "documents"))
        ensureDirectory(*(components + MEMORY_DIR))
        ensureDirectory(*(components + ARCHIVES_DIR))
        ensureDirectory(*(components + SKILLS_DIR))
        val memoryFile = memoryPath(id)

        if (!configExists) {
            atomicWrite(configFile, defaultConfig().toString(2), replaceExisting = false)
        }
        if (!Files.exists(memoryFile, LinkOption.NOFOLLOW_LINKS)) {
            atomicWrite(memoryFile, "", replaceExisting = false)
        }
    }

    private fun readConfig(id: String): JSONObject {
        val raw = readText(configPath(id), "bot-config.json 无法读取", 100_000)
        val parsed =
            try {
                JSONObject(raw)
            } catch (_: JSONException) {
                error("bot-config.json 配置无效")
            }
        validateStoredConfig(parsed)
        return parsed
    }

    private fun validateStoredConfig(config: JSONObject) {
        if (
            (config.length() != 2 && config.length() != 3) ||
                !config.has(INSTRUCTIONS) ||
                !config.has(ENABLED_TOOLS) ||
                (config.length() == 3 && !config.has(COMPACTION_THRESHOLD))
        ) {
            error("bot-config.json 配置无效")
        }
        val instructions = config.opt(INSTRUCTIONS)
        if (instructions !is String || instructions.length > MAX_INSTRUCTIONS_LENGTH) {
            error("bot-config.json 配置无效")
        }
        val enabledTools = config.opt(ENABLED_TOOLS)
        if (enabledTools !is JSONArray) error("bot-config.json 配置无效")
        val seen = mutableSetOf<String>()
        for (index in 0 until enabledTools.length()) {
            val tool = enabledTools.opt(index)
            if (tool !is String || tool !in KNOWN_TOOLS || !seen.add(tool)) {
                error("bot-config.json 配置无效")
            }
        }
        if (config.has(COMPACTION_THRESHOLD)) cleanStoredCompactionThreshold(config)
    }

    private fun readMemory(id: String): String =
        readText(memoryPath(id), "MEMORY.md 无法读取", MAX_MEMORY_LENGTH)

    private fun ensureSharedPersona(): String {
        if (readPersonaMigrationVersion() < PERSONA_MIGRATION_VERSION) migrateSharedPersona()
        return readText(personaPath(), "PERSONA.md 无法读取", MAX_PERSONA_LENGTH)
    }

    private fun migrateSharedPersona() {
        val personaFile = personaPath()
        val personaExisted = Files.exists(personaFile, LinkOption.NOFOLLOW_LINKS)
        val previous =
            if (personaExisted) {
                readText(personaFile, "PERSONA.md 无法读取", MAX_PERSONA_LENGTH)
            } else {
                ""
            }
        val migrated =
            when {
                isRetiredPersonaSeed(previous) || previous == personaSeed -> ""
                else -> removeRetiredPersonaClauses(previous)
            }
        if (migrated != previous || !personaExisted) {
            atomicWrite(personaFile, migrated, replaceExisting = personaExisted)
        }
        try {
            atomicWrite(
                personaMigrationPath(),
                JSONObject().put(VERSION, PERSONA_MIGRATION_VERSION).toString(2),
                replaceExisting = false,
            )
        } catch (failure: IOException) {
            // Keep an unfinished migration retryable rather than marking unrelated text migrated.
            try {
                if (personaExisted) {
                    atomicWrite(personaFile, previous, replaceExisting = true)
                } else {
                    Files.deleteIfExists(personaFile)
                }
            } catch (rollback: IOException) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    }

    private fun removeRetiredPersonaClauses(text: String): String =
        text
            .replace("。人格仅由主人明确要求修改，不从聊天、记忆或日记自动演化。", "。")
            .replace("；性格自演化未启用。", "。")

    private fun isRetiredPersonaSeed(text: String): Boolean =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) } == RETIRED_PERSONA_SEED_SHA256

    private fun readText(file: Path, errorMessage: String, limit: Int): String =
        try {
            require(Files.size(file) <= limit.toLong() * 4) { "$errorMessage：内容超过上限，原文件保留" }
            file.toFile().readText(Charsets.UTF_8).also {
                require(it.length <= limit) { "$errorMessage：内容超过上限，原文件保留" }
            }
        } catch (_: IOException) {
            error(errorMessage)
        }

    private fun defaultConfig(): JSONObject =
        JSONObject().put(INSTRUCTIONS, "").put(ENABLED_TOOLS, toolsToJson(DEFAULT_ENABLED_TOOLS))

    private fun cleanCompactionThreshold(input: JSONObject): Double {
        require(input.has(COMPACTION_THRESHOLD)) { "缺少 $COMPACTION_THRESHOLD" }
        val value = input.get(COMPACTION_THRESHOLD)
        require(value is Number) { "$COMPACTION_THRESHOLD 必须是数字" }
        val threshold = value.toDouble()
        require(!threshold.isNaN() && !threshold.isInfinite()) {
            "$COMPACTION_THRESHOLD 必须是有限数字"
        }
        require(threshold in MIN_COMPACTION_THRESHOLD..MAX_COMPACTION_THRESHOLD) {
            "$COMPACTION_THRESHOLD 必须在 0.5 到 0.9 之间"
        }
        return threshold
    }

    private fun cleanStoredCompactionThreshold(config: JSONObject) {
        try {
            cleanCompactionThreshold(config)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("bot-config.json 配置无效", error)
        }
    }

    private fun toolsToJson(tools: Collection<String>): JSONArray {
        val result = JSONArray()
        for (tool in tools) result.put(tool)
        return result
    }

    private fun toolsToJson(tools: JSONArray): JSONArray {
        val result = JSONArray()
        for (index in 0 until tools.length()) result.put(tools.getString(index))
        return result
    }

    private fun requiredTools(input: JSONObject): List<String> {
        require(input.has(ENABLED_TOOLS)) { "缺少 $ENABLED_TOOLS" }
        val value = input.get(ENABLED_TOOLS)
        require(value is JSONArray) { "$ENABLED_TOOLS 必须是数组" }
        val result = LinkedHashSet<String>()
        for (index in 0 until value.length()) {
            val tool = value.opt(index)
            require(tool is String) { "$ENABLED_TOOLS 只能包含字符串" }
            require(tool in KNOWN_TOOLS) { "不支持的工具" }
            result.add(tool)
        }
        return result.toList()
    }

    private fun requireKnownBot(id: String): JSONObject {
        val registry = botStore.call(JSONObject().put(OP, LIST))
        val bots = registry.getJSONArray(BOTS)
        for (index in 0 until bots.length()) {
            val bot = bots.getJSONObject(index)
            if (bot.getString(ID) == id) return bot
        }
        throw IllegalArgumentException("未知机器人: $id")
    }

    private fun findSessionOwner(bots: JSONArray, sessionId: String): JSONObject? {
        for (index in 0 until bots.length()) {
            val bot = bots.getJSONObject(index)
            val sessions = bot.getJSONArray(SESSION_IDS)
            for (sessionIndex in 0 until sessions.length()) {
                if (sessions.getString(sessionIndex) == sessionId) return bot
            }
        }
        return null
    }

    private fun resolveCwdBot(bots: JSONArray, cwd: String): JSONObject {
        val normalized =
            try {
                Paths.get(cwd).normalize()
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("cwd 不是有效路径")
            }
        require(normalized.isAbsolute) { "cwd 必须是绝对路径" }

        for (index in 0 until bots.length()) {
            val bot = bots.getJSONObject(index)
            if (normalized.startsWith(Paths.get(BOTS_ROOT, bot.getString(ID)))) return bot
        }
        throw IllegalArgumentException("cwd 不属于已知工作区")
    }

    private fun workspaceComponents(id: String): Array<String> =
        when (id) {
            DEFAULT_BOT_ID -> arrayOf(BOT_WORKSPACES_DIR, DEFAULT_BOT_ID)
            else -> {
                requireBotId(id)
                arrayOf(BOT_WORKSPACES_DIR, id)
            }
        }

    private fun virtualWorkspace(id: String): String {
        requireBotId(id)
        return "$BOTS_ROOT/$id"
    }

    private fun requireBotId(id: String) {
        require(
            id == DEFAULT_BOT_ID ||
                (UUID_PATTERN.matches(id) &&
                    runCatching {
                        UUID.fromString(id)
                    }
                        .isSuccess),
        ) {
            "机器人 ID 必须是 UUID"
        }
    }

    private fun configPath(id: String): Path = safeFile(*(workspaceComponents(id) + CONFIG_FILE))

    private fun memoryPath(id: String): Path =
        safeFile(*(workspaceComponents(id) + MEMORY_DIR + MEMORY_FILE))

    private fun personaPath(): Path = safeFile(SYSTEM_DIR, PERSONA_FILE)

    private fun personaMigrationPath(): Path = safeFile(SYSTEM_DIR, PERSONA_MIGRATION_FILE)

    private fun readPersonaMigrationVersion(): Int {
        val file = personaMigrationPath()
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return 0
        val parsed =
            try {
                JSONObject(readText(file, "$PERSONA_MIGRATION_FILE 无法读取", MAX_MIGRATION_STATE_LENGTH))
            } catch (_: JSONException) {
                error("$PERSONA_MIGRATION_FILE 配置无效")
            }
        require(parsed.length() == 1 && parsed.has(VERSION)) {
            "$PERSONA_MIGRATION_FILE 配置无效"
        }
        val version = parsed.get(VERSION)
        require(version is Int && version in 1..PERSONA_MIGRATION_VERSION) {
            "$PERSONA_MIGRATION_FILE 配置无效"
        }
        return version
    }

    private fun learnedPersonaPath(): Path = safeFile(SYSTEM_DIR, LEARNED_PERSONA_FILE)

    private fun requiredProfile(input: JSONObject): JSONObject {
        require(input.has(PROFILE)) { "缺少 $PROFILE" }
        val value = input.get(PROFILE)
        require(value is JSONObject) { "$PROFILE 必须是 JSON 对象" }
        return value
    }

    private fun cleanLearnedPersona(profile: JSONObject): LearnedPersona {
        requireOnlyFields(profile, REVISION, TRAITS, EVIDENCE_COUNT, UPDATED_AT)
        val revision = requiredString(profile, REVISION)
        require(revision.isNotBlank() && revision.length <= MAX_LEARNED_REVISION_LENGTH) {
            "revision 必须是 1 到 $MAX_LEARNED_REVISION_LENGTH 个字符"
        }
        require(revision.none { it.isISOControl() }) { "revision 不能包含控制字符" }

        require(profile.has(TRAITS)) { "缺少 $TRAITS" }
        val rawTraits = profile.get(TRAITS)
        require(rawTraits is JSONObject && rawTraits.length() > 0) {
            "traits 必须是非空 JSON 对象"
        }
        require(rawTraits.keys().asSequence().all { it in LEARNED_TRAIT_VALUES }) {
            "traits 包含不支持的字段"
        }
        val traits = mutableMapOf<String, String>()
        for (key in rawTraits.keys()) {
            val value = requiredString(rawTraits, key)
            require(value in LEARNED_TRAIT_VALUES.getValue(key)) { "$key 的值无效" }
            traits[key] = value
        }

        val evidenceValue = profile.get(EVIDENCE_COUNT)
        require(
            evidenceValue is Int ||
                (evidenceValue is Long && evidenceValue in 1L..Int.MAX_VALUE.toLong()),
        ) {
            "$EVIDENCE_COUNT 必须是正整数"
        }
        val evidenceCount = (evidenceValue as Number).toInt()
        require(evidenceCount > 0) { "$EVIDENCE_COUNT 必须是正整数" }

        val updatedAt = requiredString(profile, UPDATED_AT)
        require(UPDATED_AT_PATTERN.matches(updatedAt)) { "$UPDATED_AT 必须是 ISO8601 时间" }
        try {
            OffsetDateTime.parse(updatedAt)
        } catch (_: DateTimeParseException) {
            require(false) { "$UPDATED_AT 必须是有效的 ISO8601 时间" }
        }

        return LearnedPersona(
            revision = revision,
            traits = traits,
            evidenceCount = evidenceCount,
            updatedAt = updatedAt,
        )
    }

    private fun learnedPersonaJson(profile: LearnedPersona): JSONObject =
        JSONObject()
            .put(REVISION, profile.revision)
            .put(TRAITS, JSONObject(profile.traits))
            .put(EVIDENCE_COUNT, profile.evidenceCount)
            .put(UPDATED_AT, profile.updatedAt)

    private fun readLearnedPersonaIfExists(): LearnedPersona? {
        val path = learnedPersonaPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        return readLearnedPersona()
    }

    private fun readLearnedPersona(): LearnedPersona {
        val path = learnedPersonaPath()
        val raw = readText(path, "$LEARNED_PERSONA_FILE 无法读取", MAX_LEARNED_PERSONA_LENGTH)
        val parsed =
            try {
                JSONObject(raw)
            } catch (_: JSONException) {
                error("$LEARNED_PERSONA_FILE 配置无效")
            }
        return try {
            cleanLearnedPersona(parsed)
        } catch (_: IllegalArgumentException) {
            error("$LEARNED_PERSONA_FILE 配置无效")
        }
    }

    private fun learnedPersonaText(profile: LearnedPersona): String {
        val traits = LEARNED_TRAIT_ORDER.mapNotNull { key ->
            profile.traits[key]?.let { value -> requireNotNull(LEARNED_TRAIT_LABELS[key]?.get(value)) }
        }
        return "共同学习表达：" + traits.joinToString("；") + "。" +
            "仅影响说话方式；手动人格优先，未覆盖维度使用默认表达，不改变身份、事实、权限、任务或安全约束。"
    }

    private fun learningResponse(applied: Boolean, revision: String): JSONObject =
        JSONObject().put(OK, true).put(APPLIED, applied).put(REVISION, revision)

    private fun requireOnlyFields(input: JSONObject, vararg allowed: String) {
        val allowedFields = allowed.toSet()
        val keys = input.keys()
        while (keys.hasNext()) {
            require(keys.next() in allowedFields) { "不支持字段" }
        }
    }

    private fun requiredString(input: JSONObject, key: String): String {
        require(input.has(key)) { "缺少 $key" }
        val value = input.get(key)
        require(value is String) { "$key 必须是字符串" }
        return value
    }

    private fun requireSafeExistingRoot() {
        val root = rootPath()
        if (Files.isSymbolicLink(root)) throw IOException("filesDir 不能是符号链接")
        if (
            Files.exists(root, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("filesDir 不是目录")
        }
        val botsFile = root.resolve(BOTS_FILE)
        if (Files.isSymbolicLink(botsFile)) throw IOException("bots.json 不能是符号链接")
        if (
            Files.exists(botsFile, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(botsFile, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("bots.json 不是普通文件")
        }
    }

    private fun rootPath(): Path = filesDir.toPath().toAbsolutePath().normalize()

    private fun ensureRootDirectory(): Path {
        val root = rootPath()
        if (Files.isSymbolicLink(root)) throw IOException("filesDir 不能是符号链接")
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("filesDir 不是目录")
            }
        } else {
            Files.createDirectories(root)
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("filesDir 不是安全目录")
            }
        }
        requireInsideRoot(root)
        return root
    }

    private fun ensureDirectory(vararg components: String): Path {
        var current = ensureRootDirectory()
        for (component in components) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                throw IOException("工作区路径不能是符号链接")
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("工作区目录路径不是目录")
                }
            } else {
                Files.createDirectory(current)
            }
            requireInsideRoot(current)
        }
        return current
    }

    private fun safeFile(vararg components: String): Path {
        require(components.isNotEmpty())
        val parent = ensureDirectory(*components.copyOfRange(0, components.lastIndex))
        val file = parent.resolve(components.last())
        if (Files.isSymbolicLink(file)) throw IOException("工作区文件不能是符号链接")
        if (
            Files.exists(file, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("工作区文件路径不是普通文件")
        }
        requireInsideRoot(file)
        return file
    }

    private fun requireInsideRoot(path: Path) {
        val canonicalRoot = filesDir.canonicalFile.toPath()
        val canonicalPath = path.toFile().canonicalFile.toPath()
        if (!canonicalPath.startsWith(canonicalRoot)) {
            throw IOException("路径超出工作区")
        }
    }

    private fun requireSafeWriteTarget(file: Path) {
        val root = rootPath()
        if (Files.isSymbolicLink(file)) throw IOException("工作区文件不能是符号链接")
        if (
            Files.exists(file, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("工作区文件路径不是普通文件")
        }
        var parent: Path? = file.parent
        while (parent != null && parent.startsWith(root)) {
            if (Files.isSymbolicLink(parent)) throw IOException("工作区目录不能是符号链接")
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("工作区目录路径不是目录")
            }
            if (parent == root) break
            parent = parent.parent
        }
        requireInsideRoot(file)
    }

    private fun atomicWrite(file: Path, content: String, replaceExisting: Boolean) {
        requireSafeWriteTarget(file)
        val parent = requireNotNull(file.parent) { "工作区文件必须位于目录内" }
        val temporary = Files.createTempFile(parent, "${file.fileName}.", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            requireSafeWriteTarget(file)
            if (replaceExisting) {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val BOTS_FILE = "bots.json"
        const val BOTS = "bots"
        const val OP = "op"
        const val LIST = "list"
        const val CREATE = "create"
        const val RENAME = "rename"
        const val PIN = "pin"
        const val BIND = "bind"
        const val SELECT = "select"
        const val SETTINGS = "settings"
        const val SAVE_SETTINGS = "saveSettings"
        const val PERSONA = "persona"
        const val SAVE_PERSONA = "savePersona"
        const val LEARNING = "learning"
        const val CONTEXT = "context"
        const val DELETE = "delete"
        const val ID = "id"
        const val AVATAR = "avatar"
        const val INSTRUCTIONS = "instructions"
        const val MEMORY = "memory"
        const val ENABLED_TOOLS = "enabledTools"
        const val COMPACTION_THRESHOLD = "compactionThreshold"
        const val CONFIRM_DELETE = "confirmDelete"
        const val SHARED_PERSONA = "sharedPersona"
        const val DEFAULT_PERSONA = "defaultPersona"
        const val LEARNED_PERSONA = "learnedPersona"
        const val LEARNED_PERSONA_REVISION = "learnedPersonaRevision"
        const val SESSION_ID = "sessionId"
        const val SESSION_IDS = "sessionIds"
        const val EXCLUDED_SESSION_IDS = "excludedSessionIds"
        const val LEGACY_MEMORY = "legacyMemory"
        const val CWD = "cwd"
        const val TEXT = "text"
        const val WORKSPACE = "workspace"
        const val DELETED_WORKSPACES_DIR = ".deleted"

        const val DEFAULT_BOT_ID = "default"
        const val DEFAULT_AVATAR = "circle"
        const val BOT_WORKSPACES_DIR = "BOT"
        const val SYSTEM_DIR = "CONFIG"
        const val CONFIG_FILE = "bot-config.json"
        const val MEMORY_DIR = "memory"
        const val MEMORY_FILE = "MEMORY.md"
        const val ARCHIVES_DIR = "archives"
        const val SKILLS_DIR = "skills"
        const val PERSONA_FILE = "PERSONA.md"
        const val PERSONA_MIGRATION_FILE = "PERSONA_MIGRATION.json"
        const val LEARNED_PERSONA_FILE = "PERSONA_LEARNED.json"
        const val BOTS_ROOT = "/BOT"
        const val PROFILE = "profile"
        const val REVISION = "revision"
        const val TRAITS = "traits"
        const val EVIDENCE_COUNT = "evidenceCount"
        const val UPDATED_AT = "updatedAt"
        const val OK = "ok"
        const val APPLIED = "applied"
        const val VERSION = "version"

        const val MAX_INSTRUCTIONS_LENGTH = 16_000
        const val MAX_MEMORY_LENGTH = 24_000
        const val MAX_PERSONA_LENGTH = 16_000
        const val PERSONA_MIGRATION_VERSION = 1
        const val MAX_MIGRATION_STATE_LENGTH = 1_000
        const val MAX_LEARNED_PERSONA_LENGTH = 10_000
        const val MAX_LEARNED_REVISION_LENGTH = 128
        const val MAX_SESSION_ID_LENGTH = 256
        const val MIN_COMPACTION_THRESHOLD = 0.5
        const val MAX_COMPACTION_THRESHOLD = 0.9
        const val DEFAULT_COMPACTION_THRESHOLD = 0.8

        val DEFAULT_ENABLED_TOOLS =
            listOf(
                "files",
                "shell",
                "web",
                "skills",
                "plugins",
                "memory",
                "app-management",
                "conversation-history",
            )
        val KNOWN_TOOLS = DEFAULT_ENABLED_TOOLS.toSet()
        const val RETIRED_PERSONA_SEED_SHA256 =
            "93b250d0784297d18cf69ba637c12da8cb6fd284cc445e94508d747dd03f538f"
        val LEARNED_TRAIT_VALUES =
            mapOf(
                "directness" to setOf("direct", "gentle", "balanced"),
                "verbosity" to setOf("concise", "adaptive", "detailed"),
                "warmth" to setOf("reserved", "friendly", "warm"),
                "humor" to setOf("none", "situational", "playful"),
                "reflection" to setOf("grounded", "situational", "reflective"),
            )
        val LEARNED_TRAIT_ORDER = LEARNED_TRAIT_VALUES.keys.toList()
        val LEARNED_TRAIT_LABELS =
            mapOf(
                "directness" to
                    mapOf(
                        "direct" to "表达直接",
                        "gentle" to "表达温和",
                        "balanced" to "表达平衡",
                    ),
                "verbosity" to
                    mapOf(
                        "concise" to "篇幅简短",
                        "adaptive" to "篇幅按需要调整",
                        "detailed" to "篇幅细致展开",
                    ),
                "warmth" to
                    mapOf(
                        "reserved" to "语气克制",
                        "friendly" to "语气友好",
                        "warm" to "语气温暖",
                    ),
                "humor" to
                    mapOf(
                        "none" to "不主动使用幽默",
                        "situational" to "视场景使用幽默",
                        "playful" to "可以使用轻松玩笑",
                    ),
                "reflection" to
                    mapOf(
                        "grounded" to "回应落在当前事实",
                        "situational" to "结合场景补充看法",
                        "reflective" to "适度回顾反思",
                    ),
            )
        val UPDATED_AT_PATTERN =
            Regex(
                "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})$",
            )
        val UUID_PATTERN =
            Regex(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            )
    }
}
