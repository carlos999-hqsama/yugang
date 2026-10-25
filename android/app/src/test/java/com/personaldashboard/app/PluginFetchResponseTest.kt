package com.personaldashboard.app

import java.util.Base64
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real HTTP response path used by native.fetch MIME classification. */
class PluginFetchResponseTest {
    @Test
    fun `auto returns nonempty text for rss and structured xml or json`() {
        MockWebServer().use { server ->
            server.start()

            val cases =
                listOf(
                    Triple("/rss", "application/rss+xml; charset=utf-8", "<rss><channel /></rss>"),
                    Triple("/vendor-xml", "application/vnd.example.feed+xml", "<feed />"),
                    Triple("/vendor-json", "application/vnd.example.entity+json", "{\"ok\":true}"),
                )
            cases.forEach { (path, contentType, body) ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", contentType)
                        .setBody(body),
                )
                val result = PluginFetch.fetch(JSONObject().put("url", server.url(path).toString()))

                assertEquals(path, 200, result.getInt("status"))
                assertTrue(path, result.getBoolean("ok"))
                assertEquals(path, body, result.getString("text"))
                assertEquals(path, body.toByteArray(Charsets.UTF_8).size.toLong(), result.getLong("bytes"))
                assertNull(path, result.opt("base64"))
            }
        }
    }

    @Test
    fun `auto binary and explicit response types retain their base64 or text semantics`() {
        MockWebServer().use { server ->
            server.start()
            val binary = byteArrayOf(0x00, 0x01, 0x02, 0xff.toByte())

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody(okio.Buffer().write(binary)),
            )
            val autoBinary =
                PluginFetch.fetch(JSONObject().put("url", server.url("/auto-binary").toString()))
            assertEquals(200, autoBinary.getInt("status"))
            assertNull(autoBinary.opt("text"))
            assertTrue(Base64.getDecoder().decode(autoBinary.getString("base64")).contentEquals(binary))

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody(okio.Buffer().write(binary)),
            )
            val explicitText =
                PluginFetch.fetch(
                    JSONObject()
                        .put("url", server.url("/explicit-text").toString())
                        .put("responseType", "text"),
                )
            assertEquals(4, explicitText.getLong("bytes"))
            assertEquals("\u0000\u0001\u0002\uFFFD", explicitText.getString("text"))
            assertNull(explicitText.opt("base64"))

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/rss+xml; charset=utf-8")
                    .setBody("<rss />"),
            )
            val explicitBase64 =
                PluginFetch.fetch(
                    JSONObject()
                        .put("url", server.url("/explicit-base64").toString())
                        .put("responseType", "base64"),
                )
            assertEquals(
                "<rss />",
                String(Base64.getDecoder().decode(explicitBase64.getString("base64"))),
            )
            assertNull(explicitBase64.opt("text"))
        }
    }

    @Test
    fun `empty rss response remains text with an empty value`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/rss+xml; charset=utf-8")
                    .setBody(""),
            )
            server.start()

            val result = PluginFetch.fetch(JSONObject().put("url", server.url("/empty").toString()))

            assertEquals(200, result.getInt("status"))
            assertEquals(0L, result.getLong("bytes"))
            assertTrue(result.getString("text").isEmpty())
            assertNull(result.opt("base64"))
            assertFalse(result.getBoolean("truncated"))
        }
    }
}
