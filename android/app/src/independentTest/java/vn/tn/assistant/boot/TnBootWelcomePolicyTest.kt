package vn.tn.assistant.boot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TnBootWelcomePolicyTest {
    @Test fun enablingReminderRequiresEveryAndroidNotificationGate() {
        assertTrue(TnBootWelcomePolicy.canEnableReminder(32, false, true, true))
        assertTrue(TnBootWelcomePolicy.canEnableReminder(33, true, true, true))
        assertFalse(TnBootWelcomePolicy.canEnableReminder(33, false, true, true))
        assertFalse(TnBootWelcomePolicy.canEnableReminder(33, true, false, true))
        assertFalse(TnBootWelcomePolicy.canEnableReminder(33, true, true, false))
    }

    @Test fun deniedNotificationPermissionEventuallyOpensSystemSettings() {
        assertTrue(TnBootWelcomePolicy.shouldRequestNotificationPermission(false, false))
        assertTrue(TnBootWelcomePolicy.shouldRequestNotificationPermission(true, true))
        assertFalse(TnBootWelcomePolicy.shouldRequestNotificationPermission(true, false))
    }

    private fun allowed(
        optedIn: Boolean = true,
        greeting: Boolean = true,
        session: Boolean = true,
        notifications: Boolean = true,
        playedToday: Boolean = false,
        boot: String = "count:8",
        previous: String? = null,
    ) = TnBootWelcomePolicy.shouldNotify(optedIn, greeting, session, notifications, playedToday, boot, previous)

    @Test fun needsExplicitOptInGreetingSessionAndNotificationPermission() {
        assertTrue(allowed())
        assertFalse(allowed(optedIn = false))
        assertFalse(allowed(greeting = false))
        assertFalse(allowed(session = false))
        assertFalse(allowed(notifications = false))
        assertFalse(allowed(playedToday = true))
    }

    @Test fun notifiesAtMostOnceForTheSameBootAndAcceptsANewBoot() {
        assertFalse(allowed(previous = "count:8"))
        assertTrue(allowed(previous = "count:7"))
    }

    @Test fun missingBootIdentityNeverCreatesANUnboundedReminder() {
        assertFalse(allowed(boot = ""))
    }
}
