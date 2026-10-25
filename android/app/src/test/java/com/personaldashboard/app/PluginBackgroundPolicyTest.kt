package com.personaldashboard.app

import org.json.JSONArray
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
        assertNull(PluginBackgroundPolicy.lastResult(store.get(old.pluginId)!!))
        store.markRun(next.pluginId, 124L, "ok", 120L, next.generation)
        assertEquals("ok",store.get(next.pluginId)!!.lastStatus)
        assertEquals(
            next.generation,
            PluginBackgroundPolicy.lastResult(store.get(next.pluginId)!!)!!.getString("generation"),
        )
    }

    @Test
    fun `generation receipt after removal does not recreate schedule`() {
        val dir = directory()
        val store = PluginBackgroundStore(dir)
        val old = record()
        store.put(old)
        val read = store.get(old.pluginId)!!
        store.remove(read.pluginId)

        store.markRun(
            read.pluginId,
            123L,
            "preempted",
            -1L,
            read.generation,
            result = PluginBackgroundPolicy.ActionResult("LATE_RESULT_CANARY"),
            errorMessage = "LATE_ERROR_CANARY",
        )

        assertNull(store.get(old.pluginId))
        assertTrue(store.all().isEmpty())
        val disk = java.io.File(dir, "background.json").readText()
        assertFalse(disk.contains("LATE_RESULT_CANARY"))
        assertFalse(disk.contains("LATE_ERROR_CANARY"))
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
        assertEquals(next.generation, PluginBackgroundPolicy.lastResult(current)!!.getString("generation"))
        assertNull(PluginBackgroundPolicy.lastError(current))
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
        assertFalse(status.toString().contains("secret"))
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

    @Test
    fun `execution receipts are bounded generation-bound and errors use a fixed safe catalog`() {
        val store = PluginBackgroundStore(directory())
        val item = record()
        store.put(item)
        store.markRun(item.pluginId, 123L, "failed", 500L, item.generation)

        val current = store.get(item.pluginId)!!
        val status = PluginBackgroundPolicy.statusJson(current, true, nowMs = 1_000L)
        val result = status.getJSONObject("lastResult")
        val error = status.getJSONObject("lastError")
        assertEquals("execution_receipt", result.getString("kind"))
        assertEquals(item.generation, result.getString("generation"))
        assertEquals("failed", result.getString("status"))
        assertFalse(result.getBoolean("ok"))
        assertEquals(123L, result.getLong("finishedAtMs"))
        assertEquals("failed", error.getString("code"))
        assertEquals("后台动作执行失败", error.getString("message"))
        assertTrue(result.toString().toByteArray().size <= 1024)
        assertTrue(error.toString().toByteArray().size <= 512)
        assertFalse(status.toString().contains("Exception"))
        assertFalse(status.toString().contains("tool"))
        assertFalse(status.toString().contains("model"))

        store.markRun(item.pluginId, 456L, "ok", 100L, item.generation)
        val recovered = PluginBackgroundPolicy.statusJson(store.get(item.pluginId), true, 1_000L)
        assertTrue(recovered.getJSONObject("lastResult").getBoolean("ok"))
        assertTrue(recovered.isNull("lastError"))
    }

    @Test
    fun `old generation cannot replace either result or error receipt`() {
        val dir = directory()
        val store = PluginBackgroundStore(dir)
        val old = record()
        store.put(old)
        val next = record(action = "sync", revision = "r2")
        store.put(next)
        store.markRun(
            next.pluginId,
            100L,
            "ok",
            50L,
            next.generation,
            result = PluginBackgroundPolicy.ActionResult(JSONObject().put("owner", "current")),
        )
        val before = store.get(next.pluginId)!!

        store.markRun(
            old.pluginId,
            999L,
            "failed",
            900L,
            old.generation,
            result = PluginBackgroundPolicy.ActionResult(JSONObject().put("owner", "old")),
            errorMessage = "OLD_GENERATION_ERROR_CANARY",
        )

        val after = store.get(next.pluginId)!!
        assertEquals(
            PluginBackgroundPolicy.lastResult(before).toString(),
            PluginBackgroundPolicy.lastResult(after).toString(),
        )
        assertEquals(
            PluginBackgroundPolicy.lastError(before)?.toString(),
            PluginBackgroundPolicy.lastError(after)?.toString(),
        )
        assertEquals(100L, after.lastRunAtMs)
        assertEquals("ok", after.lastStatus)
        assertEquals(
            "current",
            PluginBackgroundPolicy.lastResult(after)!!
                .getJSONObject("result")
                .getString("owner"),
        )
        val disk = java.io.File(dir, "background.json").readText()
        assertFalse(disk.contains("OLD_GENERATION_ERROR_CANARY"))
    }

    @Test
    fun `next run is explicitly estimated and disappears when system has no schedule`() {
        val item = record()
        val created = PluginBackgroundPolicy.statusJson(item, true, nowMs = 100L)
        assertEquals(item.createdAtMs + item.intervalMs, created.getLong("nextRunAt"))
        assertTrue(created.getBoolean("nextRunAtEstimated"))

        val ran = item.copy(lastRunAtMs = 2_000_000L, lastStatus = "ok", lastDurationMs = 20L)
        val afterRun = PluginBackgroundPolicy.statusJson(ran, true, nowMs = 2_100_000L)
        assertEquals(2_000_000L + item.intervalMs, afterRun.getLong("nextRunAt"))
        assertTrue(afterRun.getBoolean("nextRunAtEstimated"))

        val overdue = PluginBackgroundPolicy.statusJson(ran, true, nowMs = 9_000_000L)
        assertEquals(9_000_000L, overdue.getLong("nextRunAt"))
        assertTrue(overdue.getBoolean("nextRunAtEstimated"))
        assertTrue(PluginBackgroundPolicy.statusJson(ran, false, 2_100_000L).isNull("nextRunAt"))
        assertTrue(PluginBackgroundPolicy.statusJson(null, false, 2_100_000L).isNull("nextRunAt"))
    }

    @Test
    fun `never-run and unscheduled records do not invent execution results`() {
        val never = PluginBackgroundPolicy.statusJson(record(), false, nowMs = 100L)
        assertTrue(never.isNull("lastResult"))
        assertTrue(never.isNull("lastError"))
        assertTrue(never.isNull("nextRunAt"))

        val missing = PluginBackgroundPolicy.statusJson(null, false, nowMs = 100L)
        assertEquals("never", missing.getString("lastStatus"))
        assertTrue(missing.isNull("lastResult"))
        assertTrue(missing.isNull("lastError"))
        assertTrue(missing.isNull("nextRunAt"))
    }

    @Test
    fun `explicit numeric and summary results survive reload without view or state`() {
        val dir = directory()
        val store = PluginBackgroundStore(dir)
        val item = record()
        store.put(item)
        val invoked =
            JSONObject()
                .put("result", 37)
                .put("view", JSONObject().put("text", "VIEW_CANARY"))
                .put("state", JSONObject().put("private", "STATE_CANARY"))
        val explicit = PluginBackgroundPolicy.explicitActionResult(invoked)!!

        store.markRun(item.pluginId, 123L, "ok", 20L, item.generation, result = explicit)

        var receipt = PluginBackgroundPolicy.lastResult(store.get(item.pluginId)!!)!!
        assertEquals("action_result", receipt.getString("kind"))
        assertTrue(receipt.getBoolean("hasActionResult"))
        assertEquals(37, receipt.getInt("result"))
        assertEquals("json", receipt.getString("format"))
        assertFalse(receipt.getBoolean("truncated"))

        val summary =
            JSONObject()
                .put("summary", "同步完成 3 条")
                .put("count", 3)
                .put("fresh", true)
        store.markRun(
            item.pluginId,
            456L,
            "ok",
            30L,
            item.generation,
            result = PluginBackgroundPolicy.ActionResult(summary),
        )

        val reloaded = PluginBackgroundStore(dir).get(item.pluginId)!!
        receipt = PluginBackgroundPolicy.lastResult(reloaded)!!
        val result = receipt.getJSONObject("result")
        assertEquals("同步完成 3 条", result.getString("summary"))
        assertEquals(3, result.getInt("count"))
        assertTrue(result.getBoolean("fresh"))
        assertEquals(item.generation, receipt.getString("generation"))
        val disk = java.io.File(dir, "background.json").readText()
        assertTrue(disk.contains("同步完成 3 条"))
        assertFalse(disk.contains("VIEW_CANARY"))
        assertFalse(disk.contains("STATE_CANARY"))
    }

    @Test
    fun `sensitive request header and log canaries never reach result storage`() {
        val dir = directory()
        val store = PluginBackgroundStore(dir)
        val item = record()
        store.put(item)
        val canaries =
            listOf(
                "CANARY_TOKEN",
                "CANARY_COOKIE",
                "CANARY_AUTHORIZATION",
                "CANARY_PASSWORD",
                "CANARY_SECRET",
                "CANARY_API_KEY",
                "CANARY_HEADER",
                "CANARY_REQUEST",
                "CANARY_LOG",
                "CANARY_TEXT",
            )
        val result =
            JSONObject()
                .put("summary", "safe summary")
                .put("count", 2)
                .put("token", canaries[0])
                .put(
                    "nested",
                    JSONObject()
                        .put("cookie", canaries[1])
                        .put("authorization", canaries[2])
                        .put("password", canaries[3])
                        .put("client_secret", canaries[4])
                        .put("apiKey", canaries[5])
                        .put("healthy", true),
                )
                .put("headers", JSONObject().put("x-fixture", canaries[6]))
                .put("request", JSONObject().put("body", canaries[7]))
                .put("logs", JSONArray().put(canaries[8]))
                .put("text", "token=" + canaries[9])

        store.markRun(
            item.pluginId,
            123L,
            "ok",
            20L,
            item.generation,
            result = PluginBackgroundPolicy.ActionResult(result),
        )

        val disk = java.io.File(dir, "background.json").readText()
        canaries.forEach { canary -> assertFalse("leaked $canary", disk.contains(canary)) }
        val receipt =
            PluginBackgroundPolicy.lastResult(PluginBackgroundStore(dir).get(item.pluginId)!!)!!
        val stored = receipt.getJSONObject("result")
        assertTrue(receipt.getBoolean("redacted"))
        assertEquals("safe summary", stored.getString("summary"))
        assertEquals(2, stored.getInt("count"))
        assertTrue(stored.getJSONObject("nested").getBoolean("healthy"))
        assertFalse(stored.has("token"))
        assertFalse(stored.has("headers"))
        assertFalse(stored.has("request"))
        assertFalse(stored.has("logs"))
        assertFalse(stored.getString("text").contains(canaries[9]))
    }

    @Test
    fun `oversize results stay within 4KiB and expose explicit truncation`() {
        val dir = directory()
        val store = PluginBackgroundStore(dir)
        val item = record()
        store.put(item)
        val result = JSONObject()
        for (index in 0 until 32) result.put("field$index", "x".repeat(500))

        store.markRun(
            item.pluginId,
            123L,
            "ok",
            20L,
            item.generation,
            result = PluginBackgroundPolicy.ActionResult(result),
        )

        val reloaded = PluginBackgroundStore(dir).get(item.pluginId)!!
        val receipt = PluginBackgroundPolicy.lastResult(reloaded)!!
        assertTrue(receipt.getBoolean("truncated"))
        assertEquals("json_prefix", receipt.getString("format"))
        assertTrue(receipt.get("result") is String)
        assertTrue(receipt.getString("result").endsWith("…"))
        assertEquals(reloaded.lastActionResult, receipt.getString("result"))
        PluginBackgroundStore(dir).put(reloaded)
        assertEquals(
            reloaded.lastActionResult,
            PluginBackgroundStore(dir).get(item.pluginId)!!.lastActionResult,
        )
        assertTrue(
            PluginBackgroundPolicy.resultJson(reloaded.lastActionResult)
                .toByteArray(Charsets.UTF_8)
                .size <= PluginBackgroundPolicy.MAX_ACTION_RESULT_BYTES,
        )
    }

    @Test
    fun `recorded errors are bounded and redacted while raw request errors fall back`() {
        val dir = directory()
        val store = PluginBackgroundStore(dir)
        val item = record()
        store.put(item)
        store.markRun(
            item.pluginId,
            123L,
            "failed",
            20L,
            item.generation,
            errorMessage =
                "HTTP 401 token=ERROR_TOKEN_CANARY, retry later " +
                    "https://fixture.invalid/ERROR_URL_CANARY",
        )

        var error = PluginBackgroundPolicy.lastError(store.get(item.pluginId)!!)!!
        assertEquals("failed", error.getString("code"))
        assertEquals("recorded", error.getString("messageSource"))
        assertTrue(error.getString("message").startsWith("HTTP 401"))
        assertTrue(error.getString("message").contains("retry later"))
        assertTrue(error.getBoolean("redacted"))
        assertFalse(error.getString("message").contains("ERROR_TOKEN_CANARY"))
        assertFalse(error.getString("message").contains("ERROR_URL_CANARY"))
        val recordedDisk = java.io.File(dir, "background.json").readText()
        assertFalse(recordedDisk.contains("ERROR_TOKEN_CANARY"))
        assertFalse(recordedDisk.contains("ERROR_URL_CANARY"))

        store.markRun(
            item.pluginId,
            124L,
            "failed",
            20L,
            item.generation,
            errorMessage = "x".repeat(2_000),
        )
        error = PluginBackgroundPolicy.lastError(store.get(item.pluginId)!!)!!
        assertTrue(error.getBoolean("truncated"))
        assertTrue(error.getString("message").toByteArray(Charsets.UTF_8).size <= 512)

        store.markRun(
            item.pluginId,
            456L,
            "failed",
            20L,
            item.generation,
            errorMessage = "{\"request\":\"RAW_REQUEST_CANARY\"}",
        )
        error = PluginBackgroundPolicy.lastError(PluginBackgroundStore(dir).get(item.pluginId)!!)!!
        assertEquals("fallback", error.getString("messageSource"))
        assertEquals("后台动作执行失败", error.getString("message"))
        assertFalse(java.io.File(dir, "background.json").readText().contains("RAW_REQUEST_CANARY"))

        store.markRun(
            item.pluginId,
            457L,
            "failed",
            20L,
            item.generation,
            errorMessage = "request: RAW_REQUEST_CANARY",
        )
        error = PluginBackgroundPolicy.lastError(store.get(item.pluginId)!!)!!
        assertEquals("fallback", error.getString("messageSource"))
        assertFalse(java.io.File(dir, "background.json").readText().contains("RAW_REQUEST_CANARY"))
    }

    @Test
    fun `missing explicit result is honest for current and legacy receipts`() {
        val store = PluginBackgroundStore(directory())
        val item = record()
        store.put(item)
        store.markRun(item.pluginId, 123L, "ok", 20L, item.generation)
        val current = PluginBackgroundPolicy.lastResult(store.get(item.pluginId)!!)!!
        assertEquals("execution_receipt", current.getString("kind"))
        assertFalse(current.getBoolean("hasActionResult"))
        assertEquals("not_returned", current.getString("resultUnavailableReason"))
        assertFalse(current.has("result"))

        val legacy = item.copy(lastRunAtMs = 100L, lastStatus = "ok", lastDurationMs = 10L)
        val fallback = PluginBackgroundPolicy.lastResult(legacy)!!
        assertFalse(fallback.getBoolean("hasActionResult"))
        assertEquals("legacy_or_not_recorded", fallback.getString("resultUnavailableReason"))
    }
}
