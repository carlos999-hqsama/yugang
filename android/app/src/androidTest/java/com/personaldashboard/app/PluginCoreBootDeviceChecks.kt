package com.personaldashboard.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Boots the real core page through its own production JS bridge, without a fake JS native module. */
internal class PluginCoreBootDeviceChecks(private val test:Instrumentation) {
    fun run() {
        check(Build.MODEL.contains("sdk")||Build.FINGERPRINT.contains("emulator")){"仅允许专用模拟器"}
        val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val web=MainActivity::class.java.getDeclaredField("web").apply{isAccessible=true}.get(activity) as WebView
        fun probe():JSONObject {
            val latch=CountDownLatch(1);var result="null"
            test.runOnMainSync{web.evaluateJavascript("""JSON.stringify({ready:document.readyState,boot:!!document.getElementById('pd-boot-status'),input:!!document.querySelector('#scr-harness .input textarea'),harness:(()=>{const e=document.getElementById('scr-harness');return !!e&&e.getBoundingClientRect().width>0&&getComputedStyle(e).display!=='none'})(),bootVisible:(()=>{const e=document.getElementById('pd-boot-status');return !!e&&e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0})(),bootRole:document.getElementById('pd-boot-status')?.getAttribute('role'),settings:(()=>{const e=document.getElementById('scr-settings');return !!e&&e.getBoundingClientRect().width>0&&getComputedStyle(e).display!=='none'})()})"""){result=it;latch.countDown()}}
            check(latch.await(5,TimeUnit.SECONDS)){"核心WebView没有回调"}
            return (JSONTokener(result).nextValue() as? String)?.let{JSONObject(it)}?:JSONObject()
        }
        fun await(label:String,condition:(JSONObject)->Boolean){val end=System.currentTimeMillis()+20000;var last=JSONObject();while(System.currentTimeMillis()<end){last=probe();if(condition(last))return;Thread.sleep(100)};error("$label: $last")}
        test.runOnMainSync{activity.openPlugin("harness")}
        await("核心对话页未完成真实启动"){it.optBoolean("harness")&&it.optBoolean("input")&&!it.optBoolean("boot")}
        test.sendStatus(0,Bundle().apply{putString("stream","PASS: Actual core startup through PDNative getConfig renders conversation input without boot error\n")})
        test.runOnMainSync{activity.openPlugin("settings")}
        await("设置页未显示"){it.optBoolean("settings")&&!it.optBoolean("boot")}
        test.runOnMainSync{activity.openPlugin("harness")}
        await("返回对话页失败"){it.optBoolean("harness")&&it.optBoolean("input")&&!it.optBoolean("boot")}
        test.sendStatus(0,Bundle().apply{putString("stream","PASS: Actual native navigation settings -> conversation remains visible and usable\n")})
        val original=MainActivity::class.java.getDeclaredField("bridge").apply{isAccessible=true}.get(activity) as Bridge
        val failOnce=java.util.concurrent.atomic.AtomicBoolean(true)
        val failing=object {
            @android.webkit.JavascriptInterface fun call(method:String,id:String,args:String?) {
                if(method=="getConfig"&&failOnce.compareAndSet(true,false))activity.runOnUiThread{
                    val payload=JSONObject().put("error","synthetic startup failure").toString()
                    web.evaluateJavascript("window.__pd_cb(${JSONObject.quote(id)},${JSONObject.quote(payload)})",null)
                } else original.call(method,id,args)
            }
        }
        try{
            test.runOnMainSync{web.removeJavascriptInterface("PDNative");web.addJavascriptInterface(failing,"PDNative");web.reload()}
            await("启动失败提示不可见"){it.optBoolean("bootVisible")&&it.optString("bootRole")=="alert"}
            test.runOnMainSync{web.evaluateJavascript("document.querySelector('[data-boot-retry]').click();true",null)}
            await("启动重试未恢复对话页"){it.optBoolean("harness")&&it.optBoolean("input")&&!it.optBoolean("boot")}
            test.sendStatus(0,Bundle().apply{putString("stream","PASS: Synthetic startup failure stays visible; real retry recovers the actual conversation page\n")})
        }finally{test.runOnMainSync{web.removeJavascriptInterface("PDNative");web.addJavascriptInterface(original,"PDNative");web.reload()}}

    }
}
