package com.personaldashboard.app

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

private const val RUNTIME_SPACE_RESERVE_BYTES = 64L shl 20

/** Conservative preflight using only space that is currently free, without cache eviction. */
internal fun checkRuntimeSpace(unpackedBytes: Long, usableBytes: Long) {
    require(unpackedBytes > 0) { "运行时清单缺少有效解包大小" }
    val need = Math.addExact(unpackedBytes, RUNTIME_SPACE_RESERVE_BYTES)
    check(usableBytes >= need) {
        if (usableBytes == 0L) "无可用存储空间或无法确认容量，已中止安装"
        else "存储空间不足：需要约 ${need shr 20} MB，可用 ${usableBytes shr 20} MB"
    }
}

/**
 * 内置 dsh 运行时（一体化）：APK assets 里带一个 runtime.tar.xz（Debian arm64 rootfs + Node.js + dsh）， 首次启动解包到
 * files/rt/rootfs，然后用 PRoot（jniLibs 里的 libproot.so）在其中运行 dsh web， 只监听 127.0.0.1:3090。dsh 启动时打印的
 * `dsh web: http://…/?token=…` 由这里读取， 原生侧直接换取登录 Cookie —— 用户无需任何配对。
 *
 * 安全：PRoot 不是安全边界，真正的边界是 Android 应用沙箱；dsh 端口只在回环地址，不对外。 进程级单例，由 [DshService]（前台服务）持有生命周期。
 */
object Runtime {
    private const val TAG = "PD-RT"
    const val ASSET = "runtime/runtime.tar.xz"
    const val MANIFEST = "runtime/runtime.json"

    private const val GROUP_READY_TIMEOUT_SECONDS = 2L
    private const val WORKER_JOIN_TIMEOUT_MILLIS = 5_000L
    private const val MAX_RESTART_ATTEMPTS = 3
    private const val RESTART_BACKOFF_MILLIS = 1_500L
    private const val START_TIMEOUT_MILLIS = 600_000L
    private const val START_WATCHDOG_INTERVAL_MILLIS = 1_000L
    private const val TERMINATE_TIMEOUT_SECONDS = 3L

    @Volatile
    var state = "idle"
        private set // idle|installing|starting|ready|error|stopped|missing

    @Volatile
    var progress = 0.0
        private set // 解包进度 0..1

    @Volatile
    var message = ""
        private set // 给用户看的一句话

    @Volatile
    var detail = ""
        private set // 诊断信息（设置 > 高级）

    @Volatile
    var firstRun = false
        private set

    private val logs = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    @Volatile private var proc: Process? = null
    @Volatile private var groupPid = 0
    @Volatile private var pidFile: File? = null
    @Volatile private var groupReady = CountDownLatch(0)
    @Volatile private var worker: Thread? = null
    @Volatile private var wanted = false
    private var restarts = 0
    private val control = Executors.newSingleThreadExecutor()

    fun addListener(l: (JSONObject) -> Unit) {
        listeners += l
    }

    fun removeListener(l: (JSONObject) -> Unit) {
        listeners -= l
    }

    fun status(ctx: Context): JSONObject =
        JSONObject()
            .put("state", state)
            .put("progress", progress)
            .put("message", message)
            .put("firstRun", firstRun)
            .put("bundled", bundled(ctx))
            .put("port", Config.EMBEDDED_PORT)

    fun diagnostics(ctx: Context): JSONObject =
        status(ctx)
            .put("detail", detail)
            .put("manifest", manifest(ctx) ?: JSONObject())
            .put("installed", installedSha(ctx))
            .put("abi", Build.SUPPORTED_ABIS.joinToString())
            .put("log", synchronized(logs) { logs.joinToString("\n") })

    private fun set(s: String, msg: String, det: String = detail, p: Double = progress) {
        if (s != state || msg != message)
            Log.i(
                TAG,
                "state=$s msg=$msg${if (det.isNotEmpty() && s == "error") " detail=" + det.take(300) else ""}",
            )
        state = s
        message = msg
        detail = det
        progress = p
        val j =
            JSONObject()
                .put("state", s)
                .put("progress", p)
                .put("message", msg)
                .put("firstRun", firstRun)
        listeners.forEach { runCatching { it(j) } }
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > 400) logs.removeFirst()
        }
    }

    fun bundled(ctx: Context) = runCatching {
        ctx.assets.open(MANIFEST).close()
        true
    }
        .getOrDefault(false)

    private fun manifest(ctx: Context): JSONObject? = runCatching {
        JSONObject(ctx.assets.open(MANIFEST).bufferedReader().readText())
    }
        .getOrNull()

    private fun rtDir(ctx: Context) = File(ctx.filesDir, "rt")

    private fun rootfs(ctx: Context) = File(rtDir(ctx), "rootfs")

    private fun homeDir(ctx: Context) =
        File(ctx.filesDir, "home").apply { mkdirs() } // → /root/projects（会话、设置、工作区）

    private fun installedSha(ctx: Context) =
        File(rtDir(ctx), "installed").takeIf { it.exists() }?.readText()?.trim() ?: ""

    /** 确保运行（幂等）。在后台线程里完成解包 + 启动 + 自动登录。 */
    fun ensure(ctx: Context, cfg: Config, net: Net) {
        val app = ctx.applicationContext
        submit { startWorker(app, cfg, net) }
    }

    // 控制操作串行执行，等待旧线程退出也不阻塞 Service 的主线程。
    private fun submit(action: () -> Unit) {
        control.execute {
            runCatching(action).onFailure { set("error", "Harness 操作失败，请重试", it.toString()) }
        }
    }

    private fun startWorker(ctx: Context, cfg: Config, net: Net) {
        if (worker?.isAlive == true) return
        wanted = true
        worker =
            Thread(
                    {
                        try {
                            loop(ctx, cfg, net)
                        } catch (_: InterruptedException) {
                            /* stop/restart */
                        } catch (_: InterruptedIOException) {
                            /* 解包取消，保留当前已安装版本 */
                        } catch (e: Exception) {
                            if (wanted) {
                                Log.e(TAG, "runtime", e)
                                set("error", "Harness 启动失败", e.toString())
                            }
                        }
                    },
                    "pd-runtime",
                )
                .apply { start() }
    }

    fun stop(onStopped: (Boolean) -> Unit = {}) {
        submit { onStopped(stopWorker()) }
    }

    private fun stopWorker(): Boolean {
        wanted = false
        val stopping = proc
        stopping?.let {
            if (
                !groupReady.await(GROUP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
                    it.isAlive &&
                    recordedPid() == 0
            ) {
                set("error", "Harness 正在准备停止，请稍后重试", "等待启动进程组确认超时")
                return false
            }
            terminate(it, groupPid)
        }
        worker?.interrupt()
        worker?.join(WORKER_JOIN_TIMEOUT_MILLIS)
        if (worker?.isAlive == true) {
            set("error", "Harness 尚未停止，请稍后重试", "旧运行线程尚未退出")
            return false
        }
        HostRuntimeControl.stop()
        AppControl.stop()
        worker = null
        proc = null
        groupPid = 0
        set("stopped", "Harness 已停止")
        return true
    }

    fun restart(ctx: Context, cfg: Config, net: Net) {
        val app = ctx.applicationContext
        submit {
            if (stopWorker()) {
                restarts = 0
                startWorker(app, cfg, net)
            }
        }
    }

    private fun loop(ctx: Context, cfg: Config, net: Net) {
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            set(
                "error",
                "这台设备不是 ARM64，无法运行内置 Harness",
                "ABIs=${Build.SUPPORTED_ABIS.joinToString()}",
            )
            return
        }
        val man =
            manifest(ctx)
                ?: run {
                    set(
                        "missing",
                        "这个安装包没有内置 Harness 运行时",
                        "assets/$MANIFEST 不存在（构建时未运行 tools/runtime/fetch.sh + pack.py）",
                    )
                    return
                }
        val sha = man.optString("sha256")
        if (installedSha(ctx) != sha || !File(rootfs(ctx), "opt/pd/start-dsh.sh").exists())
            install(ctx, man)
        RuntimeDns(ctx, rootfs(ctx)).use { dns ->
            dns.start()
            while (wanted) {
                set("starting", "正在启动 Harness…", p = 1.0)
                val ok = runOnce(ctx, cfg, net)
                if (!wanted) break
                if (ok) restarts = 0
                if (++restarts > MAX_RESTART_ATTEMPTS) {
                    set("error", "Harness 多次意外退出", detail)
                    return
                }
                Thread.sleep(RESTART_BACKOFF_MILLIS * restarts)
            }
        }
    }

    // ------------------------------------------------------------ 解包
    private fun install(ctx: Context, man: JSONObject) {
        firstRun = installedSha(ctx).isEmpty()
        val total = man.optLong("xzBytes", 1).coerceAtLeast(1)
        set("installing", if (firstRun) "首次使用，正在准备 Harness…" else "正在更新 Harness…", "", 0.0)
        val dir = rtDir(ctx).apply { mkdirs() }
        val tmp = File(dir, "rootfs.new")
        val old = File(dir, "rootfs.old")
        deleteTree(tmp)
        deleteTree(old)
        checkRuntimeSpace(man.optLong("bytes", 0), dir.usableSpace)
        var last = 0L
        ctx.assets.open(ASSET).use { raw ->
            val counted =
                Counting(raw.buffered(1 shl 16)) { n ->
                    if (!wanted || Thread.currentThread().isInterrupted)
                        throw InterruptedIOException("安装已取消")
                    if (n - last > total / 200) {
                        last = n
                        set("installing", message, "", n.toDouble() / total)
                    }
                }
            TarXz.extract(counted, tmp)
        }
        File(tmp, "tmp").mkdirs()
        runCatching { Os.chmod(File(tmp, "tmp").path, "1777".toInt(8)) }
        if (!wanted || Thread.currentThread().isInterrupted) throw InterruptedIOException("安装已取消")
        val cur = rootfs(ctx)
        if (cur.exists()) check(cur.renameTo(old)) { "无法保留旧运行时" }
        if (!tmp.renameTo(cur)) {
            old.renameTo(cur)
            error("无法切换运行时目录")
        }
        deleteTree(old)
        File(dir, "installed").writeText(man.optString("sha256"))
        log("installed runtime ${man.optString("version")}")
    }

    /** 删除目录树，不跟随符号链接（rootfs 里有大量指向 / 的绝对链接） */
    fun deleteTree(f: File) {
        val st = runCatching { Os.lstat(f.path) }.getOrNull() ?: return
        if (OsConstants.S_ISDIR(st.st_mode)) {
            runCatching { Os.chmod(f.path, "700".toInt(8)) }
            f.list()?.forEach { deleteTree(File(f, it)) }
        }
        runCatching { Os.remove(f.path) }
    }

    // ------------------------------------------------------------ 运行
    /** 运行一次 dsh，直到退出。返回是否曾经就绪。 */
    private fun runOnce(ctx: Context, cfg: Config, net: Net): Boolean {
        val appSocket = AppControl.start(ctx.applicationContext)
        // Plugin and preview service channel, separate from the short AppControl settings socket.
        // A startup failure does not block basic Harness conversations.
        val hostRuntimeSocket = runCatching {
            HostRuntimeControl.start(ctx.applicationContext)
        }
            .getOrElse {
                Log.w("PD-RT", "HostRuntimeControl start failed", it)
                ""
            }
        val lib = ctx.applicationInfo.nativeLibraryDir
        val root = rootfs(ctx).path
        val ptmp = File(ctx.cacheDir, "proot").apply { mkdirs() }
        val home = homeDir(ctx).path
        PhoneWorkspace.prepare(ctx)
        BotWorkspaces.get(ctx).call(org.json.JSONObject().put("op", "list"))
        val botRoots = BotWorkspaces.roots(ctx).path
        File(root, "BOT").mkdirs()
        File(root, "CONFIG").mkdirs()
        File(root, "PLUGINS").mkdirs()
        val marker = File(ptmp, "runtime.pid")
        marker.delete()
        pidFile = marker
        val cmd =
            listOf(
                "$lib/libproot.so",
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                root,
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
                "${ptmp.path}:/dev/shm",
                "-b",
                "$botRoots:/BOT",
                "-b",
                "$home:/root/projects",
                "-b",
                "${File(ctx.filesDir, "CONFIG").path}:/CONFIG",
                "-b",
                "${File(ctx.filesDir, "PLUGINS").path}:/PLUGINS",
                "-w",
                "/BOT/default",
                "/bin/bash",
                "/opt/pd/start-dsh.sh",
            )
        // PRoot 可能忽略 TERM；独立进程组让停止同时结束 tracer、Node 和其工具子进程。
        val launch =
            listOf(
                "/system/bin/setsid",
                "/system/bin/sh",
                "-c",
                "echo \$\$ > \"\$PD_RUNTIME_PID_FILE\"; echo PD_RUNTIME_PID=\$\$; exec \"\$@\"",
                "pd-runtime",
            ) + cmd
        val pb = ProcessBuilder(launch).redirectErrorStream(true).directory(File(home))
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("PROOT_TMP_DIR", ptmp.path)
            put("LD_LIBRARY_PATH", lib)
            put("HOME", "/root")
            put("PATH", "/usr/local/bin:/usr/bin:/bin")
            put("LANG", "C.UTF-8")
            put("ANDROID_ROOT", "/system")
            put("ANDROID_DATA", "/data")
            put("PD_DSH_PORT", Config.EMBEDDED_PORT.toString())
            put("PD_RUNTIME_PID_FILE", marker.path)
            put("PD_APP_SOCKET", appSocket)
            // Only the abstract socket name crosses the process boundary; no credential is exported.
            if (hostRuntimeSocket.isNotEmpty()) put("PD_HOST_RUNTIME_SOCKET", hostRuntimeSocket)
            cfg.deepseekKey.takeIf { it.isNotEmpty() }?.let { put("DEEPSEEK_API_KEY", it) }
        }
        log("$ start dsh (port ${Config.EMBEDDED_PORT})")
        if (!wanted || Thread.currentThread().isInterrupted) throw InterruptedException()
        val handshake = CountDownLatch(1)
        groupReady = handshake
        groupPid = 0
        val p = pb.start()
        proc = p
        log("runtime process $p")

        val ready = AtomicBoolean(false)
        val started = System.currentTimeMillis()
        val watchdog = Thread {
            // 首次冷启动：真机通常几十秒；TCG 模拟器 + arm64 翻译可能要数分钟，超时 10 分钟
            try {
                while (p.isAlive && !ready.get()) {
                    Thread.sleep(START_WATCHDOG_INTERVAL_MILLIS)
                    if (System.currentTimeMillis() - started > START_TIMEOUT_MILLIS) {
                        log("! start timeout")
                        terminate(p, groupPid)
                        break
                    }
                }
            } catch (_: InterruptedException) {
                /* 已退出或已就绪 */
            }
        }
            .apply {
                isDaemon = true
                start()
            }
        var firstOutput = true
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (firstOutput) {
                    firstOutput = false
                    check(line.startsWith("PD_RUNTIME_PID=")) { "运行时未报告进程组" }
                    groupPid = line.substringAfter('=').toInt()
                    check(groupPid > 1) { "无效的运行时进程组" }
                    handshake.countDown()
                    if (!wanted) terminate(p, groupPid)
                    return@forEachLine
                }
                log(line.replace(Regex("token=[^&\\s]+"), "token=***"))
                val m = Regex("dsh web: (http://127\\.0\\.0\\.1:\\d+/\\?token=[^\\s&]+)").find(line)
                if (m != null) {
                    // 自动登录：用本进程的一次性 launch token 换 30 天 Cookie（每次启动都换新，token 只在内存里）
                    try {
                        if (wanted) {
                            net.pair(m.groupValues[1])
                            ready.set(true)
                            set("ready", "Harness 已就绪", "")
                        }
                    } catch (e: Exception) {
                        set("error", "Harness 已启动但登录失败", e.message ?: "")
                    }
                }
            }
        } catch (e: java.io.IOException) {
            log("! stdout closed: ${e.javaClass.simpleName}")
        } finally {
            handshake.countDown()
        }
        val code =
            try {
                p.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
        if (!p.isAlive && proc === p) {
            val remaining = groupPid.takeIf { it > 1 } ?: recordedPid()
            if (remaining > 1) runCatching { Os.kill(-remaining, OsConstants.SIGKILL) }
            proc = null
            groupPid = 0
        }
        watchdog.interrupt()
        log("! dsh exited code=$code ready=${ready.get()}")
        if (wanted && !ready.get())
            set(
                "starting",
                "Harness 启动超时或意外退出，正在重试…",
                "exit $code\n" + synchronized(logs) { logs.takeLast(30).joinToString("\n") },
            )
        return ready.get()
    }

    private fun recordedPid(): Int = runCatching {
        pidFile?.readText()?.trim()?.toIntOrNull()?.takeIf { it > 1 } ?: 0
    }
        .getOrDefault(0)

    private fun terminate(p: Process, announcedPid: Int) {
        // stdout 尚未消费首行时，使用 host shell 在 exec 前写下的同一 PGID。
        val pid = announcedPid.takeIf { it > 1 } ?: recordedPid()
        if (!p.isAlive && pid == 0) return
        check(pid > 1) { "未取得运行时进程组，未启动第二个实例" }
        // 负 PID 只针对 setsid 创建的本次运行时组，不涉及其它 App 或旧版。
        runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
        if (!p.waitFor(TERMINATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Os.kill(-pid, OsConstants.SIGKILL)
            check(p.waitFor(TERMINATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "运行时未能停止"
            }
        }
    }

    private class Counting(i: InputStream, val cb: (Long) -> Unit) : FilterInputStream(i) {
        var n = 0L

        override fun read(): Int =
            `in`.read().also {
                if (it >= 0) {
                    n++
                    cb(n)
                }
            }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            `in`.read(b, off, len).also {
                if (it > 0) {
                    n += it
                    cb(n)
                }
            }
    }
}
