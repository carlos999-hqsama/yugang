package com.personaldashboard.app

import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/**
 * Pure policy for the Bot verification preview: viewport bounds, selector and text validation, the
 * bridge allowlist and the session lifecycle.
 *
 * No Android types are used here so the rules stay unit testable on the JVM.
 */
object PluginPreviewPolicy {
    /** Viewport bounds in CSS pixels; the preview never renders an unbounded window. */
    const val MIN_WIDTH_PX = 240
    const val MAX_WIDTH_PX = 1024
    const val MIN_HEIGHT_PX = 320
    const val MAX_HEIGHT_PX = 1600
    const val DEFAULT_WIDTH_PX = 412
    const val DEFAULT_HEIGHT_PX = 892

    const val MAX_SELECTOR_LENGTH = 200
    const val MAX_INPUT_LENGTH = 4096
    const val MAX_SCROLL_PX = 100_000
    const val MAX_SNAPSHOT_HTML_BYTES = 512 * 1024
    const val MAX_SNAPSHOT_TEXT_LENGTH = 20_000
    const val MAX_SNAPSHOT_OFFSET = 1_000_000
    const val MIN_SNAPSHOT_LIMIT = 1
    const val DEFAULT_SNAPSHOT_LIMIT = 2_000
    const val MAX_CONSOLE_ERRORS = 50
    const val MAX_PNG_BYTES = 4 * 1024 * 1024
    const val MAX_CAPTURE_EDGE_PX = 1080
    const val DEFAULT_SLOW_NETWORK_DELAY_MS = 2_000L
    const val MAX_NETWORK_DELAY_MS = 10_000L

    /** Expected preview refusals are operation results, not renderer/page failures. */
    const val PREVIEW_REASON = "preview"
    const val PREVIEW_POLICY_CODE = "preview_policy_denied"
    const val PREVIEW_BROWSER_CODE = "preview_browser_disabled"

    /** A preview session is closed after this idle time or after the total lifetime. */
    const val IDLE_TIMEOUT_MS = 90_000L
    const val MAX_LIFETIME_MS = 10 * 60_000L
    /** Upper bound for one preview operation; the IPC worker waits at most this long. */
    const val OP_TIMEOUT_MS = 25_000L
    const val PAGE_READY_TIMEOUT_MS = 20_000L

    val OPS =
        setOf(
            "open",
            "snapshot",
            "click",
            "input",
            "scroll",
            "reload",
            "clearStorage",
            "close",
            "status",
        )
    val NETWORK_MODES = setOf("online", "offline", "slow")

    /**
     * The preview renders on its own origin, so browser storage never mixes with the foreground
     * page. The tag must stay a plain DNS label.
     */
    private const val PREVIEW_ORIGIN_TAG = "-p"

    /** Bridge calls a preview page may perform; everything else is refused before it runs. */
    val READ_ONLY_METHODS = setOf("ready", "getConfig")
    /** Host capabilities that only read data; the preview needs them to render a real page. */
    val READ_ONLY_CAPABILITIES = setOf("sseOpen", "sseClose")
    /** Plugin management actions that only read state. */
    val READ_ONLY_MANAGEMENT = setOf("read", "list")
    /** Actions of the host capabilities that would write or trigger work. */
    private val READ_METHODS = setOf("GET", "HEAD")

    private val BROWSER_METHODS = setOf("browser", "siteBrowserOpen")

    /** Structured failure returned only to the call that the preview policy refused. */
    data class BridgeFailure(
        val code: String,
        val message: String,
        val reason: String? = null,
        val expected: Boolean = false,
    ) {
        fun payload(): JSONObject =
            JSONObject()
                .put("error", message)
                .put("code", code)
                .put("expected", expected)
                .put("retryable", false)
                .apply { reason?.let { put("reason", it) } }
    }

    fun allowAudio(request: JSONObject): Boolean {
        if (!request.has("allowAudio")) return false
        require(request.get("allowAudio") is Boolean) { "allowAudio 必须为布尔值" }
        return request.getBoolean("allowAudio")
    }

    fun op(raw: String?): String {
        val value = raw?.trim().orEmpty()
        require(value.isNotEmpty()) { "缺少预览操作" }
        require(value in OPS) { "不支持的预览操作" }
        return value
    }

    /** Viewport in CSS pixels; missing values fall back to a phone sized window. */
    fun viewport(width: Int?, height: Int?): Pair<Int, Int> {
        val w = width ?: DEFAULT_WIDTH_PX
        val h = height ?: DEFAULT_HEIGHT_PX
        require(w in MIN_WIDTH_PX..MAX_WIDTH_PX) { "预览宽度超出允许范围" }
        require(h in MIN_HEIGHT_PX..MAX_HEIGHT_PX) { "预览高度超出允许范围" }
        return w to h
    }

    /**
     * Drafts get a registry-independent revision. Each open also gets a short unique origin suffix;
     * the complete host stays a legal one-label DNS name even for a 48-character plugin ID.
     */
    fun previewRevision(): String = "preview-${UUID.randomUUID()}"

    fun previewOriginTag(sessionId: String): String =
        PREVIEW_ORIGIN_TAG + String.format(Locale.ROOT, "%06x", sessionId.hashCode()).take(6)

    /** Validate and isolate a JSON draft without installing it or touching plugin state. */
    fun draftBundle(packageJson: String): JSONObject {
        require(packageJson.toByteArray().size <= PluginContract.MAX_PACKAGE_BYTES) {
            "插件包超过 2 MiB"
        }
        val clean = JSONObject(packageJson).apply { remove("_revision") }
        return PluginContract.validate(clean).put("_revision", previewRevision())
    }

    /** CSS selector used by click/input/scroll; only printable characters and a bounded length. */
    fun selector(raw: String?): String {
        val value = raw?.trim().orEmpty()
        require(value.isNotEmpty()) { "缺少选择器" }
        require(value.length <= MAX_SELECTOR_LENGTH) { "选择器过长" }
        require(value.none { it.isISOControl() }) { "选择器包含非法字符" }
        return value
    }

    /** Text typed into a preview field. */
    fun inputText(raw: String?): String {
        val value = raw.orEmpty()
        require(value.length <= MAX_INPUT_LENGTH) { "输入内容过长" }
        require(value.none { it.isISOControl() && it != '\n' && it != '\t' }) { "输入内容包含非法字符" }
        return value
    }

    fun scrollOffset(raw: Int?): Int {
        val value = raw ?: 0
        require(value in -MAX_SCROLL_PX..MAX_SCROLL_PX) { "滚动距离超出允许范围" }
        return value
    }

    /** Visible-text pagination is bounded so one snapshot cannot become an unbounded DOM dump. */
    fun snapshotPage(offset: Int?, limit: Int?): Pair<Int, Int> {
        val at = offset ?: 0
        val size = limit ?: DEFAULT_SNAPSHOT_LIMIT
        require(at in 0..MAX_SNAPSHOT_OFFSET) { "文本偏移超出允许范围" }
        require(size in MIN_SNAPSHOT_LIMIT..MAX_SNAPSHOT_TEXT_LENGTH) { "分页长度超出允许范围" }
        return at to size
    }

    class Network(val mode: String, val delayMs: Long) {
        fun json(): JSONObject = JSONObject().put("mode", mode).put("delayMs", delayMs)
    }

    fun network(raw: JSONObject?): Network {
        if (raw == null) return Network("online", 0)
        val mode = raw.optString("mode", "online")
        require(mode in NETWORK_MODES) { "不支持的网络模式" }
        val hasDelay = raw.has("delayMs") && !raw.isNull("delayMs")
        if (mode != "slow") {
            require(!hasDelay) { "只有 slow 模式支持 delayMs" }
            return Network(mode, 0)
        }
        val delay = raw.optLong("delayMs", DEFAULT_SLOW_NETWORK_DELAY_MS)
        require(delay in 1..MAX_NETWORK_DELAY_MS) { "网络延迟必须在 1-${MAX_NETWORK_DELAY_MS}ms 内" }
        return Network(mode, delay)
    }

    /**
     * Management actions that would change installed plugin state or the foreground app. The
     * preview never installs, enables, rolls back, removes or navigates.
     */
    fun refuseManagement(action: String): String? =
        when (action) {
            "install" -> "预览不允许安装插件"
            "enable" -> "预览不允许启用或停用插件"
            "rollback" -> "预览不允许回退插件"
            "remove" -> "预览不允许移除插件"
            "invoke" -> "预览不允许执行会写入插件状态的动作"
            "navigate" -> "预览不允许切换前台页面"
            else -> null
        }

    /** Read channels that can reach a remote server in addition to generic native.fetch. */
    fun usesNetwork(method: String): Boolean =
        method in setOf("http", "sseOpen")

    /** Reason why a bridge call must be refused inside the preview, or null when it is allowed. */
    fun refuseBridgeCall(method: String, args: JSONObject?): String? {
        if (method == "siteBrowserOpen") return "预览不会打开真实网站"
        if (method == "pluginNotify") return if (args?.optString("op") == "status") null else "预览仅允许查询通知权限，不会发送或申请通知"
        if (method == "pluginModel")
            return if (args?.optString("op") == "budget") "预览不允许修改模型预算" else null
        if (method in READ_ONLY_METHODS) return null
        if (method in READ_ONLY_CAPABILITIES) return null
        if (method == "http") {
            val verb = args?.optString("method", "GET").orEmpty().uppercase()
            return if (verb in READ_METHODS) null else "预览只允许只读请求"
        }
        if (method == "plugins") {
            val action = args?.optString("action").orEmpty()
            if (action == "fetch" || action == "fetchCancel") return null
            refuseManagement(action)?.let {
                return it
            }
            if (action in READ_ONLY_MANAGEMENT) return null
            return "预览只允许读取插件状态"
        }
        return "预览不允许调用宿主能力 $method"
    }

    /** Adds stable SDK metadata without weakening the allowlist or entering the real Bridge. */
    fun bridgeFailure(method: String, args: JSONObject?): BridgeFailure? {
        val message = refuseBridgeCall(method, args) ?: return null
        return BridgeFailure(
            code = if (method in BROWSER_METHODS) PREVIEW_BROWSER_CODE else PREVIEW_POLICY_CODE,
            message = message,
            reason = PREVIEW_REASON,
            expected = true,
        )
    }

    /** Lifecycle of one preview session. */
    enum class State {
        OPENING,
        READY,
        CLOSED,
    }

    class Session(
        val id: String,
        val pluginId: String,
        val origin: String,
        val revision: String,
        val installed: Boolean,
        val network: Network,
        val openedAt: Long,
    ) {
        var state: State = State.OPENING
            private set

        var lastUsedAt: Long = openedAt
            private set

        var closedReason: String = ""
            private set

        var closedAt: Long = 0L
            private set

        private var pausedAt: Long? = null
        private var pausedFor: Long = 0L

        fun loading() {
            if (state != State.CLOSED) state = State.OPENING
        }

        fun ready(now: Long) {
            if (state == State.OPENING) {
                state = State.READY
                lastUsedAt = now
            }
        }

        fun touch(now: Long) {
            lastUsedAt = now
        }

        fun pause(now: Long) {
            if (state != State.CLOSED && pausedAt == null) pausedAt = now
        }

        fun resume(now: Long) {
            val paused = pausedAt ?: return
            pausedAt = null
            if (state == State.CLOSED) return
            val elapsed = (now - paused).coerceAtLeast(0)
            lastUsedAt += elapsed
            pausedFor += elapsed
        }

        fun close(reason: String, now: Long = System.currentTimeMillis()) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            closedReason = reason
            closedAt = now
            pausedAt = null
        }

        fun idleFor(now: Long): Long =
            when (val paused = pausedAt) {
                null -> now - lastUsedAt
                else -> (paused - lastUsedAt).coerceAtLeast(0)
            }

        fun activeFor(now: Long): Long =
            when (val paused = pausedAt) {
                null -> now - openedAt - pausedFor
                else -> paused - openedAt - pausedFor
            }

        fun idleRemainingMs(now: Long): Long =
            if (state == State.CLOSED) 0
            else (IDLE_TIMEOUT_MS - idleFor(now)).coerceIn(0, IDLE_TIMEOUT_MS)

        fun maxRemainingMs(now: Long): Long =
            if (state == State.CLOSED) 0
            else (MAX_LIFETIME_MS - activeFor(now)).coerceIn(0, MAX_LIFETIME_MS)

        fun expired(now: Long): Boolean =
            state != State.CLOSED &&
                pausedAt == null &&
                (idleFor(now) >= IDLE_TIMEOUT_MS || activeFor(now) >= MAX_LIFETIME_MS)
    }
}
