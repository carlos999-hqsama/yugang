package com.personaldashboard.app

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Device checks for the preview's storage close-out and request handling.
 *
 * Closing a preview must clear localStorage/sessionStorage on that exact preview origin and the
 * closed plugin page must not be able to write a draft back afterwards. The sweep is asserted with
 * a host owned probe WebView loaded on the captured origin, because a newly opened preview always
 * receives a different, empty origin and could never show a surviving draft. Only the dedicated
 * emulator may run this; the foreground page of the same plugin is the untouched sibling control.
 */
internal class PluginPreviewStorageDeviceChecks(private val runner: Instrumentation) {
    private fun passed(text: String) =
        runner.sendStatus(0, Bundle().apply { putString("stream", "PASS: $text\n") })

    private fun waitFor(text: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) {
            if (condition()) return
            Thread.sleep(100)
        }
        check(condition()) { text }
    }

    fun run() {
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator")) { "仅允许专用模拟器" }
        val app =
            runner.startActivitySync(
                android.content.Intent(runner.targetContext, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        val id = "preview-storage-" + java.util.UUID.randomUUID().toString().take(8)
        var probeView: WebView? = null
        try {
            // The host must have resumed before any preview can be admitted.
            waitFor("宿主页面未就绪，无法运行预览存储检查") {
                runCatching { previewResult(JSONObject().put("op", "status")) }
                    .getOrNull()?.optBoolean("appResumed") == true
            }
            PluginPlatform.manage(
                runner.targetContext,
                JSONObject().put("action", "install").put("bundle", bundle(id)),
            )
            val foreground = foregroundPage(app, id)
            eval(foreground, "localStorage.setItem('foreground-draft','KEEP');localStorage.length")
            check(eval(foreground, "localStorage.getItem('foreground-draft')") == "\"KEEP\"")
            passed("Foreground page of the same plugin holds its own draft on its own origin")

            val opened = previewResult(JSONObject().put("op", "open").put("id", id))
            check(opened.getBoolean("loaded")) { opened.toString() }
            val previewSession = session()
            val origin = previewSession.storageOrigin()
            check(origin.startsWith("https://plugin-$id-p")) {
                "预览没有使用独立 origin：$origin"
            }
            val previewWeb =
                PluginPreview::class.java.getDeclaredField("web").apply { isAccessible = true }
                    .get(previewSession) as WebView
            check(previewWeb.isAttachedToWindow) { "预览 WebView 未挂载" }

            val activeProbe = openProbe(origin)
            probeView = activeProbe
            val seeded =
                waitForState(activeProbe, origin, text = "精确 origin 探针读不到预览草稿") {
                    it.optBoolean("accessible") && it.optString("local") == "DRAFT"
                }
            passed("Probe on the captured origin reads the preview draft (${seeded.optString("href").take(160)})")

            check(previewResult(JSONObject().put("op", "close")).getBoolean("closed"))
            check(
                PluginPreviewManager.handle(JSONObject().put("op", "status")).getString("state") ==
                    "closed",
            )
            val cleared =
                waitForState(activeProbe, origin, 20_000, "关闭后预览 origin 仍然留有存储") {
                    it.optInt("localCount") == 0 &&
                        it.optInt("sessionCount") == 0 &&
                        it.isNull("local") &&
                        it.isNull("late") &&
                        it.isNull("unmount")
                }
            passed("Close cleared localStorage/sessionStorage on the captured origin")
            waitFor("关闭的预览没有释放 WebView", 10_000) { !previewWeb.isAttachedToWindow }

            Thread.sleep(800)
            val settled = probeState(activeProbe, origin) ?: error("清理后探针页面未响应")
            check(
                settled.optInt("localCount") == 0 &&
                    settled.optInt("sessionCount") == 0 &&
                    settled.isNull("late") &&
                    settled.isNull("unmount"),
            ) {
                "清理后旧页面又写回了草稿：$settled"
            }
            check(eval(foreground, "localStorage.getItem('foreground-draft')") == "\"KEEP\"") {
                "关闭预览清掉了前台插件 origin"
            }
            check(eval(foreground, "localStorage.getItem('preview-draft')") == "\"DRAFT\"") {
                "关闭预览清掉了同一插件的兄弟页面"
            }
            passed(
                "No late write restored a draft; foreground sibling kept its own storage" +
                    " (cleared=${cleared.optString("href").take(160)})",
            )

            // Malformed bridge arguments must be refused before dispatch, and a stream refusal must
            // stay on the stream channel instead of the ordinary call callback.
            val requestBundle = bundle("preview-request-check", REQUEST_FIXTURE)
            check(
                previewResult(
                    JSONObject().put("op", "open").put("packageJson", requestBundle.toString()),
                ).getBoolean("loaded"),
            )
            val requestWeb = session().let {
                PluginPreview::class.java.getDeclaredField("web").apply { isAccessible = true }
                    .get(it) as WebView
            }
            waitFor("预览请求检查夹具没有挂载") {
                eval(requestWeb, "!!window.__pd_request_probe") == "true"
            }
            waitFor("合法预览请求没有得到回调") {
                eval(requestWeb, "window.__pd_request_probe.replies['probe-good']") != "null"
            }
            val refused = eval(requestWeb, "window.__pd_request_probe.replies['probe-bad']")
            check(refused.contains("error") && refused.contains("不是合法 JSON")) {
                "畸形预览请求没有被安全拒绝：$refused"
            }
            check(!eval(requestWeb, "String(window.__pd_request_probe.ids)").contains("probe-sse")) {
                "SSE 通道错误走了普通 callback"
            }
            val stream = eval(requestWeb, "String(window.__pd_request_probe.sse)")
            check(stream.contains("error|") && stream.contains("不是合法 JSON")) {
                "SSE 通道没有收到拒绝：$stream"
            }
            check(
                eval(requestWeb, "window.__pd_request_probe.replies['probe-good']")
                    .contains("value"),
            ) {
                "合法请求被一起拒绝了"
            }
            passed("Malformed preview request refused safely; SSE refusal stayed on the stream channel")
            check(previewResult(JSONObject().put("op", "close")).getBoolean("closed"))
        } finally {
            runCatching { PluginPreviewManager.closeAll() }
            runCatching {
                PluginPlatform.manage(
                    runner.targetContext,
                    JSONObject().put("action", "remove").put("id", id),
                )
            }
            probeView?.let { web -> runCatching { runner.runOnMainSync { web.destroy() } } }
            runCatching { runner.runOnMainSync { app.openPlugin("harness") } }
        }
    }

    /** The host owned document the probe loads on the captured origin; it holds no plugin code. */
    private fun openProbe(origin: String): WebView {
        lateinit var web: WebView
        runner.runOnMainSync {
            web =
                WebView(runner.targetContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkLoads = true
                    loadDataWithBaseURL("$origin/", PROBE_PAGE, "text/html", "utf-8", null)
                }
        }
        waitFor("预览 origin 探针没有加载") {
            evalOrNull(web, "window.__pd_probe_ready===true") == "true"
        }
        return web
    }

    /**
     * Reads the captured origin from a freshly loaded probe document, so a renderer cache can never
     * decide the result; only the storage service may.
     */
    private fun probeState(probe: WebView, origin: String): JSONObject? {
        runner.runOnMainSync {
            runCatching {
                probe.loadDataWithBaseURL("$origin/", PROBE_PAGE, "text/html", "utf-8", null)
            }
        }
        val until = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < until) {
            val raw = evalOrNull(probe, PROBE_READ)
            if (raw != null && raw != "null") {
                val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                val state = inner?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (state != null && state.optBoolean("ready")) return state
            }
            Thread.sleep(50)
        }
        return null
    }

    private fun waitForState(
        probe: WebView,
        origin: String,
        timeout: Long = 10_000,
        text: String,
        expect: (JSONObject) -> Boolean,
    ): JSONObject {
        val until = System.currentTimeMillis() + timeout
        var last: JSONObject? = null
        while (System.currentTimeMillis() < until) {
            last = probeState(probe, origin)
            if (last != null && expect(last)) return last
            Thread.sleep(100)
        }
        error("$text：$last")
    }

    private fun session(): PluginPreview =
        PluginPreviewManager::class.java.getDeclaredField("preview").apply { isAccessible = true }
            .get(PluginPreviewManager) as? PluginPreview ?: error("预览会话不存在")

    private fun previewResult(request: JSONObject): JSONObject =
        PluginPreviewManager.handle(request).getJSONObject("result")

    private fun foregroundPage(app: MainActivity, id: String): WebView {
        var found: WebView? = null
        runner.runOnMainSync { app.openPlugin(id) }
        waitFor("前台插件页面缺失") {
            runner.runOnMainSync {
                found =
                    allViews(app.window.decorView)
                        .filterIsInstance<WebView>()
                        .firstOrNull { it.url?.contains("plugin-$id.pd.local") == true }
            }
            found != null
        }
        val web = found!!
        waitFor("前台夹具没有挂载") { eval(web, "window.__pd_fixture_ready===true") == "true" }
        return web
    }

    private fun allViews(view: View): List<View> {
        val children =
            if (view is ViewGroup) {
                (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) }
            } else {
                emptyList()
            }
        return listOf(view) + children
    }

    private fun eval(web: WebView, code: String): String =
        evalOrNull(web, code) ?: error("预览页面没有响应")

    private fun evalOrNull(web: WebView, code: String): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        runner.runOnMainSync {
            runCatching { web.evaluateJavascript(code) { value -> result = value; done.countDown() } }
                .onFailure { done.countDown() }
        }
        return if (done.await(3, TimeUnit.SECONDS)) result else null
    }

    private fun bundle(id: String, code: String = STORAGE_FIXTURE): JSONObject =
        JSONObject()
            .put(
                "manifest",
                JSONObject()
                    .put("apiVersion", 1)
                    .put("frameworkVersion", 1)
                    .put("id", id)
                    .put("title", "Preview storage fixture")
                    .put("version", "1.0")
                    .put("kind", "native")
                    .put("capabilities", JSONArray()),
            )
            .put(
                "view",
                JSONObject().put("type", "page").put("entry", "main.js").put("styles", JSONArray()),
            )
            .put("resources", JSONObject().put("main.js", code))

    private companion object {
        const val PROBE_PAGE =
            """<!doctype html><meta charset="utf-8"><title>Preview storage probe</title>""" +
                """<body>PROBE<script>window.__pd_probe_ready=true;</script></body>"""

        /**
         * Read through a freshly loaded document: `ready` marks the probe page, `accessible` proves
         * real storage access on the captured origin and the rest is the preview's own draft.
         */
        const val PROBE_READ =
            "window.__pd_probe_ready===true?JSON.stringify({ready:true," +
                "accessible:!!window.localStorage," +
                "local:localStorage.getItem('preview-draft')," +
                "late:localStorage.getItem('preview-late')," +
                "unmount:localStorage.getItem('preview-unmount')," +
                "localCount:localStorage.length,sessionCount:sessionStorage.length," +
                "href:location.href}):null"

        /** Writes a draft, keeps writing while it lives and writes again from its own unmount. */
        const val STORAGE_FIXTURE =
            """
import {definePage} from '/framework/v1/page.js';
export default definePage({
  mount(ctx){
    localStorage.setItem('preview-draft','DRAFT');
    sessionStorage.setItem('preview-draft','DRAFT');
    window.__pd_fixture_writer=setInterval(()=>{
      localStorage.setItem('preview-late',String(Date.now()));
      sessionStorage.setItem('preview-late',String(Date.now()));
    },25);
    window.__pd_fixture_ready=true;
    ctx.root.textContent='PREVIEW_STORAGE_FIXTURE';
  },
  unmount(){localStorage.setItem('preview-unmount','must-not-survive');}
});
"""

        /** Malformed arguments for a normal method and for a stream, plus a valid control request. */
        const val REQUEST_FIXTURE =
            """
import {definePage} from '/framework/v1/page.js';
import {on} from '/framework/v1/js/native.js';
export default definePage({
  mount(ctx){
    const probe={replies:{},sse:null,ids:[]};
    window.__pd_request_probe=probe;
    const sdk=window.__pd_cb;
    window.__pd_cb=(id,json)=>{probe.ids.push(id);probe.replies[id]=json;if(id!=='probe-bad')sdk(id,json);};
    on('sse:probe-sse',event=>{probe.sse=event.kind+'|'+String(event.error||'');});
    window.PDNative.call('getConfig','probe-bad','{not json');
    window.PDNative.call('sseOpen','probe-sse','[not json');
    window.PDNative.call('getConfig','probe-good','{}');
    ctx.root.textContent='PREVIEW_REQUEST_FIXTURE';
  }
});
"""
    }
}
