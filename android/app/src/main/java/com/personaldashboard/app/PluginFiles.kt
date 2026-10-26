package com.personaldashboard.app

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Host-owned opaque references. A plugin cannot turn an arbitrary URI/path into a file handle. */
class PluginFiles(private val context: Context, private val plugin: String) {
    private val root=File(context.filesDir,"plugin-files/${PluginContract.id(plugin)}").apply { mkdirs() }
    private val index=android.util.AtomicFile(File(root,"handles.json"))
    private val lock=locks.computeIfAbsent(root.absolutePath){Any()}
    private fun load(): JSONObject=if(index.baseFile.exists()||File(index.baseFile.path+".bak").exists()) {
        val bytes=index.openRead().use { input->val out=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=2*1024*1024){"文件句柄索引过大"};out.write(buffer,0,n)};out.toByteArray() };JSONObject(String(bytes,Charsets.UTF_8))
    } else JSONObject()
    private fun save(data: JSONObject) { val stream=index.startWrite();try{stream.write(data.toString().toByteArray());index.finishWrite(stream)}catch(e:Exception){index.failWrite(stream);throw e} }
    fun grant(uri: Uri, flags: Int, directory: Boolean=false, owned: Boolean=false, mime: String?=null): JSONObject=synchronized(grantsLock){synchronized(lock){
        require(uri.toString().length<=8192){"文件地址过长"}
        require(uri.scheme=="content"){"文件必须来自系统文件选择器"}
        val rows=load();require(rows.length()<128){"文件句柄过多，请先释放不用的句柄"}
        val access=flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val priorGrant=context.contentResolver.persistedUriPermissions.firstOrNull{it.uri==uri}
        val priorAccess=(if(priorGrant?.isReadPermission==true)Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if(priorGrant?.isWritePermission==true)Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        var persisted=false
        if(flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION!=0) persisted=runCatching { context.contentResolver.takePersistableUriPermission(uri,access);true }.getOrDefault(false)
        val id=UUID.randomUUID().toString();rows.put(id,JSONObject().put("uri",uri.toString()).put("directory",directory).put("writable",access and Intent.FLAG_GRANT_WRITE_URI_PERMISSION!=0).put("persisted",persisted).put("owned",owned).put("mime",mime))
        try{save(rows);stat(id).also{require(it.optLong("size",-1)<=MAX_BYTES){"文件超过 256 MiB"}}}
        catch(e:Exception){rows.remove(id);runCatching{save(rows)};val acquired=access and priorAccess.inv();if(persisted&&acquired!=0)runCatching{context.contentResolver.releasePersistableUriPermission(uri,acquired)};throw e}
    }}
    fun importFile(file: File, name: String, mime: String): JSONObject {
        require(file.length()<=MAX_BYTES){"文件超过 256 MiB"}
        val output=File(context.cacheDir,"plugin-export/$plugin").apply { mkdirs() }
        val dest=File(output,UUID.randomUUID().toString()+"-"+name.replace(Regex("[^\\p{L}\\p{N}_. -]"),"_").take(100))
        val perPlugin=output.walkTopDown().filter{it.isFile}.sumOf{it.length()}
        val total=File(context.cacheDir,"plugin-export").walkTopDown().filter{it.isFile}.sumOf{it.length()}
        require(perPlugin+file.length()<=MAX_BYTES&&total+file.length()<=1024L*1024*1024){"宿主文件缓存已满，请释放不用的文件"}
        file.copyTo(dest,overwrite=false)
        return try{grant(FileProvider.getUriForFile(context,context.packageName+".plugin-files",dest),Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,owned=true,mime=mime)}catch(e:Exception){dest.delete();throw e}
    }
    private fun row(id: String): JSONObject=synchronized(lock){load().optJSONObject(id)?.let { JSONObject(it.toString()) }?:error("文件句柄已失效")}
    internal fun uri(id: String): Uri=Uri.parse(row(id).getString("uri"))
    internal fun input(id: String)=context.contentResolver.openInputStream(uri(id))?:error("无法读取文件")
    internal fun output(id: String, truncate: Boolean=true): java.io.OutputStream {
        check(row(id).optBoolean("writable")){"文件没有写入授权"}
        return context.contentResolver.openOutputStream(uri(id),if(truncate)"wt" else "wa")?:error("无法写入文件")
    }
    fun stat(id: String): JSONObject {
        val row=row(id);val uri=Uri.parse(row.getString("uri"));var name="文件";var size=-1L
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c->if(c.moveToFirst()){c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf{it>=0}?.let{name=c.getString(it).orEmpty()};c.getColumnIndex(OpenableColumns.SIZE).takeIf{it>=0}?.let{if(!c.isNull(it))size=c.getLong(it)}} }
        if(size<0&&row.has("knownSize"))size=row.getLong("knownSize")
        return JSONObject().put("handle",id).put("name",name.take(180)).put("size",size).put("mime",row.optString("mime").takeIf{it.isNotBlank()}?:context.contentResolver.getType(uri)?:"application/octet-stream").put("directory",row.optBoolean("directory")).put("writable",row.optBoolean("writable")).put("persisted",row.optBoolean("persisted"))
    }
    fun call(a: JSONObject): JSONObject {
        val op=a.optString("op");val id=a.optString("handle")
        when(op){
            "stat" -> return stat(id)
            "list" -> {
                if(id.isBlank())return synchronized(lock){JSONObject().put("files",JSONArray(load().keys().asSequence().mapNotNull { runCatching{stat(it)}.getOrNull() }.toList()))}
                val row=row(id);check(row.optBoolean("directory")){"不是文件夹句柄"};val tree=Uri.parse(row.getString("uri"));val directoryId=a.optString("documentId",DocumentsContract.getTreeDocumentId(tree))
                require(directoryId==DocumentsContract.getTreeDocumentId(tree)||DocumentsContract.isChildDocument(context.contentResolver,DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree)),DocumentsContract.buildDocumentUriUsingTree(tree,directoryId))){"目录标识不属于授权范围"}
                val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,directoryId);val result=JSONArray()
                context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE),null,null,null)?.use { c->while(c.moveToNext()&&result.length()<128){val document=c.getString(0);result.put(JSONObject().put("documentId",document).put("name",c.getString(1)).put("mime",c.getString(2)).put("size",c.getLong(3)))}}
                return JSONObject().put("files",result)
            }
            "read" -> {
                val offset=a.optLong("offset",0);val count=a.optInt("length",CHUNK_BYTES);require(offset>=0&&offset<=MAX_BYTES&&count in 1..CHUNK_BYTES){"文件读取范围无效"}
                input(id).use { stream->var skipped=0L;while(skipped<offset){val n=stream.skip(offset-skipped);if(n<=0){if(stream.read()<0)break;skipped++}else skipped+=n};val b=ByteArray(count);var n=0;while(n<count){val k=stream.read(b,n,count-n);if(k<0)break;n+=k};return JSONObject().put("base64",Base64.encodeToString(b.copyOf(n),Base64.NO_WRAP)).put("offset",offset).put("bytes",n).put("eof",n<count) }
            }
            "write" -> synchronized(lock){
                val bytes=Base64.decode(a.getString("base64"),Base64.DEFAULT);require(bytes.size<=CHUNK_BYTES){"每次最多写入 64 KiB"};val append=a.optBoolean("append",false);val previous=if(append)stat(id).optLong("size",-1) else 0L
                require(previous>=0){"文件大小未知，请先截断写入或使用完整流传输"};require(previous+bytes.size<=MAX_BYTES){"文件超过 256 MiB"}
                output(id,!append).use { it.write(bytes) };synchronized(lock){val rows=load();rows.getJSONObject(id).put("knownSize",previous+bytes.size);save(rows)};return JSONObject().put("written",bytes.size)
            }
            "openChild" -> {
                val source=row(id);check(source.optBoolean("directory")){"不是文件夹句柄"};val tree=Uri.parse(source.getString("uri"));val child=DocumentsContract.buildDocumentUriUsingTree(tree,a.getString("documentId"));val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
                require(DocumentsContract.isChildDocument(context.contentResolver,parent,child)){"文件不属于授权文件夹"}
                return grant(child,Intent.FLAG_GRANT_READ_URI_PERMISSION or (if(source.optBoolean("writable"))Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
            }
            "remove" -> synchronized(grantsLock){synchronized(lock){check(row(id).optBoolean("writable")){"文件没有删除授权"};check(DocumentsContract.deleteDocument(context.contentResolver,uri(id))){"文件未删除"};release(id);return JSONObject().put("removed",true)}}
            "release" -> {release(id);return JSONObject().put("released",true)}
            "thumbnail","video" -> {
                val retriever=MediaMetadataRetriever()
                try {
                    val file=uri(id);val mime=stat(id).optString("mime")
                    if(op=="video"||mime.startsWith("video/")){
                        retriever.setDataSource(context,file)
                        if(op=="video")return JSONObject().put("durationMs",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:-1).put("width",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:0).put("height",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:0).put("rotation",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?:0)
                    }
                    val bitmap=if(mime.startsWith("video/")){
                        val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf{it>0}?:error("视频尺寸不可用")
                        val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf{it>0}?:error("视频尺寸不可用")
                        val scale=minOf(1.0,320.0/width,320.0/height)
                        retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,maxOf(1,(width*scale).toInt()),maxOf(1,(height*scale).toInt()))
                    } else {
                        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};input(id).use{BitmapFactory.decodeStream(it,null,bounds)};require(bounds.outWidth>0&&bounds.outHeight>0){"不是可识别图片"};val options=BitmapFactory.Options().apply{inSampleSize=maxOf(1,maxOf(bounds.outWidth,bounds.outHeight)/320)};input(id).use{BitmapFactory.decodeStream(it,null,options)}
                    }?:error("无法生成缩略图")
                    val out=ByteArrayOutputStream();try{bitmap.compress(Bitmap.CompressFormat.JPEG,80,out)}finally{bitmap.recycle()};require(out.size()<=256*1024){"缩略图过大"};return JSONObject().put("mime","image/jpeg").put("base64",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP))
                } finally {retriever.release()}
            }
            else -> error("不支持的文件操作")
        }
    }
    internal fun release(id: String)=synchronized(grantsLock){synchronized(lock){
        val rows=load();val old=rows.optJSONObject(id);rows.remove(id);save(rows)
        if(old!=null){
            val value=old.getString("uri")
            if(old.optBoolean("owned"))runCatching{context.contentResolver.delete(Uri.parse(value),null,null)}
            if(old.optBoolean("persisted")){
                val other=runCatching{File(context.filesDir,"plugin-files").listFiles().orEmpty().any{dir->
                    val source=File(dir,"handles.json");if(!source.isFile)File(source.path+".bak").exists() else{check(source.length()<=2*1024*1024);val entries=JSONObject(source.readText());entries.keys().asSequence().any{entries.getJSONObject(it).optString("uri")==value}}
                }}.getOrDefault(true)
                if(!other)runCatching{context.contentResolver.releasePersistableUriPermission(Uri.parse(value),Intent.FLAG_GRANT_READ_URI_PERMISSION or (if(old.optBoolean("writable"))Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))}
            }
        }
    }}
    companion object {
        private val grantsLock=Any()
        const val MAX_BYTES=256L*1024*1024;const val CHUNK_BYTES=65536
        private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>()
        fun removePlugin(context: Context,id: String){
            val files=PluginFiles(context,id);val handles=synchronized(files.lock){files.load().keys().asSequence().toList()};handles.forEach{files.release(it)}
            for(root in listOf(File(context.filesDir,"plugin-files/${PluginContract.id(id)}"),File(context.cacheDir,"plugin-export/$id"))){check(!root.exists()||root.deleteRecursively()){ "插件文件清理未完成" }}
        }
    }
}
