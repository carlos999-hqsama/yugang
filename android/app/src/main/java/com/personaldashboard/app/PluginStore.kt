package com.personaldashboard.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** One atomic registry contains active version, previous version and per-plugin state. */
class PluginStore(private val directory: File) {
    private val file = File(directory, "registry.json")
    private var registry: JSONObject

    init {
        directory.mkdirs()
        registry = if (file.exists()) JSONObject(file.readText()) else JSONObject()
    }

    @Synchronized
    fun requireRevision(id: String, revision: String) {
        val row = entry(id)
        require(row.getBoolean("enabled") && row.getString("revision") == revision) { "插件已更新或停用" }
    }

    @Synchronized
    fun list(): JSONObject {
        val items = JSONArray()
        registry.keys().asSequence().forEach { id ->
            val row = registry.getJSONObject(id)
            if (row.optBoolean("removed")) return@forEach
            val bundle = row.getJSONObject("current")
            items.put(
                JSONObject(bundle.getJSONObject("manifest").toString())
                    .put("enabled", row.getBoolean("enabled"))
                    .put("authority", "owner-full")
                    .put("canRollback", row.has("previous")),
            )
        }
        return JSONObject().put("plugins", items)
    }

    @Synchronized
    fun read(id: String): JSONObject =
        JSONObject(entry(id).getJSONObject("current").toString())
            .put("_revision", entry(id).getString("revision"))

    @Synchronized
    fun activeBundle(id: String): JSONObject {
        requireActive(id)
        return read(id)
    }

    @Synchronized fun enabled(id: String): Boolean = entry(id).getBoolean("enabled")

    @Synchronized
    fun requireActive(id: String) {
        require(runCatching { enabled(id) }.getOrDefault(false)) { "插件已停用或移除" }
    }

    @Synchronized
    fun data(id: String): JSONObject = JSONObject(entry(id).getJSONObject("data").toString())

    @Synchronized
    fun install(source: JSONObject): JSONObject {
        val clean = JSONObject(source.toString()).apply { remove("_revision") }
        val bundle = PluginContract.validate(clean)
        val id = bundle.getJSONObject("manifest").getString("id")
        require(registry.has(id) || registry.length() < 32) { "最多安装 32 个插件" }
        val next = copy()
        val old = next.optJSONObject(id)?.takeUnless { it.optBoolean("removed") }
        val row =
            JSONObject()
                .put("current", bundle)
                .put("revision", UUID.randomUUID().toString())
                .put(
                    "data",
                    old?.optJSONObject("data") ?: bundle.optJSONObject("state") ?: JSONObject(),
                )
        old?.optJSONObject("current")?.let { current ->
            runCatching { PluginContract.validate(current) }
                .getOrNull()
                ?.let { row.put("previous", it) }
        }
        row.put("enabled", old?.optBoolean("enabled") ?: true)
        next.put(id, row)
        save(next)
        return list()
    }

    @Synchronized
    fun enable(id: String, enabled: Boolean): JSONObject {
        entry(id)
        val next = copy()
        val row = next.getJSONObject(PluginContract.id(id))
        row.put("enabled", enabled).put("revision", UUID.randomUUID().toString())
        save(next)
        return list()
    }

    @Synchronized
    fun rollback(id: String): JSONObject {
        entry(id)
        val next = copy()
        val row = next.getJSONObject(PluginContract.id(id))
        require(row.has("previous")) { "没有可回退的插件版本" }
        val previous = PluginContract.validate(row.getJSONObject("previous"))
        row.put("previous", row.getJSONObject("current"))
            .put("current", previous)
            .put("revision", UUID.randomUUID().toString())
        row.put("enabled", true)
        save(next)
        return list()
    }

    @Synchronized
    fun remove(id: String): JSONObject {
        PluginContract.id(id)
        val next = copy()
        // Commit the removal before deleting resources. Only the ID is retained for retry;
        // current/previous bundles and private state are no longer stored in the registry.
        next.put(id, JSONObject().put("removed", true))
        save(next)
        return list()
    }

    @Synchronized
    fun installed(id: String): Boolean =
        registry.has(PluginContract.id(id)) && !registry.getJSONObject(id).optBoolean("removed")

    @Synchronized
    fun pendingRemovals(): List<String> =
        registry
            .keys()
            .asSequence()
            .filter { registry.getJSONObject(it).optBoolean("removed") }
            .toList()

    @Synchronized
    fun finishRemoval(id: String) {
        PluginContract.id(id)
        val row = registry.optJSONObject(id) ?: return
        if (!row.optBoolean("removed")) return
        val next = copy()
        next.remove(id)
        save(next)
    }

    @Synchronized
    fun setData(id: String, data: JSONObject, expected: JSONObject) {
        require(data.toString().toByteArray().size <= PluginContract.MAX_STATE_BYTES) {
            "插件状态超过 32 KiB"
        }
        require(read(id).getString("_revision") == expected.getString("_revision") && enabled(id)) {
            "插件已更新或停用，旧操作结果未保存"
        }
        val next = copy()
        next.getJSONObject(id).put("data", JSONObject(data.toString()))
        save(next)
    }

    @Synchronized
    fun requireCapability(id: String, capability: String) {
        val row = entry(id)
        require(row.getBoolean("enabled") && capability in PluginContract.capabilities) {
            "插件已停用或宿主不支持此能力"
        }
    }

    private fun entry(id: String): JSONObject =
        registry.getJSONObject(PluginContract.id(id)).also {
            require(!it.optBoolean("removed")) { "插件已移除" }
        }

    private fun copy() = JSONObject(registry.toString())

    private fun save(next: JSONObject) {
        val bytes = next.toString().toByteArray()
        require(bytes.size <= 16 * 1024 * 1024) { "插件注册表超过 16 MiB" }
        val temp = File(directory, "registry.new")
        FileOutputStream(temp).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(
            temp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        registry = next
    }
}
