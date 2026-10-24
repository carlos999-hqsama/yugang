package com.personaldashboard.app

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PluginCancellationTest {
    @Test fun completedCallsReleaseTheirCancellationHooks() {
        val cancellation = PluginCancellation()
        var completedCanceled = false
        val detach = cancellation.onCancel { completedCanceled = true }
        detach()
        detach()
        var activeCanceled = false
        cancellation.onCancel { activeCanceled = true }
        cancellation.cancel()
        assertFalse(completedCanceled)
        assertTrue(activeCanceled)
    }
    @Test fun cancellationClosesExistingAndLateNativeCallsExactlyOnce() {
        val c=PluginCancellation();var before=0;var after=0
        c.onCancel{before++};c.cancel();c.cancel();c.onCancel{after++}
        assertEquals(1,before);assertEquals(1,after)
        try{c.check();fail()}catch(_:IllegalStateException){}
    }
    @Test fun stateCommitCannotEnterAfterCancellation() {
        val c=PluginCancellation();val cancelled=CountDownLatch(1);var wrote=false
        val t=Thread{c.cancel();cancelled.countDown()};t.start();assertTrue(cancelled.await(1,TimeUnit.SECONDS))
        try{synchronized(c){c.check();wrote=true};fail()}catch(_:IllegalStateException){}
        assertFalse(wrote);t.join()
    }
}
