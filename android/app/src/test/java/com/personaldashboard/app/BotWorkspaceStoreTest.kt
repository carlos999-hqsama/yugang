package com.personaldashboard.app

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BotWorkspaceStoreTest {
    private lateinit var filesDir: File
    private lateinit var outsideDir: File

    @Test
    fun messageRouteUsesRegisteredBotOwnershipAndReservesOneTargetConversation() {
        val a = "11111111-1111-4111-8111-111111111111"
        val b = "22222222-2222-4222-8222-222222222222"
        val store = BotWorkspaceStore(filesDir, "persona must not leak")
        store.call(createRequest(a, "A"))
        store.call(createRequest(b, "B"))
        store.call(JSONObject().put("op", "bind").put("id", a).put("sessionId", "origin-a"))
        fun route(target: String? = null) =
            JSONObject()
                .put("op", "messageRoute")
                .put("sessionId", "origin-a")
                .put("cwd", "/BOT/$a")
                .apply { target?.let { put("targetId", it) } }
        val directory = store.call(route())
        assertEquals(a, directory.getJSONObject("sender").getString("id"))
        assertFalse(directory.toString().contains("persona must not leak"))
        assertFalse(directory.toString().contains("instructions"))
        val reserved = store.call(route(b)).getJSONObject("target")
        assertEquals("/BOT/$b", reserved.getString("workspace"))
        assertEquals(
            reserved.getString("sessionId"),
            store.call(route(b)).getJSONObject("target").getString("sessionId"),
        )
        rejects { store.call(route(a)) }
        rejects { store.call(route("missing")) }
        rejects { store.call(route(b).put("cwd", "/BOT/$b")) }
    }

    @Test
    fun messageReplyKeepsItsOriginalConversationWithoutChangingTargetSelection() {
        val a = "11111111-1111-4111-8111-111111111111"
        val b = "22222222-2222-4222-8222-222222222222"
        val store = BotWorkspaceStore(filesDir, "shared")
        store.call(createRequest(a, "A"))
        store.call(createRequest(b, "B"))
        for (sid in listOf("original-a", "new-a")) store.call(
            JSONObject().put("op", "bind").put("id", a).put("sessionId", sid),
        )
        store.call(JSONObject().put("op", "bind").put("id", b).put("sessionId", "session-b"))
        val request =
            JSONObject()
                .put("op", "messageRoute")
                .put("sessionId", "session-b")
                .put("cwd", "/BOT/$b")
                .put("targetId", a)
                .put("targetSessionId", "original-a")
        assertEquals(
            "original-a",
            store.call(request).getJSONObject("target").getString("sessionId"),
        )
        assertEquals(
            "new-a",
            bot(store.call(JSONObject().put("op", "list")), a).getString("selectedSessionId"),
        )
        rejects { store.call(request.put("targetSessionId", "session-b")) }
    }

    @Test
    fun messageAdmissionPreventsDeletingBotsDuringHandoffAndExpiresAfterFailure() {
        val a = "11111111-1111-4111-8111-111111111111"
        val b = "22222222-2222-4222-8222-222222222222"
        var clock = 0L
        val store = BotWorkspaceStore(filesDir, "shared") { clock }
        store.call(createRequest(a, "A"))
        store.call(createRequest(b, "B"))
        store.call(JSONObject().put("op", "bind").put("id", a).put("sessionId", "source"))
        val request =
            JSONObject()
                .put("op", "messageRoute")
                .put("sessionId", "source")
                .put("cwd", "/BOT/$a")
                .put("targetId", b)
        val sid = store.call(request).getJSONObject("target").getString("sessionId")
        request.put("targetSessionId", sid).put("deliveryId", "bm-" + "a".repeat(32))
        rejects { store.call(request) }
        request.put("admission", "begin")
        store.call(request)
        fun deletion(id: String) =
            JSONObject().put("op", "delete").put("id", id).put("confirmDelete", true)
        rejects { store.call(deletion(a)) }
        rejects { store.call(deletion(b)) }
        store.call(request.put("admission", "check"))
        store.call(request.put("admission", "end"))
        rejects { store.call(request.put("admission", "check")) }
        store.call(request.put("admission", "begin"))
        clock = java.util.concurrent.TimeUnit.MINUTES.toNanos(6)
        rejects { store.call(request.put("admission", "check")) }
        store.call(deletion(b))
        assertFalse(File(filesDir, "BOT/$b").exists())
        rejects { store.call(request.put("admission", "begin")) }
        assertFalse(File(filesDir, "BOT/$b").exists())
    }

    @Test
    fun unregisteredChildSessionCannotSendAsAnIndependentBot() {
        val a = "11111111-1111-4111-8111-111111111111"
        val b = "22222222-2222-4222-8222-222222222222"
        val store = BotWorkspaceStore(filesDir, "shared")
        store.call(createRequest(a, "A"))
        store.call(createRequest(b, "B"))
        rejects {
            store.call(
                JSONObject()
                    .put("op", "messageRoute")
                    .put("sessionId", "child")
                    .put("cwd", "/BOT/$a")
                    .put("targetId", b),
            )
        }
    }

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("pd-bot-workspace").toFile()
        outsideDir = Files.createTempDirectory("pd-bot-workspace-outside").toFile()
    }

    @Test
    fun compactionThresholdHasBoundsPersistsAndOmissionPreservesCurrentValue() {
        val id = "22222222-2222-4222-8222-2222222222aa"
        val store = BotWorkspaceStore(filesDir, "persona")
        store.call(createRequest(id, "Compaction"))

        assertEquals(0.8, store.call(settingsRequest(id)).getDouble("compactionThreshold"), 0.0)

        val saved = store.call(validSave(id, "duties", "memory", listOf("files"), 0.67))
        assertEquals(0.67, saved.getDouble("compactionThreshold"), 0.0)
        val omitted = store.call(validSave(id, "next duties", "next memory", listOf("web")))
        assertEquals(0.67, omitted.getDouble("compactionThreshold"), 0.0)

        val settings = store.call(settingsRequest(id))
        val context = store.call(contextRequest("new", "/BOT/$id/documents"))
        assertEquals(0.67, settings.getDouble("compactionThreshold"), 0.0)
        assertEquals(0.67, context.getDouble("compactionThreshold"), 0.0)
        val config = JSONObject(File(filesDir, "BOT/$id/bot-config.json").readText())
        assertEquals(0.67, config.getDouble("compactionThreshold"), 0.0)

        for (invalid in listOf(0.49, 0.91, "80", true)) {
            rejects {
                store.call(
                    validSave(id, "invalid", "memory", listOf("files"))
                        .put("compactionThreshold", invalid),
                )
            }
        }
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun listCreatesDefaultLayoutWithoutChangingLegacyFiles() {
        val id = "11111111-1111-4111-8111-111111111111"
        val defaultWorkspace = File(filesDir, "BOT/default")
        val legacyDefault = File(filesDir, "agent-workspace").apply { mkdirs() }
        File(legacyDefault, "legacy.txt").writeText("old default file")
        val botWorkspace = File(filesDir, "BOT/$id")
        val legacyBot = File(filesDir, "bot-workspaces/$id").apply { mkdirs() }
        File(legacyBot, "old-work.txt").writeText("old bot file")
        BotStore(File(filesDir, "bots.json")).call(createRequest(id, "Worker"))

        val store = BotWorkspaceStore(filesDir, "shared seed")
        val result = store.call(JSONObject().put("op", "list"))

        assertEquals("/BOT/default", bot(result, "default").getString("workspace"))
        assertEquals("/BOT/$id", bot(result, id).getString("workspace"))
        assertEquals("old default file", File(defaultWorkspace, "legacy.txt").readText())
        assertEquals("old bot file", File(botWorkspace, "old-work.txt").readText())
        assertDefaultWorkspaceLayout(defaultWorkspace)
        assertDefaultWorkspaceLayout(botWorkspace)
        assertEquals("", File(filesDir, "CONFIG/PERSONA.md").readText())
        assertEquals(
            1,
            JSONObject(File(filesDir, "CONFIG/PERSONA_MIGRATION.json").readText()).getInt("version"),
        )
    }

    @Test
    fun settingsAreIndependentAndSharedPersonaAppliesToAllBotsImmediately() {
        val firstId = "22222222-2222-4222-8222-222222222222"
        val secondId = "33333333-3333-4333-8333-333333333333"
        val store = BotWorkspaceStore(filesDir, "initial persona")
        val created = store.call(createRequest(firstId, "First"))
        assertEquals("/BOT/$firstId", bot(created, firstId).getString("workspace"))
        assertEquals("circle", bot(created, firstId).getString("avatar"))
        assertTrue(File(filesDir, "BOT/$firstId/bot-config.json").isFile)
        store.call(createRequest(secondId, "Second"))
        store.call(JSONObject().put("op", "savePersona").put("text", "same persona"))

        val saved =
            store.call(
                JSONObject()
                    .put("op", "saveSettings")
                    .put("id", firstId)
                    .put("instructions", "Use the first workspace")
                    .put("memory", "first memory")
                    .put(
                        "enabledTools",
                        JSONArray(listOf("files", "web", "files", "memory")),
                    ),
            )
        assertEquals(
            listOf("files", "web", "memory"),
            strings(saved.getJSONArray("enabledTools")),
        )
        assertEquals("same persona", saved.getString("sharedPersona"))

        val firstSettings = store.call(settingsRequest(firstId))
        val secondSettings = store.call(settingsRequest(secondId))
        assertEquals("Use the first workspace", firstSettings.getString("instructions"))
        assertEquals("first memory", firstSettings.getString("memory"))
        assertEquals(
            listOf("files", "web", "memory"),
            strings(firstSettings.getJSONArray("enabledTools")),
        )
        assertEquals("same persona", firstSettings.getString("sharedPersona"))
        assertEquals("", secondSettings.getString("instructions"))
        assertEquals("", secondSettings.getString("memory"))
        assertEquals(DEFAULT_TOOLS, strings(secondSettings.getJSONArray("enabledTools")))
        assertEquals("same persona", secondSettings.getString("sharedPersona"))

        val firstConfig = JSONObject(File(filesDir, "BOT/$firstId/bot-config.json").readText())
        assertEquals(3, firstConfig.length())
        assertEquals("Use the first workspace", firstConfig.getString("instructions"))
        assertEquals("first memory", File(filesDir, "BOT/$firstId/memory/MEMORY.md").readText())
        assertEquals("", File(filesDir, "BOT/$secondId/memory/MEMORY.md").readText())

        val reopened = BotWorkspaceStore(filesDir, "must not reseed")
        val reopenedFirst = reopened.call(settingsRequest(firstId))
        assertEquals("Use the first workspace", reopenedFirst.getString("instructions"))
        assertEquals("first memory", reopenedFirst.getString("memory"))
        assertEquals("same persona", reopenedFirst.getString("sharedPersona"))
    }

    @Test
    fun learnedPersonaPersistsSharesAcrossBotsAndIsIdempotentForSameCandidate() {
        val firstId = "22222222-2222-4222-8222-2222222222aa"
        val secondId = "33333333-3333-4333-8333-3333333333aa"
        val store = BotWorkspaceStore(filesDir, defaultSeed)
        store.call(createRequest(firstId, "First"))
        store.call(createRequest(secondId, "Second"))

        val applied = store.call(
            learningRequest(
                "nightly-1",
                mapOf("directness" to "direct", "verbosity" to "adaptive", "humor" to "situational"),
            ),
        )
        assertEquals(true, applied.getBoolean("ok"))
        assertEquals(true, applied.getBoolean("applied"))
        assertEquals("nightly-1", applied.getString("revision"))

        val persisted = JSONObject(File(filesDir, "CONFIG/PERSONA_LEARNED.json").readText())
        assertEquals("nightly-1", persisted.getString("revision"))
        assertEquals("direct", persisted.getJSONObject("traits").getString("directness"))
        assertEquals(3, persisted.getInt("evidenceCount"))
        assertEquals("2026-10-24T01:02:03Z", persisted.getString("updatedAt"))
        assertEquals("", File(filesDir, "CONFIG/PERSONA.md").readText())

        for ((id, cwd) in listOf("default" to "/BOT/default", firstId to "/BOT/$firstId", secondId to "/BOT/$secondId")) {
            val context = store.call(contextRequest("session-${id.substringAfter('-')}", cwd))
            assertEquals(defaultSeed, context.getString("defaultPersona"))
            assertEquals(
                "共同学习表达：表达直接；篇幅按需要调整；视场景使用幽默。" +
                    "仅影响说话方式；手动人格优先，未覆盖维度使用默认表达，不改变身份、事实、权限、任务或安全约束。",
                context.getString("learnedPersona"),
            )
            assertEquals("nightly-1", context.getString("learnedPersonaRevision"))
        }

        val duplicate = store.call(
            learningRequest(
                "nightly-1",
                mapOf("humor" to "situational", "verbosity" to "adaptive", "directness" to "direct"),
            ),
        )
        assertEquals(false, duplicate.getBoolean("applied"))
        assertEquals("nightly-1", duplicate.getString("revision"))

        val next = store.call(learningRequest("nightly-2", mapOf("warmth" to "warm")))
        assertEquals(true, next.getBoolean("applied"))
        val reopened = BotWorkspaceStore(filesDir, "reopen seed")
        val reopenedContext =
            reopened.call(contextRequest("new-session", "/BOT/$secondId/documents"))
        assertEquals(
            "共同学习表达：语气温暖。" +
                "仅影响说话方式；手动人格优先，未覆盖维度使用默认表达，不改变身份、事实、权限、任务或安全约束。",
            reopenedContext.getString("learnedPersona"),
        )
        assertEquals("nightly-2", reopenedContext.getString("learnedPersonaRevision"))
    }

    @Test
    fun learnedPersonaRejectsUnknownOrInvalidFieldsWithoutWriting() {
        val store = BotWorkspaceStore(filesDir, "manual persona")
        store.call(JSONObject().put("op", "list"))
        val file = File(filesDir, "CONFIG/PERSONA_LEARNED.json")

        val invalid =
            listOf(
                learningRequest("", mapOf("warmth" to "warm")).put("extra", true),
                learningRequest(" ".repeat(129), mapOf("warmth" to "warm")),
                JSONObject().put("op", "learning"),
                learningRequest("bad-type", emptyMap()),
                learningRequest("unknown-trait", mapOf("tone" to "direct")),
                learningRequest("bad-value", mapOf("warmth" to "hot")),
                learningRequest("bad-evidence", mapOf("warmth" to "warm"))
                    .put("profile", JSONObject().put("revision", "bad-evidence")
                        .put("traits", JSONObject().put("warmth", "warm"))
                        .put("evidenceCount", 0)
                        .put("updatedAt", "2026-10-24T01:02:03Z")),
                learningRequest("fraction", mapOf("warmth" to "warm"))
                    .put("profile", JSONObject().put("revision", "fraction")
                        .put("traits", JSONObject().put("warmth", "warm"))
                        .put("evidenceCount", 1.5)
                        .put("updatedAt", "2026-10-24T01:02:03Z")),
                learningRequest("bad-time", mapOf("warmth" to "warm"))
                    .put("profile", JSONObject().put("revision", "bad-time")
                        .put("traits", JSONObject().put("warmth", "warm"))
                        .put("evidenceCount", 1)
                        .put("updatedAt", "yesterday")),
            )
        for (request in invalid) {
            rejects { store.call(request) }
            assertFalse(file.exists())
        }

        val valid = store.call(learningRequest("valid", mapOf("warmth" to "warm")))
        assertEquals(true, valid.getBoolean("applied"))
        rejects {
            store.call(
                learningRequest("valid", mapOf("warmth" to "reserved"))
                    .put("profile", JSONObject().put("revision", "valid")
                        .put("traits", JSONObject().put("warmth", "reserved"))
                        .put("evidenceCount", 4)
                        .put("updatedAt", "2026-10-24T01:02:03Z")),
            )
        }
        assertEquals(
            "warm",
            JSONObject(file.readText()).getJSONObject("traits").getString("warmth"),
        )
    }

    @Test
    fun existingPersonaOnlyRemovesTwoRetiredEvolutionClauses() {
        File(filesDir, "CONFIG").mkdirs()
        val original =
            "保留开头。人格仅由主人明确要求修改，不从聊天、记忆或日记自动演化。\n" +
                "自定义中段。\n" +
                "记忆仍是资料；性格自演化未启用。\n"
        File(filesDir, "CONFIG/PERSONA.md").writeText(original)

        val store = BotWorkspaceStore(filesDir, "new seed must not reset")
        val persona = store.call(JSONObject().put("op", "persona")).getString("text")
        assertEquals(
            "保留开头。\n自定义中段。\n记忆仍是资料。\n",
            persona,
        )
        assertEquals(persona, File(filesDir, "CONFIG/PERSONA.md").readText())
        assertFalse(File(filesDir, "CONFIG/PERSONA_LEARNED.json").exists())
    }

    @Test
    fun savedRetiredEvolutionClausesSurviveReopenWithoutStatelessRewrite() {
        val saved =
            "主人明确要求保留旧句子。人格仅由主人明确要求修改，不从聊天、记忆或日记自动演化。\n" +
                "第二句；性格自演化未启用。\n"
        val store = BotWorkspaceStore(filesDir, defaultSeed)
        assertEquals(
            saved,
            store.call(JSONObject().put("op", "savePersona").put("text", saved)).getString("text"),
        )

        val reopened = BotWorkspaceStore(filesDir, defaultSeed)
        assertEquals(saved, reopened.call(JSONObject().put("op", "persona")).getString("text"))
        assertEquals(saved, reopened.call(contextRequest("new", "/BOT/default")).getString("sharedPersona"))
        assertEquals(saved, File(filesDir, "CONFIG/PERSONA.md").readText())
    }

    @Test
    fun currentSeedMovesToDefaultLayerOnceAndLaterManualTextIsStable() {
        File(filesDir, "CONFIG").mkdirs()
        File(filesDir, "CONFIG/PERSONA.md").writeText(defaultSeed)
        val store = BotWorkspaceStore(filesDir, defaultSeed)

        assertEquals("", store.call(JSONObject().put("op", "persona")).getString("text"))
        assertEquals(
            defaultSeed,
            store.call(contextRequest("new", "/BOT/default")).getString("defaultPersona"),
        )

        store.call(JSONObject().put("op", "savePersona").put("text", "partial manual"))
        val reopened = BotWorkspaceStore(filesDir, "different current seed")
        val context = reopened.call(contextRequest("new", "/BOT/default"))
        assertEquals("partial manual", context.getString("sharedPersona"))
        assertEquals("different current seed", context.getString("defaultPersona"))
        assertEquals("partial manual", File(filesDir, "CONFIG/PERSONA.md").readText())
    }

    @Test
    fun retiredSeedMovesToDefaultLayerOnceAndNearbyCustomTextIsNotGuessedAway() {
        val retiredSeed = javaClass.getResourceAsStream("/bot-persona-retired.md")!!.readBytes()
        assertEquals(
            "93b250d0784297d18cf69ba637c12da8cb6fd284cc445e94508d747dd03f538f",
            MessageDigest.getInstance("SHA-256").digest(retiredSeed).joinToString("") { byte ->
                "%02x".format(byte)
            },
        )
        File(filesDir, "CONFIG").mkdirs()
        File(filesDir, "CONFIG/PERSONA.md").writeBytes(retiredSeed)

        val store = BotWorkspaceStore(filesDir, defaultSeed)
        assertEquals("", store.call(JSONObject().put("op", "persona")).getString("text"))
        assertEquals(1, JSONObject(File(filesDir, "CONFIG/PERSONA_MIGRATION.json").readText()).getInt("version"))

        val modifiedRetiredSeed = retiredSeed + "X".toByteArray(Charsets.UTF_8)
        val otherFilesDir = File(filesDir, "other-files").apply { mkdirs() }
        File(otherFilesDir, "CONFIG").mkdirs()
        File(otherFilesDir, "CONFIG/PERSONA.md").writeBytes(modifiedRetiredSeed)
        val nearbyStore = BotWorkspaceStore(otherFilesDir, defaultSeed)
        nearbyStore.call(JSONObject().put("op", "list"))
        val nearbyPersona = File(otherFilesDir, "CONFIG/PERSONA.md").readText()
        assertTrue(nearbyPersona.endsWith("X"))
        assertFalse(nearbyPersona.contains("不从聊天、记忆或日记自动演化"))
        assertFalse(nearbyPersona.contains("性格自演化未启用"))
    }

    @Test
    fun confirmedBoundUserBotDeletionArchivesFilesWithoutDestroyingSessions() {
        val id = "77777777-7777-4777-8777-7777777777aa"
        val store = BotWorkspaceStore(filesDir, "persona")
        store.call(createRequest(id, "Temporary").put("avatar", "pentagon"))
        store.call(validSave(id, "temporary duties", "temporary memory", listOf("files")))
        store.call(
            JSONObject().put("op", "bind").put("id", id).put("sessionId", "raw-runtime-session"),
        )
        val workspace = File(filesDir, "BOT/$id")
        val retained =
            File(workspace, "documents/retained.txt").apply {
                parentFile!!.mkdirs()
                writeText("workspace files are archived")
            }
        val rawSession =
            File(filesDir, "sessions/raw-runtime-session.json").apply {
                parentFile!!.mkdirs()
                writeText("{\"raw\":true}")
            }

        rejects {
            store.call(
                JSONObject().put("op", "delete").put("id", "default").put("confirmDelete", true),
            )
        }
        rejects {
            store.call(
                JSONObject()
                    .put("op", "delete")
                    .put("id", PluginDeveloperBot.ID)
                    .put("confirmDelete", true),
            )
        }
        rejects { store.call(JSONObject().put("op", "delete").put("id", id)) }
        assertTrue(workspace.isDirectory)

        val result =
            store.call(JSONObject().put("op", "delete").put("id", id).put("confirmDelete", true))
        assertEquals(listOf("default"), botIds(result))
        assertFalse(workspace.exists())
        val archive = File(filesDir, "BOT/.deleted/$id")
        assertEquals(
            "workspace files are archived",
            File(archive, "documents/retained.txt").readText(),
        )
        assertEquals("{\"raw\":true}", rawSession.readText())
        rejects { store.call(contextRequest("raw-runtime-session", "/BOT/$id")) }
        assertEquals(
            "default",
            store.call(contextRequest("new-default-session", "/BOT/default")).getString("id"),
        )
        assertEquals(
            listOf("default"),
            botIds(BotWorkspaceStore(filesDir, "seed").call(JSONObject().put("op", "list"))),
        )
    }

    @Test
    fun deletionRejectsArchiveReuseAndRollsWorkspaceBackWhenRegistryWriteFails() {
        val conflictId = "88888888-8888-4888-8888-8888888888aa"
        val failureId = "88888888-8888-4888-8888-8888888888bb"
        val store = BotWorkspaceStore(filesDir, "persona")
        store.call(createRequest(conflictId, "Conflict"))
        store.call(createRequest(failureId, "Failure"))
        File(filesDir, "BOT/.deleted/$conflictId")
            .apply { parentFile!!.mkdirs() }
            .writeText("existing archive")

        fails {
            store.call(
                JSONObject().put("op", "delete").put("id", conflictId).put("confirmDelete", true),
            )
        }
        assertTrue(File(filesDir, "BOT/$conflictId").isDirectory)
        assertEquals("existing archive", File(filesDir, "BOT/.deleted/$conflictId").readText())

        check(filesDir.setWritable(false, false))
        try {
            fails {
                store.call(
                    JSONObject()
                        .put("op", "delete")
                        .put("id", failureId)
                        .put("confirmDelete", true),
                )
            }
        } finally {
            check(filesDir.setWritable(true, true))
        }
        assertTrue(File(filesDir, "BOT/$failureId").isDirectory)
        assertFalse(File(filesDir, "BOT/.deleted/$failureId").exists())
        assertEquals(
            failureId,
            bot(store.call(JSONObject().put("op", "list")), failureId).getString("id"),
        )
    }

    @Test
    fun oldRegistryMappingsAndExistingWorkspaceFilesArePreservedAcrossReopen() {
        val id = "44444444-4444-4444-8444-444444444444"
        val registryStore = BotStore(File(filesDir, "bots.json"))
        registryStore.call(createRequest(id, "Old Bot"))
        registryStore.call(
            JSONObject().put("op", "bind").put("id", id).put("sessionId", "old-session"),
        )
        val botWorkspace = File(filesDir, "BOT/$id").apply { mkdirs() }
        File(botWorkspace, "notes.txt").writeText("keep this")
        File(botWorkspace, "memory").mkdirs()
        File(botWorkspace, "memory/MEMORY.md").writeText("old memory")
        val oldConfig =
            JSONObject()
                .put("instructions", "old instructions")
                .put("enabledTools", JSONArray(listOf("files", "memory")))
                .toString(2)
        File(botWorkspace, "bot-config.json").writeText(oldConfig)

        val store = BotWorkspaceStore(filesDir, "persona seed")
        val listed = store.call(JSONObject().put("op", "list"))
        assertEquals(listOf("old-session"), strings(bot(listed, id).getJSONArray("sessionIds")))

        val settings = store.call(settingsRequest(id))
        assertEquals("old instructions", settings.getString("instructions"))
        assertEquals("old memory", settings.getString("memory"))
        assertEquals(listOf("files", "memory"), strings(settings.getJSONArray("enabledTools")))
        assertEquals("keep this", File(botWorkspace, "notes.txt").readText())
        assertEquals(oldConfig, File(botWorkspace, "bot-config.json").readText())

        store.call(JSONObject().put("op", "rename").put("id", id).put("name", "Renamed"))
        val reopened = BotWorkspaceStore(filesDir, "replacement seed")
        val afterReopen = reopened.call(JSONObject().put("op", "list"))
        assertEquals("Renamed", bot(afterReopen, id).getString("name"))
        assertEquals(
            listOf("old-session"),
            strings(bot(afterReopen, id).getJSONArray("sessionIds")),
        )
        assertEquals(
            "old instructions",
            reopened.call(settingsRequest(id)).getString("instructions"),
        )
        assertEquals("old memory", reopened.call(settingsRequest(id)).getString("memory"))
        assertEquals(
            "",
            reopened.call(JSONObject().put("op", "persona")).getString("text"),
        )
    }

    @Test
    fun malformedBotConfigFailsWithoutBeingReplaced() {
        val id = "55555555-5555-4555-8555-555555555555"
        BotStore(File(filesDir, "bots.json")).call(createRequest(id, "Broken"))
        val botWorkspace = File(filesDir, "BOT/$id").apply { mkdirs() }
        val configFile = File(botWorkspace, "bot-config.json")
        val malformed = "{\"instructions\":\"secret\",\"enabledTools\":[\"not-a-tool\"]}"
        configFile.writeText(malformed)

        val store = BotWorkspaceStore(filesDir, "persona")
        val error = fails { store.call(JSONObject().put("op", "list")) }
        assertTrue(error is IllegalStateException)
        assertFalse(error.message.orEmpty().contains("secret"))
        assertEquals(malformed, configFile.readText())
    }

    @Test
    fun saveSettingsValidationFailureNeverOverwritesConfigOrMemory() {
        val id = "66666666-6666-4666-8666-666666666666"
        val store = BotWorkspaceStore(filesDir, "persona")
        store.call(createRequest(id, "Validated"))
        store.call(validSave(id, "before", "before memory", listOf("files")))
        val configFile = File(filesDir, "BOT/$id/bot-config.json")
        val memoryFile = File(filesDir, "BOT/$id/memory/MEMORY.md")
        val beforeConfig = configFile.readText()
        val beforeMemory = memoryFile.readText()

        val invalidInputs =
            listOf(
                validSave(id, "x".repeat(16_001), "ok", listOf("files")),
                validSave(id, "ok", "x".repeat(24_001), listOf("files")),
                validSave(id, "ok", "ok", listOf("unknown-tool")),
                validSave(id, "ok", "ok", listOf("files")).put("enabledTools", "files"),
                validSave(id, "ok", "ok", listOf("files")).put("path", "/tmp/escape"),
                validSave(id, "ok", "ok", listOf("files")).put("memory", JSONObject.NULL),
            )
        for (input in invalidInputs) {
            rejects { store.call(input) }
            assertEquals(beforeConfig, configFile.readText())
            assertEquals(beforeMemory, memoryFile.readText())
        }

        val afterReopen = BotWorkspaceStore(filesDir, "other seed").call(settingsRequest(id))
        assertEquals("before", afterReopen.getString("instructions"))
        assertEquals("before memory", afterReopen.getString("memory"))
    }

    @Test
    fun savePersonaRejectsLongTextWithoutOverwritingExistingPersona() {
        val store = BotWorkspaceStore(filesDir, "seed")
        store.call(JSONObject().put("op", "savePersona").put("text", "current"))

        rejects {
            store.call(JSONObject().put("op", "savePersona").put("text", "x".repeat(16_001)))
        }
        rejects {
            store.call(JSONObject().put("op", "savePersona").put("text", 42))
        }
        rejects {
            store.call(
                JSONObject()
                    .put("op", "savePersona")
                    .put("text", "next")
                    .put("path", "/tmp/persona"),
            )
        }

        assertEquals("current", store.call(JSONObject().put("op", "persona")).getString("text"))
        assertEquals("current", File(filesDir, "CONFIG/PERSONA.md").readText())
    }

    @Test
    fun contextAcceptsOnlyCanonicalBotWorkspace() {
        val store = BotWorkspaceStore(filesDir, "persona")
        store.call(JSONObject().put("op", "list"))
        assertEquals(
            "default",
            store.call(contextRequest("new", "/BOT/default/documents")).getString("id"),
        )
        for (cwd in
            listOf(
                "/workspace",
                "/bots/default",
                "/root/projects",
                "/root/documents",
                "/tmp/elsewhere",
            )) {
            rejects { store.call(contextRequest("new", cwd)) }
        }
    }

    @Test
    fun explicitSessionMappingWinsAndExcludesOtherBotMappings() {
        val firstId = "88888888-8888-4888-8888-888888888888"
        val secondId = "99999999-9999-4999-8999-999999999999"
        val store = BotWorkspaceStore(filesDir, "persona")
        store.call(createRequest(firstId, "First"))
        store.call(createRequest(secondId, "Second"))
        store.call(JSONObject().put("op", "bind").put("id", firstId).put("sessionId", "session-a"))
        store.call(JSONObject().put("op", "bind").put("id", secondId).put("sessionId", "session-b"))

        rejects { store.call(contextRequest("session-a", "/BOT/$secondId/project")) }
        rejects { store.call(contextRequest("session-a", "/BOT/default")) }
        val mappedAcrossCwd = store.call(contextRequest("session-a", "/BOT/$firstId/project"))
        assertEquals(firstId, mappedAcrossCwd.getString("id"))
        assertEquals("/BOT/$firstId", mappedAcrossCwd.getString("workspace"))
        assertEquals(listOf("session-a"), strings(mappedAcrossCwd.getJSONArray("sessionIds")))
        assertEquals(
            listOf("session-b"),
            strings(mappedAcrossCwd.getJSONArray("excludedSessionIds")),
        )
        assertFalse(mappedAcrossCwd.getBoolean("legacyMemory"))

        rejects { store.call(contextRequest("session-b", "/workspace")) }

        val byBotCwd = store.call(contextRequest("new-session", "/BOT/$secondId/subdir"))
        assertEquals(secondId, byBotCwd.getString("id"))
        assertEquals(listOf("session-b"), strings(byBotCwd.getJSONArray("sessionIds")))
        assertEquals(listOf("session-a"), strings(byBotCwd.getJSONArray("excludedSessionIds")))

        val byLegacyCwd =
            store.call(contextRequest("another-new-session", "/BOT/default/documents"))
        assertEquals("default", byLegacyCwd.getString("id"))
        assertEquals(
            listOf("session-a", "session-b"),
            strings(byLegacyCwd.getJSONArray("excludedSessionIds")),
        )
        assertTrue(byLegacyCwd.getBoolean("legacyMemory"))
    }

    @Test
    fun symlinkedWorkspaceAndConfigPathsAreRejectedWithoutWritingOutside() {
        val id = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        BotStore(File(filesDir, "bots.json")).call(createRequest(id, "Symlink"))
        File(filesDir, "BOT").mkdirs()
        Files.createSymbolicLink(
            File(filesDir, "BOT/$id").toPath(),
            outsideDir.toPath(),
        )

        val store = BotWorkspaceStore(filesDir, "persona")
        fails { store.call(JSONObject().put("op", "list")) }
        assertOutsideEmpty()

        Files.delete(File(filesDir, "BOT/$id").toPath())
        store.call(JSONObject().put("op", "list"))
        val defaultWorkspace = File(filesDir, "BOT/default").apply { mkdirs() }
        val defaultConfig = File(defaultWorkspace, "bot-config.json")
        Files.delete(defaultConfig.toPath())
        val outsideConfig = File(outsideDir, "bot-config.json")
        outsideConfig.writeText("outside sentinel")
        Files.createSymbolicLink(defaultConfig.toPath(), outsideConfig.toPath())

        fails { store.call(JSONObject().put("op", "settings").put("id", "default")) }
        assertEquals("outside sentinel", outsideConfig.readText())

        Files.delete(defaultConfig.toPath())
        val configDir = File(filesDir, "CONFIG")
        File(configDir, "PERSONA.md").writeText("safe persona")
        val outsideLearned = File(outsideDir, "PERSONA_LEARNED.json")
        outsideLearned.writeText("outside learned sentinel")
        Files.createSymbolicLink(
            File(configDir, "PERSONA_LEARNED.json").toPath(),
            outsideLearned.toPath(),
        )
        fails {
            store.call(
                learningRequest("symlink", mapOf("warmth" to "warm")),
            )
        }
        assertEquals("outside learned sentinel", outsideLearned.readText())
    }

    @Test
    fun oversizedAgentEditedMemoryIsRetainedAndFailsExplicitly() {
        val store = BotWorkspaceStore(filesDir, "persona")
        store.call(JSONObject().put("op", "list"))
        val file = File(filesDir, "BOT/default/memory/MEMORY.md")
        file.writeText("x".repeat(24_001))
        rejects { store.call(settingsRequest("default")) }
        assertEquals(24_001L, file.length())
    }

    @Test
    fun memoryWriteFailureRollsBackChangedInstructionsAndTools() {
        val store = BotWorkspaceStore(filesDir, "persona")
        store.call(JSONObject().put("op", "list"))
        store.call(validSave("default", "before", "old memory", listOf("files")))
        val memoryDir = File(filesDir, "BOT/default/memory")
        check(memoryDir.setWritable(false, false))
        try {
            fails { store.call(validSave("default", "after", "new memory", listOf("web"))) }
            val after = store.call(settingsRequest("default"))
            assertEquals("before", after.getString("instructions"))
            assertEquals("old memory", after.getString("memory"))
            assertEquals(listOf("files"), strings(after.getJSONArray("enabledTools")))
        } finally {
            memoryDir.setWritable(true, true)
        }
    }

    private fun assertDefaultWorkspaceLayout(workspace: File) {
        assertTrue(File(workspace, "bot-config.json").isFile)
        assertTrue(File(workspace, "memory/MEMORY.md").isFile)
        assertTrue(File(workspace, "archives").isDirectory)
        assertTrue(File(workspace, "skills").isDirectory)
        assertEquals(0L, File(workspace, "memory/MEMORY.md").length())
        assertEquals(0, File(workspace, "archives").listFiles()!!.size)
        val config = JSONObject(File(workspace, "bot-config.json").readText())
        assertEquals("", config.getString("instructions"))
        assertEquals(DEFAULT_TOOLS, strings(config.getJSONArray("enabledTools")))
    }

    private fun assertOutsideEmpty() {
        assertEquals(0, outsideDir.listFiles()!!.size)
    }

    private fun createRequest(id: String, name: String): JSONObject =
        JSONObject().put("op", "create").put("id", id).put("name", name)

    private fun settingsRequest(id: String): JSONObject =
        JSONObject().put("op", "settings").put("id", id)

    private fun validSave(
        id: String,
        instructions: String,
        memory: String,
        tools: List<String>,
        compactionThreshold: Double? = null,
    ): JSONObject {
        val request =
            JSONObject()
                .put("op", "saveSettings")
                .put("id", id)
                .put("instructions", instructions)
                .put("memory", memory)
                .put("enabledTools", JSONArray(tools))
        if (compactionThreshold != null) request.put("compactionThreshold", compactionThreshold)
        return request
    }

    private fun contextRequest(sessionId: String, cwd: String): JSONObject =
        JSONObject().put("op", "context").put("sessionId", sessionId).put("cwd", cwd)

    private fun learningRequest(
        revision: String,
        traits: Map<String, String>,
        evidenceCount: Int = 3,
        updatedAt: String = "2026-10-24T01:02:03Z",
    ): JSONObject {
        val traitJson = JSONObject()
        for ((key, value) in traits) traitJson.put(key, value)
        return JSONObject()
            .put("op", "learning")
            .put(
                "profile",
                JSONObject()
                    .put("revision", revision)
                    .put("traits", traitJson)
                    .put("evidenceCount", evidenceCount)
                    .put("updatedAt", updatedAt),
            )
    }

    private fun bot(registry: JSONObject, id: String): JSONObject {
        val bots = registry.getJSONArray("bots")
        for (index in 0 until bots.length()) {
            val bot = bots.getJSONObject(index)
            if (bot.getString("id") == id) return bot
        }
        throw AssertionError("Missing bot: $id")
    }

    private fun strings(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)

    private fun botIds(registry: JSONObject): List<String> =
        (0 until registry.getJSONArray("bots").length()).map { index ->
            registry.getJSONArray("bots").getJSONObject(index).getString("id")
        }

    private inline fun rejects(action: () -> Unit) {
        try {
            action()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }

    private inline fun fails(action: () -> Unit): Exception {
        try {
            action()
        } catch (error: Exception) {
            return error
        }
        throw AssertionError("Expected operation failure")
    }

    private companion object {
        const val defaultSeed = "synthetic default persona"

        val DEFAULT_TOOLS =
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
    }
}
