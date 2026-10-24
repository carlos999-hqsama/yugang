package com.personaldashboard.app

import android.app.Instrumentation
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Actual Android identities must keep notifications and tap intents independent. */
internal class PluginNotificationCollisionDeviceChecks(private val runner: Instrumentation) {
    fun run() {
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator")) { "仅允许专用模拟器" }
        val context = runner.targetContext
        val ids = listOf("aaa", "akuo")
        val store = PluginPlatform.store(context)
        check(ids.none { store.installed(it) }) { "Synthetic collision IDs already installed" }
        check(PluginBackgroundPolicy.jobId(ids[0]) == PluginBackgroundPolicy.jobId(ids[1]))
        val manager = context.getSystemService(NotificationManager::class.java)
        fun remove(id: String) = PluginPlatform.manage(context, JSONObject().put("action", "remove").put("id", id))
        try {
            for (id in ids) {
                val bundle = JSONObject().put("manifest", JSONObject().put("apiVersion", 1).put("id", id).put("title", "Collision fixture").put("version", "1").put("kind", "native").put("capabilities", JSONArray().put("plugin.notification")))
                    .put("view", JSONObject().put("type", "text").put("text", "Synthetic fixture"))
                PluginPlatform.manage(context, JSONObject().put("action", "install").put("bundle", bundle))
                check(PluginBackground.notify(context, id, JSONObject().put("title", "Synthetic collision check").put("body", id)).getBoolean("shown"))
            }
            fun active() = manager.activeNotifications.filter { it.tag in ids }
            check(active().map { it.tag }.toSet() == ids.toSet()) { "Colliding notification IDs replaced each other" }
            val intents = ids.map { id -> PendingIntent.getActivity(context, PluginBackgroundPolicy.jobId(id), PluginBackground.openIntent(context, id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) }
            check(intents.all { it != null } && intents[0] != intents[1]) { "Notification tap intents alias each other" }
            check(PluginBackground.openIntent(context, ids[0]).filterEquals(PluginBackground.openIntent(context, ids[1])).not())
            remove(ids[0])
            check(active().map { it.tag } == listOf(ids[1])) { "Removing one plugin cleared another notification" }
            check(PendingIntent.getActivity(context, PluginBackgroundPolicy.jobId(ids[0]), PluginBackground.openIntent(context, ids[0]), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) == null)
            check(PendingIntent.getActivity(context, PluginBackgroundPolicy.jobId(ids[1]), PluginBackground.openIntent(context, ids[1]), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) == intents[1])
            runner.sendStatus(0, Bundle().apply { putString("stream", "PASS: Actual colliding Android notification IDs coexist; tap intents are distinct; removing A preserves B notification and tap\n") })
        } finally { ids.forEach { runCatching { remove(it) } } }
    }
}
