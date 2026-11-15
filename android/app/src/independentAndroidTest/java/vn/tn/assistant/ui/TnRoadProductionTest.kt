package vn.tn.assistant.ui

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import vn.tn.assistant.driving.TnRoadMatcher
import vn.tn.assistant.driving.TnRoadMonitor
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in live HTTPS test: private, temporary TN credentials supplied by the host.
 * GPS is simulated through LocationManager; auth, backend and rendering are real.
 * Never supplies a fetch/authorization override, calls speech or changes server config.
 */
class TnRoadProductionTest {
    @android.annotation.SuppressLint("MissingPermission")
    @Test fun systemGpsThroughProductionAuthorizationAndRoadDialog() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("roadProduction") == "true")
        check(BuildConfig.APPLICATION_ID == "vn.tn.assistant.preview")
        check(BuildConfig.API_BASE_URL.trimEnd('/') == "https://assistant.tnfastvn.com")
        check(Build.VERSION.SDK_INT >= 26 && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(TnRoadMonitor.preciseAllowed(context))
        val file = File(context.filesDir, "tn-road-production.private.json")
        val input = JSONObject(file.readText())
        check(file.delete())
        val login = input.getJSONObject("login")
        check(login.getString("username").matches(Regex("tn-road-qa-[a-f0-9]{16}-(basic|premium)")))
        val expected = input.getString("expected")
        check(expected in setOf("premium_required", "driving_disabled", "matched"))
        val query = input.getJSONObject("query")
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        fun auth(path: String, data: JSONObject): JSONObject {
            val request = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + path)
                .post(data.toString().toRequestBody("application/json".toMediaType())).build()
            return client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Live auth HTTP ${response.code}" }
                JSONObject(response.body!!.string())
            }
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val session = TnSessionStore.get(context)
        var registered = false
        var dialog: TnRoadDialog? = null
        var events = 0
        val latest = AtomicReference<TnRoadMatcher.Result>()
        fun location() = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = query.getDouble("lat"); longitude = query.getDouble("lon")
            accuracy = 5f; bearing = query.getDouble("bearing").toFloat(); speed = 10f
            bearingAccuracyDegrees = 1f; speedAccuracyMetersPerSecond = 1f
            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        TnIsolatedActivityPreferences(context).use {
            // Launch without a session first, so unrelated home/welcome flows cannot call providers.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    session.accept(auth("/v1/auth/login", login))
                    scenario.onActivity { activity ->
                        @Suppress("DEPRECATION")
                        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false,
                            true, true, true, android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE)
                        registered = true
                        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                        dialog = TnRoadDialog(activity) { show ->
                            TnRoadMonitor(activity, { result ->
                                events++; latest.set(result); show(result)
                            }).also { it.start() }
                        }.also { it.show() }
                    }
                    val deadline = SystemClock.elapsedRealtime() + 50000
                    while (latest.get()?.state != expected && SystemClock.elapsedRealtime() < deadline) {
                        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location())
                        SystemClock.sleep(500)
                    }
                    assertEquals("Live GPS/API state", expected, latest.get()?.state)
                    var before = 0
                    scenario.onActivity {
                        val root = dialog!!.window!!.decorView
                        val sign = root.findViewWithTag<TextView>("tnRoadSign")
                        val status = root.findViewWithTag<TextView>("tnRoadStatus")
                        if (expected == "matched") {
                            assertEquals(query.getString("road_id"), latest.get()!!.road!!.id)
                            assertEquals(query.getInt("speed").toString(), sign.text.toString())
                            assertEquals(View.VISIBLE, sign.visibility)
                        } else {
                            assertEquals(View.GONE, sign.visibility)
                            assertEquals(if (expected == "premium_required")
                                "Tài khoản chưa có quyền sử dụng dữ liệu giao thông." else
                                "Dữ liệu giao thông chưa được bật trên máy chủ TN.", status.text.toString())
                        }
                        dialog!!.dismiss(); before = events
                    }
                    manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location())
                    SystemClock.sleep(1200)
                    scenario.onActivity { assertEquals("Closed dialog received updates", before, events) }
                } finally {
                    instrumentation.runOnMainSync { dialog?.dismiss() }
                    if (registered) manager.removeTestProvider(LocationManager.GPS_PROVIDER)
                    try {
                        session.refreshToken?.let { auth("/v1/auth/logout", JSONObject().put("refresh_token", it)) }
                    } finally { session.clear(); client.dispatcher.cancelAll() }
                }
            }
        }
    }
}
