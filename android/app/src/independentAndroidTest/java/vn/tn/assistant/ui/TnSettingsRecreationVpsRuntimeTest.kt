package vn.tn.assistant.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity

/** Opt-in actual VPS session: Android recreation must preserve the current Settings page. */
class TnSettingsRecreationVpsRuntimeTest {
    @Test fun authenticatedVpsSettingsSurviveActivityRecreationAndStillLoadAdminEntitlements() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnLiveUi") == "true")
        assertEquals("https://assistant.tnfastvn.com", BuildConfig.API_BASE_URL)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario, "The existing real VPS session must reach the home UI") { activity ->
                descendants(activity.window.decorView).any { it.contentDescription == "CÀI ĐẶT" }
            }
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).single { it.contentDescription == "CÀI ĐẶT" }.performClick()
            }
            await(scenario, "Settings must load the real Admin entitlement before recreation") { activity ->
                descendants(activity.window.decorView).any { it.contentDescription == "Premium" }
            }
            scenario.onActivity { activity ->
                val surface = descendants(activity.window.decorView).filterIsInstance<TnUserSettingsSurface>().single()
                surface.floating()
                assertEquals("Tiện ích nổi", surface.findViewWithTag<TextView>("tnSettingsHeading").text.toString())
                val state = Bundle()
                MainActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
                    .apply { isAccessible = true }.invoke(activity, state)
                assertEquals(setOf("selected_group", "detail", "scroll_y"), requireNotNull(state.getBundle("tn_settings_ui")).keySet())
            }
            scenario.recreate()
            await(scenario, "The authenticated detail must return after Android recreates the Activity") { activity ->
                descendants(activity.window.decorView).any { it.tag == "tnSettingsHeading" && (it as TextView).text.toString() == "Tiện ích nổi" }
            }
            // Account loading may finish after the page first appears. Returning
            // to the root must use the refreshed VPS response, not cached fixtures.
            scenario.onActivity { activity ->
                val surface = descendants(activity.window.decorView).filterIsInstance<TnUserSettingsSurface>().single()
                assertTrue(surface.back())
            }
            await(scenario, "The recreated settings must re-fetch the real Premium account") { activity ->
                descendants(activity.window.decorView).any { it.contentDescription == "Premium" }
            }
            scenario.onActivity { activity ->
                val surface = descendants(activity.window.decorView).filterIsInstance<TnUserSettingsSurface>().single()
                surface.accountDetails()
                assertFalse("The real Admin remains free of upgrade prompts after recreation",
                    descendants(surface).filterIsInstance<TextView>().any { "Nâng cấp" in it.text.toString() })
            }
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, message: String, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60000
        while (SystemClock.uptimeMillis() < deadline) {
            var matches = false
            scenario.onActivity { matches = predicate(it) }
            if (matches) return
            SystemClock.sleep(100)
        }
        fail(message)
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
