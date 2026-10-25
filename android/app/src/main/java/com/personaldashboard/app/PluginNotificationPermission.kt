package com.personaldashboard.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * App-wide Android notification permission dispatcher.
 *
 * MainActivity forwards resume/pause/destroy and onRequestPermissionsResult. Requests are always
 * completed asynchronously on the main thread; no host or plugin lock is held while Android owns
 * the permission UI. Opening notification settings is deliberately not an authorization path.
 */
object PluginNotificationPermission {
    internal const val REQUEST_CODE = 0x504E
    private const val PREFERENCES = "plugin-notification-permission"
    private const val REQUESTED_BEFORE = "requested-before"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var resumedActivity: Activity? = null

    @Volatile private var pending: Pending? = null

    private data class Pending(
        val activity: Activity,
        val callback: (JSONObject) -> Unit,
    )

    fun status(context: Context, preview: Boolean = false): JSONObject =
        snapshot(context, preview).statusJson()

    /** Re-evaluate a request origin on the main thread before Android may show its permission UI. */
    fun requestDecision(
        context: Context,
        preview: Boolean = false,
        pageVisible: Boolean = true,
    ): Pair<PluginNotificationPermissionPolicy.Snapshot, String?> {
        val current = snapshot(context, preview)
        return current to
            PluginNotificationPermissionPolicy.immediateRequestResult(current, pageVisible)
    }

    /**
     * Request the real Android runtime permission. This method never waits for the result. Exactly
     * one terminal callback is posted to the main thread for each accepted call.
     */
    fun request(
        activity: Activity,
        preview: Boolean = false,
        callback: (JSONObject) -> Unit,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            requestOnMain(activity, preview, callback)
        } else {
            main.post { requestOnMain(activity, preview, callback) }
        }
    }

    fun onResume(activity: Activity) {
        resumedActivity = activity
    }

    fun onPause(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
        // Android's permission dialog may itself pause the Activity. Keep its result callback.
    }

    fun onDestroy(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
        val request = pending?.takeIf { it.activity === activity } ?: return
        pending = null
        deliver(
            request.callback,
            PluginNotificationPermissionPolicy.requestResult(
                snapshot(activity, preview = false),
                "activity_destroyed",
                requested = true,
            ),
        )
    }

    /** Returns true only for this dispatcher's request code, so other Activity results continue. */
    @Suppress("UNUSED_PARAMETER")
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val request = pending ?: return true
        pending = null
        // grantResults is not a source of truth by itself: read back the real app permission and
        // notification switch after Android delivers the result.
        val current = snapshot(request.activity, preview = false)
        deliver(
            request.callback,
            PluginNotificationPermissionPolicy.requestResult(
                current,
                if (current.permissionGranted) "granted" else "denied",
                requested = true,
            ),
        )
        return true
    }

    private fun requestOnMain(
        activity: Activity,
        preview: Boolean,
        callback: (JSONObject) -> Unit,
    ) {
        val (current, immediate) = requestDecision(activity, preview)
        if (immediate != null) {
            deliverRequest(callback, current, immediate, requested = false)
            return
        }
        pending = Pending(activity, callback)
        // Persist only a boolean host permission-flow marker, never plugin or user data.
        preferences(activity).edit().putBoolean(REQUESTED_BEFORE, true).apply()
        try {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE,
            )
        } catch (_: Exception) {
            if (pending?.activity === activity) pending = null
            if (!current.requestedBefore) {
                preferences(activity).edit().putBoolean(REQUESTED_BEFORE, false).apply()
            }
            deliverRequest(
                callback,
                snapshot(activity, preview = false),
                "request_failed",
                requested = false,
            )
        }
    }

    private fun snapshot(
        context: Context,
        preview: Boolean,
    ): PluginNotificationPermissionPolicy.Snapshot {
        val activity = context as? Activity
        val manager = context.getSystemService(NotificationManager::class.java)
        val granted =
            Build.VERSION.SDK_INT >= PluginNotificationPermissionPolicy.RUNTIME_PERMISSION_API &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        return PluginNotificationPermissionPolicy.Snapshot(
            sdkInt = Build.VERSION.SDK_INT,
            runtimePermissionGranted = granted,
            notificationManagerAvailable = manager != null,
            notificationsEnabled = manager?.areNotificationsEnabled() == true,
            foreground =
                activity != null &&
                    resumedActivity === activity &&
                    !activity.isFinishing &&
                    !activity.isDestroyed,
            preview = preview,
            requestedBefore = preferences(context).getBoolean(REQUESTED_BEFORE, false),
            shouldShowRequestRationale =
                activity != null &&
                    Build.VERSION.SDK_INT >= PluginNotificationPermissionPolicy.RUNTIME_PERMISSION_API &&
                    !granted &&
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
            requestInProgress = pending != null,
        )
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun deliverRequest(
        callback: (JSONObject) -> Unit,
        current: PluginNotificationPermissionPolicy.Snapshot,
        result: String,
        requested: Boolean,
    ) = deliver(callback, PluginNotificationPermissionPolicy.requestResult(current, result, requested))

    private fun deliver(callback: (JSONObject) -> Unit, result: JSONObject) {
        // Always post, including immediate denial/already-granted results, so a caller cannot
        // accidentally invoke its completion while still holding a plugin or lifecycle lock.
        main.post { runCatching { callback(result) } }
    }
}
