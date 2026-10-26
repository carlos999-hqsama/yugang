package com.personaldashboard.app

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PluginCapabilitiesTest {
    @Test fun foregroundOperationsCannotRunFromAnAgentBackgroundAction(){
        for((capability,spec) in PluginCapabilities.services){
            for(op in spec.foreground+spec.pageOnly){
                assertTrue(runCatching{PluginCapabilities.operation(capability,op,false)}.isFailure)
                assertEquals(spec,PluginCapabilities.operation(capability,op,true))
            }
        }
    }
    @Test fun grantedServicesAreDiscoverableAndBackgroundReadIsAvailable(){
        val declared=setOf("plugin.files","plugin.notifications.read")
        val rows=PluginCapabilities.describe(declared,false).getJSONArray("operations")
        val notification=(0 until rows.length()).map{rows.getJSONObject(it)}.first{it.getString("capability")=="plugin.notifications.read"}
        val ops=notification.getJSONArray("operations")
        val active=(0 until ops.length()).map{ops.getJSONObject(it)}.first{it.getString("name")=="active"}
        assertTrue(active.getBoolean("available"))
        val request=(0 until ops.length()).map{ops.getJSONObject(it)}.first{it.getString("name")=="request"}
        assertFalse(request.getBoolean("available"));assertEquals("foreground_required",request.getString("reason"))
        assertTrue(runCatching{PluginCapabilities.operation("plugin.files","invented",true)}.isFailure)
    }
    @Test fun scriptsAndStateUseAnExecutableUtf8BudgetInsteadOfACharacterAssumption(){
        val bundle=JSONObject().put("manifest",JSONObject().put("apiVersion",1).put("id","test-budget").put("title","测试").put("version","1").put("kind","native").put("capabilities",JSONArray()))
            .put("view",JSONObject().put("type","text").put("text","ok")).put("code","//"+"中".repeat(65000)).put("state",JSONObject().put("text","x".repeat(20000)))
        PluginContract.validate(bundle)
        val bytes=PluginInvocationBudget.executable(JSONObject().put("bundle",bundle).put("inputs",JSONObject()).put("state",JSONObject().put("text","y".repeat(20000))))
        assertTrue(bytes.size>200000);assertTrue(bytes.size<PluginInvocationBudget.MAX_ENVELOPE_BYTES)
        assertFalse(JSONObject(String(bytes)).getJSONObject("bundle").has("state"))
    }
    @Test fun inputOverflowIsRejectedBeforeStartingAProcess(){
        val request=JSONObject().put("bundle",JSONObject()).put("inputs",JSONObject().put("text","x".repeat(PluginInvocationBudget.MAX_INPUT_BYTES+1)))
        assertTrue(runCatching{PluginInvocationBudget.executable(request)}.isFailure)
    }
}
