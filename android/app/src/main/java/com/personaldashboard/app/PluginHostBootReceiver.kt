package com.personaldashboard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restore durable host records without starting the AI runtime or opening any page. */
class PluginHostBootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        val pending=goAsync()
        Thread({try{runCatching{PluginBackground.reconcile(context.applicationContext)};runCatching{PluginTransfers.recover(context.applicationContext)}}finally{pending.finish()}},"plugin-boot-recovery").start()
    }
}
