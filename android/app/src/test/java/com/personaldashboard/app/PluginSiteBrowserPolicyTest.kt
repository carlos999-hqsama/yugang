package com.personaldashboard.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginSiteBrowserPolicyTest {
    @Test
    fun `http and https urls are accepted without credentials`() {
        assertEquals(
            "http://library.example.org/forever/home/",
            PluginSiteBrowserPolicy.url("http://library.example.org/forever/home/"),
        )
        assertEquals(
            "https://example.test/form?a=1",
            PluginSiteBrowserPolicy.url("https://example.test/form?a=1"),
        )
        assertEquals(
            "https://[2001:db8::1]/",
            PluginSiteBrowserPolicy.url("https://[2001:db8::1]/"),
        )
    }

    @Test
    fun `non-http schemes credentials and malformed urls are rejected`() {
        for (url in listOf(
            "file:///system/file.txt",
            "javascript:alert(1)",
            "data:text/html,<script>1</script>",
            "intent://example.test/#Intent;scheme=https;end",
            "blob:https://example.test/id",
            "https://user:secret@example.test/",
            "https://example.test/a b",
            "https:///missing-host",
            "",
        )) {
            assertRejected { PluginSiteBrowserPolicy.url(url) }
        }
    }

    @Test
    fun `plugin requests cannot claim a gesture`() {
        assertEquals(
            "https://example.test/",
            PluginSiteBrowserPolicy.openRequest(JSONObject().put("url", "https://example.test/")),
        )
        assertRejected {
            PluginSiteBrowserPolicy.openRequest(
                JSONObject()
                    .put("url", "https://example.test/")
                    .put("userGesture", true),
            )
        }
        assertRejected {
            PluginSiteBrowserPolicy.openRequest(JSONObject().put("url", "javascript:alert(1)"))
        }
    }

    @Test
    fun `profiles are injective revision independent and never default`() {
        assertEquals(
            PluginSiteBrowserPolicy.profileName("demo-plugin"),
            PluginSiteBrowserPolicy.profileName("demo-plugin"),
        )
        assertEquals("yg11hm7ouvm6njq", PluginSiteBrowserPolicy.profileName("demo-plugin"))
        assertEquals("yg05ro2i3", PluginSiteBrowserPolicy.profileName("other"))
        assertNotEquals(
            PluginSiteBrowserPolicy.profileName("demo-plugin"),
            PluginSiteBrowserPolicy.profileName("other"),
        )
        val longest = PluginSiteBrowserPolicy.profileName("a".repeat(48))
        assertTrue(longest.matches(Regex("yg[0-9]{2}[a-z0-9]+")))
        assertTrue(longest.length <= 53)
    }

    @Test
    fun `close receipts contain only the contract fields`() {
        val receipt = PluginSiteBrowserPolicy.closeReceipt(true, "https://example.test/final")
        val names = mutableListOf<String>()
        receipt.keys().forEachRemaining(names::add)
        assertEquals(setOf("opened", "closed", "finalUrl"), names.toSet())
        assertEquals(true, receipt.getBoolean("opened"))
        assertEquals(true, receipt.getBoolean("closed"))
        assertEquals("https://example.test/final", receipt.getString("finalUrl"))
    }

    @Test
    fun `download names are bounded and cannot carry paths or headers`() {
        assertEquals(
            "secret.txt",
            PluginSiteBrowserPolicy.downloadName(
                "attachment; filename=\"../../secret.txt\r\n\"",
                "https://example.test/download",
            ),
        )
        assertEquals(
            "下载.txt",
            PluginSiteBrowserPolicy.downloadName(
                "attachment; filename*=UTF-8''%E4%B8%8B%E8%BD%BD.txt",
                "https://example.test/download",
            ),
        )
        assertEquals(
            "report.pdf",
            PluginSiteBrowserPolicy.downloadName(null, "https://example.test/files/report.pdf?x=1"),
        )
        assertEquals("download", PluginSiteBrowserPolicy.downloadName(null, "https://example.test/"))
        assertEquals(
            "a".repeat(PluginSiteBrowserPolicy.MAX_FILENAME_CHARS),
            PluginSiteBrowserPolicy.downloadName(
                "attachment; filename=${"a".repeat(200)}",
                "https://example.test/download",
            ),
        )
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }
}
