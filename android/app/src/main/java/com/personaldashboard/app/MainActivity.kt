package com.personaldashboard.app

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * 薄壳：一个全屏 WebView 加载 assets/www（设计稿的 HTML/CSS 原样复用）， 网络、密钥、内置浏览器、剪贴板、语音、DSHBox 跳转等由原生提供。
 * configChanges 覆盖折叠/展开，Activity 不重建，JS 状态保留，页面按宽度 600dp 切换内屏/外屏布局。
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var cfg: Config
    private lateinit var net: Net
    private lateinit var panes: Panes
    internal val pluginVoice by lazy { PluginVoice(this) }
    var coreVisible = true
        private set

    val paneOffsetX
        get() = (NAV_RAIL_WIDTH_DP * resources.displayMetrics.density).toInt()

    internal val hostUi=PluginHostUi(this)
    private var pendingSharedIntent: Intent?=null
    private var pageReady = false
    private var pendingCoreRoute: String? = null
    private lateinit var pluginShell: PluginShell

    fun openPlugin(id: String) {
        pluginShell.open(id)
    }

    fun showSharedBot(id: String,text: String,uris: List<Uri>){
        showCore("harness")
        Thread({val staged=runCatching{botAttachments.stage(uris)}.getOrDefault(org.json.JSONArray())
            runOnUiThread{emit("shareDraft",JSONObject().put("botId",id).put("text",text).put("files",staged))}
        },"bot-share-import").start()
    }

    fun openPluginManager() {
        pluginShell.showManager()
    }

    private var pendingPair: String? = null
    private var pendingPlugin: String? = null
    private var lastInsets = JSONObject()
    private var siteBrowserInsets = intArrayOf(0, 0, 0, 0)
    private var fileCb: ValueCallback<Array<Uri>>? = null
    internal val botAttachments by lazy { BotAttachmentTransfer(this, cfg) { botId, sessionId -> requireBotSession(botId, sessionId) } }
    private var botFileCallback: ((List<Uri>) -> Unit)? = null
    private val pickBotFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val callback = botFileCallback
        botFileCallback = null
        callback?.invoke(uris)
    }

    fun chooseBotFiles(callback: (List<Uri>) -> Unit) {
        check(botFileCallback == null) { "文件选择器已打开" }
        botFileCallback = callback
        try { pickBotFiles.launch("*/*") }
        catch (error: Exception) { botFileCallback = null;throw error }
    }

    /** Validate the native receiver before uploads and before resolving a chat file. */
    internal fun requireBotSession(botId: String, sessionId: String?): String {
        val bots = BotWorkspaces.get(this).call(JSONObject().put("op", "list")).getJSONArray("bots")
        val bot = (0 until bots.length()).map { bots.getJSONObject(it) }.firstOrNull { it.getString("id") == botId }
            ?: error("Bot 已不存在")
        if (sessionId != null) {
            val sessions = bot.getJSONArray("sessionIds")
            check((0 until sessions.length()).any { sessions.getString(it) == sessionId } || bot.optString("selectedSessionId") == sessionId) { "会话不属于此 Bot" }
        }
        return bot.getString("workspace")
    }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            // MODE_OPEN_MULTIPLE can return only ClipData, even for a single photo.
            val selected =
                if (r.resultCode == RESULT_OK) {
                    val clip = r.data?.clipData
                    if (clip != null && clip.itemCount > 0)
                        (0 until clip.itemCount)
                            .mapNotNull { clip.getItemAt(it).uri }
                            .toTypedArray()
                            .takeIf { it.isNotEmpty() }
                    else WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
                } else null
            val callback = fileCb
            fileCb = null
            callback?.onReceiveValue(selected)
        }
    private var pluginImageCallback: ((List<Uri>) -> Unit)? = null
    private val pickPluginImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val callback = pluginImageCallback
            pluginImageCallback = null
            callback?.invoke(uris)
        }

    fun choosePluginFiles(params: WebChromeClient.FileChooserParams,callback: (List<Uri>) -> Unit){
        check(pluginImageCallback==null){"文件选择器已打开"}
        pluginImageCallback=callback
        try { val types=params.acceptTypes.filter{it.isNotBlank()};pickPluginImages.launch(if(types.size==1)types.first() else "*/*") }
        catch(_:ActivityNotFoundException){pluginImageCallback=null;callback(emptyList())}
    }

    fun choosePluginImages(callback: (List<Uri>) -> Unit) {
        pluginImageCallback = callback
        try {
            pickPluginImages.launch("image/*")
        } catch (_: ActivityNotFoundException) {
            pluginImageCallback = null
            callback(emptyList())
        }
    }

    private val pickPluginPackage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) pluginShell.installPackage(uri)
        }

    fun choosePluginPackage() {
        try {
            pickPluginPackage.launch("application/json")
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "未找到文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private val voice =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val text =
                r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            emit("voice", JSONObject().put("text", text.orEmpty()))
        }

    internal fun applySystemInsets(ins: WindowInsetsCompat): WindowInsetsCompat {
        val d = resources.displayMetrics.density
        val bars =
            ins.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
        val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
        siteBrowserInsets = intArrayOf(bars.top, maxOf(bars.bottom, ime.bottom), bars.left, bars.right)
        pluginShell.insets(bars.top, maxOf(bars.bottom, ime.bottom), bars.left, bars.right)
        lastInsets =
            JSONObject()
                .put("top", bars.top / d)
                .put("left", bars.left / d)
                .put("right", bars.right / d)
                .put("bottom", maxOf(bars.bottom, ime.bottom) / d)
                .put("ime", ime.bottom > 0)
        if (pageReady) emit("insets", lastInsets)
        return ins
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
            navigationBarStyle =
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
        )
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        net = Net(cfg) { ch, v -> emit(ch, v) }
        root = FrameLayout(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        setContentView(root)
        panes = Panes(this, root, net, cfg)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        bridge = Bridge(this, cfg, net, panes, trustedAgentVoiceSource = true)
        setupMainWeb()
        pluginShell = PluginShell(this, root, cfg, panes) { tab -> showCore(tab) }
        // 插件 Bot 的独立预览会话复用同一套页面渲染与原生通道，只借用当前 Activity 的窗口。
        PluginPreviewManager.attach(this, cfg, panes)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins -> applySystemInsets(ins) }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (pluginShell.siteBrowserBack()) return
                    if (pluginShell.back()) return
                    if (panes.back()) return
                    web.evaluateJavascript("(window.__pd_back && window.__pd_back()) ? 1 : 0") { r
                        ->
                        if (r != "1") moveTaskToBack(true)
                    }
                }
            },
        )

        handleIntent(intent)
        Runtime.addListener(rtListener)
        AppControl.addListener(appSettingsListener)
        PluginPlatform.addListener(pluginListener)
        if (cfg.embedded) DshService.start(this)
    }

    /** 内置运行时状态 → 页面（Harness 内联显示「正在启动 Harness…」/ 进度 / 错误） */
    private val rtListener: (JSONObject) -> Unit = { j ->
        if (pageReady) emit("runtime", j)
    }
    /** 原生 AppControl 成功提交三项设置后转发；事件不依赖页面当时是否存活。 */
    private val pluginListener: () -> Unit = {
        if (pageReady) emit("pluginsChanged", JSONObject())
    }
    private val appSettingsListener: (JSONObject) -> Unit = { j ->
        if (pageReady) emit("appSettings", j)
    }

    override fun onResume() {
        super.onResume()
        PluginSiteBrowserHost.onResume(this)
        PluginNotificationPermission.onResume(this)
        PluginPreviewManager.onResumed()
        Thread(
                {
                    runCatching {
                        PluginTransfers.recover(applicationContext)
                        PluginPlatform.recoverPendingRemovals(applicationContext)
                        val sync = PluginBackground.reconcile(applicationContext)
                        if (sync.optInt("errors") > 0)
                            android.util.Log.w(
                                "PluginBackground",
                                sync.optString("error", "后台计划暂未恢复"),
                            )
                    }
                        .onFailure { android.util.Log.w("PluginBackground", "插件清理或后台计划暂未恢复") }
                },
                "plugin-lifecycle-recovery",
            )
            .start()
        if (::pluginShell.isInitialized) pluginShell.resume()
        // 用户从通知里停止过：回到 App 时自动再启动
        if (cfg.embedded && Runtime.state in setOf("stopped", "idle")) DshService.start(this)
        if (pageReady) emit("resume", JSONObject())
    }

    override fun onPause() {
        PluginSiteBrowserHost.onPause(this)
        PluginNotificationPermission.onPause(this)
        PluginPreviewManager.onPaused()
        if (::pluginShell.isInitialized) pluginShell.pause()
        if (cfg.embedded && cfg.keepAwakeWhileRunning) DshService.protectPendingTask()
        super.onPause()
    }

    private lateinit var bridge: Bridge
    private val loader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    /** 创建主 WebView。渲染进程崩溃/被系统回收时会重建它（否则整个 App 会被一起杀掉）。 */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupMainWeb() {
        pageReady = false
        web = WebView(this)
        root.addView(
            web,
            0,
            FrameLayout.LayoutParams(-1, -1).apply {
                leftMargin = (NAV_RAIL_WIDTH_DP * resources.displayMetrics.density).toInt()
            },
        )
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
        }
        web.setBackgroundColor(0xFFFFFFFF.toInt())
        web.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    req: WebResourceRequest,
                ): WebResourceResponse? =
                    if (req.url.host == Panes.FILE_HOST && req.method == "GET")
                        panes.fetchDshFile(Uri.decode(req.url.encodedPath ?: "/")) // 对话里的图片缩略图
                    else loader.shouldInterceptRequest(req.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    req: WebResourceRequest,
                ): Boolean {
                    if (req.url.host == APP_HOST) return false
                    if (req.hasGesture()) openInApp(req.url.toString())
                    return true // 主界面永不离开本地资源
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    android.util.Log.w(
                        TAG,
                        "main renderer gone, crash=${detail.didCrash()}; recreating",
                    )
                    root.removeView(view)
                    view.destroy()
                    net.closeAll()
                    panes.hideAll()
                    root.post { if (!isFinishing && !isDestroyed) setupMainWeb() }
                    return true
                }
            }
        web.webChromeClient =
            object : WebChromeClient() {
                override fun onShowFileChooser(
                    v: WebView,
                    cb: ValueCallback<Array<Uri>>,
                    p: FileChooserParams,
                ): Boolean {
                    fileCb?.onReceiveValue(null)
                    fileCb = cb
                    return try {
                        pickFiles.launch(p.createIntent())
                        true
                    } catch (_: ActivityNotFoundException) {
                        fileCb = null
                        false
                    }
                }
            }
        web.addJavascriptInterface(bridge, "PDNative")
        // 仅调试版：用 `am start --ez pd_blank true` 加载空白页，排查 WebView 渲染进程问题是否与页面有关
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("pd_blank", false) == true)
            web.loadData("<h1>blank</h1>", "text/html", "utf-8")
        else web.loadUrl("https://$APP_HOST/assets/www/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** DSHBox 的「用外部浏览器打开」→ 选择本应用 → 自动配对 */
    private fun handleIntent(i: Intent?) {
        if(i!=null&&i.action in setOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)){
            if(pageReady)PluginShareInbox.receive(this,i) else pendingSharedIntent=i
            return
        }
        if(i?.data?.let { PluginConnections.callback(this,it) }==true)return
        if (i?.action == "com.personaldashboard.app.OPEN_PLUGIN") {
            val id =
                runCatching { PluginContract.id(i.getStringExtra("pluginId").orEmpty()) }
                    .getOrNull() ?: return
            if (pageReady) openPlugin(id) else pendingPlugin = id
            return
        }
        val d = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW || d.scheme != "http") return
        val s = d.toString()
        if (!Net.isAutoPairLink(s) || Net.parseToken(s) == null || cfg.embedded)
            return // 内置模式自动登录，不接受外部启动链接
        if (pageReady) pairFromLink(s) else pendingPair = s
    }

    private fun pairFromLink(link: String) {
        Thread {
            val res =
                try {
                    net.pair(link)
                    JSONObject().put("ok", true)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message)
                }
            runOnUiThread {
                panes.resetDshWeb()
                emit("paired", res)
                if (res.optBoolean("ok")) emit("navigate", JSONObject().put("tab", "harness"))
            }
        }
            .start()
    }

    fun hideCoreForPlugin() {
        coreVisible = false
        panes.hideAll()
        web.visibility = android.view.View.INVISIBLE
        if (pageReady)
            web.evaluateJavascript(
                "window.__pd_plugin_surface && window.__pd_plugin_surface()",
                null,
            )
    }

    fun showLicenses() {
        val notice = assets.open("third-party-notices.html").bufferedReader().use { it.readText() }
        FilePreviewDialog(this).show(FileTextContent("text/html", "开源许可证", notice, notice.toByteArray().size))
    }

    private fun showCore(tab: String) {
        coreVisible = true
        web.visibility = android.view.View.VISIBLE
        if (!pageReady) {
            pendingCoreRoute = tab
            return
        }
        web.evaluateJavascript(
            "window.__pd_go && window.__pd_go(${JSONObject.quote(tab)})",
            null,
        )
    }

    fun onPageReady() {
        pageReady = true
        val requestedRoute = pendingCoreRoute
        pendingCoreRoute = null
        pluginShell.ready()
        pendingSharedIntent?.let { shared -> pendingSharedIntent=null;root.post {PluginShareInbox.receive(this,shared)} }
        if (requestedRoute != null && coreVisible) showCore(requestedRoute)
        emit("insets", lastInsets)
        emit("runtime", Runtime.status(this))
        pendingPair?.let {
            pendingPair = null
            pairFromLink(it)
        }
        pendingPlugin?.let {
            pendingPlugin = null
            openPlugin(it)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 折叠/展开：WebView 尺寸变化后页面自行切换布局并重新上报覆盖层矩形
        web.post { ViewCompat.requestApplyInsets(root) }
        if (::pluginShell.isInitialized) pluginShell.siteBrowserThemeChanged()
    }

    internal fun siteBrowserInsets(): IntArray = siteBrowserInsets.copyOf()

    internal fun mountSiteBrowserSurface(surface: android.view.View): Boolean =
        ::pluginShell.isInitialized && pluginShell.mountSiteBrowserSurface(surface)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (
            ::pluginShell.isInitialized &&
                level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        )
            pluginShell.trimHiddenPages()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        PluginNotificationPermission.onRequestPermissionsResult(requestCode, permissions, grantResults)
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        hostUi.close()
        PluginSiteBrowserHost.onDestroy(this)
        PluginNotificationPermission.onDestroy(this)
        PluginPreviewManager.detachIfOwner(this)
        pluginShell.close()
        panes.close()
        fileCb?.onReceiveValue(null)
        fileCb = null
        pluginImageCallback?.invoke(emptyList())
        pluginImageCallback = null
        botFileCallback?.invoke(emptyList())
        botFileCallback = null
        botAttachments.close()
        Runtime.removeListener(rtListener)
        AppControl.removeListener(appSettingsListener)
        PluginPlatform.removeListener(pluginListener)
        bridge.close()
        net.dispose()
        pageReady = false
        web.stopLoading()
        web.removeJavascriptInterface("PDNative")
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
    }

    // ---------------------------------------------------------------- 给 Bridge / Panes 用
    fun publicConfig(): JSONObject =
        cfg.public(
                BuildConfig.VERSION_NAME,
                packageManager.getLaunchIntentForPackage(DSHBOX) != null,
            )
            .put(
                "backgroundNotifications",
                getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            )
            .put(
                "batteryExempt",
                getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(packageName),
            )

    fun openBackgroundSettings(which: String): Boolean {
        val i =
            when (which) {
                "notifications" ->
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                "battery" ->
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    )
                else -> throw IllegalArgumentException("未知后台设置")
            }
        startActivity(i)
        return true
    }

    fun reply(id: String, v: JSONObject) {
        val js = "window.__pd_cb(${JSONObject.quote(id)}, ${JSONObject.quote(v.toString())})"
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript(js, null) }
    }

    fun emit(ch: String, v: JSONObject) {
        val js =
            "window.__pd_ev && window.__pd_ev(${JSONObject.quote(ch)}, ${JSONObject.quote(v.toString())})"
        runOnUiThread {
            if (!isDestroyed) {
                web.evaluateJavascript(js, null)
                if (::pluginShell.isInitialized) pluginShell.emit(ch, v)
            }
        }
    }

    fun openInApp(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return openExternal(url)
        return try {
            PluginSiteBrowserHost.openCore(this, "core-links", JSONObject().put("url", url), { result ->
                if (!result.optBoolean("opened")) Toast.makeText(this, result.optString("reason", "网页未打开"), Toast.LENGTH_LONG).show()
            })
            true
        } catch (_: Exception) { Toast.makeText(this, "链接无效", Toast.LENGTH_SHORT).show();false }
    }

    fun openExternal(url: String): Boolean {
        val u = Uri.parse(url)
        if (u.scheme !in setOf("http", "https", "mailto", "intent")) return false
        return try {
            val i =
                if (u.scheme == "intent")
                    Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        component = null
                        selector = null
                    }
                else Intent(Intent.ACTION_VIEW, u)
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Toast.makeText(this, "没有可以打开该链接的应用", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun openDshbox(): Boolean {
        val i = packageManager.getLaunchIntentForPackage(DSHBOX) ?: return false
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun startVoice(): Boolean {
        val i =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要发送的内容")
        return try {
            voice.launch(i)
            true
        } catch (e: ActivityNotFoundException) {
            throw RuntimeException("系统没有可用的语音识别服务", e)
        }
    }

    companion object {
        const val APP_HOST = "appassets.androidplatform.net"
        const val DSHBOX = "com.dshbox.app"
        internal const val NAV_RAIL_WIDTH_DP = 64
        const val TAG = "PD"
    }
}
