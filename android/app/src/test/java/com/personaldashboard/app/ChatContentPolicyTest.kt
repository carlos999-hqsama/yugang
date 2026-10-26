package com.personaldashboard.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatContentPolicyTest {
    @Test fun `active documents retain interactivity without provider origin or CORS grants`() {
        for (mime in listOf("text/html", "image/svg+xml", "Text/HTML; charset=utf-8")) {
            val headers = ChatContentPolicy.responseHeaders(mime, mapOf("Access-Control-Allow-Origin" to "*", "Set-Cookie" to "synthetic=1", "Content-Length" to "42"))
            assertEquals("sandbox allow-scripts", headers["Content-Security-Policy"])
            assertEquals("42", headers["Content-Length"])
            assertFalse(headers.keys.any { it.equals("Access-Control-Allow-Origin", true) || it.equals("Set-Cookie", true) })
            assertEquals("no-store", headers["Cache-Control"])
        }
    }
    @Test fun `only known download policy is translated and other upstream policies stay enforced`() {
        assertEquals(ChatContentPolicy.DOCUMENT_SANDBOX, ChatContentPolicy.responseHeaders("text/html", mapOf("content-security-policy" to "sandbox; default-src 'none'"))["Content-Security-Policy"])
        assertEquals("default-src 'none', sandbox allow-scripts", ChatContentPolicy.responseHeaders("text/html", mapOf("CONTENT-SECURITY-POLICY" to "default-src 'none'"))["Content-Security-Policy"])
        val media = ChatContentPolicy.responseHeaders("video/mp4", mapOf("Content-Range" to "bytes 0-9/42", "Content-Security-Policy" to "default-src 'none'"))
        assertEquals("bytes 0-9/42", media["Content-Range"])
        assertEquals("default-src 'none'", media["Content-Security-Policy"])
        assertFalse(ChatContentPolicy.responseHeaders("image/png", emptyMap()).containsKey("Content-Security-Policy"))
    }
    @Test fun `document names retain spaces hashes and question marks without URL queries`() {
        val path = "/BOT/default/documents/窄屏 #1?.html"
        val target = ChatContentPolicy.target(JSONObject().put("path",path),"/BOT/default")
        assertNull(java.net.URI(target.url).rawQuery)
        assertNull(java.net.URI(target.url).rawFragment)
        assertEquals(path,java.net.URI(target.url).path)
        assertEquals(target.upstream,ChatContentPolicy.upstream(target,target.url))
        assertNull(ChatContentPolicy.upstream(target,"https://remote.invalid/BOT/default/x.html"))
        assertNull(ChatContentPolicy.upstream(target,"https://dshfile.pd.local/BOT/other/x.txt"))
    }
    @Test fun `attachments use session reference route rather than guessed runtime paths`() {
        val id="sha256:"+"a".repeat(64)
        val target=ChatContentPolicy.target(JSONObject().put("sessionId","session-1").put("attachmentId",id).put("name","片段 1.mp4"),"/BOT/default")
        assertEquals("video/mp4",target.mime)
        assertTrue(target.upstream.startsWith("/api/yugang/session-file?"))
        assertTrue(target.upstream.contains("sessionId=session-1"))
        assertFalse(target.url.contains(".dsh"))
        assertEquals(target.upstream,ChatContentPolicy.upstream(target,target.url))
        assertNull(ChatContentPolicy.upstream(target,target.url.replace("session-1","session-2")))
        assertThrows(IllegalArgumentException::class.java) { ChatContentPolicy.target(JSONObject().put("sessionId","session-1").put("attachmentId",id).put("name","../private"),"/BOT/default") }
    }
    @Test fun `workspace traversals are refused while unsupported formats have a download route`() {
        for(path in listOf("/BOT/default/../other/secret","/BOT/default/./x","/BOT/default-x/file","/root/key")) {
            assertThrows(IllegalArgumentException::class.java) { ChatContentPolicy.path(path,"/BOT/default") }
        }
        assertFalse(ChatContentPolicy.inline(ChatContentPolicy.mime("file.xlsx")))
        assertFalse(ChatContentPolicy.inline(ChatContentPolicy.mime("file.zip")))
        assertTrue(ChatContentPolicy.inline(ChatContentPolicy.mime("movie.MP4")))
        assertTrue(ChatContentPolicy.inline(ChatContentPolicy.mime("report.html")))
    }
    @Test fun `video byte ranges support prefixes suffixes and EOF and reject malformed ranges`() {
        assertEquals(0L..9L,ChatContentPolicy.byteRange("bytes=0-9",100))
        assertEquals(90L..99L,ChatContentPolicy.byteRange("bytes=90-",100))
        assertEquals(90L..99L,ChatContentPolicy.byteRange("bytes=-10",100))
        assertEquals(0L..99L,ChatContentPolicy.byteRange("bytes=-1000",100))
        assertEquals(90L..99L,ChatContentPolicy.byteRange("bytes=90-1000",100))
        for(value in listOf("bytes=100-","bytes=9-0","bytes=-0","bytes=-","bytes=0-1,3-4","bytes=999999999999999999999-")) {
            assertThrows(Exception::class.java) { ChatContentPolicy.byteRange(value,100) }
        }
    }
}
