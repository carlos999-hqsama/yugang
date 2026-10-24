package com.personaldashboard.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic plugin network route: public targets only, bounded headers, bodies, timeouts and sizes. */
class PluginPreviewNetworkTest {
    @Test fun `public http targets are accepted`() {
        val request = PluginFetch.parse(JSONObject().put("url", "https://example.com/api?q=1"))
        assertEquals("GET", request.method)
        assertEquals("example.com", request.url.host)
        assertEquals(PluginFetch.DEFAULT_TIMEOUT_MS, request.timeoutMs)
        assertEquals("auto", request.responseType)
        assertEquals(PluginFetch.parse(JSONObject().put("url", "http://example.com")).url.scheme, "http")
    }

    @Test fun `credential and non http targets are refused`() {
        for (url in listOf("ftp://example.com/x", "file:///etc/passwd",
            "https://user:secret@example.com/x", "not a url")) {
            assertRejected(url) { PluginFetch.validateUrl(url) }
        }
    }

    @Test fun `local services are available without host credentials`() {
        assertEquals("127.0.0.1", PluginFetch.validateUrl("http://127.0.0.1:8080").host)
        assertTrue(PluginFetch.parse(JSONObject().put("url", "http://192.168.1.2/api")).headers.isEmpty())
    }

    @Test fun `methods, headers and bodies are bounded`() {
        assertRejected { PluginFetch.parse(JSONObject().put("url", "https://example.com").put("method", "TRACE")) }
        assertRejected {
            PluginFetch.parse(JSONObject().put("url", "https://example.com")
                .put("headers", JSONObject().put("Cookie", "session=1")))
        }
        assertRejected {
            PluginFetch.parse(JSONObject().put("url", "https://example.com")
                .put("headers", JSONObject().put("X-Test", "a\r\nInjected: 1")))
        }
        assertRejected {
            PluginFetch.parse(JSONObject().put("url", "https://example.com")
                .put("body", "x".repeat(PluginFetch.MAX_REQUEST_BYTES + 1)))
        }
        val request = PluginFetch.parse(JSONObject().put("url", "https://example.com")
            .put("method", "post").put("headers", JSONObject().put("Authorization", "Bearer plugin-token"))
            .put("body", "hello"))
        assertEquals("POST", request.method)
        assertEquals("Bearer plugin-token", request.headers["Authorization"])
        assertEquals("hello", String(request.body!!))
    }

    @Test fun `timeouts and response sizes stay inside the budget`() {
        val fast = PluginFetch.parse(JSONObject().put("url", "https://example.com").put("timeoutMs", 10))
        assertEquals(1000L, fast.timeoutMs)
        val slow = PluginFetch.parse(JSONObject().put("url", "https://example.com").put("timeoutMs", 10_000_000))
        assertEquals(PluginFetch.MAX_TIMEOUT_MS, slow.timeoutMs)
        val big = PluginFetch.parse(JSONObject().put("url", "https://example.com").put("maxBytes", 99_000_000))
        assertEquals(PluginFetch.MAX_BINARY_BYTES.toLong(), big.maxBytes)
    }

    @Test fun `an explicit null body is not sent as text`() {
        val request = PluginFetch.parse(JSONObject().put("url", "https://example.com").put("body", JSONObject.NULL))
        assertEquals(null, request.body)
        assertEquals("application/json; charset=utf-8", request.contentType)
    }

    @Test fun `response type is closed`() {
        assertRejected { PluginFetch.parse(JSONObject().put("url", "https://example.com").put("responseType", "raw")) }
    }

    private fun assertRejected(url: String, block: () -> Unit) {
        try { block(); throw AssertionError("应当拒绝该地址：$url") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message?.isNotEmpty() == true) }
    }

    private fun assertRejected(block: () -> Unit) {
        try { block(); throw AssertionError("应当拒绝该输入") }
        catch (expected: IllegalArgumentException) { /* expected */ }
    }
}
