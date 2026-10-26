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

    @org.junit.Test fun parentAndChildCancellationDoNotHoldCallbacksUnderMonitor() {
        val parent=PluginCancellation();val child=PluginCancellation()
        val parentEntered=java.util.concurrent.CountDownLatch(1);val childEntered=java.util.concurrent.CountDownLatch(1)
        val detach=parent.onCancel { parentEntered.countDown();check(childEntered.await(2,java.util.concurrent.TimeUnit.SECONDS));child.cancel() }
        child.onCancel { childEntered.countDown();check(parentEntered.await(2,java.util.concurrent.TimeUnit.SECONDS));detach() }
        val a=Thread{parent.cancel()}.apply{isDaemon=true};val b=Thread{child.cancel()}.apply{isDaemon=true}
        a.start();b.start();a.join(3000);b.join(3000)
        org.junit.Assert.assertFalse("parent cancellation hung",a.isAlive)
        org.junit.Assert.assertFalse("child cancellation hung",b.isAlive)
    }
}
