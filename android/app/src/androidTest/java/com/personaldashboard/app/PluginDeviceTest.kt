package com.personaldashboard.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs only on the dedicated emulator. Uses synthetic plugins and a local HTTP fixture. */
class PluginDeviceTest : Instrumentation() {
    private var scenario: String? = null
    override fun onCreate(arguments: Bundle?) { scenario=arguments?.getString("scenario");super.onCreate(arguments);start() }
    override fun onStart() {
        if(scenario=="coreBoot") {
            val result=Bundle()
            try{PluginCoreBootDeviceChecks(this).run();result.putString("stream","PASS: coreBoot\n");finish(Activity.RESULT_OK,result)}
            catch(e:Throwable){result.putString("stream","FAIL: coreBoot: ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}\n");finish(Activity.RESULT_CANCELED,result)}
            return
        }
        if(scenario=="foundation") {
            val result=Bundle()
            try{PluginFoundationDeviceChecks(this).run();result.putString("stream","PASS: foundation\n");finish(Activity.RESULT_OK,result)}
            catch(e:Throwable){result.putString("stream","FAIL: foundation: ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}\n");finish(Activity.RESULT_CANCELED,result)}
            return
        }
        if(scenario=="notificationRead") {
            val result=Bundle()
            try{PluginNotificationReadDeviceChecks(this).run();result.putString("stream","PASS: notificationRead\n");finish(Activity.RESULT_OK,result)}
            catch(e:Throwable){result.putString("stream","FAIL: notificationRead: ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}\n");finish(Activity.RESULT_CANCELED,result)}
            return
        }
        if(scenario=="previewIdle"){ verifyPreviewIdle();return }
        if(scenario in setOf("a11yPlain","pluginLinkActivation","modalRendererRecovery","pageCache","mediaOrientation","previewLifecycle","previewStreams","originStorage","tarBoundaries","runtimeExtract","removalRecovery","previewStorage","notificationCollision","backgroundRegistry","developerContract","secretIntegrity","insets","backgroundPreemption","notificationDenied","notificationGranted","runtimeDependencies","browserBoundary")) {
            val result=Bundle()
            try {
                PluginRegressionDeviceChecks(this).run(scenario!!)
                result.putString("stream","PASS: $scenario\n");finish(Activity.RESULT_OK,result)
            } catch(e:Throwable) {
                result.putString("stream","FAIL: $scenario: ${e.javaClass.name}: ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}\n")
                finish(Activity.RESULT_CANCELED,result)
            }
            return
        }
        if(scenario in setOf("backgroundSeed","backgroundStatus","backgroundCleanup")){ verifyBackgroundLifecycle();return }
        if(scenario!=null){finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream","FAIL: Unknown scenario: $scenario\n")});return}
        val result = Bundle()
        val id = "plugin-device-"+java.util.UUID.randomUUID().toString().take(8)
        val evidence = JSONArray()
        var server: ServerSocket? = null
        var originalRuntimeMode: String? = null
        fun passed(name: String) { evidence.put(name); sendStatus(0,Bundle().apply { putString("stream","PASS: $name\n") }) }
        try {
            check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator")) { "仅允许专用模拟器" }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val fixture = ServerSocket(0)
            server = fixture
            val fixtureThread = Thread {
                while (!fixture.isClosed) try {
                    fixture.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        while (reader.readLine()?.takeIf { it.isNotEmpty() } != null) {}
                        val image = request.contains("/image")
                        val bytes = if (image) {
                            val bitmap = android.graphics.Bitmap.createBitmap(48,48,android.graphics.Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(0xff223344.toInt())
                            val out = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); bitmap.recycle(); out.toByteArray()
                        } else "{\"value\":\"LIVE_NETWORK_OK\"}".toByteArray()
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: ${if(image) "image/png" else "application/json"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(headers.toByteArray()+bytes)
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon=true; start() }
            val code = """
import {definePage} from '/framework/v1/page.js';
import {native} from '/framework/v1/js/native.js';
export default definePage({mount(ctx){
 ctx.root.innerHTML='<div class="check-page"><h1>Plugin verification</h1><div class="check-columns"><section><label>Draft<input id="draft" value="FOREGROUND_DRAFT"></label><button id="count">Count 0</button><p id="model-status"></p><button id="model">Model status</button><p id="network">Network pending</p><button id="load">Load network</button><button id="public">Public HTTPS</button><p id="public-result"></p><img id="public-image" width="48" height="48"><img id="image" width="48" height="48"><div id="scroller" style="height:100px;overflow:auto"><div style="height:700px">Scrollable content</div></div></section><section><h2>Independent preview</h2><p id="viewport"></p><p>Black and white layout</p></section></div></div>';
 const root=ctx.root;let n=0;
 root.querySelector('#viewport').textContent='Width '+innerWidth;
 root.querySelector('#model').onclick=async()=>{try{const value=await native.model({op:'status'});root.querySelector('#model-status').textContent=JSON.stringify({configured:value.configured,allowedModels:value.allowedModels});}catch(e){root.querySelector('#model-status').textContent='ERROR '+e.message}};
 root.querySelector('#count').onclick=()=>root.querySelector('#count').textContent='Count '+(++n);
 root.querySelector('#public').onclick=async()=>{try{await fetch('https://example.com',{mode:'no-cors'});root.querySelector('#public-result').textContent='DIRECT_HTTPS_OK';root.querySelector('#public-image').src='https://httpbin.org/image/png';}catch(e){root.querySelector('#public-result').textContent='ERROR '+e.message;}};
 root.querySelector('#load').onclick=async()=>{try{const v=await native.fetchJson('http://127.0.0.1:${fixture.localPort}/data');root.querySelector('#network').textContent=v.value;root.querySelector('#image').src=await native.fetchImage('http://127.0.0.1:${fixture.localPort}/image');}catch(e){root.querySelector('#network').textContent='ERROR '+e.message;}};
}});
"""
            val bundle = JSONObject().put("manifest", JSONObject().put("apiVersion",1).put("frameworkVersion",1).put("id",id).put("title","Synthetic check").put("version","1.0").put("kind","native").put("capabilities",JSONArray().put("plugin.model").put("plugin.background").put("plugin.notification").put("plugin.state")))
                .put("view",JSONObject().put("type","page").put("entry","main.js").put("styles",JSONArray().put("style.css")))
                .put("resources",JSONObject().put("main.js",code).put("style.css",".check-page{padding:16px}.check-columns{display:grid;gap:16px;grid-template-columns:1fr}section{padding:16px;border:1px solid #ddd;border-radius:14px}input,button{display:block;margin:12px 0;padding:12px;border:1px solid #ddd;border-radius:10px}h1{font-size:20px}@media(min-width:600px){.check-columns{grid-template-columns:1fr 1fr}}"))
                .put("code","""export async function run({action,state}){return {state:{runs:(state.runs||0)+1},view:{type:"text",text:"BACKGROUND_EXECUTED"}}}""")
            PluginPlatform.manage(targetContext,JSONObject().put("action","install").put("bundle",bundle))
            runOnMainSync { activity.openPlugin(id) }
            val field=MainActivity::class.java.getDeclaredField("pluginShell").apply {isAccessible=true}
            val shell=field.get(activity) as PluginShell
            fun allViews(view:android.view.View):List<android.view.View> = listOf(view)+if(view is android.view.ViewGroup)(0 until view.childCount).flatMap {allViews(view.getChildAt(it))} else emptyList()
            lateinit var foreground:WebView
            val deadline=System.currentTimeMillis()+20000
            while(System.currentTimeMillis()<deadline){
                var found:WebView?=null
                runOnMainSync { found=allViews(activity.window.decorView).filterIsInstance<WebView>().firstOrNull {it.url?.contains("plugin-$id.pd.local")==true} }
                if(found!=null){foreground=found!!;break};Thread.sleep(100)
            }
            fun js(source:String):String {
                val latch=CountDownLatch(1); var answer=""
                runOnMainSync { foreground.evaluateJavascript(source){answer=it;latch.countDown()} }
                check(latch.await(5,TimeUnit.SECONDS)); return answer
            }
            fun waitFor(label:String,test:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(test())return;Thread.sleep(100)};error(label)}
            waitFor("foreground boot") { js("!!document.querySelector('#draft')")=="true" }
            js("document.querySelector('#draft').focus();document.querySelector('#draft').value='KEEP_DRAFT';document.querySelector('#count').click();true")
            var focused:android.view.View?=null
            runOnMainSync {focused=activity.currentFocus}
            passed("Foreground page ready, draft and counter set")
            fun operation(op:String,args:JSONObject=JSONObject()):JSONObject {
                val receipt=PluginPreviewManager.handle(args.put("op",op))
                check(listOf("session","plugin","state","ok","result").all { receipt.has(it) }) { receipt.toString() }
                check(receipt.getString("state") in setOf("opening","ready","closed"))
                return receipt
            }
            fun payload(op:String,args:JSONObject=JSONObject()):JSONObject=operation(op,args).getJSONObject("result")
            for(width in listOf(400,796)){
                val opened=payload("open",JSONObject().put("id",id).put("width",width).put("height",720))
                check(opened.optBoolean("loaded")){opened.toString()}
                var shot=payload("snapshot")
                check(shot.optBoolean("ok")){shot.toString()}
                check(shot.getJSONObject("viewport").getInt("width")==width){"CSS viewport mismatch: ${shot.optJSONObject("viewport")}"}
                check(shot.getString("capture")=="ok"){shot.optString("captureReason")}
                val png=shot.getJSONObject("png").getString("base64")
                File(targetContext.cacheDir,"plugin-preview-$width.png").writeBytes(android.util.Base64.decode(png,android.util.Base64.DEFAULT))
                check(payload("input",JSONObject().put("selector","#draft").put("text","PREVIEW_ONLY")).optBoolean("ok"))
                check(payload("click",JSONObject().put("selector","#count")).optBoolean("ok"))
                val scroll=payload("scroll",JSONObject().put("selector","#scroller").put("y",300))
                check(kotlin.math.abs(scroll.getDouble("scrollTop")-300)<2){scroll.toString()}
                check(!payload("scroll",JSONObject().put("selector","#missing-scroller").put("y",10)).getBoolean("ok"))
                check(payload("click",JSONObject().put("selector","#load")).optBoolean("ok"))
                waitFor("preview network") { payload("snapshot").optString("text").contains("LIVE_NETWORK_OK") }
                shot=payload("snapshot")
                check(shot.getJSONArray("errors").length()==0){shot.getJSONArray("errors").toString()}
                check(js("document.querySelector('#draft').value")=="\"KEEP_DRAFT\"")
                check(js("document.querySelector('#count').textContent")=="\"Count 1\"")
                runOnMainSync {check(activity.currentFocus===focused){"Preview stole foreground focus"}}
                passed("$width CSS px: actual PNG, input, click, scroll, live network, foreground unchanged")
                check(payload("close").getBoolean("closed"))
            }
            val installedRevision=PluginPlatform.store(targetContext).activeBundle(id).getString("_revision")
            val draft=JSONObject(bundle.toString())
            draft.getJSONObject("manifest").put("id","draft-only-check")
            val draftExtra="""
 const inline=document.createElement('p');inline.id='inline-check';inline.innerHTML='Hello <b>world</b>';ctx.root.append(inline);
 const long=document.createElement('p');long.id='long-text';long.textContent='字'.repeat(35000);ctx.root.append(long);
 const controls=document.createElement('div');controls.innerHTML='<button id="store-set">Set storage</button><button id="store-read">Read storage</button><p id="storage-result"></p>';ctx.root.append(controls);
 const database=()=>new Promise((resolve,reject)=>{const r=indexedDB.open('draft-check',1);r.onupgradeneeded=()=>r.result.createObjectStore('items');r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)});
 controls.querySelector('#store-set').onclick=async()=>{localStorage.setItem('draft-check','DRAFT');sessionStorage.setItem('draft-check','DRAFT');const db=await database();const tx=db.transaction('items','readwrite');tx.objectStore('items').put('DRAFT','value');tx.oncomplete=()=>{db.close();controls.querySelector('#storage-result').textContent='STORAGE_SET'}};
 controls.querySelector('#store-read').onclick=async()=>{const db=await database();const tx=db.transaction('items');const r=tx.objectStore('items').get('value');r.onsuccess=()=>{db.close();controls.querySelector('#storage-result').textContent=[localStorage.getItem('draft-check')||'EMPTY',sessionStorage.getItem('draft-check')||'EMPTY',r.result||'EMPTY'].join('|')}};
 const root=ctx.root;
"""
            draft.getJSONObject("resources").put("main.js", code.replace(" const root=ctx.root;",draftExtra))
            check(payload("open",JSONObject().put("packageJson",draft.toString()).put("width",796).put("height",720)).getBoolean("loaded"))
            check(operation("status").getJSONObject("result").getJSONObject("preview").getString("plugin")=="draft-only-check")
            check(payload("snapshot",JSONObject().put("selector","#inline-check")).getString("text")=="Hello world")
            val firstPage=payload("snapshot",JSONObject().put("selector","#long-text").put("offset",0).put("limit",20000))
            check(firstPage.getInt("textLength")==35000 && firstPage.getInt("nextOffset")==20000)
            val lastPage=payload("snapshot",JSONObject().put("selector","#long-text").put("offset",25000).put("limit",10000))
            check(lastPage.getString("text").length==10000 && lastPage.isNull("nextOffset"))
            js("localStorage.setItem('foreground-check','KEEP');true")
            check(payload("click",JSONObject().put("selector","#store-set")).getBoolean("ok"))
            waitFor("draft storage seeded") { payload("snapshot",JSONObject().put("selector","#storage-result")).getString("text")=="STORAGE_SET" }
            check(payload("clearStorage").optBoolean("ok"))
            check(payload("click",JSONObject().put("selector","#store-read")).getBoolean("ok"))
            waitFor("draft storage cleared") { payload("snapshot",JSONObject().put("selector","#storage-result")).getString("text")=="EMPTY|EMPTY|EMPTY" }
            check(js("localStorage.getItem('foreground-check')")=="\"KEEP\"")
            check(payload("reload").getBoolean("reloaded"))
            check(PluginPlatform.store(targetContext).activeBundle(id).getString("_revision")==installedRevision)
            check(runCatching { PluginPlatform.store(targetContext).read("draft-only-check") }.isFailure)
            check(js("document.querySelector('#draft').value")=="\"KEEP_DRAFT\"")
            operation("close")
            val closed=operation("status")
            check(closed.getString("state")=="closed" && closed.getJSONObject("result").getString("reason").isNotEmpty())
            passed("Uninstalled draft: real preview, text pages past 20k, reload, scoped clear, unchanged registry and foreground")
            for(mode in listOf("offline","slow")){
                payload("open",JSONObject().put("packageJson",draft.toString()).put("network",JSONObject().put("mode",mode).also{if(mode=="slow")it.put("delayMs",250)}))
                val started=System.currentTimeMillis()
                check(payload("click",JSONObject().put("selector","#load")).optBoolean("ok"))
                waitFor("network profile $mode") { payload("snapshot").getString("text").let { if(mode=="offline")it.contains("ERROR") else it.contains("LIVE_NETWORK_OK") } }
                if(mode=="slow")check(System.currentTimeMillis()-started>=250)
                operation("close")
            }
            passed("Draft native network: offline error and bounded slow mode, no installation")
            // The same native HTTP bridge is tested outside the preview as well.
            js("document.querySelector('#load').click();true")
            waitFor("foreground network") {js("document.querySelector('#network').textContent").contains("LIVE_NETWORK_OK")}
            waitFor("remote image") {js("document.querySelector('#image').naturalWidth")=="48"}
            passed("Foreground live HTTP JSON and image through real native bridge")
            js("document.querySelector('#public').click();true")
            waitFor("public HTTPS request") {js("document.querySelector('#public-result').textContent").contains("DIRECT_HTTPS_OK")}
            waitFor("public HTTPS image") {js("document.querySelector('#public-image').naturalWidth").toIntOrNull()?.let{it>0}==true}
            passed("Direct public HTTPS fetch and image load without native workaround")
            val socketName=HostRuntimeControl.start(targetContext)
            fun channel(request:JSONObject,abort:Boolean=false):JSONObject {
                val socket=android.net.LocalSocket()
                socket.connect(android.net.LocalSocketAddress(socketName,android.net.LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout=30000
                socket.outputStream.write((JSONObject().put("action","plugin_preview").put("request",request).toString()+"\n").toByteArray())
                socket.outputStream.flush()
                if(abort){socket.close();return JSONObject()}
                return socket.use {JSONObject(it.inputStream.bufferedReader().readLine()).getJSONObject("value")}
            }
            check(channel(JSONObject().put("op","open").put("id",id)).getJSONObject("result").getBoolean("loaded"))
            Thread.sleep(1200)
            check(operation("status").getString("state")=="ready"){"normal IPC close destroyed persistent preview"}
            channel(JSONObject().put("op","close"))
            channel(JSONObject().put("op","open").put("id",id),true)
            Thread.sleep(1500)
            check(operation("status").getString("state")=="closed"){"aborted call left a preview alive"}
            passed("Same-UID IPC keeps completed sessions and closes aborted previews")
            runOnMainSync { PluginPreviewManager.onPaused() }
            check(runCatching { operation("open",JSONObject().put("id",id)) }.isFailure)
            check(operation("status").getString("state")=="closed")
            runOnMainSync { PluginPreviewManager.onResumed() }
            passed("Background host refuses preview without launching or changing foreground")
            // Native service integration, with no real key and no paid model request.
            js("document.querySelector('#model').click();true")
            waitFor("foreground model service") {js("document.querySelector('#model-status').textContent").contains("configured")}
            check(js("typeof JSON.parse(document.querySelector('#model-status').textContent).configured")=="\"boolean\"")
            check(payload("open",JSONObject().put("packageJson",draft.toString())).getBoolean("loaded"))
            payload("click",JSONObject().put("selector","#model"))
            waitFor("draft model service") {payload("snapshot",JSONObject().put("selector","#model-status")).getString("text").contains("configured")}
            operation("close")
            passed("Real page and uninstalled draft can reach host model status without receiving a key")
            val cfg=Config(targetContext)
            val revision=PluginPlatform.store(targetContext).activeBundle(id).getString("_revision")
            fun service(capability:String,args:JSONObject)=PluginPlatform.pageCapability(targetContext,cfg,id,revision,capability,args,PluginCancellation())
            val beforeRuns=PluginPlatform.store(targetContext).data(id).optInt("runs",0)
            val schedule=JSONObject().put("op","schedule").put("action","refresh").put("intervalMinutes",15)
            check(service("plugin.background",schedule).getBoolean("accepted"))
            val record=PluginBackgroundStore(targetContext).get(id)!!
            val scheduler=targetContext.getSystemService(android.app.job.JobScheduler::class.java)
            check(scheduler.allPendingJobs.any{it.id==record.jobId && it.extras.getString("generation")==record.generation})
            val staleInfo=android.app.job.JobInfo.Builder(record.jobId,android.content.ComponentName(targetContext,PluginBackgroundJob::class.java))
                .setPeriodic(record.intervalMs).setExtras(android.os.PersistableBundle().apply{putString("generation","stale")}).build()
            check(scheduler.schedule(staleInfo)==android.app.job.JobScheduler.RESULT_SUCCESS)
            check(!PluginBackground.status(targetContext,id).getBoolean("scheduled"))
            check(PluginBackground.reconcile(targetContext).getInt("restored")==1)
            check(scheduler.allPendingJobs.any{it.id==record.jobId && it.extras.getString("generation")==record.generation})
            scheduler.cancel(record.jobId)
            check(PluginBackground.reconcile(targetContext).getInt("restored")==1)
            originalRuntimeMode=cfg.dshMode
            cfg.dshMode="embedded"
            runOnMainSync {DshService.start(targetContext)}
            val runtimeDeadline=System.currentTimeMillis()+120000
            while(System.currentTimeMillis()<runtimeDeadline && Runtime.state !in setOf("ready","error"))Thread.sleep(250)
            check(Runtime.state=="ready"){"Synthetic runtime startup: ${Runtime.state}"}
            uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            uiAutomation.executeShellCommand("cmd jobscheduler run -f ${targetContext.packageName} ${record.jobId}").use{it.fileDescriptor.let{fd->java.io.FileInputStream(fd).readBytes()}}
            waitFor("system background action") {PluginBackground.status(targetContext,id).optString("lastStatus")=="ok"}
            check(PluginPlatform.store(targetContext).data(id).getInt("runs")==beforeRuns+1)
            check(js("document.querySelector('#draft').value")=="\"KEEP_DRAFT\"")
            runOnMainSync {activity.startActivity(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
            Thread.sleep(500)
            passed("OS JobScheduler executed native plugin action while page was hidden; state saved; missing system job restored")
            uiAutomation.executeShellCommand("pm grant ${targetContext.packageName} android.permission.POST_NOTIFICATIONS").use{java.io.FileInputStream(it.fileDescriptor).readBytes()}
            check(service("plugin.notification",JSONObject().put("title","Synthetic acceptance").put("body","Plugin background notification")).getBoolean("shown"))
            waitFor("system notification visible") {targetContext.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any{it.id==PluginBackgroundPolicy.jobId(id)}}
            check(!service("plugin.notification",JSONObject().put("title","Synthetic acceptance").put("body","Rate limited")).getBoolean("shown"))
            targetContext.getSystemService(android.app.NotificationManager::class.java).cancel(PluginBackgroundPolicy.notificationTag(id), PluginBackgroundPolicy.jobId(id))
            service("plugin.background",JSONObject().put("op","cancel"))
            check(!PluginBackground.status(targetContext,id).getBoolean("scheduled"))
            service("plugin.background",schedule)
            PluginPlatform.manage(targetContext,JSONObject().put("action","install").put("bundle",bundle))
            check(!PluginBackground.status(targetContext,id).getBoolean("scheduled"))
            passed("Native notification shown and rate limited; cancel/update removes the system schedule")
            operation("open",JSONObject().put("id",id))
            PluginPlatform.manage(targetContext,JSONObject().put("action","enable").put("id",id).put("enabled",false))
            check(runCatching{operation("snapshot")}.isFailure)
            passed("Disabling plugin invalidates preview")
            PluginPlatform.manage(targetContext,JSONObject().put("action","remove").put("id",id))
            runOnMainSync {shell.open("harness")}
            File(targetContext.cacheDir,"plugin-device-evidence.json").writeText(JSONObject().put("checks",evidence).toString(2))
            result.putString("stream","PASS: ${evidence.length()} acceptance groups\n$evidence\n")
            finish(Activity.RESULT_OK,result)
        } catch(e:Throwable){
            result.putString("stream","FAILED: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}\nPassed: $evidence\n")
            finish(Activity.RESULT_CANCELED,result)
        } finally {
            runCatching{PluginPreviewManager.closeAll()}
            runCatching{server?.close()}
            originalRuntimeMode?.let {Config(targetContext).dshMode=it}
            runCatching{PluginPlatform.manage(targetContext,JSONObject().put("action","remove").put("id",id))}
        }
    }
    /** Separate invocations intentionally survive process/device restart; only the fixture is retained. */
    private fun verifyBackgroundLifecycle() {
        val result = Bundle()
        val id = "plugin-periodic-1023"
        try {
            check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator")) { "仅允许专用模拟器" }
            if(scenario=="backgroundSeed") {
                check(PluginBackgroundStore(targetContext).get(id)==null) { "Fixture already scheduled; preserve its evidence" }
                val bundle=JSONObject().put("manifest",JSONObject().put("apiVersion",1).put("id",id).put("title","Periodic acceptance fixture").put("version","1.0").put("kind","native").put("capabilities",JSONArray().put("plugin.background").put("plugin.state")))
                    .put("view",JSONObject().put("type","text").put("text","PERIODIC_FIXTURE"))
                    .put("code","""export async function run({state}){return {state:{runs:(state.runs||0)+1,times:[...(state.times||[]),Date.now()]},view:{type:"text",text:"PERIODIC_FIXTURE_OK"}}}""")
                PluginPlatform.manage(targetContext,JSONObject().put("action","install").put("bundle",bundle))
                startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                runOnMainSync {DshService.start(targetContext)}
                val deadline=System.currentTimeMillis()+120000
                while(System.currentTimeMillis()<deadline && Runtime.state !in setOf("ready","error"))Thread.sleep(250)
                check(Runtime.state=="ready") { "Fixture runtime: ${Runtime.state}" }
                PluginBackground.schedule(targetContext,id,JSONObject().put("action","refresh").put("intervalMinutes",15))
                uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
            }
            if(scenario=="backgroundCleanup") {
                PluginPlatform.manage(targetContext,JSONObject().put("action","remove").put("id",id))
                check(PluginBackgroundStore(targetContext).get(id)==null)
            }
            val evidence=JSONObject().put("at",System.currentTimeMillis()).put("status",PluginBackground.status(targetContext,id))
                .put("data",if(scenario=="backgroundCleanup")JSONObject() else PluginPlatform.store(targetContext).data(id))
            result.putString("stream","LIFECYCLE: $evidence\n")
            finish(Activity.RESULT_OK,result)
        }catch(e:Throwable){result.putString("stream","FAIL: "+e.javaClass.name+": "+e.message+"\n");finish(Activity.RESULT_CANCELED,result)}
    }

    private fun verifyPreviewIdle() {
        val result=Bundle()
        try {
            check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator")) { "仅允许专用模拟器" }
            val activity=startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val server=android.net.LocalServerSocket("pd-idle-probe-"+java.util.UUID.randomUUID())
            val done=CountDownLatch(1)
            val watcher=Thread {
                try {server.accept().use { s -> s.soTimeout=1000;try{s.inputStream.read()}catch(e:Exception){sendStatus(0,Bundle().apply{putString("stream","IDLE_READ_EXCEPTION: "+e.javaClass.name+": "+e.message+"\n")})}}}
                finally{done.countDown()}
            }.apply{start()}
            android.net.LocalSocket().use { client ->
                client.connect(android.net.LocalSocketAddress(server.localSocketAddress.name,android.net.LocalSocketAddress.Namespace.ABSTRACT))
                Thread.sleep(1600)
            }
            check(done.await(3,TimeUnit.SECONDS));server.close();watcher.join(1000)
            val draft=JSONObject().put("manifest",JSONObject().put("apiVersion",1).put("frameworkVersion",1).put("id","preview-idle-check").put("title","Idle handshake check").put("version","1.0").put("kind","native").put("capabilities",JSONArray()))
                .put("view",JSONObject().put("type","page").put("entry","main.js").put("styles",JSONArray()))
                .put("resources",JSONObject().put("main.js","import {definePage} from '/framework/v1/page.js'; await new Promise(r=>setTimeout(r,2200));export default definePage({mount(ctx){ctx.root.textContent='SLOW_BOOT_READY'}});"))
            val socket=android.net.LocalSocket()
            socket.connect(android.net.LocalSocketAddress(HostRuntimeControl.start(targetContext),android.net.LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout=25000
            val request=JSONObject().put("action","plugin_preview").put("request",JSONObject().put("op","open").put("packageJson",draft.toString()))
            socket.outputStream.write((request.toString()+"\n").toByteArray());socket.outputStream.flush()
            val response=socket.use{JSONObject(it.inputStream.bufferedReader().readLine())}
            check(!response.has("error")){response.optString("error")}
            check(response.getJSONObject("value").getJSONObject("result").getBoolean("loaded")){response.toString()}
            Thread.sleep(1200)
            check(PluginPreviewManager.handle(JSONObject().put("op","status")).getString("state")=="ready")
            PluginPreviewManager.closeAll()
            result.putString("stream","PASS: Same-UID preview survives >1s idle while page boots, then remains ready\n")
            finish(Activity.RESULT_OK,result)
        }catch(e:Throwable){result.putString("stream","FAIL: "+e.javaClass.name+": "+e.message+"\n");finish(Activity.RESULT_CANCELED,result)}
    }

}
