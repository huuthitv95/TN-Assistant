package vn.tn.assistant.boot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TnBootWelcomePolicyTest {
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
