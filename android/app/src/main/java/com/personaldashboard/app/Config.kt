package com.personaldashboard.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * 本地配置。普通项放私有 SharedPreferences；密钥（DeepSeek API Key、dsh 登录 Cookie） 用 Android Keystore 里的 AES-256-GCM
 * 密钥加密后再存，且永不回传给 JS（只告诉“是否已设置”）。 allowBackup=false + 数据提取规则全排除，备份/换机都不会带走。
 */
class Config(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pd", Context.MODE_PRIVATE)

    /** embedded = App 内置的 dsh 运行时（默认，自动启动、自动登录）；external = 高级选项，连 DSHBox/Termux 里的 dsh */
    var dshMode: String
        get() = sp.getString("dshMode", "embedded") ?: "embedded"
        set(v) =
            sp.edit().putString("dshMode", if (v == "external") "external" else "embedded").apply()

    val embedded: Boolean
        get() = dshMode != "external"

    /** 外部 dsh 地址（仅 external 模式使用） */
    var extDshUrl: String
        get() = sp.getString("dshUrl", DEFAULT_DSH) ?: DEFAULT_DSH
        set(v) = sp.edit().putString("dshUrl", v.trim().trimEnd('/')).apply()

    /** 当前生效的 dsh 地址 */
    var dshUrl: String
        get() = if (embedded) EMBEDDED_DSH else extDshUrl
        set(v) {
            if (!embedded) extDshUrl = v
        }

    var harnessMode: String
        get() = sp.getString("harnessMode", "native") ?: "native"
        set(v) = sp.edit().putString("harnessMode", v).apply()

    var keepAwakeWhileRunning: Boolean
        get() = sp.getBoolean("keepAwakeWhileRunning", false)
        set(v) = sp.edit().putBoolean("keepAwakeWhileRunning", v).apply()

    var ntfyUrl: String
        get() = sp.getString("ntfyUrl", "") ?: ""
        set(v) = sp.edit().putString("ntfyUrl", v.trim()).apply()

    private val cookieKey
        get() = if (embedded) "dshCookieE" else "dshCookie"

    var dshCookie: String
        get() = secret(cookieKey)
        set(v) = putSecret(cookieKey, v)

    /** DeepSeek API Key：只在启动内置 dsh 时作为环境变量 DEEPSEEK_API_KEY 传入，不回传 JS */
    var deepseekKey: String
        get() = secret("deepseekKey")
        set(v) {
            synchronized(this) {
                val next = v.trim()
                val changed =
                    if (next.isEmpty()) secretStored("deepseekKey")
                    else next != readableSecret("deepseekKey")
                if (changed) putSecret("deepseekKey", next)
            }
        }

    fun public(appVersion: String, dshboxInstalled: Boolean): JSONObject =
        JSONObject()
            .put("dshMode", dshMode)
            .put("extDshUrl", extDshUrl)
            .put("deepseekKeySet", secretStored("deepseekKey"))
            .put("dshUrl", dshUrl)
            .put("dshPaired", secretStored(cookieKey))
            .put("dshPairedAt", sp.getLong("dshPairedAtS", 0L))
            .put("harnessMode", harnessMode)
            .put("ntfyUrl", ntfyUrl)
            .put("keepAwakeWhileRunning", keepAwakeWhileRunning)
            .put("appVersion", appVersion)
            .put("dshboxInstalled", dshboxInstalled)

    /** 仅供同 UID 的 AppControl 读取，严禁复用 public() 返回整体配置。 */
    @Synchronized
    fun appControlSettings(): AppControlPolicy.Settings =
        AppControlPolicy.Settings(
            keepAwakeWhileRunning = sp.getBoolean(AppControlPolicy.FIELD_KEEP_AWAKE, false),
        )

    /** 先完整校验，再用一个 Editor.commit 原子保存，失败返回异常而绝不报告成功。 */
    @Synchronized
    fun updateAppControl(patch: AppControlPolicy.Patch): AppControlPolicy.Settings {
        AppControlPolicy.validatePatch(patch)
        val nextKeepAwake =
            patch.keepAwakeWhileRunning ?: sp.getBoolean(AppControlPolicy.FIELD_KEEP_AWAKE, false)
        val edit = sp.edit()
        patch.keepAwakeWhileRunning?.let { edit.putBoolean(AppControlPolicy.FIELD_KEEP_AWAKE, it) }
        check(edit.commit()) { "设置保存失败" }
        return AppControlPolicy.Settings(nextKeepAwake)
    }

    fun markPaired(cookie: String) {
        dshCookie = cookie
        sp.edit().putLong("dshPairedAtS", System.currentTimeMillis() / 1000).apply()
    }

    fun unpair() {
        putSecret(cookieKey, "")
        sp.edit().putLong("dshPairedAtS", 0L).apply()
    }

    /** setConfig：只接受已知字段；dshUrl 必须是本机回环地址（安全约束：dsh 不出本机） */
    fun apply(p: JSONObject) {
        if (p.has("dshMode")) dshMode = p.optString("dshMode")
        if (p.has("deepseekKey")) deepseekKey = p.optString("deepseekKey")
        if (p.has("dshUrl")) {
            val u = p.optString("dshUrl").trim().trimEnd('/')
            require(isLoopback(u)) { "dsh 地址只能是本机回环地址（127.0.0.1 / localhost）" }
            if (u != extDshUrl && !embedded) unpair()
            extDshUrl = u
        }
        if (p.has("harnessMode"))
            harnessMode = if (p.optString("harnessMode") == "web") "web" else "native"
        if (p.has("keepAwakeWhileRunning"))
            keepAwakeWhileRunning = p.optBoolean("keepAwakeWhileRunning", false)
        if (p.has("ntfyUrl")) ntfyUrl = p.optString("ntfyUrl")
    }

    // ---------------------------------------------------------------- 加密存储
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    private fun putSecret(name: String, v: String) {
        val edit = sp.edit()
        writeSecret(edit, name, v)
        edit.apply()
    }

    private fun writeSecret(
        edit: android.content.SharedPreferences.Editor,
        name: String,
        v: String,
    ) {
        if (v.isEmpty()) {
            edit.remove("s_$name")
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(v.toByteArray(Charsets.UTF_8))
        edit.putString("s_$name", Base64.encodeToString(out, Base64.NO_WRAP))
    }

    private fun secret(name: String): String {
        val b = sp.getString("s_$name", null) ?: return ""
        return try {
            val raw = Base64.decode(b, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("凭据解密失败，请重新保存", e)
        }
    }

    private fun secretStored(name: String): Boolean = sp.contains("s_$name")

    private fun readableSecret(name: String): String? =
        try {
            secret(name)
        } catch (_: IllegalStateException) {
            null
        }

    companion object {
        const val DEFAULT_DSH = "http://127.0.0.1:3080"
        const val EMBEDDED_PORT = 3090 // Public core port; the private full build retains 3088.
        const val EMBEDDED_DSH = "http://127.0.0.1:$EMBEDDED_PORT"
        private const val ALIAS = "pd-secrets-v1"

        fun isLoopback(u: String): Boolean =
            Regex("^http://(127\\.0\\.0\\.1|localhost|\\[::1\\])(:\\d{1,5})?$").matches(u)
    }
}
