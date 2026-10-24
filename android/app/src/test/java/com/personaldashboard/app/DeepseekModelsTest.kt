package com.personaldashboard.app

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DeepseekModelsTest {
    @Test fun authenticatedDiscoveryReturnsOnlyModelIds() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"deepseek-flash","extra":"not returned"},{"id":"deepseek-v4-pro"}]}"""))
            val result = DeepseekModels(OkHttpClient(), server.url("/models").toString()).read("test-fixture")
            assertTrue(result.getBoolean("verified"))
            assertEquals("deepseek-flash", result.getJSONArray("models").getJSONObject(0).getString("id"))
            assertEquals(1,result.getJSONArray("models").getJSONObject(0).length())
            assertEquals("Bearer test-fixture", server.takeRequest().getHeader("Authorization"))
            assertFalse(result.toString().contains("test-fixture"))
        }
    }
    @Test fun invalidKeyCannotReturnModelsOrExposeUpstreamBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private upstream response"))
            val failure = runCatching { DeepseekModels(OkHttpClient(), server.url("/models").toString()).read("test-fixture") }.exceptionOrNull()!!
            assertTrue(failure.message!!.contains("Key 无效"))
            assertFalse(failure.message!!.contains("private upstream"))
        }
    }
    @Test fun blankKeyDoesNotMakeNetworkRequest() {
        MockWebServer().use { server ->
            assertTrue(runCatching { DeepseekModels(OkHttpClient(),server.url("/models").toString()).read("") }.isFailure)
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun oversizedModelResponseIsRejected() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(512 * 1024 + 1)))
            val failure=runCatching { DeepseekModels(OkHttpClient(),server.url("/models").toString()).read("test-fixture") }.exceptionOrNull()!!
            assertTrue(failure.message!!.contains("响应过大"))
        }
    }
}
