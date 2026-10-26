package com.personaldashboard.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** One real Android interaction at a time. Results retain the requesting plugin/revision. */
class PluginHostUi(private val activity: MainActivity) {
    private var pending: ((Int,Intent?)->Unit)?=null
    private var pendingPermission: ((Boolean)->Unit)?=null
    private var busy=false
    private var failPending:(()->Unit)?=null
    private var cleanupPending:(()->Unit)?=null
    private val launcher=activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->val cb=pending;pending=null;runCatching{cb?.invoke(r.resultCode,r.data)}.onFailure{failPending?.invoke()}}
    private val pairLauncher=activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){r->val cb=pending;pending=null;runCatching{cb?.invoke(r.resultCode,r.data)}.onFailure{failPending?.invoke()}}
    private val permissions=activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){r->val cb=pendingPermission;pendingPermission=null;runCatching{cb?.invoke(r.values.any{it})}.onFailure{failPending?.invoke()}}
    fun execute(plugin: String, revision: String, capability: String, args: JSONObject, token: PluginCancellation, reply:(JSONObject)->Unit) {
        check(!busy&&pending==null&&pendingPermission==null){"系统选择器已打开，请完成当前操作"}
        busy=true
        val done=AtomicBoolean(false)
        var detach: (()->Unit)?=null
        fun finish(value: JSONObject) {
            if(!done.compareAndSet(false,true))return
            detach?.invoke();busy=false;failPending=null;cleanupPending?.invoke();cleanupPending=null
            try { token.check();PluginHostServices.requireCurrent(activity,plugin,revision,capability);reply(value) }
            catch(_:Exception){
                if(capability=="plugin.files"||(capability=="plugin.system"&&args.optString("op")=="camera")){
                    val issued=mutableListOf<String>();value.optString("handle").takeIf{it.isNotBlank()}?.let{issued+=it};value.optJSONArray("files")?.let{a->for(i in 0 until a.length())a.optJSONObject(i)?.optString("handle")?.let{issued+=it}}
                    issued.forEach{runCatching{PluginFiles(activity,plugin).release(it)}}
                }
                reply(JSONObject().put("cancelled",true).put("reason","owner_released"))
            }
        }
        failPending={finish(JSONObject().put("error","系统交互未完成，请重试").put("code","system_error"))}
        detach=token.onCancel { activity.runOnUiThread{if(done.compareAndSet(false,true)){busy=false;failPending=null;cleanupPending?.invoke();cleanupPending=null;pending=null;pendingPermission=null;reply(JSONObject().put("cancelled",true).put("reason","owner_released"))}} }
        if(token.cancelled||done.get())return
        val files=PluginFiles(activity,plugin);val op=args.getString("op")
        fun launch(intent: Intent, result:(Intent?)->JSONObject) {
            token.check();PluginHostServices.requireCurrent(activity,plugin,revision,capability)
            pending={code,data->try{token.check();PluginHostServices.requireCurrent(activity,plugin,revision,capability);finish(if(code==Activity.RESULT_OK)PluginPlatform.withActiveRevision(activity,plugin,revision){token.check();result(data)} else JSONObject().put("cancelled",true))}catch(_:Exception){finish(JSONObject().put("error","系统返回的内容不可用").put("code","invalid_result"))}}
            try{launcher.launch(intent)}catch(e:Exception){pending=null;detach?.invoke();throw e}
        }
        try { when(capability){
            "plugin.files" -> when(op){
                "pick" -> {
                    val mime=args.optString("mime","*/*");require(mime.length<=100&&mime.contains('/')){"文件类型无效"}
                    val request=Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,args.optBoolean("multiple",true))
                    args.optJSONArray("mimeTypes")?.let{a->require(a.length()<=16);request.putExtra(Intent.EXTRA_MIME_TYPES,Array(a.length()){a.getString(it)})}
                    launch(request){data->val uris=data?.clipData?.let{clip->(0 until clip.itemCount).map{clip.getItemAt(it).uri}}?:listOfNotNull(data?.data);require(uris.size<=6){"每次最多选择 6 个文件"};val received=JSONArray();try{uris.forEach{received.put(files.grant(it,data?.flags?:Intent.FLAG_GRANT_READ_URI_PERMISSION))};JSONObject().put("files",received)}catch(e:Exception){for(i in 0 until received.length())runCatching{files.release(received.getJSONObject(i).getString("handle"))};throw e}}
                }
                "create" -> launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType(args.optString("mime","application/octet-stream")).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,args.optString("name","文档").take(180)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)){data->files.grant(data?.data?:error("没有文件"),data.flags)}
                "directory" -> launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)){data->files.grant(data?.data?:error("没有文件夹"),data.flags,directory=true)}
                else -> error("不支持此系统文件操作")
            }
            "plugin.notifications.read" -> {PluginNotificationInbox.settings(activity);finish(JSONObject().put("opened",true).put("reason","system_authorization_required"))}
            "plugin.share" -> {
                val handles=args.optJSONArray("handles")?:JSONArray();require(handles.length()<=6){"每次最多分享 6 个文件"}
                val uris=(0 until handles.length()).map{files.uri(handles.getString(it))}
                val request=Intent(if(uris.size>1)Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).setType(args.optString("mime",if(uris.isEmpty())"text/plain" else "*/*"))
                if(args.has("text")){val text=args.getString("text");require(text.length<=16000);request.putExtra(Intent.EXTRA_TEXT,text)}
                require(uris.isNotEmpty()||args.optString("text").isNotBlank()){"没有可分享的内容"}
                if(uris.size==1)request.putExtra(Intent.EXTRA_STREAM,uris.first()) else if(uris.size>1)request.putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(uris))
                if(uris.isNotEmpty()){request.clipData=ClipData.newUri(activity.contentResolver,"文件",uris.first()).apply{uris.drop(1).forEach{addItem(ClipData.Item(it))}};request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                activity.startActivity(Intent.createChooser(request,"分享"));finish(JSONObject().put("handedOff",true).put("delivered",false))
            }
            "plugin.connection" -> when(op){
                "configure" -> PluginConnections.configure(activity,plugin,revision,args,token){finish(it)}
                "authorize" -> {val result=PluginConnections.authorize(activity,plugin,revision,args);finish(result)}
            }
            "plugin.system" -> when(op){
                "camera" -> {
                    val dir=File(activity.cacheDir,"plugin-export/$plugin").apply{mkdirs()};val output=File(dir,UUID.randomUUID().toString()+".jpg");val uri=FileProvider.getUriForFile(activity,activity.packageName+".plugin-files",output);cleanupPending={output.delete()}
                    launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)){files.grant(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,owned=true).also{cleanupPending=null}}
                }
                "calendar" -> {
                    val title=args.getString("title").take(200);val begin=args.getLong("beginAt");val end=args.getLong("endAt");require(end>begin){"日程时间无效"}
                    activity.startActivity(Intent(Intent.ACTION_INSERT,CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE,title).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME,end).putExtra(CalendarContract.Events.DESCRIPTION,args.optString("description").take(4000)))
                    finish(JSONObject().put("opened",true).put("saved",false))
                }
                "contact" -> launch(Intent(Intent.ACTION_PICK,ContactsContract.CommonDataKinds.Phone.CONTENT_URI)){data->val result=JSONObject();activity.contentResolver.query(data?.data?:error("没有联系人"),arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER),null,null,null)?.use{c->if(c.moveToFirst()){result.put("name",c.getString(0).orEmpty().take(200));result.put("phone",c.getString(1).orEmpty().take(100))}};result}
                "location" -> {
                    fun locate(){PluginDeviceServices.location(activity,token){finish(it)}}
                    if(ContextCompat.checkSelfPermission(activity,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)locate()
                    else {pendingPermission={allowed->if(allowed)locate() else finish(JSONObject().put("cancelled",true).put("reason","permission_denied"))};permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))}
                }
                "pair" -> PluginDeviceServices.pair(activity,plugin,args,{sender,before->pending={code,_->token.check();PluginHostServices.requireCurrent(activity,plugin,revision,capability);finish(JSONObject().put("selected",code==Activity.RESULT_OK).put("devices",if(code==Activity.RESULT_OK)PluginPlatform.withActiveRevision(activity,plugin,revision){token.check();PluginDeviceServices.remember(activity,plugin,before)} else JSONArray()))};pairLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())},{message->finish(JSONObject().put("error",message).put("code","pairing_failed"))})
                "connect" -> {
                    fun connect(){finish(PluginBluetooth.call(activity,plugin,revision,args))}
                    if(android.os.Build.VERSION.SDK_INT<31||ContextCompat.checkSelfPermission(activity,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)connect()
                    else{pendingPermission={allowed->if(allowed)connect() else finish(JSONObject().put("cancelled",true).put("reason","permission_denied"))};permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))}
                }
                "unpair" -> {PluginDeviceServices.unpair(activity,plugin,args);finish(JSONObject().put("removed",true))}
                else -> error("不支持的系统交互")
            }
            "plugin.browser" -> PluginSiteBrowserHost.snapshot(activity,plugin,revision){finish(it)}
            else -> error("不支持此系统交互")
        }}catch(_:Exception){failPending?.invoke()}
    }
    fun close(){runCatching{pending?.invoke(Activity.RESULT_CANCELED,null)};pending=null;runCatching{pendingPermission?.invoke(false)};pendingPermission=null;failPending?.invoke()}
}
