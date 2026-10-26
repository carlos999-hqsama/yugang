package com.personaldashboard.app

import java.util.concurrent.Semaphore
import org.json.JSONObject

object PluginInvocationBudget {
    const val MAX_INPUT_BYTES=256*1024
    const val MAX_ENVELOPE_BYTES=768*1024
    const val MAX_CONCURRENT=3
    private val slots=Semaphore(MAX_CONCURRENT,true)
    fun executable(request: JSONObject): ByteArray {
        val copy=JSONObject(request.toString());copy.getJSONObject("bundle").apply{remove("resources");remove("state")}
        require((copy.optJSONObject("inputs")?:JSONObject()).toString().toByteArray().size<=MAX_INPUT_BYTES){"插件动作输入超过 256 KiB"}
        return copy.toString().toByteArray().also { require(it.size<=MAX_ENVELOPE_BYTES){"插件执行请求超过字节预算"} }
    }
    fun validateBundle(bundle: JSONObject) {
        executable(JSONObject().put("bundle",bundle).put("inputs",JSONObject()).put("state",JSONObject().put("padding","x".repeat(32700))))
    }
    fun <T> run(block:()->T): T {
        if(!slots.tryAcquire()) throw PluginRequestException("busy","插件执行繁忙，请稍后重试",retryable=true)
        try{return block()}finally{slots.release()}
    }
}
