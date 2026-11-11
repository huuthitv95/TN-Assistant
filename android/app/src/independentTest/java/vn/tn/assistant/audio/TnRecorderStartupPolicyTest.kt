package vn.tn.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class TnRecorderStartupPolicyTest {
    @Test fun advancedWarmUpReleasesBeforeDelayAndReturnsOnlyReplacement() {
        val events = mutableListOf<String>()
        var created = 0
        val policy = TnRecorderStartupPolicy()
        val usable = policy.start(true, { ++created }, { events += "start:$it" },
            { events += "release:$it" }, { true }, { events += "wait:$it" })
        events += "read:$usable"
        assertEquals(listOf("start:1", "release:1", "wait:130", "start:2", "read:2"), events)
        val next = policy.start(true, { ++created }, { events += "start:$it" },
            { events += "release:$it" }, { true }, { error("No second warm-up") })
        assertEquals(3, next)
    }

    @Test fun cancellationDuringWarmUpNeverStartsAnotherRecorder() {
        var active = true
        var created = 0
        val released = mutableListOf<Int>()
        try {
            TnRecorderStartupPolicy().start(true, { ++created }, {}, { released += it },
                { active }, { active = false })
            org.junit.Assert.fail("Cancelled warm-up must not return a recorder")
        } catch (_: InterruptedException) { }
        assertEquals(1, created)
        assertEquals(listOf(1), released)
    }

    @Test fun failedStartReleasesRecorderWithoutConsumingSuccessfulStartMarker() {
        val policy = TnRecorderStartupPolicy()
        val released = mutableListOf<Int>()
        try {
            policy.start(true, { 1 }, { throw IllegalStateException("Cannot start") },
                { released += it }, { true }, { error("No warm-up before successful start") })
            org.junit.Assert.fail("A failed recorder cannot be returned")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(1), released)
        assertTrue(policy.requiresWarmRestartAfterStart(true))
    }

    @Test fun failedReplacementIsReleasedAndWarmUpIsNotRepeated() {
        val policy = TnRecorderStartupPolicy()
        var created = 0
        val released = mutableListOf<Int>()
        try {
            policy.start(true, { ++created }, { if (it == 2) throw IllegalStateException("Replacement failed") },
                { released += it }, { true }, {})
            org.junit.Assert.fail("A failed replacement cannot be returned")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(1, 2), released)
        assertFalse(policy.requiresWarmRestartAfterStart(true))
    }

    @Test fun advancedFirstStartWarmsOnceAndRecreatedRecorderDoesNotWarmAgain() {
        val policy = TnRecorderStartupPolicy()
        assertTrue(policy.requiresWarmRestartAfterStart(true))
        assertFalse(policy.requiresWarmRestartAfterStart(true))
        assertFalse(policy.requiresWarmRestartAfterStart(false))
    }

    @Test fun defaultFirstStartConsumesMarkerBeforeLaterAdvancedSelection() {
        val policy = TnRecorderStartupPolicy()
        assertFalse(policy.requiresWarmRestartAfterStart(false))
        assertFalse(policy.requiresWarmRestartAfterStart(true))
    }

    @Test fun concurrentFirstStartsCannotBothRequestWarmRestart() {
        val policy = TnRecorderStartupPolicy()
        val begin = CountDownLatch(1)
        val finished = CountDownLatch(12)
        val restarts = AtomicInteger(0)
        repeat(12) {
            Thread {
                try {
                    begin.await()
                    if (policy.requiresWarmRestartAfterStart(true)) restarts.incrementAndGet()
                } finally {
                    finished.countDown()
                }
            }.start()
        }
        begin.countDown()
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, restarts.get())
    }
}
