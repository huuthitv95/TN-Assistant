package vn.tn.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Pure pause/read policy tests; Android AudioRecord and the listener thread are not exercised here. */
class TnWakeOutputPausePolicyTest {
    @Test
    fun anInFlightReadReturningAfterPauseIsDiscardedBeforeProcessing() {
        val pauses = TnWakeOutputPauseLeases()
        val readStarted = CountDownLatch(1)
        val allowReadReturn = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val processed = AtomicBoolean(false)
        val read = Thread {
            readStarted.countDown()
            check(allowReadReturn.await(5, TimeUnit.SECONDS))
            if (!TnWakeReadPausePolicy.discardReturnedBlock(pauses.activeCount)) processed.set(true)
            returned.countDown()
        }
        read.start()
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        val lease = pauses.acquire()
        try {
            allowReadReturn.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertFalse("A block returned while paused must not reach wake/VAD/endpoint processing", processed.get())
        } finally {
            lease.close()
            read.join(5000)
        }
        assertFalse(TnWakeReadPausePolicy.discardReturnedBlock(pauses.activeCount))
    }

    @Test
    fun nestedPauseLeasesRemainActiveUntilEveryLeaseCloses() {
        val pauses = TnWakeOutputPauseLeases()
        val first = pauses.acquire()
        val second = pauses.acquire()

        assertEquals(2, pauses.activeCount)
        first.close()
        first.close()
        assertEquals(1, pauses.activeCount)
        assertTrue(TnWakeReadPausePolicy.discardReturnedBlock(pauses.activeCount))

        second.close()
        second.close()
        assertEquals(0, pauses.activeCount)
        assertFalse(TnWakeReadPausePolicy.discardReturnedBlock(pauses.activeCount))
    }

    @Test
    fun pauseAcquiredDuringProcessingIsActivatedOnlyAfterTheBlockFinishes() {
        val pauses = TnWakeOutputPauseLeases()
        val processingStarted = CountDownLatch(1)
        val allowProcessingToFinish = CountDownLatch(1)
        val processingFinished = CountDownLatch(1)
        val processed = AtomicBoolean(false)
        var admitted = false
        val read = Thread {
            admitted = pauses.withProcessing {
                processingStarted.countDown()
                check(allowProcessingToFinish.await(5, TimeUnit.SECONDS))
                processed.set(true)
            }
            processingFinished.countDown()
        }
        read.start()
        assertTrue(processingStarted.await(5, TimeUnit.SECONDS))
        val lease = pauses.acquire()
        try {
            assertEquals(0, pauses.activeCount)
            allowProcessingToFinish.countDown()
            assertTrue(processingFinished.await(5, TimeUnit.SECONDS))
            assertTrue(admitted)
            assertTrue(processed.get())
            assertEquals(1, pauses.activeCount)
            assertFalse(pauses.withProcessing { processed.set(false) })
            assertTrue(processed.get())
        } finally {
            lease.close()
            read.join(5000)
        }
        assertEquals(0, pauses.activeCount)
    }
}
