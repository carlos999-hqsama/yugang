package com.personaldashboard.app

import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FileTextContentTest {
    private fun content(text: String, mime: String = "text/html"): FileTextContent {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return FileTextContent.read(bytes.inputStream(), bytes.size.toLong(), mime, "报告.html")
    }

    @Test fun htmlIsReturnedInAppWithoutAPathOrExternalOpen() {
        val html = "<!doctype html><h1>活跃关卡分布</h1><p>中文报告</p>"
        val result = content(html, "Text/HTML; charset=utf-8").toJson()
        assertEquals("text/html", result.getString("mime"))
        assertEquals(html, result.getString("html"))
        assertEquals(html.toByteArray().size, result.getInt("bytes"))
        assertFalse(result.has("path"))
        assertFalse(result.has("url"))
    }

    @Test fun commonTextFormatsRoundTripWithoutLosingPunctuationOrNewlines() {
        for ((mime, text) in listOf("text/markdown" to "# 报告\n\n**统计**", "text/plain" to "任务完成", "text/csv" to "名称,值\n\"甲,乙\",9\n", "application/json" to "{\"值\":9}", "application/geo+json" to "{}")) {
            val source = content(text, mime)
            val result = source.toJson()
            assertTrue(result.has("dataUrl"))
            val decoded = FileTextContent.fromJson(result)
            assertEquals(mime, decoded.mime)
            assertEquals(text, decoded.text)
            assertEquals(source.bytes, decoded.bytes)
        }
    }

    @Test fun actualAndDeclaredLengthAreBothBounded() {
        val exact = ByteArray(FileTextContent.MAX_BYTES) { 'a'.code.toByte() }
        assertEquals(exact.size, FileTextContent.read(exact.inputStream(), -1, "text/plain", "边界.txt").bytes)
        assertThrows(IllegalArgumentException::class.java) {
            FileTextContent.read(ByteArray(0).inputStream(), exact.size.toLong() + 1, "text/html", "过大.html")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileTextContent.read((exact + 'b'.code.toByte()).inputStream(), -1, "text/plain", "过大.txt")
        }
        assertThrows(IllegalStateException::class.java) {
            FileTextContent.read("x".byteInputStream(), 2, "text/plain", "未传完.txt")
        }
    }

    @Test fun binaryMalformedUtf8AndRemoteUrlsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { content("%PDF", "application/pdf") }
        assertThrows(IllegalArgumentException::class.java) {
            FileTextContent.read(byteArrayOf(0xc3.toByte(), 0x28).inputStream(), 2, "text/plain", "无效.txt")
        }
        for (url in listOf("https://example.com/report.html", "file:///data/private.html", "data:text/html;base64,%%%")) {
            assertThrows(IllegalArgumentException::class.java) { FileTextContent.fromJson(JSONObject().put("dataUrl", url)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileTextContent.fromJson(JSONObject().put("html", "<h1>x</h1>").put("text", "x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileTextContent.fromJson(JSONObject().put("html", "x").put("mime", "text/plain"))
        }
    }

    @Test fun cancellationStopsReadingAndNamesCannotActAsPaths() {
        assertThrows(CancellationException::class.java) {
            FileTextContent.read("正文".byteInputStream(), -1, "text/plain", "报告.txt") { throw CancellationException() }
        }
        assertEquals("报告.html", FileTextContent.name("/tmp/报告.html\n"))
        assertEquals("报告.txt", FileTextContent.name("C:\\tmp\\报告.txt"))
        assertEquals("", content("").text)
    }
}
