package com.personaldashboard.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Private attachments use opaque IDs bound to plugin+thread. No paths or credentials enter JS. */
class PluginImages(private val context: Context) {
    private val root = File(context.filesDir, "plugin-media")

    private fun folder(plugin: String, thread: String): File {
        PluginContract.id(plugin)
        PluginContract.validateResourceId(thread)
        return File(root, "$plugin/$thread")
    }

    @Synchronized
    fun import(
        plugin: String,
        thread: String,
        uris: List<Uri>,
        cancellation: PluginCancellation,
    ): JSONArray {
        cancellation.check()
        require(uris.size in 1..MAX_IMAGES_PER_MESSAGE) { "每次最多4张图片" }
        require(
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() } <
                MAX_MEDIA_STORE_BYTES,
        ) {
            "待发送图片较多，请先发送或移除未使用插件"
        }
        val dir = folder(plugin, thread)
        dir.mkdirs()
        // One native view owns one pending selection per thread. Replacing it releases the old
        // bounded cache immediately instead of waiting for plugin removal.
        dir.listFiles()?.forEach { it.delete() }
        val result = JSONArray()
        val created = mutableListOf<File>()
        var total = 0
        try {
            for ((i, uri) in uris.withIndex()) {
                cancellation.check()
                val mime = context.contentResolver.getType(uri)
                require(mime in setOf("image/png", "image/jpeg", "image/webp")) {
                    "仅支持 PNG、JPEG、WebP"
                }
                val input = context.contentResolver.openInputStream(uri) ?: error("无法读取图片")
                val bytes = input.use { s ->
                    val out = ByteArrayOutputStream()
                    val b = ByteArray(8192)
                    while (true) {
                        cancellation.check()
                        val n = s.read(b)
                        if (n < 0) break
                        require(out.size() + n <= MAX_SOURCE_IMAGE_BYTES) { "单张原图超过30MiB" }
                        out.write(b, 0, n)
                    }
                    out.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
                var sample = 1
                while (
                    maxOf(bounds.outWidth, bounds.outHeight) / sample >
                        MAX_IMPORT_DECODE_DIMENSION
                )
                    sample *= 2
                val bitmap =
                    BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    ) ?: error("图片无法解码")
                val scale = minOf(1.0, MAX_IMPORT_IMAGE_EDGE / maxOf(bitmap.width, bitmap.height))
                val scaled =
                    if (scale < 1)
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * scale).toInt().coerceAtLeast(1),
                            (bitmap.height * scale).toInt().coerceAtLeast(1),
                            true,
                        )
                    else bitmap
                val orientation = runCatching {
                    androidx.exifinterface.media
                        .ExifInterface(java.io.ByteArrayInputStream(bytes))
                        .getAttributeInt(
                            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                            1,
                        )
                }
                    .getOrDefault(1)
                val matrix =
                    android.graphics.Matrix().apply {
                        when (orientation) {
                            2 -> setScale(-1f, 1f)
                            3 -> setRotate(180f)
                            4 -> setScale(1f, -1f)
                            5 -> {
                                setRotate(90f)
                                postScale(-1f, 1f)
                            }
                            6 -> setRotate(90f)
                            7 -> {
                                setRotate(-90f)
                                postScale(-1f, 1f)
                            }
                            8 -> setRotate(-90f)
                        }
                    }
                val oriented =
                    if (orientation in 2..8)
                        Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
                    else scaled
                val out = ByteArrayOutputStream()
                oriented.compress(Bitmap.CompressFormat.JPEG, 88, out)
                if (oriented !== scaled) oriented.recycle()
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                val encoded = out.toByteArray()
                require(encoded.size <= MAX_ENCODED_IMAGE_BYTES) { "压缩后图片仍超过2MiB" }
                total += encoded.size
                require(total <= MAX_TOTAL_IMAGE_BYTES) { "图片合计超过6MiB" }
                val id = UUID.randomUUID().toString()
                val f = File(dir, "$id.jpg")
                synchronized(cancellation) {
                    cancellation.check()
                    f.writeBytes(encoded)
                    created += f
                }
                result.put(JSONObject().put("id", id).put("name", "图片${i+1}.jpg"))
            }
            return result
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw e
        }
    }

    @Synchronized
    fun payload(plugin: String, thread: String, refs: JSONArray): JSONArray {
        require(refs.length() <= MAX_IMAGES_PER_MESSAGE)
        val out = JSONArray()
        var total = 0
        for (i in 0 until refs.length()) {
            val ref = refs.getJSONObject(i)
            val id = ref.getString("id").also(PluginContract::validateResourceId)
            val f = File(folder(plugin, thread), "$id.jpg")
            require(f.isFile && f.length() <= MAX_ENCODED_IMAGE_BYTES) {
                "图片已不可用，请核对发送状态后重新选择"
            }
            val bytes = f.readBytes()
            total += bytes.size
            require(total <= MAX_TOTAL_IMAGE_BYTES)
            out.put(
                JSONObject()
                    .put("name", ref.optString("name", "图片.jpg").take(80))
                    .put(
                        "dataUrl",
                        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                    ),
            )
        }
        return out
    }

    @Synchronized
    fun clear(plugin: String, thread: String, refs: JSONArray) {
        for (i in 0 until refs.length()) {
            val id = refs.getJSONObject(i).getString("id")
            PluginContract.validateResourceId(id)
            File(folder(plugin, thread), "$id.jpg").delete()
        }
    }

    @Synchronized
    fun clearAll(plugin: String, thread: String) {
        File(root, "$plugin/$thread").deleteRecursively()
    }

    @Synchronized
    fun remove(plugin: String) {
        PluginContract.id(plugin)
        check(File(root, plugin).deleteRecursively()) { "图片清理失败" }
        File(context.cacheDir, "plugin-previews/$plugin").deleteRecursively()
    }

    /**
     * Convert inline historical thumbnails into bounded private cache references before returning
     * to JS.
     */
    fun stagePreviews(
        plugin: String,
        thread: String,
        detail: JSONObject,
        token: PluginCancellation,
    ): JSONObject {
        val dir = File(context.cacheDir, "plugin-previews/$plugin/$thread")
        val current = mutableSetOf<String>()
        val messages = detail.optJSONArray("messages") ?: return detail
        for (i in 0 until messages.length()) {
            val images = messages.getJSONObject(i).optJSONArray("images") ?: continue
            for (j in 0 until images.length()) {
                val img = images.getJSONObject(j)
                val url = img.optString("dataUrl")
                if (url.isEmpty()) continue
                img.remove("dataUrl")
                if (
                    url.length > MAX_PREVIEW_URL_LENGTH ||
                        !Regex("^data:image/(png|jpeg|webp);base64,").containsMatchIn(url)
                ) {
                    img.put("previewOmitted", true)
                    continue
                }
                val key =
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(url.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                current += key
                synchronized(token) {
                    token.check()
                    dir.mkdirs()
                    val f = File(dir, "$key.json")
                    val temp = File(dir, "$key.tmp")
                    temp.writeText(JSONObject().put("dataUrl", url).toString())
                    check(temp.renameTo(f)) { "图片缓存写入失败" }
                    f.setLastModified(System.currentTimeMillis())
                }
                img.put("previewId", key)
            }
        }
        val all =
            File(context.cacheDir, "plugin-previews/$plugin")
                .walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.lastModified() }
                .toList()
        var bytes = all.sumOf { it.length() }
        for (f in all) {
            if (bytes <= MAX_PREVIEW_CACHE_BYTES) break
            if (f.parentFile == dir && f.nameWithoutExtension in current) continue
            val n = f.length()
            if (f.delete()) bytes -= n
        }
        return detail
    }

    fun preview(plugin: String, thread: String, key: String): JSONObject {
        PluginContract.id(plugin)
        PluginContract.validateResourceId(thread)
        require(Regex("[a-f0-9]{64}").matches(key)) { "图片引用无效" }
        val f = File(context.cacheDir, "plugin-previews/$plugin/$thread/$key.json")
        require(f.isFile && f.length() <= MAX_PREVIEW_FILE_BYTES) {
            "图片缓存已失效，请刷新历史页"
        }
        return JSONObject(f.readText())
    }

    companion object {
        private const val MAX_IMAGES_PER_MESSAGE = 4
        private const val MAX_MEDIA_STORE_BYTES = 64L * 1024 * 1024
        private const val MAX_SOURCE_IMAGE_BYTES = 30 * 1024 * 1024
        private const val MAX_ENCODED_IMAGE_BYTES = 2 * 1024 * 1024
        private const val MAX_TOTAL_IMAGE_BYTES = 6 * 1024 * 1024
        private const val MAX_IMPORT_DECODE_DIMENSION = 3_200
        private const val MAX_IMPORT_IMAGE_EDGE = 1_600.0
        private const val MAX_PREVIEW_URL_LENGTH = 3 * 1024 * 1024
        private const val MAX_PREVIEW_FILE_BYTES = 3L * 1024 * 1024 + 100
        private const val MAX_PREVIEW_CACHE_BYTES = 32L * 1024 * 1024
        private const val MAX_PREVIEW_DECODE_DIMENSION = 2_000

        fun decode(value: JSONObject): Bitmap {
            val url = value.getString("dataUrl")
            require(Regex("^data:image/(png|jpeg|webp);base64,").containsMatchIn(url)) { "图片类型不支持" }
            val bytes = Base64.decode(url.substringAfter(','), Base64.DEFAULT)
            require(bytes.size <= MAX_ENCODED_IMAGE_BYTES) { "图片超过2MiB" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
            var sample = 1
            while (
                maxOf(bounds.outWidth, bounds.outHeight) / sample >
                    MAX_PREVIEW_DECODE_DIMENSION
            )
                sample *= 2
            return BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: error("图片无法解码")
        }
    }
}
