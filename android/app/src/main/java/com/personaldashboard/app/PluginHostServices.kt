package com.personaldashboard.app

import android.content.Context
import org.json.JSONObject

/** Common capability entry for page calls and Agent action SDK calls. */
object PluginHostServices {
    fun requireCurrent(context:Context,id:String,revision:String,capability:String) {
        val store=PluginPlatform.store(context);store.requireRevision(id,revision)
        require(capability in PluginContract.caps(store.activeBundle(id))){"插件未声明此能力"}
    }
    fun discovery(context:Context,id:String,revision:String,page:Boolean):JSONObject {
        val store=PluginPlatform.store(context);store.requireRevision(id,revision)
        return PluginCapabilities.describe(PluginContract.caps(store.activeBundle(id)),page)
            .put("diagnostics",PluginCallAdmission.diagnostics()).put("storage",store.recoveryStatus()).put("backgroundStorage",PluginBackgroundStore(context).recoveryStatus())
    }
    fun call(context:Context,id:String,revision:String,capability:String,args:JSONObject,cancellation:PluginCancellation,page:Boolean=false,userInitiated:Boolean=false):JSONObject {
        require(args.toString().toByteArray().size<=PluginCapabilities.MAX_ARGUMENT_BYTES){"通用能力参数过大"}
        requireCurrent(context,id,revision,capability);cancellation.check();PluginCapabilities.operation(capability,args.getString("op"),page)
        val result=when(capability){
            "plugin.files"->PluginPlatform.withActiveRevision(context,id,revision){cancellation.check();PluginFiles(context,id).call(args)}
            "plugin.transfer"->PluginTransfers.call(context,id,revision,args,cancellation,userInitiated)
            "plugin.notifications.read"->PluginPlatform.withActiveRevision(context,id,revision){cancellation.check();PluginNotificationInbox.call(context,id,revision,args)}
            "plugin.share"->PluginPlatform.withActiveRevision(context,id,revision){cancellation.check();PluginShareInbox.call(context,id,args)}
            "plugin.connection"->PluginConnections.call(context,id,revision,args,cancellation)
            "plugin.system"->when(args.getString("op")){"devices"->JSONObject().put("devices",PluginDeviceServices.devices(context,id));"unpair"->{PluginDeviceServices.unpair(context,id,args);JSONObject().put("removed",true)};"connectionStatus","services","readCharacteristic","writeCharacteristic","disconnect"->PluginBluetooth.call(context,id,revision,args);else->error("此操作需要前台插件页面")}
            else->error("此操作需要前台插件页面")
        }
        cancellation.check();requireCurrent(context,id,revision,capability);return result
    }
    fun release(context:Context,id:String,removed:Boolean=false) {
        PluginNotificationInbox.release(id)
        PluginBluetooth.release(id)
        PluginTransfers.cancelPlugin(context,id,removed)
        if(removed){PluginDeviceServices.removePlugin(context,id);PluginFiles.removePlugin(context,id);PluginConnections.removePlugin(context,id);PluginShareInbox.clear(id)}
    }
}
