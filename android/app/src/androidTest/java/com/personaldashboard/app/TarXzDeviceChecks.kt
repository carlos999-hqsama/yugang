package com.personaldashboard.app

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.system.Os
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

/** Synthetic damaged archives only; never operates on the installed runtime. */
internal class TarXzDeviceChecks(private val runner: Instrumentation) {
    private data class Entry(val name: String, val type: Char='0', val data: ByteArray=byteArrayOf(), val link: String="", val size: ByteArray?=null)
    private fun archive(vararg entries: Entry): ByteArray {
        val tar=ByteArrayOutputStream()
        for(e in entries) {
            val h=ByteArray(512)
            fun text(offset:Int,value:String) {value.toByteArray().copyInto(h,offset)}
            text(0,e.name);text(100,"0000755");text(124,e.data.size.toString(8).padStart(11,'0'))
            h[156]=e.type.code.toByte();text(157,e.link);text(257,"ustar")
            e.size?.copyInto(h,124)
            for(i in 148..155)h[i]=32
            text(148,h.sumOf{it.toInt() and 255}.toString(8).padStart(6,'0')+"\u0000 ")
            tar.write(h);tar.write(e.data);tar.write(ByteArray((512-e.data.size%512)%512))
        }
        tar.write(ByteArray(1024));val out=ByteArrayOutputStream()
        XZOutputStream(out,LZMA2Options()).use{it.write(tar.toByteArray())};return out.toByteArray()
    }
    fun runtime() {
        check(Build.MODEL.contains("sdk")||Build.FINGERPRINT.contains("emulator")) {"仅允许专用模拟器"}
        val context=runner.targetContext
        val dest=File(context.cacheDir,"full-runtime-extract-"+java.util.UUID.randomUUID())
        try {
            val manifest=org.json.JSONObject(context.assets.open("runtime/runtime.json").bufferedReader().use{it.readText()})
            check(context.cacheDir.usableSpace>manifest.getLong("bytes")+64L*1024*1024) {"Insufficient scratch space"}
            context.assets.open("runtime/runtime.tar.xz").use {TarXz.extract(it,dest)}
            check(File(dest,"usr/local/bin/node").isFile&&File(dest,"opt/pd/plugin-runner.mjs").isFile)
            check(org.json.JSONObject(File(dest,"usr/local/lib/node_modules/npm/node_modules/ip-address/package.json").readText()).getString("version")=="10.7.4")
            runner.sendStatus(0,Bundle().apply{putString("stream","PASS: Actual APK runtime archive extracts with native tar hardening and retains pinned npm dependencies\n")})
        } finally { Runtime.deleteTree(dest) }
    }
    fun run() {
        check(Build.MODEL.contains("sdk")||Build.FINGERPRINT.contains("emulator")) {"仅允许专用模拟器"}
        val base=File(runner.targetContext.cacheDir,"tar-check-"+java.util.UUID.randomUUID()).apply{mkdirs()}
        val dest=File(base,"rootfs.new");val outside=File(base,"rootfs.new-x").apply{mkdirs()}
        fun extract(vararg e:Entry) {TarXz.extract(ByteArrayInputStream(archive(*e)),dest)}
        fun reset() {dest.deleteRecursively();dest.mkdirs()}
        fun rejects(label:String,vararg e:Entry) {reset();check(runCatching{extract(*e)}.isFailure) {"Archive accepted: $label"};check(outside.listFiles().orEmpty().all{it.name=="sentinel"}) {"Archive wrote outside: $label"}}
        try {
            val long="dir/"+"n".repeat(240)
            extract(Entry("dir",'5'),Entry("dir/file",data="OK".toByteArray()),Entry("copy",'1',link="dir/file"),Entry("././@LongLink",'L',(long+"\u0000").toByteArray()),Entry("ignored",data="LONG".toByteArray()),Entry("././@LongLink",'K',"/usr/bin/node\u0000".toByteArray()),Entry("absolute",'2'))
            check(File(dest,"copy").readText()=="OK"&&File(dest,long).readText()=="LONG")
            check(Os.readlink(File(dest,"absolute").path)=="/usr/bin/node")
            val target=File(outside,"sentinel").apply{writeText("UNCHANGED")};Os.chmod(outside.path,448);Os.chmod(target.path,384)
            val link=Entry("escape",'2',link=outside.path)
            rejects("directory traversal",link,Entry("escape/nested",'5'))
            rejects("file traversal",link,Entry("escape/file",data="BAD".toByteArray()))
            rejects("hardlink destination",Entry("safe",data="SAFE".toByteArray()),link,Entry("escape/copy",'1',link="safe"))
            rejects("hardlink source",link,Entry("copy",'1',link="escape/sentinel"))
            reset()
            // Rejecting this archive or safely skipping the symlink mode are both valid.
            runCatching {extract(Entry("a/.",'5'),Entry("a",'2',link=outside.path))}
            check(Os.stat(outside.path).st_mode and 511==448&&Os.stat(target.path).st_mode and 511==384&&target.readText()=="UNCHANGED")
            rejects("oversized GNU record",Entry("long",'L',size="00010000001\u0000".toByteArray()))
            rejects("negative GNU record",Entry("long",'K',size=ByteArray(12){255.toByte()}))
            rejects("overflow GNU record",Entry("long",'L',size=ByteArray(12).apply{this[0]=128.toByte();this[1]=1}))
            runner.sendStatus(0,Bundle().apply{putString("stream","PASS: Valid tar/GNU links survive; damaged headers and symlink traversal cannot write or chmod a sibling directory\n")})
        } finally {base.deleteRecursively()}
    }
}
