package vn.tn.assistant.actions

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.ui.TnIsolatedActivityPreferences
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises the foreground MapDirection adapter while intercepting Android's external launch. */
class TnMapDirectionRuntimeTest {
    @Test fun mainActivityLaunchesGoogleMapsHttpsAndRejectsOtherTargets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launches = LinkedBlockingQueue<Intent>()
        val mapUrl = "https://www.google.com/maps/dir/?api=1&destination=123+Main+Street"
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW && intent.data?.host?.endsWith("google.com") == true) {
                    launches.offer(Intent(intent))
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }

        instrumentation.addMonitor(monitor)
        try {
            TnIsolatedActivityPreferences(context).use {
                val activity = instrumentation.startActivitySync(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ) as MainActivity
                try {
                    val execute = MainActivity::class.java.getDeclaredMethod(
                        "executeDirectives",
                        JSONObject::class.java,
                        String::class.java,
                        String::class.java,
                        kotlin.jvm.functions.Function0::class.java,
                    ).apply { isAccessible = true }
                    fun dispatch(url: String) = JSONObject().put("directives", JSONArray().put(
                        JSONObject().put("type", "MapDirection").put("payload", JSONObject().put("url", url)),
                    ))

                    val accepted = execute.invoke(activity, dispatch(mapUrl), "", "", { true })
                    assertEquals(TnDirectiveExecutor.Summary(), accepted)
                    val launched = launches.poll(2, TimeUnit.SECONDS)
                    assertNotNull("MapDirection did not start Android ACTION_VIEW", launched)
                    assertEquals(Intent.ACTION_VIEW, launched!!.action)
                    assertEquals(Uri.parse(mapUrl), launched.data)

                    val rejected = listOf(
                        "http://www.google.com/maps/dir/?api=1",
                        "https://maps.google.com/maps/dir/?api=1",
                        "https://www.google.com.evil.example/maps/dir/?api=1",
                        "https://www.google.com/search?q=maps",
                    )
                    rejected.forEach { url ->
                        val summary = execute.invoke(activity, dispatch(url), "", "", { true }) as TnDirectiveExecutor.Summary
                        assertEquals("Unsafe or non-map URL was not rejected: $url", 1, summary.failed)
                        assertEquals("Unsafe or non-map URL was treated as unsupported: $url", 0, summary.unsupported)
                        assertNull("Rejected URL reached an external Android app: $url", launches.poll(100, TimeUnit.MILLISECONDS))
                    }
                    assertTrue("The intercepted Maps launch should not leave MainActivity", activity.hasWindowFocus())
                    assertFalse(launches.isNotEmpty())
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
