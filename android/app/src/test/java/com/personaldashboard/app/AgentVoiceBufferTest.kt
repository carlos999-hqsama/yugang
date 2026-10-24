package com.personaldashboard.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentVoiceBufferTest {
    private fun frame(type: String, text: String = "", attempt: String = "r1") =
        JSONObject().put("type", type).put("attemptId", attempt).put("text", text)

    @Test
    fun onlyConsentedLivePlainTextIsDeliveredWithNativeClock() {
        var now = 100L
        val b = AgentVoiceBuffer { now * 1_000_000 }
        b.scope("a", "sa")
        b.feed("a", "sa", frame("start"))
        b.feed("a", "sa", frame("text", "过去的字。"))
        val id = b.subscribe("plugin:rev", b.current()!!).getString("id")
        assertEquals(0, b.poll("plugin:rev", id).getJSONArray("events").length())
        b.feed("a", "sa", frame("text", "还没接收start的字。"))
        b.feed("a", "sa", frame("start"))
        now = 125
        b.feed("a", "sa", frame("text", "你好"))
        now = 140
        b.feed("a", "sa", frame("text", "。下一句"))
        now = 160
        b.feed("a", "sa", frame("end"))
        val es = b.poll("plugin:rev", id).getJSONArray("events")
        assertEquals(5, es.length())
        assertEquals(125, es.getJSONObject(0).getLong("firstTokenAt"))
        assertEquals(140, es.getJSONObject(2).getLong("firstSentenceAt"))
        assertEquals("你好。", es.getJSONObject(2).getString("text"))
        assertEquals("下一句", es.getJSONObject(3).getString("text"))
        assertEquals("end", es.getJSONObject(4).getString("type"))
        assertEquals(0, b.poll("plugin:rev", id).getJSONArray("events").length())
    }

    @Test
    fun scopeOwnerAndCancellationDoNotExposeAnotherBot() {
        val b = AgentVoiceBuffer { 10 }
        b.scope("a", "sa")
        val prior = b.current()!!
        val id = b.subscribe("p:r1", prior).getString("id")
        assertThrows(IllegalStateException::class.java) { b.poll("other:r1", id) }
        b.feed("other", "sb", frame("start"))
        b.feed("other", "sb", frame("text", "不该看到。"))
        assertEquals(0, b.poll("p:r1", id).getJSONArray("events").length())
        b.scope("b", "sb")
        assertFalse(b.active())
        assertThrows(IllegalStateException::class.java) { b.poll("p:r1", id) }
        assertThrows(IllegalArgumentException::class.java) { b.subscribe("p:r1", prior) }
        val next = b.subscribe("p:r1", b.current()!!).getString("id")
        b.cancel("other:r1")
        assertTrue(b.active())
        b.cancel("p:r1", next)
        assertFalse(b.active())
    }

    @Test
    fun slowConsumerGetsAnErrorInsteadOfUnboundedTextOrSilentTruncation() {
        val b = AgentVoiceBuffer { 10 }
        b.scope("a", "s")
        val id = b.subscribe("p", b.current()!!).getString("id")
        b.feed("a", "s", frame("start"))
        repeat(300) { b.feed("a", "s", frame("text", "a")) }
        val es = b.poll("p", id).getJSONArray("events")
        assertEquals(1, es.length())
        assertEquals("error", es.getJSONObject(0).getString("type"))
    }

    @Test
    fun noPreGrantQueuedFramesOrFalseErrorAfterNormalEnd() {
        val b = AgentVoiceBuffer { 100_000_000 }
        b.scope("a", "s")
        val id = b.subscribe("p", b.current()!!).getString("id")
        b.feed("a", "s", frame("start"), 99_000_000)
        b.feed("a", "s", frame("text", "旧字。"), 101_000_000)
        assertEquals(0, b.poll("p", id).getJSONArray("events").length())
        b.feed("a", "s", frame("start"), 110_000_000)
        b.feed("a", "s", frame("text", "🐳".repeat(161)), 120_000_000)
        b.feed("a", "s", frame("end"), 130_000_000)
        b.feed("a", "s", frame("error"), 140_000_000)
        val es = b.poll("p", id).getJSONArray("events")
        assertEquals(4, es.length())
        assertEquals(120L, es.getJSONObject(0).getLong("at"))
        assertEquals("🐳".repeat(160), es.getJSONObject(1).getString("text"))
        assertEquals("🐳", es.getJSONObject(2).getString("text"))
        assertEquals("end", es.getJSONObject(3).getString("type"))
    }
}
