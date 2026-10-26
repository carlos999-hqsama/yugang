package com.personaldashboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTransferPolicyTest {
    @Test
    fun uploadDefaultsAndRejectsUnknownValues() {
        assertEquals(
            PluginTransferPolicy.UploadMethod.PUT,
            PluginTransferPolicy.parseUploadMethod("put"),
        )
        assertEquals(
            PluginTransferPolicy.UploadProtocol.PLAIN,
            PluginTransferPolicy.parseUploadProtocol("plain"),
        )
        assertEquals(
            PluginTransferPolicy.UploadProtocol.TUS,
            PluginTransferPolicy.parseUploadProtocol("TUS"),
        )
        assertFalse(runCatching { PluginTransferPolicy.parseUploadMethod("PATCH") }.isSuccess)
        assertFalse(runCatching { PluginTransferPolicy.parseUploadProtocol("chunked") }.isSuccess)
    }

    @Test
    fun recoveryAllowsOnlyQueuedAndPausedDownloads() {
        assertTrue(PluginTransferPolicy.canAutoRecover(PluginTransferPolicy.State.QUEUED))
        assertTrue(PluginTransferPolicy.canAutoRecover(PluginTransferPolicy.State.PAUSED_DOWNLOAD))
        assertFalse(PluginTransferPolicy.canAutoRecover(PluginTransferPolicy.State.PAUSED_UPLOAD))
        assertFalse(PluginTransferPolicy.canAutoRecover(PluginTransferPolicy.State.UNKNOWN))
        assertFalse(PluginTransferPolicy.canAutoRecover(PluginTransferPolicy.State.RUNNING))
    }

    @Test
    fun pausedUploadsRequireExplicitTusResume() {
        assertFalse(
            PluginTransferPolicy.canRun(
                PluginTransferPolicy.Operation.UPLOAD,
                PluginTransferPolicy.UploadProtocol.PLAIN,
                PluginTransferPolicy.State.PAUSED_UPLOAD,
                hasLiveWorker = false,
            ),
        )
        assertFalse(
            PluginTransferPolicy.canRun(
                PluginTransferPolicy.Operation.UPLOAD,
                PluginTransferPolicy.UploadProtocol.TUS,
                PluginTransferPolicy.State.UNKNOWN,
                hasLiveWorker = false,
            ),
        )
        assertTrue(
            PluginTransferPolicy.canExplicitResume(
                PluginTransferPolicy.Operation.UPLOAD,
                PluginTransferPolicy.UploadProtocol.TUS,
                PluginTransferPolicy.State.PAUSED_UPLOAD,
            ),
        )
        assertFalse(
            PluginTransferPolicy.canExplicitResume(
                PluginTransferPolicy.Operation.UPLOAD,
                PluginTransferPolicy.UploadProtocol.PLAIN,
                PluginTransferPolicy.State.UNKNOWN,
            ),
        )
    }

    @Test
    fun contentRangeParsingIsStrict() {
        val partial = PluginTransferPolicy.parseContentRange("bytes 100-199/1000")
        assertEquals(100L, partial!!.start)
        assertEquals(199L, partial.endInclusive)
        assertEquals(1000L, partial.total)
        val unknown = PluginTransferPolicy.parseContentRange("bytes 0-9/*")
        assertEquals(null, unknown?.total)
        assertNull(PluginTransferPolicy.parseContentRange("bytes 200-100/1000"))
        assertNull(PluginTransferPolicy.parseContentRange("bytes 0-10/10"))
        assertNull(PluginTransferPolicy.parseContentRange("items 0-1/2"))
    }

    @Test
    fun strongEtagsNeverUseWeakValues() {
        assertTrue(PluginTransferPolicy.isStrongEtag("\"v1\""))
        assertFalse(PluginTransferPolicy.isStrongEtag("W/\"v1\""))
        assertFalse(PluginTransferPolicy.isStrongEtag("v1"))
        assertFalse(PluginTransferPolicy.isStrongEtag("\"bad\nvalue\""))
    }

    @Test
    fun jobIdsAreStableAndPositive() {
        val first = PluginTransferPolicy.jobIdFor("00000000-0000-0000-0000-000000000001")
        assertEquals(first, PluginTransferPolicy.jobIdFor("00000000-0000-0000-0000-000000000001"))
        assertTrue(first > 0)
    }

    @Test
    fun mimeFallsBackWithoutTrustingHeaders() {
        assertEquals(
            PluginTransferPolicy.DEFAULT_MIME,
            PluginTransferPolicy.safeMime("text/plain\r\nX-Evil: 1"),
        )
        assertEquals(
            PluginTransferPolicy.DEFAULT_MIME,
            PluginTransferPolicy.safeMime("not-a-mime"),
        )
        assertEquals("image/jpeg", PluginTransferPolicy.safeMime("image/jpeg"))
    }
    @org.junit.Test fun orphanedDownloadsResumeButUploadsRemainUnknown() {
        org.junit.Assert.assertEquals(PluginTransferPolicy.State.PAUSED_DOWNLOAD,PluginTransferPolicy.interruptedState(PluginTransferPolicy.Operation.DOWNLOAD,PluginTransferPolicy.State.RUNNING,false))
        org.junit.Assert.assertEquals(PluginTransferPolicy.State.UNKNOWN,PluginTransferPolicy.interruptedState(PluginTransferPolicy.Operation.UPLOAD,PluginTransferPolicy.State.RUNNING,false))
        org.junit.Assert.assertEquals(PluginTransferPolicy.State.RUNNING,PluginTransferPolicy.interruptedState(PluginTransferPolicy.Operation.DOWNLOAD,PluginTransferPolicy.State.RUNNING,true))
    }
}
