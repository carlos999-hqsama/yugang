package com.personaldashboard.app

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Trusted local plugin code runs in a short-lived Node process, never in the privileged WebView.
 * Android UID is the isolation boundary; this is NOT a sandbox for hostile plugin authors.
 */
object PluginProcess {
    private const val MAX_INPUT_BYTES = 200_000
    private const val MAX_STDERR_BYTES = 131_072
    private const val MAX_STDOUT_CHARS = 131_072
    private const val IO_BUFFER_SIZE = 4_096
    private const val IO_THREAD_COUNT = 3
    private const val PID_HANDSHAKE_TIMEOUT_SECONDS = 3L
    private const val FINALIZATION_TIMEOUT_SECONDS = 1L
    internal const val EXECUTION_TIMEOUT_SECONDS = 72L

    fun run(context: Context, request: JSONObject, cancellation: PluginCancellation): JSONObject = PluginInvocationBudget.run {
        runAdmitted(context, request, cancellation)
    }

    private fun runAdmitted(context: Context, request: JSONObject, cancellation: PluginCancellation): JSONObject {
        cancellation.check()
        val bytes = PluginInvocationBudget.executable(request)
        val lib = context.applicationInfo.nativeLibraryDir
        val root = File(context.filesDir, "rt/rootfs")
        require(File(root, "opt/pd/plugin-runner.mjs").isFile) { "请先安装插件版宿主运行时" }
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val pluginId =
            PluginContract.id(
                request.getJSONObject("bundle").getJSONObject("manifest").getString("id"),
            )
        BotDirectoryLayout.prepare(context.filesDir)
        File(context.filesDir, "PLUGINS/$pluginId").mkdirs()
        val temp = File(context.cacheDir, "plugin-proot").apply { mkdirs() }
        val cmd =
            listOf(
                "$lib/libproot.so",
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                root.path,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                "/system",
                "-b",
                "/apex",
                "-b",
                "/proc/self/fd:/dev/fd",
                "-b",
                "${temp.path}:/dev/shm",
                "-b",
                "${home.path}:/root/projects",
                "-b",
                "${context.filesDir}/BOT:/BOT",
                "-b",
                "${context.filesDir}/CONFIG:/CONFIG",
                "-b",
                "${context.filesDir}/PLUGINS:/PLUGINS",
                "-w",
                "/PLUGINS/$pluginId",
                "/usr/local/bin/node",
                "/opt/pd/plugin-runner.mjs",
            )
        val launch =
            listOf(
                "/system/bin/setsid",
                "/system/bin/sh",
                "-c",
                "echo PD_PLUGIN_PID=\$\$; exec \"\$@\"",
                "pd-plugin",
            ) + cmd
        val builder = ProcessBuilder(launch).directory(home)
        builder.environment().apply {
            clear()
            put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("PROOT_TMP_DIR", temp.path)
            put("LD_LIBRARY_PATH", lib)
            put("HOME", "/root")
            put("PATH", "/usr/local/bin:/usr/bin:/bin")
            put("LANG", "C.UTF-8")
            put("ANDROID_ROOT", "/system")
            put("ANDROID_DATA", "/data")
            put("PD_HOST_RUNTIME_SOCKET", HostRuntimeControl.start(context))
        }
        val process = builder.start()
        val finalized=java.util.concurrent.atomic.AtomicBoolean(false)
        val detachProcess=cancellation.onCancel { if(!finalized.get())process.destroyForcibly() }
        var detachGroup:(()->Unit)?=null
        val pool = Executors.newFixedThreadPool(IO_THREAD_COUNT)
        val logsExceeded = java.util.concurrent.atomic.AtomicBoolean(false)
        var group = 0
        try {
            // Drain stderr with a bound; never forward plugin logs or arbitrary exception text to
            // UI.
            val errors = pool.submit {
                var n = 0
                val b = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    val k = process.errorStream.read(b)
                    if (k < 0) break
                    n += k
                    if (n > MAX_STDERR_BYTES) {
                        logsExceeded.set(true)
                        cancellation.cancel()
                        throw IllegalStateException("插件日志过多，执行已停止")
                    }
                }
            }
            val reader = process.inputStream.bufferedReader()
            val first =
                pool
                    .submit<String> { reader.readLine() ?: "" }
                    .get(PID_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            group =
                first.removePrefix("PD_PLUGIN_PID=").toIntOrNull()?.takeIf { it > 1 }
                    ?: error("插件进程未报告身份")
            val activeGroup = group
            detachProcess()
            detachGroup=cancellation.onCancel { if(!finalized.get()&&process.isAlive){runCatching { Os.kill(-activeGroup, OsConstants.SIGKILL) };process.destroyForcibly()} }
            cancellation.check()
            val writer = pool.submit { process.outputStream.use { it.write(bytes) } }
            val output =
                pool.submit<String> {
                    val out = StringBuilder()
                    val buffer = CharArray(IO_BUFFER_SIZE)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        out.append(buffer, 0, count)
                        check(out.length <= MAX_STDOUT_CHARS) { "插件输出过大" }
                    }
                    out.toString()
                }
            val text = output.get(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            writer.get(FINALIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            errors.get(FINALIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            require(process.waitFor(FINALIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "插件尚未退出，操作结果需核对"
            }
            val envelope = JSONObject(text)
            require(!envelope.has("error")) { envelope.optString("detail", "插件执行失败，操作结果需核对") }
            require(process.exitValue() == 0) { "插件执行失败，操作结果需核对" }
            cancellation.check()
            return envelope.getJSONObject("value")
        } catch (_: java.util.concurrent.TimeoutException) {
            error(if(logsExceeded.get()) "插件日志过多，执行已停止" else "插件执行超时，已停止；不会自动重试")
        } catch (e: Exception) {
            if(logsExceeded.get())error("插件日志过多，执行已停止")
            throw e
        } finally {
            finalized.set(true);detachProcess();detachGroup?.invoke()
            if (group > 1&&process.isAlive) runCatching { Os.kill(-group, OsConstants.SIGKILL) }
            process.destroyForcibly()
            pool.shutdownNow()
        }
    }
}
