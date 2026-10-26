package com.personaldashboard.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginSiteBrowserPolicyTest {
    @Test
    fun `reports retain CDN resources without granting external main frames local files`() {
        assertTrue(PluginSiteBrowserPolicy.documentNetworkResource("https://cdn.example.test/chart.js", false))
        assertFalse(PluginSiteBrowserPolicy.documentNetworkResource("https://cdn.example.test/chart.js", true))
        assertFalse(PluginSiteBrowserPolicy.documentNetworkResource("https://dshfile.pd.local/BOT/a/chart.js", false))
        assertFalse(PluginSiteBrowserPolicy.documentNetworkResource("file:///secret", false))
    }

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
    fun `core and document profiles are stable and cannot collide with plugin profiles`() {
        assertEquals("ygxcorebrowser1", PluginSiteBrowserPolicy.CORE_PROFILE_NAME)
        assertEquals("ygxdocumentbrowser1", PluginSiteBrowserPolicy.DOCUMENT_PROFILE_NAME)
        assertNotEquals(
            PluginSiteBrowserPolicy.CORE_PROFILE_NAME,
            PluginSiteBrowserPolicy.DOCUMENT_PROFILE_NAME,
        )

        val pluginPattern = Regex("yg[0-9]{2}[a-z0-9]+")
        assertFalse(PluginSiteBrowserPolicy.CORE_PROFILE_NAME.matches(pluginPattern))
        assertFalse(PluginSiteBrowserPolicy.DOCUMENT_PROFILE_NAME.matches(pluginPattern))
        val pluginProfiles =
            listOf("core", "document", "demo-plugin", "a00", "a".repeat(48))
                .map(PluginSiteBrowserPolicy::profileName)
        pluginProfiles.forEach { assertTrue(it.matches(pluginPattern)) }
        assertFalse(PluginSiteBrowserPolicy.CORE_PROFILE_NAME in pluginProfiles)
        assertFalse(PluginSiteBrowserPolicy.DOCUMENT_PROFILE_NAME in pluginProfiles)
    }

    @Test
    fun `core open accepts only url and title and reserves the document origin`() {
        assertEquals(
            "https://example.test/read?from=core",
            PluginSiteBrowserPolicy.openCoreRequest(
                JSONObject()
                    .put("url", "https://example.test/read?from=core")
                    .put("title", "阅读"),
            ),
        )
        assertRejected {
            PluginSiteBrowserPolicy.openCoreRequest(
                JSONObject()
                    .put("url", "https://example.test/")
                    .put("userGesture", true),
            )
        }
        assertRejected {
            PluginSiteBrowserPolicy.openCoreRequest(JSONObject().put("title", "缺少地址"))
        }
        assertRejected {
            PluginSiteBrowserPolicy.openCoreRequest(
                JSONObject().put("url", "https://dshfile.pd.local/private/report.html"),
            )
        }
    }

    @Test
    fun `document open and subresources require the controlled origin`() {
        val initial = "https://dshfile.pd.local/documents/report.html"
        assertEquals(initial, PluginSiteBrowserPolicy.documentInitialUrl(initial))
        assertEquals(
            "https://dshfile.pd.local/assets/report.css",
            PluginSiteBrowserPolicy.documentResourceUrl("https://dshfile.pd.local/assets/report.css"),
        )
        assertEquals(
            "https://dshfile.pd.local/media/narration.mp3",
            PluginSiteBrowserPolicy.documentResourceUrl("https://dshfile.pd.local/media/narration.mp3"),
        )

        for (url in listOf(
            "https://dshfile.pd.local/",
            "http://dshfile.pd.local/documents/report.html",
            "https://example.test/documents/report.html",
            "https://dshfile.pd.local.example.test/documents/report.html",
            "https://user@dshfile.pd.local/documents/report.html",
            "https://dshfile.pd.local:444/documents/report.html",
        )) {
            assertRejected { PluginSiteBrowserPolicy.documentInitialUrl(url) }
        }
        assertRejected {
            PluginSiteBrowserPolicy.documentResourceUrl("https://example.test/tracker.js")
        }
    }

    @Test
    fun `document handoff requires a real main frame GET gesture`() {
        val external = "https://example.test/next"
        assertEquals(
            external,
            PluginSiteBrowserPolicy.documentHandoffUrl(external, true, true, "GET"),
        )
        assertNull(
            PluginSiteBrowserPolicy.documentHandoffUrl(
                "https://dshfile.pd.local/documents/next.html",
                true,
                true,
                "GET",
            ),
        )
        assertNull(PluginSiteBrowserPolicy.documentHandoffUrl(external, true, false, "GET"))
        assertNull(PluginSiteBrowserPolicy.documentHandoffUrl(external, false, true, "GET"))
        assertNull(PluginSiteBrowserPolicy.documentHandoffUrl(external, true, true, "POST"))
    }

    @Test
    fun `document handoff receipt carries an explicit Core target`() {
        val receipt =
            PluginSiteBrowserPolicy.documentHandoffReceipt(
                true,
                "https://dshfile.pd.local/documents/report.html",
                "https://example.test/next",
            )
        assertEquals(true, receipt.getBoolean("opened"))
        assertEquals(true, receipt.getBoolean("closed"))
        assertEquals(
            "https://dshfile.pd.local/documents/report.html",
            receipt.getString("finalUrl"),
        )
        assertEquals("https://example.test/next", receipt.getString("openCoreUrl"))
        assertEquals(4, receipt.length())
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

    @Test
    fun `download size is bounded by content length and the actual stream`() {
        val max = PluginSiteBrowserPolicy.MAX_DOWNLOAD_BYTES

        assertEquals(null, PluginSiteBrowserPolicy.contentLength(null))
        assertEquals(0L, PluginSiteBrowserPolicy.contentLength("0"))
        assertEquals(max, PluginSiteBrowserPolicy.contentLength(max.toString()))
        val advertisedOverLimit =
            assertThrows(PluginSiteBrowserPolicy.DownloadSizeLimitException::class.java) {
                PluginSiteBrowserPolicy.contentLength((max + 1).toString())
            }
        assertEquals("下载超过 256 MiB 上限", advertisedOverLimit.message)

        val chunk = 64 * 1024L
        val advertised = PluginSiteBrowserPolicy.contentLength(max.toString())
        var actual = 0L
        repeat((max / chunk).toInt()) {
            actual = PluginSiteBrowserPolicy.downloadSize(advertised, actual + chunk)
        }
        assertEquals(max, actual)
        assertThrows(PluginSiteBrowserPolicy.DownloadSizeLimitException::class.java) {
            PluginSiteBrowserPolicy.downloadSize(null, actual + 1)
        }
        assertThrows(PluginSiteBrowserPolicy.DownloadSizeLimitException::class.java) {
            PluginSiteBrowserPolicy.downloadSize(max - 1, max + 1)
        }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }
}
