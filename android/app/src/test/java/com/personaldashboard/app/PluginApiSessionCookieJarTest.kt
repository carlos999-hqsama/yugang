package com.personaldashboard.app

import java.nio.file.Files
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Confirms OkHttp invokes the implementation and sends/deletes cookies through a real client. */
class PluginApiSessionCookieJarTest {
    private class ReversibleCipher : PluginApiSessionCipher {
        override fun seal(plaintext: ByteArray): ByteArray =
            byteArrayOf('P'.code.toByte(), 'D'.code.toByte(), 0) +
                plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

        override fun open(sealed: ByteArray): ByteArray =
            sealed.drop(3).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    @Test(timeout = 15000)
    fun `okhttp stores sends updates and deletes cookies through the jar`() {
        MockWebServer().use { server ->
            server.start()
            val store =
                PluginApiSessionStore(
                    Files.createTempDirectory("pd-plugin-api-session-http").toFile(),
                    ReversibleCipher(),
                )
            val jar =
                PluginApiSessionCookieJar(
                    PluginApiSessionPolicy.contextId("http-plugin"),
                    store,
                )
            val client = OkHttpClient.Builder().cookieJar(jar).build()
            try {
                server.enqueue(MockResponse().addHeader("Set-Cookie", "sid=http-secret; Path=/"))
                client
                    .newCall(Request.Builder().url(server.url("/login")).build())
                    .execute()
                    .use { assertEquals(200, it.code) }
                server.enqueue(MockResponse())
                client
                    .newCall(Request.Builder().url(server.url("/items")).build())
                    .execute()
                    .use { assertEquals(200, it.code) }
                assertNull(server.takeRequest().getHeader("Cookie"))
                assertEquals("sid=http-secret", server.takeRequest().getHeader("Cookie"))

                server.enqueue(
                    MockResponse().addHeader("Set-Cookie", "sid=deleted; Max-Age=0; Path=/"),
                )
                client
                    .newCall(Request.Builder().url(server.url("/logout")).build())
                    .execute()
                    .use { assertEquals(200, it.code) }
                server.enqueue(MockResponse())
                client
                    .newCall(Request.Builder().url(server.url("/after-logout")).build())
                    .execute()
                    .use { assertEquals(200, it.code) }
                assertEquals("sid=http-secret", server.takeRequest().getHeader("Cookie"))
                assertNull(server.takeRequest().getHeader("Cookie"))
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test(timeout = 15000)
    fun `real client and jar apply cross-host path and secure matching`() {
        MockWebServer().use { server ->
            server.start()
            val store =
                PluginApiSessionStore(
                    Files.createTempDirectory("pd-plugin-api-session-rules").toFile(),
                    ReversibleCipher(),
                )
            val jar =
                PluginApiSessionCookieJar(
                    PluginApiSessionPolicy.contextId("rules-plugin"),
                    store,
                )
            val client = OkHttpClient.Builder().cookieJar(jar).build()
            try {
                server.enqueue(
                    MockResponse()
                        .addHeader("Set-Cookie", "api=path; Path=/api")
                        .addHeader("Set-Cookie", "root=host; Path=/")
                        .addHeader("Set-Cookie", "safe=secure; Secure; Path=/api"),
                )
                client
                    .newCall(Request.Builder().url(server.url("/seed")).build())
                    .execute()
                    .use { assertEquals(200, it.code) }
                server.enqueue(MockResponse())
                client
                    .newCall(Request.Builder().url(server.url("/api/items")).build())
                    .execute()
                    .use { assertEquals(200, it.code) }
                server.takeRequest()
                assertEquals("api=path; root=host", server.takeRequest().getHeader("Cookie"))

                server.enqueue(MockResponse())
                client
                    .newCall(Request.Builder().url(server.url("/")).build())
                    .execute()
                    .use { assertEquals(200, it.code) }
                assertEquals("root=host", server.takeRequest().getHeader("Cookie"))
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }

            val domainOrigin = "https://www.example.com/api".toHttpUrl()
            jar.saveFromResponse(
                domainOrigin,
                listOf(
                    okhttp3.Cookie.parse(
                        domainOrigin,
                        "wide=domain; Domain=example.com; Path=/",
                    )!!,
                    okhttp3.Cookie.parse(domainOrigin, "safe-wide=secure; Secure; Domain=example.com")!!,
                ),
            )
            assertEquals(
                setOf("safe-wide=secure", "wide=domain"),
                jar.loadForRequest("https://other.example.com/api".toHttpUrl())
                    .map { "${it.name}=${it.value}" }.toSet(),
            )
            assertEquals(
                setOf("wide=domain"),
                jar.loadForRequest("http://other.example.com/api".toHttpUrl())
                    .map { "${it.name}=${it.value}" }.toSet(),
            )
        }
    }
}
