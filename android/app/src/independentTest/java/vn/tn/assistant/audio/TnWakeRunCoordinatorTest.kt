package vn.tn.assistant.audio

import org.junit.Assert.assertEquals
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
    fun sameServiceDifferentOwnerQueuesReplacementAndOldCompletionPromotesIt() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(owner = 11L, service = 7L).run)
        assertTrue(coordinator.registerWorker(old))

        val request = coordinator.request(owner = 22L, service = 7L)
        assertNull(request.run)
        assertFalse(request.alreadyActive)

        val replacement = requireNotNull(coordinator.complete(old))
        assertTrue(replacement.owner == 22L)
        assertTrue(replacement.service == 7L)
        assertTrue(coordinator.isCurrent(replacement))
    }

    @Test
    fun differentServiceSameOwnerStillQueuesReplacement() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(owner = 11L, service = 7L).run)
        assertTrue(coordinator.registerWorker(old))

        val request = coordinator.request(owner = 11L, service = 8L)
        assertNull(request.run)
        assertFalse(request.alreadyActive)
        assertTrue(coordinator.isCurrent(old))
    }

    @Test
    fun targetServiceCanAbandonUnclaimedReplacementWithoutOriginDoingSo() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(owner = 11L, service = 7L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(owner = 22L, service = 8L)
        val replacement = requireNotNull(coordinator.complete(old))

        assertTrue(coordinator.abandonReplacement(replacement, 8L).abandoned)
        assertFalse(coordinator.isCurrent(replacement))
        assertTrue(coordinator.noWorkerRemains())
    }

    @Test
    fun supersedingReservationCannotBeAbandonedByOldOrigin() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(owner = 11L, service = 7L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(owner = 22L, service = 8L)
        val replacement = requireNotNull(coordinator.complete(old))
        coordinator.request(owner = 33L, service = 9L)

        assertFalse(coordinator.abandonReplacement(replacement, 7L).abandoned)
        assertTrue(coordinator.isCurrent(replacement))
    }

    @Test
    fun differentOwnerWaitsForOldRunToCompleteBeforeReplacementIsReserved() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(old))

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
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L)
        val replacement = requireNotNull(coordinator.complete(old))

        assertNull(coordinator.complete(old))
        assertTrue(coordinator.isCurrent(replacement))
    }

    @Test
    fun invalidatedOldRunCannotCompleteFreshReplacement() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L, service = 1L).run)
        assertSame(old, coordinator.invalidate())
        val fresh = requireNotNull(coordinator.request(33L, service = 2L).run)

        assertNull(coordinator.complete(old))
        assertTrue(coordinator.isCurrent(fresh))
        assertFalse(coordinator.noWorkerRemains())
    }

    @Test
    fun invalidateDropsPendingReplacementAndOldCompletionCannotRestartIt() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L)

        assertSame(old, coordinator.invalidate())
        assertFalse(coordinator.isCurrent(old))
        assertNull(coordinator.complete(old))

        val fresh = requireNotNull(coordinator.request(33L).run)
        assertTrue(fresh.owner == 33L)
        assertTrue(coordinator.isCurrent(fresh))
    }

    @Test
    fun rejectedReplacementLeavesNoWorkerForCompletionPublication() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L)
        val replacement = requireNotNull(coordinator.complete(old))

        assertFalse(coordinator.noWorkerRemains())
        assertNull(coordinator.rejectReplacement(replacement))
        assertTrue(coordinator.noWorkerRemains())
        assertNull(coordinator.complete(old))
    }

    @Test
    fun rejectingReplacementPromotesNewerPendingOwner() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L)
        val replacement = requireNotNull(coordinator.complete(old))
        coordinator.request(33L)

        val newerReplacement = requireNotNull(coordinator.rejectReplacement(replacement))
        assertTrue(newerReplacement.owner == 33L)
        assertTrue(coordinator.isCurrent(newerReplacement))
        assertFalse(coordinator.noWorkerRemains())

        assertNull(coordinator.rejectReplacement(newerReplacement))
        assertTrue(coordinator.noWorkerRemains())
        assertNull(coordinator.complete(old))
    }

    @Test
    fun staleCompletionCannotPublishAfterReplacementRejection() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L)
        val replacement = requireNotNull(coordinator.complete(old))
        assertNull(coordinator.complete(old))
        assertTrue(coordinator.isCurrent(replacement))
        assertNull(coordinator.rejectReplacement(old))
        assertTrue(coordinator.isCurrent(replacement))
    }

    @Test
    fun staleReplacementCallbackCannotRejectFreshRunAfterInvalidation() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L)
        val candidate = requireNotNull(coordinator.complete(old))

        assertSame(candidate, candidate)
        assertSame(candidate, coordinator.invalidate())
        val fresh = requireNotNull(coordinator.request(33L).run)

        // Service.startReplacement's current-run guard makes this stale callback
        // a no-op; rejectReplacement must likewise never affect the fresh run.
        assertFalse(coordinator.isCurrent(candidate))
        assertTrue(coordinator.isCurrent(fresh))
        assertNull(coordinator.rejectReplacement(candidate))
        assertTrue(coordinator.isCurrent(fresh))
        assertFalse(coordinator.noWorkerRemains())
    }

    @Test
    fun idleWaitsForInvalidatedWorkerToFinish() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.invalidate(old)
        assertFalse(coordinator.noWorkerRemains())
        assertNull(coordinator.finish(old))
        assertTrue(coordinator.noWorkerRemains())
    }

    @Test
    fun staleWorkerFinishCannotMakeFreshRunIdle() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.invalidate(old)
        val fresh = requireNotNull(coordinator.request(33L).run)
        assertFalse(coordinator.noWorkerRemains())
        assertNull(coordinator.finish(old))
        assertFalse(coordinator.noWorkerRemains())
        assertTrue(coordinator.isCurrent(fresh))
    }

    @Test
    fun finishWithoutReplacementRemovesWorkerAndReturnsNull() {
        val coordinator = TnWakeRunCoordinator()
        val run = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(run))

        assertNull(coordinator.finish(run))
        assertTrue(coordinator.noWorkerRemains())
    }

    @Test
    fun destroyedReplacementOriginCannotDropLiveTargetReservation() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L, service = 1L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L, service = 2L)
        val replacement = requireNotNull(coordinator.complete(old))

        assertFalse(coordinator.abandonReplacement(replacement, 1L).abandoned)
        assertTrue(coordinator.isCurrent(replacement))
        assertTrue(coordinator.abandonReplacement(replacement, 2L).abandoned)
        assertTrue(coordinator.noWorkerRemains())
    }

    @Test
    fun destroyedTargetPromotesPendingRequestToAReplacement() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L, service = 1L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L, service = 2L)
        val candidate = requireNotNull(coordinator.complete(old))
        coordinator.request(33L, service = 3L)

        val promoted = coordinator.abandonReplacement(candidate, 2L).promoted
        assertNotNull(promoted)
        assertEquals(33L, promoted?.owner)
        assertEquals(3L, promoted?.service)
        assertTrue(coordinator.isCurrent(requireNotNull(promoted)))
    }

    @Test
    fun nullRunTargetCleanupCannotAbandonCandidateOwnedByAnotherTarget() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L, service = 1L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L, service = 2L)
        val candidate = requireNotNull(coordinator.complete(old))

        assertNull(coordinator.currentReplacementFor(9L))
        assertFalse(coordinator.abandonReplacement(candidate, 9L).abandoned)
        assertTrue(coordinator.isCurrent(candidate))
    }

    @Test
    fun abortingCurrentReservationPromotesPendingReplacement() {
        val coordinator = TnWakeRunCoordinator()
        val first = requireNotNull(coordinator.request(11L, service = 1L).run)
        coordinator.request(22L, service = 2L)

        val replacement = requireNotNull(coordinator.rejectReplacement(first))

        assertTrue(replacement.owner == 22L)
        assertTrue(coordinator.isCurrent(replacement))
        assertFalse(coordinator.isCurrent(first))
        assertFalse(coordinator.noWorkerRemains())
    }

    @Test
    fun abortingCurrentReservationWithoutPendingLeavesCoordinatorIdle() {
        val coordinator = TnWakeRunCoordinator()
        val run = requireNotNull(coordinator.request(11L, service = 1L).run)

        assertNull(coordinator.rejectReplacement(run))
        assertTrue(coordinator.noWorkerRemains())
    }

    @Test
    fun claimedReplacementNoLongerBelongsToItsOriginalService() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L, service = 1L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L, service = 2L)
        val replacement = requireNotNull(coordinator.complete(old))
        assertTrue(coordinator.claimReplacement(replacement))

        assertFalse(coordinator.abandonReplacement(replacement, 1L).abandoned)
        assertTrue(coordinator.isCurrent(replacement))
    }

    @Test
    fun duplicateWorkerRegistrationIsIdempotent() {
        val coordinator = TnWakeRunCoordinator()
        val run = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(run))
        assertFalse(coordinator.registerWorker(run))
        assertFalse(coordinator.noWorkerRemains())
        assertNull(coordinator.finish(run))
        assertTrue(coordinator.noWorkerRemains())
    }

    @Test
    fun equalButDistinctRunCannotUnregisterOrFinishTheWorker() {
        val coordinator = TnWakeRunCoordinator()
        val run = requireNotNull(coordinator.request(11L, service = 7L).run)
        val equalValue = TnWakeRunCoordinator.Run(run.token, run.owner, run.service)
        assertTrue(run == equalValue)
        assertTrue(coordinator.registerWorker(run))

        coordinator.unregisterWorker(equalValue)
        assertFalse(coordinator.noWorkerRemains())
        assertNull(coordinator.finish(equalValue))
        assertFalse(coordinator.noWorkerRemains())
        assertNull(coordinator.finish(run))
        assertTrue(coordinator.noWorkerRemains())
    }

    @Test
    fun sameOwnerRequestRetriesUnclaimedReplacementReservation() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L, service = 1L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(22L, service = 2L)
        val candidate = requireNotNull(coordinator.complete(old))

        val retry = coordinator.request(22L, service = 2L)
        assertSame(candidate, retry.run)
        assertFalse(retry.alreadyActive)
        assertTrue(coordinator.beginStart(candidate, 2L))
        assertTrue(coordinator.request(22L, service = 2L).alreadyActive)
    }

    @Test
    fun sameOwnerInitialReservationRemainsIdempotentUntilWorkerStarts() {
        val coordinator = TnWakeRunCoordinator()
        val first = requireNotNull(coordinator.request(11L, service = 1L).run)

        val duplicate = coordinator.request(11L, service = 1L)
        assertNull(duplicate.run)
        assertTrue(duplicate.alreadyActive)
        assertFalse(coordinator.beginStart(first, 2L))
        assertTrue(coordinator.isCurrent(first))
    }

    @Test
    fun repeatedMissingTargetPromotionCanBeAbandonedWithoutLeavingCurrentReservation() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(1L, service = 10L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(2L, service = 20L)
        val first = requireNotNull(coordinator.complete(old))
        coordinator.request(3L, service = 30L)

        val second = coordinator.abandonReplacement(first, 20L).promoted
        assertNotNull(second)
        val third = coordinator.abandonReplacement(requireNotNull(second), 30L)
        assertTrue(third.abandoned)
        assertNull(third.promoted)
        assertTrue(coordinator.noWorkerRemains())
    }

    @Test
    fun equalButDistinctReplacementCannotRetryOrStartReservation() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(1L, service = 10L).run)
        assertTrue(coordinator.registerWorker(old))
        coordinator.request(2L, service = 20L)
        val candidate = requireNotNull(coordinator.complete(old))
        val equal = TnWakeRunCoordinator.Run(candidate.token, candidate.owner, candidate.service)

        val retry = coordinator.request(2L, service = 20L)
        assertSame(candidate, retry.run)
        assertFalse(coordinator.beginStart(equal, 20L))
        assertTrue(coordinator.isCurrent(candidate))
    }

    @Test
    fun sameOwnerRequestIsIdempotentWhileTheRunIsBeingStarted() {
        val coordinator = TnWakeRunCoordinator()
        val old = requireNotNull(coordinator.request(11L).run)
        assertTrue(coordinator.registerWorker(old))

        val request = coordinator.request(11L)
        assertNull(request.run)
        assertTrue(request.alreadyActive)
        assertNull(coordinator.complete(old))
    }
}
