package vn.tn.assistant.ui

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.auth.TnSessionStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The floating plan is a short-lived display snapshot; it must never survive a different login. */
class TnFloatingAccountPlanRuntimeTest {
    private fun iso(epoch: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epoch))

    private fun isoFraction(epoch: Long, fraction: String): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epoch)) + "." + fraction + "Z"

    private fun me(kind: String, expires: Long? = null) = JSONObject().put(
        "account_plan",
        JSONObject().put("kind", kind).put("server_time", iso(System.currentTimeMillis()))
            .put("expires_at", expires?.let(::iso) ?: JSONObject.NULL),
    )

    @Test fun accountReplacementAndGenerationRetirementNeverKeepThePreviousPlan() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TnIsolatedActivityPreferences(context).use {
            val store = TnSessionStore.get(context)
            fun session(access: String) = JSONObject().put("access_token", access).put("refresh_token", "$access-refresh")
            store.accept(session("TEST-plan-account-a"))
            TnFloatingAccountPlan.update(context, me("premium", System.currentTimeMillis() + 86_400_000L))
            assertEquals("premium", TnFloatingAccountPlan.read(context)?.kind)

            // A second login replaces the display snapshot instead of keeping
            // the previous account's Premium label until the next refresh.
            store.accept(session("TEST-plan-account-b"))
            TnFloatingAccountPlan.clear(context)
            assertNull("Replacing the account retires its display plan", TnFloatingAccountPlan.read(context))

            TnFloatingAccountPlan.update(context, me("basic"))
            assertEquals("basic", TnFloatingAccountPlan.read(context)?.kind)

            // A worker that started before logout must not restore a plan.
            val owner = TnFloatingAccountPlan.generation()
            TnFloatingAccountPlan.clear(context)
            TnFloatingAccountPlan.update(context, me("premium", System.currentTimeMillis() + 86_400_000L), owner)
            assertNull("A retired generation cannot republish a display plan", TnFloatingAccountPlan.read(context))
            store.clear()
        }
    }

    @Test fun malformedOrExpiredServerPlansAreNotDisplayedAsPremium() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TnIsolatedActivityPreferences(context).use {
            val store = TnSessionStore.get(context)
            store.accept(JSONObject().put("access_token", "TEST-plan-malformed").put("refresh_token", "TEST-plan-malformed-refresh"))
            TnFloatingAccountPlan.update(context, JSONObject().put(
                "account_plan", JSONObject().put("kind", "premium").put("server_time", "not-a-time"),
            ))
            assertNull("Malformed server time must be rejected, not treated as Premium", TnFloatingAccountPlan.read(context))
            TnFloatingAccountPlan.update(context, JSONObject().put(
                "account_plan", JSONObject().put("kind", "premium")
                    .put("server_time", iso(System.currentTimeMillis())),
            ))
            assertNull("Premium without an expiry is not a valid display plan", TnFloatingAccountPlan.read(context))

            // An expired instant is demoted by the display layer itself.
            val expired = TnFloatingAccountPlan("premium", System.currentTimeMillis() - 1000, System.currentTimeMillis(), 0)
            assertEquals("basic", expired.effectiveKind(0))
            store.clear()
        }
    }

    @Test fun rfc3339NanoFractionsOfAnyPrecisionKeepValidPremiumPlans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TnIsolatedActivityPreferences(context).use {
            val store = TnSessionStore.get(context)
            store.accept(JSONObject().put("access_token", "TEST-plan-fraction").put("refresh_token", "TEST-plan-fraction-refresh"))
            val now = System.currentTimeMillis()
            for (fraction in listOf("1", "12", "123456789")) {
                TnFloatingAccountPlan.update(context, JSONObject().put(
                    "account_plan", JSONObject().put("kind", "premium")
                        .put("server_time", isoFraction(now, fraction))
                        .put("expires_at", isoFraction(now + 86_400_000L, fraction)),
                ))
                assertEquals("Premium timestamps with .$fraction must parse", "premium", TnFloatingAccountPlan.read(context)?.effectiveKind())
            }
            store.clear()
        }
    }
}
