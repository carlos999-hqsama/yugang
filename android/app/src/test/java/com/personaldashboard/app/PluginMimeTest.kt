package com.personaldashboard.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginMimeTest {
    @Test
    fun `feed xml json and established text media types are text`() {
        listOf(
            "application/rss+xml",
            "application/atom+xml",
            "application/xml",
            "text/xml",
            "application/vnd.example.feed+xml",
            "application/vnd.example.entity+json",
            "text/plain",
            "application/json",
            "application/javascript",
            "application/x-www-form-urlencoded",
            "application/graphql",
        ).forEach { contentType ->
            assertTrue(contentType, PluginMime.isText(contentType))
        }
    }

    @Test
    fun `parameters case and surrounding whitespace are normalized`() {
        listOf(
            "  Application/RSS+XML ; charset=UTF-8  ",
            "\tAPPLICATION/ATOM+XML\t;",
            "Application/XML; Charset=\"UTF-8\"",
            "TEXT/XML; charset=utf-8",
            "Application/Vendor.Example+JSON; charset=utf-8",
            "Application/JSON; charset=utf-8",
            "  TEXT/HTML  ",
        ).forEach { contentType ->
            assertTrue(contentType, PluginMime.isText(contentType))
        }
    }

    @Test
    fun `binary and malformed media types stay binary`() {
        listOf(
            null,
            "",
            "   ; charset=utf-8",
            "application/octet-stream",
            "image/png",
            "application/pdf",
            "application/xml2",
            "application/jsonp",
            "application/+xml",
            "application",
            "application/",
            "text/",
            "application/xml/extra",
        ).forEach { contentType ->
            assertFalse(contentType ?: "null", PluginMime.isText(contentType))
        }
    }
}
