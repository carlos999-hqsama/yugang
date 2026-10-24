package com.personaldashboard.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PluginRequestExceptionTest {
    @Test fun `transport errors preserve structured cause without leaking exception text`() {
        val timeout=PluginRequestException.network(SocketTimeoutException("private details"),"https://example.com/final",0,false).payload()
        assertTrue(timeout.getBoolean("timeout"));assertEquals("timeout",timeout.getString("code"))
        assertTrue(timeout.getBoolean("retryable"));assertFalse(timeout.toString().contains("private details"))
        val cancelled=PluginRequestException.network(SocketTimeoutException(),"https://example.com",0,true).payload()
        assertTrue(cancelled.getBoolean("cancelled"));assertFalse(cancelled.getBoolean("retryable"))
        assertEquals("dns_error",PluginRequestException.network(UnknownHostException(),"https://example.com",0,false).code)
    }
    @Test fun `http failure after redirect keeps actual status and final url`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/final"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type","application/json").setBody("{}"))
            val value=PluginFetch.fetch(JSONObject().put("url",server.url("/start").toString()).put("responseType","text"))
            assertEquals(429,value.getInt("status"));assertEquals(server.url("/final").toString(),value.getString("finalUrl"))
            assertFalse(value.getBoolean("ok"));assertEquals("http_error",value.getString("code"));assertTrue(value.getBoolean("retryable"))
        }
    }
}
