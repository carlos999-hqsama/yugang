package com.personaldashboard.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Small host-private records; ciphertext and atomic backup remain inside app private storage. */
internal class PluginEncryptedStore(context: Context, directory: String, private val id: String) {
    private val root=File(context.applicationContext.filesDir,directory).apply { mkdirs() }
    private val file=AtomicFile(File(root,PluginContract.id(id)+".bin"))
    private val cipher=AndroidKeystorePluginApiSessionCipher()
    private val lock=locks.computeIfAbsent(file.baseFile.absolutePath){Any()}
    fun read(): JSONObject=synchronized(lock){
        if(!file.baseFile.exists()&&!java.io.File(file.baseFile.path+".bak").exists())JSONObject() else {
            val bytes=file.openRead().use { input -> val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=2*1024*1024){"宿主私有记录过大"};out.write(buffer,0,n)};out.toByteArray() }
            JSONObject(String(cipher.open(bytes),Charsets.UTF_8))
        }
    }
    fun update(change:(JSONObject)->Unit): JSONObject=synchronized(lock){
        val data=read();val before=data.toString();change(data);val serialized=data.toString();if(before==serialized)return@synchronized data;val bytes=serialized.toByteArray();require(bytes.size<=1024*1024){"宿主私有记录过大"};val stream=file.startWrite()
        try {stream.write(cipher.seal(bytes));file.finishWrite(stream)}catch(e:Exception){file.failWrite(stream);throw e};data
    }
    fun remove()=synchronized(lock){file.delete();check(listOf(file.baseFile,File(file.baseFile.path+".bak"),File(file.baseFile.path+".new")).none{it.exists()}){"宿主私有记录清理未完成"}}
    companion object { private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>() }
}
