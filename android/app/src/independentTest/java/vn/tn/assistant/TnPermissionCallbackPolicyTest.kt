package vn.tn.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TnPermissionCallbackPolicyTest {
    @Test
    fun delayedLocationResultAfterAccountReplacementIsIgnored() {
        val pending = TnPermissionOperation(owner = 11L, revision = 4L)

        assertFalse(
            TnPermissionCallbackPolicy.accepts(
                pending,
                currentOwner = 22L,
                currentRevision = 4L,
                activityValid = true,
                granted = true,
            ),
        )
    }

    @Test
    fun aNewCommandRevisionInvalidatesAnOlderLocationRequest() {
        val pending = TnPermissionOperation(owner = 11L, revision = 4L)

        assertFalse(
            TnPermissionCallbackPolicy.accepts(
                pending,
                currentOwner = 11L,
                currentRevision = 5L,
                activityValid = true,
                granted = true,
            ),
        )
    }

    @Test
    fun delayedBootWelcomeResultAfterAccountReplacementIsIgnored() {
        val pending = TnPermissionOperation(owner = 11L, revision = 8L)

        assertFalse(
            TnPermissionCallbackPolicy.accepts(
                pending,
                currentOwner = 22L,
                currentRevision = 9L,
                activityValid = true,
                granted = true,
            ),
        )
    }

    @Test
    fun currentGrantedResultIsAcceptedButDeniedOrInvalidActivityIsNot() {
        val pending = TnPermissionOperation(owner = 11L, revision = 3L)

        assertTrue(TnPermissionCallbackPolicy.accepts(pending, 11L, 3L, true, true))
        assertFalse(TnPermissionCallbackPolicy.accepts(pending, 11L, 3L, true, false))
        assertFalse(TnPermissionCallbackPolicy.accepts(pending, 11L, 3L, false, true))
    }
}
