package com.personaldashboard.app

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** GATT only for devices explicitly associated through this plugin's system pairing flow. */
@Suppress("MissingPermission","DEPRECATION")
object PluginBluetooth {
    private class Connection(val plugin:String,val revision:String){
        var gatt:BluetoothGatt?=null;@Volatile var state="connecting";var operation="";var result=JSONObject();var touched=android.os.SystemClock.elapsedRealtime()
    }
    private val connections=ConcurrentHashMap<String,Connection>()
    private val main=Handler(Looper.getMainLooper())
    fun call(context:Context,plugin:String,revision:String,a:JSONObject):JSONObject {
        connections.entries.removeAll{(_,c)->synchronized(c){c.state=="closed"&&android.os.SystemClock.elapsedRealtime()-c.touched>600000}}
        val op=a.getString("op")
        if(op=="connect"){
            val device=a.getString("device");val owned=PluginDeviceServices.devices(context,plugin);require((0 until owned.length()).any{owned.getString(it)==device}){"设备未通过当前插件配对"}
            synchronized(connections){require(connections.values.count{it.state!="closed"}<4&&connections.size<32){"蓝牙连接过多"}}
            val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter?:error("设备不支持蓝牙");check(adapter.isEnabled){"请先开启系统蓝牙"}
            val id=UUID.randomUUID().toString();val c=Connection(plugin,revision);connections[id]=c
            val callback=object:BluetoothGattCallback(){
                override fun onConnectionStateChange(gatt:BluetoothGatt,status:Int,newState:Int){synchronized(c){
                    if(c.state=="closed"){gatt.close();return}
                    if(status==BluetoothGatt.GATT_SUCCESS&&newState==BluetoothProfile.STATE_CONNECTED){c.state="discovering";if(!gatt.discoverServices())close(c)}else if(newState==BluetoothProfile.STATE_DISCONNECTED||status!=BluetoothGatt.GATT_SUCCESS){c.result=JSONObject().put("status",status);close(c)}
                }}
                override fun onServicesDiscovered(gatt:BluetoothGatt,status:Int){synchronized(c){if(c.state=="closed")return;c.result=JSONObject().put("status",status);if(status==BluetoothGatt.GATT_SUCCESS)c.state="ready" else close(c)}}
                override fun onCharacteristicRead(gatt:BluetoothGatt,ch:BluetoothGattCharacteristic,status:Int){synchronized(c){if(c.state=="closed"||c.operation.isBlank())return;c.result=JSONObject().put("operationId",c.operation).put("status",status).put("ok",status==BluetoothGatt.GATT_SUCCESS);if(status==BluetoothGatt.GATT_SUCCESS)c.result.put("base64",Base64.encodeToString((ch.value?:ByteArray(0)).take(512).toByteArray(),Base64.NO_WRAP));c.operation=""}}
                override fun onCharacteristicWrite(gatt:BluetoothGatt,ch:BluetoothGattCharacteristic,status:Int){synchronized(c){if(c.state=="closed"||c.operation.isBlank())return;c.result=JSONObject().put("operationId",c.operation).put("status",status).put("ok",status==BluetoothGatt.GATT_SUCCESS);c.operation=""}}
            }
            try{c.gatt=adapter.getRemoteDevice(device).connectGatt(context.applicationContext,false,callback,android.bluetooth.BluetoothDevice.TRANSPORT_LE)}catch(e:Exception){connections.remove(id);throw e}
            main.postDelayed({synchronized(c){if(c.state in setOf("connecting","discovering")){c.result=JSONObject().put("reason","timeout");close(c)}}},20000)
            return JSONObject().put("connectionId",id).put("state",c.state)
        }
        val c=connections[a.getString("connectionId")]?:error("蓝牙连接已释放")
        check(c.plugin==plugin&&c.revision==revision){"蓝牙连接不属于当前插件版本"}
        synchronized(c){c.touched=android.os.SystemClock.elapsedRealtime()
            if(op=="disconnect"){close(c);return JSONObject().put("closed",true)}
            if(op=="connectionStatus")return JSONObject().put("state",c.state).put("busy",c.operation.isNotBlank()).put("lastResult",JSONObject(c.result.toString()))
            check(c.state=="ready"){"蓝牙服务尚未就绪"};val g=c.gatt?:error("蓝牙连接已释放")
            if(op=="services")return JSONObject().put("services",JSONArray(g.services.map{s->JSONObject().put("id",s.uuid.toString()).put("characteristics",JSONArray(s.characteristics.map{ch->JSONObject().put("id",ch.uuid.toString()).put("properties",ch.properties)}))}))
            check(c.operation.isBlank()){"蓝牙操作繁忙"}
            val ch=g.getService(UUID.fromString(a.getString("service")))?.getCharacteristic(UUID.fromString(a.getString("characteristic")))?:error("蓝牙特征不存在")
            val operation=UUID.randomUUID().toString();c.operation=operation
            val accepted=try { when(op){
                "readCharacteristic"->g.readCharacteristic(ch)
                "writeCharacteristic"->{val bytes=Base64.decode(a.getString("base64"),Base64.DEFAULT);require(bytes.size<=512){"蓝牙写入最多 512 字节"};ch.value=bytes;ch.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;g.writeCharacteristic(ch)}
                else->error("不支持的蓝牙操作")
            }}catch(e:Exception){c.operation="";throw e}
            if(!accepted){c.operation="";error("系统未接受蓝牙操作")}
            main.postDelayed({synchronized(c){if(c.operation==operation){c.operation="";c.result=JSONObject().put("operationId",operation).put("ok",false).put("reason","timeout");close(c)}}},15000)
            return JSONObject().put("accepted",true).put("operationId",operation)
        }
    }
    private fun close(c:Connection){c.state="closed";c.operation="";runCatching{c.gatt?.disconnect()};runCatching{c.gatt?.close()};c.gatt=null;c.touched=android.os.SystemClock.elapsedRealtime()}
    fun release(plugin:String){connections.values.filter{it.plugin==plugin}.forEach{synchronized(it){close(it)}}}
}
