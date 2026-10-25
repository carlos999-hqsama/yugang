package com.personaldashboard.app

import java.io.File
import java.nio.file.Files
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure and persistence tests use real OkHttp Cookie parsing/matching semantics. */
class PluginApiSessionPolicyTest {
    private class ReversibleCipher : PluginApiSessionCipher {
        override fun seal(plaintext: ByteArray): ByteArray =
            byteArrayOf('P'.code.toByte(), 'D'.code.toByte()) +
                ByteArray(1) + plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

        override fun open(sealed: ByteArray): ByteArray {
            check(sealed.size > 3)
            return sealed.drop(3).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    private fun directory(): File = Files.createTempDirectory("pd-plugin-api-session").toFile()

    private fun jar(
        store: PluginApiSessionStore,
        pluginId: String,
        context: String? = null,
    ) = PluginApiSessionCookieJar(
        PluginApiSessionPolicy.contextId(pluginId, context),
        store,
    )

    private fun parse(
        url: String,
        vararg setCookies: String,
    ): List<Cookie> {
        val headers = Headers.Builder()
        setCookies.forEach { headers.add("Set-Cookie", it) }
        return Cookie.parseAll(url.toHttpUrl(), headers.build())
    }

    private fun header(cookies: List<Cookie>) = cookies.joinToString("; ") { "${it.name}=${it.value}" }

    private fun values(cookies: List<Cookie>) = cookies.map { "${it.name}=${it.value}" }.toSet()

    @Test
    fun `session operation without context uses the default host context`() {
        val factory = PluginApiSessionFactory(directory(), ReversibleCipher())
        val result = factory.call("default-context-plugin", JSONObject().put("op", "status"))
        assertEquals("default", result.getString("context"))
        assertEquals(0, result.getInt("cookieCount"))
    }

    @Test
    fun `credentials include requires a host plugin identity`() {
        assertFalse(
            PluginApiSessionPolicy.credentialsIncluded(
                JSONObject().put("credentials", "omit"),
            ),
        )
        assertTrue(
            PluginApiSessionPolicy.credentialsIncluded(
                JSONObject().put("credentials", "include"),
            ),
        )
        assertNull(
            PluginApiSessionPolicy.credentialsContext(JSONObject().put("credentials", "omit"), null),
        )
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PluginApiSessionPolicy.credentialsContext(
                    JSONObject().put("credentials", "include"),
                    null,
                )
            }
        assertEquals("缺少插件身份，不能使用持久登录会话", error.message)
    }

    @Test
    fun `two plugins on one host have isolated cookie jars and opaque files`() {
        val root = directory()
        val store = PluginApiSessionStore(root, ReversibleCipher())
        val first = jar(store, "first-plugin")
        val second = jar(store, "second-plugin")
        val url = "https://service.example.com/login".toHttpUrl()

        first.saveFromResponse(url, parse(url.toString(), "sid=first-secret; Path=/"))
        second.saveFromResponse(url, parse(url.toString(), "sid=second-secret; Path=/"))

        assertEquals("sid=first-secret", header(first.loadForRequest(url)))
        assertEquals("sid=second-secret", header(second.loadForRequest(url)))
        root.walkBottomUp().filter { it.isFile }.forEach { file ->
            val bytes = file.readBytes()
            assertFalse(file.path, bytes.toString(Charsets.ISO_8859_1).contains("first-secret"))
            assertFalse(file.path, bytes.toString(Charsets.ISO_8859_1).contains("second-secret"))
        }
    }

    @Test
    fun `domain path host-only and secure rules follow real OkHttp cookies`() {
        val store = PluginApiSessionStore(directory(), ReversibleCipher())
        val session = jar(store, "policy-plugin")
        val origin = "https://www.example.com/api/login".toHttpUrl()
        session.saveFromResponse(
            origin,
            parse(
                origin.toString(),
                "wide=domain; Domain=example.com; Path=/",
                "api=path; Path=/api",
                "exact=host; Path=/",
                "safe=secure; Secure; Path=/",
            ),
        )

        assertEquals(
            listOf("api"),
            session.loadForRequest("https://www.example.com/api/items".toHttpUrl())
                .map { it.name }.take(1),
        )
        assertEquals(
            setOf("api=path", "exact=host", "safe=secure", "wide=domain"),
            values(session.loadForRequest("https://www.example.com/api/items".toHttpUrl())),
        )
        assertEquals(
            setOf("exact=host", "safe=secure", "wide=domain"),
            values(session.loadForRequest("https://www.example.com/".toHttpUrl())),
        )
        assertEquals(
            setOf("wide=domain"),
            values(session.loadForRequest("https://other.example.com/api/items".toHttpUrl())),
        )
        assertEquals(
            setOf("api=path", "exact=host", "wide=domain"),
            values(session.loadForRequest("http://www.example.com/api/items".toHttpUrl())),
        )
    }

    @Test
    fun `expiry deletion replacement and session cookies survive a store reload`() {
        val root = directory()
        val store = PluginApiSessionStore(root, ReversibleCipher())
        val first = jar(store, "persist-plugin", "login")
        val origin = "https://service.example.com/api/login".toHttpUrl()
        first.saveFromResponse(
            origin,
            parse(
                origin.toString(),
                "sid=old; Max-Age=3600; Path=/api",
                "browser=session; Path=/",
                "temporary=one-second; Max-Age=1; Path=/",
            ),
        )
        first.saveFromResponse(origin, parse(origin.toString(), "sid=new; Max-Age=3600; Path=/api"))
        first.saveFromResponse(
            origin,
            parse(origin.toString(), "temporary=deleted; Max-Age=0; Path=/"),
        )

        val reloaded = jar(store, "persist-plugin", "login")
        val target = "https://service.example.com/api/items".toHttpUrl()
        assertEquals(
            setOf("sid=new", "browser=session"),
            values(reloaded.loadForRequest(target)),
        )
        assertEquals(2, reloaded.status().getInt("cookieCount"))
        assertEquals(1, reloaded.status().getInt("sessionCookieCount"))

        reloaded.clear()
        assertEquals(0, reloaded.loadForRequest(target).size)
        assertEquals(0, jar(store, "persist-plugin", "login").loadForRequest(target).size)
    }

    @Test
    fun `late response after context clear cannot recreate the revoked jar`() {
        val root = directory()
        val factory = PluginApiSessionFactory(root, ReversibleCipher())
        val id = PluginApiSessionPolicy.contextId("clear-plugin", "login")
        val old = factory.cookieJar(id) as PluginApiSessionCookieJar
        val other = factory.cookieJar(PluginApiSessionPolicy.contextId("other-plugin", "login"))
        val url = "https://service.example.com/".toHttpUrl()
        old.saveFromResponse(url, parse(url.toString(), "sid=old-clear; Path=/"))
        other.saveFromResponse(url, parse(url.toString(), "sid=other-clear; Path=/"))

        factory.clear(id)
        old.saveFromResponse(url, parse(url.toString(), "sid=late-clear; Path=/"))

        val fresh = factory.cookieJar(id) as PluginApiSessionCookieJar
        assertTrue(old !== fresh)
        assertEquals(0, old.loadForRequest(url).size)
        assertEquals(0, fresh.loadForRequest(url).size)
        assertEquals("sid=other-clear", header(other.loadForRequest(url)))
    }

    @Test
    fun `late response after uninstall cannot recreate that plugin jar`() {
        val root = directory()
        val factory = PluginApiSessionFactory(root, ReversibleCipher())
        val removedId = PluginApiSessionPolicy.contextId("uninstall-plugin")
        val keptId = PluginApiSessionPolicy.contextId("kept-plugin")
        val removed = factory.cookieJar(removedId) as PluginApiSessionCookieJar
        val kept = factory.cookieJar(keptId) as PluginApiSessionCookieJar
        val url = "https://service.example.com/".toHttpUrl()
        removed.saveFromResponse(url, parse(url.toString(), "sid=removed-late; Path=/"))
        kept.saveFromResponse(url, parse(url.toString(), "sid=kept-late; Path=/"))

        factory.clearPlugin(removedId.pluginId)
        removed.saveFromResponse(url, parse(url.toString(), "sid=must-not-return; Path=/"))

        val freshRemoved = factory.cookieJar(removedId) as PluginApiSessionCookieJar
        assertEquals(0, removed.loadForRequest(url).size)
        assertEquals(0, freshRemoved.loadForRequest(url).size)
        assertEquals("sid=kept-late", header(kept.loadForRequest(url)))
    }

    @Test
    fun `expired cookies are neither sent nor persisted`() {
        val now = 1_000_000L
        val domain = "example.com"
        val expired =
            Cookie.Builder()
                .name("expired")
                .value("value")
                .expiresAt(now - 1)
                .hostOnlyDomain(domain)
                .path("/")
                .build()
        val persistent =
            Cookie.Builder()
                .name("persistent")
                .value("value")
                .expiresAt(now + 1_000)
                .hostOnlyDomain(domain)
                .path("/")
                .build()

        assertEquals(listOf(persistent), PluginApiSessionPolicy.activeCookies(listOf(expired, persistent), now))
        assertEquals(
            listOf(persistent),
            PluginApiSessionPolicy.replace(listOf(expired, persistent), listOf(expired), now),
        )
        assertTrue(
            PluginApiSessionPolicy
                .decodeCookies(PluginApiSessionPolicy.encodeCookies(listOf(expired), now))
                .isEmpty(),
        )
    }

    @Test
    fun `uninstall cleanup is exact while other plugins and contexts remain`() {
        val root = directory()
        val store = PluginApiSessionStore(root, ReversibleCipher())
        val url = "https://service.example.com/".toHttpUrl()
        jar(store, "remove-plugin", "a").saveFromResponse(
            url,
            parse(url.toString(), "sid=removed-one; Path=/"),
        )
        jar(store, "remove-plugin", "b").saveFromResponse(
            url,
            parse(url.toString(), "sid=removed-two; Path=/"),
        )
        jar(store, "keep-plugin").saveFromResponse(
            url,
            parse(url.toString(), "sid=kept; Path=/"),
        )

        store.clearPlugin("remove-plugin")

        assertEquals(0, jar(store, "remove-plugin", "a").loadForRequest(url).size)
        assertEquals(0, jar(store, "remove-plugin", "b").loadForRequest(url).size)
        assertEquals("sid=kept", header(jar(store, "keep-plugin").loadForRequest(url)))
    }

    @Test
    fun `status exposes metadata only and never a login claim`() {
        val store = PluginApiSessionStore(directory(), ReversibleCipher())
        val session = jar(store, "status-plugin")
        val url = "https://service.example.com/api/login".toHttpUrl()
        session.saveFromResponse(
            url,
            parse(url.toString(), "sid=status-secret; Secure; Path=/api"),
        )

        val status = session.status().toString()
        assertTrue(status.contains("\"cookieCount\":1"))
        assertFalse(status.contains("sid"))
        assertFalse(status.contains("status-secret"))
        assertFalse(status.contains("service.example.com"))
        assertFalse(status.contains("loggedIn"))
        assertFalse(status.contains("authenticated"))
    }
}
