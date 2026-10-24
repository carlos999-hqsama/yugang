package com.personaldashboard.app

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTtsPolicyTest {
    @org.junit.Test
    fun nativeActionPlaybackMustLeaveTimeForItsResult() {
        org.junit.Assert.assertFalse(PluginTtsPolicy.fitsActionPlayback(80_000, 72_000))
        org.junit.Assert.assertFalse(PluginTtsPolicy.fitsActionPlayback(120_000, 72_000))
        org.junit.Assert.assertTrue(PluginTtsPolicy.fitsActionPlayback(67_000, 71_000))
        org.junit.Assert.assertFalse(PluginTtsPolicy.fitsActionPlayback(1_000, 0))
        org.junit.Assert.assertFalse(PluginTtsPolicy.fitsActionPlayback(0, 72_000))
    }

    private fun wav(): ByteArray =
        ByteArray(44) {
            when (it) {
                0 -> 'R'.code.toByte()
                1 -> 'I'.code.toByte()
                2 -> 'F'.code.toByte()
                3 -> 'F'.code.toByte()
                8 -> 'W'.code.toByte()
                9 -> 'A'.code.toByte()
                10 -> 'V'.code.toByte()
                11 -> 'E'.code.toByte()
                else -> 0
            }
        }

    private fun mp3(): ByteArray =
        ByteArray(64) {
            when (it) {
                0 -> 'I'.code.toByte()
                1 -> 'D'.code.toByte()
                2 -> '3'.code.toByte()
                else -> 0
            }
        }

    @Test
    fun `text limits reject blank oversized and nul input`() {
        assertEquals("你好", PluginTtsPolicy.validateText("  你好 "))
        listOf("", "   ", "a\u0000").forEach { text ->
            try {
                PluginTtsPolicy.validateText(text)
                throw AssertionError("Expected rejection: $text")
            } catch (_: IllegalArgumentException) {}
        }
        try {
            PluginTtsPolicy.validateText("长".repeat(PluginTtsPolicy.MAX_TEXT_CHARS + 1))
            throw AssertionError("Expected oversized rejection")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `audio format comes from bytes rather than names`() {
        assertEquals("wav", PluginTtsPolicy.detectFormat(wav()))
        assertEquals("mp3", PluginTtsPolicy.detectFormat(mp3()))
        assertNull(PluginTtsPolicy.detectFormat(ByteArray(64)))
    }

    @Test
    fun `fallback accepts only structured base64 mp3`() {
        val encoded = Base64.getEncoder().encodeToString(mp3())
        val audio =
            PluginTtsPolicy.parseFallback(
                "application/json",
                JSONObject().put("errno", 0).put("data", encoded).toString().toByteArray(),
            )
        assertEquals("mp3", audio.format)
        assertTrue(audio.bytes.size == mp3().size)

        listOf(
                JSONObject().put("errno", 1).put("data", encoded).toString().toByteArray(),
                JSONObject()
                    .put("errno", 0)
                    .put("data", Base64.getEncoder().encodeToString(wav()))
                    .toString()
                    .toByteArray(),
                "not-json".toByteArray(),
            )
            .forEach { body ->
                try {
                    PluginTtsPolicy.parseFallback("application/json", body)
                    throw AssertionError("Expected structured fallback rejection")
                } catch (_: IllegalStateException) {}
            }
    }

    @Test
    fun `contract admits tts capability`() {
        assertTrue("plugin.tts" in PluginContract.capabilities)
    }

    @Test
    fun fallbackClientInitializesWithoutKeepingIdleConnectionsOrFollowingRedirects() {
        val client = PluginTtsPolicy.fallbackClient()
        try {
            assertEquals(0, client.connectionPool.idleConnectionCount())
            assertEquals(12000, client.callTimeoutMillis)
            assertEquals(false, client.followRedirects)
            assertEquals(false, client.followSslRedirects)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
