package com.personaldashboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppControlPolicyTest {
    @Test
    fun botRouteSeparatesAuthenticatedSourceAndOptionalReplyDestination() {
        val route =
            AppControlPolicy.parseRequest(
                """{"action":"bot-route","sessionId":"sender","cwd":"/BOT/default","targetId":"peer","targetSessionId":"original"}""",
            ) as AppControlPolicy.Command.BotRoute
        assertEquals("sender", route.sessionId)
        assertEquals("original", route.targetSessionId)
        for (bad in
            listOf(
                """{"action":"bot-route","sessionId":"sender","cwd":"relative"}""",
                """{"action":"bot-route","sessionId":"sender","cwd":"/BOT/default","targetSessionId":"original"}""",
                """{"action":"bot-route","sessionId":"sender","cwd":"/BOT/default","senderId":"spoof"}""",
                """{"action":"bot-route","sessionId":"sender","cwd":"/BOT/default","targetId":null}""",
            )) {
            try {
                AppControlPolicy.parseRequest(bad)
                throw AssertionError("invalid route accepted")
            } catch (_: AppControlPolicy.Rejected) {}
        }
    }

    @Test
    fun updateAcceptsKeepAwakeWhileRunning() {
        val command =
            parseUpdate("""{"action":"update","patch":{"keepAwakeWhileRunning":true}}""")
        assertEquals(AppControlPolicy.Patch(keepAwakeWhileRunning = true), command.patch)
    }

    @Test
    fun deprecatedNotifyAndPollFieldsAreRejected() {
        reject("""{"action":"update","patch":{"notifyInApp":true}}""")
        reject("""{"action":"update","patch":{"pollSec":30}}""")
        reject("""{"action":"update","patch":{"keepAwakeWhileRunning":true,"notifyInApp":false}}""")
        reject("""{"action":"update","patch":{"keepAwakeWhileRunning":true,"pollSec":5}}""")
    }

    @Test
    fun booleansMustBeActualJsonBooleans() {
        reject("""{"action":"update","patch":{"keepAwakeWhileRunning":"false"}}""")
        reject("""{"action":"update","patch":{"keepAwakeWhileRunning":0}}""")
    }

    @Test
    fun unknownAndSecretFieldsAreRejectedWithoutEchoingThem() {
        val secret = "secret-value-must-not-echo"
        val errors =
            listOf(
                rejection("""{"action":"update","patch":{"hostToken":"$secret"}}"""),
                rejection("""{"action":"update","patch":{"deepseekKey":"$secret"}}"""),
                rejection(
                    """{"action":"update","patch":{"keepAwakeWhileRunning":true,"dshMode":"external"}}""",
                ),
                rejection(
                    """{"action":"update","patch":{"keepAwakeWhileRunning":true},"apiKey":"$secret"}""",
                ),
            )
        errors.forEach { error ->
            assertTrue(error.message.orEmpty().isNotBlank())
            assertTrue(!error.message.orEmpty().contains(secret))
        }
    }

    @Test
    fun emptyPatchAndGetWithExtraFieldsAreRejected() {
        reject("""{"action":"update","patch":{}}""")
        reject("""{"action":"get","patch":{}}""")
        reject("""{"action":"get","keepAwakeWhileRunning":true}""")
        reject("""{"action":"delete"}""")
    }

    @Test
    fun requestLimitIsExactly8192Utf8Bytes() {
        val base = """{"action":"get"}"""
        val padding = AppControlPolicy.MAX_REQUEST_BYTES - base.toByteArray(Charsets.UTF_8).size
        assertTrue(
            AppControlPolicy.parseRequest(base + " ".repeat(padding))
                is AppControlPolicy.Command.Get,
        )
        reject(base + " ".repeat(padding + 1))
    }

    @Test
    fun settingsJsonContainsOnlyKeepAwake() {
        val keys =
            AppControlPolicy.Settings(keepAwakeWhileRunning = false)
                .toJson()
                .keys()
                .asSequence()
                .toSet()
        assertEquals(setOf("keepAwakeWhileRunning"), keys)
    }

    private fun parseUpdate(request: String): AppControlPolicy.Command.Update =
        AppControlPolicy.parseRequest(request) as AppControlPolicy.Command.Update

    private fun reject(request: String): AppControlPolicy.Rejected = rejection(request)

    private fun rejection(request: String): AppControlPolicy.Rejected =
        assertThrows(AppControlPolicy.Rejected::class.java) {
            AppControlPolicy.parseRequest(request)
        }
}
