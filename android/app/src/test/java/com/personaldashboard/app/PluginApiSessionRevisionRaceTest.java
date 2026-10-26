package com.personaldashboard.app;
import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class PluginApiSessionRevisionRaceTest {
  public static class TestContext extends ContextWrapper {
    File root;
    public TestContext() { super(null); }
    @Override public Context getApplicationContext() { return this; }
    @Override public File getFilesDir() { return root; }
  }
  static Object field(Class<?> c, String n) throws Exception {
    Field f = c.getDeclaredField(n); f.setAccessible(true); return f.get(null);
  }
  static JSONObject bundle(String version) throws Exception {
    return new JSONObject().put("manifest",new JSONObject().put("apiVersion",1)
      .put("id","review-plugin").put("title","Review").put("version",version).put("kind","native")
      .put("capabilities",new JSONArray().put("plugin.apiSession")))
      .put("view",new JSONObject().put("type","text").put("text","review"))
      .put("code","export async function run(){return {view:{type:'text',text:'ok'}}}");
  }
  @Test public void stalePageClearCannotEraseSessionFromNewRevision() throws Exception {
    Class<?> uc=Class.forName("sun.misc.Unsafe");
    Field uf=uc.getDeclaredField("theUnsafe"); uf.setAccessible(true);
    Object unsafe=uf.get(null);
    java.lang.reflect.Method allocate=uc.getMethod("allocateInstance",Class.class);
    TestContext ctx=(TestContext)allocate.invoke(unsafe,TestContext.class);
    ctx.root=Files.createTempDirectory("yugang-review-race").toFile();
    Config cfg=(Config)allocate.invoke(unsafe,Config.class);
    Field instance=PluginPlatform.class.getDeclaredField("instance"); instance.setAccessible(true);
    Object previous=instance.get(null);
    PluginStore store=new PluginStore(new File(ctx.root,"registry"));
    store.install(bundle("1")); instance.set(null,store);
    String revision=store.read("review-plugin").getString("_revision");
    File apiRoot=new File(ctx.root,"PLUGIN_API_SESSIONS");
    PluginApiSessionFactory factory=new PluginApiSessionFactory(apiRoot,new PluginApiSessionCipher(){
      public byte[] seal(byte[] b){return b;}
      public byte[] open(byte[] b){return b;}
    });
    Map factories=(Map)field(PluginApiSessions.class,"factories");
    factories.put(apiRoot.getCanonicalPath(),factory);
    CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
    JSONObject args=new JSONObject(){
      @Override public String getString(String key) throws JSONException {
        if(!key.equals("op")) return super.getString(key);
        entered.countDown();
        try { if(!release.await(5,TimeUnit.SECONDS))throw new RuntimeException("review timeout"); }
        catch(InterruptedException e){throw new RuntimeException(e);}
        return "clear";
      }
    };
    PluginCancellation parent=new PluginCancellation();
    ExecutorService executor=Executors.newFixedThreadPool(2);
    Future<JSONObject> old=executor.submit(()->PluginPlatform.INSTANCE.pageCapability(ctx,cfg,
      "review-plugin",revision,"plugin.apiSession",args,parent,0));
    try {
      assertTrue(entered.await(5,TimeUnit.SECONDS));
      parent.cancel();
      CountDownLatch updateAttempted=new CountDownLatch(1);
      Future<?> update=executor.submit(()-> {
        updateAttempted.countDown();
        try { synchronized(field(PluginPlatform.class,"lifecycle")) {store.install(bundle("2"));} }
        catch(Exception e){throw new RuntimeException(e);}
      });
      assertTrue(updateAttempted.await(5,TimeUnit.SECONDS));
      try {update.get(100,TimeUnit.MILLISECONDS);fail("update must serialize with the old operation");}
      catch(TimeoutException expected){}
      release.countDown();
      try {old.get(5,TimeUnit.SECONDS);fail("cancelled page must fail");}
      catch(ExecutionException expected){assertTrue(expected.getCause() instanceof IllegalStateException);}
      update.get(5,TimeUnit.SECONDS);
      PluginApiSessionContextId id=factory.contextId("review-plugin","default");
      HttpUrl url=HttpUrl.get("https://review.invalid/");
      factory.cookieJar(id).saveFromResponse(url,Collections.singletonList(
        new Cookie.Builder().name("review_marker").value("synthetic").hostOnlyDomain("review.invalid").path("/").build()));
      assertEquals(1,factory.status(id).getInt("cookieCount"));
      assertEquals(1,factory.status(id).getInt("cookieCount"));
    } finally {
      release.countDown();executor.shutdownNow();instance.set(null,previous);factories.remove(apiRoot.getCanonicalPath());
      try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(ctx.root.toPath())){
        paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});
      }
    }
  }
}
