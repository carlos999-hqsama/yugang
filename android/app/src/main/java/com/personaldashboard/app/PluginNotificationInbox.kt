package com.personaldashboard.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import java.lang.ref.WeakReference
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class PluginNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { PluginNotificationInbox.connected(this) }
    override fun onListenerDisconnected() { PluginNotificationInbox.disconnected(this) }
    override fun onDestroy() { PluginNotificationInbox.disconnected(this);super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { runCatching { PluginNotificationInbox.event(sbn,"posted") } }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { runCatching { PluginNotificationInbox.event(sbn,"removed") } }
}

/** Bounded live notification feed, kept only in host memory. Subscription is not system permission. */
object PluginNotificationInbox {
    private var service = WeakReference<PluginNotificationListener>(null)
    private var sequence = 0L
    private val events = ArrayDeque<JSONObject>()
    private data class Subscription(val plugin: String,val revision: String,val packages: Set<String>,var cursor: Long,var touched: Long)
    private val subscriptions = linkedMapOf<String,Subscription>()
    @Synchronized fun connected(value: PluginNotificationListener) { service=WeakReference(value) }
    @Synchronized fun disconnected(value: PluginNotificationListener) { if(service.get()===value){service.clear();events.clear();subscriptions.clear()} }
    private fun packages(args: JSONObject): Set<String> {
        val a=args.optJSONArray("packages")?:error("请选择要读取通知的 App")
        require(a.length() in 1..32) { "请选择 1 至 32 个 App" }
        return (0 until a.length()).map { a.getString(it).also { p -> require(Regex("[A-Za-z][A-Za-z0-9_.]{1,199}").matches(p)) { "App 标识无效" } } }.toSet()
    }
    @Synchronized fun event(sbn: StatusBarNotification, kind: String) {
        subscriptions.entries.removeAll { android.os.SystemClock.elapsedRealtime()-it.value.touched>600000 }
        if(subscriptions.isEmpty()){events.clear();return}
        if(subscriptions.values.none { sbn.packageName in it.packages })return
        val row=encode(sbn).put("kind",kind)
        if(kind=="posted") events.lastOrNull()?.takeIf { it.optString("key")==row.optString("key") && it.optString("text")==row.optString("text") && it.optString("title")==row.optString("title") }?.let { return }
        row.put("sequence",++sequence);events.addLast(row);while(events.size>256)events.removeFirst()
    }
    private fun encode(sbn: StatusBarNotification): JSONObject {
        val n=sbn.notification;val e=n.extras
        return JSONObject().put("key",sbn.key.take(512)).put("package",sbn.packageName).put("postedAt",sbn.postTime)
            .put("title",e?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(512))
            .put("text",(e?.getCharSequence(Notification.EXTRA_BIG_TEXT)?:e?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(4096))
            .put("group",sbn.groupKey?.take(512)).put("groupSummary",n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
    }
    @Synchronized fun call(context: Context, plugin: String, revision: String, args: JSONObject): JSONObject {
        val enabled=context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        val live=service.get()
        if(!enabled){events.clear();subscriptions.clear()}
        val op=args.optString("op","status")
        if(op=="status")return JSONObject().put("enabled",enabled).put("connected",enabled&&live!=null).put("reason",if(!enabled)"permission_required" else if(live==null)"service_disconnected" else "")
        check(enabled&&live!=null) { "请先开启系统通知使用权，等待监听服务连接" }
        subscriptions.entries.removeAll { android.os.SystemClock.elapsedRealtime()-it.value.touched>600000 }
        when(op){
            "sources" -> return JSONObject().put("packages",JSONArray(live.activeNotifications.orEmpty().map{it.packageName}.distinct().sorted()))
            "active" -> { val allowed=packages(args);return JSONObject().put("notifications",JSONArray(live.activeNotifications.orEmpty().filter { it.packageName in allowed }.take(128).map(::encode))).put("source","active_notifications") }
            "subscribe" -> { require(subscriptions.size<32){"通知订阅过多"};val id=UUID.randomUUID().toString();subscriptions[id]=Subscription(plugin,revision,packages(args),sequence,android.os.SystemClock.elapsedRealtime());return JSONObject().put("subscriptionId",id).put("cursor",sequence) }
            "poll","unsubscribe" -> {
                val id=args.getString("subscriptionId");val s=subscriptions[id]?:error("通知订阅已结束")
                check(s.plugin==plugin&&s.revision==revision){"通知订阅不属于当前插件版本"}
                if(op=="unsubscribe"){subscriptions.remove(id);return JSONObject().put("removed",true)}
                s.touched=android.os.SystemClock.elapsedRealtime();val gap=events.firstOrNull()?.optLong("sequence")?.let { s.cursor<it-1 }?:false
                val rows=events.filter { it.optLong("sequence")>s.cursor&&it.optString("package") in s.packages }.take(64)
                s.cursor=if(rows.size==64)rows.last().getLong("sequence") else sequence
                return JSONObject().put("events",JSONArray(rows.map { JSONObject(it.toString()) })).put("cursor",s.cursor).put("gap",gap)
            }
            else -> error("不支持的通知读取操作")
        }
    }
    @Synchronized fun release(plugin: String) { subscriptions.entries.removeAll { it.value.plugin==plugin };if(subscriptions.isEmpty())events.clear() }
    fun settings(context: Context) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
}
