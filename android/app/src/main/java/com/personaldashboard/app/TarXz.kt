package com.personaldashboard.app

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.tukaani.xz.XZInputStream

/**
 * 最小 tar 解包器（ustar + GNU 长文件名 'L'/'K'），用于解包内置运行时。 处理目录 / 普通文件 / 符号链接 / 硬链接（硬链接改为复制，Android
 * 应用分区常常不允许 link）。 条目路径去掉前导 / 后限制在 dest 内（含 .. 段则跳过）；符号链接目标文本按原样保留供 PRoot 解释，但后续条目不得沿它写出 dest。
 */
object TarXz {
    // GNU 长名/长链接记录上限，避免损坏头触发超大内存分配。
    private const val MAX_LONG_NAME_BYTES = 1 shl 20

    fun extract(xz: InputStream, dest: File) {
        dest.mkdirs()
        val base = dest.canonicalPath
        val inp = XZInputStream(xz, 256 * 1024)
        val hdr = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        val dirModes = ArrayList<Pair<String, Int>>()
        val buf = ByteArray(1 shl 16)
        while (true) {
            if (!readFully(inp, hdr)) break
            if (hdr.all { it.toInt() == 0 }) break
            val type = hdr[156].toInt().toChar()
            val size = num(hdr, 124, 12)
            if (size < 0) throw IOException("negative entry size")
            var name =
                longName
                    ?: run {
                        val n = str(hdr, 0, 100)
                        val prefix =
                            if (str(hdr, 257, 6).startsWith("ustar")) str(hdr, 345, 155) else ""
                        if (prefix.isNotEmpty()) "$prefix/$n" else n
                    }
            val link = longLink ?: str(hdr, 157, 100)
            if (type == 'L' || type == 'K') {
                if (size > Int.MAX_VALUE.toLong() || size > MAX_LONG_NAME_BYTES)
                    throw IOException("GNU long name too large: $size")
                val data = ByteArray(size.toInt())
                if (!readFully(inp, data)) throw IOException("truncated GNU long name")
                skip(inp, pad(size))
                val s = String(data, Charsets.UTF_8).trimEnd('\u0000')
                if (type == 'L') longName = s else longLink = s
                continue
            }
            longName = null
            longLink = null
            name = name.trimStart('/').removePrefix("./")
            val mode = num(hdr, 100, 8).toInt() and "7777".toInt(8)
            if (name.isEmpty() || name.split('/').any { it == ".." }) {
                skipEntry(inp, size)
                continue
            }
            val out = File(dest, name)
            when (type) {
                '5' -> {
                    requireWithin(base, out, "path escapes")
                    if (!out.mkdirs() && !out.isDirectory)
                        throw IOException("cannot create directory: $name")
                    dirModes += out.path to mode
                }
                '2' -> {
                    prepareParent(base, out)
                    removeExisting(out.path)
                    Os.symlink(link, out.path)
                }
                '1' -> {
                    val src = File(dest, link.trimStart('/').removePrefix("./"))
                    requireWithin(base, src, "hardlink escapes")
                    prepareParent(base, out)
                    removeExisting(out.path)
                    src.copyTo(out, overwrite = true)
                    chmodNoFollow(out.path, Os.stat(src.path).st_mode and "7777".toInt(8))
                }
                '0',
                '\u0000',
                '7' -> {
                    prepareParent(base, out)
                    removeExisting(out.path)
                    out.outputStream().use { o ->
                        var left = size
                        while (left > 0) {
                            val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (r < 0) throw IOException("truncated")
                            o.write(buf, 0, r)
                            left -= r
                        }
                    }
                    skip(inp, pad(size))
                    chmodNoFollow(out.path, (mode and "777".toInt(8)) or "600".toInt(8))
                    continue
                }
                else -> {} // 设备节点 / FIFO 等：跳过（PRoot 绑定宿主 /dev）
            }
            skipEntry(inp, size)
        }
        // 目录权限最后再设（避免先设成只读导致写不进子文件）
        for ((p, m) in dirModes.asReversed()) {
            requireWithin(base, File(p), "directory mode path escapes")
            chmodNoFollow(p, (m and "777".toInt(8)) or "700".toInt(8))
        }
    }

    private fun isWithin(base: String, path: String): Boolean {
        if (path == base) return true
        val prefix = if (base.endsWith(File.separator)) base else base + File.separator
        return path.startsWith(prefix)
    }

    private fun requireWithin(base: String, path: File, message: String) {
        if (!isWithin(base, path.canonicalPath)) throw IOException("$message: ${path.path}")
    }

    private fun prepareParent(base: String, out: File) {
        val parent = out.parentFile ?: throw IOException("path escapes: ${out.path}")
        if (!isWithin(base, parent.canonicalPath)) throw IOException("path escapes: ${out.path}")
        if (!parent.mkdirs() && !parent.isDirectory)
            throw IOException("cannot create parent: ${parent.path}")
    }

    private fun removeExisting(path: String) {
        if (runCatching { Os.remove(path) }.isSuccess) return
        if (runCatching { Os.lstat(path) }.isSuccess) throw IOException("cannot replace: $path")
    }

    private fun chmodNoFollow(path: String, mode: Int) {
        val st = runCatching { Os.lstat(path) }.getOrNull() ?: return
        if (!OsConstants.S_ISLNK(st.st_mode)) runCatching { Os.chmod(path, mode) }
    }

    private fun skipEntry(i: InputStream, size: Long) {
        skip(i, size)
        skip(i, pad(size))
    }

    private fun pad(n: Long) = (512 - n % 512) % 512

    private fun readFully(i: InputStream, b: ByteArray): Boolean {
        var o = 0
        while (o < b.size) {
            val r = i.read(b, o, b.size - o)
            if (r < 0) {
                if (o == 0) return false
                throw IOException("truncated header")
            }
            o += r
        }
        return true
    }

    private fun skip(i: InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) {
            val r = i.read(tmp, 0, minOf(8192L, left).toInt())
            if (r < 0) throw IOException("truncated")
            left -= r
        }
    }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var e = off
        while (e < off + len && b[e].toInt() != 0) e++
        return String(b, off, e - off, Charsets.UTF_8)
    }

    private fun num(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {
            if (b[off].toInt() and 0x40 != 0) throw IOException("negative tar number")
            var v = (b[off].toInt() and 0x3f).toLong()
            for (k in off + 1 until off + len) {
                if (v > Long.MAX_VALUE ushr 8) throw IOException("tar number overflow")
                v = (v shl 8) or (b[k].toLong() and 0xff)
            }
            return v
        } // GNU base-256
        val s = str(b, off, len).trim().trim('\u0000')
        return if (s.isEmpty()) 0 else s.toLong(8)
    }
}
