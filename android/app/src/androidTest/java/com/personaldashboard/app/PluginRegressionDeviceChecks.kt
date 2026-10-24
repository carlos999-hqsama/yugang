package com.personaldashboard.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Adversarial lifecycle checks on the dedicated emulator, never a user's phone. */
internal class PluginRegressionDeviceChecks(private val runner: Instrumentation) {
    private val context get() = runner.targetContext
    private fun passed(text:String) = runner.sendStatus(0,Bundle().apply {putString("stream","PASS: $text\n")})
    private fun waitFor(text:String, timeout:Long=10000, condition:()->Boolean) {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) {if(condition())return;Thread.sleep(100)}
        check(condition()) {text}
    }
    private fun shell(command:String):String = runner.uiAutomation.executeShellCommand(command).use {java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8)}
    private fun activity():MainActivity = runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun preview():PluginPreview? = PluginPreviewManager::class.java.getDeclaredField("preview").apply {isAccessible=true}.get(PluginPreviewManager) as PluginPreview?
    private fun state():String = PluginPreviewManager.handle(JSONObject().put("op","status")).getString("state")
    private fun draft(code:String):JSONObject = JSONObject().put("manifest",JSONObject().put("apiVersion",1).put("frameworkVersion",1).put("id","lifecycle-draft-check").put("title","Lifecycle fixture").put("version","1.0").put("kind","native").put("capabilities",JSONArray()))
        .put("view",JSONObject().put("type","page").put("entry","main.js").put("styles",JSONArray()))
        .put("resources",JSONObject().put("main.js",code))
    private fun open(bundle:JSONObject, cancellation:PluginCancellation=PluginCancellation()):JSONObject = PluginPreviewManager.handle(JSONObject().put("op","open").put("packageJson",bundle.toString()),cancellation)
    private fun socketOpen(bundle:JSONObject):Thread = Thread {
        runCatching {
            val socket=android.net.LocalSocket()
            socket.connect(android.net.LocalSocketAddress(HostRuntimeControl.start(context),android.net.LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout=15000
            socket.use {
                val r=JSONObject().put("action","plugin_preview").put("request",JSONObject().put("op","open").put("packageJson",bundle.toString()))
                it.outputStream.write((r.toString()+"\n").toByteArray());it.outputStream.flush()
                it.inputStream.bufferedReader().readLine()
            }
        }
    }.apply {start()}

    fun run(scenario:String) {
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator")) {"仅允许专用模拟器"}
        val runtimeScenario=scenario in setOf("backgroundPreemption","runtimeDependencies")
        val cfg=if(runtimeScenario)Config(context)else null
        val previousMode=cfg?.dshMode
        if(runtimeScenario)cfg!!.dshMode="embedded"
        try {
        when(scenario) {
            "pluginLinkActivation" -> pluginLinkActivation()
            "a11yPlain" -> a11yPlain()
            "modalRendererRecovery" -> modalRendererRecovery()
            "pageCache" -> pageCache()
            "mediaOrientation" -> mediaOrientation()
            "previewLifecycle" -> previewLifecycle()
            "previewStreams" -> previewStreams()
            "originStorage" -> originStorage()
            "tarBoundaries" -> TarXzDeviceChecks(runner).run()
            "runtimeExtract" -> TarXzDeviceChecks(runner).runtime()
            "removalRecovery" -> PluginRemovalRecoveryDeviceChecks(runner).run()
            "previewStorage" -> PluginPreviewStorageDeviceChecks(runner).run()
            "notificationCollision" -> PluginNotificationCollisionDeviceChecks(runner).run()
            "backgroundRegistry" -> PluginBackgroundRegistryDeviceChecks(runner).run()
            "developerContract" -> PluginDeveloperContractDeviceChecks(runner).run()
            "secretIntegrity" -> ConfigSecretIntegrityDeviceChecks(runner).run()
            "insets" -> PluginInsetsDeviceChecks(runner).run()
            "backgroundPreemption" -> backgroundPreemption()
            "notificationDenied" -> notificationPermission(false)
            "notificationGranted" -> notificationPermission(true)
            "runtimeDependencies" -> runtimeDependencies()
            "browserBoundary" -> browserBoundary()
        }
        } finally { if(previousMode!=null)cfg!!.dshMode=previousMode }

    }

    private fun browserBoundary() {
        val app=activity()
        val isolated=object:android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences =
                this@PluginRegressionDeviceChecks.context.getSharedPreferences("audit-browser-"+name,mode)
        }
        val cfg=Config(isolated)
        val server=ServerSocket(0)
        cfg.dshMode="external";cfg.dshUrl="http://127.0.0.1:${server.localPort}";cfg.dshCookie="SYNTHETIC_BROWSER_COOKIE"
        val net=Net(cfg) {_,_->}
        val fileRequests=AtomicInteger()
        val privateRequests=AtomicInteger()
        val unexpectedNavigations=AtomicInteger()
        val slowStarted=CountDownLatch(1)
        val slowRelease=CountDownLatch(1)
        val fixture=Thread {
            while(!server.isClosed) try {val accepted=server.accept();Thread {try {accepted.use {socket->
                val reader=socket.getInputStream().bufferedReader();val request=reader.readLine().orEmpty()
                val headers=mutableListOf<String>();while(true){val line=reader.readLine();if(line.isNullOrEmpty())break;headers+=line}
                val local=request.contains("/api/file?")
                if(local){check(headers.any {it.contains("SYNTHETIC_BROWSER_COOKIE")});fileRequests.incrementAndGet()}
                val svg=request.contains("private.svg")
                if(local && svg)privateRequests.incrementAndGet()
                if(local && request.contains("unrequested.html"))unexpectedNavigations.incrementAndGet()
                if(local && request.contains("slow.html")){slowStarted.countDown();check(slowRelease.await(5,TimeUnit.SECONDS))}
                val body=if(local && svg) "<svg xmlns='http://www.w3.org/2000/svg' width='173' height='19'></svg>"
                else if(local && request.contains("frames.html")) "<html><body><script>window.addEventListener('securitypolicyviolation',e=>{if(e.violatedDirective==='frame-src')window.blockedFrame=true})</script><iframe src='https://foreign.audit.invalid/frame'></iframe><iframe id='local' src='safe.html' onload='window.localFrame=this.contentDocument.querySelector(\"img\").naturalWidth'></iframe></body></html>"
                else if(local) "<html><body><img src='private.svg' onload='window.observed=this.naturalWidth' onerror='window.observed=-1'></body></html>"
                else if(request.contains("/race")) "<html><body><script>window.raceTicks=0;setInterval(()=>{window.raceTicks++;const i=new Image();i.src='https://dshfile.pd.local/private.svg?t='+window.raceTicks;},30)</script></body></html>"
                else "<html><body><a id='go' href='mailto:manual@audit.invalid'>Open mail</a><img src='https://dshfile.pd.local/private.svg' onload='window.observed=this.naturalWidth' onerror='window.observed=-1'><script>setTimeout(()=>location.href='mailto:auto@audit.invalid',150)</script></body></html>"
                val data=body.toByteArray();val type=if(local && svg)"image/svg+xml" else "text/html"
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray()+data)
            }}catch(_:java.io.IOException) { }}.apply {isDaemon=true;start()}}catch(_:java.io.IOException) { }
        }.apply {isDaemon=true;start()}
        val monitor=runner.addMonitor(android.content.IntentFilter(Intent.ACTION_VIEW).apply {addDataScheme("mailto")},Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,Intent()),true)
        lateinit var root:android.widget.FrameLayout
        lateinit var panes:Panes
        lateinit var dialog:android.app.Dialog
        runner.runOnMainSync {root=android.widget.FrameLayout(app);panes=Panes(app,root,net,cfg);dialog=android.app.Dialog(app).apply {setContentView(root);show();window!!.setLayout(-1,700)}}
        fun show(id:String,url:String,cmd:String=""):WebView {
            lateinit var web:WebView
            runner.runOnMainSync {
                panes.browser(JSONObject().put("visible",true).put("active",id).put("cmd",cmd).put("tabs",JSONArray().put(JSONObject().put("id",id).put("url",url))).put("rect",JSONObject().put("x",0).put("y",0).put("w",300).put("h",200)))
                web=(0 until root.childCount).map {root.getChildAt(it)}.filterIsInstance<WebView>().single()
            }
            return web
        }
        fun eval(web:WebView,code:String):String? {val done=CountDownLatch(1);var result:String?=null;runner.runOnMainSync {web.evaluateJavascript(code){result=it;done.countDown()}};check(done.await(3,TimeUnit.SECONDS));return result}
        fun currentUrl(web:WebView):String = org.json.JSONTokener(eval(web,"location.href")).nextValue().toString()
        try {
            val normal=show("normal","https://dshfile.pd.local/safe.html")
            waitFor("Normal local report did not load its image") {eval(normal,"window.observed") == "173"}
            val baseline=privateRequests.get()
            val remote=show("remote","http://127.0.0.1:${server.localPort}/remote")
            var observed:String?=null
            waitFor("Remote image attempt did not complete") {observed=eval(remote,"window.observed");observed in setOf("173","-1")}
            Thread.sleep(400)
            val automatic=monitor.hits
            runner.sendStatus(0,Bundle().apply {putString("stream","OBSERVED: localImage=173 remoteImage=$observed authenticatedPrivateImageRequests=${privateRequests.get()-baseline} automaticExternalIntents=$automatic\n")})
            runner.runOnMainSync {remote.requestFocus();remote.evaluateJavascript("document.querySelector('#go').focus()",null)}
            runner.waitForIdleSync();runner.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));runner.sendKeySync(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))
            waitFor("User link activation did not reach the monitored intent") {monitor.hits==automatic+1}
            check(observed=="-1") {"Remote browser page embedded an authenticated private image"}
            check(automatic==0) {"Remote browser page launched an external intent without a user gesture"}
            passed("Native reports retain same-origin images; remote pages cannot embed private files or auto-open external apps; user links still work")
            val fileUrl="https://dshfile.pd.local/safe.html"
            val remoteUrl="http://127.0.0.1:${server.localPort}/remote"
            val same=show("remote",fileUrl,"navigate")
            check(same===remote) {"Address navigation replaced the existing browser tab"}
            waitFor("Explicit address navigation could not load the local report") {eval(same,"window.observed")=="173"}
            val beforeRemote=fileRequests.get()
            show("remote",remoteUrl,"navigate")
            waitFor("A previously authorized tab retained file access after leaving the report") {currentUrl(same)==remoteUrl && eval(same,"window.observed")=="-1"}
            runner.runOnMainSync {same.evaluateJavascript("location.href="+JSONObject.quote(fileUrl),null)}
            Thread.sleep(350)
            check(currentUrl(same)==remoteUrl) {"A remote page navigated into the private file origin"}
            check(fileRequests.get()==beforeRemote) {"Leaving the report did not revoke private file requests"}
            show("remote",remoteUrl,"back")
            waitFor("Back navigation did not restore a legitimate local report") {currentUrl(same)==fileUrl && eval(same,"window.observed")=="173"}
            show("remote",fileUrl,"fwd")
            waitFor("Forward navigation restored private file access to a remote page") {currentUrl(same)==remoteUrl && eval(same,"window.observed")=="-1"}
            passed("Address-bar file navigation keeps the same tab; leaving a report revokes file access; remote navigation cannot grant it; back and forward preserve the boundary")
            val racing=show("remote","http://127.0.0.1:${server.localPort}/race","navigate")
            waitFor("Old remote document did not start its polling control") {(eval(racing,"window.raceTicks")?.toIntOrNull()?:0)>2}
            val privateBefore=privateRequests.get()
            val ticksBefore=eval(racing,"window.raceTicks")!!.toInt()
            show("remote","https://dshfile.pd.local/slow.html","navigate")
            check(slowStarted.await(3,TimeUnit.SECONDS)) {"Slow file navigation did not enter the proxy"}
            Thread.sleep(600)
            val ticksDuring=eval(racing,"window.raceTicks")?.toIntOrNull()?:0
            val borrowed=privateRequests.get()-privateBefore
            runner.sendStatus(0,Bundle().apply {putString("stream","OBSERVED: transition oldPageTicks=$ticksBefore->$ticksDuring authenticatedPrivateRequests=$borrowed\n")})
            check(ticksDuring>ticksBefore) {"Old document did not remain active during the delayed navigation control"}
            check(borrowed==0) {"Old remote document borrowed private file access before report commit"}
            runner.runOnMainSync {racing.evaluateJavascript("location.href='https://dshfile.pd.local/unrequested.html'",null)}
            Thread.sleep(250)
            runner.sendStatus(0,Bundle().apply {putString("stream","OBSERVED: unrequestedAuthenticatedNavigations=${unexpectedNavigations.get()}\n")})
            check(unexpectedNavigations.get()==0) {"Old remote page replaced the authorized file destination with another private path"}
            slowRelease.countDown()
            waitFor("Delayed legitimate report lost its relative image") {eval(racing,"window.observed")=="173"}
            passed("While file navigation is delayed, the still-running remote document cannot borrow authenticated file access; the committed report retains its image")
            show("remote","https://dshfile.pd.local/frames.html","navigate")
            waitFor("Cross-origin report frame was not rejected by the real CSP") {eval(racing,"window.blockedFrame")=="true"}
            waitFor("Same-origin report frame lost its relative image") {eval(racing,"window.localFrame")=="173"}
            passed("Real report CSP blocks foreign child frames and preserves same-origin frames with relative images")
        } finally {slowRelease.countDown();runner.removeMonitor(monitor);runner.runOnMainSync {panes.close();dialog.dismiss()};net.dispose();server.close();fixture.join(1000);context.getSharedPreferences("audit-browser-pd",android.content.Context.MODE_PRIVATE).edit().clear().commit()}
    }

    private fun runtimeDependencies() {
        activity()
        val cfg = Config(context)
        Runtime.ensure(context, cfg, Net(cfg) { _, _ -> })
        waitFor("Updated Harness did not become ready", 120000) { Runtime.status(context).optString("state") == "ready" }
        val expected = JSONObject(context.assets.open("runtime/runtime.json").bufferedReader().use { it.readText() })
        val id = "runtime-dependencies-check"
        val code = """
            import {readFileSync} from 'node:fs';
            import {createRequire} from 'node:module';
            import {execFileSync} from 'node:child_process';
            export async function run(){
              const require=createRequire('/opt/pd/dsh/node_modules/@deepseek-ai/libreoffice-kit/package.json');
              const zip=require('fflate');
              const control=zip.unzipSync(zip.zipSync({'ok.txt':Uint8Array.of(79,75)}));
              if(control['ok.txt'][0]!==79)throw Error('ZIP control failed');
              const mcp=JSON.parse(readFileSync('/opt/pd/dsh/node_modules/@modelcontextprotocol/client/package.json','utf8'));
              const npmRequire=createRequire('/usr/local/lib/node_modules/npm/package.json');
              const socksRequire=createRequire(npmRequire.resolve('socks/package.json'));
              const ip=socksRequire('ip-address');
              if(new ip.Address6('2001:db8::1').correctForm()!=='2001:db8::1')throw Error('IPv6 control failed');
              const ipProbe="const {Address6}=require(process.argv[1]);try{new Address6('2001:db8::1'+'%'.repeat(100000)+String.fromCharCode(10))}catch{process.exit(0)}process.exit(1)";
              execFileSync('/usr/local/bin/node',['-e',ipProbe,socksRequire.resolve('ip-address')],{timeout:2000,env:{PATH:'/usr/local/bin:/usr/bin:/bin'}});
              const ipVersion=JSON.parse(readFileSync('/usr/local/lib/node_modules/npm/node_modules/ip-address/package.json','utf8')).version;
              const version=command=>execFileSync(command,['--version','--userconfig='+process.cwd()+'/npm-user.rc','--globalconfig='+process.cwd()+'/npm-global.rc'],{encoding:'utf8',timeout:10000,env:{PATH:'/usr/local/bin:/usr/bin:/bin'}}).trim();
              return {view:{type:'text',text:JSON.stringify({node:process.version,npm:version('/usr/local/bin/npm'),npx:version('/usr/local/bin/npx'),mcp:mcp.version,ipAddress:ipVersion,zip:true})}};
            }
        """.trimIndent()
        val bundle = JSONObject().put("manifest", JSONObject().put("apiVersion",1).put("id",id).put("title","Runtime fixture").put("version","1.0").put("kind","native").put("capabilities",JSONArray()))
            .put("view",JSONObject().put("type","text").put("text","Synthetic runtime verification")).put("code",code)
        try {
            PluginPlatform.manage(context,JSONObject().put("action","install").put("bundle",bundle))
            val value=JSONObject(PluginPlatform.invoke(context,id,"check",JSONObject()).getJSONObject("view").getString("text"))
            check(value.getString("node")==expected.getString("node"))
            check(value.getString("npm")==expected.getString("npm"))
            check(value.getString("npx")==expected.getString("npm"))
            check(value.getString("mcp")=="2.3.0" && value.getBoolean("zip"))
            check(value.getString("ipAddress")=="10.7.4")
            passed("Installed ARM64 Harness, npm/npx and patched MCP/Office/IPv6 dependencies execute through the real plugin process")
        } finally { PluginPlatform.manage(context,JSONObject().put("action","remove").put("id",id)) }
    }

    private fun a11yPlain() {
        val automation=runner.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val app=activity();var web:WebView?=null;var dialog:android.app.Dialog?=null
        try {
            runner.runOnMainSync {
                web=WebView(app).apply {settings.javaScriptEnabled=true}
                dialog=android.app.Dialog(app).apply {setContentView(web!!);show();window!!.setLayout(900,600)}
                web!!.loadDataWithBaseURL("https://a11y-control.invalid/","<html><body><script>window.clicked=0</script><button onclick='window.clicked++'>Plain activation</button></body></html>","text/html","utf-8",null)
            }
            fun find(n:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                if(n==null)return null
                if((n.text?.toString().orEmpty()+n.contentDescription?.toString().orEmpty()).contains("Plain activation") && n.isClickable)return n
                for(i in 0 until n.childCount)find(n.getChild(i))?.let{return it}
                return null
            }
            var node:android.view.accessibility.AccessibilityNodeInfo?=null
            waitFor("Plain WebView node missing"){node=find(automation.rootInActiveWindow);node!=null}
            check(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            var clicked=false
            waitFor("Unmodified platform WebView ACTION_CLICK did not activate DOM") {
                runner.runOnMainSync {web!!.evaluateJavascript("window.clicked") {clicked=it=="1"}}
                clicked
            }
            passed("Unmodified platform WebView accessibility control activates DOM")
        } finally {runner.runOnMainSync {dialog?.dismiss();web?.destroy()}}
    }

    private fun pluginLinkActivation() {
        val automation = runner.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val accessibility=context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        check(accessibility.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()) {"此场景需要专用模拟器先启用TalkBack，不能用被测试工具抑制的辅助服务替代"}
        val serviceInfo = automation.serviceInfo
        val oldFlags = serviceInfo.flags
        serviceInfo.flags = oldFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = serviceInfo
        val app = activity()
        val id = "link-activation-check"
        val bundle = draft("""
            import {definePage} from '/framework/v1/page.js';
            export default definePage({mount(ctx){window.linkClicks=0;ctx.root.innerHTML='<a id="target" style="display:block;padding:32px" href="https://example.com/lint-link-check">Open test link</a>';ctx.root.querySelector('#target').addEventListener('click',()=>window.linkClicks++);}});
        """.trimIndent()).apply { getJSONObject("manifest").put("id",id) }
        val filter = android.content.IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }
        val monitor = runner.addMonitor(filter,android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,Intent()),true)
        fun views(v:android.view.View):List<android.view.View> = listOf(v) +
            if(v is android.view.ViewGroup) (0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
        var page:WebView? = null
        try {
            PluginPlatform.manage(context,JSONObject().put("action","install").put("bundle",bundle))
            runner.runOnMainSync { app.openPlugin(id) }
            waitFor("Link fixture page missing") {
                runner.runOnMainSync { page=views(app.window.decorView).filterIsInstance<WebView>().firstOrNull {it.url?.contains("plugin-$id.pd.local")==true} }
                page != null
            }
            val web = page!!
            var ready=false
            waitFor("Link fixture not mounted") {
                runner.runOnMainSync {web.evaluateJavascript("!!document.querySelector('#target')") {ready=it=="true"}}
                ready
            }
            fun accessibleLink(node:android.view.accessibility.AccessibilityNodeInfo?, depth:Int=0):android.view.accessibility.AccessibilityNodeInfo? {
                if(node == null || depth > 24) return null
                val label = node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()
                if (label.contains("Open test link") && node.isClickable && node.actionList.any {it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK}) return node
                for(i in 0 until node.childCount) accessibleLink(node.getChild(i),depth+1)?.let {return it}
                return null
            }
            var link:android.view.accessibility.AccessibilityNodeInfo? = null
            waitFor("Accessible link missing") {
                link = accessibleLink(automation.rootInActiveWindow)
                link != null
            }
            check(link!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Accessibility link activation was swallowed") {monitor.hits==1}
            passed("Accessibility activation opens one external link without a touch event")
            val focused=CountDownLatch(1)
            runner.runOnMainSync {web.requestFocus();web.evaluateJavascript("document.querySelector('#target').focus();true") {focused.countDown()} }
            check(focused.await(3,TimeUnit.SECONDS))
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            waitFor("Keyboard link activation was swallowed") {monitor.hits==2}
            passed("Keyboard activation opens one external link without a touch event")
        } finally {
            serviceInfo.flags = oldFlags
            automation.serviceInfo = serviceInfo
            runner.removeMonitor(monitor)
            if(!app.isDestroyed)runner.runOnMainSync {app.openPlugin("harness")}
            PluginPlatform.manage(context,JSONObject().put("action","remove").put("id",id))
        }
    }

    private fun modalRendererRecovery() {
        val app = activity()
        fun views(v:android.view.View):List<android.view.View> = listOf(v) +
            if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        fun find(host:String):WebView? {
            var found:WebView? = null
            runner.runOnMainSync {
                found = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .flatMap { views(it) }.filterIsInstance<WebView>().firstOrNull {
                        if (host == "licenses.pd.local") !it.settings.javaScriptEnabled
                        else it.url?.contains(host) == true
                    }
            }
            return found
        }
        val detail = object:android.webkit.RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_BOUND
        }
        try {
            runner.runOnMainSync { app.showLicenses() }
            waitFor("License window missing") { find("licenses.pd.local") != null }
            val license = find("licenses.pd.local")!!
            runner.runOnMainSync { check(license.webViewClient.onRenderProcessGone(license,detail)) }
            waitFor("Failed license window retained") { find("licenses.pd.local") == null }
            check(!app.isDestroyed)
            passed("License renderer-exit callback dismisses and releases only the license window")
        } finally { if(!app.isDestroyed) runner.runOnMainSync { app.finish() } }
    }

    private fun pageCache() {
        val app=activity()
        val ids=listOf("page-cache-a","page-cache-b")
        fun bundle(id:String,version:String="1.0")=draft("""
            import {definePage} from '/framework/v1/page.js';
            window.bootId=crypto.randomUUID();window.shows=0;window.hides=0;
            export default definePage({mount(ctx){ctx.root.innerHTML='<input id="draft"><div id="scroll" style="height:120px;overflow:auto"><div style="height:3000px">cache fixture</div></div>';},onShow(){window.shows++},onHide(){window.hides++}});
        """.trimIndent()).apply {getJSONObject("manifest").put("id",id).put("version",version)}
        fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun find(id:String):WebView? {var result:WebView?=null;runner.runOnMainSync {result=views(app.window.decorView).filterIsInstance<WebView>().firstOrNull {it.url?.contains("plugin-$id.pd.local")==true}};return result}
        fun js(web:WebView,code:String):String {val latch=CountDownLatch(1);var value="";runner.runOnMainSync {web.evaluateJavascript(code){value=it;latch.countDown()}};check(latch.await(3,TimeUnit.SECONDS)) {var details="";runner.runOnMainSync {details="url=${web.url} attached=${web.isAttachedToWindow} visible=${web.visibility} activityDestroyed=${app.isDestroyed}"};"No JS response: $details"};return value}
        fun navigate(id:String):WebView {
            runner.runOnMainSync {app.openPlugin(id)}
            waitFor("Page $id missing") {find(id)!=null}
            val web=find(id)!!;try {waitFor("Page $id not ready") {
                var loaded=false;runner.runOnMainSync {loaded=web.progress==100}
                loaded && js(web,"window.shows>0")=="true"
            }} catch(e:IllegalStateException) {
                var details="";runner.runOnMainSync {details="url=${web.url} progress=${web.progress} attached=${web.isAttachedToWindow} visibility=${web.visibility}"}
                val state=runCatching {js(web,"JSON.stringify({ready:document.readyState,shows:window.shows,body:document.body?.innerText})")}.getOrElse {it.message.orEmpty()}
                error("${e.message}; $details; state=$state")
            };return web
        }
        try {
            ids.forEach {PluginPlatform.manage(context,JSONObject().put("action","install").put("bundle",bundle(it)))}
            val first=navigate(ids[0]);val boot=js(first,"window.bootId")
            waitFor("Fixture layout not ready") {js(first,"document.querySelector('#scroll').clientHeight>0&&document.querySelector('#scroll').scrollHeight>1000")=="true"}
            check(js(first,"document.querySelector('#draft').value='DRAFT_ONLY_IN_MEMORY';document.querySelector('#scroll').scrollTop=413;Math.abs(document.querySelector('#scroll').scrollTop-413)<1")=="true") {"Fixture scroll not seeded: "+js(first,"JSON.stringify({top:document.querySelector('#scroll').scrollTop,height:document.querySelector('#scroll').clientHeight,total:document.querySelector('#scroll').scrollHeight})") }
            val other=navigate(ids[1]);waitFor("Hidden lifecycle missing") {js(first,"window.hides===1")=="true"}
            check(first.visibility==android.view.View.GONE)
            check(navigate(ids[0])===first) {"Menu switch rebuilt WebView"}
            waitFor("Visible lifecycle missing") {js(first,"window.shows===2")=="true"}
            check(js(first,"window.bootId")==boot)
            check(js(first,"document.querySelector('#draft').value==='DRAFT_ONLY_IN_MEMORY'&&Math.abs(document.querySelector('#scroll').scrollTop-413)<1")=="true") {"Cache state: "+js(first,"JSON.stringify({draft:document.querySelector('#draft').value,top:document.querySelector('#scroll').scrollTop,height:document.querySelector('#scroll').clientHeight,total:document.querySelector('#scroll').scrollHeight})") }
            runner.runOnMainSync {app.openPlugin(ids[0])};check(js(first,"window.shows")=="2")
            passed("Menu switch retains WebView, JS instance, draft and scroll; selecting current page is a no-op")
            runner.runOnMainSync {app.openPlugin("harness")};check(find(ids[0])===first)
            check(navigate(ids[0])===first);passed("Core chat roundtrip restores the existing plugin page")
            runner.runOnMainSync {app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)}
            waitFor("Hidden page not released on memory trim") {find(ids[1])==null}
            check(find(ids[0])===first)
            val otherAgain=navigate(ids[1]);check(otherAgain!==other);passed("Real memory callback releases hidden pages and preserves the active page")
            PluginPlatform.manage(context,JSONObject().put("action","enable").put("id",ids[0]).put("enabled",false))
            waitFor("Disabled hidden page retained") {find(ids[0])==null}
            check(find(ids[1])===otherAgain) {"Disabling another plugin replaced the active page"}
            PluginPlatform.manage(context,JSONObject().put("action","enable").put("id",ids[0]).put("enabled",true))
            val firstAgain=navigate(ids[0]);check(firstAgain!==first);passed("Disabling invalidates only its cached page; enabling creates a valid instance")
            val old=navigate(ids[1])
            PluginPlatform.manage(context,JSONObject().put("action","install").put("bundle",bundle(ids[1],"1.1")))
            waitFor("Updated active page not replaced") {find(ids[1])?.let {it!==old}==true}
            val updated=find(ids[1])!!
            check(find(ids[0])===firstAgain) {"Updating a plugin replaced another cached page"}
            passed("Installing a new revision replaces only the affected cached page")
            runner.runOnMainSync {firstAgain.webViewClient.onRenderProcessGone(firstAgain,object:android.webkit.RenderProcessGoneDetail(){override fun didCrash()=true;override fun rendererPriorityAtExit()=WebView.RENDERER_PRIORITY_BOUND})}
            waitFor("Hidden exit callback retained its page") {find(ids[0])==null}
            check(find(ids[1])===updated && updated.visibility==android.view.View.VISIBLE)
            passed("Hidden renderer-exit callback does not navigate away from the active plugin")
            navigate(ids[0]);navigate(ids[1])
            PluginPlatform.manage(context,JSONObject().put("action","remove").put("id",ids[0]))
            waitFor("Removed plugin retained its page") {find(ids[0])==null}
            check(find(ids[1])===updated)
            passed("Removal releases only the removed plugin page")
            val panes=MainActivity::class.java.getDeclaredField("panes").apply {isAccessible=true}.get(app) as Panes
            runner.runOnMainSync {app.openPlugin("harness");panes.browser(JSONObject().put("visible",false).put("tabs",JSONArray().put(JSONObject().put("id","fixture-tab").put("url","about:blank"))));panes.dshWeb(JSONObject().put("visible",true))}
            open(draft("import {definePage} from '/framework/v1/page.js';export default definePage({mount(ctx){ctx.root.textContent='DESTROY_FIXTURE'}});"))
            runner.runOnMainSync {app.finish()}
            waitFor("Activity not destroyed") {app.isDestroyed}
            waitFor("Activity destruction retained WebViews",2000) {var count=0;runner.runOnMainSync {count=views(app.window.decorView).filterIsInstance<WebView>().size};count==0 && preview()==null}
            passed("Activity destruction releases core, browser, embedded, cached and preview WebViews")
        } finally {
            if(!app.isDestroyed)runner.runOnMainSync {app.openPlugin("harness")}
            ids.forEach {PluginPlatform.manage(context,JSONObject().put("action","remove").put("id",it))}
        }
    }

    private fun mediaOrientation() {
        val colors=intArrayOf(android.graphics.Color.RED,android.graphics.Color.GREEN,android.graphics.Color.BLUE,android.graphics.Color.YELLOW)
        val corners=arrayOf(intArrayOf(0,1,2,3),intArrayOf(1,0,3,2),intArrayOf(3,2,1,0),intArrayOf(2,3,0,1),intArrayOf(0,2,1,3),intArrayOf(2,0,3,1),intArrayOf(3,1,2,0),intArrayOf(1,3,0,2))
        val imageStore=PluginImages(context)
        val file=java.io.File(context.cacheDir,"orientation-fixture.jpg")
        try {
            for(orientation in 1..8) {
                val source=android.graphics.Bitmap.createBitmap(80,40,android.graphics.Bitmap.Config.ARGB_8888)
                for(y in 0 until 40)for(x in 0 until 80)source.setPixel(x,y,colors[(if(y<20)0 else 2)+(if(x<40)0 else 1)])
                file.outputStream().use { source.compress(android.graphics.Bitmap.CompressFormat.JPEG,100,it) };source.recycle()
                androidx.exifinterface.media.ExifInterface(file).apply {setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
                val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"orientation-fixture.jpg");put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/jpeg")}
                val uri=context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Fixture insert failed")
                try {
                    context.contentResolver.openOutputStream(uri)!!.use {it.write(file.readBytes())}
                    val refs=imageStore.import("orientation-check","317404bf-b0f1-4c75-a582-d100b6d8d137",listOf(uri),PluginCancellation())
                    val result=PluginImages.decode(imageStore.payload("orientation-check","317404bf-b0f1-4c75-a582-d100b6d8d137",refs).getJSONObject(0))
                    val rotated=orientation>=5;check(result.width==(if(rotated)40 else 80)&&result.height==(if(rotated)80 else 40)) {"Orientation $orientation dimensions"}
                    val points=arrayOf(10 to 10,(result.width-11) to 10,10 to (result.height-11),(result.width-11) to (result.height-11))
                    for((index,point)in points.withIndex()) {
                        val actual=result.getPixel(point.first,point.second)
                        val closest=colors.indices.minBy {val c=colors[it];kotlin.math.abs(android.graphics.Color.red(actual)-android.graphics.Color.red(c))+kotlin.math.abs(android.graphics.Color.green(actual)-android.graphics.Color.green(c))+kotlin.math.abs(android.graphics.Color.blue(actual)-android.graphics.Color.blue(c))}
                        check(closest==corners[orientation-1][index]) {"Orientation $orientation corner $index"}
                    }
                    result.recycle();imageStore.clear("orientation-check","317404bf-b0f1-4c75-a582-d100b6d8d137",refs)
                    passed("JPEG EXIF orientation $orientation retains expected corners and dimensions")
                } finally {context.contentResolver.delete(uri,null,null)}
            }
        } finally {file.delete();imageStore.remove("orientation-check")}
    }

    private fun originStorage() {
        val app=activity()
        val suffix=java.util.UUID.randomUUID().toString().take(8)
        val ids=listOf("storage-check-a-$suffix","storage-check-b-$suffix")
        val notifications=context.getSystemService(android.app.NotificationManager::class.java)
        val hadPermission=context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(!hadPermission)shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        fun bundle(id:String, version:String="1.0") = draft("""
            import {definePage} from '/framework/v1/page.js';
            export default definePage({mount(ctx){ctx.root.textContent='STORAGE_FIXTURE';window.fixtureReady=true;},unmount(){localStorage.setItem('late-unload','must-not-survive')}});
        """.trimIndent()).apply {getJSONObject("manifest").put("id",id).put("version",version).put("capabilities",JSONArray().put("plugin.notification"))}
        fun all(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
        fun find(id:String):WebView? {var found:WebView?=null;runner.runOnMainSync {found=all(app.window.decorView).filterIsInstance<WebView>().firstOrNull {it.url?.contains("plugin-$id.pd.local")==true}};return found}
        fun js(web:WebView,code:String):String {val done=CountDownLatch(1);var result="";runner.runOnMainSync {web.evaluateJavascript(code){result=it;done.countDown()}};check(done.await(3,TimeUnit.SECONDS));return result}
        fun page(id:String):WebView {runner.runOnMainSync {app.openPlugin(id)};waitFor("Storage fixture page missing") {find(id)!=null};val web=find(id)!!;waitFor("Storage fixture not ready") {js(web,"window.fixtureReady===true")=="true"};return web}
        fun state(web:WebView):JSONObject {js(web,"window.fixtureState=null;(async()=>{window.fixtureState=JSON.stringify({local:localStorage.getItem('fixture'),late:localStorage.getItem('late-unload'),dbs:(await indexedDB.databases()).map(x=>x.name)})})()");var raw="null";waitFor("Storage fixture read failed") {raw=js(web,"window.fixtureState");raw!="null"};return JSONObject(org.json.JSONTokener(raw).nextValue() as String)}
        fun manage(action:String,id:String,version:String="1.0") = PluginPlatform.manage(context,JSONObject().put("action",action).put("id",id).apply {if(action=="install")put("bundle",bundle(id,version));if(action=="enable")put("enabled",false)})
        try {
            ids.forEach {manage("install",it)}
            val a=page(ids[0]);val b=page(ids[1])
            for(web in listOf(a,b)) {
                js(web,"window.fixtureSeeded=false;(async()=>{localStorage.setItem('fixture','control');const r=indexedDB.open('fixture-drafts',1);r.onupgradeneeded=()=>r.result.createObjectStore('images');await new Promise((resolve,reject)=>{r.onsuccess=()=>{r.result.close();resolve()};r.onerror=reject});window.fixtureSeeded=true})()")
                waitFor("Storage fixture seed failed") {js(web,"window.fixtureSeeded")=="true"}
            }
            manage("install",ids[0],"1.1")
            val updated=page(ids[0]);check(state(updated).getString("local")=="control") {"Update removed plugin draft"}
            manage("enable",ids[0])
            PluginPlatform.manage(context,JSONObject().put("action","enable").put("id",ids[0]).put("enabled",true))
            val enabled=page(ids[0]);check(state(enabled).getJSONArray("dbs").length()==1) {"Disable removed plugin database"}
            page(ids[1]) // Remove the cached hidden page; no foreground navigation is needed.
            ids.forEach {id -> check(PluginBackground.notify(context,id,JSONObject().put("title","Synthetic uninstall test").put("body","No user content")).getBoolean("shown"))}
            val pending=notifications.activeNotifications.single{it.id==PluginBackgroundPolicy.jobId(ids[0])}.notification.contentIntent
            manage("remove",ids[0])
            check(notifications.activeNotifications.none{it.id==PluginBackgroundPolicy.jobId(ids[0])}) {"Uninstall retained notification"}
            check(notifications.activeNotifications.any{it.id==PluginBackgroundPolicy.jobId(ids[1])}) {"Uninstall removed sibling notification"}
            check(runCatching {pending.send()}.exceptionOrNull() is android.app.PendingIntent.CanceledException) {"Uninstall retained notification tap intent"}
            check(find(ids[0])==null) {"Uninstall left a live page"}
            val control=state(b)
            check(control.getString("local")=="control" && control.getJSONArray("dbs").length()==1) {"Uninstall erased a sibling plugin"}
            manage("install",ids[0],"2.0")
            val reinstalled=state(page(ids[0]))
            check(reinstalled.isNull("local") && reinstalled.isNull("late") && reinstalled.getJSONArray("dbs").length()==0) {"Reinstall retained old storage: $reinstalled"}
            passed("Updates and disable preserve drafts; uninstall closes hidden page and clears localStorage/IndexedDB; reinstall is clean, notifications/tap intents are removed and sibling data/notifications survive")
        } finally {
            runner.runOnMainSync {app.openPlugin("harness")}
            ids.forEach {manage("remove",it)}
            if(!hadPermission)shell("pm revoke ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
    }

    private fun previewStreams() {
        activity()
        fun js(web: WebView, code: String): String {
            val done = CountDownLatch(1)
            var result = ""
            runner.runOnMainSync {web.evaluateJavascript(code) {result=it;done.countDown()}}
            check(done.await(3,TimeUnit.SECONDS)) {"No preview JavaScript response"}
            return result
        }
        val failures = mutableListOf<String>()
        try {
            val offline = draft("import {definePage} from '/framework/v1/page.js';import {sse} from '/framework/v1/js/native.js';export default definePage({mount(ctx){window.fixtureState='pending';sse('dsh','/fixture',{onState:(kind)=>window.fixtureState=kind});ctx.root.textContent='OFFLINE_STREAM'}});")
            PluginPreviewManager.handle(JSONObject().put("op","open").put("packageJson",offline.toString())
                .put("network",JSONObject().put("mode","offline")))
            val webField = PluginPreview::class.java.getDeclaredField("web").apply { isAccessible=true }
            runCatching {
                val web = webField.get(preview()!!) as WebView
                waitFor("Offline SSE never reached its state callback", 3000) {
                    js(web,"window.fixtureState") == "\"error\""
                }
                passed("Offline SSE reports an error through the real SDK state callback")
            }.onFailure { failures += it.message.orEmpty() }
            open(draft("import {definePage} from '/framework/v1/page.js';export default definePage({mount(ctx){ctx.root.textContent='RELOAD_STREAM'}});"))
            val session = preview()!!
            val net = PluginPreview::class.java.getDeclaredField("net").apply {isAccessible=true}.get(session) as Net
            @Suppress("UNCHECKED_CAST")
            val streams = Net::class.java.getDeclaredField("sses").apply {isAccessible=true}.get(net) as MutableMap<String, okhttp3.sse.EventSource>
            val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
            streams["fixture-stream"] = object : okhttp3.sse.EventSource {
                override fun request() = okhttp3.Request.Builder().url("https://fixture.invalid/").build()
                override fun cancel() { canceled.set(true) }
            }
            runCatching {
                session.reload()
                waitFor("WebView reload retained the old document SSE",3000) {canceled.get()}
                check(streams.isEmpty())
                passed("Real WebView reload closes streams owned by its previous document")
            }.onFailure { failures += it.message.orEmpty() }
            check(failures.isEmpty()) {failures.joinToString("; ")}
        } finally { PluginPreviewManager.closeAll() }
    }

    private fun previewLifecycle() {
        activity()
        val normal=draft("import {definePage} from '/framework/v1/page.js';export default definePage({mount(ctx){ctx.root.textContent='READY'}});")
        try {
            HostRuntimeControl.start(context)
            open(normal)
            val first=preview()!!
            HostRuntimeControl.stop()
            check(first.isClosed() && state()=="closed") {"Explicit stop left preview alive"}
            passed("Explicit tool stop closes an attached preview")

            HostRuntimeControl.start(context)
            val busy=CountDownLatch(1);val release=CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {busy.countDown();release.await(2,TimeUnit.SECONDS)}
            check(busy.await(2,TimeUnit.SECONDS))
            val queued=socketOpen(normal)
            try {
                waitFor("Queued preview not admitted") { val f=HostRuntimeControl::class.java.getDeclaredField("previewRequests").apply {isAccessible=true};(f.get(HostRuntimeControl) as Set<*>).isNotEmpty() }
                HostRuntimeControl.stop()
            } finally {release.countDown()}
            queued.join(5000);Thread.sleep(300)
            check(!queued.isAlive && preview()==null && state()=="closed") {"Queued cancellation: threadAlive=${queued.isAlive}, preview=${preview()?.status()}, state=${state()}"}
            passed("Stopping before UI creation cannot attach a late WebView")

            HostRuntimeControl.start(context)
            val slow=draft("import {definePage} from '/framework/v1/page.js';await new Promise(r=>setTimeout(r,2200));export default definePage({mount(ctx){ctx.root.textContent='SLOW_READY'}});")
            val opening=socketOpen(slow)
            waitFor("Slow preview not attached") {preview()!=null}
            val attached=preview()!!
            HostRuntimeControl.stop();opening.join(5000)
            check(!opening.isAlive && attached.isClosed() && state()=="closed")
            passed("Stopping during slow boot cancels its in-flight preview request")

            HostRuntimeControl.start(context)
            val oldRequest=PluginCancellation();open(normal,oldRequest);val old=preview()!!
            open(normal);val current=preview()!!
            oldRequest.cancel()
            val close=PluginPreviewManager::class.java.getDeclaredMethod("closeSession",String::class.java,PluginPreview::class.java).apply {isAccessible=true}
            close.invoke(PluginPreviewManager,"stale expiry",old)
            check(preview()===current && !current.isClosed() && state()=="ready")
            passed("Old cancellation and stale expiry cannot close a replacement session")
            PluginPreviewManager.closeAll()
            val ticker=PluginPreviewManager::class.java.getDeclaredField("ticker").apply {isAccessible=true}.get(PluginPreviewManager) as Runnable
            val handler=PluginPreviewManager::class.java.getDeclaredField("handler").apply {isAccessible=true}.get(PluginPreviewManager) as Handler
            runner.runOnMainSync {ticker.run();check(!handler.hasCallbacks(ticker)) {"Closed preview re-armed its expiry ticker"}}
            passed("A closing/closed preview cannot re-arm the expiry ticker")

            val requests=AtomicInteger();val held=CountDownLatch(1);val disconnected=CountDownLatch(1)
            ServerSocket(0).use { server ->
                val sockets=java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
                val thread=Thread {
                    while(!server.isClosed) try {
                        val socket=server.accept();sockets+=socket
                        Thread {
                            socket.use {
                                it.soTimeout=10000
                                val reader=it.getInputStream().bufferedReader();val line=reader.readLine().orEmpty()
                                while(reader.readLine()?.takeIf {s->s.isNotEmpty()}!=null){}
                                if(line.contains("/hold")) {
                                    held.countDown()
                                    runCatching {if(it.getInputStream().read()==-1)disconnected.countDown()}
                                } else {
                                    requests.incrementAndGet()
                                    val bytes="{}".toByteArray();it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray()+bytes)
                                }
                            };sockets.remove(socket)
                        }.apply {isDaemon=true;start()}
                    } catch(_:Exception){}
                }.apply {isDaemon=true;start()}
                try {
                    val page=draft("""import {definePage} from '/framework/v1/page.js';import {native} from '/framework/v1/js/native.js';export default definePage({mount(ctx){ctx.root.innerHTML='<button id="hold">Hold</button><button id="slow">Slow</button>';ctx.root.querySelector('#hold').onclick=()=>native.fetchJson('http://127.0.0.1:${server.localPort}/hold').catch(()=>{});ctx.root.querySelector('#slow').onclick=()=>native.fetchJson('http://127.0.0.1:${server.localPort}/late').catch(()=>{});const query=document.querySelector.bind(document);document.querySelector=s=>{if(s==='#slow'){const end=Date.now()+9500;while(Date.now()<end){}}return query(s)}}});""")
                    open(page)
                    PluginPreviewManager.handle(JSONObject().put("op","click").put("selector","#hold"))
                    check(held.await(5,TimeUnit.SECONDS)) {"HTTP fixture never entered"}
                    HostRuntimeControl.stop()
                    check(disconnected.await(5,TimeUnit.SECONDS)) {"Tool stop did not cancel preview HTTP"}
                    passed("Explicit stop cancels an actual preview native HTTP request")
                    HostRuntimeControl.start(context);open(page)
                    val timed=PluginPreviewManager.handle(JSONObject().put("op","click").put("selector","#slow"))
                    check(!timed.getBoolean("ok") && state()=="closed") {"Timed-out DOM operation retained a usable session: $timed"}
                    Thread.sleep(2200)
                    check(requests.get()==0) {"Timed-out click sent a late HTTP request"}
                    passed("DOM timeout closes preview and prevents the late native request")
                } finally {server.close();sockets.forEach {runCatching {it.close()}};thread.join(1000)}
            }
        } finally {PluginPreviewManager.closeAll()}
    }

    private fun backgroundPreemption() {
        activity()
        val id="preemption-check-"+java.util.UUID.randomUUID().toString().take(8)
        val until=System.currentTimeMillis()+20000
        val bundle=JSONObject().put("manifest",JSONObject().put("apiVersion",1).put("id",id).put("title","Preemption fixture").put("version","1.0").put("kind","native").put("capabilities",JSONArray().put("plugin.background").put("plugin.notification")))
            .put("view",JSONObject().put("type","text").put("text","PREEMPTION_FIXTURE"))
            .put("code","export async function run({state}){await new Promise(r=>setTimeout(r,Math.max(0,$until-Date.now())));return {state:{runs:(state.runs||0)+1},view:{type:'text',text:'DONE'}}}")
        try {
            PluginPlatform.manage(context,JSONObject().put("action","install").put("bundle",bundle))
            PluginBackground.schedule(context,id,JSONObject().put("action","refresh").put("intervalMinutes",15))
            val record=PluginBackgroundStore(context).get(id)!!
            fun active():Boolean = (PluginBackgroundCoordinator::class.java.getDeclaredField("active").apply {isAccessible=true}.get(PluginBackgroundCoordinator) as Map<*,*>).containsKey(id)
            shell("cmd jobscheduler run -f ${context.packageName} ${record.jobId}")
            waitFor("Background fixture never started") {active()}
            shell("cmd jobscheduler stop -s 4 ${context.packageName} ${record.jobId}")
            waitFor("Preempted worker did not exit") {!active()}
            Thread.sleep(500)
            check(PluginBackground.status(context,id).getString("lastStatus")=="preempted")
            passed("Actual JobScheduler stop remains preempted after the worker exits")
            while(System.currentTimeMillis()<=until+1000)Thread.sleep(100)
            shell("cmd jobscheduler run -f ${context.packageName} ${record.jobId}")
            waitFor("Later periodic execution kept an old preempted terminal state") {PluginBackground.status(context,id).optString("lastStatus")=="ok"}
            passed("A later execution of the same schedule can complete normally")

        } finally {PluginPlatform.manage(context,JSONObject().put("action","remove").put("id",id))}
    }

    private fun notificationPermission(granted:Boolean) {
        val id="notification-check-1023"
        if(!granted) {
            val bundle=JSONObject().put("manifest",JSONObject().put("apiVersion",1).put("id",id).put("title","Notification fixture").put("version","1.0").put("kind","native").put("capabilities",JSONArray().put("plugin.notification")))
                .put("view",JSONObject().put("type","text").put("text","NOTIFICATION_FIXTURE"))
            PluginPlatform.manage(context,JSONObject().put("action","install").put("bundle",bundle))
        }
        val request=JSONObject().put("title","Synthetic permission test").put("body","No user content")
        if(!granted) {
            check(PluginBackground.notify(context,id,request).optString("reason")=="notification_permission")
            passed("Real OS permission denial returns without showing a notification")
            return
        }
        try {
            check(PluginBackground.notify(context,id,request).getBoolean("shown"))
            check(!PluginBackground.notify(context,id,request).getBoolean("shown"))
            val manager=context.getSystemService(android.app.NotificationManager::class.java)
            check(manager.activeNotifications.any {it.id==PluginBackgroundPolicy.jobId(id)})
            manager.cancel(PluginBackgroundPolicy.notificationTag(id), PluginBackgroundPolicy.jobId(id))
            passed("Grant after denial shows a real notification; second call is rate limited")
        } finally {PluginPlatform.manage(context,JSONObject().put("action","remove").put("id",id))}
    }
}
