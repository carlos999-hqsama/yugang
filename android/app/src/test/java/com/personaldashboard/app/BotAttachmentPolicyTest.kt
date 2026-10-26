package com.personaldashboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure staging limits, display-name sanitization and DSH receipt validation. */
class BotAttachmentPolicyTest {
    @Test
    fun `display names keep Chinese and spaces but lose path and control syntax`() {
        assertEquals("年度 报告.pdf", BotAttachmentPolicy.sanitizeDisplayName("年度 报告.pdf"))
        assertEquals("附件", BotAttachmentPolicy.sanitizeDisplayName(" .. "))
        assertEquals("附件", BotAttachmentPolicy.sanitizeDisplayName(null))

        val unsafe = BotAttachmentPolicy.sanitizeDisplayName("目录\\文件\n.name\u0000")
        assertFalse(unsafe.contains('/'))
        assertFalse(unsafe.contains('\\'))
        assertFalse(unsafe.contains('\n'))
        assertFalse(unsafe.contains('\u0000'))

        val longName = BotAttachmentPolicy.sanitizeDisplayName("文".repeat(500))
        assertEquals(
            BotAttachmentPolicy.MAX_DISPLAY_NAME_CODE_POINTS,
            longName.codePointCount(0, longName.length),
        )
    }

    @Test
    fun `media types are normalized and video is classified from the resolved type`() {
        assertEquals("video/mp4", BotAttachmentPolicy.normalizeMediaType(" Video/MP4 ; charset=binary"))
        assertEquals("application/pdf", BotAttachmentPolicy.normalizeMediaType("application/pdf"))
        assertEquals(
            BotAttachmentPolicy.OCTET_STREAM,
            BotAttachmentPolicy.normalizeMediaType("video/*"),
        )
        assertEquals(
            BotAttachmentPolicy.OCTET_STREAM,
            BotAttachmentPolicy.normalizeMediaType("not a mime"),
        )
        assertEquals("video", BotAttachmentPolicy.kindFor("video/mp4"))
        assertEquals("file", BotAttachmentPolicy.kindFor("application/pdf"))
    }

    @Test
    fun `batch count total count and both size limits are enforced`() {
        BotAttachmentPolicy.requireCapacity(5, 1024, 1, 1024)
        BotAttachmentPolicy.requireCapacity(6, 1024, 6, 1024)
        assertEquals(
            "too_many_files",
            assertThrows(BotAttachmentException::class.java) {
                    BotAttachmentPolicy.requireCapacity(BotAttachmentPolicy.MAX_STAGED_FILES, 0, 1, 1)
                }
                .code,
        )
        assertEquals(
            "staging_limit_reached",
            assertThrows(BotAttachmentException::class.java) {
                    BotAttachmentPolicy.requireCapacity(
                        0,
                        BotAttachmentPolicy.MAX_STAGED_BYTES,
                        1,
                        1,
                    )
                }
                .code,
        )
        assertEquals(
            "invalid_batch",
            assertThrows(BotAttachmentException::class.java) {
                    BotAttachmentPolicy.requireBatchSize(7)
                }
                .code,
        )
        BotAttachmentPolicy.requireFileBytes(BotAttachmentPolicy.MAX_FILE_BYTES)
        assertEquals(
            "file_too_large",
            assertThrows(BotAttachmentException::class.java) {
                    BotAttachmentPolicy.requireFileBytes(BotAttachmentPolicy.MAX_FILE_BYTES + 1)
                }
                .code,
        )
    }

    @Test
    fun `successful receipts keep only validated receipt and file fields`() {
        val payload =
            """
            {"ok":true,"value":{"receiptId":"receipt-1","file":{"attachmentId":"digest","name":"年度 报告.pdf","bytes":4}}}
            """
                .trim()
                .toByteArray(Charsets.UTF_8)
        val receipt = BotAttachmentPolicy.parseReceipt(payload, 4, 200)
        assertEquals("receipt-1", receipt.receiptId)
        assertEquals("digest", receipt.file.attachmentId)
        assertEquals("年度 报告.pdf", receipt.file.name)
        assertEquals(4L, receipt.file.bytes)
    }

    @Test
    fun `rejected malformed mismatched and oversized responses are structured`() {
        val rejected =
            assertThrows(BotAttachmentException::class.java) {
                BotAttachmentPolicy.parseReceipt(
                    """{"ok":false,"error":{"message":"secret provider body"}}""".toByteArray(Charsets.UTF_8),
                    4,
                    200,
                )
            }
        assertEquals("upload_rejected", rejected.code)
        assertEquals(200, rejected.status)
        assertFalse(rejected.outcomeUnknown)
        assertFalse(rejected.message.contains("secret provider body"))

        val malformed =
            assertThrows(BotAttachmentException::class.java) {
                BotAttachmentPolicy.parseReceipt("not-json".toByteArray(Charsets.UTF_8), 4, 200)
            }
        assertEquals("invalid_response", malformed.code)
        assertTrue(malformed.outcomeUnknown)

        val mismatch =
            assertThrows(BotAttachmentException::class.java) {
                BotAttachmentPolicy.parseReceipt(
                    """
                    {"ok":true,"value":{"receiptId":"r","file":{"attachmentId":"a","name":"n","bytes":5}}}
                    """
                        .trim()
                        .toByteArray(Charsets.UTF_8),
                    4,
                    200,
                )
            }
        assertEquals("invalid_response", mismatch.code)

        val oversized =
            assertThrows(BotAttachmentException::class.java) {
                BotAttachmentPolicy.parseReceipt(
                    ByteArray(BotAttachmentPolicy.MAX_RESPONSE_BYTES + 1),
                    4,
                    200,
                )
            }
        assertEquals("response_too_large", oversized.code)
        assertTrue(oversized.outcomeUnknown)
    }

    @Test
    fun `pending cancellation tombstones are exact bounded and expire`() {
        var now = 0L
        val ledger = PendingRequestCancellations { now }
        assertFalse(ledger.mark(""))
        (0 until 130).forEach { assertTrue(ledger.mark("request-$it")) }
        assertEquals(128, ledger.sizeForTest())
        assertFalse(ledger.consume("request-0"))
        assertTrue(ledger.consume("request-129"))
        assertFalse(ledger.consume("request-129"))

        now = 120_000_000_000L
        assertTrue(ledger.mark("later"))
        assertEquals(1, ledger.sizeForTest())
        assertTrue(ledger.consume("later"))
        assertFalse(ledger.consume("unrelated"))
    }
}
