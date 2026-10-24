package com.personaldashboard.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Clear one plugin origin, including localStorage which WebStorage.deleteOrigin leaves behind. */
internal object PluginBrowserStorage {
    fun clear(context: Context, id: String) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "插件存储清理不能阻塞界面" }
        val origin = "https://plugin-${PluginContract.id(id)}.pd.local"
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var web: WebView? = null
        var failure: String? = null
        handler.post {
            if (finished.get()) return@post
            try {
                web =
                    WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.blockNetworkLoads = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient =
                            object : WebViewClient() {
                                override fun onRenderProcessGone(
                                    view: WebView,
                                    detail: android.webkit.RenderProcessGoneDetail,
                                ): Boolean {
                                    finish("插件存储清理页面已退出，请重试")
                                    return true
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    fun poll() {
                                        if (finished.get()) return
                                        view.evaluateJavascript(
                                            "window.__pd_storage_clear || null",
                                        ) { result ->
                                            when (result) {
                                                "\"done\"" -> finish(null)
                                                "null" -> handler.postDelayed({ poll() }, 25)
                                                else -> finish("无法清理插件浏览器存储: $result")
                                            }
                                        }
                                    }
                                    poll()
                                }

                                private fun finish(error: String?) {
                                    if (!finished.compareAndSet(false, true)) return
                                    failure = error
                                    web?.destroy()
                                    done.countDown()
                                }
                            }
                        // Host-owned document: no plugin code, bridge, navigation or shared site
                        // deletion.
                        loadDataWithBaseURL(origin + "/", CLEAR_PAGE, "text/html", "UTF-8", null)
                    }
                val cookies = CookieManager.getInstance()
                cookies.getCookie(origin)?.split(';')?.forEach { cookie ->
                    val name = cookie.substringBefore('=').trim()
                    if (name.isNotEmpty()) {
                        cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure")
                        cookies.setCookie(
                            origin,
                            "$name=; Max-Age=0; Path=/; Domain=${android.net.Uri.parse(origin).host}; Secure",
                        )
                    }
                }
            } catch (_: Exception) {
                failure = "无法打开插件存储清理页面"
                finished.set(true)
                web?.destroy()
                done.countDown()
            }
        }
        val completed =
            try {
                done.await(10, TimeUnit.SECONDS)
            } finally {
                if (done.count > 0) {
                    finished.set(true)
                    handler.post { web?.destroy() }
                }
            }
        if (!completed) throw IOException("插件存储清理超时，请重试")
        failure?.let { throw IOException(it) }
    }

    private const val CLEAR_PAGE =
        """<!doctype html><script>
(async()=>{
  localStorage.clear(); sessionStorage.clear();
  await Promise.all((await indexedDB.databases()).filter(x=>x.name).map(x=>new Promise((resolve,reject)=>{
    const r=indexedDB.deleteDatabase(x.name); r.onsuccess=resolve; r.onerror=reject;
  })));
  if ('caches' in window) await Promise.all((await caches.keys()).map(x=>caches.delete(x)));
  window.__pd_storage_clear='done';
})().catch(e=>window.__pd_storage_clear=String(e));
</script>"""
}
