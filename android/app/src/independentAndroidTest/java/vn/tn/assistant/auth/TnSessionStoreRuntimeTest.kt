package vn.tn.assistant.auth

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.ui.TnIsolatedActivityPreferences
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TnSessionStoreRuntimeTest {
    @Test fun slowRefreshNeverBlocksLogoutOrRestoresItsOldCredentials() = retireSlowRefresh(false)
    @Test fun slowRefreshNeverBlocksAccountReplacementOrOverwritesTheNewLogin() = retireSlowRefresh(true)

    private fun retireSlowRefresh(replace: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TnIsolatedActivityPreferences(context).use {
            val store = TnSessionStore.get(context)
            fun session(access: String) = JSONObject().put("access_token", access).put("refresh_token", "$access-refresh")
            store.accept(session("TEST-old"))
            val owner = store.actionGeneration()
            val networkStarted = CountDownLatch(1)
            val releaseNetwork = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val refreshWorker = Thread {
                try {
                    store.refreshAfterUnauthorized("TEST-old") {
                        networkStarted.countDown()
                        check(releaseNetwork.await(5, TimeUnit.SECONDS))
                        session("TEST-stale-rotation")
                    }
                    failures += AssertionError("An old refresh must not restore a retired login")
                } catch (_: IOException) { }
                catch (error: Throwable) { failures += error }
                finally { finished.countDown() }
            }
            val invalidated = CountDownLatch(1)
            val invalidator = Thread {
                try { if (replace) store.accept(session("TEST-next")) else store.clear() }
                catch (error: Throwable) { failures += error }
                finally { invalidated.countDown() }
            }
            try {
                refreshWorker.start()
                assertTrue(networkStarted.await(2, TimeUnit.SECONDS))
                invalidator.start()
                assertTrue("Logout/login must not wait for network I/O under a session monitor", invalidated.await(750, TimeUnit.MILLISECONDS))
                assertFalse(store.ownsActions(owner))
            } finally {
                releaseNetwork.countDown()
                refreshWorker.join(3000)
                if (invalidator.state != Thread.State.NEW) invalidator.join(3000)
            }
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
            if (replace) assertEquals("TEST-next", store.accessToken) else assertFalse(store.hasSession())
        }
    }
    @Test fun actionsRemainBoundToTheirLoginAcrossRotationLogoutAndAccountReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TnIsolatedActivityPreferences(context).use {
            val store = TnSessionStore.get(context)
            fun session(access: String) = JSONObject().put("access_token", access).put("refresh_token", "$access-refresh")
            store.accept(session("TEST-owner-a"))
            val owner = store.actionGeneration()
            assertTrue(store.ownsActions(owner))
            store.refreshAfterUnauthorized("TEST-owner-a") { session("TEST-owner-a-rotated") }
            assertTrue("Token rotation is the same login", store.ownsActions(owner))
            store.clear()
            assertFalse("Logout retires outstanding actions", store.ownsActions(owner))
            store.accept(session("TEST-owner-b"))
            assertFalse(store.ownsActions(owner))
            val next = store.actionGeneration()
            assertTrue(store.ownsActions(next))
            context.getSharedPreferences("tn-session-v1", 0).edit().clear().commit()
            assertFalse("External preference reset retires cached actions", store.ownsActions(next))
        }
    }
    @Test fun simultaneousActivityAndServiceRefreshRotateOnlyOnceAndNeverPersistPlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TnIsolatedActivityPreferences(context).use {
            val store = TnSessionStore.get(context)
            val prefs = context.getSharedPreferences("tn-session-v1", 0)
            val token = "fixture-access-before"
            val refresh = "fixture-refresh-before"
            store.accept(JSONObject().put("access_token", token).put("refresh_token", refresh))
            assertFalse(prefs.all.values.any { it == token || it == refresh })
            val calls = AtomicInteger(0)
            val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val begin = CountDownLatch(1); val finished = CountDownLatch(2)
            repeat(2) {
                Thread {
                    try {
                        begin.await()
                        assertEquals("fixture-access-after", store.refreshAfterUnauthorized(token) { supplied ->
                            assertEquals(refresh, supplied); calls.incrementAndGet(); Thread.sleep(30)
                            JSONObject().put("access_token", "fixture-access-after").put("refresh_token", "fixture-refresh-after")
                        })
                    } catch (error: Throwable) { errors += error } finally { finished.countDown() }
                }.start()
            }
            begin.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(1, calls.get())
            val encrypted = prefs.getString("refresh_ciphertext", null)
            try {
                store.refreshAfterUnauthorized("fixture-access-after") { throw IOException("Temporary network outage") }
                fail("A failed refresh must be surfaced")
            } catch (_: IOException) { }
            assertEquals("A transient outage must not destroy the last encrypted refresh", encrypted, prefs.getString("refresh_ciphertext", null))
            assertEquals("fixture-access-after", store.accessToken)
            prefs.edit().clear().commit()
            assertFalse("Clearing session storage invalidates any process cache", store.hasSession())
            assertNull(store.accessToken)
        }
    }
}
