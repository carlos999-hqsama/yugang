package com.personaldashboard.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.util.Base64

/** Synthetic encrypted-pref integrity checks for the dedicated Android emulator. */
internal class ConfigSecretIntegrityDeviceChecks(private val runner: Instrumentation) {
    private class IsolatedContext(
        private val base: Context,
        private val prefix: String,
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) =
            base.getSharedPreferences(prefix + name, mode)
    }

    private fun passed(text: String) =
        runner.sendStatus(0, Bundle().apply { putString("stream", "PASS: $text\n") })

    private fun fixedFailure(action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        check(error is IllegalStateException && error.message == "凭据解密失败，请重新保存") {
            "Damaged credential did not produce the fixed safe error"
        }
    }

    fun run() {
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator")) { "仅允许专用模拟器" }
        val prefix = "config-secret-integrity-" + java.util.UUID.randomUUID().toString().take(8) + "-"
        val context = IsolatedContext(runner.targetContext, prefix)
        val preferences = context.getSharedPreferences("pd", Context.MODE_PRIVATE)
        val damagedCipher = Base64.encodeToString(ByteArray(28), Base64.NO_WRAP)
        val replacementCredential = "synthetic-key-" + java.util.UUID.randomUUID()

        try {
            preferences.edit().putString("s_deepseekKey", damagedCipher).commit()
            val config = Config(context)
            check(config.public("synthetic", false).getBoolean("deepseekKeySet")) {
                "Public config failed to report a stored damaged DeepSeek key by presence"
            }
            fixedFailure { config.deepseekKey }

            config.deepseekKey = ""
            check(!preferences.contains("s_deepseekKey")) { "Explicit DeepSeek clear did not remove the damaged key" }
            config.deepseekKey = replacementCredential
            val stored = preferences.getString("s_deepseekKey", null)
            check(stored != null && stored != damagedCipher && stored != replacementCredential) {
                "New DeepSeek key was not stored as an encrypted value"
            }
            check(config.deepseekKey == replacementCredential) { "New DeepSeek key was not readable" }
            check(config.public("synthetic", false).getBoolean("deepseekKeySet")) {
                "Public config lost the replaced DeepSeek key"
            }
            passed("DeepSeek encrypted-key presence, clear, replacement, and read behavior are intact")
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
