package com.personaldashboard.app

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Owns the single Bot verification preview session.
 *
 * The session is a separate offscreen rendering of an installed plugin page or a validated draft.
 * It never replaces the foreground page, never changes the current plugin selection and never
 * writes installed plugin state. Draft revisions and origins are temporary; subsequent operations
 * never require the draft to be installed or active.
 */
object PluginPreviewManager {
    private val lock = Any()
    private var activityRef: WeakReference<MainActivity>? = null
    private var config: Config? = null
    private var panesRef: WeakReference<Panes>? = null
    private var preview: PluginPreview? = null
    private var session: PluginPreviewPolicy.Session? = null
    @Volatile private var loaded = false
    @Volatile private var resumed = false
    private val handler = Handler(Looper.getMainLooper())

    private val ticker =
        object : Runnable {
            override fun run() {
                expire()
                synchronized(lock) {
                    handler.removeCallbacks(this)
                    if (preview != null) handler.postDelayed(this, TICK_MS)
                }
            }
        }

    private val pluginListener: () -> Unit = {
        handler.post {
            val owner = synchronized(lock) { activityRef?.get() }
            val currentWithState = synchronized(lock) { preview to session }
            val current = currentWithState.first
            val state = currentWithState.second
            if (owner != null && current != null && state?.installed == true) {
                val active = runCatching {
                    PluginPlatform.store(owner).activeBundle(current.pluginId())
                }
                    .getOrNull()
                if (active == null || active.optString("_revision") != current.revision()) {
                    closeSession("插件已更新或停用", current)
                }
            }
        }
        Unit
    }

    private const val TICK_MS = 15_000L

    /** The activity registers itself so the preview can render without launching anything. */
    fun attach(owner: MainActivity, cfg: Config, host: Panes) =
        synchronized(lock) {
            activityRef = WeakReference(owner)
            config = cfg
            panesRef = WeakReference(host)
            PluginPlatform.removeListener(pluginListener)
            PluginPlatform.addListener(pluginListener)
        }

    fun onResumed() {
        resumed = true
        val now = System.currentTimeMillis()
        synchronized(lock) { session?.resume(now) }
        preview?.resume()
    }

    fun onPaused() {
        resumed = false
        val now = System.currentTimeMillis()
        preview?.pause()
        synchronized(lock) { session?.pause(now) }
    }

    /** The app is gone: the preview cannot outlive its window, but its closed receipt remains. */
    fun onDestroyed() {
        resumed = false
        PluginPlatform.removeListener(pluginListener)
        closeSession("应用已关闭")
        synchronized(lock) {
            activityRef = null
            config = null
            panesRef = null
        }
        handler.removeCallbacks(ticker)
    }

    /** Entry point for the trusted Bot channel; every request is bounded. */
    fun handle(
        request: JSONObject,
        cancellation: PluginCancellation = PluginCancellation(),
    ): JSONObject {
        cancellation.check()
        val operation = PluginPreviewPolicy.op(request.optString("op"))
        if (operation != "open") {
            val target = synchronized(lock) { preview }
            cancellation.onCancel { target?.close("操作已取消") }
        }
        val result =
            try {
                when (operation) {
                    "open" -> open(request, cancellation)
                    "snapshot" ->
                        withSession {
                            it.snapshot(
                                request.optString("selector", ""),
                                if (request.has("offset")) request.optInt("offset") else null,
                                if (request.has("limit")) request.optInt("limit") else null,
                            )
                        }
                    "click" -> withSession { it.click(request.optString("selector")) }
                    "input" ->
                        withSession {
                            it.input(request.optString("selector"), request.optString("text"))
                        }
                    "scroll" ->
                        withSession {
                            it.scroll(
                                request.optString("selector"),
                                if (request.has("y")) request.optInt("y") else null,
                            )
                        }
                    "reload" ->
                        withSession(requireReady = false) {
                            synchronized(lock) {
                                loaded = false
                                session?.loading()
                            }
                            it.reload()
                        }
                    "clearStorage" -> withSession { it.clearStorage() }
                    "close" -> closeSession("已关闭")
                    "status" -> status()
                    else -> throw IllegalArgumentException("不支持的预览操作")
                }
            } catch (e: InterruptedException) {
                cancellation.cancel()
                throw e
            }

        cancellation.check()
        return result
    }

    private fun open(request: JSONObject, cancellation: PluginCancellation): JSONObject {
        val owner = synchronized(lock) { activityRef?.get() } ?: error("宿主页面未就绪，无法打开预览")
        val cfg = synchronized(lock) { config } ?: error("宿主页面未就绪，无法打开预览")
        val host = synchronized(lock) { panesRef?.get() } ?: error("宿主页面未就绪，无法打开预览")
        if (!resumed) error("应用当前不在前台，无法生成真实预览画面")
        val hasId =
            request.has("id") && !request.isNull("id") && request.optString("id").isNotEmpty()
        val hasDraft =
            request.has("packageJson") &&
                !request.isNull("packageJson") &&
                request.optString("packageJson").isNotEmpty()
        require(hasId != hasDraft) { "open 需要在已安装插件 ID 和 packageJson 草稿之间选择一个" }
        val (width, height) =
            PluginPreviewPolicy.viewport(
                if (request.has("width")) request.optInt("width") else null,
                if (request.has("height")) request.optInt("height") else null,
            )
        val network = PluginPreviewPolicy.network(request.optJSONObject("network"))
        val bundle: JSONObject
        val id: String
        val installed: Boolean
        if (hasDraft) {
            bundle = PluginPreviewPolicy.draftBundle(request.optString("packageJson"))
            id = PluginContract.id(bundle.getJSONObject("manifest").getString("id"))
            installed = false
        } else {
            id = PluginContract.id(request.optString("id"))
            // The preview renders a copy of the installed bundle; the installed state stays
            // untouched.
            bundle = JSONObject(PluginPlatform.store(owner).activeBundle(id).toString())
            installed = true
        }

        closeSession("被新的预览替换")
        val sessionId = UUID.randomUUID().toString()
        val originTag = PluginPreviewPolicy.previewOriginTag(sessionId)
        val density = owner.resources.displayMetrics.density.coerceAtLeast(0.5f)
        val created = CountDownLatch(1)
        var failure: Throwable? = null
        var instance: PluginPreview? = null
        owner.runOnUiThread {
            try {
                cancellation.check()
                check(synchronized(lock) { activityRef?.get() === owner } && resumed) {
                    "宿主页面已离开，预览已取消"
                }
                instance =
                    PluginPreview(
                        owner,
                        cfg,
                        host,
                        bundle,
                        (width * density).toInt(),
                        (height * density).toInt(),
                        originTag,
                        installed,
                        network,
                        PluginPreviewPolicy.allowAudio(request),
                        { reason -> instance?.let { previewClosedUnexpectedly(it, reason) } },
                    )
                val createdPreview = instance!!
                cancellation.onCancel { createdPreview.close("操作已取消") }
                cancellation.check()
            } catch (e: Throwable) {
                failure = e
            }
            created.countDown()
        }
        val completed =
            try {
                created.await(3_000, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                cancellation.cancel()
                throw e
            }
        if (!completed) {
            cancellation.cancel()
            error("预览创建超时")
        }
        failure?.let { error("预览创建失败：${it.message ?: "未知原因"}") }
        val ready = instance ?: error("预览创建失败")
        if (cancellation.cancelled) {
            ready.close("操作已取消")
            cancellation.check()
        }
        val openedAt = System.currentTimeMillis()
        val attached =
            synchronized(lock) {
                if (
                    activityRef?.get() !== owner ||
                        !resumed ||
                        cancellation.cancelled ||
                        ready.isClosed()
                )
                    false
                else {
                    preview = ready
                    session =
                        PluginPreviewPolicy.Session(
                            sessionId,
                            id,
                            ready.storageOrigin(),
                            ready.revision(),
                            installed,
                            network,
                            openedAt,
                        )
                    handler.removeCallbacks(ticker)
                    handler.postDelayed(ticker, TICK_MS)
                    true
                }
            }
        if (!attached) {
            ready.close("宿主页面已离开")
            error("宿主页面已离开，预览已取消")
        }
        if (cancellation.cancelled) {
            ready.close("操作已取消")
            cancellation.check()
        }
        // The framework handshake tells us the page really booted; a timeout is reported, not
        // hidden.
        val booted = ready.awaitReady()
        if (ready.isClosed()) {
            closeSession(ready.closedReason(), ready)
            error("预览已关闭：${ready.closedReason()}")
        }
        synchronized(lock) {
            check(preview === ready) { "预览已关闭" }
            loaded = booted
            if (booted) session?.ready(System.currentTimeMillis())
        }
        return receipt(
            JSONObject()
                .put("loaded", booted)
                .put("source", if (installed) "installed" else "draft")
                .put("revision", ready.revision())
                .put("origin", ready.storageOrigin())
                .put("network", network.json()),
        )
    }

    private fun withSession(
        requireReady: Boolean = true,
        block: (PluginPreview) -> JSONObject,
    ): JSONObject {
        val current = synchronized(lock) { preview } ?: error("预览尚未打开，请先执行 open")
        val installed = synchronized(lock) { session?.installed } ?: true
        if (current.isClosed()) {
            val reason = current.closedReason().ifEmpty { "预览已关闭" }
            closeSession(reason, current)
            error("$reason，请重新打开预览")
        }
        if (!resumed) error("应用当前不在前台，无法操作真实预览")
        if (installed) {
            val owner = synchronized(lock) { activityRef?.get() }
            if (owner != null) {
                val active = runCatching {
                    PluginPlatform.store(owner).activeBundle(current.pluginId())
                }
                    .getOrNull()
                if (active == null || active.getString("_revision") != current.revision()) {
                    closeSession("插件已更新或停用", current)
                    error("插件已更新或停用，请重新打开预览")
                }
            }
        }
        check(!requireReady || current.awaitReady(0)) { "预览页面尚未完成加载，请稍后重试" }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            check(preview === current) { "预览会话已替换" }
            if (current.awaitReady(0)) {
                loaded = true
                session?.ready(now)
            }
            session?.touch(now)
        }
        return receipt(block(current))
    }

    private fun closeSession(reason: String, expected: PluginPreview? = null): JSONObject {
        val current =
            synchronized(lock) {
                if (expected != null && preview !== expected)
                    return receipt(JSONObject().put("closed", false))
                val closing = preview
                handler.removeCallbacks(ticker)
                preview = null
                loaded = false
                val closingReason = closing?.closedReason()?.takeIf { it.isNotEmpty() } ?: reason
                val closedAt = closing?.closedAt()?.takeIf { it > 0 } ?: System.currentTimeMillis()
                closing?.let { session?.close(closingReason, closedAt) } ?: session?.close(reason)
                closing
            }
        current?.close(reason)
        return receipt(JSONObject().put("closed", true).put("reason", reason))
    }

    private fun previewClosedUnexpectedly(current: PluginPreview, reason: String) {
        synchronized(lock) {
            if (preview !== current) return
            preview = null
            loaded = false
            session?.close(reason, current.closedAt())
            handler.removeCallbacks(ticker)
        }
    }

    private fun expire() {
        val currentWithState = synchronized(lock) { preview to session }
        val current = currentWithState.first
        val state = currentWithState.second
        if (!resumed) return
        if (current == null || state == null) return
        val now = System.currentTimeMillis()
        if (!state.expired(now)) return
        closeSession(
            if (state.idleFor(now) >= PluginPreviewPolicy.IDLE_TIMEOUT_MS) "预览空闲超时"
            else "预览超过最长使用时间",
            current,
        )
    }

    private fun status(): JSONObject {
        val current = synchronized(lock) { preview }
        if (current?.isClosed() == true) previewClosedUnexpectedly(current, current.closedReason())
        if (current != null && !current.isClosed())
            synchronized(lock) {
                if (preview === current) {
                    loaded = current.awaitReady(0)
                    if (loaded) session?.ready(System.currentTimeMillis()) else session?.loading()
                }
            }
        val state = synchronized(lock) { session }
        val now = System.currentTimeMillis()
        val detail =
            JSONObject()
                .put("loaded", loaded)
                .put("appResumed", resumed)
                .put("idleRemainingMs", state?.idleRemainingMs(now) ?: 0)
                .put("maxRemainingMs", state?.maxRemainingMs(now) ?: 0)
                .put("reason", state?.closedReason ?: "")
                .put("closedAt", state?.closedAt ?: 0)
                .put("preview", current?.status() ?: JSONObject())
        return receipt(detail, queryOk = true)
    }

    /** Every successful channel operation carries the same session identity and state receipt. */
    private fun receipt(result: JSONObject, queryOk: Boolean = false): JSONObject {
        val currentWithState = synchronized(lock) { preview to session }
        val current = currentWithState.first
        val state = currentWithState.second
        val ok = if (queryOk) true else result.optBoolean("ok", true)
        return JSONObject()
            .put("session", state?.id ?: "")
            .put("plugin", current?.pluginId() ?: state?.pluginId ?: "")
            .put("state", state?.state?.name?.lowercase() ?: "closed")
            .put("ok", ok)
            .put("result", result)
    }

    /** Closes the session when the host context is gone; used by the app lifecycle. */
    fun detachIfOwner(owner: MainActivity) {
        if (synchronized(lock) { activityRef?.get() } === owner) onDestroyed()
    }

    /** Test and tool helper: closes everything without touching the foreground app. */
    fun closeAll() {
        closeSession("已关闭")
    }
}
