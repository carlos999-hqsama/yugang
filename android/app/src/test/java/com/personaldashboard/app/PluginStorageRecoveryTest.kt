package com.personaldashboard.app

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginStorageRecoveryTest {
    private fun bundle(
        id: String = "test-plugin",
        version: String = "1",
        text: String = "native",
    ): JSONObject =
        JSONObject()
            .put(
                "manifest",
                JSONObject()
                    .put("apiVersion", 1)
                    .put("id", id)
                    .put("title", "Test")
                    .put("version", version)
                    .put("kind", "native")
                    .put("capabilities", JSONArray()),
            )
            .put("view", JSONObject().put("type", "text").put("text", text))
            .put(
                "code",
                "export async function run(){ return {view:{type:'text',text:'ok'}} }",
            )

    private fun withDir(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("plugin-storage-recovery").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun pluginRow(directory: File, id: String): JSONObject =
        JSONObject(File(directory, "registry.json").readText(Charsets.UTF_8))
            .getJSONObject("plugins")
            .getJSONObject(id)

    private fun listedPlugin(list: JSONObject, id: String): JSONObject {
        val plugins = list.getJSONArray("plugins")
        for (index in 0 until plugins.length()) {
            val item = plugins.getJSONObject(index)
            val itemId = item.optJSONObject("manifest")?.optString("id") ?: item.optString("id")
            if (itemId == id) return item
        }
        fail("插件 $id 不在列表中")
        error("unreachable")
    }

    private fun recoveryHasBytes(directory: File, expected: ByteArray): Boolean =
        File(directory, "recovery").listFiles()?.any {
            it.isFile && it.readBytes().contentEquals(expected)
        } == true

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun legacyRegistryMigratesLifecycleDataAndKeepsOriginalBytes() =
        withDir { directory ->
            val first = bundle("legacy-plugin", "1", "first")
            val second = bundle("legacy-plugin", "2", "second")
            val legacy =
                JSONObject().put(
                    "legacy-plugin",
                    JSONObject()
                        .put("current", second)
                        .put("previous", first)
                        .put("revision", "legacy-revision")
                        .put("data", JSONObject().put("draft", "KEEP_ME").put("count", 7))
                        .put("enabled", false),
                )
            val originalBytes = legacy.toString().toByteArray(Charsets.UTF_8)
            File(directory, "registry.json").writeBytes(originalBytes)

            val store = PluginStore(directory)
            val migrated = store.read("legacy-plugin")
            assertEquals("2", migrated.getJSONObject("manifest").getString("version"))
            assertEquals("legacy-revision", migrated.getString("_revision"))
            assertEquals("KEEP_ME", store.data("legacy-plugin").getString("draft"))
            assertFalse(store.enabled("legacy-plugin"))
            assertTrue(recoveryHasBytes(directory, originalBytes))

            val registryText = File(directory, "registry.json").readText(Charsets.UTF_8)
            assertFalse(registryText.contains("export async function"))
            assertFalse(registryText.contains("KEEP_ME"))
            assertTrue(registryText.contains("\"path\""))

            val status = store.recoveryStatus()
            assertEquals("completed", status.getJSONObject("migration").getString("status"))
            assertTrue(status.getJSONObject("migration").getString("recoveryFile").startsWith("recovery/"))

            store.rollback("legacy-plugin")
            assertEquals(
                "1",
                store.read("legacy-plugin").getJSONObject("manifest").getString("version"),
            )
            assertTrue(store.enabled("legacy-plugin"))
            assertEquals("KEEP_ME", PluginStore(directory).data("legacy-plugin").getString("draft"))
        }

    @Test
    fun stateUpdateDoesNotChangePackageBytesOrMtime() =
        withDir { directory ->
            val store = PluginStore(directory)
            store.install(bundle())
            val packageFile =
                File(directory, "packages").listFiles()!!.single { it.isFile }
            val beforeBytes = packageFile.readBytes()
            val fixedMtime = 1_600_000_000_000L
            assertTrue(packageFile.setLastModified(fixedMtime))

            store.setData(
                "test-plugin",
                JSONObject().put("draft", "small-change"),
                store.read("test-plugin"),
            )

            assertArrayEquals(beforeBytes, packageFile.readBytes())
            assertEquals(fixedMtime, packageFile.lastModified())
            assertTrue(File(directory, "state/test-plugin.json").isFile)
            assertFalse(
                File(directory, "registry.json")
                    .readText(Charsets.UTF_8)
                    .contains("small-change"),
            )
            assertEquals("small-change", PluginStore(directory).data("test-plugin").getString("draft"))
        }

    @Test
    fun truncatedIndexUsesBackupAndPreservesCorruptPrimaryBeforeRecoveryWrite() =
        withDir { directory ->
            val store = PluginStore(directory)
            store.install(bundle())
            store.enable("test-plugin", true)
            val corrupt = """{"schemaVersion":2,"plugins":{"test-plugin":""".toByteArray()
            File(directory, "registry.json").writeBytes(corrupt)

            val reopened = PluginStore(directory)
            val status = reopened.recoveryStatus()
            assertEquals("recovered", status.getString("status"))
            assertEquals("registry.json.bak", status.getJSONObject("index").getString("source"))
            assertEquals(
                "1",
                reopened.read("test-plugin").getJSONObject("manifest").getString("version"),
            )

            reopened.enable("test-plugin", false)
            assertTrue(recoveryHasBytes(directory, corrupt))
            assertFalse(reopened.enabled("test-plugin"))

            val afterRestart = PluginStore(directory)
            assertFalse(afterRestart.enabled("test-plugin"))
            assertEquals("ok", afterRestart.recoveryStatus().getString("status"))
        }

    @Test
    fun unrecoverableIndexNeverThrowsFromConstructorAndRejectsLossyWrites() =
        withDir { directory ->
            val corrupt = """{"schemaVersion":2,"plugins":""".toByteArray()
            File(directory, "registry.json").writeBytes(corrupt)

            val store = PluginStore(directory)
            val list = store.list()
            assertEquals(0, list.getJSONArray("plugins").length())
            assertEquals("unavailable", list.getJSONObject("_recovery").getString("status"))
            assertFalse(list.getJSONObject("_recovery").getBoolean("writable"))
            assertEquals("unavailable", store.recoveryStatus().getJSONObject("index").getString("status"))
            try {
                store.read("test-plugin")
                fail("损坏索引不应返回插件")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("索引不可用"))
            }
            try {
                store.install(bundle())
                fail("损坏索引不应允许写入")
            } catch (_: IllegalStateException) {
            }
            assertArrayEquals(corrupt, File(directory, "registry.json").readBytes())
            assertFalse(File(directory, "registry.json.bak").exists())
        }

    @Test
    fun missingIndexWithExistingArtifactsStaysReadOnly() =
        withDir { directory ->
            File(directory, "packages").mkdirs()
            val existing = File(directory, "packages/orphaned-package.json")
            val bytes = "{\"userData\":\"KEEP\"}".toByteArray()
            existing.writeBytes(bytes)

            val store = PluginStore(directory)
            val list = store.list()
            assertEquals(0, list.getJSONArray("plugins").length())
            assertEquals("unavailable", list.getJSONObject("_recovery").getString("status"))
            assertFalse(list.getJSONObject("_recovery").getBoolean("writable"))
            try {
                store.install(bundle())
                fail("缺少索引时不能初始化空可写索引")
            } catch (_: IllegalStateException) {
            }
            assertFalse(File(directory, "registry.json").exists())
            assertArrayEquals(bytes, existing.readBytes())
        }

    @Test
    fun truncatedPackageOnlyIsolatesItsOwnPlugin() =
        withDir { directory ->
            val store = PluginStore(directory)
            store.install(bundle("alpha-plugin", "1", "alpha"))
            store.install(bundle("beta-plugin", "1", "beta"))
            val alphaPath = pluginRow(directory, "alpha-plugin").getJSONObject("current").getString("path")
            val alphaFile = File(directory, alphaPath)
            val truncated = alphaFile.readBytes().copyOfRange(0, 36)
            alphaFile.writeBytes(truncated)

            val list = store.list()
            assertEquals(2, list.getJSONArray("plugins").length())
            assertEquals(
                "failed",
                listedPlugin(list, "alpha-plugin").getJSONObject("_recovery").getString("status"),
            )
            assertEquals(
                "1",
                listedPlugin(list, "beta-plugin").getString("version"),
            )
            try {
                store.read("alpha-plugin")
                fail("截断包应报错")
            } catch (_: IllegalStateException) {
            }
            assertEquals("beta", store.read("beta-plugin").getJSONObject("view").getString("text"))
            assertEquals(0, store.data("alpha-plugin").length())
            assertArrayEquals(truncated, alphaFile.readBytes())

            store.install(bundle("gamma-plugin", "1", "gamma"))
            assertTrue(store.read("gamma-plugin").has("manifest"))
            assertArrayEquals(truncated, alphaFile.readBytes())
            assertEquals(
                "failed",
                listedPlugin(PluginStore(directory).list(), "alpha-plugin")
                    .getJSONObject("_recovery")
                    .getString("status"),
            )
        }

    @Test
    fun rollbackStillRecoversWhenCurrentPackageIsCorrupt() =
        withDir { directory ->
            val store = PluginStore(directory)
            store.install(bundle("rollback-plugin", "1", "first"))
            store.install(bundle("rollback-plugin", "2", "second"))
            val currentPath =
                pluginRow(directory, "rollback-plugin")
                    .getJSONObject("current")
                    .getString("path")
            File(directory, currentPath).writeBytes("corrupt".toByteArray())

            store.rollback("rollback-plugin")
            val recovered = store.read("rollback-plugin")
            assertEquals("1", recovered.getJSONObject("manifest").getString("version"))
            assertEquals("first", recovered.getJSONObject("view").getString("text"))
            assertEquals("degraded", store.recoveryStatus().getString("status"))
        }

    @Test
    fun semanticallyInvalidPackageIsReportedWithoutHidingOtherPlugins() =
        withDir { directory ->
            val store = PluginStore(directory)
            store.install(bundle("alpha-plugin", "1", "alpha"))
            store.install(bundle("beta-plugin", "1", "beta"))
            val row = pluginRow(directory, "alpha-plugin")
            val reference = row.getJSONObject("current")
            val invalidBytes = JSONObject().put("manifest", JSONObject()).toString().toByteArray()
            File(directory, reference.getString("path")).writeBytes(invalidBytes)
            reference.put("sha256", sha256(invalidBytes)).put("size", invalidBytes.size)
            File(directory, "registry.json").writeText(
                JSONObject(File(directory, "registry.json").readText())
                    .put(
                        "plugins",
                        JSONObject(File(directory, "registry.json").readText())
                            .getJSONObject("plugins")
                            .put("alpha-plugin", row),
                    )
                    .toString(),
            )

            val reopened = PluginStore(directory)
            val alpha = listedPlugin(reopened.list(), "alpha-plugin")
            assertEquals("failed", alpha.getJSONObject("_recovery").getString("status"))
            assertTrue(alpha.getJSONObject("_recovery").getString("message").contains("语义校验"))
            try {
                reopened.read("alpha-plugin")
                fail("语义损坏包应报错")
            } catch (_: IllegalStateException) {
            }
            assertEquals(
                "beta",
                reopened.read("beta-plugin").getJSONObject("view").getString("text"),
            )
        }

    @Test
    fun unreadableStateIsNeverSilentlyReplaced() =
        withDir { directory ->
            val store = PluginStore(directory)
            store.install(bundle())
            val stateFile = File(directory, "state/test-plugin.json")
            val backupFile = File(directory, "state/test-plugin.json.bak")
            backupFile.delete()
            val corrupt = "[]".toByteArray()
            stateFile.writeBytes(corrupt)
            val expected = store.read("test-plugin")

            try {
                store.data("test-plugin")
                fail("损坏 state 不应返回空对象")
            } catch (_: IllegalStateException) {
            }
            try {
                store.setData("test-plugin", JSONObject().put("replacement", true), expected)
                fail("损坏 state 不应被覆盖")
            } catch (_: IllegalStateException) {
            }
            assertArrayEquals(corrupt, stateFile.readBytes())
            assertEquals(
                "failed",
                listedPlugin(store.list(), "test-plugin")
                    .getJSONObject("_recovery")
                    .getString("state"),
            )
        }

    @Test
    fun stateBackupIsActuallyConsumedAndDamagedPrimaryIsPreserved() =
        withDir { directory ->
            val store = PluginStore(directory)
            store.install(bundle())
            store.setData(
                "test-plugin",
                JSONObject().put("value", "first"),
                store.read("test-plugin"),
            )
            store.setData(
                "test-plugin",
                JSONObject().put("value", "second"),
                store.read("test-plugin"),
            )
            val stateFile = File(directory, "state/test-plugin.json")
            val corrupt = """{"value":""".toByteArray()
            stateFile.writeBytes(corrupt)

            assertEquals("first", store.data("test-plugin").getString("value"))
            val pluginStatus =
                store.recoveryStatus().getJSONArray("plugins").getJSONObject(0)
            assertEquals("test-plugin", pluginStatus.getString("id"))
            assertEquals("recovered", pluginStatus.getString("status"))

            store.setData(
                "test-plugin",
                JSONObject().put("value", "third"),
                store.read("test-plugin"),
            )
            assertEquals("third", store.data("test-plugin").getString("value"))
            assertTrue(recoveryHasBytes(directory, corrupt))
        }

    @Test
    fun blockedStateBackupWriteLeavesBothStateAndPackageIntact() =
        withDir { directory ->
            val store = PluginStore(directory)
            store.install(bundle())
            val initial = JSONObject().put("value", "kept")
            store.setData("test-plugin", initial, store.read("test-plugin"))
            val stateFile = File(directory, "state/test-plugin.json")
            val stateBefore = stateFile.readBytes()
            val packageFile = File(directory, "packages").listFiles()!!.single { it.isFile }
            val packageBefore = packageFile.readBytes()
            val packageMtime = packageFile.lastModified()
            val stateBackup = File(directory, "state/test-plugin.json.bak")
            stateBackup.delete()
            assertTrue(stateBackup.mkdir())

            val failure =
                runCatching {
                    store.setData(
                        "test-plugin",
                        JSONObject().put("value", "not-committed"),
                        store.read("test-plugin"),
                    )
                }
            assertTrue(failure.isFailure)
            assertArrayEquals(stateBefore, stateFile.readBytes())
            assertEquals("kept", store.data("test-plugin").getString("value"))
            assertArrayEquals(packageBefore, packageFile.readBytes())
            assertEquals(packageMtime, packageFile.lastModified())
        }

    @Test
    fun thirtyTwoSyntheticPluginsFitAndThirtyThirdIsRejected() =
        withDir { directory ->
            val store = PluginStore(directory)
            repeat(32) { index ->
                store.install(bundle("synthetic-%02d".format(index), "1", "item-$index"))
            }
            assertEquals(32, store.list().getJSONArray("plugins").length())
            assertEquals(32, File(directory, "packages").listFiles()!!.count { it.isFile })
            assertEquals(32, File(directory, "state").listFiles()!!.count { it.isFile })

            val rejected =
                runCatching {
                    store.install(bundle("synthetic-32", "1", "too-many"))
                }
            assertTrue(rejected.isFailure)
            assertEquals(32, store.list().getJSONArray("plugins").length())
            assertEquals(
                "item-31",
                store.read("synthetic-31").getJSONObject("view").getString("text"),
            )
        }

    @Test
    fun damagedBackgroundFileRecoversValidBackupAndPreservesOriginalBytes() =
        withDir { directory ->
            val store = PluginBackgroundStore(directory)
            val record =
                PluginBackgroundStore.ScheduleRecord(
                    pluginId = "background-test",
                    jobId = PluginBackgroundPolicy.jobId("background-test"),
                    revision = "revision-1",
                    action = "tick",
                    intervalMs = PluginBackgroundPolicy.MIN_INTERVAL_MS,
                    payload = JSONObject(),
                    createdAtMs = 1L,
                )
            store.put(record)
            store.put(record.copy(action = "tock"))
            assertTrue(File(directory, "background.json.bak").isFile)

            val backgroundFile = File(directory, "background.json")
            val corrupt = """{"schedules":{"background-test":""".toByteArray()
            backgroundFile.writeBytes(corrupt)
            assertEquals("tick",store.get("background-test")!!.action)
            assertTrue(recoveryHasBytes(directory,corrupt))
            assertEquals("recovered",store.recoveryStatus().getString("status"))
            store.put(record)
            assertEquals("tick",store.get("background-test")!!.action)
            assertTrue(File(directory, "background.json.bak").isFile)
            assertTrue(File(directory, "background.json.bak").length() > 0)
        }
}
