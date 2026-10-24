package com.personaldashboard.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PluginStoreTest {
    private fun bundle(version: String = "1", capability: String? = null, text: String = "native") = JSONObject()
        .put("manifest",JSONObject().put("apiVersion",1).put("id","test-plugin").put("title","Test")
            .put("version",version).put("kind","native").put("capabilities",JSONArray(if(capability==null) emptyList<String>() else listOf(capability))))
        .put("view",JSONObject().put("type","text").put("text",text))
        .put("code","export async function run(){ return {view:{type:'text',text:'ok'}} }")
    private fun withStore(test:(PluginStore,java.io.File)->Unit) {
        val dir=Files.createTempDirectory("pd-plugins").toFile()
        try { test(PluginStore(dir),dir) } finally { dir.deleteRecursively() }
    }
    @Test fun freshInstallHasNoPersonalPlugins() = withStore { s,_ ->
        assertEquals(0,s.list().getJSONArray("plugins").length())
    }
    @Test fun invalidUpdateKeepsCurrentAndRollbackSurvivesReopen() = withStore { store,dir ->
        store.install(bundle()); store.install(bundle("2",text="updated"))
        try { store.install(bundle("3").put("view",JSONObject().put("type","webview"))); fail() } catch(_:IllegalArgumentException) {}
        assertEquals("2",store.read("test-plugin").getJSONObject("manifest").getString("version"))
        val reopened=PluginStore(dir); reopened.rollback("test-plugin")
        assertEquals("1",reopened.read("test-plugin").getJSONObject("manifest").getString("version"))
    }
    @Test fun agentPluginsHaveFullAuthorityWithoutManualGrant() = withStore { s,_ ->
        s.install(bundle(capability="plugin.model")); assertTrue(s.enabled("test-plugin"))
        s.requireCapability("test-plugin","plugin.model")
        s.requireCapability("test-plugin","plugin.media")
        s.install(bundle("2","app.call")); assertTrue(s.enabled("test-plugin"))
        s.requireCapability("test-plugin","app.call")
        s.enable("test-plugin",false)
        try{s.requireCapability("test-plugin","plugin.media");fail()}catch(_:IllegalArgumentException){}
        s.enable("test-plugin",true);s.rollback("test-plugin");assertTrue(s.enabled("test-plugin"))
    }
    @Test fun staleActionCannotOverwriteUpdatedPluginState() = withStore { s,_ ->
        s.install(bundle()); val before=s.read("test-plugin")
        s.install(bundle("2"))
        try{s.setData("test-plugin",JSONObject().put("stale",true),before);fail()}catch(_:IllegalArgumentException){}
        assertFalse(s.data("test-plugin").has("stale"))
    }
    @Test fun traversalAndHostEntrySpoofAreRejected() = withStore { s,_ ->
        val bad=bundle(); bad.getJSONObject("manifest").put("id","../settings")
        try{s.install(bad);fail()}catch(_:IllegalArgumentException){}
        val spoof=bundle();spoof.getJSONObject("manifest").put("id","demo-plugin").put("kind","builtin").put("entry","demo-plugin")
        try{s.install(spoof);fail()}catch(_:IllegalArgumentException){}
        assertEquals(0,s.list().getJSONArray("plugins").length())
    }
    @Test fun independentPageHasItsOwnResourcesAndCannotEnterHostPage() = withStore { s,_ ->
        val page=bundle().put("view",JSONObject().put("type","page").put("entry","main.js").put("styles",JSONArray()))
            .put("resources",JSONObject().put("main.js","export default {}"))
        page.getJSONObject("manifest").put("frameworkVersion",1)
        s.install(page)
        assertEquals("page",s.read("test-plugin").getJSONObject("view").getString("type"))
        val missing=JSONObject(page.toString());missing.getJSONObject("resources").remove("main.js")
        try{s.install(missing);fail()}catch(_:IllegalArgumentException){}
        val traversal=JSONObject(page.toString());traversal.getJSONObject("resources").put("../host.html","bad")
        try{s.install(traversal);fail()}catch(_:IllegalArgumentException){}
        val host=JSONObject(page.toString());host.getJSONObject("manifest").put("id","demo-plugin").put("entry","demo-plugin")
        try{s.install(host);fail()}catch(_:IllegalArgumentException){}
    }

    @Test fun reinstallDoesNotRestoreDeletedPluginState()=withStore{s,_->
        s.install(bundle());s.setData("test-plugin",JSONObject().put("old",true),s.read("test-plugin"));s.remove("test-plugin")
        s.install(bundle());assertTrue(s.enabled("test-plugin"));assertFalse(s.data("test-plugin").has("old"))
    }
    @Test fun stoppedPluginCannotCommitInFlightState()=withStore{s,_->
        s.install(bundle());val before=s.read("test-plugin");s.enable("test-plugin",false)
        try{s.setData("test-plugin",JSONObject(),before);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun pendingRemovalSurvivesRestartWithoutRetainingPrivateState()=withStore{s,dir->
        s.install(bundle());s.setData("test-plugin",JSONObject().put("draft","SYNTHETIC_OLD_DRAFT"),s.read("test-plugin"))
        s.remove("test-plugin")
        val reopened=PluginStore(dir)
        assertEquals(listOf("test-plugin"),reopened.pendingRemovals());assertFalse(reopened.installed("test-plugin"))
        assertFalse(java.io.File(dir,"registry.json").readText().contains("SYNTHETIC_OLD_DRAFT"))
        reopened.finishRemoval("test-plugin")
        assertTrue(PluginStore(dir).pendingRemovals().isEmpty())
    }
    @Test fun aLateRemovalReceiptCannotEraseAReinstalledPlugin()=withStore{s,_->
        s.install(bundle());s.remove("test-plugin");s.install(bundle("2"));s.finishRemoval("test-plugin")
        assertTrue(s.installed("test-plugin"));assertEquals("2",s.read("test-plugin").getJSONObject("manifest").getString("version"))
    }
    @Test fun failedRemovalCommitKeepsTheInstalledState()=withStore{s,dir->
        s.install(bundle());s.setData("test-plugin",JSONObject().put("draft","SYNTHETIC_KEPT"),s.read("test-plugin"))
        val file=java.io.File(dir,"registry.json");assertTrue(file.delete());assertTrue(file.mkdir());java.io.File(file,"obstacle").writeText("fixture")
        assertTrue(runCatching{s.remove("test-plugin")}.isFailure)
        assertTrue(s.installed("test-plugin"));assertEquals("SYNTHETIC_KEPT",s.data("test-plugin").getString("draft"))
    }
    @Test fun removedPluginStaysRemovedAfterRestart() {
        val dir=Files.createTempDirectory("pd-plugin-seed").toFile()
        try {
            val s=PluginStore(dir);s.install(bundle());s.remove("test-plugin")
            val reopened=PluginStore(dir)
            assertEquals(0,reopened.list().getJSONArray("plugins").length())
        }finally{dir.deleteRecursively()}
    }

    @Test fun pluginCanBeUpdatedRolledBackAndRemovedWithoutHostFallback() {
        val dir=Files.createTempDirectory("pd-plugin-independent").toFile()
        val first=bundle().apply { getJSONObject("manifest").put("id","demo-plugin") }
        try {
            val s=PluginStore(dir);s.install(first)
            val second=JSONObject(first.toString()).apply { getJSONObject("manifest").put("version","2") }
            s.install(second);s.rollback("demo-plugin")
            assertEquals("1",s.read("demo-plugin").getJSONObject("manifest").getString("version"))
            s.remove("demo-plugin");assertEquals(0,PluginStore(dir).list().getJSONArray("plugins").length())
        }finally{dir.deleteRecursively()}
    }

    @Test fun disableReenableDoesNotAdmitPreviousActionResults()=withStore{s,_->
        s.install(bundle());val before=s.read("test-plugin")
        s.enable("test-plugin",false);s.enable("test-plugin",true)
        try{s.setData("test-plugin",JSONObject().put("late",true),before);fail()}catch(_:IllegalArgumentException){}
        assertFalse(s.data("test-plugin").has("late"))
    }
    @Test fun newsLifecycleUpgradeRollbackRemoveAndReinstallSurviveRestart() {
        val dir=Files.createTempDirectory("news-lifecycle").toFile()
        val news=bundle().apply { getJSONObject("manifest").put("id","news").put("onOpen","open") }
        try {
            var s=PluginStore(dir);s.install(news);s.requireActive("news")
            s.setData("news",JSONObject().put("issueId","fixture"),s.read("news"))
            val updated=JSONObject(news.toString());updated.getJSONObject("manifest").put("version","2")
            s.install(updated);assertEquals("fixture",s.data("news").getString("issueId"))
            s.rollback("news");assertEquals("1",s.read("news").getJSONObject("manifest").getString("version"))
            s.enable("news",false);s=PluginStore(dir)
            try{s.requireActive("news");fail()}catch(_:IllegalArgumentException){}
            s.remove("news");s=PluginStore(dir)
            assertEquals(0,s.list().getJSONArray("plugins").length())
            try{s.requireActive("news");fail()}catch(_:IllegalArgumentException){}
            s.install(updated);s.requireActive("news");assertFalse(s.data("news").has("issueId"))
        }finally{dir.deleteRecursively()}
    }
    @Test fun navigationContractRejectsUnsafeLinksAndInvalidActions() {
        for(url in listOf("javascript:alert(1)","https://user:secret@example.com/","file:///etc/test")) {
            try { PluginContract.validateView(JSONObject().put("type","link").put("text","source").put("url",url));fail() }catch(_:IllegalArgumentException){}
        }
        PluginContract.validateView(JSONObject().put("type","link").put("text","source").put("url","https://example.com/"))
        try { PluginContract.validateView(JSONObject().put("type","text").put("text","read").put("backAction","bad/action"));fail() }catch(_:IllegalArgumentException){}
    }

    @Test fun legalBrowserLinksNormalizeWithoutRejectingThePage() {
        for(raw in listOf("https://example.com/a|b","https://example.com/a%b","https://例子.测试/正文")) {
            val normalized=PluginContract.linkUrl(raw)
            val uri=java.net.URI(normalized)
            assertEquals("https",uri.scheme);assertNotNull(uri.host)
            PluginContract.validateView(JSONObject().put("type","link").put("text","来源").put("url",raw))
        }
        try { PluginContract.linkUrl("not a url");fail() } catch(e:IllegalArgumentException) { assertEquals("仅支持公开 HTTP 链接",e.message) }
    }
    @Test fun activeBundleIsUnavailableAfterRemovalOrDisable()=withStore{s,_->
        s.install(bundle());assertTrue(s.activeBundle("test-plugin").has("_revision"))
        s.enable("test-plugin",false)
        try{s.activeBundle("test-plugin");fail()}catch(_:IllegalArgumentException){}
        s.enable("test-plugin",true);s.remove("test-plugin")
        try{s.activeBundle("test-plugin");fail()}catch(_:IllegalArgumentException){}
    }

}
