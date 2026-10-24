package com.personaldashboard.app

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Authenticated model discovery; keys and upstream error bodies never return to the page. */
internal class DeepseekModels(
    private val client: OkHttpClient = defaultClient,
    private val endpoint: String = "https://api.deepseek.com/models",
) {
    fun read(rawKey: String): JSONObject {
        val key = rawKey.trim()
        require(key.isNotEmpty()) { "请先填写 DeepSeek API Key。" }
        require(key.length <= 8192 && key.none { it.isWhitespace() || it.code < 32 }) { "Key 不能包含空格或换行。" }
        try {
            val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key").get().build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) {
                    when (response.code) {
                        401, 403 -> "Key 无效或无权访问，请检查后重试。"
                        429 -> "服务限流，请稍后重试。"
                        else -> "模型服务连接失败（HTTP ${response.code}）。"
                    }
                }
                val body = response.body ?: error("模型服务未返回数据。")
                val bytes = body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 512 * 1024) { "模型列表响应过大。" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val data =
                    try {
                        JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("data")
                    } catch (_: Exception) {
                        error("模型服务返回的列表格式无效。")
                    }
                val ids = linkedSetOf<String>()
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    require(id.isNotBlank() && id.length <= 200 && id.none { it.isWhitespace() || it.code < 32 }) {
                        "模型服务返回了无效的模型 ID。"
                    }
                    ids.add(id)
                    require(ids.size <= 128) { "模型列表数量过多。" }
                }
                require(ids.isNotEmpty()) { "Key 已连通，但服务未返回可用模型。" }
                return JSONObject()
                    .put("verified", true)
                    .put("models", JSONArray(ids.map { JSONObject().put("id", it) }))
            }
        } catch (_: IOException) {
            error("模型服务连接失败，请检查网络后重试。")
        }
    }
    companion object {
        private val defaultClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(12, TimeUnit.SECONDS).build()
    }
}
