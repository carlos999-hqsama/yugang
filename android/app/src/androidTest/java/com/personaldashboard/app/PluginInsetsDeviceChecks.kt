package com.personaldashboard.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Insets
import android.os.Build
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebView
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Real-window light-theme and safe-area checks for the dedicated Android emulator. */
internal class PluginInsetsDeviceChecks(private val runner: Instrumentation) {
    private val context get() = runner.targetContext
    private val pluginId = "insets-safe-area-check"

    private fun shell(command: String): String =
        runner.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8)
        }

    private fun passed(text: String) =
        runner.sendStatus(0, Bundle().apply { putString("stream", "PASS: $text\n") })

    private fun waitFor(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) {
            if (condition()) return
            Thread.sleep(100)
        }
        check(condition()) { message }
    }

    private fun views(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun js(web: WebView, script: String): String {
        val done = CountDownLatch(1)
        var value = ""
        runner.runOnMainSync {
            web.evaluateJavascript(script) {
                value = it
                done.countDown()
            }
        }
        check(done.await(3, TimeUnit.SECONDS)) { "WebView evaluation timed out" }
        return value
    }

    private fun json(web: WebView, script: String): JSONObject =
        JSONObject(JSONTokener(js(web, script)).also { check(it.more()) }.nextValue().toString())

    private fun webField(activity: MainActivity): WebView {
        val field = MainActivity::class.java.getDeclaredField("web").apply { isAccessible = true }
        return field.get(activity) as WebView
    }

    private fun rootField(activity: MainActivity): ViewGroup {
        val field = MainActivity::class.java.getDeclaredField("root").apply { isAccessible = true }
        return field.get(activity) as ViewGroup
    }

    private fun nightCommand(value: String): String =
        when (value.trim()) {
            "0" -> "auto"
            "1" -> "no"
            "2" -> "yes"
            "no", "yes", "auto" -> value.trim()
            else -> error("Unexpected emulator night setting: $value")
        }

    fun run() {
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator")) { "仅允许专用模拟器" }

        val originalNight = nightCommand(shell("settings get secure ui_night_mode"))
        var activity: MainActivity? = null
        var originalInsets: WindowInsets? = null
        val preferences = context.getSharedPreferences("pd", android.content.Context.MODE_PRIVATE)

        val previousMode = preferences.getString("dshMode", null)
        val previousHarness = preferences.getString("harnessMode", null)
        // Keep this scenario on the inert external/web path. The dedicated acceptance image
        // defaults to embedded/native, which is restored in finally below.
        check(
            preferences.edit().putString("dshMode", "external").putString("harnessMode", "web").commit()
        ) { "Could not select the inert test harness path" }

        try {
            shell("cmd uimode night yes")
            activity =
                runner.startActivitySync(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ) as MainActivity

            waitFor("Activity did not observe system dark mode") {
                (activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            }

            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            check(controller.isAppearanceLightStatusBars) { "System dark mode changed status icons to light" }
            check(controller.isAppearanceLightNavigationBars) { "System dark mode changed navigation icons to light" }

            val core = webField(activity)
            waitFor("Core page did not boot") {
                js(core, "!!document.querySelector('.main')") == "true"
            }
            val coreColors = json(
                core,
                """JSON.stringify({background:getComputedStyle(document.body).backgroundColor,
                    text:getComputedStyle(document.body).color})""",
            )
            check(coreColors.getString("background") == "rgb(246, 246, 247)") { coreColors.toString() }
            check(coreColors.getString("text") == "rgb(29, 29, 31)") { coreColors.toString() }
            passed("Fixed light core keeps dark system-bar icons in real system dark mode")

            val root = rootField(activity)
            runner.runOnMainSync { originalInsets = root.rootWindowInsets }
            checkNotNull(originalInsets) { "Real WindowInsets were not available" }

            val bundle =
                JSONObject()
                    .put(
                        "manifest",
                        JSONObject()
                            .put("apiVersion", 1)
                            .put("frameworkVersion", 1)
                            .put("id", pluginId)
                            .put("title", "Insets fixture")
                            .put("version", "1.0")
                            .put("kind", "native")
                            .put("capabilities", JSONArray()),
                    )
                    .put(
                        "view",
                        JSONObject().put("type", "page").put("entry", "main.js").put("styles", JSONArray()),
                    )
                    .put(
                        "resources",
                        JSONObject().put(
                            "main.js",
                            """import {definePage} from '/framework/v1/page.js';
                            export default definePage({mount(ctx){
                                ctx.root.innerHTML='<div id="insets-probe" style="width:100%;height:4px"></div>';
                            }});""",
                        ),
                    )
            PluginPlatform.manage(context, JSONObject().put("action", "install").put("bundle", bundle))
            runner.runOnMainSync { activity.openPlugin(pluginId) }

            var page: WebView? = null
            waitFor("Synthetic plugin page missing") {
                runner.runOnMainSync {
                    page = views(activity.window.decorView).filterIsInstance<WebView>()
                        .firstOrNull { it.url?.contains("plugin-$pluginId.pd.local") == true }
                }
                page != null
            }
            val plugin = checkNotNull(page)
            waitFor("Synthetic plugin page did not mount") {
                js(plugin, "!!document.querySelector('#insets-probe')") == "true"
            }

            val density = activity.resources.displayMetrics.density
            val leftDp = 24.0
            val rightDp = 32.0
            val topDp = 28
            val bottomDp = 36
            fun dp(value: Int) = (value * density).toInt()

            // Both a system bar and a cutout contribute on each horizontal side. The larger
            // cutout must therefore win the safe-area union instead of being averaged away.
            val synthetic =
                WindowInsets.Builder()
                    .setInsets(
                        WindowInsets.Type.statusBars(),
                        Insets.of(dp(12), dp(topDp), dp(16), 0),
                    )
                    .setInsets(
                        WindowInsets.Type.navigationBars(),
                        Insets.of(dp(12), 0, dp(16), dp(bottomDp)),
                    )
                    .setInsets(
                        WindowInsets.Type.displayCutout(),
                        Insets.of(dp(24), 0, dp(32), 0),
                    )
                    .build()
            runner.runOnMainSync {
                // Keep the synthetic cutout for the whole traversal: an ordinary layout requests
                // real window insets again, which otherwise overwrites a one-shot injection.
                ViewCompat.setOnApplyWindowInsetsListener(root) { view, _ ->
                    activity.applySystemInsets(WindowInsetsCompat.toWindowInsetsCompat(synthetic, view))
                }
                root.dispatchApplyWindowInsets(synthetic)
            }

            waitFor("Core safe-area contract did not reach actual layout") {
                val layout =
                    json(
                        core,
                        """JSON.stringify({left:document.querySelector('.main').getBoundingClientRect().left,
                            right:innerWidth-document.querySelector('.main').getBoundingClientRect().right,
                            bodyLeft:getComputedStyle(document.body).paddingLeft,
                            bodyRight:getComputedStyle(document.body).paddingRight})""",
                    )
                layout.getDouble("left") >= leftDp - 0.5 &&
                    layout.getDouble("right") >= rightDp - 0.5 &&
                    layout.getString("bodyLeft").startsWith("24") &&
                    layout.getString("bodyRight").startsWith("32")
            }

            var pluginLayout = JSONObject()
            waitFor("Plugin safe-area contract did not reach actual layout") {
                pluginLayout =
                    json(
                        plugin,
                        """JSON.stringify({left:document.querySelector('#insets-probe').getBoundingClientRect().left,
                            right:innerWidth-document.querySelector('#insets-probe').getBoundingClientRect().right})""",
                    )
                pluginLayout.has("left") && pluginLayout.has("right")
            }

            // Convert the plugin WebView origin to CSS pixels, then verify screen-relative
            // bounds rather than trusting either a native field or a JS variable.
            var pluginOrigin = 0
            var pluginWidth = 0
            var screenWidth = 0
            try {
            waitFor("Native plugin bounds did not avoid the cutout") {
                runner.runOnMainSync {
                    pluginOrigin = plugin.left
                    pluginWidth = plugin.width
                    screenWidth = root.width
                }
                pluginOrigin / density >= leftDp + MainActivity.NAV_RAIL_WIDTH_DP - 0.5 &&
                    (screenWidth - pluginOrigin - pluginWidth) / density >= rightDp - 0.5
            }
            } catch(e:IllegalStateException) {
                var diagnostic=""
                runner.runOnMainSync {
                    val params=plugin.layoutParams as android.widget.FrameLayout.LayoutParams
                    val field=MainActivity::class.java.getDeclaredField("lastInsets").apply{isAccessible=true}
                    diagnostic="origin=$pluginOrigin width=$pluginWidth root=$screenWidth density=$density margins=${params.leftMargin},${params.rightMargin} lastInsets=${field.get(activity)}"
                }
                throw IllegalStateException(diagnostic,e)
            }
            val originDp = pluginOrigin / density
            check(pluginLayout.getDouble("left") + originDp >= leftDp + MainActivity.NAV_RAIL_WIDTH_DP - 0.5) { pluginLayout.toString() }
            check(pluginLayout.getDouble("right") + (screenWidth - pluginOrigin - pluginWidth) / density >= rightDp - 0.5) {
                pluginLayout.toString()
            }
            passed("Real core and plugin layouts avoid nonzero synthetic cutout/system-bar safe areas")
        } finally {
            activity?.let { app ->
                if (!app.isDestroyed) runner.runOnMainSync { app.openPlugin("harness") }
            }
            PluginPlatform.manage(context, JSONObject().put("action", "remove").put("id", pluginId))
            originalInsets?.let { insets ->
                activity?.let { app ->
                    val restoreRoot = rootField(app)
                    runner.runOnMainSync {
                        ViewCompat.setOnApplyWindowInsetsListener(restoreRoot) { _, incoming -> app.applySystemInsets(incoming) }
                        restoreRoot.dispatchApplyWindowInsets(insets)
                    }
                }
            }
            activity?.let { app ->
                if (!app.isDestroyed) runner.runOnMainSync { app.finish() }
            }
            preferences.edit().apply {
                if (previousMode == null) remove("dshMode") else putString("dshMode", previousMode)
                if (previousHarness == null) remove("harnessMode") else putString("harnessMode", previousHarness)
            }.commit()
            shell("cmd uimode night ${nightCommand(originalNight)}")
        }
    }
}
