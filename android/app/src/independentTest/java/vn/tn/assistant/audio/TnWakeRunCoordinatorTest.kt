package vn.tn.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TnWakeRunCoordinatorTest {
    @Test
    fun firstRequestReservesRunAndDuplicateActiveRequestIsIdempotent() {
        val coordinator = TnWakeRunCoordinator()

        val first = coordinator.request(owner = 11L)
        val duplicate = coordinator.request(owner = 11L)

        assertNotNull(first.run)
        assertFalse(first.alreadyActive)
        assertNull(duplicate.run)
        assertTrue(duplicate.alreadyActive)
        assertSame(first.run, first.run)
    }

    @Test
    fun differentOwnerWaitsForOldRunToCompleteBeforeReplacementIsReserved() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)

        val replacementRequest = coordinator.request(22L)
        assertNull(replacementRequest.run)
        assertFalse(replacementRequest.alreadyActive)
        assertTrue(coordinator.isCurrent(old))

        val replacement = requireNotNull(coordinator.complete(old))
        assertTrue(replacement.owner == 22L)
        assertFalse(coordinator.isCurrent(old))
        assertTrue(coordinator.isCurrent(replacement))
    }

    @Test
    fun staleCompletionCannotReleaseOrReplaceCurrentRun() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        coordinator.request(22L)
        val replacement = requireNotNull(coordinator.complete(old))

        assertNull(coordinator.complete(old))
        assertTrue(coordinator.isCurrent(replacement))
    }

    @Test
    fun invalidateDropsPendingReplacementAndOldCompletionCannotRestartIt() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        coordinator.request(22L)

        assertSame(old, coordinator.invalidate())
        assertFalse(coordinator.isCurrent(old))
        assertNull(coordinator.complete(old))

        val fresh = requireNotNull(coordinator.request(33L).run)
        assertTrue(fresh.owner == 33L)
        assertTrue(coordinator.isCurrent(fresh))
    }

    @Test
    fun sameOwnerRequestIsIdempotentWhileTheRunIsBeingStarted() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)

        val request = coordinator.request(11L)
        assertNull(request.run)
        assertTrue(request.alreadyActive)
        assertNull(coordinator.complete(old))
    }
}
