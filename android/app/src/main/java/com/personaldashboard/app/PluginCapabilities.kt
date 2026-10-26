package com.personaldashboard.app

import org.json.JSONArray
import org.json.JSONObject

/** The operation directory is also the runtime admission policy for generic host services. */
object PluginCapabilities {
    data class Spec(val operations: Set<String>, val foreground: Set<String> = emptySet(), val pageOnly: Set<String> = emptySet())
    val services = linkedMapOf(
        "plugin.files" to Spec(setOf("pick", "create", "directory", "openChild", "list", "stat", "read", "write", "remove", "release", "thumbnail", "video"), setOf("pick", "create", "directory")),
        "plugin.transfer" to Spec(setOf("download", "upload", "status", "list", "cancel", "resume")),
        "plugin.share" to Spec(setOf("send", "inbox", "take"), setOf("send")),
        "plugin.notifications.read" to Spec(setOf("status", "request", "sources", "active", "subscribe", "poll", "unsubscribe"), setOf("request")),
        "plugin.connection" to Spec(setOf("list", "configure", "authorize", "status", "request", "remove"), setOf("configure", "authorize")),
        "plugin.system" to Spec(setOf("camera", "calendar", "contact", "location", "pair", "devices", "unpair", "connect", "connectionStatus", "services", "readCharacteristic", "writeCharacteristic", "disconnect"), setOf("camera", "calendar", "contact", "location", "pair", "connect")),
        "plugin.browser" to Spec(setOf("snapshot"), setOf("snapshot"), setOf("snapshot")),
    )
    const val MAX_ARGUMENT_BYTES = 256 * 1024
    fun operation(capability: String, op: String, page: Boolean): Spec {
        val spec = services[capability] ?: error("宿主不支持此通用能力")
        require(op in spec.operations) { "宿主不支持此能力操作" }
        require(page || op !in (spec.foreground + spec.pageOnly)) { "此操作需要前台插件页面" }
        return spec
    }
    fun describe(declared: Set<String>, page: Boolean): JSONObject {
        val result = JSONArray()
        for ((name, spec) in services) result.put(JSONObject().put("capability", name).put("version", 1)
            .put("declared", name in declared).put("operations", JSONArray(spec.operations.map { op -> JSONObject().put("name", op)
                .put("foreground", op in spec.foreground).put("available", name in declared && (page || op !in spec.foreground + spec.pageOnly))
                .put("reason", if (name !in declared) "not_declared" else if (!page && op in spec.foreground + spec.pageOnly) "foreground_required" else "") })))
        for(name in PluginContract.capabilities.filter{it !in services})result.put(JSONObject().put("capability",name).put("version",1).put("declared",name in declared).put("legacyInterface",true)
            .put("surfaces",JSONArray(if(name=="plugin.voice")listOf("page") else listOf("page","action"))))
        return JSONObject().put("apiVersion", 1).put("frameworkVersion", 1).put("operations", result)
            .put("limits", JSONObject().put("argumentsBytes", MAX_ARGUMENT_BYTES).put("fileChunkBytes", 65536).put("fileBytes", 256L*1024*1024))
    }
}
