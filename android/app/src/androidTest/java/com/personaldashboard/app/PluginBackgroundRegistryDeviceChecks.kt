package com.personaldashboard.app

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Unreadable registry data must not be interpreted as proof that a plugin disappeared. */
internal class PluginBackgroundRegistryDeviceChecks(private val runner: Instrumentation) {
    fun run() {
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator")) { "仅允许专用模拟器" }
        val context = runner.targetContext
        val id = "background-registry-" + java.util.UUID.randomUUID().toString().take(8)
        val store = PluginPlatform.store(context)
        val bundle = JSONObject().put("manifest", JSONObject().put("apiVersion", 1).put("id", id).put("title", "Registry fixture").put("version", "1").put("kind", "native").put("capabilities", JSONArray().put("plugin.background")))
            .put("view", JSONObject().put("type", "text").put("text", "Synthetic fixture"))
        PluginPlatform.manage(context, JSONObject().put("action", "install").put("bundle", bundle))
        try {
            val overflow = runCatching { PluginBackground.schedule(context, id, JSONObject().put("action", "refresh").put("intervalMinutes", 307445734561841L)) }.exceptionOrNull()
            check(overflow is IllegalArgumentException && PluginBackgroundStore(context).get(id) == null) { "Overflowing minute input was accepted or persisted" }
            PluginBackground.schedule(context, id, JSONObject().put("action", "refresh").put("intervalMinutes", 15))
            val background = PluginBackgroundStore(context)
            val generation = background.get(id)!!.generation
            val registry = PluginStore::class.java.getDeclaredField("registry").apply { isAccessible = true }.get(store) as JSONObject
            synchronized(PluginBackground) {
                val original = registry.getJSONObject(id)
                try {
                    val unreadable = JSONObject(original.toString()).apply { remove("enabled") }
                    registry.put(id, unreadable)
                    val result = PluginBackground.reconcile(context)
                    check(result.getInt("errors") > 0 && result.optString("error").isNotEmpty())
                    check(background.get(id)?.generation == generation) { "Unknown registry erased the persisted schedule" }
                    registry.put(id, JSONObject(original.toString()).apply { remove("revision") })
                    val brokenBundle = PluginBackground.reconcile(context)
                    check(brokenBundle.getInt("errors") > 0)
                    check(background.get(id)?.generation == generation) { "Unreadable active bundle erased the persisted schedule" }
                } finally { registry.put(id, original) }
                store.enable(id, false)
                check(PluginBackground.reconcile(context).getInt("cancelled") > 0)
                check(background.get(id) == null) { "Confirmed disabled plugin kept its schedule" }
            }
            runner.sendStatus(0, Bundle().apply { putString("stream", "PASS: Unknown registry and unreadable bundle retain the exact schedule generation; confirmed disable removes it\n") })
        } finally { PluginPlatform.manage(context, JSONObject().put("action", "remove").put("id", id)) }
    }
}
