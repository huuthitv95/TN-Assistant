package vn.tn.assistant.actions

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.ui.TnIsolatedActivityPreferences

/** Real PackageManager lookup through the adapter, on the isolated SDK AVD. */
class TnExternalMediaAppRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun installedLauncherPackage(): String {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(query, 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != context.packageName && context.packageManager.getLaunchIntentForPackage(it) != null }
            ?: ""
    }

    @Test fun configuredSelectionProducesItsRealLauncherIntentForDispatch() {
        val packageName = installedLauncherPackage()
        org.junit.Assume.assumeTrue("An installed launcher app is required", packageName.isNotEmpty())
        TnIsolatedActivityPreferences(context).use {
            context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE).edit()
                .putString("music_package", packageName).commit()
            val launched = mutableListOf<Intent>()
            val details = JSONObject()
            val result = TnExternalMediaApp.execute(context, "PlayMusicApp",
                JSONObject().put("query", "Hoa Hai Duong"), details) { launched += it; true }
            assertEquals("success", result)
            val intent = launched.single()
            assertEquals(Intent.ACTION_MAIN, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
            assertEquals(packageName, intent.`package`)
            assertEquals(packageName, details.getString("media_app_package"))
            assertEquals("music", details.getString("media_app_kind"))
            assertTrue(details.getBoolean("intent_started"))
            assertTrue(details.getBoolean("interaction_required"))
            assertFalse(details.getBoolean("completion_verified"))
            assertTrue(details.getBoolean("query_present"))
            assertFalse("The receipt must not echo the spoken query", details.toString().contains("Hoa Hai Duong"))
        }
    }

    @Test fun missingOrUninstalledSelectionNeverClaimsAnIntentStarted() {
        TnIsolatedActivityPreferences(context).use {
            val prefs = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
            var launches = 0
            val missing = JSONObject()
            assertEquals("failed", TnExternalMediaApp.execute(context, "PlayRadioApp", JSONObject(), missing) { launches++; true })
            assertEquals("media_app_not_configured", missing.getString("reason"))
            assertFalse(missing.getBoolean("intent_started"))

            prefs.edit().putString("radio_package", "com.tn.missing.radio").commit()
            val uninstalled = JSONObject()
            assertEquals("failed", TnExternalMediaApp.execute(context, "PlayRadioApp", JSONObject(), uninstalled) { launches++; true })
            assertEquals("media_app_unavailable", uninstalled.getString("reason"))
            assertFalse(uninstalled.getBoolean("intent_started"))

            // A generic media_package is not a music choice and cannot stand in for one.
            prefs.edit().putString("music_package", "").putString("media_package", "com.spotify.music").commit()
            val stale = JSONObject()
            assertEquals("failed", TnExternalMediaApp.execute(context, "PlayMusicApp", JSONObject(), stale) { launches++; true })
            assertEquals("media_app_not_configured", stale.getString("reason"))
            assertFalse(stale.has("media_app_package"))
            assertEquals(0, launches)

            prefs.edit().putString("tv_package", context.packageName).commit()
            val self = JSONObject()
            assertEquals("failed", TnExternalMediaApp.execute(context, "PlayTVApp", JSONObject(), self) { launches++; true })
            assertEquals("media_app_invalid_package", self.getString("reason"))
            assertFalse(self.getBoolean("intent_started"))
            assertEquals(0, launches)
        }
    }

    @Test fun refusedLaunchIsFailedEvenWhenThePackageExists() {
        val packageName = installedLauncherPackage()
        org.junit.Assume.assumeTrue("An installed launcher app is required", packageName.isNotEmpty())
        TnIsolatedActivityPreferences(context).use {
            context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE).edit()
                .putString("tv_package", packageName).commit()
            val details = JSONObject()
            assertEquals("failed", TnExternalMediaApp.execute(context, "PlayTVApp", JSONObject(), details) { false })
            assertEquals("media_app_launch_refused", details.getString("reason"))
            assertFalse(details.getBoolean("intent_started"))
        }
    }
}
