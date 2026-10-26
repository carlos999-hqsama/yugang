package com.personaldashboard.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * Small, JSON-oriented storage primitive shared only by the plugin stores.
 *
 * A replacement is first made durable in a sibling temporary file and then renamed over the
 * target. Index/state/background replacements also rotate the previous valid bytes to a sibling
 * backup. If the current bytes are already damaged, they are copied into the app-private recovery
 * directory and are never discarded as part of a normal save.
 */
internal object PluginAtomicStorage {
    internal class StorageException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    internal data class JsonRead(
        val value: JSONObject,
        val source: File,
        val recovered: Boolean,
        val primaryError: String?,
    )

    internal fun readJson(
        primary: File,
        backup: File?,
        maxBytes: Long,
        accept: (JSONObject) -> Unit,
    ): JsonRead {
        val primaryFailure =
            if (!primary.isFile) {
                StorageException(
                    if (primary.exists()) "主存储文件不是普通文件" else "主存储文件不存在",
                )
            } else {
                runCatching {
                    val parsed = parseJsonObject(readBytes(primary, maxBytes))
                    accept(parsed)
                    return JsonRead(parsed, primary, false, null)
                }
                    .exceptionOrNull()
            }

        if (backup?.isFile == true) {
            runCatching {
                val parsed = parseJsonObject(readBytes(backup, maxBytes))
                accept(parsed)
                return JsonRead(
                    parsed,
                    backup,
                    true,
                    primaryFailure?.summary() ?: "主存储文件不存在",
                )
            }
                .onFailure { backupFailure ->
                    throw StorageException(
                        "插件存储主文件和备份均不可用：${primaryFailure?.summary() ?: "主存储文件不存在"}；" +
                            "备份：${backupFailure.summary()}",
                        backupFailure,
                    )
                }
        } else if (backup != null && backup.exists()) {
            throw StorageException(
                "插件存储主文件和备份均不可用：${primaryFailure?.summary() ?: "主存储文件不存在"}；" +
                    "备份不是普通文件",
            )
        }

        throw StorageException(
            "插件存储不可用：${primaryFailure?.summary() ?: "主存储文件不存在"}",
            primaryFailure,
        )
    }

    /**
     * Replaces [target] atomically. [previousUsable] decides whether the current bytes are rotated
     * to [backup]. If false, those bytes are copied to [recoveryDirectory] before replacement.
     * A failed write leaves the current target in place and keeps the in-memory caller unchanged.
     *
     * @return the recovery copy made for damaged current bytes, if any.
     */
    internal fun writeBytesAtomically(
        target: File,
        bytes: ByteArray,
        backup: File? = null,
        recoveryDirectory: File? = null,
        maxExistingBytes: Long = 16L * 1024 * 1024,
        previousUsable: ((ByteArray) -> Boolean)? = null,
    ): File? {
        val parent = target.parentFile ?: throw StorageException("存储目标没有父目录")
        ensureDirectory(parent)
        if (Files.isSymbolicLink(target.toPath())) {
            throw StorageException("拒绝覆盖符号链接存储文件")
        }
        if (target.exists() && !target.isFile) {
            throw StorageException("存储目标不是普通文件，未写入：${target.name}")
        }

        var recoveryCopy: File? = null
        if (target.isFile) {
            val existing = readBytes(target, maxExistingBytes)
            val usable = previousUsable?.invoke(existing) ?: true
            if (usable) {
                if (backup != null) {
                    rotateBackup(targetBytes = existing, backup = backup)
                }
            } else {
                val recoveryDir =
                    recoveryDirectory
                        ?: throw StorageException("当前存储数据损坏且没有可用的私有恢复目录，拒绝覆盖")
                recoveryCopy = preserveBytes(existing, recoveryDir, target.name)
            }
        }
        writeRawAtomically(target, bytes, replace = true)
        return recoveryCopy
    }

    internal fun writeStandaloneAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw StorageException("存储目标没有父目录")
        ensureDirectory(parent)
        if (Files.isSymbolicLink(target.toPath())) {
            throw StorageException("拒绝覆盖符号链接存储文件")
        }
        if (target.exists() && !target.isFile) {
            throw StorageException("存储目标不是普通文件，未写入：${target.name}")
        }
        writeRawAtomically(target, bytes, replace = true)
    }

    internal fun writeImmutable(target: File, bytes: ByteArray): File {
        val parent = target.parentFile ?: throw StorageException("不可变文件没有父目录")
        ensureDirectory(parent)
        if (target.exists() || Files.isSymbolicLink(target.toPath())) {
            throw StorageException("不可变存储文件已存在：${target.name}")
        }
        writeRawAtomically(target, bytes, replace = false)
        verifyFileBytes(target, bytes)
        return target
    }

    internal fun preserveFile(source: File, recoveryDirectory: File, hint: String): File {
        val max = 16L * 1024 * 1024
        val bytes = readBytes(source, max)
        return preserveBytes(bytes, recoveryDirectory, hint)
    }

    internal fun readBytes(file: File, maxBytes: Long): ByteArray {
        if (!file.isFile) throw StorageException("存储文件不存在或不是普通文件：${file.name}")
        if (Files.isSymbolicLink(file.toPath())) {
            throw StorageException("拒绝读取符号链接存储文件：${file.name}")
        }
        if (file.length() > maxBytes) {
            throw StorageException("存储文件超过允许大小：${file.name}")
        }
        val output = ByteArrayOutputStream()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw StorageException("存储文件超过允许大小：${file.name}")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    internal fun parseJsonObject(bytes: ByteArray): JSONObject =
        try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (failure: Exception) {
            throw StorageException("存储 JSON 无法解析", failure)
        }

    internal fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val result = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        for (index in digest.indices) {
            val value = digest[index].toInt() and 0xff
            result[index * 2] = digits[value ushr 4]
            result[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(result)
    }

    internal fun relativePath(root: File, file: File): String =
        root.canonicalFile
            .toPath()
            .relativize(file.canonicalFile.toPath())
            .toString()
            .replace(File.separatorChar, '/')

    internal fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw StorageException("无法创建插件私有目录：${directory.name}")
        }
        if (!directory.isDirectory) throw StorageException("插件私有路径不是目录：${directory.name}")
    }

    private fun rotateBackup(targetBytes: ByteArray, backup: File) {
        val parent = backup.parentFile ?: throw StorageException("备份目标没有父目录")
        ensureDirectory(parent)
        if (Files.isSymbolicLink(backup.toPath())) {
            throw StorageException("拒绝覆盖符号链接备份文件")
        }
        if (backup.exists() && !backup.isFile) {
            throw StorageException("备份目标不是普通文件，未写入：${backup.name}")
        }
        writeRawAtomically(backup, targetBytes, replace = true)
        verifyFileBytes(backup, targetBytes)
    }

    private fun preserveBytes(bytes: ByteArray, recoveryDirectory: File, hint: String): File {
        ensureDirectory(recoveryDirectory)
        val safeHint = hint.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(96)
        val target =
            File(
                recoveryDirectory,
                "$safeHint.corrupt-${System.currentTimeMillis()}-${UUID.randomUUID()}.bak",
            )
        writeImmutable(target, bytes)
        return target
    }

    private fun writeRawAtomically(target: File, bytes: ByteArray, replace: Boolean) {
        val parent = target.parentFile ?: throw StorageException("存储目标没有父目录")
        ensureDirectory(parent)
        val temporary = File(parent, ".${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            verifyFileBytes(temporary, bytes)
            val options =
                if (replace) {
                    arrayOf(
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } else {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE)
                }
            try {
                Files.move(temporary.toPath(), target.toPath(), *options)
            } catch (failure: Exception) {
                throw StorageException("无法原子提交插件存储文件：${target.name}", failure)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun verifyFileBytes(file: File, expected: ByteArray) {
        val actual = readBytes(file, expected.size.toLong().coerceAtLeast(1L))
        if (actual.size != expected.size || sha256(actual) != sha256(expected)) {
            throw StorageException("插件存储文件写入校验失败：${file.name}")
        }
    }

    private fun Throwable.summary(): String =
        (message ?: javaClass.simpleName).replace('\n', ' ').replace('\r', ' ').take(300)
}
