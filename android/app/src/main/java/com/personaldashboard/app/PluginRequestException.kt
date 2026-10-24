package com.personaldashboard.app

import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONObject

/** Public request metadata only; response bodies and host credentials never enter bridge errors. */
class PluginRequestException(
    val code: String,
    override val message: String,
    val status: Int = 0,
    val finalUrl: String = "",
    val timeout: Boolean = false,
    val cancelled: Boolean = false,
    val retryable: Boolean = false,
) : RuntimeException(message) {
    fun payload(): JSONObject =
        JSONObject()
            .put("error", message)
            .put("code", code)
            .put("status", status)
            .put("finalUrl", finalUrl)
            .put("timeout", timeout)
            .put("cancelled", cancelled)
            .put("retryable", retryable)

    companion object {
        fun network(
            error: java.io.IOException,
            finalUrl: String,
            status: Int,
            cancelled: Boolean,
        ): PluginRequestException {
            val timeout = error is InterruptedIOException && !cancelled
            val code =
                when {
                    cancelled -> "cancelled"
                    timeout -> "timeout"
                    error is UnknownHostException -> "dns_error"
                    error is SSLException -> "tls_error"
                    else -> "network_error"
                }
            val message =
                when (code) {
                    "cancelled" -> "请求已取消"
                    "timeout" -> "请求超时"
                    "dns_error" -> "无法解析服务地址"
                    "tls_error" -> "安全连接失败"
                    else -> "网络请求失败"
                }
            return PluginRequestException(
                code,
                message,
                status,
                finalUrl,
                timeout,
                cancelled,
                !cancelled && code != "tls_error",
            )
        }
    }
}
