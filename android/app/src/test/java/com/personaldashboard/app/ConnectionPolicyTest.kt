package com.personaldashboard.app

import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
    @Test fun incomingPairLinksCannotChooseAnotherLocalService() {
        assertTrue(Net.isAutoPairLink("http://127.0.0.1:3080/?token=synthetic"))
        assertTrue(Net.isAutoPairLink("http://localhost:3080/?token=synthetic"))
        for (url in listOf("http://127.0.0.1:9999/?token=synthetic", "http://example.com:3080/?token=synthetic", "http://u@localhost:3080/?token=synthetic", "http://localhost:3080/other?token=synthetic")) assertFalse(url, Net.isAutoPairLink(url))
    }
    @Test fun harnessTransportOnlyAcceptsLoopbackOrigins() {
        assertTrue(Config.isLoopback("http://127.0.0.1:3080"))
        assertTrue(Config.isLoopback("http://localhost:3088"))
        assertTrue(Config.isLoopback("http://[::1]"))
        for (url in listOf("https://127.0.0.1:3080", "http://example.com:3080", "http://192.168.1.10")) {
            assertFalse(url, Config.isLoopback(url))
        }
    }
}
