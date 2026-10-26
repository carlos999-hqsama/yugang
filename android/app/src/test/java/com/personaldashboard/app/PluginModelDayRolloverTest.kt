package com.personaldashboard.app

import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginModelDayRolloverTest {
    private val directories = mutableListOf<java.io.File>()

    @After
    fun cleanDirectories() {
        directories.forEach { it.deleteRecursively() }
    }

    private fun directory() =
        Files.createTempDirectory("pd-plugin-model-days").toFile().also { directories += it }

    @Test
    fun `late old-day settle and release preserve new-day accounting and attribute old usage`() {
        val dir = directory()
        val store = PluginModelStore(dir)
        store.setBudget("budget-p", 300L, "2026-10-23")
        store.reserve("budget-p", 100L, "2026-10-23", 300L)
        store.reserve("budget-p", 50L, "2026-10-23", 300L)

        assertRecord(store.record("budget-p", "2026-10-24"), 300L, 0L, 0L)
        store.reserve("budget-p", 300L, "2026-10-24", 300L)
        store.settleUsage("budget-p", 40L, 100L, "2026-10-24")
        store.setBudget("other", 500L, "2026-10-24")
        store.reserve("other", 50L, "2026-10-24", 500L)
        assertRecord(store.record("budget-p", "2026-10-24"), 300L, 40L, 200L)

        val lateSettled = store.settleUsage("budget-p", 80L, 100L, "2026-10-23")
        assertRecord(lateSettled, 300L, 80L, 50L)
        assertRecord(store.record("budget-p", "2026-10-24"), 300L, 40L, 200L)
        val lateReleased = store.releaseReservation("budget-p", 50L, "2026-10-23")
        assertRecord(lateReleased, 300L, 80L, 0L)

        // Reading the old day is also read-only with respect to the current day's counters.
        assertRecord(store.record("budget-p", "2026-10-23"), 300L, 80L, 0L)
        assertRecord(store.record("budget-p", "2026-10-24"), 300L, 40L, 200L)
        assertRecord(store.record("other", "2026-10-24"), 500L, 0L, 50L)

        val root = JSONObject(java.io.File(dir, "model-budgets.json").readText())
        val current = root.getJSONObject("records").getJSONObject("budget-p")
        assertEquals("2026-10-24", current.getString("day"))
        assertEquals(40L, current.getLong("usedTokens"))
        assertEquals(200L, current.getLong("reservedTokens"))
        val old = root.getJSONObject("history").getJSONObject("budget-p").getJSONObject("2026-10-23")
        assertEquals("2026-10-23", old.getString("day"))
        assertEquals(80L, old.getLong("usedTokens"))
        assertEquals(0L, old.getLong("reservedTokens"))
        assertFalse(root.getJSONObject("history").has("other"))
    }

    @Test
    fun `legacy current-day JSON accepts late usage without backdating or migrating other plugins`() {
        val dir = directory()
        val legacy =
            JSONObject()
                .put(
                    "records",
                    JSONObject()
                        .put("budget-p", row("2026-10-24", 300L, 40L, 200L))
                        .put("other", row("2026-10-24", 500L, 7L, 20L)),
                )
        java.io.File(dir, "model-budgets.json").writeText(legacy.toString())
        val store = PluginModelStore(dir)

        // No archived old row exists in legacy JSON; known usage still needs an old-day owner.
        val late = store.settleUsage("budget-p", 80L, 100L, "2026-10-23")
        assertRecord(late, 300L, 80L, 0L)
        assertRecord(store.record("budget-p", "2026-10-24"), 300L, 40L, 200L)
        assertRecord(store.record("budget-p", "2026-10-23"), 300L, 80L, 0L)
        assertRecord(store.record("other", "2026-10-24"), 500L, 7L, 20L)

        val root = JSONObject(java.io.File(dir, "model-budgets.json").readText())
        assertEquals("2026-10-24", root.getJSONObject("records").getJSONObject("budget-p").getString("day"))
        assertTrue(root.getJSONObject("history").has("budget-p"))
        assertFalse(root.getJSONObject("history").has("other"))
    }

    private fun row(day: String, budget: Long, used: Long, reserved: Long) =
        JSONObject()
            .put("day", day)
            .put("budgetTokens", budget)
            .put("usedTokens", used)
            .put("reservedTokens", reserved)

    private fun assertRecord(
        record: PluginModelStore.BudgetRecord,
        budget: Long,
        used: Long,
        reserved: Long,
    ) {
        assertEquals(budget, record.budgetTokens)
        assertEquals(used, record.usedTokens)
        assertEquals(reserved, record.reservedTokens)
    }
}
