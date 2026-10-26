package com.personaldashboard.app

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Plugin index plus immutable package files and per-plugin atomic state files.
 *
 * `registry.json` contains only revision/enabled metadata and references to package files. A
 * package file is written once and never modified; current/previous only change by index commit.
 * User state lives in `state/<plugin-id>.json`, so a state write never touches bundle bytes or
 * package mtimes. Recoverable copies stay under this store's app-private `recovery/` directory.
 */
class PluginStore(private val directory: File) {
    private val registryFile = File(directory, "registry.json")
    private val registryBackupFile = File(directory, "registry.json.bak")
    private val packagesDirectory = File(directory, "packages")
    private val statesDirectory = File(directory, "state")
    private val recoveryDirectory = File(directory, "recovery")

    private var registry: JSONObject = emptyRegistry()
    private var indexReadable = false
    private var indexWritable = false
    private var indexStatus = "unavailable"
    private var indexSource = "registry.json"
    private var indexMessage: String? = null
    private var migrationStatus = "not-needed"
    private var migrationRecovery: String? = null
    private var migrationWarning: String? = null

    init {
        try {
            PluginAtomicStorage.ensureDirectory(directory)
            val primaryExists = registryFile.exists()
            val backupExists = registryBackupFile.exists()
            if (!primaryExists && !backupExists) {
                if (hasOrphanedStorage()) {
                    throw PluginAtomicStorage.StorageException(
                        "插件索引缺失，但发现现有插件包、状态或恢复副本；已保持只读，避免覆盖用户数据",
                    )
                }
                registry = emptyRegistry()
                indexReadable = true
                indexWritable = true
                indexStatus = "ok"
            } else {
                val read =
                    PluginAtomicStorage.readJson(
                        registryFile,
                        registryBackupFile,
                        MAX_INDEX_BYTES,
                        ::validateIndexRoot,
                    )
                when (indexKind(read.value)) {
                    IndexKind.LEGACY -> migrateLegacy(read.value, read.source)
                    IndexKind.V2 -> adoptIndex(read)
                }
            }
        } catch (failure: Exception) {
            registry = emptyRegistry()
            indexReadable = false
            indexWritable = false
            indexStatus = "unavailable"
            indexMessage = storageMessage(failure)
        }
    }

    @Synchronized
    fun requireRevision(id: String, revision: String) {
        requireIndexReadable()
        val row = entry(id)
        require(row.getBoolean("enabled") && row.getString("revision") == revision) {
            "插件已更新或停用"
        }
    }

    @Synchronized
    fun list(): JSONObject {
        if (!indexReadable) {
            return JSONObject()
                .put("plugins", JSONArray())
                .put("_recovery", recoveryStatus())
        }
        val plugins = pluginsObject()
        val items = JSONArray()
        var hasPluginIssue = false
        for (id in plugins.keys().asSequence().toList()) {
            val rawRow = plugins.optJSONObject(id)
            if (rawRow != null && rawRow.optBoolean("removed")) continue
            val health =
                runCatching { inspectPlugin(id, rawRow, lightweight = true) }
                    .getOrElse { failure ->
                        PluginHealth(
                            id = id,
                            bundle = null,
                            enabled = false,
                            canRollback = false,
                            issue =
                                JSONObject()
                                    .put("id", id)
                                    .put("status", "failed")
                                    .put("bundle", "failed")
                                    .put("state", "unavailable")
                                    .put("writable", false)
                                    .put("message", storageMessage(failure)),
                        )
                    }
            val item =
                if (health.bundle == null) {
                    JSONObject()
                        .put("id", id).put("title", id).put("version", "数据待恢复").put("capabilities", JSONArray())
                        .put("enabled", false)
                        .put("authority", "owner-full")
                        .put("canRollback", health.canRollback)
                } else {
                    JSONObject(health.bundle.getJSONObject("manifest").toString())
                        .put("enabled", health.enabled)
                        .put("authority", "owner-full")
                        .put("canRollback", health.canRollback)
                }
            health.issue?.let {
                item.put("_recovery", it)
                hasPluginIssue = true
            }
            items.put(item)
        }
        val result = JSONObject().put("plugins", items)
        if (hasPluginIssue || indexStatus != "ok") result.put("_recovery", recoveryStatus(fullCheck=false))
        return result
    }

    @Synchronized
    fun read(id: String): JSONObject {
        requireIndexReadable()
        val validId = PluginContract.id(id)
        val row = entry(validId)
        val bundle = loadCurrentBundle(validId, row)
        return JSONObject(bundle.toString()).put("_revision", revisionOf(validId, row))
    }

    @Synchronized
    fun activeBundle(id: String): JSONObject {
        requireActive(id)
        return read(id)
    }

    @Synchronized
    fun enabled(id: String): Boolean {
        requireIndexReadable()
        return entry(id).getBoolean("enabled")
    }

    @Synchronized
    fun requireActive(id: String) {
        requireIndexReadable()
        require(runCatching { enabled(id) }.getOrDefault(false)) { "插件已停用或移除" }
    }

    @Synchronized
    fun data(id: String): JSONObject {
        requireIndexReadable()
        val validId = PluginContract.id(id)
        entry(validId)
        return JSONObject(readState(validId).value.toString())
    }

    @Synchronized
    fun install(source: JSONObject): JSONObject {
        requireIndexWritable()
        val clean = JSONObject(source.toString()).apply { remove("_revision") }
        val bundle = PluginContract.validate(clean)
        val id = bundle.getJSONObject("manifest").getString("id")
        val plugins = pluginsObject()
        require(plugins.has(id) || plugins.length() < MAX_PLUGINS) { "最多安装 32 个插件" }

        val rawOld = plugins.optJSONObject(id)
        val old = rawOld?.takeUnless { it.optBoolean("removed") }
        if (old != null) {
            // An upgrade must not silently replace unreadable user state with bundle defaults.
            readState(id)
        }

        val currentReference = writeBundleFile(id, bundle)
        var previousReference: JSONObject? = null
        if (old != null) {
            previousReference =
                runCatching { loadCurrentBundle(id, old) }
                    .getOrNull()
                    ?.let { JSONObject(old.getJSONObject("current").toString()) }
        }

        val initialState =
            if (old == null) {
                JSONObject((bundle.optJSONObject("state") ?: JSONObject()).toString())
            } else null
        initialState?.let { writeState(id, it) }

        val next = copy()
        val nextRow =
            JSONObject()
                .put("current", currentReference)
                .put("revision", UUID.randomUUID().toString())
                .put("enabled", old?.getBoolean("enabled") ?: true)
        previousReference?.let { nextRow.put("previous", it) }
        next.getJSONObject("plugins").put(id, nextRow)
        save(next)
        return list()
    }

    @Synchronized
    fun enable(id: String, enabled: Boolean): JSONObject {
        requireIndexWritable()
        entry(id)
        val validId = PluginContract.id(id)
        val next = copy()
        val row = next.getJSONObject("plugins").getJSONObject(validId)
        row.put("enabled", enabled).put("revision", UUID.randomUUID().toString())
        save(next)
        return list()
    }

    @Synchronized
    fun rollback(id: String): JSONObject {
        requireIndexWritable()
        val validId = PluginContract.id(id)
        val row = entry(validId)
        require(row.has("previous")) { "没有可回退的插件版本" }
        loadBundleReference(validId, row, "previous")

        val next = copy()
        val nextRow = next.getJSONObject("plugins").getJSONObject(validId)
        val current = nextRow.optJSONObject("current")
        val previous = nextRow.getJSONObject("previous")
        if (current == null) {
            nextRow.remove("previous")
        } else {
            nextRow.put("previous", current)
        }
        nextRow
            .put("current", previous)
            .put("revision", UUID.randomUUID().toString())
            .put("enabled", true)
        save(next)
        return list()
    }

    @Synchronized
    fun remove(id: String): JSONObject {
        requireIndexWritable()
        val validId = PluginContract.id(id)
        val next = copy()
        // The tombstone is the commit point. Package/state bytes are deliberately retained:
        // pendingRemovals() lets the host finish other cleanup, and reinstall never adopts them.
        next
            .getJSONObject("plugins")
            .put(validId, JSONObject().put("removed", true))
        save(next)
        return list()
    }

    @Synchronized
    fun installed(id: String): Boolean {
        if (!indexReadable) return false
        val validId = PluginContract.id(id)
        val row = pluginsObject().optJSONObject(validId) ?: return false
        return !row.optBoolean("removed")
    }

    @Synchronized
    fun pendingRemovals(): List<String> {
        if (!indexReadable) return emptyList()
        val plugins = pluginsObject()
        return plugins
            .keys()
            .asSequence()
            .filter { plugins.optJSONObject(it)?.optBoolean("removed") == true }
            .toList()
    }

    @Synchronized
    fun finishRemoval(id: String) {
        requireIndexWritable()
        val validId = PluginContract.id(id)
        val plugins = pluginsObject()
        val row = plugins.optJSONObject(validId) ?: return
        if (!row.optBoolean("removed")) return
        val next = copy()
        next.getJSONObject("plugins").remove(validId)
        save(next)
        File(statesDirectory,"$validId.json").delete()
        File(statesDirectory,"$validId.json.bak").delete()
        prunePackages()
    }

    @Synchronized
    fun setData(id: String, data: JSONObject, expected: JSONObject) {
        requireIndexWritable()
        require(data.toString().toByteArray(Charsets.UTF_8).size <= PluginContract.MAX_STATE_BYTES) {
            "插件状态超过 32 KiB"
        }
        val validId = PluginContract.id(id)
        val current = read(validId)
        require(current.getString("_revision") == expected.getString("_revision") && enabled(validId)) {
            "插件已更新或停用，旧操作结果未保存"
        }
        // If state is unreadable and has no valid backup, stop instead of replacing it with a
        // partial UI snapshot. If a backup is usable, the atomic writer preserves the damaged
        // primary in recovery/ before committing the new state.
        readState(validId)
        writeState(validId, JSONObject(data.toString()))
    }

    @Synchronized
    fun requireCapability(id: String, capability: String) {
        val row = entry(id)
        require(row.getBoolean("enabled") && capability in PluginContract.capabilities) {
            "插件已停用或宿主不支持此能力"
        }
    }

    /**
     * Non-throwing machine-readable state for host discovery and recovery UI:
     * `{status, writable, index:{status,source,message?}, migration:{status,recoveryFile?},
     * plugins:[{id,status,bundle,state,previous?,writable,canRecover,message}]}`.
     * `status` is one of `ok`, `recovered`, `degraded`, or `unavailable`.
     */
    @Synchronized
    @JvmOverloads
    fun recoveryStatus(fullCheck: Boolean = true): JSONObject {
        val pluginIssues = JSONArray()
        if (indexReadable) {
            val plugins = runCatching { pluginsObject() }.getOrNull()
            if (plugins == null) {
                pluginIssues.put(
                    JSONObject()
                        .put("status", "failed")
                        .put("message", "插件索引结构损坏：缺少 plugins"),
                )
            } else {
                for (id in plugins.keys().asSequence().toList()) {
                    val row = plugins.optJSONObject(id)
                    if (row != null && row.optBoolean("removed")) continue
                    runCatching { inspectPlugin(id, row, lightweight = !fullCheck) }
                        .onSuccess { health -> health.issue?.let { pluginIssues.put(it) } }
                        .onFailure { failure ->
                            pluginIssues.put(
                                JSONObject()
                                    .put("id", id)
                                    .put("status", "failed")
                                    .put("message", storageMessage(failure)),
                            )
                        }
                }
            }
        }

        val overall =
            when {
                !indexReadable -> "unavailable"
                pluginIssues.length() > 0 -> "degraded"
                indexStatus == "recovered" -> "recovered"
                else -> "ok"
            }
        val status =
            JSONObject()
                .put("status", overall)
                .put("writable", indexWritable)
                .put("index", indexJson())
                .put("recoveryDirectory", "recovery")
                .put(
                    "migration",
                    JSONObject()
                        .put("status", migrationStatus)
                        .apply { migrationRecovery?.let { put("recoveryFile", it) } }
                        .apply { migrationWarning?.let { put("warning", it) } },
                )
                .put("plugins", pluginIssues)
        indexMessage?.let { status.put("message", it) }
        return status
    }

    private fun adoptIndex(read: PluginAtomicStorage.JsonRead) {
        registry = read.value
        indexReadable = true
        indexWritable = true
        indexStatus = if (read.recovered) "recovered" else "ok"
        indexSource = relative(read.source)
        indexMessage =
            if (read.recovered) {
                "主插件索引不可用，已使用 ${relative(read.source)}：${read.primaryError.orEmpty()}"
            } else null
    }

    private fun migrateLegacy(legacy: JSONObject, source: File) {
        try {
            // The original bytes are durable before any index commit. This is intentionally a
            // copy under the app-private store, not a move, and remains available forever.
            val recovery = PluginAtomicStorage.preserveFile(source, recoveryDirectory, "registry-v1")
            migrationRecovery = relative(recovery)
            migrationStatus = "in-progress"
            val migrated = emptyRegistry()
            val migratedPlugins = migrated.getJSONObject("plugins")

            for (id in legacy.keys().asSequence().toList()) {
                val row = legacy.optJSONObject(id)
                if (row == null) {
                    migratedPlugins.put(id, damagedLegacyRow("旧索引插件记录不是对象", null))
                    continue
                }
                if (row.opt("removed") == true) {
                    migratedPlugins.put(id, JSONObject().put("removed", true))
                    continue
                }

                var enabled = row.opt("enabled") as? Boolean
                val warnings = mutableListOf<String>()
                if (enabled == null) {
                    enabled = false
                    warnings += "旧 enabled 字段无效，迁移后按停用处理"
                }
                var revision = row.opt("revision") as? String
                if (revision.isNullOrBlank()) {
                    revision = UUID.randomUUID().toString()
                    warnings += "旧 revision 字段无效，已生成新 revision 以阻止旧操作"
                }

                val currentResult =
                    runCatching {
                        val value =
                            row.optJSONObject("current")
                                ?: throw IllegalArgumentException("旧索引缺少 current")
                        PluginContract.validate(value).also {
                            require(it.getJSONObject("manifest").getString("id") == id) {
                                "旧索引 current 插件身份不一致"
                            }
                        }
                    }
                val currentBundle = currentResult.getOrNull()
                val currentFailure = currentResult.exceptionOrNull()?.let(::storageMessage)

                val stateResult =
                    runCatching {
                        val value =
                            if (row.has("data")) {
                                row.opt("data") as? JSONObject
                                    ?: throw IllegalArgumentException("旧 data 不是对象")
                            } else {
                                currentBundle?.optJSONObject("state")
                                    ?: throw IllegalArgumentException(
                                        "旧索引缺少 data，且 current 不可读",
                                    )
                            }
                        require(
                            value.toString().toByteArray(Charsets.UTF_8).size <=
                                PluginContract.MAX_STATE_BYTES,
                        ) { "旧 state 超过 32 KiB" }
                        JSONObject(value.toString())
                    }
                val stateValue = stateResult.getOrNull()
                val stateFailure = stateResult.exceptionOrNull()?.let(::storageMessage)

                // Semantic damage is isolated to that plugin and the original registry remains in
                // recovery/. A validated package/state that fails to write is a storage failure,
                // so abort the whole migration without replacing the legacy index.
                val currentReference = currentBundle?.let { writeBundleFile(id, it) }
                stateValue?.let { writeState(id, it) }

                var previousReference: JSONObject? = null
                if (row.has("previous")) {
                    val previousResult =
                        runCatching {
                            val value =
                                row.optJSONObject("previous")
                                    ?: throw IllegalArgumentException("旧 previous 不是对象")
                            PluginContract.validate(value).also {
                                require(it.getJSONObject("manifest").getString("id") == id) {
                                    "旧索引 previous 插件身份不一致"
                                }
                            }
                        }
                    val previousBundle = previousResult.getOrNull()
                    if (previousBundle == null) {
                        warnings +=
                            "旧上一版无法迁移：${previousResult.exceptionOrNull()?.let(::storageMessage) ?: "未知错误"}"
                    } else {
                        previousReference = writeBundleFile(id, previousBundle)
                    }
                }

                val errors = listOfNotNull(currentFailure, stateFailure).toMutableList()
                errors += warnings
                val migratedRow =
                    JSONObject()
                        .put("enabled", enabled)
                        .put("revision", revision)
                        .apply { currentReference?.let { put("current", it) } }
                        .apply { previousReference?.let { put("previous", it) } }
                        .apply {
                            if (errors.isNotEmpty())
                                put("migrationError", errors.joinToString("；").take(1000))
                        }
                migratedPlugins.put(id, migratedRow)
            }

            val bytes = migrated.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_INDEX_BYTES) { "新插件索引超过 16 MiB" }
            PluginAtomicStorage.writeBytesAtomically(
                registryFile,
                bytes,
                backup = null,
                recoveryDirectory = recoveryDirectory,
                maxExistingBytes = MAX_INDEX_BYTES,
                previousUsable = { isUsableIndexBytes(it) },
            )
            runCatching { PluginAtomicStorage.writeStandaloneAtomically(registryBackupFile, bytes) }
                .onFailure { migrationWarning = "索引备份暂不可用：${storageMessage(it)}" }
            registry = migrated
            indexReadable = true
            indexWritable = true
            indexStatus = "ok"
            indexSource = relative(registryFile)
            indexMessage = null
            migrationStatus = "completed"
        } catch (failure: Exception) {
            migrationStatus = "failed"
            throw failure
        }
    }

    private fun damagedLegacyRow(message: String, revision: String?): JSONObject =
        JSONObject()
            .put("enabled", false)
            .put("revision", revision ?: UUID.randomUUID().toString())
            .put("migrationError", message)

    private fun inspectPlugin(id: String, row: JSONObject?, lightweight: Boolean = false): PluginHealth {
        if (row == null) {
            return PluginHealth(
                id = id,
                bundle = null,
                enabled = false,
                canRollback = false,
                issue =
                    JSONObject()
                        .put("id", id)
                        .put("status", "failed")
                        .put("bundle", "failed")
                        .put("state", "unavailable")
                        .put("writable", false)
                        .put("message", "插件索引记录不是对象"),
            )
        }

        val messages = mutableListOf<String>()
        val enabled = row.opt("enabled") as? Boolean
        if (enabled == null) messages += "enabled 字段无效"
        val revision = row.opt("revision") as? String
        if (revision.isNullOrBlank()) messages += "revision 字段无效"
        row.optString("migrationError").takeIf { it.isNotEmpty() }?.let { messages += it }
        row.optString("migrationWarning").takeIf { it.isNotEmpty() }?.let { messages += it }

        val bundleResult = runCatching { if(lightweight)loadManifest(id,row,"current") else loadCurrentBundle(id, row) }
        val bundle = bundleResult.getOrNull()
        val bundleFailure = bundle == null
        if (bundleFailure) {
            messages +=
                bundleResult.exceptionOrNull()?.let(::storageMessage) ?: "当前插件包不可用"
        }

        var stateStatus = "unavailable"
        var stateFailure = false
        var stateRecovered = false
        try {
            val state = readState(id)
            stateRecovered = state.recovered
            stateStatus = if (state.recovered) "recovered" else "ok"
        } catch (failure: Exception) {
            stateStatus = "failed"
            stateFailure = true
            messages += "state 不可用：${storageMessage(failure)}"
        }

        val hasPrevious = row.has("previous")
        val previousOk = !hasPrevious || runCatching { if(lightweight)loadManifest(id,row,"previous") else loadBundleReference(id, row, "previous") }.isSuccess
        if (hasPrevious && !previousOk) {
            messages +=
                "上一版插件包不可用，回退已禁用：" +
                    (runCatching { loadBundleReference(id, row, "previous") }
                        .exceptionOrNull()
                        ?.let(::storageMessage)
                        ?: "无法读取")
        }

        val failed = bundleFailure || stateFailure || enabled == null || revision.isNullOrBlank()
        val recovered = stateRecovered
        if (!failed && !recovered && messages.isEmpty()) {
            return PluginHealth(id, bundle, enabled == true, hasPrevious && previousOk, null)
        }
        val status =
            when {
                failed -> "failed"
                messages.isNotEmpty() -> "degraded"
                recovered -> "recovered"
                else -> "degraded"
            }
        val issue =
            JSONObject()
                .put("id", id)
                .put("status", status)
                .put("bundle", if (bundleFailure) "failed" else "ok")
                .put("state", stateStatus)
                .put("writable", !bundleFailure && !stateFailure && enabled != null && !revision.isNullOrBlank())
                .apply { if (hasPrevious) put("previous", if (previousOk) "ok" else "failed") }
                .put("canRecover", previousOk && hasPrevious)
                .put("message", messages.distinct().joinToString("；").take(1500))
                .apply { if (stateRecovered) put("recoveredFrom", "state/<id>.json.bak") }
        return PluginHealth(id, bundle, enabled == true, hasPrevious && previousOk, issue)
    }

    private fun loadCurrentBundle(id: String, row: JSONObject): JSONObject =
        loadBundleReference(id, row, "current")

    /** Navigation only needs manifest metadata; executable admission always validates full bytes. */
    private fun loadManifest(id: String,row: JSONObject,key: String): JSONObject {
        val raw=row.optJSONObject(key)?:return loadBundleReference(id,row,key)
        val ref=parseReference(id,key,raw);val file=resolvePackage(ref.path)
        val manifest=raw.optJSONObject("manifest")
        if(manifest!=null&&file.isFile&&file.length()==ref.size.toLong()&&file.lastModified()==raw.optLong("modifiedAt",-1)&&manifest.optString("id")==id&&manifest.optString("title").isNotBlank()&&manifest.optString("version").isNotBlank()&&manifest.optJSONArray("capabilities")!=null)
            return JSONObject().put("manifest",JSONObject(manifest.toString()))
        return loadBundleReference(id,row,key)
    }

    private fun loadBundleReference(id: String, row: JSONObject, key: String): JSONObject {
        val rawReference =
            row.optJSONObject(key)
                ?: throw PluginAtomicStorage.StorageException(
                    "插件 $id 的 $key 包引用缺失或损坏" +
                        row.optString("migrationError").takeIf { it.isNotEmpty() }
                            ?.let { "：$it" }
                            .orEmpty(),
                )
        val reference = parseReference(id, key, rawReference)
        val packageFile = resolvePackage(reference.path)
        val bytes =
            try {
                PluginAtomicStorage.readBytes(packageFile, PluginContract.MAX_PACKAGE_BYTES.toLong())
            } catch (failure: Exception) {
                throw PluginAtomicStorage.StorageException(
                    "插件 $id 的 $key 包不可读取：${storageMessage(failure)}",
                    failure,
                )
            }
        if (bytes.size != reference.size) {
            throw PluginAtomicStorage.StorageException("插件 $id 的 $key 包大小与索引不一致")
        }
        if (PluginAtomicStorage.sha256(bytes) != reference.sha256) {
            throw PluginAtomicStorage.StorageException("插件 $id 的 $key 包校验失败")
        }
        val parsed =
            try {
                PluginAtomicStorage.parseJsonObject(bytes)
            } catch (failure: Exception) {
                throw PluginAtomicStorage.StorageException(
                    "插件 $id 的 $key 包不是有效 JSON：${storageMessage(failure)}",
                    failure,
                )
            }
        val bundle =
            try {
                PluginContract.validate(parsed)
            } catch (failure: Exception) {
                throw PluginAtomicStorage.StorageException(
                    "插件 $id 的 $key 包未通过语义校验：${storageMessage(failure)}",
                    failure,
                )
            }
        if (bundle.getJSONObject("manifest").getString("id") != id) {
            throw PluginAtomicStorage.StorageException("插件 $id 的 $key 包身份不一致")
        }
        return bundle
    }

    private fun writeBundleFile(id: String, bundle: JSONObject): JSONObject {
        val bytes = bundle.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= PluginContract.MAX_PACKAGE_BYTES) { "插件包超过 2 MiB" }
        val total=packagesDirectory.listFiles().orEmpty().filter{it.isFile}.sumOf{it.length()}
        if(total+bytes.size>256L*1024*1024){prunePackages();check(packagesDirectory.listFiles().orEmpty().filter{it.isFile}.sumOf{it.length()}+bytes.size<=256L*1024*1024){"插件包空间已满，恢复材料仍保留，请先处理恢复状态"}}
        val path = "packages/${UUID.randomUUID()}.json"
        val file = File(directory, path)
        PluginAtomicStorage.writeImmutable(file, bytes)
        return JSONObject()
            .put("path", path)
            .put("sha256", PluginAtomicStorage.sha256(bytes))
            .put("size", bytes.size)
            .put("modifiedAt",file.lastModified())
            .put("manifest",JSONObject(bundle.getJSONObject("manifest").toString()))
    }

    private fun parseReference(id: String, key: String, raw: JSONObject): BundleReference {
        val path = raw.optString("path")
        if (!PACKAGE_PATH.matches(path) || !path.startsWith("packages/")) {
            throw PluginAtomicStorage.StorageException("插件 $id 的 $key 包路径无效")
        }
        val sha = raw.optString("sha256")
        if (!SHA256.matches(sha)) {
            throw PluginAtomicStorage.StorageException("插件 $id 的 $key 包校验值无效")
        }
        val size = raw.optInt("size", -1)
        if (size !in 1..PluginContract.MAX_PACKAGE_BYTES) {
            throw PluginAtomicStorage.StorageException("插件 $id 的 $key 包大小无效")
        }
        return BundleReference(path, sha, size)
    }

    private fun resolvePackage(path: String): File {
        val packagesRoot = packagesDirectory.canonicalFile
        val resolved = File(directory, path).canonicalFile
        if (!resolved.toPath().startsWith(packagesRoot.toPath())) {
            throw PluginAtomicStorage.StorageException("插件包路径越界")
        }
        if (resolved.parentFile != packagesRoot) {
            throw PluginAtomicStorage.StorageException("插件包路径无效")
        }
        return resolved
    }

    private fun readState(id: String): StateRead {
        val primary = File(statesDirectory, "$id.json")
        val backup = File(statesDirectory, "$id.json.bak")
        if (!primary.exists() && !backup.exists()) {
            throw PluginAtomicStorage.StorageException(
                "插件 $id 的 state 文件缺失；为避免清空用户数据，已拒绝读写",
            )
        }
        val read =
            PluginAtomicStorage.readJson(
                primary,
                backup,
                PluginContract.MAX_STATE_BYTES.toLong(),
                ::validateStateObject,
            )
        return StateRead(read.value, read.recovered, read.source, read.primaryError)
    }

    private fun writeState(id: String, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= PluginContract.MAX_STATE_BYTES) { "插件状态超过 32 KiB" }
        val target = File(statesDirectory, "$id.json")
        val backup = File(statesDirectory, "$id.json.bak")
        PluginAtomicStorage.writeBytesAtomically(
            target,
            bytes,
            backup = backup,
            recoveryDirectory = recoveryDirectory,
            maxExistingBytes = PluginContract.MAX_STATE_BYTES.toLong(),
            previousUsable = { isUsableStateBytes(it) },
        )
    }

    private fun validateStateObject(value: JSONObject) {
        require(value.toString().toByteArray(Charsets.UTF_8).size <= PluginContract.MAX_STATE_BYTES) {
            "插件 state 超过 32 KiB"
        }
    }

    private fun isUsableStateBytes(bytes: ByteArray): Boolean =
        runCatching {
            val value = PluginAtomicStorage.parseJsonObject(bytes)
            validateStateObject(value)
        }.isSuccess

    private fun entry(id: String): JSONObject {
        requireIndexReadable()
        val validId = PluginContract.id(id)
        val plugins = pluginsObject()
        if (!plugins.has(validId)) throw JSONException("插件不存在：$validId")
        val row =
            plugins.optJSONObject(validId)
                ?: throw PluginAtomicStorage.StorageException("插件 $validId 的索引记录不是对象")
        require(row.opt("removed") != true) { "插件已移除" }
        return row
    }

    private fun revisionOf(id: String, row: JSONObject): String {
        val revision =
            row.opt("revision") as? String
                ?: throw PluginAtomicStorage.StorageException("插件 $id 的 revision 字段损坏")
        if (revision.isBlank()) {
            throw PluginAtomicStorage.StorageException("插件 $id 的 revision 字段损坏")
        }
        return revision
    }

    private fun pluginsObject(): JSONObject =
        registry.optJSONObject("plugins")
            ?: throw PluginAtomicStorage.StorageException("插件索引结构损坏：缺少 plugins")

    private fun requireIndexReadable() {
        if (!indexReadable) {
            throw PluginAtomicStorage.StorageException(
                "插件存储索引不可用，已保持只读：${indexMessage ?: "无法读取 registry.json"}",
            )
        }
    }

    private fun requireIndexWritable() {
        requireIndexReadable()
        if (!indexWritable) {
            throw PluginAtomicStorage.StorageException(
                "插件存储索引当前不可写：${indexMessage ?: "没有可恢复索引"}",
            )
        }
    }

    private fun copy(): JSONObject = JSONObject(registry.toString())

    private fun save(next: JSONObject) {
        requireIndexWritable()
        val bytes = next.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_INDEX_BYTES) { "插件注册表超过 16 MiB" }
        val recoveryCopy =
            PluginAtomicStorage.writeBytesAtomically(
                registryFile,
                bytes,
                backup = registryBackupFile,
                recoveryDirectory = recoveryDirectory,
                maxExistingBytes = MAX_INDEX_BYTES,
                previousUsable = { isUsableIndexBytes(it) },
            )
        registry = next
        indexReadable = true
        indexWritable = true
        indexSource = relative(registryFile)
        if (recoveryCopy != null) {
            indexMessage =
                "保存前已将损坏的 registry.json 保留为 ${relative(recoveryCopy)}，并完成索引恢复"
            indexStatus = "recovered"
        }
        if (indexStatus == "recovered" && indexMessage == null) {
            indexMessage = "主索引已从 registry.json.bak 恢复"
        }
        runCatching { prunePackages() }
    }

    private fun prunePackages() {
        val used=mutableSetOf<String>()
        fun collect(index: JSONObject) {
            val rows=index.optJSONObject("plugins")?:return
            for(id in rows.keys()){val row=rows.optJSONObject(id)?:continue;for(key in listOf("current","previous"))row.optJSONObject(key)?.optString("path")?.takeIf{PACKAGE_PATH.matches(it)}?.let{used+=it}}
        }
        collect(registry)
        val candidates=listOf(registryBackupFile)+(recoveryDirectory.listFiles()?.filter{it.isFile}?:emptyList())
        for(file in candidates) {
            if(file.length()>MAX_INDEX_BYTES)continue
            // An unreadable reference snapshot is retained, so its packages are conservatively retained too.
            val value=runCatching{JSONObject(file.readText())}.getOrNull()?:return
            collect(value)
        }
        for(file in packagesDirectory.listFiles().orEmpty())if(file.isFile&&"packages/${file.name}" !in used)file.delete()
    }

    private fun validateIndexRoot(root: JSONObject) {
        if (root.has("schemaVersion")) {
            require(root.opt("schemaVersion") == 2) { "不支持此插件索引版本" }
            val plugins = root.opt("plugins")
            require(plugins is JSONObject) { "插件索引缺少 plugins 对象" }
            for (id in plugins.keys()) {
                PluginContract.id(id)
            }
        } else {
            // Legacy v1: plugin ID directly at the root. Empty legacy registries are valid.
            for (id in root.keys()) {
                PluginContract.id(id)
                // Bad legacy rows are quarantined by migrateLegacy without hiding healthy rows.
            }
        }
    }

    private fun indexKind(root: JSONObject): IndexKind =
        if (root.has("schemaVersion")) IndexKind.V2 else IndexKind.LEGACY

    private fun isUsableIndexBytes(bytes: ByteArray): Boolean =
        runCatching {
            val root=PluginAtomicStorage.parseJsonObject(bytes)
            validateIndexRoot(root)
            if(indexKind(root)==IndexKind.V2){
                val rows=root.getJSONObject("plugins")
                for(id in rows.keys()){
                    val row=rows.getJSONObject(id)
                    if(row.optBoolean("removed"))continue
                    require(row.opt("enabled") is Boolean&&row.optString("revision").isNotBlank())
                    parseReference(id,"current",row.getJSONObject("current"))
                    if(row.has("previous"))parseReference(id,"previous",row.getJSONObject("previous"))
                }
            }else for(id in root.keys())require(root.opt(id) is JSONObject)
        }.isSuccess

    private fun indexJson(): JSONObject =
        JSONObject()
            .put("status", indexStatus)
            .put("source", indexSource)
            .put("primary", "registry.json")
            .put("backup", "registry.json.bak")
            .apply { indexMessage?.let { put("message", it) } }

    private fun relative(file: File): String =
        runCatching { PluginAtomicStorage.relativePath(directory, file) }
            .getOrDefault(file.name)

    private fun hasOrphanedStorage(): Boolean =
        listOf(packagesDirectory, statesDirectory, recoveryDirectory).any { child ->
            child.isDirectory && child.listFiles()?.any { it.isFile } == true
        }

    private fun storageMessage(failure: Throwable): String =
        (failure.message ?: failure.javaClass.simpleName)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(500)

    private fun emptyRegistry(): JSONObject =
        JSONObject().put("schemaVersion", 2).put("plugins", JSONObject())

    private data class BundleReference(val path: String, val sha256: String, val size: Int)

    private data class StateRead(
        val value: JSONObject,
        val recovered: Boolean,
        val source: File,
        val primaryError: String?,
    )

    private data class PluginHealth(
        val id: String,
        val bundle: JSONObject?,
        val enabled: Boolean,
        val canRollback: Boolean,
        val issue: JSONObject?,
    )

    private enum class IndexKind { LEGACY, V2 }

    companion object {
        private const val MAX_PLUGINS = 32
        private const val MAX_INDEX_BYTES = 16L * 1024 * 1024
        private val PACKAGE_PATH = Regex("packages/[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.json")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
