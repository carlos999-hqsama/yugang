package com.personaldashboard.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PluginBackgroundPolicyTest {
    private fun directory() = Files.createTempDirectory("pd-plugin-background").toFile()

    private fun record(
        pluginId: String = "test-plugin",
        revision: String = "r1",
        action: String = "refresh",
        interval: Long = PluginBackgroundPolicy.MIN_INTERVAL_MS,
        payload: JSONObject = JSONObject().put("page", 1)
    ) = PluginBackgroundStore.ScheduleRecord(
        pluginId = pluginId,
        jobId = PluginBackgroundPolicy.jobId(pluginId),
        revision = revision,
        action = action,
        intervalMs = interval,
        payload = payload,
        createdAtMs = 1L
    )

    @Test
    fun `late result from a replaced schedule cannot overwrite new status`() {
        val store = PluginBackgroundStore(directory())
        val old = record()
        store.put(old)
        val next = record(action="sync")
        store.put(next)
        store.markRun(old.pluginId, 123L, "cancelled", 100L, old.generation)
        assertEquals("never",store.get(old.pluginId)!!.lastStatus)
        store.markRun(next.pluginId, 124L, "ok", 120L, next.generation)
        assertEquals("ok",store.get(next.pluginId)!!.lastStatus)
    }

    @Test
    fun `generation receipt after removal does not recreate schedule`() {
        val store = PluginBackgroundStore(directory())
        val old = record()
        store.put(old)
        val read = store.get(old.pluginId)!!
        store.remove(read.pluginId)

        store.markRun(read.pluginId, 123L, "preempted", -1L, read.generation)

        assertNull(store.get(old.pluginId))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `generation receipt for replaced schedule leaves replacement untouched`() {
        val store = PluginBackgroundStore(directory())
        val old = record()
        store.put(old)
        val next = record(action = "sync", revision = "r2")
        store.put(next)
        store.markRun(next.pluginId, 100L, "ok", 50L, next.generation)

        store.markRun(old.pluginId, 123L, "preempted", -1L, old.generation)

        val current = store.get(next.pluginId)!!
        assertEquals(next.generation, current.generation)
        assertEquals("sync", current.action)
        assertEquals("r2", current.revision)
        assertEquals(100L, current.lastRunAtMs)
        assertEquals("ok", current.lastStatus)
        assertEquals(50L, current.lastDurationMs)
    }

    @Test
    fun `generationless status write still fails when schedule is missing`() {
        val store = PluginBackgroundStore(directory())
        val error = assertThrows(IllegalStateException::class.java) {
            store.markRun("test-plugin", 123L, "preempted", -1L)
        }
        assertEquals("插件后台计划不存在，不能写入执行状态", error.message)
        assertNull(store.get("test-plugin"))
    }

    @Test
    fun `corrupt storage still fails generation status writes`() {
        val dir = directory()
        val store = PluginBackgroundStore(dir)
        val old = record()
        store.put(old)
        java.io.File(dir, "background.json").writeText("{broken")

        val error = assertThrows(IllegalStateException::class.java) {
            store.markRun(old.pluginId, 123L, "preempted", -1L, old.generation)
        }
        assertEquals("宿主插件后台存储损坏，已停止后台状态操作", error.message)
        assertTrue(error.cause != null)
    }

    @Test
    fun `one plugin has one replaceable periodic record across instances`() {
        val dir = directory()
        val a = PluginBackgroundStore(dir)
        val b = PluginBackgroundStore(dir)
        a.put(record())
        b.put(record(action = "sync", interval = PluginBackgroundPolicy.MAX_INTERVAL_MS, payload = JSONObject().put("x", 2)))
        val all = b.all()
        assertEquals(1, all.size)
        assertEquals("sync", all.single().action)
        assertEquals(PluginBackgroundPolicy.MAX_INTERVAL_MS, all.single().intervalMs)
        assertEquals(PluginBackgroundPolicy.jobId("test-plugin"), all.single().jobId)
    }

    @Test
    fun `two plugins can interleave updates through separate store instances`() {
        val dir = directory()
        val a = PluginBackgroundStore(dir)
        val b = PluginBackgroundStore(dir)
        a.put(record("plugin-a"))
        b.put(record("plugin-b"))
        val ready = CountDownLatch(2)
        val done = CountDownLatch(2)
        val failures = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.execute {
                ready.countDown()
                runCatching { a.put(record("plugin-a", action = "a2")) }.onFailure { failures.incrementAndGet() }
                done.countDown()
            }
            pool.execute {
                ready.countDown()
                runCatching { b.put(record("plugin-b", action = "b2")) }.onFailure { failures.incrementAndGet() }
                done.countDown()
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertTrue(done.await(2, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(0, failures.get())
        val actions = a.all().associate { it.pluginId to it.action }
        assertEquals(mapOf("plugin-a" to "a2", "plugin-b" to "b2"), actions)
    }

    @Test
    fun `notification reservation and rollback are atomic across instances`() {
        val dir = directory()
        val a = PluginBackgroundStore(dir)
        val b = PluginBackgroundStore(dir)
        val now = 10_000_000L
        assertTrue(a.reserveNotification("test-plugin", now))
        assertFalse(b.reserveNotification("test-plugin", now + 59_000))
        assertTrue(a.reserveNotification("test-plugin", now + 60_000))
        assertEquals(listOf(now, now + 60_000), b.notificationTimes("test-plugin"))

        b.rollbackNotification("test-plugin", now + 60_000)
        assertEquals(listOf(now), a.notificationTimes("test-plugin"))
    }

    @Test
    fun `concurrent same timestamp notification reservations allow exactly one`() {
        val dir = directory()
        val storeCount = 8
        val stores = (1..storeCount).map { PluginBackgroundStore(dir) }
        val start = CountDownLatch(1)
        val ready = CountDownLatch(storeCount)
        val allowed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(storeCount)
        try {
            stores.forEach { store ->
                pool.execute {
                    ready.countDown()
                    start.await()
                    if (store.reserveNotification("test-plugin", 123L)) allowed.incrementAndGet()
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, allowed.get())
        assertEquals(1, stores.first().notificationTimes("test-plugin").size)
    }

    @Test fun `interval action payload and notification limits have hard bounds`() {
        PluginBackgroundPolicy.action("refresh")
        PluginBackgroundPolicy.interval(PluginBackgroundPolicy.MIN_INTERVAL_MS)
        PluginBackgroundPolicy.payload(JSONObject())
        try { PluginBackgroundPolicy.interval(PluginBackgroundPolicy.MIN_INTERVAL_MS - 1); throw AssertionError("必须遵守 15 分钟下限") }
        catch (_: IllegalArgumentException) {}
        try { PluginBackgroundPolicy.action("../bad"); throw AssertionError("后台动作必须受命名限制") }
        catch (_: IllegalArgumentException) {}
        try {
            PluginBackgroundPolicy.payload(JSONObject().put("data", "x".repeat(17 * 1024)))
            throw AssertionError("payload 必须有硬上限")
        } catch (_: IllegalArgumentException) {}
        try { PluginBackgroundPolicy.notification("", "正文"); throw AssertionError("标题不能为空") }
        catch (_: IllegalArgumentException) {}
        try {
            PluginBackgroundPolicy.notification("标题", "x".repeat(PluginBackgroundPolicy.MAX_NOTIFICATION_BODY_CHARS + 1))
            throw AssertionError("正文长度必须受限")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `disabled stale or capability-missing schedules are invalid and cancellation keeps rate accounting`() {
        val capabilities = setOf(PluginBackgroundPolicy.BACKGROUND_CAPABILITY)
        val item = record()
        assertTrue(PluginBackgroundPolicy.scheduleValid(item, true, "r1", capabilities))
        assertFalse(PluginBackgroundPolicy.scheduleValid(item, false, "r1", capabilities))
        assertFalse(PluginBackgroundPolicy.scheduleValid(item, true, "r2", capabilities))
        assertFalse(PluginBackgroundPolicy.scheduleValid(item, true, "r1", emptySet()))

        val store = PluginBackgroundStore(directory())
        store.put(item)
        assertTrue(store.reserveNotification("test-plugin", 123L))
        store.remove("test-plugin")
        assertTrue(store.all().isEmpty())
        assertEquals(1, store.notificationTimes("test-plugin").size)
    }

    @Test
    fun `status reports bounded results without leaking payload`() {
        val store = PluginBackgroundStore(directory())
        val item = record(payload = JSONObject().put("secret", "payload"))
        store.put(item)
        store.markRun("test-plugin", 123L, "ok", 500L)
        val status = PluginBackgroundPolicy.statusJson(store.get("test-plugin"), true)
        assertTrue(status.getBoolean("scheduled"))
        assertEquals("ok", status.getString("lastStatus"))
        assertEquals(500L, status.getLong("lastDurationMs"))
        assertEquals(PluginBackgroundPolicy.MAX_RUN_MS, status.getLong("maxRunMs"))
        assertFalse(status.has("payload"))
        assertFalse(status.toString().contains("payload-secret"))
    }

    @Test
    fun `corrupt background storage fails closed`() {
        val dir = directory()
        PluginBackgroundStore(dir).put(record())
        java.io.File(dir, "background.json").writeText("{broken")
        try {
            PluginBackgroundStore(dir).all()
            throw AssertionError("损坏后台存储不能被静默清空")
        } catch (_: IllegalStateException) {}
    }

    @Test fun `job identifiers avoid collisions with other plugin schedules`() {
        val first = PluginBackgroundPolicy.jobId("test-plugin")
        val second = PluginBackgroundPolicy.jobId("other-plugin", setOf(first))
        assertTrue(first != second)
        assertTrue(PluginBackgroundPolicy.isHostJobId(second))
    }

    @Test
    fun `missing notification history is initialized while schedules survive`() {
        val dir = directory()
        PluginBackgroundStore(dir).put(record())
        val file = java.io.File(dir, "background.json")
        file.writeText(JSONObject(file.readText()).apply { remove("notifications") }.toString())
        assertFalse(JSONObject(file.readText()).has("notifications"))

        val store = PluginBackgroundStore(dir)
        assertEquals(1, store.all().size)
        assertEquals(emptyList<Long>(), store.notificationTimes("test-plugin"))
        assertTrue(store.reserveNotification("test-plugin", 123L))
        assertEquals(listOf(123L), store.notificationTimes("test-plugin"))
        assertEquals(1, store.all().size)
        assertTrue(JSONObject(file.readText()).getJSONObject("notifications").has("test-plugin"))
    }

    @Test
    fun `notification history that is not an object fails closed and keeps original bytes`() {
        for (value in listOf("[]", "\"corrupt\"", "3", "null")) {
            val dir = directory()
            val file = java.io.File(dir, "background.json")
            val before = "{\"schedules\":{},\"notifications\":$value}"
            file.writeText(before)
            val store = PluginBackgroundStore(dir)

            val error = assertThrows(IllegalStateException::class.java) { store.all() }
            assertEquals("宿主插件后台通知存储损坏，已停止后台状态操作", error.message)
            assertThrows(IllegalStateException::class.java) {
                store.reserveNotification("test-plugin", 123L)
            }
            assertThrows(IllegalStateException::class.java) { store.notificationTimes("test-plugin") }
            assertThrows(IllegalStateException::class.java) { store.put(record()) }
            assertEquals(before, file.readText())
        }
    }

    @Test
    fun `corrupt notification rows fail closed and keep original bytes`() {
        for (rows in listOf("[0]", "[1,\"x\"]", "{}", "\"corrupt\"")) {
            val dir = directory()
            val file = java.io.File(dir, "background.json")
            val before = "{\"schedules\":{},\"notifications\":{\"test-plugin\":$rows}}"
            file.writeText(before)

            val error =
                assertThrows(IllegalStateException::class.java) { PluginBackgroundStore(dir).all() }
            assertEquals("插件通知计数损坏，已停止后台状态操作", error.message)
            assertEquals(before, file.readText())
        }
    }

    @Test
    fun `colliding job ids keep separate notification identity`() {
        val first = PluginBackgroundPolicy.jobId("aaa")
        // Hash collision from the field report: both plugins land on the same raw job id.
        assertEquals(first, PluginBackgroundPolicy.jobId("akuo"))

        assertEquals("aaa", PluginBackgroundPolicy.notificationTag("aaa"))
        assertEquals("akuo", PluginBackgroundPolicy.notificationTag("akuo"))
        val a = PluginBackgroundPolicy.notificationOpenUri("aaa")
        val b = PluginBackgroundPolicy.notificationOpenUri("akuo")
        assertEquals("pd-plugin-notification://open/aaa", a)
        assertEquals("pd-plugin-notification://open/akuo", b)
        assertTrue(a != b)
        assertFalse(a.contains(first.toString()))
        assertThrows(IllegalArgumentException::class.java) {
            PluginBackgroundPolicy.notificationTag("AAA")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginBackgroundPolicy.notificationOpenUri("akuo/../aaa")
        }
    }
    @Test fun `minute conversion rejects values that would overflow into the allowed window`() {
        assertEquals(PluginBackgroundPolicy.MIN_INTERVAL_MS, PluginBackgroundPolicy.intervalMinutes(15))
        assertEquals(PluginBackgroundPolicy.MAX_INTERVAL_MS, PluginBackgroundPolicy.intervalMinutes(1440))
        for (value in listOf(Long.MIN_VALUE, -1L, 0L, 14L, 1441L, 307445734561841L, Long.MAX_VALUE)) {
            try { PluginBackgroundPolicy.intervalMinutes(value); throw AssertionError("Overflow or out-of-range interval accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
