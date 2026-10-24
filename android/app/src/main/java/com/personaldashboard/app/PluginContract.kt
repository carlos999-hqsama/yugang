package com.personaldashboard.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Versioned, data-only UI contract. Plugin JavaScript never enters the main WebView. */
object PluginContract {
    const val MAX_PACKAGE_BYTES = 2 * 1024 * 1024
    const val MAX_STATE_BYTES = 32768
    /** 插件视图文本上限。 */
    const val MAX_VIEW_TEXT_CHARS = 16000
    val capabilities =
        setOf(
            "app.call",
            "plugin.state",
            "plugin.model",
            "plugin.media",
            "plugin.voice",
            "plugin.tts",
            "plugin.background",
            "plugin.notification",
        )

    fun id(value: String): String = value.also {
        require(Regex("[a-z][a-z0-9-]{1,47}").matches(it)) { "插件 ID 只能包含小写字母、数字和短横线" }
        require(it !in setOf("harness", "settings", "plugins")) { "此 ID 属于宿主" }
    }

    fun validate(bundle: JSONObject): JSONObject {
        require(bundle.toString().toByteArray().size <= MAX_PACKAGE_BYTES) { "插件包超过 2 MiB" }
        val m = bundle.getJSONObject("manifest")
        require(m.getInt("apiVersion") == 1) { "不支持此插件 API 版本" }
        id(m.getString("id"))
        require(m.getString("title").length in 1..40) { "插件标题须为 1–40 字" }
        require(m.getString("version").length in 1..40) { "插件版本无效" }
        val caps = m.getJSONArray("capabilities")
        require((0 until caps.length()).all { caps.getString(it) in capabilities }) {
            "宿主尚不支持所请求的能力"
        }
        require(m.getString("kind") == "native") { "插件类型必须是 native" }
        require(!m.has("entry")) { "插件不能跳转宿主页面" }
        if (m.has("onOpen"))
            require(Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}").matches(m.getString("onOpen"))) {
                "插件打开动作无效"
            }
        val view = bundle.getJSONObject("view")
        validateView(view)
        require(!bundle.has("webFiles")) { "插件须使用公共页面框架" }
        if (view.getString("type") == "page") {
            require(m.getInt("frameworkVersion") == 1) { "不支持此插件基础库版本" }
            val files = bundle.getJSONObject("resources")
            require(files.length() in 1..128) { "插件页面资源数量无效" }
            for (path in files.keys()) {
                require(resourcePath(path)) { "插件资源路径无效" }
                require(path.substringAfterLast('.') in setOf("js", "css", "svg", "json")) {
                    "插件仅包含业务脚本、样式与资源"
                }
                require(files.get(path) is String) { "插件资源必须为文本" }
            }
            require(files.has(view.getString("entry"))) { "缺少插件页面入口" }
            val styles = view.getJSONArray("styles")
            for (i in 0 until styles.length()) require(files.has(styles.getString(i))) { "缺少插件样式" }
        } else require(!bundle.has("resources")) { "页面资源需要 page 视图" }
        require(!bundle.has("code") || bundle.getString("code").length <= 65536) { "插件逻辑超过 64 KiB" }
        require(
            !bundle.has("state") ||
                bundle.get("state") is JSONObject &&
                    bundle.getJSONObject("state").toString().toByteArray().size <= MAX_STATE_BYTES,
        ) {
            "插件 state 必须是最多32KiB的对象"
        }
        return JSONObject(bundle.toString())
    }

    fun resourcePath(path: String): Boolean =
        path.length in 1..160 &&
            path.split('/').all {
                it.matches(Regex("[A-Za-z0-9_.-]+")) &&
                    it !in setOf(".", "..") &&
                    !it.startsWith("__host")
            }

    fun caps(bundle: JSONObject): Set<String> =
        bundle.getJSONObject("manifest").getJSONArray("capabilities").let { a ->
            (0 until a.length()).map { a.getString(it) }.toSet()
        }

    fun linkUrl(raw: String): String {
        val url = raw.toHttpUrlOrNull()
        require(url != null && url.username.isEmpty() && url.password.isEmpty()) { "仅支持公开 HTTP 链接" }
        return url.toUri().toString()
    }

    fun validateResourceId(value: String) {
        require(Regex("[A-Za-z0-9_-]{16,64}").matches(value)) { "资源引用无效" }
    }

    fun validateView(view: JSONObject) {
        if (view.optString("type") == "page") {
            require(
                view.length() == 3 &&
                    resourcePath(view.getString("entry")) &&
                    view.getString("entry").endsWith(".js"),
            ) {
                "插件页面入口无效"
            }
            val styles = view.getJSONArray("styles")
            require(styles.length() <= 16) { "插件样式过多" }
            for (i in 0 until styles.length()) require(
                resourcePath(styles.getString(i)) && styles.getString(i).endsWith(".css"),
            ) {
                "插件样式路径无效"
            }
            return
        }
        require(view.optString("scrollKey").length <= 512) { "阅读位置标识过长" }
        require(
            view.optString("backAction").isEmpty() ||
                Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}").matches(view.getString("backAction")),
        ) {
            "返回动作无效"
        }
        require(view.optString("inputKey").length <= 200) { "输入标识过长" }
        if (view.has("refreshAction")) {
            require(Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}").matches(view.getString("refreshAction")))
            require(view.optInt("refreshSeconds") in 10..120)
        }
        var count = 0
        fun visit(v: JSONObject, depth: Int) {
            require(depth <= 10 && ++count <= 200) { "插件界面过深或超过 200 个组件" }
            when (v.getString("type")) {
                "column",
                "row",
                "card" -> {
                    val children = v.getJSONArray("children")
                    for (i in 0 until children.length()) visit(children.getJSONObject(i), depth + 1)
                }
                "text",
                "title" -> require(v.getString("text").length <= MAX_VIEW_TEXT_CHARS) { "文本过长" }
                "input" -> {
                    require(Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}").matches(v.getString("key"))) {
                        "输入字段名无效"
                    }
                    require(v.optString("value").length <= MAX_VIEW_TEXT_CHARS) { "输入过长" }
                }
                "image" -> {
                    validateResourceId(v.getString("threadId"))
                    require(
                        Regex("[A-Za-z0-9_-]{16,64}")
                            .matches(
                                v.getString(if (v.has("previewId")) "previewId" else "mediaId"),
                            ),
                    )
                    require(v.optString("name").length <= 80)
                }
                "attachments" -> {
                    validateResourceId(v.getString("threadId"))
                    require(Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}").matches(v.getString("key")))
                    require(v.optString("value").length <= 2000)
                }
                "link" -> {
                    require(
                        v.getString("text").length in 1..80 && v.getString("url").length <= 4096,
                    ) {
                        "链接无效"
                    }
                    linkUrl(v.getString("url"))
                }
                "button" -> {
                    require(v.getString("text").length in 1..80) { "按钮文字无效" }
                    require(v.optString("confirm").length <= 500) { "确认提示过长" }
                    require(Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}").matches(v.getString("action"))) {
                        "按钮 action 无效"
                    }
                }
                else -> throw IllegalArgumentException("宿主不支持此界面组件")
            }
        }
        visit(view, 0)
        require(view.toString().toByteArray().size <= 128 * 1024) { "插件界面过大" }
    }
}
