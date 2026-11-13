package vn.tn.assistant.driving

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import vn.tn.assistant.api.TnServiceFailure
import vn.tn.assistant.api.TnVoiceBackendClient
import vn.tn.assistant.auth.TnSessionStore
import java.util.concurrent.Executors

/** Explicit, foreground-only location session. No disk cache, microphone or TTS. */
internal class TnRoadMonitor(context: Context, private val show: (TnRoadMatcher.Result) -> Unit,
    private val authorization: (() -> Boolean)? = null,
    private val fetch: ((TnRoadMatcher.Fix, Long) -> TnRoadMatcher.Snapshot)? = null
) : AutoCloseable, LocationListener {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val session = TnSessionStore.get(app)
    private val owner = session.actionGeneration()
    private val backend = TnVoiceBackendClient(app)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var fix: TnRoadMatcher.Fix? = null
    private var snapshot: TnRoadMatcher.Snapshot? = null
    private var inFlight = false
    private var nextRequest = 0L
    private var failure: String? = null
    @Volatile private var closed = false
    private fun authorized() = authorization?.invoke() ?: session.ownsActions(owner)
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            when {
                !authorized() -> { show(TnRoadMatcher.Result("session_expired")); close(); return }
                !preciseAllowed(app) -> { show(TnRoadMatcher.Result("permission_required")); close(); return }
                !runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> {
                    fix = null; snapshot = null; backend.cancel(); show(TnRoadMatcher.Result("gps_disabled"))
                }
                else -> update()
            }
            main.postDelayed(this,1000)
        }
    }

    fun start() {
        check(!closed)
        if (!authorized()) { show(TnRoadMatcher.Result("session_expired")); close(); return }
        if (!preciseAllowed(app)) { show(TnRoadMatcher.Result("permission_required")); close(); return }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,this,Looper.getMainLooper())
            main.post(tick)
        } catch (_: SecurityException) { show(TnRoadMatcher.Result("permission_required")); close() }
        catch (_: IllegalArgumentException) { show(TnRoadMatcher.Result("gps_disabled")); close() }
    }

    override fun onLocationChanged(location: Location) {
        if (closed || !authorized()) return
        val accurateBearing = location.hasBearing() && (Build.VERSION.SDK_INT < 26 ||
            location.hasBearingAccuracy() && location.bearingAccuracyDegrees <= 20)
        val accurateSpeed = location.hasSpeed() && (Build.VERSION.SDK_INT < 26 ||
            location.hasSpeedAccuracy() && location.speedAccuracyMetersPerSecond <= 3)
        fix = TnRoadMatcher.Fix(location.latitude,location.longitude,
            if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
            if (accurateBearing) location.bearing.toDouble() else null,
            if (accurateSpeed) location.speed.toDouble() else null,location.elapsedRealtimeNanos/1000000)
        update()
    }

    private fun update() {
        if (closed) return
        val current = fix
        if (current == null) { show(TnRoadMatcher.Result("waiting_gps")); return }
        val now = SystemClock.elapsedRealtime()
        val result = TnRoadMatcher.match(current,snapshot ?: TnRoadMatcher.Snapshot(emptyList(),false,now),now,System.currentTimeMillis())
        if (result.state in setOf("location_unreliable","direction_unavailable")) { show(result); return }
        show(if (snapshot == null) TnRoadMatcher.Result(failure ?: "loading") else result)
        if (inFlight || now < nextRequest || !authorized()) return
        inFlight = true; nextRequest = now+5000
        worker.execute {
            try {
                if (closed || !authorized()) return@execute
                val data = fetch?.invoke(current,now) ?: TnRoadMatcher.parse(
                    backend.json("/v1/driving/area/speedlimit",JSONObject()
                        .put("lat",current.lat).put("lon",current.lon).put("bearing",current.bearing).put("radius_m",150)),now)
                val bounded=data.copy(center=TnRoadMatcher.Point(current.lat,current.lon),radius=150.0)
                main.post { if (!closed && authorized()) { snapshot=bounded; failure=null; update() } }
            } catch (error: Exception) {
                val reason = when {
                    error is TnServiceFailure && error.statusCode == 403 -> "premium_required"
                    error is TnServiceFailure && error.statusCode == 401 -> "session_expired"
                    error is TnServiceFailure && error.errorCode == "DRIVING_DISABLED" -> "driving_disabled"
                    else -> "network_unavailable"
                }
                main.post {
                    if (!closed && authorized()) {
                        snapshot=null; failure=reason; show(TnRoadMatcher.Result(reason))
                        nextRequest=SystemClock.elapsedRealtime()+15000
                        if (reason in setOf("premium_required","session_expired","driving_disabled")) close()
                    }
                }
            } finally { main.post { inFlight=false } }
        }
    }

    override fun onProviderDisabled(provider: String) { if (!closed) { fix=null; snapshot=null; show(TnRoadMatcher.Result("gps_disabled")) } }
    override fun onProviderEnabled(provider: String) { if (!closed) { fix=null; show(TnRoadMatcher.Result("waiting_gps")) } }
    @Deprecated("Legacy location callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    // Lint cannot resolve removeUpdates' versioned permission annotation. API
    // 21/22 is checked below; API 23+ permits removal after permission revocation.
    // Catch the revocation race and always cancel the remaining session resources.
    @android.annotation.SuppressLint("MissingPermission")
    override fun close() {
        if (closed) return
        closed=true; main.removeCallbacksAndMessages(null)
        // Permission may have been revoked since start; cleanup must still finish.
        if (Build.VERSION.SDK_INT >= 23 || ContextCompat.checkSelfPermission(app,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { manager.removeUpdates(this) } catch (_: SecurityException) { }
        }
        backend.cancel(); worker.shutdownNow(); fix=null; snapshot=null
    }

    companion object {
        fun preciseAllowed(context: Context) = ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    }
}
