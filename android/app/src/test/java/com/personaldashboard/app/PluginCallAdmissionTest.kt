package com.personaldashboard.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class PluginCallAdmissionTest {
    @Test fun ownerQueueIsBoundedAndReleaseAllowsReplacement() {
        val owner="admission-"+java.util.UUID.randomUUID()
        val entered=CountDownLatch(4);val release=CountDownLatch(1);val completed=CountDownLatch(12)
        try {
            repeat(12){PluginCallAdmission.submit(owner,Runnable{entered.countDown();release.await(3,TimeUnit.SECONDS);completed.countDown()})}
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertThrows(RejectedExecutionException::class.java){PluginCallAdmission.submit(owner,Runnable{})}
            PluginCallAdmission.cancelQueued(owner)
            val replacement=CountDownLatch(1);PluginCallAdmission.submit(owner,Runnable{replacement.countDown()})
            release.countDown();assertTrue(replacement.await(3,TimeUnit.SECONDS))
        }finally{release.countDown();PluginCallAdmission.cancelQueued(owner)}
    }
    @Test fun closedPageRejectsNewWorkWithoutClosingOtherOwners() {
        val page=PluginBridgeExecutor();page.shutdownNow()
        assertThrows(RejectedExecutionException::class.java){page.execute{}}
        val other=PluginBridgeExecutor();val done=CountDownLatch(1)
        try{other.execute{done.countDown()};assertTrue(done.await(3,TimeUnit.SECONDS))}finally{other.shutdownNow()}
    }
}
