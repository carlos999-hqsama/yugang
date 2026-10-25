package com.personaldashboard.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONObject

/** Opaque host-bound factory/context key. Plugin code never constructs this value. */
class PluginApiSessionContextId internal constructor(
    val pluginId: String,
    val context: String,
) {
    override fun equals(other: Any?): Boolean =
        other is PluginApiSessionContextId &&
            other.pluginId == pluginId &&
            other.context == context

    override fun hashCode(): Int = 31 * pluginId.hashCode() + context.hashCode()

    override fun toString(): String = "PluginApiSessionContextId(${pluginId.hashCode()},$context)"
}

/** Process-wide entry point. All factories for one app data root share their in-memory jars. */
object PluginApiSessions {
    private const val STORAGE_DIRECTORY = "PLUGIN_API_SESSIONS"
    private val factories = ConcurrentHashMap<String, PluginApiSessionFactory>()

    fun of(context: Context): PluginApiSessionFactory {
        val root = File(context.applicationContext.filesDir, STORAGE_DIRECTORY)
        return factories.computeIfAbsent(root.canonicalFile.absolutePath) {
            PluginApiSessionFactory(root, AndroidKeystorePluginApiSessionCipher())
        }
    }
}

/**
 * Minimal controller-facing API. Cookie values are only passed to OkHttp and the encrypted store.
 * `status` reports counts/timestamps; it never claims that a service login remains valid.
 */
class PluginApiSessionFactory internal constructor(
    root: File,
    cipher: PluginApiSessionCipher,
) {
    private val store = PluginApiSessionStore(root, cipher)
    private val jars = ConcurrentHashMap<PluginApiSessionContextId, CookieJar>()

    fun contextId(
        pluginId: String,
        context: String? = PluginApiSessionPolicy.DEFAULT_CONTEXT,
    ): PluginApiSessionContextId = PluginApiSessionPolicy.contextId(pluginId, context)

    fun cookieJar(id: PluginApiSessionContextId): CookieJar =
        synchronized(jars) { jars.computeIfAbsent(id) { PluginApiSessionCookieJar(id, store) } }

    fun credentialsJar(
        args: JSONObject,
        hostPluginId: String?,
    ): CookieJar? = PluginApiSessionPolicy.credentialsContext(args, hostPluginId)?.let { cookieJar(it) }

    fun status(id: PluginApiSessionContextId): JSONObject {
        val jar = cookieJar(id)
        require(jar is PluginApiSessionCookieJar)
        return jar.status()
    }

    fun clear(id: PluginApiSessionContextId): JSONObject {
        synchronized(jars) {
            val jar = jars.remove(id) as? PluginApiSessionCookieJar
            if (jar != null) jar.clear() else store.clear(id)
        }
        return JSONObject().put("pluginId", id.pluginId).put("context", id.context).put("cleared", true)
    }

    /** Bind this to the host-derived plugin identity; never accept pluginId from plugin JSON. */
    fun call(hostPluginId: String, args: JSONObject): JSONObject {
        val id = contextId(hostPluginId, args.optString("context", PluginApiSessionPolicy.DEFAULT_CONTEXT))
        return when (args.getString("op")) {
            "status" -> status(id)
            "clear" -> clear(id)
            else -> throw IllegalArgumentException("不支持的登录会话操作")
        }
    }

    /** Precise uninstall cleanup. Installs, updates, rollbacks, and disable/enable retain sessions. */
    fun clearPlugin(pluginId: String): JSONObject {
        val id = PluginApiSessionPolicy.pluginId(pluginId)
        synchronized(jars) {
            jars.keys.filter { it.pluginId == id }.forEach { key ->
                (jars.remove(key) as? PluginApiSessionCookieJar)?.clear()
            }
        }
        store.clearPlugin(id)
        return JSONObject().put("pluginId", id).put("cleared", true)
    }
}

internal class PluginApiSessionCookieJar(
    private val id: PluginApiSessionContextId,
    private val store: PluginApiSessionStore,
) : CookieJar {
    private val lock = Any()
    private var state: PluginApiSessionState = store.read(id)
    private var revoked = false

    private val cookies: List<Cookie>
        get() = state.cookies

    private val updatedAtMs: Long
        get() = state.updatedAtMs

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        PluginApiSessionPolicy.validateUrlForCookie(url)
        synchronized(lock) {
            if (revoked) return emptyList()
            return PluginApiSessionPolicy.requestCookies(cookies, url, System.currentTimeMillis())
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        PluginApiSessionPolicy.validateUrlForCookie(url)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (revoked) return
            val next = PluginApiSessionPolicy.replace(this.cookies, cookies, now)
            store.write(id, next, now)
            state = PluginApiSessionState(next, now)
        }
    }

    fun status(): JSONObject =
        synchronized(lock) {
            PluginApiSessionPolicy.status(id, cookies, System.currentTimeMillis(), updatedAtMs)
        }

    fun clear() {
        synchronized(lock) {
            store.clear(id)
            revoked = true
            state = PluginApiSessionState(emptyList(), 0L)
        }
    }
}

internal data class PluginApiSessionState(
    val cookies: List<Cookie>,
    val updatedAtMs: Long,
)

internal interface PluginApiSessionCipher {
    fun seal(plaintext: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/** One app-level AndroidKeystore AES-256/GCM key protects every plugin's opaque session files. */
internal class AndroidKeystorePluginApiSessionCipher : PluginApiSessionCipher {
    private val key: SecretKey by lazy { key() }

    @Synchronized
    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size in 1..255) { "登录会话加密 IV 无效" }
        return MAGIC + byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plaintext)
    }

    @Synchronized
    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > MAGIC.size + 1 && sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "登录会话存储无效"
        }
        val ivSize = sealed[MAGIC.size].toInt() and 0xff
        val start = MAGIC.size + 1
        require(ivSize in 1..255 && sealed.size > start + ivSize + 16) {
            "登录会话存储无效"
        }
        val iv = sealed.copyOfRange(start, start + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(sealed, start + ivSize, sealed.size - start - ivSize)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "pd-plugin-api-session-cookies-v1"
        val MAGIC = "YG_PLUGIN_SESSION_V1".toByteArray(Charsets.US_ASCII)
    }
}

internal class PluginApiSessionStore(
    root: File,
    private val cipher: PluginApiSessionCipher,
) {
    private val directory = File(root, "sessions")

    internal fun read(id: PluginApiSessionContextId): PluginApiSessionState {
        val file = file(id)
        if (!file.isFile) return PluginApiSessionState(emptyList(), 0L)
        return try {
            val plain = cipher.open(file.readBytes())
            val json = JSONObject(String(plain, Charsets.UTF_8))
            PluginApiSessionState(
                PluginApiSessionPolicy.decodeCookies(json),
                json.optLong("updatedAtMs", 0L).coerceAtLeast(0L),
            )
        } catch (e: Exception) {
            throw IllegalStateException("插件登录会话存储损坏，请清除后重试", e)
        }
    }

    internal fun write(
        id: PluginApiSessionContextId,
        cookies: List<Cookie>,
        updatedAtMs: Long,
    ) {
        val file = file(id)
        if (cookies.isEmpty()) {
            if (file.isFile && !file.delete()) throw IllegalStateException("登录会话清理失败")
            return
        }
        val payload =
            PluginApiSessionPolicy
                .encodeCookies(cookies, System.currentTimeMillis())
                .put("updatedAtMs", updatedAtMs)
                .toString()
                .toByteArray(Charsets.UTF_8)
        val sealed = cipher.seal(payload)
        file.parentFile?.mkdirs()
        check(file.parentFile?.isDirectory == true) { "登录会话存储不可用" }
        val temporary = File.createTempFile("session-", ".tmp", directory)
        try {
            temporary.writeBytes(sealed)
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.isFile) temporary.delete()
        }
    }

    internal fun clear(id: PluginApiSessionContextId) {
        val file = file(id)
        if (file.isFile && !file.delete()) throw IllegalStateException("登录会话清理失败")
    }

    internal fun clearPlugin(pluginId: String) {
        val token = PluginApiSessionPolicy.storageToken(pluginId)
        val target = File(directory, token)
        if (!target.exists()) return
        target.walkBottomUp().forEach { file ->
            if (file.isDirectory) check(file.delete()) { "插件登录会话目录清理失败" }
            else check(file.delete()) { "插件登录会话文件清理失败" }
        }
        check(!target.exists()) { "插件登录会话目录清理失败" }
    }

    private fun file(id: PluginApiSessionContextId): File {
        val plugin = PluginApiSessionPolicy.storageToken(id.pluginId)
        val context = PluginApiSessionPolicy.storageToken(id.context)
        return File(File(directory, plugin), "$context.session")
    }
}
