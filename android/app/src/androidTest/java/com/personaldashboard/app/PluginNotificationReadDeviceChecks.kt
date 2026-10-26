package com.personaldashboard.app

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Own synthetic notification only. Never runs on a real handset. */
internal class PluginNotificationReadDeviceChecks(private val test:Instrumentation) {
    fun run() {
        check(Build.MODEL.contains("sdk")||Build.FINGERPRINT.contains("emulator")){"仅允许专用模拟器"}
        val context=test.targetContext;val id="foundation-notification-check";val component=context.packageName+"/com.personaldashboard.app.PluginNotificationListener"
        val store=PluginPlatform.store(context)
        check(runCatching{store.activeBundle(id)}.isFailure){"已有fixture，不覆盖"}
        fun shell(command:String){test.uiAutomation.executeShellCommand(command).use{java.io.FileInputStream(it.fileDescriptor).readBytes()}}
        val wasEnabled=androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val manager=context.getSystemService(NotificationManager::class.java);val tag="foundation-notification";val notificationId=424242
        try {
            val bundle=JSONObject().put("manifest",JSONObject().put("apiVersion",1).put("id",id).put("title","Notification fixture").put("version","1").put("kind","native").put("capabilities",JSONArray().put("plugin.notifications.read"))).put("view",JSONObject().put("type","text").put("text","fixture"))
            PluginPlatform.manage(context,JSONObject().put("action","install").put("bundle",bundle))
            val revision=store.activeBundle(id).getString("_revision")
            fun call(op:String,extra:JSONObject=JSONObject())=PluginHostServices.call(context,id,revision,"plugin.notifications.read",extra.put("op",op),PluginCancellation())
            fun waitFor(label:String,check:()->Boolean){val until=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<until){if(check())return;Thread.sleep(100)};error(label)}
            shell("cmd notification allow_listener $component")
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            waitFor("Listener did not connect"){call("status").optBoolean("connected")}
            val selected=JSONObject().put("packages",JSONArray().put(context.packageName))
            val subscription=call("subscribe",selected).getString("subscriptionId")
            manager.createNotificationChannel(NotificationChannel(tag,"Synthetic acceptance",NotificationManager.IMPORTANCE_LOW))
            manager.notify(tag,notificationId,Notification.Builder(context,tag).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Synthetic title").setContentText("Synthetic body").build())
            waitFor("Own notification absent"){val a=call("active",JSONObject().put("packages",JSONArray().put(context.packageName))).getJSONArray("notifications");(0 until a.length()).any{a.getJSONObject(it).optString("title")=="Synthetic title"}}
            val received=mutableListOf<JSONObject>()
            waitFor("Posted event absent"){val a=call("poll",JSONObject().put("subscriptionId",subscription)).getJSONArray("events");for(i in 0 until a.length())received+=a.getJSONObject(i);received.any{it.optString("kind")=="posted"&&it.optString("title")=="Synthetic title"}}
            check(received.all{it.getString("package")==context.packageName})
            check(runCatching{PluginNotificationInbox.call(context,"another-plugin",revision,JSONObject().put("op","poll").put("subscriptionId",subscription))}.isFailure){"cross-plugin subscription leaked"}
            manager.cancel(tag,notificationId)
            waitFor("Removal event absent"){val a=call("poll",JSONObject().put("subscriptionId",subscription)).getJSONArray("events");(0 until a.length()).any{a.getJSONObject(it).optString("kind")=="removed"&&a.getJSONObject(it).optString("title")=="Synthetic title"}}
            call("unsubscribe",JSONObject().put("subscriptionId",subscription))
            check(runCatching{call("poll",JSONObject().put("subscriptionId",subscription))}.isFailure)
            shell("cmd notification disallow_listener $component")
            waitFor("Revoked listener stayed enabled"){!call("status").optBoolean("enabled")}
            check(runCatching{call("active",selected)}.isFailure)
            test.sendStatus(0,Bundle().apply{putString("stream","PASS: Real listener posted/removed events, explicit source filter, ownership, unsubscribe and permission revocation\n")})
        } finally {
            manager.cancel(tag,notificationId);manager.deleteNotificationChannel(tag)
            if(wasEnabled)shell("cmd notification allow_listener $component") else shell("cmd notification disallow_listener $component")
            PluginPlatform.manage(context,JSONObject().put("action","remove").put("id",id))
        }
    }
}
