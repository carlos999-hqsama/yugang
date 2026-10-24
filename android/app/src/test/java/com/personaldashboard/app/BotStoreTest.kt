package com.personaldashboard.app

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BotStoreTest {
    private lateinit var directory: File
    private lateinit var file: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("pd-bot-store").toFile()
        file = File(directory, "bots.json")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun fishShapeAndColorStayIndependentAcrossReloadAndPartialSave() {
        val id="11111111-1111-4111-8111-111111111111"
        val store=BotStore(file)
        store.call(JSONObject().put("op","create").put("id",id).put("name","Fish").put("fish","whale").put("avatar","pebble").put("color","blue"))
        store.call(JSONObject().put("op","rename").put("id",id).put("name","Fish").put("fish","ray"))
        val reloaded=BotStore(file).call(JSONObject().put("op","list"))
        assertEquals("ray",bot(reloaded,id).getString("fish"))
        assertEquals("pebble",bot(reloaded,id).getString("avatar"))
        assertEquals("blue",bot(reloaded,id).getString("color"))
        assertEquals("ink",bot(reloaded,"default").getString("color"))
        try {
            store.call(JSONObject().put("op","rename").put("id",id).put("name","Fish").put("color","url(https://invalid.example)"))
            throw AssertionError("invalid palette value must fail")
        } catch(_:IllegalArgumentException){}
        assertEquals("blue",bot(BotStore(file).call(JSONObject().put("op","list")),id).getString("color"))
    }

    @Test
    fun fishAndShapeAreIndependentAndPersistTogether() {
        val store = BotStore(file)
        val id = "11111111-1111-4111-8111-111111111111"
        store.call(JSONObject().put("op","create").put("id",id).put("name","Fish").put("avatar","hexagon").put("fish","ray"))
        store.call(JSONObject().put("op","rename").put("id",id).put("name","Fish").put("fish","swordfish"))
        val registry = BotStore(file).call(JSONObject().put("op","list"))
        assertEquals("hexagon", bot(registry,id).getString("avatar"))
        assertEquals("swordfish", bot(registry,id).getString("fish"))
        assertEquals("whale", bot(registry,"default").getString("fish"))
        try {
            store.call(JSONObject().put("op","rename").put("id",id).put("name","Fish").put("fish","<svg onload=evil>"))
            throw AssertionError("invalid fish must be rejected")
        } catch (expected: IllegalArgumentException) { }
        assertEquals("swordfish", bot(BotStore(file).call(JSONObject().put("op","list")),id).getString("fish"))
    }

    @Test
    fun bindingImplicitDefaultSelectionClearsOnlyItsStaleSelection() {
        val store = BotStore(file)
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        store.call(JSONObject().put("op", "select").put("id", "default").put("sessionId", "legacy"))
        store.call(JSONObject().put("op", "create").put("id", id).put("name", "Writer"))
        val result = store.call(JSONObject().put("op", "bind").put("id", id).put("sessionId", "legacy"))
        assertTrue(bot(result, "default").isNull("selectedSessionId"))
        assertEquals("legacy", bot(result, id).getString("selectedSessionId"))
    }

    @Test
    fun firstListWithoutFileReturnsIndependentDefaultRegistry() {
        val store = BotStore(file)

        val result = store.call(JSONObject().put("op", "list"))

        assertEquals("default", result.getString("defaultBotId"))
        assertEquals(1, result.getJSONArray("bots").length())
        val default = result.getJSONArray("bots").getJSONObject(0)
        assertEquals("default", default.getString("id"))
        assertEquals("主 Bot", default.getString("name"))
        assertEquals("circle", default.getString("avatar"))
        assertTrue(default.getBoolean("pinned"))
        assertEquals(0, default.getJSONArray("sessionIds").length())
        assertTrue(default.isNull("selectedSessionId"))

        result.getJSONArray("bots").put(JSONObject().put("id", "external-change"))
        result.getJSONArray("bots").getJSONObject(0).put("name", "Changed externally")
        val freshResult = store.call(JSONObject().put("op", "list"))
        assertEquals(1, freshResult.getJSONArray("bots").length())
        assertEquals("主 Bot", freshResult.getJSONArray("bots").getJSONObject(0).getString("name"))
        assertFalse(file.exists())
    }

    @Test
    fun createWithSameIdAndNameIsIdempotentAndSurvivesRealDiskReload() {
        val store = BotStore(file)
        val id = "11111111-1111-4111-8111-111111111111"

        store.call(
            JSONObject()
                .put("op", "create")
                .put("id", id)
                .put("name", " Work ")
        )
        val duplicate = store.call(
            JSONObject()
                .put("op", "create")
                .put("id", id)
                .put("name", "Work")
        )
        rejects {
            store.call(
                JSONObject()
                    .put("op", "create")
                    .put("id", id)
                    .put("name", "Different")
            )
        }

        assertEquals(2, duplicate.getJSONArray("bots").length())
        val created = bot(duplicate, id)
        assertEquals("Work", created.getString("name"))
        assertEquals("circle", created.getString("avatar"))
        assertFalse(created.getBoolean("pinned"))
        assertEquals(0, created.getJSONArray("sessionIds").length())
        assertTrue(created.isNull("selectedSessionId"))

        val reloaded = BotStore(file).call(JSONObject().put("op", "list"))
        assertEquals(2, reloaded.getJSONArray("bots").length())
        assertEquals("Work", bot(reloaded, id).getString("name"))
    }

    @Test
    fun renameAndPinPersistAcrossRealDiskReload() {
        val store = BotStore(file)
        val id = "22222222-2222-4222-8222-222222222222"
        store.call(JSONObject().put("op", "create").put("id", id).put("name", "Research").put("avatar", "hexagon"))

        val renamed = store.call(
            JSONObject().put("op", "rename").put("id", id).put("name", " Research Bot ")
        )
        store.call(JSONObject().put("op", "pin").put("id", id).put("pinned", false))
        store.call(JSONObject().put("op", "pin").put("id", "default").put("pinned", false))

        val reloaded = BotStore(file).call(JSONObject().put("op", "list"))
        assertEquals("Research Bot", bot(renamed, id).getString("name"))
        assertEquals("Research Bot", bot(reloaded, id).getString("name"))
        assertEquals("hexagon", bot(reloaded, id).getString("avatar"))
        assertFalse(bot(reloaded, id).getBoolean("pinned"))
        assertFalse(bot(reloaded, "default").getBoolean("pinned"))
    }

    @Test
    fun avatarChoicePersistsAndLegacyRegistryReceivesSafeDefaultWithoutDiskMigration() {
        val id = "88888888-8888-4888-8888-888888888888"
        val store = BotStore(file)
        writeLegacyBot(store, id, "Legacy")

        val legacy = BotStore(file).call(JSONObject().put("op", "list"))
        assertEquals("circle", bot(legacy, id).getString("avatar"))
        assertFalse(JSONObject(file.readText()).getJSONArray("bots").getJSONObject(1).has("avatar"))

        store.call(
            JSONObject()
                .put("op", "rename")
                .put("id", id)
                .put("name", "Writer")
                .put("avatar", "hexagon")
        )
        val reloaded = BotStore(file).call(JSONObject().put("op", "list"))
        assertEquals("hexagon", bot(reloaded, id).getString("avatar"))
    }

    @Test
    fun deleteProtectsBuiltInsButAllowsExplicitBoundUserBotDeletion() {
        val store = BotStore(file)
        val boundId = "99999999-9999-4999-8999-999999999999"
        store.call(JSONObject().put("op", "create").put("id", boundId).put("name", "Bound"))
        store.call(JSONObject().put("op", "bind").put("id", boundId).put("sessionId", "active"))

        rejects { store.call(JSONObject().put("op", "delete").put("id", "default")) }
        rejects { store.call(JSONObject().put("op", "delete").put("id", PluginDeveloperBot.ID)) }

        val result = store.call(JSONObject().put("op", "delete").put("id", boundId))
        assertEquals(1, result.getJSONArray("bots").length())
        assertEquals(1, BotStore(file).call(JSONObject().put("op", "list")).getJSONArray("bots").length())
    }

    @Test
    fun multipleBotsKeepBindingsAndSelectionsIsolatedWhileDefaultOwnsLegacySessions() {
        val store = BotStore(file)
        val firstId = "33333333-3333-4333-8333-333333333333"
        val secondId = "44444444-4444-4444-8444-444444444444"
        store.call(JSONObject().put("op", "create").put("id", firstId).put("name", "First"))
        store.call(JSONObject().put("op", "create").put("id", secondId).put("name", "Second"))

        store.call(JSONObject().put("op", "bind").put("id", firstId).put("sessionId", "dsh-first"))
        store.call(JSONObject().put("op", "bind").put("id", secondId).put("sessionId", "dsh-second"))
        store.call(JSONObject().put("op", "select").put("id", firstId).put("sessionId", "dsh-first"))
        store.call(JSONObject().put("op", "select").put("id", secondId).put("sessionId", "dsh-second"))

        val mapped = store.call(JSONObject().put("op", "list"))
        assertEquals(listOf("dsh-first"), sessionIds(bot(mapped, firstId)))
        assertEquals(listOf("dsh-second"), sessionIds(bot(mapped, secondId)))
        assertEquals("dsh-first", bot(mapped, firstId).getString("selectedSessionId"))
        assertEquals("dsh-second", bot(mapped, secondId).getString("selectedSessionId"))
        assertEquals(0, bot(mapped, "default").getJSONArray("sessionIds").length())

        rejects {
            store.call(
                JSONObject().put("op", "bind").put("id", secondId).put("sessionId", "dsh-first")
            )
        }
        rejects {
            store.call(
                JSONObject().put("op", "select").put("id", "default").put("sessionId", "dsh-first")
            )
        }
        rejects {
            store.call(
                JSONObject().put("op", "select").put("id", firstId).put("sessionId", "dsh-unbound")
            )
        }

        val afterRejectedMoves = store.call(JSONObject().put("op", "list"))
        assertEquals(listOf("dsh-first"), sessionIds(bot(afterRejectedMoves, firstId)))
        assertEquals(listOf("dsh-second"), sessionIds(bot(afterRejectedMoves, secondId)))

        store.call(JSONObject().put("op", "select").put("id", "default").put("sessionId", "legacy-dsh-id"))
        val defaultSelected = store.call(JSONObject().put("op", "list"))
        assertEquals("legacy-dsh-id", bot(defaultSelected, "default").getString("selectedSessionId"))
        assertEquals(0, bot(defaultSelected, "default").getJSONArray("sessionIds").length())

        store.call(JSONObject().put("op", "bind").put("id", firstId).put("sessionId", "legacy-dsh-id"))
        val reassigned = store.call(JSONObject().put("op", "list"))
        assertEquals(0, bot(reassigned, "default").getJSONArray("sessionIds").length())
        assertTrue(bot(reassigned, "default").isNull("selectedSessionId"))
        assertTrue("legacy-dsh-id" in sessionIds(bot(reassigned, firstId)))
    }

    @Test
    fun writeFailureDoesNotChangeInMemoryRegistry() {
        val store = BotStore(file)
        val id = "55555555-5555-4555-8555-555555555555"
        store.call(JSONObject().put("op", "create").put("id", id).put("name", "Before failure"))
        val before = store.call(JSONObject().put("op", "list")).toString()

        assertTrue(file.delete())
        assertTrue(file.mkdirs())
        fails {
            store.call(JSONObject().put("op", "rename").put("id", id).put("name", "After failure"))
        }

        assertEquals(before, store.call(JSONObject().put("op", "list")).toString())
        assertTrue(file.isDirectory)
    }

    @Test
    fun corruptFileIsReportedAndNeverReplacedWithAnEmptyRegistry() {
        val corrupt = "{ not json"
        file.writeText(corrupt)

        try {
            BotStore(file)
            throw AssertionError("Expected corrupt bots.json to fail")
        } catch (_: IllegalStateException) {
        }

        assertEquals(corrupt, file.readText())
    }

    @Test
    fun invalidOperationsAndParametersAreRejected() {
        val store = BotStore(file)
        val validId = "66666666-6666-4666-8666-666666666666"

        rejects { store.call(JSONObject()) }
        rejects { store.call(JSONObject().put("op", "delete")) }
        rejects {
            store.call(
                JSONObject().put("op", "create").put("id", "default").put("name", "Default")
            )
        }
        rejects {
            store.call(JSONObject().put("op", "create").put("id", "not-an-uuid").put("name", "Bad"))
        }
        rejects {
            store.call(JSONObject().put("op", "create").put("id", validId).put("name", "  "))
        }
        rejects {
            store.call(
                JSONObject().put("op", "create")
                    .put("id", validId)
                    .put("name", "a".repeat(81))
            )
        }
        rejects {
            store.call(JSONObject().put("op", "rename").put("id", validId).put("name", "Unknown"))
        }
        rejects {
            store.call(JSONObject().put("op", "pin").put("id", "default").put("pinned", "true"))
        }
        rejects {
            store.call(JSONObject().put("op", "bind").put("id", "default").put("sessionId", ""))
        }
        rejects {
            store.call(
                JSONObject().put("op", "bind").put("id", "default").put("sessionId", ("bad" + 0.toChar() + "id"))
            )
        }
        rejects {
            store.call(
                JSONObject().put("op", "bind").put("id", "default").put("sessionId", "x".repeat(257))
            )
        }
        rejects {
            store.call(
                JSONObject().put("op", "create")
                    .put("id", validId)
                    .put("name", "Avatar")
                    .put("avatar", "colored-circle")
            )
        }

        assertEquals(1, store.call(JSONObject().put("op", "list")).getJSONArray("bots").length())
        assertFalse(file.exists())
    }

    @Test
    fun dshSessionIdsDoNotNeedToBeUuids() {
        val store = BotStore(file)
        val id = "77777777-7777-4777-8777-777777777777"
        val sessionId = "dsh:session#001"
        store.call(JSONObject().put("op", "create").put("id", id).put("name", "DSH"))

        store.call(JSONObject().put("op", "bind").put("id", id).put("sessionId", sessionId))

        val result = BotStore(file).call(JSONObject().put("op", "list"))
        assertEquals(sessionId, bot(result, id).getString("selectedSessionId"))
        assertEquals(listOf(sessionId), sessionIds(bot(result, id)))
    }

    private fun bot(registry: JSONObject, id: String): JSONObject {
        val bots = registry.getJSONArray("bots")
        for (index in 0 until bots.length()) {
            val candidate = bots.getJSONObject(index)
            if (candidate.getString("id") == id) return candidate
        }
        throw IllegalArgumentException("Bot not found: $id")
    }

    private fun sessionIds(bot: JSONObject): List<String> {
        val values = bot.getJSONArray("sessionIds")
        return (0 until values.length()).map(values::getString)
    }

    private fun writeLegacyBot(store: BotStore, id: String, name: String) {
        store.call(
            JSONObject()
                .put("op", "create")
                .put("id", id)
                .put("name", name)
        )
        val registry = JSONObject(file.readText())
        registry.getJSONArray("bots").getJSONObject(1).remove("avatar")
        file.writeText(registry.toString())
    }

    private inline fun rejects(action: () -> Unit) {
        try {
            action()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }

    private inline fun fails(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            return
        }
        throw AssertionError("Expected operation failure")
    }
}
