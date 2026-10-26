package com.personaldashboard.app

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Shared bounded page IO. Only public counters are retained; never payloads or URLs. */
object PluginCallAdmission {
    private val ownerLock=Any()
    private val owners=mutableMapOf<String,Int>()
    private val history=ArrayDeque<JSONObject>()
    private val executor=ThreadPoolExecutor(4,8,30,TimeUnit.SECONDS,ArrayBlockingQueue(64),{r->Thread(r,"plugin-host-io").apply{isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    private fun release(owner:String)=synchronized(ownerLock){val n=owners[owner]?:return@synchronized;if(n<=1)owners.remove(owner) else owners[owner]=n-1}
    private class Call(val owner:String,val task:Runnable):Runnable {
        override fun run(){val started=System.nanoTime();try{task.run()}finally{release(owner);synchronized(history){history.addLast(JSONObject().put("elapsedMs",(System.nanoTime()-started)/1000000));while(history.size>64)history.removeFirst()}}}
    }
    fun submit(owner:String,task:Runnable){
        synchronized(ownerLock){val n=owners[owner]?:0;if(n>=12)throw java.util.concurrent.RejectedExecutionException("插件调用繁忙");owners[owner]=n+1}
        try{executor.execute(Call(owner,task))}catch(e:RuntimeException){release(owner);throw e}
    }
    fun cancelQueued(owner:String){for(task in executor.queue.toList())if(task is Call&&task.owner==owner&&executor.remove(task))release(owner)}
    fun diagnostics():JSONObject=JSONObject().put("active",executor.activeCount).put("queued",executor.queue.size).put("owners",synchronized(ownerLock){owners.size})
        .put("recent",synchronized(history){JSONArray(history.map{JSONObject(it.toString())})})
}

/** Per-page facade for the shared pool; closing a page cannot shut down other pages. */
internal class PluginBridgeExecutor:java.util.concurrent.Executor {
    private val owner=java.util.UUID.randomUUID().toString()
    @Volatile private var closed=false
    override fun execute(task:Runnable){
        if(closed)throw java.util.concurrent.RejectedExecutionException("页面已关闭")
        PluginCallAdmission.submit(owner,Runnable{if(!closed)task.run()})
        if(closed)PluginCallAdmission.cancelQueued(owner)
    }
    fun shutdownNow(){closed=true;PluginCallAdmission.cancelQueued(owner)}
}
