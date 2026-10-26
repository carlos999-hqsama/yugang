package com.personaldashboard.app;
import java.util.concurrent.*;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class PluginFetchQueuedCancelTest {
  @Test public void queuedFetchNeverPostsAfterCancellation() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
      String id = "review-before-start";
      JSONObject args = new JSONObject().put("url", server.url("/write").toString())
          .put("method", "POST").put("body", "{}").put("requestId", id);
      ExecutorService queue = Executors.newSingleThreadExecutor();
      CountDownLatch permitStart = new CountDownLatch(1);
      Future<JSONObject> result = queue.submit(() -> {
        permitStart.await();
        return PluginFetch.INSTANCE.fetch(args, new PluginCancellation(), null);
      });
      try {
        boolean cancelled = PluginFetch.INSTANCE.cancel(id);
        permitStart.countDown();
        try { result.get(5, TimeUnit.SECONDS); fail("cancelled request must fail"); }
        catch (ExecutionException expected) { assertNotNull(expected.getCause()); }
        assertTrue(cancelled);
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS));
        assertEquals(0, server.getRequestCount());
      } finally { permitStart.countDown(); queue.shutdownNow(); }
    }
  }
}
