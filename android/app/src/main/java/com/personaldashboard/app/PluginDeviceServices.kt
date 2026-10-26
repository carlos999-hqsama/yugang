package com.personaldashboard.app

import android.Manifest
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

object PluginDeviceServices {
    @Suppress("DEPRECATION")
    fun devices(context: Context,plugin:String): JSONArray {
        val owned=PluginEncryptedStore(context,"plugin-devices",plugin).read().optJSONArray("devices")?:JSONArray()
        val actual=context.getSystemService(CompanionDeviceManager::class.java)?.associations.orEmpty().toSet()
        return JSONArray((0 until owned.length()).map{owned.getString(it)}.filter{it in actual})
    }
    @Suppress("DEPRECATION")
    fun remember(context:Context,plugin:String,before:Set<String>):JSONArray {
        val current=context.getSystemService(CompanionDeviceManager::class.java)?.associations.orEmpty()
        val added=current.filter{it !in before}
        PluginEncryptedStore(context,"plugin-devices",plugin).update{data->val old=data.optJSONArray("devices")?:JSONArray();data.put("devices",JSONArray(((0 until old.length()).map{old.getString(it)}+added).distinct()))}
        return devices(context,plugin)
    }
    @Suppress("DEPRECATION")
    fun unpair(context: Context,plugin:String,args: JSONObject){val id=args.getString("device");val manager=context.getSystemService(CompanionDeviceManager::class.java)?:error("此设备不支持配对");require((0 until devices(context,plugin).length()).any{devices(context,plugin).optString(it)==id}){"设备不属于已授权配对"};manager.disassociate(id)}
    @Suppress("DEPRECATION")
    fun removePlugin(context:Context,plugin:String) {
        val manager=context.getSystemService(CompanionDeviceManager::class.java)
        val own=devices(context,plugin);for(i in 0 until own.length())manager?.disassociate(own.getString(i))
        PluginEncryptedStore(context,"plugin-devices",plugin).remove()
    }
    @Suppress("DEPRECATION")
    fun pair(context: Context,plugin:String,args: JSONObject,onFound:(IntentSender,Set<String>)->Unit,onFailure:(String)->Unit) {
        val manager=context.getSystemService(CompanionDeviceManager::class.java)?:error("此设备不支持伴侣设备配对")
        val before=manager.associations.toSet()
        val filter=BluetoothDeviceFilter.Builder();args.optString("name").takeIf{it.isNotBlank()}?.let{require(it.length<=100);filter.setNamePattern(Pattern.compile(".*"+Pattern.quote(it)+".*"))}
        val request=AssociationRequest.Builder().addDeviceFilter(filter.build()).setSingleDevice(false).build()
        manager.associate(request,object:CompanionDeviceManager.Callback(){override fun onDeviceFound(chooserLauncher: IntentSender){onFound(chooserLauncher,before)};override fun onFailure(error:CharSequence?){onFailure("系统未完成设备配对")}},Handler(Looper.getMainLooper()))
    }
    @Suppress("MissingPermission","DEPRECATION")
    fun location(context: Context,parent: PluginCancellation,reply:(JSONObject)->Unit) {
        val manager=context.getSystemService(LocationManager::class.java)?:error("设备没有定位服务")
        check(manager.isLocationEnabled){"请先开启系统定位"};check(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){"当前没有可用的粗略定位来源"}
        val main=Handler(Looper.getMainLooper());val done=AtomicBoolean(false);var stop:(()->Unit)?=null;var detach:(()->Unit)?=null
        val timeout=Runnable{if(done.compareAndSet(false,true)){stop?.invoke();detach?.invoke();reply(JSONObject().put("available",false).put("reason","timeout"))}}
        fun finish(location: Location?) {if(!done.compareAndSet(false,true))return;main.removeCallbacks(timeout);stop?.invoke();detach?.invoke();reply(if(location==null)JSONObject().put("available",false).put("reason","location_unavailable") else JSONObject().put("available",true).put("latitude",location.latitude).put("longitude",location.longitude).put("accuracyMeters",location.accuracy).put("at",location.time))}
        if(android.os.Build.VERSION.SDK_INT>=30){val signal=CancellationSignal();stop={signal.cancel()};detach=parent.onCancel{signal.cancel();main.post{finish(null)}};manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER,signal,context.mainExecutor){finish(it)}}
        else {val listener=object:LocationListener{override fun onLocationChanged(location: Location){finish(location)};override fun onProviderDisabled(provider:String){finish(null)};override fun onProviderEnabled(provider:String){};override fun onStatusChanged(provider:String?,status:Int,extras:android.os.Bundle?){}}
            stop={manager.removeUpdates(listener)};detach=parent.onCancel{main.post{finish(null)}};manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,listener,Looper.getMainLooper())}
        if(!done.get())main.postDelayed(timeout,20000)
    }
}
