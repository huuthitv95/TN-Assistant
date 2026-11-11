package vn.tn.assistant.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TnRecordingSessionGateTest {
    @Test fun rapidStopRestartWaitsForTheOldCaptureToFinishCleanup() {
        val gate = TnRecordingSessionGate()
        val old = requireNotNull(gate.begin())
        assertTrue(gate.isActive)
        gate.cancel()
        assertFalse(old.isActive)
        assertFalse(gate.isActive)
        assertNull("Cancellation does not release a recorder still owned by its worker", gate.begin())
        old.close()
        val next = requireNotNull(gate.begin())
        assertTrue(next.isActive)
        next.close()
    }

    @Test fun staleCloseCannotReleaseOrCancelTheNewerCapture() {
        val gate = TnRecordingSessionGate()
        val old = requireNotNull(gate.begin())
        old.close()
        val next = requireNotNull(gate.begin())
        old.close()
        assertTrue(next.isActive)
        assertTrue(gate.isActive)
        assertNull("An old close cannot make the new capture's slot reusable", gate.begin())
        next.close()
        gate.cancel()
        requireNotNull(gate.begin()).close()
    }

    @Test fun cancellationDuringBlockedInitializationCannotStartRecording() {
        val gate = TnRecordingSessionGate()
        val capture = requireNotNull(gate.begin())
        val initializing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val starts = AtomicInteger()
        val error = AtomicReference<Throwable?>()
        Thread {
            try {
                initializing.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                if (capture.isActive) starts.incrementAndGet()
            } catch (failure: Throwable) { error.set(failure) }
            finally { capture.close(); finished.countDown() }
        }.start()
        try {
            assertTrue(initializing.await(5, TimeUnit.SECONDS))
            gate.cancel()
            assertNull(gate.begin())
        } finally { resume.countDown() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertNull(error.get())
        assertEquals(0, starts.get())
        requireNotNull(gate.begin()).close()
    }

    @Test fun cancellationDuringWarmUpDoesNotCreateAReplacementRecorder() {
        val gate = TnRecordingSessionGate()
        val capture = requireNotNull(gate.begin())
        val warming = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val created = AtomicInteger()
        val released = AtomicInteger()
        val returned = AtomicInteger()
        val error = AtomicReference<Throwable?>()
        Thread {
            try {
                TnRecorderStartupPolicy().start(true,
                    create = { created.incrementAndGet() }, begin = {}, discard = { released.incrementAndGet() },
                    active = { capture.isActive }, settle = {
                        warming.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    })
                returned.incrementAndGet()
            } catch (_: InterruptedException) {
                // Cancellation is a terminal capture outcome, not a replacement start.
            } catch (failure: Throwable) { error.set(failure) }
            finally { capture.close(); finished.countDown() }
        }.start()
        try {
            assertTrue(warming.await(5, TimeUnit.SECONDS))
            gate.cancel()
            assertFalse(capture.isActive)
            assertNull("Warm-up cleanup retains ownership", gate.begin())
        } finally { resume.countDown() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertNull(error.get())
        assertEquals(1, created.get())
        assertEquals(1, released.get())
        assertEquals(0, returned.get())
        requireNotNull(gate.begin()).close()
    }
}
