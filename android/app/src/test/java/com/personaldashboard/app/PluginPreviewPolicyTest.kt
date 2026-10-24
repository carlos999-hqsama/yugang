package com.personaldashboard.app

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preview rules are pure policy: viewport bounds, selector limits, the bridge allowlist and the session clock. */
class PluginPreviewPolicyTest {
    @Test fun `audio preview requires an explicit boolean opt in`() {
        assertFalse(PluginPreviewPolicy.allowAudio(JSONObject()))
        assertTrue(PluginPreviewPolicy.allowAudio(JSONObject().put("allowAudio",true)))
        assertFalse(PluginPreviewPolicy.allowAudio(JSONObject().put("allowAudio",false)))
        assertRejected { PluginPreviewPolicy.allowAudio(JSONObject().put("allowAudio","true")) }
    }
    @Test fun `network profiles cover read host transports but keep local reads`() {
        for(method in listOf("http","sseOpen"))assertTrue(PluginPreviewPolicy.usesNetwork(method))
        assertFalse(PluginPreviewPolicy.usesNetwork("plugins"))
    }
    @Test fun `preview cannot save host settings or open foreground report dialogs`() {
        assertNotNull(PluginPreviewPolicy.refuseBridgeCall("setConfig",JSONObject().put("deepseekKey","synthetic")))
        assertNotNull(PluginPreviewPolicy.refuseBridgeCall("pluginMedia",JSONObject().put("op","clear")))
    }
    @Test fun `viewport falls back to a phone sized window and rejects extremes`() {
        assertEquals(PluginPreviewPolicy.DEFAULT_WIDTH_PX to PluginPreviewPolicy.DEFAULT_HEIGHT_PX,
            PluginPreviewPolicy.viewport(null, null))
        assertEquals(360 to 640, PluginPreviewPolicy.viewport(360, 640))
        assertRejected { PluginPreviewPolicy.viewport(10, 640) }
        assertRejected { PluginPreviewPolicy.viewport(360, 10) }
        assertRejected { PluginPreviewPolicy.viewport(4000, 640) }
        assertRejected { PluginPreviewPolicy.viewport(360, 9000) }
    }

    @Test fun `selector and text are bounded and printable`() {
        assertEquals("#refresh", PluginPreviewPolicy.selector(" #refresh "))
        assertRejected { PluginPreviewPolicy.selector(null) }
        assertRejected { PluginPreviewPolicy.selector("") }
        assertRejected { PluginPreviewPolicy.selector("x".repeat(PluginPreviewPolicy.MAX_SELECTOR_LENGTH + 1)) }
        assertRejected { PluginPreviewPolicy.selector("a\u0000b") }
        assertEquals("hello", PluginPreviewPolicy.inputText("hello"))
        assertEquals("a\nb", PluginPreviewPolicy.inputText("a\nb"))
        assertRejected { PluginPreviewPolicy.inputText("x".repeat(PluginPreviewPolicy.MAX_INPUT_LENGTH + 1)) }
        assertRejected { PluginPreviewPolicy.inputText("a\u0007b") }
    }

    @Test fun `preview refuses every action that would change the app`() {
        assertNull(PluginPreviewPolicy.refuseManagement("read"))
        assertNull(PluginPreviewPolicy.refuseManagement("list"))
        for (action in listOf("install", "enable", "rollback", "remove", "invoke", "navigate")) {
            assertTrue("$action 必须被拒绝", PluginPreviewPolicy.refuseManagement(action) != null)
        }
    }

    @Test fun `bridge allowlist keeps the preview read only except for network requests`() {
        assertNull(PluginPreviewPolicy.refuseBridgeCall("ready", null))
        assertNull(PluginPreviewPolicy.refuseBridgeCall("getConfig", null))
        assertNotNull(PluginPreviewPolicy.refuseBridgeCall("toast", null))
        assertNull(PluginPreviewPolicy.refuseBridgeCall("http", JSONObject().put("target", "dsh").put("method", "GET")))
        assertTrue(PluginPreviewPolicy.refuseBridgeCall("http", JSONObject().put("target", "dsh").put("method", "POST")) != null)
        assertNull(PluginPreviewPolicy.refuseBridgeCall("plugins", JSONObject().put("action", "fetch")))
        assertNull(PluginPreviewPolicy.refuseBridgeCall("plugins", JSONObject().put("action", "fetchCancel")))
        assertNull(PluginPreviewPolicy.refuseBridgeCall("plugins", JSONObject().put("action", "read").put("id", "demo-plugin")))
        assertNull(PluginPreviewPolicy.refuseBridgeCall("plugins", JSONObject().put("action", "list")))
        assertTrue(PluginPreviewPolicy.refuseBridgeCall("plugins", JSONObject().put("action", "install")) != null)
        assertTrue(PluginPreviewPolicy.refuseBridgeCall("plugins", JSONObject().put("action", "invoke")) != null)
        assertTrue(PluginPreviewPolicy.refuseBridgeCall("plugins", JSONObject().put("action", "enable")) != null)
        assertTrue(PluginPreviewPolicy.refuseBridgeCall("plugins", JSONObject().put("action", "remove")) != null)
        for (method in listOf("openExternal", "copy", "voice", "browser", "dshWeb", "setConfig", "bots", "wsOpen")) {
            assertTrue("$method 必须被拒绝", PluginPreviewPolicy.refuseBridgeCall(method, null) != null)
        }
    }

    @Test fun `the preview uses its own origin label`() {
        val tag = PluginPreviewPolicy.previewOriginTag("session-1")
        assertTrue(tag.matches(Regex("-p[a-f0-9]{6}")))
        assertTrue("plugin-${"x".repeat(48)}$tag".length <= 63)
        assertTrue("plugin-demo$tag.pd.local".matches(Regex("[a-z0-9.-]+")))
    }

    @Test fun `only known preview operations are accepted`() {
        assertEquals("snapshot", PluginPreviewPolicy.op("snapshot"))
        assertEquals("reload", PluginPreviewPolicy.op("reload"))
        assertEquals("clearStorage", PluginPreviewPolicy.op("clearStorage"))
        assertRejected { PluginPreviewPolicy.op("install") }
        assertRejected { PluginPreviewPolicy.op(null) }
        assertRejected { PluginPreviewPolicy.op("") }
    }

    @Test fun `network profiles are bounded and only slow may set a delay`() {
        assertEquals("online" to 0L, PluginPreviewPolicy.network(null).let { it.mode to it.delayMs })
        assertEquals("offline" to 0L, PluginPreviewPolicy.network(JSONObject().put("mode", "offline")).let { it.mode to it.delayMs })
        val slow = PluginPreviewPolicy.network(JSONObject().put("mode", "slow"))
        assertEquals("slow", slow.mode)
        assertEquals(PluginPreviewPolicy.DEFAULT_SLOW_NETWORK_DELAY_MS, slow.delayMs)
        assertEquals(PluginPreviewPolicy.MAX_NETWORK_DELAY_MS,
            PluginPreviewPolicy.network(JSONObject().put("mode", "slow").put("delayMs", PluginPreviewPolicy.MAX_NETWORK_DELAY_MS)).delayMs)
        assertRejected { PluginPreviewPolicy.network(JSONObject().put("mode", "fast")) }
        assertRejected { PluginPreviewPolicy.network(JSONObject().put("mode", "online").put("delayMs", 1)) }
        assertRejected { PluginPreviewPolicy.network(JSONObject().put("mode", "slow").put("delayMs", 0)) }
        assertRejected { PluginPreviewPolicy.network(JSONObject().put("mode", "slow").put("delayMs", PluginPreviewPolicy.MAX_NETWORK_DELAY_MS + 1)) }
    }

    @Test fun `snapshot text pagination is bounded`() {
        assertEquals(0 to PluginPreviewPolicy.DEFAULT_SNAPSHOT_LIMIT, PluginPreviewPolicy.snapshotPage(null, null))
        assertEquals(20 to 40, PluginPreviewPolicy.snapshotPage(20, 40))
        assertEquals(25000 to 2000, PluginPreviewPolicy.snapshotPage(25000, 2000))
        assertRejected { PluginPreviewPolicy.snapshotPage(-1, 10) }
        assertRejected { PluginPreviewPolicy.snapshotPage(PluginPreviewPolicy.MAX_SNAPSHOT_OFFSET + 1, 10) }
        assertRejected { PluginPreviewPolicy.snapshotPage(0, 0) }
        assertRejected { PluginPreviewPolicy.snapshotPage(0, PluginPreviewPolicy.MAX_SNAPSHOT_TEXT_LENGTH + 1) }
    }

    @Test fun `draft bundles are validated and get a temporary revision`() {
        val draft = JSONObject()
            .put("manifest", JSONObject().put("apiVersion", 1).put("id", "draft-plugin").put("title", "Draft")
                .put("version", "1").put("kind", "native").put("frameworkVersion", 1)
                .put("capabilities", JSONArray()))
            .put("view", JSONObject().put("type", "page").put("entry", "main.js").put("styles", JSONArray()))
            .put("resources", JSONObject().put("main.js", "export default {}"))
            .put("_revision", "installed-revision")
        val isolated = PluginPreviewPolicy.draftBundle(draft.toString())
        assertEquals("draft-plugin", isolated.getJSONObject("manifest").getString("id"))
        assertTrue(isolated.getString("_revision").matches(Regex("preview-[0-9a-f-]{36}")))
        assertRejected { PluginPreviewPolicy.draftBundle(draft.put("manifest", draft.getJSONObject("manifest").put("id", "Invalid")).toString()) }
    }

    @Test fun `session expires on idle time and on total lifetime`() {
        val now = 1_000_000L
        val session = session("s1", now)
        assertEquals(PluginPreviewPolicy.State.OPENING, session.state)
        session.ready(now)
        assertEquals(PluginPreviewPolicy.State.READY, session.state)
        assertFalse(session.expired(now + PluginPreviewPolicy.IDLE_TIMEOUT_MS - 1))
        assertTrue(session.expired(now + PluginPreviewPolicy.IDLE_TIMEOUT_MS))
        val touched = session("s2", now)
        touched.ready(now)
        touched.touch(now + PluginPreviewPolicy.IDLE_TIMEOUT_MS - 1)
        assertFalse(touched.expired(now + PluginPreviewPolicy.IDLE_TIMEOUT_MS))
        assertTrue(touched.expired(now + PluginPreviewPolicy.MAX_LIFETIME_MS))
    }

    @Test fun `closing a session is final and keeps the reason`() {
        val session = session("s3", 0L)
        session.ready(0L)
        session.close("预览空闲超时", 123L)
        session.close("重复关闭")
        assertEquals(PluginPreviewPolicy.State.CLOSED, session.state)
        assertEquals("预览空闲超时", session.closedReason)
        assertEquals(123L, session.closedAt)
        assertFalse(session.expired(PluginPreviewPolicy.MAX_LIFETIME_MS))
    }

    @Test fun `app background pauses both preview leases`() {
        val opened = 10_000L
        val session = session("s4", opened)
        session.ready(opened)
        session.pause(opened + 20_000L)
        assertFalse(session.expired(opened + PluginPreviewPolicy.MAX_LIFETIME_MS))
        assertEquals(20_000L, session.idleFor(opened + PluginPreviewPolicy.MAX_LIFETIME_MS))
        assertEquals(PluginPreviewPolicy.MAX_LIFETIME_MS - 20_000L,
            session.maxRemainingMs(opened + PluginPreviewPolicy.MAX_LIFETIME_MS))
        session.resume(opened + PluginPreviewPolicy.MAX_LIFETIME_MS)
        assertEquals(PluginPreviewPolicy.IDLE_TIMEOUT_MS - 20_000L, session.idleRemainingMs(opened + PluginPreviewPolicy.MAX_LIFETIME_MS))
        assertEquals(PluginPreviewPolicy.MAX_LIFETIME_MS - 20_000L, session.maxRemainingMs(opened + PluginPreviewPolicy.MAX_LIFETIME_MS))
    }

    private fun session(id: String, openedAt: Long) = PluginPreviewPolicy.Session(
        id, "demo-plugin", "https://plugin-demo-p000001.pd.local", "revision", true,
        PluginPreviewPolicy.Network("online", 0), openedAt,
    )

    private fun assertRejected(block: () -> Unit) {
        try { block(); throw AssertionError("应当拒绝该输入") }
        catch (expected: IllegalArgumentException) { /* expected */ }
    }
}
