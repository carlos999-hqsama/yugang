package com.personaldashboard.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Host-owned native content-page chrome. Website contents never receive the host bridge, and the
 * surface is always mounted below [PluginShell]'s unchanged native rail.
 */
object PluginSiteBrowserHost {
    private val main = Handler(Looper.getMainLooper())
    private data class Entry(
        val owner: String,
        val pluginId: String,
        val activity: MainActivity,
        val browser: PluginSiteBrowser,
        val chrome: PluginSiteBrowserChrome,
    )

    private val entries = mutableSetOf<Entry>() // Main thread only.
    private const val PROFILE_MARKERS = "plugin-site-browser-profiles"

    fun open(
        activity: MainActivity,
        owner: String,
        pluginId: String,
        revision: String,
        args: JSONObject,
        admit: () -> Boolean,
        onClosed: (JSONObject) -> Unit,
        onDownload: (JSONObject) -> Unit = {},
    ) {
        val allowed = setOf("url", "title")
        require(args.keys().asSequence().all { it in allowed }) { "站内浏览器只接受 url 和 title" }
        val url = PluginSiteBrowserPolicy.openRequest(JSONObject().put("url", args.getString("url")))
        val title = args.optString("title", "站内浏览器").filterNot { it.isISOControl() }.take(80)
        main.post {
            if (activity.isFinishing || activity.isDestroyed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                onClosed(PluginSiteBrowserPolicy.closeReceipt(false, url).put("reason", "应用不在前台，未打开网站"))
                return@post
            }
            var browser: PluginSiteBrowser? = null
            var chrome: PluginSiteBrowserChrome? = null
            var entry: Entry? = null
            var shown = false
            try {
                PluginPlatform.withActiveRevision(activity, pluginId, revision) {
                    val bundle = PluginPlatform.store(activity).activeBundle(pluginId)
                    PluginPlatform.store(activity).requireCapability(pluginId, "plugin.browser")
                    require("plugin.browser" in PluginContract.caps(bundle)) { "插件未声明 plugin.browser" }
                    check(PluginSiteBrowser.isSupported()) { "当前系统 WebView 不支持独立登录空间，请更新 Android System WebView" }
                    // A failed construction may still have created a profile. Keep cleanup ownership.
                    check(
                        activity.getSharedPreferences(PROFILE_MARKERS, Context.MODE_PRIVATE)
                            .edit().putBoolean(pluginId, true).commit(),
                    ) { "无法记录网站登录空间" }
                    browser =
                        PluginSiteBrowser.open(
                            activity,
                            pluginId,
                            url,
                            PluginSiteBrowser.HostGestureAdmission { id, target ->
                                id == pluginId && target == url && admit()
                            },
                            object : PluginSiteBrowser.Listener {
                                override fun onClosed(receipt: JSONObject) {
                                    chrome?.detach()
                                    entry?.let { entries.remove(it) }
                                    onClosed(receipt.put("opened", receipt.optBoolean("opened") && shown))
                                }

                                override fun onDownload(receipt: JSONObject) {
                                    if (receipt.optBoolean("downloaded")) chrome?.addDownload(receipt)
                                    Toast.makeText(
                                        activity,
                                        if (receipt.optBoolean("downloaded"))
                                            "已保存：${receipt.optString("displayPath", "下载/鱼缸")}"
                                        else "下载未完成",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    onDownload(receipt)
                                }

                                override fun onBlocked(reason: String) {
                                    Toast.makeText(activity, reason, Toast.LENGTH_SHORT).show()
                                }

                                override fun onNavigationChanged(url: String) {
                                    chrome?.navigationChanged(url)
                                }

                                override fun onLoadProgress(progress: Int) {
                                    chrome?.setProgress(progress)
                                }
                            },
                        )
                    val current = requireNotNull(browser)
                    val currentChrome =
                        PluginSiteBrowserChrome(
                            activity = activity,
                            browser = current,
                            title = title,
                            insets = activity.siteBrowserInsets(),
                        )
                    check(activity.mountSiteBrowserSurface(currentChrome.view)) { "浏览器内容区不可用" }
                    chrome = currentChrome
                    entry =
                        Entry(owner, pluginId, activity, current, currentChrome).also { entries.add(it) }
                    shown = true
                }
            } catch (error: Exception) {
                chrome?.detach()
                val current = browser
                if (current != null) current.close()
                else
                    onClosed(
                        PluginSiteBrowserPolicy.closeReceipt(false, url)
                            .put("reason", error.message ?: "网站未打开"),
                    )
            }
        }
    }

    fun back(activity: MainActivity): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val current = entries.lastOrNull { it.activity === activity } ?: return false
        if (!current.chrome.consumeBack()) current.browser.back()
        return true
    }

    /** A rail navigation owns the next page. Remove chrome immediately, then deliver close receipts. */
    fun closeForNavigation(activity: MainActivity) {
        if (Looper.myLooper() == Looper.getMainLooper()) closeNow(activity)
        else main.post { closeNow(activity) }
    }

    private fun closeNow(activity: MainActivity) {
        entries.filter { it.activity === activity }.toList().forEach { entry ->
            entry.chrome.hideMenu()
            entry.chrome.detach()
            entry.browser.close()
        }
    }

    fun closeOwner(owner: String) {
        main.post { entries.filter { it.owner == owner }.toList().forEach { it.browser.close() } }
    }

    fun closePlugin(id: String) {
        main.post { entries.filter { it.pluginId == id }.toList().forEach { it.browser.close() } }
    }

    fun insets(activity: MainActivity, top: Int, bottom: Int, left: Int, right: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        entries.filter { it.activity === activity }.forEach { it.chrome.setInsets(top, bottom, left, right) }
    }

    fun themeChanged(activity: MainActivity) {
        if (Looper.myLooper() == Looper.getMainLooper())
            entries.filter { it.activity === activity }.forEach { it.chrome.applyTheme() }
        else main.post { entries.filter { it.activity === activity }.forEach { it.chrome.applyTheme() } }
    }

    fun onPause(activity: MainActivity) {
        main.post { entries.filter { it.activity === activity }.forEach { it.browser.pause() } }
    }

    fun onResume(activity: MainActivity) {
        main.post { entries.filter { it.activity === activity }.forEach { it.browser.resume() } }
    }

    fun onDestroy(activity: MainActivity) {
        main.post { closeNow(activity) }
    }

    /** Called from uninstall's worker after the revision has been invalidated. */
    fun clearPlugin(context: Context, id: String) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "浏览器清理不能阻塞界面" }
        val markers = context.getSharedPreferences(PROFILE_MARKERS, Context.MODE_PRIVATE)
        if (!markers.getBoolean(id, false)) return
        val done = CountDownLatch(1)
        var cleaned = false
        main.post {
            entries.filter { it.pluginId == id }.toList().forEach { it.browser.close() }
            fun waitForDestroy(remaining: Int) {
                if (entries.any { it.pluginId == id }) {
                    if (remaining > 0) main.postDelayed({ waitForDestroy(remaining - 1) }, 25)
                    else done.countDown()
                } else PluginSiteBrowser.deleteProfile(id) { result -> cleaned = result; done.countDown() }
            }
            waitForDestroy(200)
        }
        check(done.await(8, TimeUnit.SECONDS) && cleaned) { "插件浏览器登录空间未清理，请重试卸载" }
        check(markers.edit().remove(id).commit()) { "浏览器清理记录未保存，请重试" }
    }
}

/** Compact native toolbar chrome. It has no address entry and never styles site HTML/CSS. */
private class PluginSiteBrowserChrome(
    private val activity: MainActivity,
    private val browser: PluginSiteBrowser,
    title: String,
    insets: IntArray,
) {
    private data class DownloadRecord(val uri: Uri, val name: String, val bytes: Long)

    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val toolbarHeight = dp(46)
    private val records = mutableListOf<DownloadRecord>()
    private val actionButtons = mutableListOf<Button>()

    val view = FrameLayout(activity)
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val toolbar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private val progress = View(activity)
    private val menu = ScrollView(activity)
    private val menuBody = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val domain = TextView(activity)
    private val downloadList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val emptyDownloads = TextView(activity)
    private lateinit var downloadsTitle: TextView
    private val titleView = TextView(activity).apply {
        text = title
        textSize = 14f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        contentDescription = title
    }

    init {
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setPadding(dp(2), 0, dp(2), 0)
        toolbar.addView(
            action("返回") { browser.back() },
            LinearLayout.LayoutParams(dp(46), -1),
        )
        toolbar.addView(
            titleView,
            LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(2); marginEnd = dp(2) },
        )
        toolbar.addView(
            action("更多") { toggleMenu() },
            LinearLayout.LayoutParams(dp(42), -1),
        )
        toolbar.addView(
            action("关闭") { browser.close() },
            LinearLayout.LayoutParams(dp(46), -1),
        )

        content.addView(toolbar, LinearLayout.LayoutParams(-1, toolbarHeight))
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        content.addView(browser.view, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(content, FrameLayout.LayoutParams(-1, -1))

        domain.text = currentDomain()
        domain.textSize = 13f
        domain.typeface = android.graphics.Typeface.DEFAULT_BOLD
        domain.maxLines = 1
        domain.ellipsize = TextUtils.TruncateAt.MIDDLE
        domain.setPadding(dp(16), dp(12), dp(16), dp(10))
        menuBody.addView(domain)
        menuBody.addView(menuAction("刷新") {
            hideMenu()
            browser.view.reload()
        })
        downloadsTitle =
            TextView(activity).apply {
                text = "下载记录"
                textSize = 11.5f
                setPadding(dp(16), dp(12), dp(16), dp(6))
            }
        menuBody.addView(downloadsTitle)
        menuBody.addView(downloadList)
        emptyDownloads.text = "暂无下载"
        emptyDownloads.textSize = 12.5f
        emptyDownloads.setPadding(dp(16), 0, dp(16), dp(12))
        menuBody.addView(emptyDownloads)
        menu.isFillViewport = false
        menu.isVerticalScrollBarEnabled = false
        menu.addView(menuBody)
        menu.visibility = View.GONE
        menu.layoutParams =
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = toolbarHeight }
        view.addView(menu)

        setInsets(insets[0], insets[1], insets[2], insets[3])
        setProgress(0)
        applyTheme()
    }

    private fun action(label: String, click: () -> Unit): Button =
        Button(activity).apply {
            text = when(label) { "返回" -> "‹"; "更多" -> "···"; "关闭" -> "×"; else -> label }
            textSize = if(label in setOf("返回", "更多", "关闭")) 23f else 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            contentDescription = label
            setOnClickListener { click() }
        }.also { actionButtons.add(it) }

    private fun menuAction(label: String, click: () -> Unit): Button {
        val button = action(label, click)
        button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        button.setPadding(dp(16), 0, dp(16), 0)
        button.layoutParams = LinearLayout.LayoutParams(-1, dp(42))
        return button
    }

    private fun currentDomain(): String =
        try {
            Uri.parse(browser.currentUrl()).host?.take(160) ?: "当前网站"
        } catch (_: Exception) {
            "当前网站"
        }

    fun navigationChanged(url: String) {
        if (menu.visibility == View.VISIBLE) domain.text = currentDomain()
    }

    fun setProgress(value: Int) {
        val bounded = value.coerceIn(0, 100)
        if (bounded in 1..99) {
            progress.visibility = View.VISIBLE
            progress.pivotX = 0f
            progress.scaleX = bounded / 100f
        } else if (bounded == 0) {
            progress.visibility = View.VISIBLE
            progress.pivotX = 0f
            progress.scaleX = 0.015f
        } else {
            progress.visibility = View.INVISIBLE
            progress.scaleX = 1f
        }
    }

    fun addDownload(receipt: JSONObject) {
        val uri = Uri.parse(receipt.optString("uri"))
        if (uri.scheme != "content") return
        records.add(0, DownloadRecord(uri, receipt.optString("name"), receipt.optLong("bytes")))
        while (records.size > 20) records.removeAt(records.size - 1)
        renderDownloads()
        applyTheme()
    }

    private fun renderDownloads() {
        for (i in 0 until downloadList.childCount) actionButtons.remove(downloadList.getChildAt(i))
        downloadList.removeAllViews()
        records.forEach { record ->
            val label =
                if (record.bytes > 0) "${record.name} · ${downloadSize(record.bytes)}" else record.name
            downloadList.addView(
                menuAction("打开 $label") { openDownload(record) },
            )
        }
        emptyDownloads.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun downloadSize(bytes: Long): String =
        when {
            bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024f / 1024f)
            bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }

    private fun openDownload(record: DownloadRecord) {
        val result =
            runCatching {
                DownloadedFiles.open(activity, JSONObject().put("uri", record.uri.toString()))
            }.getOrElse {
                JSONObject().put("opened", false).put("reason", "下载文件已不可用")
            }
        Toast.makeText(
            activity,
            if (result.optBoolean("opened")) "已打开：${record.name}" else result.optString("reason", "文件暂时无法打开"),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun toggleMenu() {
        if (menu.visibility == View.VISIBLE) hideMenu()
        else {
            domain.text = currentDomain()
            renderDownloads()
            menu.visibility = View.VISIBLE
        }
    }

    fun consumeBack(): Boolean {
        if (menu.visibility != View.VISIBLE) return false
        hideMenu()
        return true
    }

    fun hideMenu() {
        menu.visibility = View.GONE
    }

    fun setInsets(top: Int, bottom: Int, left: Int, right: Int) {
        view.layoutParams =
            FrameLayout.LayoutParams(-1, -1).apply {
                leftMargin = (MainActivity.NAV_RAIL_WIDTH_DP * density).toInt() + left
                rightMargin = right
                topMargin = top
                bottomMargin = bottom
            }
    }

    fun applyTheme() {
        val dark =
            (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val surface = if (dark) Color.rgb(17, 17, 20) else Color.WHITE
        val chromeColor = if (dark) Color.rgb(24, 24, 27) else Color.rgb(250, 250, 251)
        val text = if (dark) Color.rgb(240, 240, 242) else Color.rgb(29, 29, 31)
        val secondary = if (dark) Color.rgb(168, 168, 174) else Color.rgb(112, 112, 120)
        val border = if (dark) Color.rgb(48, 48, 53) else Color.rgb(226, 226, 231)
        val line = if (dark) Color.WHITE else Color.rgb(29, 29, 31)

        view.setBackgroundColor(chromeColor)
        toolbar.setBackgroundColor(chromeColor)
        titleView.setTextColor(text)
        titleView.contentDescription = titleView.text
        progress.setBackgroundColor(line)
        menu.background =
            GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(2).toFloat()
                setStroke(dp(1), border)
            }
        menu.elevation = dp(8).toFloat()
        domain.setTextColor(text)
        downloadsTitle.setTextColor(secondary)
        emptyDownloads.setTextColor(secondary)
        actionButtons.forEach { it.setTextColor(text) }
    }

    fun detach() {
        hideMenu()
        (view.parent as? ViewGroup)?.removeView(view)
    }
}
