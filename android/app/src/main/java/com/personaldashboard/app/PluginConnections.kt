package com.personaldashboard.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Named API connections. Only this host receives or stores credentials. */
object PluginConnections {
    private val callbacks=java.util.concurrent.ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,java.util.concurrent.ArrayBlockingQueue(16),{r->Thread(r,"plugin-oauth").apply{isDaemon=true}},java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
    private val callbacksInFlight=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val refreshLocks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private val client=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).callTimeout(30,TimeUnit.SECONDS).build()
    private fun store(context:Context,plugin:String)=PluginEncryptedStore(context,"plugin-connections",plugin)
    private fun id(a:JSONObject)=PluginContract.id(a.getString("connectionId"))
    private fun endpoint(raw:String):String=raw.toHttpUrl().also{require(it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()){"授权地址必须是没有内嵌账号的 HTTPS 地址"}}.toString()
    private fun origin(raw:String):String {val u=raw.toHttpUrl();require((u.isHttps||u.host in setOf("127.0.0.1","localhost","::1"))&&u.username.isEmpty()&&u.password.isEmpty()){"API 地址必须使用 HTTPS"};return u.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')+"/"}
    private fun metadata(id:String,row:JSONObject,context:Context):JSONObject=JSONObject().put("connectionId",id).put("name",row.optString("name")).put("baseUrl",row.optString("baseUrl")).put("kind",row.optString("kind"))
        .put("configured",row.optString("token").isNotBlank()).put("expiresAt",row.optLong("expiresAt",0)).put("authorization",row.optString("authorization",if(row.has("token"))"ready" else "not_authorized")).put("redirectUri",context.packageName+".oauth://callback")
    fun configure(activity:MainActivity,plugin:String,revision:String,a:JSONObject,cancellation:PluginCancellation,reply:(JSONObject)->Unit) {
        val key=a.optString("connectionId").ifBlank{"c-"+UUID.randomUUID()};PluginContract.id(key)
        val kind=a.optString("kind","apiKey");require(kind in setOf("apiKey","oauth")){"连接类型无效"};val row=JSONObject().put("configurationId",UUID.randomUUID().toString()).put("name",a.optString("name","API 连接").take(80)).put("kind",kind).put("baseUrl",origin(a.getString("baseUrl")))
        if(kind=="oauth"){
            val clientId=a.getString("clientId");require(clientId.length in 1..1024)
            row.put("authorizationUrl",endpoint(a.getString("authorizationUrl"))).put("tokenUrl",endpoint(a.getString("tokenUrl"))).put("clientId",clientId).put("scope",a.optString("scope").take(2000))
            PluginPlatform.withActiveRevision(activity,plugin,revision){cancellation.check();store(activity,plugin).update{data->require(data.has(key)||data.length()<16){"API连接过多"};data.put(key,row)}}
            reply(metadata(key,row,activity));return
        }
        val header=a.optString("header","Authorization");require(Regex("[A-Za-z][A-Za-z0-9-]{0,63}").matches(header)&&header.lowercase() !in setOf("cookie","host","proxy-authorization")){"凭据请求头无效"}
        row.put("header",header).put("prefix",a.optString("prefix",if(header.equals("Authorization",true))"Bearer " else "").take(40))
        val field=EditText(activity).apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;hint="仅保存在手机宿主";setSingleLine(true)}
        var answered=false
        val dialog=AlertDialog.Builder(activity).setTitle(row.getString("name")).setMessage(row.getString("baseUrl")).setView(field).setNegativeButton("取消"){_,_->answered=true;reply(JSONObject().put("cancelled",true))}.setPositiveButton("保存",null).create()
        val detach=cancellation.onCancel{activity.runOnUiThread{dialog.dismiss()}}
        dialog.setOnDismissListener{detach();if(!answered){answered=true;reply(JSONObject().put("cancelled",true))}}
        dialog.setOnShowListener{dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val token=field.text.toString().trim();if(token.isBlank()||token.length>8192){field.error="请输入有效的 API Key";return@setOnClickListener}
            try{row.put("token",token);PluginPlatform.withActiveRevision(activity,plugin,revision){cancellation.check();store(activity,plugin).update{data->require(data.has(key)||data.length()<16){"API连接过多"};data.put(key,row)}};field.text.clear();answered=true;dialog.dismiss();reply(metadata(key,row,activity))}catch(_:Exception){field.error="连接未保存，请重试"}
        }};dialog.show()
    }
    private fun random():String {val b=ByteArray(32);SecureRandom().nextBytes(b);return Base64.encodeToString(b,Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)}
    fun authorize(activity:MainActivity,plugin:String,revision:String,a:JSONObject):JSONObject {
        val key=id(a);val row=store(activity,plugin).read().getJSONObject(key);require(row.getString("kind")=="oauth"){"此连接不使用 OAuth"}
        val verifier=random();val state=plugin+"."+random();val redirect=activity.packageName+".oauth://callback"
        PluginPlatform.withActiveRevision(activity,plugin,revision){store(activity,plugin).update{it.getJSONObject(key).put("configurationId",UUID.randomUUID().toString()).put("authorizationAttempt",state).put("authorizationRevision",revision).put("authorizationExpiresAt",System.currentTimeMillis()+600000).put("flow",JSONObject().put("state",state).put("verifier",verifier).put("revision",revision).put("expiresAt",System.currentTimeMillis()+600000)).put("authorization","pending")}}
        val challenge=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val uri=Uri.parse(row.getString("authorizationUrl")).buildUpon().appendQueryParameter("response_type","code").appendQueryParameter("client_id",row.getString("clientId")).appendQueryParameter("redirect_uri",redirect).appendQueryParameter("scope",row.optString("scope")).appendQueryParameter("state",state).appendQueryParameter("code_challenge",challenge).appendQueryParameter("code_challenge_method","S256").build()
        activity.startActivity(Intent(Intent.ACTION_VIEW,uri));return JSONObject().put("opened",true).put("connectionId",key).put("authorization","pending")
    }
    fun callback(context:Context,uri:Uri):Boolean {
        if(uri.scheme!=context.packageName+".oauth"||uri.host!="callback")return false
        val state=uri.getQueryParameter("state").orEmpty();if(state.length !in 20..150)return true
        val plugin=state.substringBefore('.');if(runCatching{PluginContract.id(plugin)}.isFailure)return true
        if(!callbacksInFlight.add(state))return true
        try{callbacks.execute{try{runCatching{
            val data=store(context,plugin).read();val key=data.keys().asSequence().firstOrNull{data.getJSONObject(it).optJSONObject("flow")?.optString("state")==state}?:return@runCatching
            val row=data.getJSONObject(key);val flow=row.getJSONObject("flow");check(flow.getLong("expiresAt")>System.currentTimeMillis());PluginPlatform.store(context).requireRevision(plugin,flow.getString("revision"))
            PluginPlatform.withActiveRevision(context,plugin,flow.getString("revision")){store(context,plugin).update{val current=it.getJSONObject(key);check(current.getJSONObject("flow").getString("state")==state);current.remove("flow");current.put("authorization","exchanging")}}
            try{
                check(uri.getQueryParameter("error")==null);val code=uri.getQueryParameter("code")?:error("授权未完成");require(code.length in 1..8192)
                val form=FormBody.Builder().add("grant_type","authorization_code").add("code",code).add("client_id",row.getString("clientId")).add("redirect_uri",context.packageName+".oauth://callback").add("code_verifier",flow.getString("verifier")).build()
                val tokens=exchange(row.getString("tokenUrl"),form,PluginCancellation());PluginPlatform.store(context).requireRevision(plugin,flow.getString("revision"));saveTokens(context,plugin,key,tokens,flow.getString("revision"),row.getString("configurationId"),state)
            }catch(_:Exception){runCatching{PluginPlatform.withActiveRevision(context,plugin,flow.getString("revision")){store(context,plugin).update{val current=it.optJSONObject(key);if(current?.optString("authorizationAttempt")==state)current.put("authorization","failed")}}}}
        }}finally{callbacksInFlight.remove(state)}}}catch(_:java.util.concurrent.RejectedExecutionException){callbacksInFlight.remove(state)};return true
    }
    private fun exchange(url:String,body:FormBody,cancel:PluginCancellation):JSONObject {
        val call=client.newCall(Request.Builder().url(url).post(body).build());val detach=cancel.onCancel{call.cancel()}
        try{return call.execute().use{r->check(r.isSuccessful){"授权服务未接受请求"};val input=r.body?:error("授权响应为空");check(input.contentLength()<=65536);val out=java.io.ByteArrayOutputStream();input.byteStream().use{s->val b=ByteArray(4096);while(true){val n=s.read(b);if(n<0)break;check(out.size()+n<=65536){"授权响应过大"};out.write(b,0,n)}};JSONObject(out.toString("UTF-8"))}}finally{detach()}
    }
    private fun saveTokens(context:Context,plugin:String,key:String,tokens:JSONObject,revision:String,configuration:String,attempt:String?=null,cancellation:PluginCancellation?=null){
        val token=tokens.getString("access_token");require(token.length in 1..8192);require(tokens.optString("token_type","Bearer").equals("Bearer",true)){"不支持的授权令牌类型"}
        PluginPlatform.withActiveRevision(context,plugin,revision){cancellation?.check();store(context,plugin).update{data->
            val row=data.getJSONObject(key);check(row.getString("configurationId")==configuration&&(attempt==null||row.optString("authorizationAttempt")==attempt)){"连接授权已更新"}
            row.put("token",token).put("authorization","ready").put("expiresAt",System.currentTimeMillis()+tokens.optLong("expires_in",3600).coerceIn(1,31536000)*1000)
            if(attempt!=null)row.remove("refreshToken")
            tokens.optString("refresh_token").takeIf{it.isNotBlank()}?.let{require(it.length<=8192);row.put("refreshToken",it)}
        }}
    }
    private fun pruneAuthorizations(context:Context,plugin:String,revision:String) {
        PluginPlatform.withActiveRevision(context,plugin,revision){store(context,plugin).update{data->
            for(key in data.keys().asSequence().toList()){
                val row=data.getJSONObject(key)
                if(row.optString("authorization") in setOf("pending","exchanging")&&
                    (row.optString("authorizationRevision",row.optJSONObject("flow")?.optString("revision").orEmpty())!=revision||row.optLong("authorizationExpiresAt",row.optJSONObject("flow")?.optLong("expiresAt")?:0)<=System.currentTimeMillis())){
                    row.remove("flow");row.remove("authorizationAttempt");row.put("configurationId",UUID.randomUUID().toString()).put("authorization",if(row.optString("token").isNotBlank())"ready" else "not_authorized")
                }
            }
        }}
    }

    internal fun headers(context:Context,plugin:String,revision:String,key:String,target:String,cancellation:PluginCancellation):Map<String,String> {
        PluginContract.id(key)
        return synchronized(refreshLocks.computeIfAbsent(plugin+":"+key){Any()}){
            PluginHostServices.requireCurrent(context,plugin,revision,"plugin.connection");pruneAuthorizations(context,plugin,revision)
            var row=store(context,plugin).read().getJSONObject(key)
            check(row.optString("authorization") !in setOf("pending","exchanging")){"请先完成当前连接登录"}
            val base=row.getString("baseUrl").toHttpUrl();val url=target.toHttpUrl()
            require(url.scheme==base.scheme&&url.host==base.host&&url.port==base.port&&url.encodedPath.startsWith(base.encodedPath)){"请求不属于连接授权的 API 范围"}
            if(row.getString("kind")=="oauth"&&row.optLong("expiresAt")<=System.currentTimeMillis()+30000){
                val refresh=row.optString("refreshToken");check(refresh.isNotBlank()){"授权已过期，请重新登录"}
                val tokens=exchange(row.getString("tokenUrl"),FormBody.Builder().add("grant_type","refresh_token").add("refresh_token",refresh).add("client_id",row.getString("clientId")).build(),cancellation)
                saveTokens(context,plugin,key,tokens,revision,row.getString("configurationId"),cancellation=cancellation);row=store(context,plugin).read().getJSONObject(key)
            }
            cancellation.check();mapOf(row.optString("header","Authorization") to row.optString("prefix","Bearer ")+row.getString("token"))
        }
    }

    fun call(context:Context,plugin:String,revision:String,a:JSONObject,cancellation:PluginCancellation):JSONObject {
        PluginHostServices.requireCurrent(context,plugin,revision,"plugin.connection");pruneAuthorizations(context,plugin,revision)
        val data=store(context,plugin).read();val op=a.getString("op")
        if(op=="list")return JSONObject().put("connections",JSONArray(data.keys().asSequence().map{metadata(it,data.getJSONObject(it),context)}.toList()))
        val key=id(a);var row=data.getJSONObject(key)
        if(op=="remove"){PluginPlatform.withActiveRevision(context,plugin,revision){cancellation.check();store(context,plugin).update{it.remove(key)}};refreshLocks.remove(plugin+":"+key);return JSONObject().put("removed",true)}
        if(op=="status")return metadata(key,row,context)
        require(op=="request"){"此连接操作需要前台插件页面"}
        val base=row.getString("baseUrl").toHttpUrl();val url=base.resolve(a.getString("path"))?:error("API路径无效")
        require(url.scheme==base.scheme&&url.host==base.host&&url.port==base.port&&url.encodedPath.startsWith(base.encodedPath)){"请求不属于连接授权的 API 范围"}
        val credentials=headers(context,plugin,revision,key,url.toString(),cancellation)
        val headers=JSONObject(a.optJSONObject("headers")?.toString()?:"{}")
        for(name in headers.keys().asSequence().toList())if(name.equals("Authorization",true)||credentials.keys.any{it.equals(name,true)})headers.remove(name)
        credentials.forEach{(name,value)->headers.put(name,value)}
        return PluginFetch.fetch(JSONObject().put("url",url.toString()).put("method",a.optString("method","GET")).put("headers",headers).put("body",a.opt("body")?:JSONObject.NULL).put("responseType",a.optString("responseType","text")).put("timeoutMs",a.optLong("timeoutMs",15000)),cancellation,followRedirects=false)
    }
    fun removePlugin(context:Context,id:String){store(context,id).remove()}
}
