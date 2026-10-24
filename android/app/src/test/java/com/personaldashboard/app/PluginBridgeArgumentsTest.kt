package com.personaldashboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBridgeArgumentsTest {
    @Test
    fun `empty object stays the valid default request`() {
        assertEquals(0, PluginBridgeArguments.parse("{}").length())
        assertEquals(0, PluginBridgeArguments.parse(null).length())
    }

    @Test
    fun `valid object fields are retained`() {
        val arguments =
            PluginBridgeArguments.parse(
                """{"dshMode":"embedded","sampleCount":60,"nested":{"enabled":true}}""",
            )

        assertEquals("embedded", arguments.getString("dshMode"))
        assertEquals(60, arguments.getInt("sampleCount"))
        assertTrue(arguments.getJSONObject("nested").getBoolean("enabled"))
    }

    @Test
    fun `invalid JSON cannot become a dispatchable empty request`() {
        val invalid = listOf(
            "",
            "{",
            """{"sampleCount":""",
            "[]",
            "[{}]",
            "null",
            "true",
            "42",
            "\"text\"",
            "{}{}",
            "{} trailing",
        )
        for (raw in invalid) {
            var dispatched = false
            val arguments = runCatching { PluginBridgeArguments.parse(raw) }.getOrNull()
            if (arguments != null) dispatched = true
            assertFalse("畸形参数不能变成可分发请求：$raw", dispatched)
        }
    }

    @Test
    fun `invalid argument errors are safe and preserve their cause`() {
        val raw = """{"token":"private-value"} trailing"""
        try {
            PluginBridgeArguments.parse(raw)
            throw AssertionError("应当拒绝畸形参数")
        } catch (expected: IllegalArgumentException) {
            assertEquals(PluginBridgeArguments.ERROR_MESSAGE, expected.message)
            assertFalse(expected.message.orEmpty().contains("private-value"))
            assertNotNull(expected.cause)
        }
    }

    @Test
    fun `stream open failures use their consumed event routes`() {
        assertEquals(
            PluginBridgeArguments.ErrorRoute.SSE,
            PluginBridgeArguments.errorRoute("sseOpen"),
        )
        assertEquals(
            PluginBridgeArguments.ErrorRoute.WS,
            PluginBridgeArguments.errorRoute("wsOpen"),
        )
        assertEquals(
            PluginBridgeArguments.ErrorRoute.CALLBACK,
            PluginBridgeArguments.errorRoute("setConfig"),
        )
    }
}
