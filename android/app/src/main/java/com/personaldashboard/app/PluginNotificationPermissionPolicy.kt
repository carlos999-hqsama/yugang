package com.personaldashboard.app

import org.json.JSONObject

/** Pure notification permission semantics; Android state is supplied by the host dispatcher. */
object PluginNotificationPermissionPolicy {
    const val RUNTIME_PERMISSION_API = 33

    val REQUEST_RESULTS =
        setOf(
            "already_granted",
            "not_required",
            "preview_forbidden",
            "not_foreground",
            "not_requestable",
            "request_in_progress",
            "request_failed",
            "granted",
            "denied",
            "activity_destroyed",
        )

    /** The original notification call had no op and posted immediately. */
    fun operation(request: JSONObject): String =
        when (val operation = request.optString("op")) {
            "" -> "send"
            "send", "status", "request" -> operation
            else -> throw IllegalArgumentException("不支持的通知操作")
        }

    data class Snapshot(
        val sdkInt: Int,
        val runtimePermissionGranted: Boolean,
        val notificationManagerAvailable: Boolean,
        val notificationsEnabled: Boolean,
        val foreground: Boolean,
        val preview: Boolean,
        val requestedBefore: Boolean,
        val shouldShowRequestRationale: Boolean,
        val requestInProgress: Boolean,
    ) {
        val permissionGranted: Boolean
            get() = sdkInt < RUNTIME_PERMISSION_API || runtimePermissionGranted

        val shouldShowRationale: Boolean
            get() =
                sdkInt >= RUNTIME_PERMISSION_API &&
                    !permissionGranted &&
                    shouldShowRequestRationale

        val canNotify: Boolean
            get() = permissionGranted && notificationManagerAvailable && notificationsEnabled

        /**
         * Android does not expose a separate "permanently denied" flag. The host's requested marker
         * plus the real rationale callback distinguishes a first request from a request that can no
         * longer show the system prompt. A foreground, non-preview origin is always required.
         */
        val canRequest: Boolean
            get() =
                sdkInt >= RUNTIME_PERMISSION_API &&
                    !permissionGranted &&
                    foreground &&
                    !preview &&
                    !requestInProgress &&
                    (!requestedBefore || shouldShowRationale)

        fun statusJson(): JSONObject =
            JSONObject()
                .put("permission", permissionName())
                .put("permissionGranted", permissionGranted)
                .put("notificationsEnabled", notificationManagerAvailable && notificationsEnabled)
                .put("notificationManagerAvailable", notificationManagerAvailable)
                .put("canNotify", canNotify)
                .put("requiresRuntimePermission", sdkInt >= RUNTIME_PERMISSION_API)
                .put("canRequest", canRequest)
                .put("shouldShowRationale", shouldShowRationale)
                .put("requestedBefore", requestedBefore)
                .put("requestInProgress", requestInProgress)
                .put("foreground", foreground)
                .put("preview", preview)

        private fun permissionName(): String =
            when {
                sdkInt < RUNTIME_PERMISSION_API -> "not_required"
                runtimePermissionGranted -> "granted"
                else -> "denied"
            }
    }

    /** Null means the foreground caller may dispatch the real Android permission request. */
    fun immediateRequestResult(snapshot: Snapshot): String? =
        when {
            snapshot.preview -> "preview_forbidden"
            !snapshot.foreground -> "not_foreground"
            snapshot.requestInProgress -> "request_in_progress"
            snapshot.permissionGranted ->
                if (snapshot.sdkInt < RUNTIME_PERMISSION_API) "not_required" else "already_granted"
            !snapshot.canRequest -> "not_requestable"
            else -> null
        }

    /** Request outcomes always include a fresh system status, never an intent-launch assumption. */
    fun requestResult(snapshot: Snapshot, result: String, requested: Boolean): JSONObject {
        require(result in REQUEST_RESULTS) { "未知通知权限申请结果" }
        require(result != "granted" || snapshot.permissionGranted) {
            "系统权限尚未授予，不能返回授权成功"
        }
        return snapshot.statusJson()
            .put("requested", requested)
            .put("result", result)
    }
}
