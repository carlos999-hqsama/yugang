package com.personaldashboard.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * A real, isolated WebView for one installed plugin.
 *
 * This class never adds a JavaScript bridge. The host owns the only open entry point and its
 * [HostGestureAdmission] implementation must derive admission from a recent real UI gesture.
 */
class PluginSiteBrowser private constructor(
    private val appContext: Context,
    val view: WebView,
    private val profile: Profile,
    private val listener: Listener,
    private val startUrl: String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastCommittedUrl = AtomicReference(startUrl)
    private val opened = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)
    private var rendererGone = false
    private val cookieWriteLock = Any()
    private var downloadExecutor: ExecutorService? = null

    fun interface HostGestureAdmission {
        /**
         * Called synchronously on the main thread before a WebView exists. Return true only for a
         * gesture owned by the host UI; never for a boolean supplied by model output or JS.
         */
        fun admit(
            pluginId: String,
            url: String,
        ): Boolean
    }

    interface Listener {
        /** Exactly once, after the WebView is destroyed. Contains only opened/closed/finalUrl. */
        fun onClosed(receipt: JSONObject)

        /** A profile-authenticated download finished at a fixed MediaStore location. */
        fun onDownload(receipt: JSONObject) {}

        /** A navigation was refused because it was not HTTP(S). No target URL is echoed. */
        fun onBlocked(reason: String) {}

        /** Host chrome-only navigation metadata. The plugin receipt still contains only close data. */
        fun onNavigationChanged(url: String) {}

        /** Host chrome-only loading progress; this never reaches plugin code. */
        fun onLoadProgress(progress: Int) {}
    }

    /** WebView history first; when history is exhausted the current browser closes. */
    fun back(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { back() }
            return true
        }
        if (closed.get()) return false
        return if (view.canGoBack()) {
            view.goBack()
            true
        } else {
            close()
            false
        }
    }

    fun pause() {
        if (closed.get()) return
        mainHandler.post {
            if (!closed.get()) {
                profile.cookieManager.flush()
                view.onPause()
            }
        }
    }

    fun resume() {
        if (closed.get()) return
        mainHandler.post {
            if (!closed.get()) view.onResume()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(cookieWriteLock) { /* Drain native cookie writes before profile cleanup. */ }
        downloadExecutor?.shutdownNow()
        mainHandler.post {
            val receipt =
                PluginSiteBrowserPolicy.closeReceipt(opened.get(), if (rendererGone) lastCommittedUrl.get() else currentUrl())
            try {
                if (!rendererGone) view.stopLoading()
                (view.parent as? ViewGroup)?.removeView(view)
                try {
                    profile.cookieManager.flush()
                } catch (_: RuntimeException) {
                    // Renderer loss can race cookie persistence; profile deletion remains exact.
                }
                view.destroy()
            } catch (_: RuntimeException) {
                // A renderer-death race must not strand the pending close receipt.
            } finally {
                if (delivered.compareAndSet(false, true)) listener.onClosed(receipt)
            }
        }
    }

    fun currentUrl(): String =
        if (closed.get() || rendererGone) lastCommittedUrl.get() else try {
            PluginSiteBrowserPolicy.url(view.url)
        } catch (_: IllegalArgumentException) {
            lastCommittedUrl.get()
        }

    private fun rememberCommittedUrl(raw: String?) {
        try {
            lastCommittedUrl.set(PluginSiteBrowserPolicy.url(raw))
        } catch (_: IllegalArgumentException) {
            // Preserve the last legal HTTP(S) commit; rejected schemes never become finalUrl.
        }
    }

    private fun blocked(reason: String) {
        mainHandler.post {
            if (!closed.get()) listener.onBlocked(reason)
        }
    }

    private fun refusedResource(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            mapOf("Content-Length" to "0"),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun configureWebView(
        webView: WebView,
        cookieManager: CookieManager,
    ) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = false
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
        }
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean =
                    try {
                        PluginSiteBrowserPolicy.url(request.url.toString())
                        false
                    } catch (error: IllegalArgumentException) {
                        blocked(error.message ?: "站内浏览器仅支持 HTTP(S) 链接")
                        true
                    }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? =
                    try {
                        PluginSiteBrowserPolicy.url(request.url.toString())
                        null
                    } catch (_: IllegalArgumentException) {
                        refusedResource()
                    }

                override fun onPageStarted(
                    view: WebView,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    rememberCommittedUrl(url)
                    listener.onNavigationChanged(url.orEmpty())
                    listener.onLoadProgress(0)
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    rememberCommittedUrl(url)
                    listener.onNavigationChanged(url)
                    listener.onLoadProgress(100)
                }

                override fun doUpdateVisitedHistory(
                    view: WebView,
                    url: String?,
                    isReload: Boolean,
                ) {
                    rememberCommittedUrl(url)
                    listener.onNavigationChanged(url.orEmpty())
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    rendererGone = true
                    close()
                    return true
                }
            }

        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback,
                ) {
                    callback.invoke(origin, false, false)
                }

                override fun onProgressChanged(
                    view: WebView,
                    newProgress: Int,
                ) {
                    listener.onLoadProgress(newProgress.coerceIn(0, 100))
                }
            }

        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                startDownload(
                    url,
                    userAgent,
                    contentDisposition,
                    mimeType,
                )
            },
        )
    }

    private fun startDownload(
        rawUrl: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val url =
            try {
                PluginSiteBrowserPolicy.url(rawUrl)
            } catch (_: IllegalArgumentException) {
                blocked("下载地址必须是 HTTP(S) 链接")
                return
            }

        val executor =
            downloadExecutor
                ?: Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "yugang-plugin-site-download").apply { isDaemon = true }
                }.also { downloadExecutor = it }

        executor.execute {
            if (!Thread.currentThread().isInterrupted) {
                val result = download(url, userAgent, contentDisposition, mimeType)
                mainHandler.post {
                    if (!closed.get()) listener.onDownload(result)
                }
            }
        }
    }

    private fun download(
        startUrl: String,
        userAgent: String?,
        contentDisposition: String?,
        advertisedMime: String?,
    ): JSONObject =
        try {
            val connection = openDownloadConnection(startUrl, userAgent)
            val bytes = copyToMediaStore(connection, contentDisposition, advertisedMime)
            DownloadedFiles.receipt(
                appContext,
                bytes.first,
                bytes.second,
                bytes.third.mime,
                bytes.third.size,
            )
        } catch (error: Exception) {
            downloadFailure(error)
        }

    private data class DownloadTarget(
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long,
    )

    private fun openDownloadConnection(
        startUrl: String,
        userAgent: String?,
    ): Pair<HttpURLConnection, String> {
        var current = startUrl
        var redirects = 0

        while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
            val url = PluginSiteBrowserPolicy.url(current)
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = DOWNLOAD_TIMEOUT_MS.toInt()
            connection.readTimeout = DOWNLOAD_TIMEOUT_MS.toInt()
            safeHeaderValue(userAgent)?.let { connection.setRequestProperty("User-Agent", it) }
            profile.cookieManager.getCookie(url)?.let { cookie ->
                safeHeaderValue(cookie)?.let { connection.setRequestProperty("Cookie", it) }
            }
            connection.connect()

            val status = connection.responseCode
            synchronized(cookieWriteLock) {
                if (closed.get() || Thread.currentThread().isInterrupted) { connection.disconnect();throw IOException("下载已取消") }
                connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }.values.flatten().forEach {
                    profile.cookieManager.setCookie(url, it)
                }
            }
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                current =
                    try {
                        PluginSiteBrowserPolicy.redirectUrl(url, location)
                    } catch (_: IllegalArgumentException) {
                        throw IOException("下载跳转无效")
                    }
                if (++redirects > MAX_DOWNLOAD_REDIRECTS) throw IOException("下载跳转过多")
                continue
            }
            if (status !in 200..299) {
                connection.disconnect()
                throw IOException("下载失败")
            }
            return connection to url
        }
    }

    private fun copyToMediaStore(
        opened: Pair<HttpURLConnection, String>,
        contentDisposition: String?,
        advertisedMime: String?,
    ): Triple<Uri, String, DownloadTarget> {
        val (connection, finalUrl) = opened
        var pendingUri: Uri? = null
        try {
            val responseMime = connection.contentType?.substringBefore(';')?.trim().orEmpty()
            val mime = responseMime.ifBlank { advertisedMime.orEmpty() }
                .ifBlank { "application/octet-stream" }
            var name = PluginSiteBrowserPolicy.downloadName(contentDisposition, finalUrl)
            val contentLength =
                PluginSiteBrowserPolicy.contentLength(connection.getHeaderField("Content-Length"))
            val extension = PluginSiteBrowserPolicy.extension(mime)
            if (extension.isNotEmpty() && !name.substringAfterLast('.', "").equals(extension, true)) {
                name += ".$extension"
            }

            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOAD_DIRECTORY)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val resolver = appContext.contentResolver
            pendingUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建下载文件")

            var size = 0L
            resolver.openOutputStream(pendingUri, "w")?.use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted || closed.get()) throw IOException("下载已取消")
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (Thread.currentThread().isInterrupted || closed.get()) throw IOException("下载已取消")
                        val nextSize =
                            PluginSiteBrowserPolicy.downloadSize(
                                contentLength,
                                size + read.toLong(),
                            )
                        output.write(buffer, 0, read)
                        size = nextSize
                    }
                }
                output.flush()
                PluginSiteBrowserPolicy.downloadSize(contentLength, size)
            } ?: throw IOException("无法写入下载文件")

            val finished =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.SIZE, size)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
            check(resolver.update(pendingUri, finished, null, null) == 1) { "无法完成下载文件" }
            return Triple(
                pendingUri,
                name,
                DownloadTarget(pendingUri, name, mime, size),
            )
        } catch (error: Exception) {
            pendingUri?.let { appContext.contentResolver.delete(it, null, null) }
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun safeHeaderValue(value: String?): String? {
        val clean = value.orEmpty()
        if (clean.isEmpty() || clean.length > MAX_HEADER_CHARS) return null
        if (clean.any { it == '\r' || it == '\n' || it.isISOControl() }) return null
        return clean
    }

    private fun downloadFailure(error: Exception): JSONObject =
        JSONObject()
            .put("downloaded", false)
            .put(
                "reason",
                (error as? PluginSiteBrowserPolicy.DownloadSizeLimitException)?.message
                    ?: "下载失败，请重试",
            )

    companion object {
        private const val DOWNLOAD_TIMEOUT_MS = 30_000L
        private const val MAX_DOWNLOAD_REDIRECTS = 5
        private const val MAX_HEADER_CHARS = 8192
        private const val DOWNLOAD_DIRECTORY = "Download/鱼缸"

        /** True only when androidx.webkit multi-profile support is present. No global fallback. */
        fun isSupported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

        fun open(
            context: Context,
            pluginId: String,
            rawUrl: String,
            admission: HostGestureAdmission,
            listener: Listener,
            surface: PluginSiteBrowserSurface = PluginSiteBrowserSurface.INSTALLED,
        ): PluginSiteBrowser {
            check(Looper.myLooper() == Looper.getMainLooper()) { "站内浏览器只能在主线程打开" }
            require(surface == PluginSiteBrowserSurface.INSTALLED) {
                PluginSiteBrowserPolicy.previewRefusal()
            }
            PluginContract.id(pluginId)
            val url = PluginSiteBrowserPolicy.url(rawUrl)
            check(isSupported()) { "当前 WebView 不支持插件独立浏览器 profile" }
            check(admission.admit(pluginId, url)) { "站内浏览器需要用户真实触控确认" }

            val profileName = PluginSiteBrowserPolicy.profileName(pluginId)
            val profile = ProfileStore.getInstance().getOrCreateProfile(profileName)
            var webView: WebView? = null
            try {
                webView = WebView(context)
                WebViewCompat.setProfile(webView, profile.name)
                val browser =
                    PluginSiteBrowser(
                        context.applicationContext,
                        webView,
                        profile,
                        listener,
                        url,
                    )
                browser.configureWebView(webView, profile.cookieManager)
                webView.loadUrl(url)
                browser.opened.set(true)
                return browser
            } catch (error: Throwable) {
                webView?.destroy()
                throw error
            }
        }

        /**
         * Delete exactly this plugin's persistent profile. The callback runs on the main thread;
         * false means a WebView still uses it or the provider rejected deletion, so removal stays
         * pending and must be retried. Missing profiles count as successfully cleaned.
         */
        fun deleteProfile(
            pluginId: String,
            onResult: (Boolean) -> Unit,
        ) {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                try {
                    check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                    val name = PluginSiteBrowserPolicy.profileName(pluginId)
                    val store = ProfileStore.getInstance()
                    try {
                        // Do not getProfile first: that itself loads it and makes deletion illegal.
                        store.deleteProfile(name)
                        onResult(true)
                    } catch (_: IllegalStateException) {
                        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {onResult(false);return@post}
                        val profile = store.getProfile(name)
                        if (profile == null) onResult(true)
                        else WebStorageCompat.deleteBrowsingData(profile.webStorage,
                            java.util.concurrent.Executor { command -> handler.post(command) },
                            Runnable { onResult(true) })
                    }
                } catch (_: Exception) { onResult(false) }
            }
        }

    }
}

/** The browser is installed-page only. Preview is an explicit refusal, not a fallback mode. */
enum class PluginSiteBrowserSurface {
    INSTALLED,
    PREVIEW,
}
