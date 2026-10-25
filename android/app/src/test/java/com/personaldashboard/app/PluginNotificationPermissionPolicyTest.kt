package com.personaldashboard.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginNotificationPermissionPolicyTest {
    private fun snapshot(
        sdkInt: Int = 33,
        runtimePermissionGranted: Boolean = false,
        notificationManagerAvailable: Boolean = true,
        notificationsEnabled: Boolean = true,
        foreground: Boolean = true,
        preview: Boolean = false,
        requestedBefore: Boolean = false,
        shouldShowRequestRationale: Boolean = false,
        requestInProgress: Boolean = false,
    ) =
        PluginNotificationPermissionPolicy.Snapshot(
            sdkInt = sdkInt,
            runtimePermissionGranted = runtimePermissionGranted,
            notificationManagerAvailable = notificationManagerAvailable,
            notificationsEnabled = notificationsEnabled,
            foreground = foreground,
            preview = preview,
            requestedBefore = requestedBefore,
            shouldShowRequestRationale = shouldShowRequestRationale,
            requestInProgress = requestInProgress,
        )

    @Test
    fun `missing op remains legacy send while status and request are explicit`() {
        assertEquals("send", PluginNotificationPermissionPolicy.operation(JSONObject()))
        assertEquals(
            "send",
            PluginNotificationPermissionPolicy.operation(JSONObject().put("op", "send")),
        )
        assertEquals(
            "status",
            PluginNotificationPermissionPolicy.operation(JSONObject().put("op", "status")),
        )
        assertEquals(
            "request",
            PluginNotificationPermissionPolicy.operation(JSONObject().put("op", "request")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PluginNotificationPermissionPolicy.operation(JSONObject().put("op", "settings"))
        }
    }

    @Test
    fun `status separates runtime permission from the system notification switch`() {
        val status =
            snapshot(runtimePermissionGranted = true, notificationsEnabled = false).statusJson()

        assertEquals("granted", status.getString("permission"))
        assertTrue(status.getBoolean("permissionGranted"))
        assertFalse(status.getBoolean("notificationsEnabled"))
        assertFalse(status.getBoolean("canNotify"))
        assertFalse(status.getBoolean("canRequest"))
    }

    @Test
    fun `pre-33 needs no runtime request but still reports real notification availability`() {
        val status = snapshot(sdkInt = 32, notificationsEnabled = false).statusJson()

        assertEquals("not_required", status.getString("permission"))
        assertTrue(status.getBoolean("permissionGranted"))
        assertFalse(status.getBoolean("requiresRuntimePermission"))
        assertFalse(status.getBoolean("canRequest"))
        assertFalse(status.getBoolean("canNotify"))
    }

    @Test
    fun `only a foreground non-preview origin can make a requestable first attempt`() {
        assertTrue(snapshot().canRequest)
        assertFalse(snapshot(foreground = false).canRequest)
        assertFalse(snapshot(preview = true).canRequest)
        assertFalse(snapshot(requestInProgress = true).canRequest)
        assertFalse(snapshot(sdkInt = 32).canRequest)
        assertFalse(snapshot(runtimePermissionGranted = true).canRequest)
    }

    @Test
    fun `requested marker and real rationale prevent pretending a blocked prompt is available`() {
        assertTrue(snapshot(requestedBefore = true, shouldShowRequestRationale = true).canRequest)
        assertFalse(snapshot(requestedBefore = true, shouldShowRequestRationale = false).canRequest)

        val status = snapshot(requestedBefore = true).statusJson()
        assertFalse(status.getBoolean("canRequest"))
        assertTrue(status.getBoolean("requestedBefore"))
        assertFalse(status.getBoolean("shouldShowRationale"))
    }

    @Test
    fun `preview denial never becomes a native request or an authorization success`() {
        val current = snapshot(preview = true)
        val result =
            PluginNotificationPermissionPolicy.requestResult(
                current,
                "preview_forbidden",
                requested = false,
            )

        assertEquals("preview_forbidden", result.getString("result"))
        assertFalse(result.getBoolean("requested"))
        assertFalse(result.getBoolean("canRequest"))
        assertFalse(result.getBoolean("permissionGranted"))
    }

    @Test
    fun `request result is based on fresh state and cannot fabricate a grant`() {
        val denied = snapshot(requestedBefore = true)
        val result =
            PluginNotificationPermissionPolicy.requestResult(denied, "denied", requested = true)
        assertEquals("denied", result.getString("result"))
        assertFalse(result.getBoolean("permissionGranted"))
        assertFalse(result.getBoolean("canNotify"))
        assertThrows(IllegalArgumentException::class.java) {
            PluginNotificationPermissionPolicy.requestResult(denied, "granted", requested = true)
        }

        val grantedButDisabled =
            snapshot(runtimePermissionGranted = true, notificationsEnabled = false)
        val granted =
            PluginNotificationPermissionPolicy.requestResult(
                grantedButDisabled,
                "already_granted",
                requested = false,
            )
        assertTrue(granted.getBoolean("permissionGranted"))
        assertFalse(granted.getBoolean("notificationsEnabled"))
        assertFalse(granted.getBoolean("canNotify"))
    }

    @Test
    fun `missing manager cannot be reported as an enabled notification path`() {
        val status =
            snapshot(
                runtimePermissionGranted = true,
                notificationManagerAvailable = false,
                notificationsEnabled = true,
            )
                .statusJson()

        assertFalse(status.getBoolean("notificationManagerAvailable"))
        assertFalse(status.getBoolean("notificationsEnabled"))
        assertFalse(status.getBoolean("canNotify"))
    }

    @Test
    fun `request decision requires foreground even when permission is already granted`() {
        assertEquals(null, PluginNotificationPermissionPolicy.immediateRequestResult(snapshot()))
        assertEquals(
            "already_granted",
            PluginNotificationPermissionPolicy.immediateRequestResult(
                snapshot(runtimePermissionGranted = true)
            ),
        )
        assertEquals(
            "not_foreground",
            PluginNotificationPermissionPolicy.immediateRequestResult(
                snapshot(runtimePermissionGranted = true, foreground = false)
            ),
        )
        assertEquals(
            "preview_forbidden",
            PluginNotificationPermissionPolicy.immediateRequestResult(
                snapshot(runtimePermissionGranted = true, preview = true)
            ),
        )
        assertEquals(
            "not_required",
            PluginNotificationPermissionPolicy.immediateRequestResult(snapshot(sdkInt = 32)),
        )
        assertEquals(
            "request_in_progress",
            PluginNotificationPermissionPolicy.immediateRequestResult(
                snapshot(requestInProgress = true)
            ),
        )
        assertEquals(
            "not_requestable",
            PluginNotificationPermissionPolicy.immediateRequestResult(
                snapshot(requestedBefore = true)
            ),
        )
    }
}
