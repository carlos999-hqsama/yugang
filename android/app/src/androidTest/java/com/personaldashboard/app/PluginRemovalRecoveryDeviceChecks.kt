package com.personaldashboard.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Hold an actual IDB connection to reproduce cleanup failure and independent-plugin progress. */
internal class PluginRemovalRecoveryDeviceChecks(private val runner:Instrumentation) {
    private val context get()=runner.targetContext
    private fun waitFor(message:String,condition:()->Boolean){val end=System.currentTimeMillis()+15_000;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(80)};check(condition()){message}}
    private fun js(web:WebView,code:String):String{val latch=CountDownLatch(1);var value="";runner.runOnMainSync{web.evaluateJavascript(code){value=it;latch.countDown()}};check(latch.await(3,TimeUnit.SECONDS));return value}
    fun run(){
        check(Build.MODEL.contains("sdk")||Build.FINGERPRINT.contains("emulator")){"仅允许专用模拟器"}
        val app=runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
        val suffix=java.util.UUID.randomUUID().toString().take(8);val a="removal-a-$suffix";val b="removal-b-$suffix";val s=PluginPlatform.store(context)
        fun bundle(id:String)=JSONObject().put("manifest",JSONObject().put("apiVersion",1).put("id",id).put("title","Removal fixture").put("version","1").put("kind","native").put("capabilities",JSONArray().put("plugin.background")))
            .put("view",JSONObject().put("type","text").put("text","Synthetic fixture"))
        fun manage(action:String,id:String)=PluginPlatform.manage(context,JSONObject().put("action",action).put("id",id).apply{if(action=="install")put("bundle",bundle(id))})
        val views=mutableListOf<WebView>()
        fun probe(hold:Boolean):WebView {lateinit var web:WebView;runner.runOnMainSync{web=WebView(app).apply{settings.javaScriptEnabled=true;settings.domStorageEnabled=true};views+=web
            val seed=if(hold)"localStorage.setItem('draft','OLD');const r=indexedDB.open('held-draft',1);r.onupgradeneeded=()=>r.result.createObjectStore('items');await new Promise((resolve,reject)=>{r.onsuccess=()=>{window.held=r.result;window.held.onversionchange=()=>window.blocked=true;resolve()};r.onerror=reject});"else""
            web.loadDataWithBaseURL("https://plugin-$a.pd.local/","<script>(async()=>{$seed window.fixtureReady=true})()</script>","text/html","UTF-8",null)};waitFor("Storage probe not ready"){js(web,"window.fixtureReady")=="true"};return web}
        var removal:Thread?=null
        try{
            manage("install",a);manage("install",b);val held=probe(true)
            var result:JSONObject?=null;var failure:Throwable?=null
            removal=Thread{try{result=manage("remove",a)}catch(e:Throwable){failure=e}}.apply{start()}
            waitFor("Host did not attempt the blocked database cleanup"){js(held,"window.blocked")=="true"}
            check(a in s.pendingRemovals()&&!s.installed(a))
            val started=System.nanoTime();val active=s.activeBundle(b)
            PluginPlatform.pageCapability(context,Config(context),b,active.getString("_revision"),"plugin.background",JSONObject().put("op","status"),PluginCancellation())
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1500){"Cleanup blocked another plugin's capability"}
            removal.join(12_000);check(!removal.isAlive){"Cleanup did not reach its deadline"};check(failure==null){"Removal lost committed outcome: ${failure?.javaClass?.simpleName}"}
            check(result!!.getBoolean("removed")&&result!!.getBoolean("cleanupPending")&&result!!.optString("warning").isNotEmpty())
            check(a in s.pendingRemovals())
            js(held,"window.held.close();true");runner.runOnMainSync{held.destroy()};views.remove(held)
            PluginPlatform.recoverPendingRemovals(context);check(a !in s.pendingRemovals())
            val clean=probe(false);check(js(clean,"localStorage.getItem('draft')")=="null"){"Uninstall retained a draft before reinstall"}
            js(clean,"window.databaseCount=null;indexedDB.databases().then(v=>window.databaseCount=v.length)")
            waitFor("Database cleanup did not finish"){js(clean,"window.databaseCount")=="0"}
            runner.runOnMainSync{clean.destroy()};views.remove(clean)
            manage("install",a);val fresh=probe(false);js(fresh,"localStorage.setItem('draft','NEW');true")
            PluginPlatform.recoverPendingRemovals(context);check(js(fresh,"localStorage.getItem('draft')")=="\"NEW\""){"Late retry erased reinstalled data"}
            runner.sendStatus(0,Bundle().apply{putString("stream","PASS: Blocked IDB cleanup does not block another plugin; committed removal reports pending cleanup; retry clears storage before reinstall and cannot erase new data\n")})
        }finally{
            runner.runOnMainSync{views.forEach{runCatching{it.evaluateJavascript("window.held?.close()",null);it.destroy()}}}
            removal?.join(12_000);runCatching{manage("remove",a)};runCatching{manage("remove",b)}
        }
    }
}
