package com.personaldashboard.app

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Native platform navigation plus native plugin surface. Plugin packages own their UI and
 * executable modules.
 */
class PluginShell(
    private val activity: MainActivity,
    private val root: FrameLayout,
    private val cfg: Config,
    private val panes: Panes,
    private val showCore: (String) -> Unit,
) {
    private val io = Executors.newFixedThreadPool(2)
    private val store = PluginPlatform.store(activity)
    private val rail =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 248, 248))
        }
    private val railScroll =
        ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(rail)
        }
    private val panel =
        ScrollView(activity).apply {
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
    private var page: PluginPage? = null

    private data class CachedPage(val revision: String, val page: PluginPage)

    private val pages = linkedMapOf<String, CachedPage>()
    private var topInset = 0
    private var bottomInset = 0
    private var leftInset = 0
    private var rightInset = 0
    private var current = "harness"
    private var showingOnboarding = false
    private var generation = 0
    private var closed = false
    private var busy = false
    private var resumed = true
    private var renderer: NativePluginRenderer? = null
    private var activeCall: PluginCancellation? = null
    private val mediaCalls = mutableSetOf<PluginCancellation>()
    private var mediaDialog: AlertDialog? = null
    private var inputKey = ""
    private val inputDrafts = linkedMapOf<String, JSONObject>()
    private var refreshAction = ""
    private var refreshSeconds = 15
    private val poll = Runnable {
        if (!closed && resumed && panel.visibility == View.VISIBLE && refreshAction.isNotEmpty()) {
            if (!busy && renderer?.editing() != true && !panel.isPressed && mediaDialog == null)
                perform(refreshAction, renderer?.values() ?: JSONObject(), automatic = true)
            else schedulePoll()
        }
    }

    private fun schedulePoll() {
        panel.removeCallbacks(poll)
        if (resumed && refreshAction.isNotEmpty()) panel.postDelayed(poll, refreshSeconds * 1000L)
    }

    private fun leaveSurface() {
        panel.isFillViewport = false
        page?.let {
            it.pause()
            it.view.visibility = View.GONE
        }
        page = null
        if (inputKey.isNotEmpty())
            renderer?.values()?.let {
                inputDrafts[inputKey] = it
                while (inputDrafts.size > 32) inputDrafts.remove(inputDrafts.keys.first())
            }
        inputKey = ""
        panel.removeCallbacks(poll)
        refreshAction = ""
        activeCall?.cancel()
        activeCall = null
        mediaCalls.forEach { it.cancel() }
        mediaCalls.clear()
        mediaDialog?.dismiss()
        mediaDialog = null
        renderer = null
    }

    fun pause() {
        resumed = false
        panel.removeCallbacks(poll)
        page?.pause()
    }

    fun resume() {
        resumed = true
        schedulePoll()
        page?.resume()
    }

    fun emit(channel: String, value: JSONObject) {
        page?.emit(channel, value)
    }

    private var viewKey = ""
    private var backAction = ""
    private var currentRevision = ""
    private val scrolls = linkedMapOf<String, Int>()

    private fun saveScroll() {
        if (viewKey.isNotEmpty()) {
            scrolls[viewKey] = panel.scrollY
            while (scrolls.size > 128) scrolls.remove(scrolls.keys.first())
        }
    }

    val width = (MainActivity.NAV_RAIL_WIDTH_DP * activity.resources.displayMetrics.density).toInt()
    private val listener: () -> Unit = {
        activity.runOnUiThread {
            if (!closed) {
                for ((id, cached) in pages.toMap()) {
                    if (runCatching { store.requireRevision(id, cached.revision) }.isFailure) {
                        PluginSiteBrowserHost.closePlugin(id)
                        pages.remove(id)
                        if (page === cached.page) page = null
                        cached.page.close(
                            permanentlyRemoved = runCatching { store.read(id) }.isFailure,
                        )
                    }
                }
                val wasOnboarding = showingOnboarding
                refresh()
                val plugins = store.list().getJSONArray("plugins")
                if (wasOnboarding && plugins.length() > 0) {
                    val installed =
                        (0 until plugins.length())
                            .map { plugins.getJSONObject(it) }
                            .firstOrNull { it.getBoolean("enabled") }
                            ?.getString("id")
                    open(installed ?: "harness")
                } else if (current == "plugins") {
                    manage()
                } else if (
                    current !in setOf("harness", "settings") &&
                        runCatching { store.requireRevision(current, currentRevision) }.isFailure
                )
                    open(current)
            }
        }
    }

    init {
        root.addView(
            panel,
            FrameLayout.LayoutParams(-1, -1).apply { leftMargin = this@PluginShell.width },
        )
        root.addView(railScroll, FrameLayout.LayoutParams(width, -1, Gravity.START))
        PluginPlatform.addListener(listener)
        refresh()
    }

    fun insets(top: Int, bottom: Int, left: Int, right: Int) {
        topInset = top
        bottomInset = bottom
        leftInset = left
        rightInset = right
        rail.setPadding(0, top, 0, bottom)
        panel.setPadding(0, top, 0, bottom)
        railScroll.layoutParams =
            FrameLayout.LayoutParams(width, -1, Gravity.START).apply {
                leftMargin = left
            }
        panel.layoutParams =
            FrameLayout.LayoutParams(-1, -1).apply {
                leftMargin = this@PluginShell.width + left
                rightMargin = right
            }
        pages.values.forEach { it.page.view.layoutParams = pageParams() }
        PluginSiteBrowserHost.insets(activity, top, bottom, left, right)
    }

    private fun pageParams() =
        FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = this@PluginShell.width + leftInset
            rightMargin = rightInset
            topMargin = topInset
            bottomMargin = bottomInset
        }

    /** Site browsers are content surfaces below the unchanged native rail, never modal windows. */
    fun mountSiteBrowserSurface(surface: View): Boolean {
        if (closed) return false
        root.addView(surface, root.indexOfChild(railScroll))
        railScroll.bringToFront()
        return true
    }

    fun siteBrowserBack(): Boolean = PluginSiteBrowserHost.back(activity)

    fun closeSiteBrowserForNavigation() {
        PluginSiteBrowserHost.closeForNavigation(activity)
    }

    fun siteBrowserThemeChanged() {
        PluginSiteBrowserHost.themeChanged(activity)
    }

    private fun button(title: String, click: () -> Unit) =
        Button(activity).apply {
            text = title
            textSize = 11f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(2, 4, 2, 4)
            setOnClickListener { click() }
        }

    private fun navButton(title: String, key: String, click: () -> Unit): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val label =
            if (title.codePointCount(0, title.length) > 4)
                title.substring(0, title.offsetByCodePoints(0, 3)) + "…"
            else title
        val selected = current == key || key == "settings" && current == "plugins"
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams =
                LinearLayout.LayoutParams(-1, dp(62)).apply {
                    setMargins(dp(5), dp(3), dp(5), dp(3))
                }
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (selected) Color.rgb(232, 232, 232) else Color.TRANSPARENT)
                }
            addView(
                NativeNavIcon(activity, key, selected),
                LinearLayout.LayoutParams(dp(22), dp(22)),
            )
            addView(
                TextView(activity).apply {
                    text = label
                    textSize = 10f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(if (selected) Color.rgb(37, 37, 37) else Color.rgb(118, 118, 118))
                    setPadding(0, dp(4), 0, 0)
                    if (selected) setTypeface(null, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            contentDescription = title
            isClickable = true
            isFocusable = true
            isSelected = selected
            setOnClickListener { click() }
        }
    }

    fun refresh() {
        rail.removeAllViews()
        rail.addView(navButton("对话", "harness") { open("harness") })
        val plugins = store.list().getJSONArray("plugins")
        if (plugins.length() == 0) {
            rail.addView(navButton("插件", "plugins") { open("plugins") })
        }
        for (i in 0 until plugins.length()) {
            val m = plugins.getJSONObject(i)
            if (m.getBoolean("enabled")) {
                val id = m.getString("id")
                val title = m.getString("title")
                rail.addView(navButton(title, id) { open(id) })
            }
        }
        rail.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(navButton("设置", "settings") { open("settings") })
    }

    private fun onboardingView(): LinearLayout {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val title =
            TextView(activity).apply {
                text = "还没有创建插件，快来试试吧"
                textSize = 14f
                setTextColor(Color.rgb(34, 34, 37))
                gravity = Gravity.CENTER
            }
        val create =
            TextView(activity).apply {
                text = "创建插件"
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                minHeight = dp(40)
                setPadding(dp(16), 0, dp(16), 0)
                background =
                    GradientDrawable().apply {
                        setColor(Color.rgb(17, 17, 19))
                        cornerRadius = dp(10).toFloat()
                    }
                isClickable = true
                isFocusable = true
                setOnClickListener { openPluginDeveloperBot() }
            }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            setBackgroundColor(Color.WHITE)
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(
                create,
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) },
            )
        }
    }

    private fun openPluginDeveloperBot() {
        if (store.list().getJSONArray("plugins").length() > 0) {
            open("harness")
            return
        }
        saveScroll()
        viewKey = ""
        backAction = ""
        leaveSurface()
        generation++
        busy = false
        showingOnboarding = false
        current = "harness"
        panel.visibility = View.GONE
        panel.removeAllViews()
        refresh()
        showCore("harness?plugin=create")
    }

    fun ready() {
        if (current in setOf("harness", "settings")) showCore(current) else open(current)
    }

    fun back(): Boolean {
        if (current == "plugins") {
            open("settings")
            return true
        }
        page?.let {
            it.back()
            return true
        }
        if (panel.visibility != View.VISIBLE) return false
        if (backAction.isNotEmpty()) {
            if (!busy) perform(backAction, renderer?.values() ?: JSONObject())
            return true
        }
        open("harness")
        return true
    }

    fun open(id: String) {
        if (closed) return
        closeSiteBrowserForNavigation()
        if (id == "plugins") {
            if (store.list().getJSONArray("plugins").length() == 0) showOnboarding() else manage()
            return
        }
        showingOnboarding = false
        if (
            current == id &&
                page != null &&
                runCatching { store.requireRevision(id, currentRevision) }.isSuccess
        ) {
            activity.hideCoreForPlugin()
            return
        }
        saveScroll()
        viewKey = ""
        backAction = ""
        leaveSurface()
        generation++
        busy = false
        if (id in setOf("harness", "settings")) {
            current = id
            panel.visibility = View.GONE
            refresh()
            showCore(id)
            return
        }
        val bundle = runCatching { PluginContract.validate(store.activeBundle(id)) }.getOrNull()
        if (bundle == null) {
            open("harness")
            return
        }
        current = id
        currentRevision = bundle.getString("_revision")
        refresh()
        require(bundle.getJSONObject("manifest").getString("kind") == "native") { "请安装独立插件包" }
        if (bundle.getJSONObject("view").optString("type") == "page") {
            activity.hideCoreForPlugin()
            panel.visibility = View.GONE
            val cached = pages[id]?.takeIf { it.revision == currentRevision }
            val next =
                cached?.page
                    ?: run {
                        pages.remove(id)?.page?.close()
                        lateinit var created: PluginPage
                        created =
                            PluginPage(
                                activity,
                                cfg,
                                panes,
                                bundle,
                                { target ->
                                    if (page === created) open(target)
                                },
                                {
                                    if (pages[id]?.page === created) pages.remove(id)
                                    if (page === created) {
                                        page = null
                                        open("harness")
                                    }
                                },
                            )
                        pages[id] = CachedPage(currentRevision, created)
                        root.addView(created.view, root.indexOfChild(railScroll), pageParams())
                        created
                    }
            page = next
            next.view.visibility = View.VISIBLE
            if (resumed) next.resume() else next.pause()
            railScroll.bringToFront()
            return
        }
        // Core chat stays mounted; its native overlays are hidden while a plugin is visible.
        showCore("harness")
        activity.hideCoreForPlugin()
        panel.visibility = View.VISIBLE
        render(bundle.getJSONObject("view"))
        bundle
            .getJSONObject("manifest")
            .optString("onOpen")
            .takeIf { it.isNotEmpty() }
            ?.let { perform(it, JSONObject()) }
    }

    private fun render(view: JSONObject) {
        saveScroll()
        viewKey = "$current:$currentRevision:${view.optString("scrollKey")}"
        backAction = view.optString("backAction")
        val position = scrolls[viewKey] ?: 0
        panel.removeAllViews()
        val generationAtRender = generation
        refreshAction = view.optString("refreshAction")
        refreshSeconds = view.optInt("refreshSeconds", 15)
        renderer =
            NativePluginRenderer(
                activity,
                { activity.openExternal(it) },
                { action, inputs, button ->
                    if (generationAtRender == generation) perform(action, inputs, button)
                },
                { image -> imageButton(image, generationAtRender) },
                { thread, callback ->
                    val id = current
                    val revision = currentRevision
                    activity.choosePluginImages { uris ->
                        if (closed || generationAtRender != generation || uris.isEmpty())
                            callback(null)
                        else
                            io.execute {
                                val selected = runCatching {
                                    PluginPlatform.importImages(
                                            activity,
                                            id,
                                            revision,
                                            thread,
                                            uris,
                                        )
                                        .toString()
                                }
                                activity.runOnUiThread {
                                    if (!closed && generationAtRender == generation) {
                                        selected.onFailure { message(it.message ?: "图片读取失败") }
                                        callback(selected.getOrNull())
                                    }
                                }
                            }
                    }
                },
                { thread, callback ->
                    val id = current
                    val revision = currentRevision
                    val token = PluginCancellation()
                    mediaCalls += token
                    io.execute {
                        val cleared = runCatching {
                            PluginPlatform.uiMedia(
                                activity,
                                id,
                                revision,
                                JSONObject().put("op", "clear").put("threadId", thread),
                                token,
                            )
                        }
                        activity.runOnUiThread {
                            mediaCalls -= token
                            if (!closed && generationAtRender == generation) {
                                cleared.onFailure { message(it.message ?: "清理失败") }
                                callback(cleared.isSuccess)
                            }
                        }
                    }
                },
            )
        panel.addView(renderer!!.render(view))
        inputKey =
            if (view.optString("inputKey").isNotEmpty())
                "$current:$currentRevision:${view.getString("inputKey")}"
            else ""
        inputDrafts.remove(inputKey)?.let { renderer?.restore(it) }
        schedulePoll()
        panel.post { if (!closed && generationAtRender == generation) panel.scrollTo(0, position) }
    }

    private fun imageButton(value: JSONObject, expected: Int): View =
        Button(activity).apply {
            text = activity.getString(R.string.view_named_image, value.optString("name", "图片"))
            isAllCaps = false
            setOnClickListener {
                if (expected != generation) return@setOnClickListener
                isEnabled = false
                val id = current
                val revision = currentRevision
                val token = PluginCancellation()
                mediaCalls += token
                io.execute {
                    val decoded = runCatching {
                        PluginImages.decode(
                            PluginPlatform.uiMedia(
                                activity,
                                id,
                                revision,
                                JSONObject(value.toString())
                                    .put("op", if (value.has("previewId")) "preview" else "image")
                                    .put("mediaId", value.optString("mediaId")),
                                token,
                            ),
                        )
                    }
                    activity.runOnUiThread {
                        mediaCalls -= token
                        if (closed || expected != generation) {
                            decoded.getOrNull()?.recycle()
                            return@runOnUiThread
                        }
                        isEnabled = true
                        decoded
                            .onSuccess { bitmap ->
                                val image =
                                    android.widget.ImageView(activity).apply {
                                        setImageBitmap(bitmap)
                                        adjustViewBounds = true
                                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                    }
                                mediaDialog =
                                    AlertDialog.Builder(activity)
                                        .setTitle(value.optString("name", "图片"))
                                        .setView(image)
                                        .setPositiveButton("关闭", null)
                                        .create()
                                        .apply {
                                            setOnDismissListener {
                                                image.setImageDrawable(null)
                                                bitmap.recycle()
                                                mediaDialog = null
                                            }
                                            show()
                                        }
                            }
                            .onFailure { message(it.message ?: "图片读取失败") }
                    }
                }
            }
        }

    private fun perform(
        action: String,
        inputs: JSONObject,
        button: Button? = null,
        automatic: Boolean = false,
    ) {
        if (busy || closed) return
        panel.removeCallbacks(poll)
        busy = true
        button?.isEnabled = false
        val token = PluginCancellation()
        activeCall = token
        val id = current
        val generationAtStart = generation
        val revisionAtStart = currentRevision
        io.execute {
            val result = runCatching {
                PluginPlatform.invoke(activity, id, action, inputs, token, revisionAtStart)
            }
            activity.runOnUiThread {
                if (closed || generationAtStart != generation) return@runOnUiThread
                busy = false
                activeCall = null
                button?.isEnabled = true
                val active = runCatching {
                    PluginContract.validate(store.activeBundle(id))
                }
                    .getOrNull()
                if (active == null) {
                    open("harness")
                    return@runOnUiThread
                }
                if (active.getString("_revision") != revisionAtStart) {
                    open(id)
                    return@runOnUiThread
                }
                result
                    .onSuccess {
                        // Do not replace text typed while a read was in flight.
                        val edits = renderer?.values()
                        val changed = edits != null && edits.toString() != inputs.toString()
                        render(it.getJSONObject("view"))
                        if (changed) renderer?.restore(edits!!)
                    }
                    .onFailure {
                        refreshAction = "" // Failed reads stop polling; retry is explicit.
                        if (automatic) {
                            message("自动同步未完成：${it.message ?: "连接失败"}。请手动刷新。")
                        } else
                            AlertDialog.Builder(activity)
                                .setTitle("插件未完成")
                                .setMessage(it.message ?: "执行失败")
                                .setPositiveButton("知道了", null)
                                .setNeutralButton("插件管理") { _, _ -> manage() }
                                .show()
                    }
            }
        }
    }

    fun showManager() {
        manage()
    }

    private fun manage() {
        closeSiteBrowserForNavigation()
        showingOnboarding = false
        val managerScroll = if (current == "plugins") panel.scrollY else 0
        saveScroll()
        viewKey = ""
        backAction = ""
        leaveSurface()
        generation++
        busy = false
        current = "plugins"
        refresh()
        showCore("harness")
        activity.hideCoreForPlugin()
        panel.visibility = View.VISIBLE
        panel.setBackgroundColor(Color.rgb(247, 247, 249))
        val body =
            pluginManagerView(
                activity,
                store.list().getJSONArray("plugins"),
                onBack = { open("settings") },
                onAdd = { activity.choosePluginPackage() },
                onEnable = { id, enabled ->
                    mutate {
                        PluginPlatform.manage(
                            activity,
                            JSONObject()
                                .put("action", "enable")
                                .put("id", id)
                                .put("enabled", enabled),
                        )
                    }
                },
                onRemove = { m ->
                    AlertDialog.Builder(activity)
                        .setTitle("移除 ${m.getString("title")}？")
                        .setMessage("将移除已安装版本和插件状态，工作目录中的源码与文件会保留。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("移除") { _, _ ->
                            mutate {
                                PluginPlatform.manage(
                                    activity,
                                    JSONObject()
                                        .put("action", "remove")
                                        .put("id", m.getString("id")),
                                )
                            }
                        }
                        .show()
                },
            )
        panel.removeAllViews()
        panel.addView(body)
        panel.scrollTo(0, managerScroll)
    }

    private fun showOnboarding() {
        closeSiteBrowserForNavigation()
        showingOnboarding = true
        saveScroll()
        viewKey = ""
        backAction = ""
        leaveSurface()
        generation++
        busy = false
        current = "plugins"
        refresh()
        showCore("harness")
        activity.hideCoreForPlugin()
        panel.visibility = View.VISIBLE
        panel.setBackgroundColor(Color.WHITE)
        panel.removeAllViews()
        panel.isFillViewport = true
        panel.addView(onboardingView(), FrameLayout.LayoutParams(-1, -1))
        panel.scrollTo(0, 0)
    }

    fun installPackage(uri: android.net.Uri) {
        io.execute {
            val result = runCatching {
                val data =
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= PluginContract.MAX_PACKAGE_BYTES) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("无法读取插件包")
                require(data.size <= PluginContract.MAX_PACKAGE_BYTES) { "插件包超过 2 MB" }
                PluginPlatform.manage(
                    activity,
                    JSONObject()
                        .put("action", "install")
                        .put("bundle", JSONObject(data.toString(Charsets.UTF_8))),
                )
            }
            activity.runOnUiThread {
                if (!closed) result.fold({ manage() }, { message(it.message ?: "安装插件失败") })
            }
        }
    }

    private fun mutate(work: () -> Any) {
        io.execute {
            val result = runCatching(work)
            activity.runOnUiThread {
                if (!closed) {
                    result
                        .onSuccess { value ->
                            (value as? JSONObject)
                                ?.optString("warning")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { if (current == "plugins") message(it) }
                        }
                        .onFailure {
                            if (current == "plugins") manage()
                            message(it.message ?: "操作失败")
                        }
                }
            }
        }
    }

    private fun message(text: String) {
        AlertDialog.Builder(activity).setMessage(text).setPositiveButton("知道了", null).show()
    }

    fun trimHiddenPages() {
        for ((id, cached) in pages.toMap()) if (cached.page !== page) {
            pages.remove(id)
            cached.page.close()
        }
    }

    fun close() {
        closeSiteBrowserForNavigation()
        leaveSurface()
        closed = true
        generation++
        PluginPlatform.removeListener(listener)
        pages.values.forEach { it.page.close() }
        pages.clear()
        io.shutdownNow()
    }
}
