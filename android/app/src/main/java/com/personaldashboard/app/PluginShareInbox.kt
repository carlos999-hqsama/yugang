package com.personaldashboard.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shares are routed by a real host selection, never by an untrusted pluginId Intent extra. */
object PluginShareInbox {
    private data class Item(val id:String,val text:String,val uris:List<Uri>,val flags:Int,val at:Long)
    private val inbox=linkedMapOf<String,MutableList<Item>>()
    @Suppress("DEPRECATION")
    fun receive(activity:MainActivity,intent:Intent):Boolean {
        if(intent.action !in setOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE))return false
        val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().take(16000)
        val provided=if(intent.action==Intent.ACTION_SEND_MULTIPLE)intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty() else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val uris=(provided+ (intent.clipData?.let{c->(0 until minOf(c.itemCount,6)).map{c.getItemAt(it).uri}}?:emptyList())).distinct().filter{it.scheme=="content"}.take(6)
        if(text.isBlank()&&uris.isEmpty())return true
        val bots=runCatching{BotWorkspaces.get(activity).call(JSONObject().put("op","list")).getJSONArray("bots")}.getOrDefault(JSONArray())
        val targets=mutableListOf<Triple<String,String,String>>()
        for(i in 0 until bots.length()){val b=bots.getJSONObject(i);targets+=Triple("bot",b.getString("id"),b.optString("name","Bot"))}
        val plugins=runCatching{PluginPlatform.store(activity).list().getJSONArray("plugins")}.getOrDefault(JSONArray())
        for(i in 0 until plugins.length()){val p=plugins.getJSONObject(i);if(p.optBoolean("enabled")&&p.getJSONArray("capabilities").let{a->(0 until a.length()).any{a.optString(it)=="plugin.share"}})targets+=Triple("plugin",p.getString("id"),p.optString("title","插件"))}
        if(targets.isEmpty())return true
        AlertDialog.Builder(activity).setTitle("分享到鱼缸").setItems(targets.map{it.third}.toTypedArray()){_,which->
            val target=targets[which]
            if(target.first=="bot"){
                activity.showSharedBot(target.second,text,uris)
            }else{
                synchronized(this){val rows=inbox.getOrPut(target.second){mutableListOf()};rows.removeAll{System.currentTimeMillis()-it.at>600000};if(rows.size>=16)rows.removeAt(0);rows+=Item(UUID.randomUUID().toString(),text,uris,intent.flags,System.currentTimeMillis())}
                activity.openPlugin(target.second)
            }
        }.setNegativeButton("取消",null).show()
        return true
    }
    @Synchronized fun call(activity:android.content.Context,plugin:String,args:JSONObject):JSONObject {
        val rows=inbox[plugin]?:mutableListOf();rows.removeAll{System.currentTimeMillis()-it.at>600000}
        if(args.getString("op")=="inbox")return JSONObject().put("items",JSONArray(rows.map{JSONObject().put("id",it.id).put("text",it.text).put("files",it.uris.size).put("at",it.at)}))
        val item=rows.firstOrNull{it.id==args.getString("id")}?:error("分享内容已过期或已领取")
        val files=PluginFiles(activity,plugin);val received=JSONArray();try{item.uris.forEach{received.put(files.grant(it,item.flags))}}catch(e:Exception){for(i in 0 until received.length())runCatching{files.release(received.getJSONObject(i).getString("handle"))};throw e}
        rows.remove(item);return JSONObject().put("text",item.text).put("files",received).put("source","system_share")
    }
    @Synchronized fun clear(plugin:String){inbox.remove(plugin)}
}
