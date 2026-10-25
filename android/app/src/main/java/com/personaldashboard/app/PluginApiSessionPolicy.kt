package com.personaldashboard.app

import java.security.MessageDigest
import okhttp3.Cookie
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure cookie decisions for plugin-scoped API sessions.
 *
 * Cookie values pass through this policy only inside the host process. No method here is exposed
 * across the plugin bridge, and metadata output deliberately has no name/value/domain field that
 * could become a credential oracle.
 */
internal object PluginApiSessionPolicy {
    const val DEFAULT_CONTEXT = "default"
    const val MAX_COOKIES_PER_CONTEXT = 128
    const val MAX_COOKIE_VALUE_BYTES = 8192
    private val CONTEXT = Regex("[a-zA-Z0-9_-]{1,32}")

    fun pluginId(value: String): String = PluginContract.id(value)

    fun contextName(value: String? = DEFAULT_CONTEXT): String {
        val context = value ?: DEFAULT_CONTEXT
        require(CONTEXT.matches(context)) { "登录上下文编号无效" }
        return context
    }

    fun contextId(pluginId: String, context: String? = DEFAULT_CONTEXT) =
        PluginApiSessionContextId(pluginId(pluginId), contextName(context))

    /** Fetch defaults to credentials:"omit"; only exact "include" can activate a session jar. */
    fun credentialsIncluded(args: JSONObject): Boolean {
        if (!args.has("credentials")) return false
        return when (val value = args.get("credentials")) {
            "omit" -> false
            "include" -> true
            else -> throw IllegalArgumentException("仅支持 credentials: omit/include")
        }
    }

    /**
     * The caller must have a host-bound plugin identity. An anonymous host call cannot opt into a
     * persistent jar, and cannot accidentally borrow another plugin's jar by supplying an id.
     */
    fun credentialsContext(
        args: JSONObject,
        hostPluginId: String?,
    ): PluginApiSessionContextId? {
        if (!credentialsIncluded(args)) return null
        require(!hostPluginId.isNullOrBlank()) { "缺少插件身份，不能使用持久登录会话" }
        return contextId(hostPluginId, args.optString("sessionContext", DEFAULT_CONTEXT))
    }

    fun activeCookies(cookies: List<Cookie>, nowMs: Long): List<Cookie> =
        cookies.filter { it.expiresAt > nowMs }

    /** OkHttp owns RFC domain/path/host-only/secure matching; longer paths are sent first. */
    fun requestCookies(
        cookies: List<Cookie>,
        url: HttpUrl,
        nowMs: Long,
    ): List<Cookie> =
        activeCookies(cookies, nowMs)
            .filter { it.matches(url) }
            .sortedWith(
                compareByDescending<Cookie> { it.path.length }
                    .thenBy { it.domain }
                    .thenBy { it.name },
            )

    fun cookieKey(cookie: Cookie): String =
        cookie.name + "\u0000" + cookie.domain.lowercase() + "\u0000" + cookie.path

    /**
     * RFC-style name/domain/path replacement, while retaining session cookies for restarts.
     * Expired cookies are deletions and are never retained or serialized.
     */
    fun replace(
        current: List<Cookie>,
        incoming: List<Cookie>,
        nowMs: Long,
    ): List<Cookie> {
        var result = activeCookies(current, nowMs).toMutableList()
        for (cookie in incoming) {
            require(
                cookie.name.toByteArray(Charsets.UTF_8).size <= MAX_COOKIE_VALUE_BYTES &&
                    cookie.value.toByteArray(Charsets.UTF_8).size <= MAX_COOKIE_VALUE_BYTES,
            ) {
                "登录 Cookie 过大"
            }
            val key = cookieKey(cookie)
            result.removeAll { cookieKey(it) == key }
            if (cookie.expiresAt > nowMs) {
                if (result.size >= MAX_COOKIES_PER_CONTEXT) {
                    throw IllegalArgumentException("登录 Cookie 数量超过上限")
                }
                result.add(cookie)
            }
        }
        return result.sortedWith(
            compareByDescending<Cookie> { it.path.length }
                .thenBy { it.domain }
                .thenBy { it.name },
        )
    }

    fun encodeCookies(cookies: List<Cookie>, nowMs: Long): JSONObject {
        val active = activeCookies(cookies, nowMs)
        val records = JSONArray()
        active.forEach { cookie ->
            records.put(
                JSONObject()
                    .put("name", cookie.name)
                    .put("value", cookie.value)
                    .put("domain", cookie.domain)
                    .put("path", cookie.path)
                    .put("expiresAt", cookie.expiresAt)
                    .put("persistent", cookie.persistent)
                    .put("secure", cookie.secure)
                    .put("httpOnly", cookie.httpOnly)
                    .put("hostOnly", cookie.hostOnly),
            )
        }
        return JSONObject().put("version", 1).put("cookies", records)
    }

    fun decodeCookies(value: JSONObject): List<Cookie> {
        require(value.optInt("version", -1) == 1) { "登录会话存储版本无效" }
        val records = value.optJSONArray("cookies") ?: JSONArray()
        require(records.length() <= MAX_COOKIES_PER_CONTEXT) { "登录 Cookie 数量超过上限" }
        val cookies = mutableListOf<Cookie>()
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: throw IllegalArgumentException("登录 Cookie 无效")
            val name = record.optString("name")
            val cookieValue = record.optString("value")
            val domain = record.optString("domain")
            val path = record.optString("path")
            val expiresAt = record.optLong("expiresAt", -1)
            require(
                name.isNotEmpty() && cookieValue.length <= MAX_COOKIE_VALUE_BYTES &&
                    domain.isNotEmpty() && path.startsWith("/") && expiresAt > 0,
            ) {
                "登录 Cookie 无效"
            }
            val builder = Cookie.Builder().name(name).value(cookieValue).path(path)
            if (record.optBoolean("hostOnly")) builder.hostOnlyDomain(domain) else builder.domain(domain)
            if (record.optBoolean("persistent", true)) builder.expiresAt(expiresAt)
            if (record.optBoolean("secure")) builder.secure()
            if (record.optBoolean("httpOnly")) builder.httpOnly()
            cookies.add(builder.build())
        }
        return cookies
    }

    fun status(
        contextId: PluginApiSessionContextId,
        cookies: List<Cookie>,
        nowMs: Long,
        updatedAtMs: Long,
    ): JSONObject {
        val active = activeCookies(cookies, nowMs)
        return JSONObject()
            .put("pluginId", contextId.pluginId)
            .put("context", contextId.context)
            .put("cookieCount", active.size)
            .put("persistentCookieCount", active.count { it.persistent })
            .put("sessionCookieCount", active.count { !it.persistent })
            .put("hasCookies", active.isNotEmpty())
            .put("updatedAtMs", updatedAtMs)
    }

    fun storageToken(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun validateUrlForCookie(url: HttpUrl): HttpUrl {
        require(url.username.isEmpty() && url.password.isEmpty()) { "Cookie 地址不能包含账号信息" }
        require(url.scheme == "http" || url.scheme == "https") { "Cookie 仅支持 HTTP 或 HTTPS" }
        return url
    }
}
