package com.personaldashboard.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject

/** Only app-owned MediaStore downloads may be handed to a system viewer. */
object DownloadedFiles {
    fun receipt(context: Context, uri: Uri, fallbackName: String, mime: String, bytes: Long): JSONObject {
        val info = owned(context, uri)
        return JSONObject().put("downloaded", true).put("name", info.first.ifBlank { fallbackName })
            .put("mime", info.second.ifBlank { mime }).put("bytes", bytes).put("uri", uri.toString())
            .put("displayPath", "下载/鱼缸/${info.first.ifBlank { fallbackName }}")
    }

    fun open(context: Context, args: JSONObject): JSONObject {
        val uri = Uri.parse(args.getString("uri"))
        val info = owned(context, uri)
        if (info.second == "application/vnd.android.package-archive")
            return JSONObject().put("opened", false).put("reason", "安装包已在下载/鱼缸，请从系统下载管理器手动打开")
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, info.second)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                    if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            JSONObject().put("opened", true).put("uri", uri.toString()).put("name", info.first)
        } catch (_: ActivityNotFoundException) {
            JSONObject().put("opened", false).put("reason", "手机没有可打开此类型的应用，文件已保存在下载/鱼缸")
        }
    }

    private fun owned(context: Context, uri: Uri): Pair<String, String> {
        val segments = uri.pathSegments
        require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY && uri.query == null && uri.fragment == null &&
            segments.size == 3 && segments[0] in setOf("external", "external_primary") && segments[1] == "downloads" &&
            segments[2].toLongOrNull()?.let { it > 0 } == true) { "只支持本 App 下载文件的句柄" }
        val fields = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        val cursor = context.contentResolver.query(uri, fields, null, null, null) ?: error("下载文件已不可用")
        cursor.use {
            check(it.moveToFirst()) { "下载文件已不可用" }
            require(it.getString(2) == context.packageName && it.getString(3).trimEnd('/') == "Download/鱼缸") { "文件不是本 App 保存的下载文件" }
            return it.getString(0) to (it.getString(1) ?: "application/octet-stream")
        }
    }
}
