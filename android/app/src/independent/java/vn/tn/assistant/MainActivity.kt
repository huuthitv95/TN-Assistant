package vn.tn.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Typeface
import android.widget.ImageView
import android.widget.CheckBox
import android.media.session.MediaSessionManager
import android.graphics.PixelFormat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import vn.tn.assistant.audio.TnOnboardingNarrator
import vn.tn.assistant.audio.TnRecorderStartupPolicy
import vn.tn.assistant.audio.TnRecordingSessionGate
import vn.tn.assistant.audio.TnWakeService
import vn.tn.assistant.audio.TnWakeUi
import vn.tn.assistant.auth.TnSessionStore
import vn.tn.assistant.api.TnSpeechStreamFallback
import vn.tn.assistant.api.TnVoiceBackendClient
import vn.tn.assistant.audio.TnSpeechAudioFocus
import vn.tn.assistant.audio.tn.WakePhrase
import vn.tn.assistant.ui.TnOnboardingSurface
import vn.tn.assistant.ui.ClickGate
import vn.tn.assistant.ui.TnZaloPairingSurface
import vn.tn.assistant.ui.TnZaloWebSurface
import vn.tn.assistant.ui.TnAssistantHomeSurface
import vn.tn.assistant.ui.TnHomeSuggestion
import vn.tn.assistant.ui.TnUserSettingsSurface
import vn.tn.assistant.ui.TnWelcomeAppMark
import vn.tn.assistant.ui.TnFloatingControlService
import vn.tn.assistant.auth.ZaloNavigationPolicy
import vn.tn.assistant.auth.ZaloCompletionPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** TN client preview with owner-approved original UI images; TN backend and independent native runtime. */
class MainActivity : Activity() {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()
    private val worker = Executors.newSingleThreadExecutor()
    private val welcomeWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val zaloTimers = Handler(Looper.getMainLooper())
    private val recording = TnRecordingSessionGate.process
    @Volatile private var foregroundCapture: TnRecordingSessionGate.Capture? = null
    private val preferences by lazy { getSharedPreferences("tn-session-v1", MODE_PRIVATE) }
    private val deviceId by lazy {
        preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).apply()
        }
    }
    private val sessionStore by lazy { TnSessionStore.get(this) }
    private val commandRevision = java.util.concurrent.atomic.AtomicLong()
    private val welcomeRevision = java.util.concurrent.atomic.AtomicLong()
    private var welcomeAttempt: Pair<Long, String>? = null
    private var welcomeLaunchEligible = false
    private var actionConfirmation: android.app.Dialog? = null
    private var contactChoiceDialog: AlertDialog? = null
    private class ActionPermissionRequest(
        val code: Int,
        val permissions: Set<String>,
        val done: CountDownLatch = CountDownLatch(1),
        @Volatile var allowed: Boolean = false,
        @Volatile var abandoned: Boolean = false,
        var launched: Boolean = false,
    )
    @Volatile private var actionPermissionRequest: ActionPermissionRequest? = null
    private val updateInProgress = AtomicBoolean(false)
    @Volatile private var pendingReleaseInstall: TnAppRelease? = null
    private val accessToken: String? get() = sessionStore.accessToken
    private val refreshToken: String? get() = sessionStore.refreshToken
    private var wakePermissionPending = false
    private var wakeStartWhenResumed = false
    private var wakeRevision = 0
    private val wakeUi = object : TnWakeUi.Listener {
        override fun state(message: String, listening: Boolean) {
            if (!resumed || isFinishing || isDestroyed) return
            status(message); homeSurface?.setListening(listening)
            userSettings?.wakeStatus()
        }
        override fun transcript(text: String) { if (resumed) commandView?.setText(text) }
        override fun launch(intent: Intent): Boolean {
            if (!resumed || isFinishing || isDestroyed) return false
            return try { startActivity(intent); true } catch (_: RuntimeException) { false }
        }
        override fun confirm(): Boolean = showPendingConfirmation()
    }
    private var statusView: TextView? = null
    private var commandView: EditText? = null
    private var homeSurface: TnAssistantHomeSurface? = null
    private var homePanelOpen = false
    private var guideSurface: vn.tn.assistant.ui.TnAssistantGuideSurface? = null
    private var userSettings: TnUserSettingsSurface? = null
    private var pendingText: String? = null
    @Volatile private var audioRecorder: AudioRecord? = null
    private var setupStep: Int? = null
    private var setupSurface: TnOnboardingSurface? = null
    private var authenticationBusy = false
    private var authPageRevision = 0
    private var authSurface: TnOnboardingSurface? = null
    private var permissionSettingsStep: Int? = null
    private var returnFromSettingsToHome = false
    private var returnToUserSettingsFromPermission = false
    private var restoredUserSettings: Bundle? = null
    private val narrator by lazy { TnOnboardingNarrator(this) }
    @Volatile private var resumed = false
    private var narrationCue: String? = null
    private val welcomeClickGate = ClickGate()
    private var authMode = "choice"
    private var zaloFromSettings = false
    private var zaloAttempt: JSONObject? = null
    @Volatile private var zaloRevision = 0
    private var pollingZaloRevision: Int? = null
    @Volatile private var zaloCompletingRevision: Int? = null
    private var zaloPresentation = "web"
    @Volatile private var zaloExpiresAt = 0L
    @Volatile private var zaloTerminal = false
    private var zaloDenied = false
    private var zaloFailureMessage: String? = null
    private var zaloBrowser: WebView? = null
    private var zaloWebSurface: TnZaloWebSurface? = null
    private var zaloEndDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingReleaseInstall = runCatching {
            val installedVersionCode = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
            TnAppRelease.restoreSavedState(
                savedInstanceState?.getString(PENDING_RELEASE_INSTALL_STATE),
                packageName,
                installedVersionCode,
            )
        }.getOrNull()
        val launchSource = activationSource(intent)
        welcomeLaunchEligible = launchSource == "launcher"
        restoredUserSettings = savedInstanceState?.getBundle("tn_settings_ui")
        window.statusBarColor = Color.rgb(0, 8, 23)
        window.navigationBarColor = Color.rgb(0, 8, 23)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (!sessionStore.hasSession()) {
            if (setupCompleted()) renderAuth() else renderSetup(savedSetupStep())
            return
        }
        showLoading("Đang kiểm tra phiên TN…")
        worker.execute {
            try {
                sessionStore.restore { api("/v1/auth/refresh", JSONObject().put("refresh_token", it), null) }
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    if (setupCompleted()) {
                        val settingsState = restoredUserSettings
                        restoredUserSettings = null
                        renderHome()
                        if (settingsState != null) renderSettings(settingsState) else handleFloatingIntent()
                    } else renderSetup(savedSetupStep())
                }
                recordEvent("app_opened", launchSource)
            } catch (error: Exception) {
                // A transient network failure must not discard a saved login.
                if (error is TNApiException && error.statusCode in listOf(401, 403)) clearSession()
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    if (error is TNApiException && error.statusCode in listOf(401, 403)) {
                        if (setupCompleted()) renderAuth("Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục.")
                        else renderSetup(savedSetupStep())
                    } else renderSessionRetry()
                }
            }
        }
    }

    private fun api(path: String, payload: JSONObject?, token: String? = accessToken, method: String? = null): JSONObject {
        try { return apiOnce(path, payload, token, method) } catch (error: TNApiException) {
            if (error.statusCode != 401 || token == null || path.startsWith("/v1/auth/")) throw error
            val owner = sessionStore.actionGeneration()
            val next = try {
                sessionStore.refreshAfterUnauthorized(token) { apiOnce("/v1/auth/refresh", JSONObject().put("refresh_token", it), null, "POST") }
            } catch (refreshError: TNApiException) {
                if (refreshError.statusCode != 401 && refreshError.statusCode != 403) throw refreshError
                main.post {
                    if (isFinishing || isDestroyed || sessionStore.actionGeneration() != owner || accessToken != token) return@post
                    clearSession()
                    if (setupCompleted()) renderAuth("Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục.")
                    else renderSetup(savedSetupStep())
                }
                throw IOException("Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục.")
            }
            return apiOnce(path, payload, next, method)
        }
    }

    private fun apiOnce(path: String, payload: JSONObject?, token: String?, method: String?): JSONObject {
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        val body = payload?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(base + path)
        if (body == null) builder.get() else builder.method(method ?: "POST", body)
        builder.header("X-TN-Client-Version", BuildConfig.VERSION_NAME)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val started = SystemClock.elapsedRealtime()
        client.newCall(builder.build()).execute().use { response ->
            if (BuildConfig.DEBUG) android.util.Log.i("TNNetwork", "${method ?: if (body == null) "GET" else "POST"} ${BuildConfig.API_BASE_URL.toHttpUrl().host}${path.substringBefore('?')} status=${response.code} elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            val raw = response.body?.string().orEmpty()
            val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            val expectedNotReady = path == "/v1/ready" && response.code == 503 &&
                json.optBoolean("ready") == false && json.optString("status") == "not_ready"
            if (!response.isSuccessful && !expectedNotReady) {
                val detail = json.optJSONObject("error")
                throw TNApiException(response.code, vn.tn.assistant.api.TnServiceFailure.knownMessage(detail?.optString("code"))
                    ?: detail?.optString("message")?.takeIf { !it.isNullOrBlank() }
                    ?: "Yêu cầu TN thất bại (${response.code}).", detail?.optString("code"))
            }
            return json
        }
    }

    private fun apiAudio(path: String, token: String): File {
        val base = BuildConfig.API_BASE_URL.toHttpUrl()
        val target = base.resolve(path) ?: throw IOException("Địa chỉ âm thanh không hợp lệ")
        if (target.scheme != "https" || target.host != base.host || target.port != base.port ||
            target.username.isNotEmpty() || target.password.isNotEmpty() || !target.encodedPath.startsWith("/v1/tts/audio/")) {
            throw IOException("TN từ chối địa chỉ âm thanh ngoài dịch vụ")
        }
        val request = Request.Builder().url(target).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Không tải được âm thanh TN (${response.code})")
            val body = response.body ?: throw IOException("Audio response is empty")
            val audioType = body.contentType()?.let { "${it.type}/${it.subtype}" }
            val extension = when (audioType) {
                "audio/mpeg" -> "mp3"
                "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
                else -> throw IOException("TN không hỗ trợ định dạng âm thanh này")
            }
            if (body.contentLength() > MAX_AUDIO_BYTES) throw IOException("Audio response exceeds the size limit")
            val bytes = readBounded(body.byteStream(), MAX_AUDIO_BYTES)
            if (bytes.size !in 44..MAX_AUDIO_BYTES) throw IOException("Audio response has an invalid size")
            return File(cacheDir, "tn-${UUID.randomUUID()}.$extension").also { it.writeBytes(bytes) }
        }
    }

    private fun acceptSession(response: JSONObject) {
        val hadSession = !accessToken.isNullOrBlank()
        sessionStore.accept(response)
        if (!hadSession) welcomeLaunchEligible = true
    }

    /** A canceled screen must not leave a fresh, unused server session behind. */
    private fun discardSession(response: JSONObject) {
        val refresh = response.optString("refresh_token")
        val access = response.optString("access_token")
        if (refresh.isBlank() || access.isBlank()) return
        // The Activity worker may already be shutting down after Back/rotation.
        val request = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/v1/auth/logout")
            .header("Authorization", "Bearer $access")
            .post(JSONObject().put("refresh_token", refresh).toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) = Unit
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
        })
    }

    private fun setupCompleted() = preferences.getBoolean("tn_welcome_completed_v2", false)

    private fun savedSetupStep(): Int {
        val saved = preferences.getInt("tn_welcome_step_v2", 0).coerceIn(0, 5)
        return if (saved == 5 && accessToken.isNullOrBlank()) 4 else saved
    }

    private fun setupWindow(showNavigation: Boolean = false) {
        window.statusBarColor = Color.rgb(0, 8, 23)
        window.navigationBarColor = Color.rgb(0, 8, 23)
        // The source onboarding hides navigation controls until its account/Zalo step.
        // Target 36 uses edge-to-edge, so apply each source screen's bar policy explicitly.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            hide(WindowInsetsCompat.Type.statusBars())
            if (showNavigation) show(WindowInsetsCompat.Type.navigationBars())
            else hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun panelWindow() {
        window.statusBarColor = Color.rgb(0, 8, 23)
        window.navigationBarColor = Color.rgb(0, 8, 23)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun attachGuidance(surface: TnOnboardingSurface, cue: String, back: (() -> Unit)? = { onBackPressed() }) {
        narrationCue = cue
        surface.setBackAction(back)
        narrator.onState = null
        narrator.setMuted(preferences.getBoolean("tn_setup_voice_muted", false))
        narrator.show(cue, resumed)
    }

    private fun notificationGranted(): Boolean = TnMediaAccessService.hasAccess(this)

    private fun overlayGranted() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
    private fun missingSetupPermissions(): List<String> = buildList {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
        // Approximate location is sufficient for the weather feature; respect the user's choice.
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun renderSetup(step: Int, message: String = "") {
        homePanelOpen = false
        closeZaloBrowser()
        narrator.interrupt()
        authPageRevision++
        authSurface = null
        val selected = step.coerceIn(0, 5)
        setupStep = selected
        preferences.edit().putInt("tn_welcome_step_v2", selected).apply()
        statusView = null
        setupWindow(showNavigation = selected >= 4)
        if (selected == 5 && accessToken.isNullOrBlank()) { renderSetup(4); return }
        val titles = arrayOf("Cài đặt TN Assistant", "Cấp quyền truy cập", "Cấp quyền điều khiển",
            "Cấp quyền hiển thị", "Đăng ký/Đăng nhập tài khoản", "Cài đặt hoàn tất")
        val descriptions = arrayOf(
            "Bắt đầu cài đặt để kích hoạt Trợ lý giọng nói TN",
            "Cho phép TN thực hiện các tính năng điều khiển bằng giọng nói",
            "Cho phép TN điều khiển trình phát nhạc của bạn",
            "Cho phép mở TN khi bạn đang sử dụng ứng dụng khác",
            "Đăng nhập TN Assistant với Zalo để có trải nghiệm cá nhân hoá tốt nhất",
            "TN Assistant đã sẵn sàng.")
        val granted = when (selected) {
            1 -> missingSetupPermissions().isEmpty()
            2 -> notificationGranted()
            3 -> overlayGranted()
            else -> false
        }
        val primary = when {
            selected == 0 -> "BẮT ĐẦU"
            selected == 5 -> "BẮT ĐẦU SỬ DỤNG"
            selected in 1..3 -> "TIẾP TỤC"
            else -> "TIẾP TỤC"
        }
        val description: CharSequence = if (selected == 5) {
            val terms = "Điều khoản dịch vụ"
            android.text.SpannableStringBuilder(message.ifBlank { descriptions[selected] }).apply {
                append(" "); val start = length; append(terms)
                setSpan(android.text.style.UnderlineSpan(), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) { showPrivacy() }
                    override fun updateDrawState(ds: android.text.TextPaint) { /* Source terms keep the body's white color. */ }
                }, start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else message.ifBlank { descriptions[selected] }
        val surface = TnOnboardingSurface(this, selected, titles[selected],
            description, primary, primary@{
                if (!welcomeClickGate.accept(SystemClock.elapsedRealtime())) return@primary
                narrator.interrupt()
                when (selected) {
                    0 -> renderSetup(1)
                    1 -> requestSetupPermission()
                    2, 3 -> if (granted) renderSetup(selected + 1) else openSetupSettings(selected)
                    4 -> if (accessToken.isNullOrBlank()) beginZalo() else renderSetup(5)
                    5 -> {
                        preferences.edit().putBoolean("tn_welcome_completed_v2", true).remove("tn_welcome_step_v2").apply()
                        renderHome(); recordEvent("setup_completed")
                    }
                }
            })
        setupSurface = surface
        surface.setOverflowAction(if (selected == 4) ({ beginZalo(presentation = "pairing") }) else null)
        setContentView(surface)
        val cues = arrayOf("start", "permission", "notification", "overlay", "account", "finish")
        // The original welcome cards expose only the primary CTA. Back remains an Android
        // system action; account forms and the Zalo browser own their separate back controls.
        attachGuidance(surface, cues[selected], null)
    }

    private fun requestSetupPermission() {
        narrator.interrupt()
        val missing = missingSetupPermissions()
        if (missing.isEmpty() || Build.VERSION.SDK_INT < 23) { renderSetup(2); return }
        if (preferences.getBoolean("tn_setup_permissions_requested_v2", false) && missing.any { !shouldShowRequestPermissionRationale(it) }) {
            openSetupSettings(1)
        } else {
            preferences.edit().putBoolean("tn_setup_permissions_requested_v2", true).apply()
            requestPermissions(missing.toTypedArray(), REQUEST_SETUP_AUDIO)
        }
    }

    private fun openSetupSettings(step: Int) {
        narrator.interrupt()
        permissionSettingsStep = step
        val intent = when (step) {
            2 -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            3 -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        try { startActivity(intent) } catch (_: Exception) {
            permissionSettingsStep = null
            if (returnToUserSettingsFromPermission) {
                returnToUserSettingsFromPermission = false
                setupStep = null
                userSettings?.refresh()
                val permission = if (step == 2) "truy cập thông báo" else "hiển thị"
                Toast.makeText(this, "Không mở được Cài đặt Android. Hãy cấp quyền $permission trong Cài đặt → Ứng dụng → TN Assistant.", Toast.LENGTH_LONG).show()
            } else renderSetup(step, "Không mở được Cài đặt Android. Bạn có thể cấp quyền trong Cài đặt → Ứng dụng → TN Assistant hoặc chọn Để sau.")
        }
    }

    override fun onResume() {
        super.onResume()
        TnFloatingControlService.setAppForeground(this, true)
        resumed = true
        resumePendingReleaseInstall()
        TnWakeUi.attach(wakeUi)
        if (TnWakeService.consented(this) && !TnWakeService.microphoneAllowed(this)) TnWakeService.microphoneRevoked(this)
        if (wakeStartWhenResumed) { wakeStartWhenResumed = false; main.post { setWakeEnabled(true) } }
        if (!accessToken.isNullOrBlank()) TnFloatingControlService.reconcile(this)
        if (!accessToken.isNullOrBlank()) {
            val planGeneration = vn.tn.assistant.ui.TnFloatingAccountPlan.generation()
            worker.execute { runCatching {
                vn.tn.assistant.ui.TnFloatingAccountPlan.update(this, api("/v1/me", null), planGeneration)
            } }
        }
        val pending = permissionSettingsStep
        permissionSettingsStep = null
        if (pending != null && setupStep == pending) {
            val granted = when (pending) { 1 -> missingSetupPermissions().isEmpty(); 2 -> notificationGranted(); else -> overlayGranted() }
            if (returnToUserSettingsFromPermission) {
                returnToUserSettingsFromPermission = false
                setupStep = null
                preferences.edit().putBoolean("tn_overlay_granted", overlayGranted()).apply()
                userSettings?.refresh()
            } else if (returnFromSettingsToHome) {
                returnFromSettingsToHome = false
                renderHome()
                status(if (granted) "Quyền đã được bật. Nhấn lại tính năng để sử dụng." else "Quyền chưa được bật; tính năng vẫn tắt.")
            } else if (granted) renderSetup(pending + 1)
            else {
                renderSetup(pending)
                showPermissionDeferral(pending)
            }
            } else {
                narrationCue?.let { narrator.show(it, true) }
                userSettings?.refresh()
            }
        zaloBrowser?.onResume()
        if (authMode == "zalo") {
            if (zaloTerminal) finishZaloAttempt(zaloDenied)
            else if (zaloExpiresAt != 0L && SystemClock.elapsedRealtime() >= zaloExpiresAt) finishZaloAttempt(false)
            else pollZalo()
        }
        showPendingConfirmation()
        maybeStartWelcome()
    }

    override fun onPause() {
        resumed = false
        userSettings?.pauseRoadMonitor()
        welcomeRevision.incrementAndGet()
        dismissActionConfirmation()
        contactChoiceDialog?.dismiss()
        contactChoiceDialog = null
        // Explicit foreground capture does not confer background microphone
        // consent. Only the separately enabled wake FGS may keep listening.
        foregroundCapture?.cancel()
        runCatching { audioRecorder?.stop() }
        TnWakeUi.detach(wakeUi)
        TnFloatingControlService.setAppForeground(this, false)
        zaloBrowser?.onPause()
        narrator.pause()
        super.onPause()
    }

    private fun showPermissionDeferral(step: Int) {
        narrator.interrupt()
        val detail = when (step) {
            1 -> "Micro dùng khi bạn nhấn Nói; vị trí dùng cho yêu cầu thời tiết. Bạn có thể cấp sau trong Cài đặt Android."
            2 -> "TN chưa thể điều khiển trình phát nhạc. Giọng hướng dẫn vẫn hoạt động khi không cấp quyền này."
            else -> "TN chưa thể hiển thị nút mở trợ lý trên ứng dụng khác. Bạn vẫn có thể mở TN từ màn hình chính."
        }
        AlertDialog.Builder(this).setTitle("Ứng dụng chưa được cấp quyền")
            .setMessage(detail)
            .setPositiveButton("CẤP QUYỀN") { _, _ -> if (step == 1) requestSetupPermission() else openSetupSettings(step) }
            .setNegativeButton("ĐỂ SAU VÀ TIẾP TỤC") { _, _ -> renderSetup(step + 1) }
            .show()
    }

    private fun styledField(hint: String, secret: Boolean): EditText = field(hint, secret).apply {
        setTextColor(Color.WHITE); setHintTextColor(Color.rgb(177, 205, 224))
        textSize = 16f; setSingleLine(true)
        typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MainActivity, R.font.tn_barlow_regular)
        // setSingleLine installs its own transformation; restore masking after that call.
        if (secret) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        background = GradientDrawable().apply {
            setColor(Color.argb(100, 20, 52, 90)); cornerRadius = dp(4).toFloat()
            setStroke(dp(1), Color.argb(130, 71, 154, 195))
        }
    }

    private fun renderAuth(message: String = "") {
        homePanelOpen = false
        closeZaloBrowser()
        narrator.interrupt()
        authPageRevision++
        setupStep = 4
        setupWindow(showNavigation = true)
        if (authMode == "choice") {
            val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val icon = ImageView(this).apply {
                setImageDrawable(TnWelcomeAppMark(this@MainActivity))
                tag = "tnAccountChoiceMark"
                // Decorative brand art must not receive keyboard/accessibility focus.
                // On some emulators a focused ImageView draws a dashed ring around the mark.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                isLongClickable = false
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            form.addView(icon, LinearLayout.LayoutParams(dp(90), dp(90)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18) })
            form.addView(button("ĐĂNG NHẬP BẰNG ZALO") { beginZalo() })
            val status = label(message.ifBlank { "Chọn tài khoản TN hoặc đăng nhập bằng Zalo để tiếp tục." }).apply { setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }
            form.addView(status); statusView = status
            val surface = TnOnboardingSurface(this, 4, "Đăng nhập TN Assistant", "Đăng nhập để có trải nghiệm cá nhân hóa tốt nhất",
                "TÀI KHOẢN TN", { authMode = "login"; renderAuth() }, "TẠO TÀI KHOẢN TN", { authMode = "register"; renderAuth() })
            surface.setContent(form); authSurface = surface
            setContentView(surface); attachGuidance(surface, "choice")
            return
        }
        if (authMode == "zalo") { renderZalo(); return }
        val registering = authMode == "register"
        val username = styledField("Tên đăng nhập", false)
        val password = styledField("Mật khẩu, tối thiểu 10 ký tự", true)
        val name = styledField("Tên hiển thị", false)
        val status = label(message.ifBlank { if (registering) "Tạo tài khoản riêng của bạn trên TN." else "Nhập tài khoản TN để tiếp tục." }).apply { setTextColor(Color.LTGRAY); textSize = 14f }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        form.addView(username); form.addView(password)
        if (registering) form.addView(name)
        val terms = CheckBox(this).apply { text = "Tôi xác nhận tạo tài khoản TN và đã đọc thông tin sử dụng dữ liệu"; setTextColor(Color.WHITE); textSize = 14f }
        if (registering) {
            form.addView(terms)
            form.addView(button("THÔNG TIN SỬ DỤNG DỮ LIỆU") { showPrivacy() })
        }
        form.addView(status); statusView = status
        val surface = TnOnboardingSurface(this, 4, if (registering) "Tạo tài khoản TN" else "Đăng nhập TN",
            if (registering) "Thông tin dùng để tạo tài khoản TN Assistant" else "Tài khoản TN của bạn", if (registering) "TẠO TÀI KHOẢN" else "ĐĂNG NHẬP", {
                narrator.interrupt()
                if (registering && !terms.isChecked) status("Vui lòng đọc thông tin sử dụng dữ liệu và xác nhận trước khi tạo tài khoản.")
                else authenticate(username.text.toString(), password.text.toString(), name.text.toString(), registering)
            }, if (registering) "ĐÃ CÓ TÀI KHOẢN" else "TẠO TÀI KHOẢN TN", {
                authMode = if (registering) "login" else "register"; renderAuth()
            })
        surface.setContent(form); authSurface = surface
        surface.primaryButton.isEnabled = !authenticationBusy; surface.secondaryButton?.isEnabled = !authenticationBusy
        setContentView(surface); attachGuidance(surface, if (registering) "register" else "login")
    }

    private fun showPrivacy() {
        narrator.interrupt()
        AlertDialog.Builder(this).setTitle("Thông tin sử dụng dữ liệu TN")
            .setMessage("TN lưu thông tin tài khoản, thiết bị và phiên đăng nhập trên máy chủ TN. Khi bạn nhấn Nói, âm thanh được gửi tới dịch vụ nhận dạng đã cấu hình. Nội dung cần tổng hợp được gửi tới dịch vụ TTS. Vị trí chỉ dùng khi bạn cấp quyền và yêu cầu thời tiết. Quyền điều khiển nhạc và hiển thị trên ứng dụng khác là tùy chọn; bạn có thể thu hồi trong Cài đặt Android. Mật khẩu được băm trên máy chủ; khóa dịch vụ không được đóng gói trong APK.")
            .setPositiveButton("ĐÃ HIỂU", null).show()
    }

    private fun beginZalo(fromSettings: Boolean = false, presentation: String = "web") {
        zaloFromSettings = fromSettings
        authMode = "zalo"
        // Match the original app: begin in Zalo's WebLogin screen. The TN QR pairing
        // flow remains available through the recovery action when the browser login fails.
        zaloPresentation = presentation
        zaloAttempt = null
        zaloExpiresAt = 0L
        zaloTerminal = false
        zaloFailureMessage = null
        renderZalo()
    }

    private fun closeZaloBrowser() {
        zaloTimers.removeCallbacksAndMessages(null)
        zaloWebSurface = null
        val browser = zaloBrowser ?: return
        zaloBrowser = null
        browser.stopLoading()
        browser.webViewClient = WebViewClient()
        browser.webChromeClient = null
        (browser.parent as? ViewGroup)?.removeView(browser)
        browser.removeAllViews()
        browser.destroy()
    }

    private fun retryZalo() {
        if (zaloCompletingRevision == zaloRevision) {
            showZaloMessage("TN đang xác minh tài khoản. Vui lòng chờ hoàn tất.")
            return
        }
        zaloAttempt = null
        zaloExpiresAt = 0L
        zaloTerminal = false
        zaloFailureMessage = null
        zaloEndDialog?.dismiss()
        zaloEndDialog = null
        renderZalo()
    }

    private fun showZaloHelp() {
        if (zaloCompletingRevision == zaloRevision) {
            showZaloMessage("TN đang xác minh tài khoản. Vui lòng chờ hoàn tất.")
            return
        }
        narrator.interrupt()
        AlertDialog.Builder(this).setTitle("Bạn gặp lỗi đăng nhập?")
            .setItems(arrayOf("Quét mã bằng điện thoại khác", "Mở trang Zalo trong trình duyệt", "Tài khoản TN")) { _, which ->
                if (zaloCompletingRevision == zaloRevision) {
                    showZaloMessage("TN đang xác minh tài khoản. Vui lòng chờ hoàn tất.")
                    return@setItems
                }
                when (which) {
                    0 -> { zaloPresentation = "pairing"; renderZalo() }
                    1 -> {
                        val attempt = zaloAttempt ?: return@setItems
                        val address = attempt.optString("url")
                        if (ZaloNavigationPolicy(BuildConfig.API_BASE_URL, attempt.optString("state")).authorization(address)) {
                            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
                            catch (_: Exception) { showZaloMessage("Không mở được trình duyệt. Hãy chọn quét mã bằng điện thoại khác.", true) }
                        }
                    }
                    else -> { zaloRevision++; zaloAttempt = null; authMode = "login"; renderAuth() }
                }
            }.setNegativeButton("Đóng", null).show()
    }

    private fun showZaloMessage(message: String, error: Boolean = false) {
        val surface = zaloWebSurface
        if (surface == null) status(message)
        else if (error) surface.error(message) else surface.status(message)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun renderZalo() {
        homePanelOpen = false
        if (zaloCompletingRevision == zaloRevision) {
            showZaloMessage("TN đang xác minh tài khoản. Vui lòng chờ hoàn tất.")
            return
        }
        closeZaloBrowser()
        narrator.interrupt()
        zaloEndDialog?.dismiss()
        zaloEndDialog = null
        setupStep = 4
        setupWindow(showNavigation = true)
        val revision = ++zaloRevision
        authPageRevision++
        statusView = null
        var policy: ZaloNavigationPolicy? = null
        val muted = preferences.getBoolean("tn_setup_voice_muted", false)
        val image = ImageView(this).apply { setBackgroundColor(Color.WHITE) }
        val code = label("Đang tạo mã đăng nhập…")
        val state = label("Đang kết nối dịch vụ TN…")
        if (zaloPresentation == "pairing") {
            statusView = state
            setContentView(TnZaloPairingSurface(this, image, code, state, { onBackPressed() },
                { retryZalo() }, { zaloPresentation = "web"; renderZalo() }))
        } else {
            // LoginTheme is Holo.Light; retain its blue system status area above the cream action bar.
            // setupWindow hides the status bar for the welcome cards. The SDK login is a
            // separate, non-fullscreen screen in the reference app, so restore both bars
            // before fitting its two toolbars into the available content rectangle.
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.statusBarColor = 0xd90073a1.toInt()
            window.navigationBarColor = Color.BLACK
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
                show(WindowInsetsCompat.Type.systemBars())
            }
            val browser = WebView(this)
            zaloBrowser = browser
            browser.setBackgroundColor(Color.WHITE)
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            browser.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?) = true
            }
            fun current() = !isFinishing && !isDestroyed && zaloRevision == revision && authMode == "zalo" && zaloBrowser === browser
            fun blocked(address: String? = null) {
                if (current()) { browser.stopLoading(); zaloWebSurface?.error("Không mở được trang đăng nhập an toàn. Hãy thử lại hoặc chọn Thử cách khác.") }
            }
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val allowed = policy?.destination(request.url.toString()) != ZaloNavigationPolicy.Destination.BLOCKED && policy != null
                    if (!allowed) blocked(request.url.toString())
                    return !allowed
                }
                @Deprecated("Compatibility with Android 5/6")
                override fun shouldOverrideUrlLoading(view: WebView, address: String): Boolean {
                    val allowed = policy?.destination(address) != ZaloNavigationPolicy.Destination.BLOCKED && policy != null
                    if (!allowed) blocked(address)
                    return !allowed
                }
                override fun onPageStarted(view: WebView, address: String?, icon: Bitmap?) {
                    if (!current()) return
                    if (address == null || policy?.destination(address) == ZaloNavigationPolicy.Destination.BLOCKED) { blocked(address); return }
                    zaloWebSurface?.loading(true)
                }
                override fun onPageFinished(view: WebView, address: String?) {
                    if (!current()) return
                    zaloWebSurface?.loading(false)
                    if (address != null && policy?.destination(address) == ZaloNavigationPolicy.Destination.CALLBACK) {
                        showZaloMessage("Đang xác minh tài khoản Zalo với TN…")
                        pollZalo()
                    }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    if (current()) zaloWebSurface?.error("Không xác minh được kết nối bảo mật. Hãy kiểm tra ngày giờ thiết bị và thử lại.")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && current()) zaloWebSurface?.error("Không tải được trang Zalo. Kiểm tra mạng rồi thử lại, hoặc chọn Thử cách khác.")
                }
                @Deprecated("Compatibility with Android 5")
                override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                    if (current()) zaloWebSurface?.error("Không tải được trang Zalo. Kiểm tra mạng rồi thử lại, hoặc chọn Thử cách khác.")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && current() && response.statusCode >= 400) {
                        zaloWebSurface?.error("Trang đăng nhập chưa sẵn sàng (${response.statusCode}). Hãy thử lại hoặc chọn Thử cách khác.")
                    }
                }
            }
            browser.setDownloadListener { _, _, _, _, _ -> if (current()) blocked() }
            val surface = TnZaloWebSurface(this, browser, { onBackPressed() }, { retryZalo() },
                { showZaloHelp() })
            zaloWebSurface = surface
            setContentView(surface)
            zaloTimers.postDelayed({ if (current()) surface.loading(false) }, 30000)
        }
        narrationCue = if (zaloPresentation == "web") "zalo_web" else "zalo"
        narrator.onState = null
        narrator.setMuted(muted)
        narrator.show(narrationCue!!, resumed)

        fun display(attempt: JSONObject) {
            val continueUri = Uri.parse(attempt.optString("qr_payload"))
            val pair = attempt.optString("user_code")
            policy = ZaloNavigationPolicy(BuildConfig.API_BASE_URL, attempt.optString("state"))
            if (!validZaloContinue(continueUri) || !pair.matches(Regex("[0-9]{6}")) ||
                !policy!!.authorization(attempt.optString("url"))) {
                showZaloMessage("Thông tin đăng nhập trả về không hợp lệ. Hãy thử lại.", true)
                return
            }
            if (zaloExpiresAt <= SystemClock.elapsedRealtime()) { finishZaloAttempt(false); return }
            if (zaloPresentation == "web") {
                zaloWebSurface?.error(null)
                zaloWebSurface?.loading(true)
                zaloBrowser?.loadUrl(attempt.optString("url"))
            } else {
                val matrix = QRCodeWriter().encode(continueUri.toString(), BarcodeFormat.QR_CODE, 480, 480)
                val pixels = IntArray(480 * 480) { i -> if (matrix[i % 480, i / 480]) Color.BLACK else Color.WHITE }
                image.setImageBitmap(Bitmap.createBitmap(pixels, 480, 480, Bitmap.Config.ARGB_8888))
                code.text = pair.chunked(3).joinToString(" ")
                state.text = "Chờ bạn quét mã và xác nhận đăng nhập."
            }
            zaloTimers.postDelayed({ if (zaloRevision == revision && authMode == "zalo") finishZaloAttempt(false) },
                (zaloExpiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            pollZalo()
        }
        val existing = zaloAttempt
        if (existing != null) { display(existing); return }
        worker.execute {
            try {
                val attempt = api("/v1/auth/zalo/start", JSONObject().put("device_id", deviceId).put("device_name", deviceName()), accessToken)
                main.post {
                    if (isFinishing || isDestroyed || zaloRevision != revision || authMode != "zalo") return@post
                    zaloAttempt = attempt
                    zaloTerminal = false
                    zaloExpiresAt = SystemClock.elapsedRealtime() + attempt.optLong("expires_in", 600).coerceIn(1, 600) * 1000
                    display(attempt)
                }
            } catch (_: Exception) {
                main.post { if (zaloRevision == revision && authMode == "zalo") showZaloMessage("Chưa kết nối được đăng nhập Zalo. Hãy thử lại hoặc dùng tài khoản TN.", true) }
            }
        }
    }

    private fun validZaloContinue(uri: Uri): Boolean {
        val base = Uri.parse(BuildConfig.API_BASE_URL)
        val port = if (uri.port == -1) 443 else uri.port
        val basePort = if (base.port == -1) 443 else base.port
        return uri.scheme == "https" && uri.host == base.host && port == basePort &&
            uri.userInfo == null && uri.path == "/v1/auth/zalo/continue" && uri.fragment == null &&
            uri.getQueryParameters("state") == listOf(zaloAttempt?.optString("state"))
    }

    private fun finishZaloAttempt(denied: Boolean) {
        if (authMode != "zalo") return
        zaloTerminal = true
        zaloDenied = denied
        zaloTimers.removeCallbacksAndMessages(null)
        if (!resumed || zaloEndDialog?.isShowing == true) return
        narrator.interrupt()
        zaloBrowser?.stopLoading()
        val failure = zaloFailureMessage
        val title = when {
            failure != null -> "Không thể hoàn tất đăng nhập"
            denied -> "Đăng nhập chưa được chấp nhận"
            else -> "Phiên đăng nhập đã hết hạn"
        }
        val message = failure ?: if (denied) "Bạn đã từ chối đăng nhập. Bạn có thể thử lại khi sẵn sàng." else "Vui lòng tải lại trang để tiếp tục đăng nhập."
        showZaloMessage(message, true)
        zaloEndDialog = AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Tải lại trang") { _, _ -> retryZalo() }
            .setNegativeButton("Đóng") { _, _ -> onBackPressed() }
            .setOnCancelListener { onBackPressed() }.show()
    }

    private fun pollZalo() {
        val attempt = zaloAttempt ?: return
        val revision = zaloRevision
        if (!resumed || authMode != "zalo" || pollingZaloRevision == revision || zaloTerminal) return
        if (SystemClock.elapsedRealtime() >= zaloExpiresAt) { finishZaloAttempt(false); return }
        val request = JSONObject().put("state", attempt.optString("state"))
            .put("client_proof", attempt.optString("client_proof")).put("device_id", deviceId)
        pollingZaloRevision = revision
        worker.execute {
            var retry = false
            var completing = false
            try {
                val result = api("/v1/auth/zalo/status", request, null).optString("status")
                when (result) {
                    "authorized" -> {
                        if (zaloRevision != revision || zaloTerminal || SystemClock.elapsedRealtime() >= zaloExpiresAt) return@execute
                        // The server consumes this attempt before exchanging the one-time code.
                        // A failed completion must start a new attempt rather than resume polling.
                        completing = true
                        zaloCompletingRevision = revision
                        val session = api("/v1/auth/zalo/complete", request, null)
                        main.post {
                            if (isFinishing || isDestroyed || zaloRevision != revision || authMode != "zalo" || zaloTerminal || SystemClock.elapsedRealtime() >= zaloExpiresAt) {
                                discardSession(session)
                                return@post
                            }
                            try { acceptSession(session) } catch (_: Exception) {
                                discardSession(session)
                                zaloFailureMessage = "Không lưu được phiên đăng nhập an toàn. Vui lòng tạo phiên đăng nhập mới."
                                finishZaloAttempt(false)
                                return@post
                            }
                            zaloEndDialog?.dismiss()
                            zaloEndDialog = null
                            zaloAttempt = null; zaloExpiresAt = 0L; authMode = "choice"
                            if (setupCompleted()) renderHome() else renderSetup(5)
                        }
                    }
                    "pending" -> retry = true
                    "denied" -> main.post { if (zaloRevision == revision) finishZaloAttempt(true) }
                    "expired" -> main.post { if (zaloRevision == revision) finishZaloAttempt(false) }
                    else -> main.post { if (zaloRevision == revision) showZaloMessage("Chưa xác định được trạng thái đăng nhập. Hãy tải lại trang.", true) }
                }
            } catch (error: Exception) {
                if (completing) {
                    main.post {
                        if (zaloRevision == revision && authMode == "zalo" && !zaloTerminal) {
                            zaloFailureMessage = ZaloCompletionPolicy.failure((error as? TNApiException)?.statusCode, (error as? TNApiException)?.errorCode)
                            finishZaloAttempt(false)
                        }
                    }
                } else if (error is TNApiException && error.statusCode == 409) main.post {
                    if (zaloRevision == revision) {
                        zaloFailureMessage = "Không thể tiếp tục phiên đăng nhập này. Vui lòng tạo phiên đăng nhập mới."
                        finishZaloAttempt(false)
                    }
                }
                else {
                    retry = true
                    main.post { if (zaloRevision == revision) showZaloMessage("Mất kết nối kiểm tra đăng nhập. TN sẽ thử kết nối lại.") }
                }
            } finally {
                main.post {
                    if (zaloCompletingRevision == revision) zaloCompletingRevision = null
                    if (pollingZaloRevision == revision) pollingZaloRevision = null
                    if (retry && revision == zaloRevision && authMode == "zalo" && resumed && !zaloTerminal) zaloTimers.postDelayed({ pollZalo() }, 3000)
                }
            }
        }
    }


    private fun toggleFloatingControl() {
        if (TnFloatingControlService.isEnabled(this) && !TnFloatingControlService.isHidden(this)) {
            saveUserSetting("floating_enabled", false)
            return
        }
        if (!notificationGranted()) { returnFromSettingsToHome = true; setupStep = 2; openSetupSettings(2); return }
        if (!overlayGranted()) { returnFromSettingsToHome = true; setupStep = 3; openSetupSettings(3); return }
        AlertDialog.Builder(this).setTitle("Bật tiện ích nổi TN?")
            .setMessage("Hiển thị nút mở TN trên ứng dụng khác. Thao tác này không bật ghi âm hoặc nghe nền.")
            .setNegativeButton("HỦY", null).setPositiveButton("BẬT") { _, _ ->
                saveUserSetting("floating_enabled", true)
            }.show()
    }

    private fun toggleMedia() {
        if (!notificationGranted()) { returnFromSettingsToHome = true; setupStep = 2; openSetupSettings(2); return }
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = manager.getActiveSessions(ComponentName(this, TnMediaAccessService::class.java))
            val controller = controllers.firstOrNull() ?: return status("Chưa có trình phát nhạc đang hoạt động.")
            val playing = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
            status("Đã gửi yêu cầu ${if (playing) "tạm dừng" else "phát"} tới trình phát. Trạng thái do trình phát quyết định.")
        } catch (_: Exception) { status("Chưa điều khiển được trình phát. Kiểm tra quyền truy cập thông báo.") }
    }

    private fun authenticate(username: String, password: String, name: String, register: Boolean) {
        if (authenticationBusy) return
        if (username.isBlank() || password.isBlank()) {
            status("Nhập tên đăng nhập và mật khẩu.")
            return
        }
        status(if (register) "Đang tạo tài khoản TN…" else "Đang đăng nhập…")
        authenticationBusy = true
        val revision = authPageRevision
        authSurface?.primaryButton?.isEnabled = false
        authSurface?.secondaryButton?.isEnabled = false
        worker.execute {
            try {
                val payload = JSONObject().put("username", username.trim()).put("password", password)
                    .put("device_id", deviceId).put("device_name", deviceName())
                if (register) payload.put("name", name.trim().ifBlank { username.trim() })
                val session = api(if (register) "/v1/auth/register" else "/v1/auth/login", payload, null)
                main.post {
                    authenticationBusy = false
                    if (isFinishing || isDestroyed) { discardSession(session); return@post }
                    authSurface?.primaryButton?.isEnabled = true
                    authSurface?.secondaryButton?.isEnabled = true
                    if (authPageRevision == revision && setupStep == 4) {
                        try { acceptSession(session) } catch (_: Exception) {
                            discardSession(session)
                            status("Không lưu được phiên đăng nhập an toàn. Hãy thử lại.")
                            return@post
                        }
                        authMode = "choice"
                        if (setupCompleted()) renderHome() else renderSetup(5)
                        recordEvent("account_session_started")
                    } else discardSession(session)
                }
            } catch (error: Exception) {
                main.post {
                    authenticationBusy = false
                    if (isFinishing || isDestroyed) return@post
                    authSurface?.primaryButton?.isEnabled = true
                    authSurface?.secondaryButton?.isEnabled = true
                    if (authPageRevision == revision && setupStep == 4) status(error.message ?: "Không thể đăng nhập TN.")
                }
            }
        }
    }

    private fun renderHome() {
        welcomeRevision.incrementAndGet()
        guideSurface = null
        userSettings = null
        zaloFromSettings = false
        closeZaloBrowser()
        narrator.interrupt()
        narrationCue = null
        authMode = "choice"
        zaloRevision++
        zaloAttempt = null
        authPageRevision++
        authSurface = null
        setupStep = null
        homePanelOpen = false
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        homeSurface = TnAssistantHomeSurface(this).apply {
            guide.setOnClickListener { guideSurface?.closeWithAnimation() ?: renderGuide() }
            settings.setOnClickListener { renderSettings() }
            microphone.setOnClickListener {
                welcomeRevision.incrementAndGet()
                if (TnWakeService.isRunning()) {
                    TnWakeService.captureNow()
                } else if (recording.isActive) {
                    foregroundCapture?.cancel()
                    try { audioRecorder?.stop() } catch (_: Exception) { }
                    status("Đã dừng nghe.")
                } else startVoice()
            }
            microphone.setOnLongClickListener { welcomeRevision.incrementAndGet(); toggleTextEntry(); true }
            send.setOnClickListener { sendCommand(command.text?.toString().orEmpty()) }
            setSuggestions(homeSuggestions()) { command -> sendCommand(command) }
        }
        statusView = homeSurface?.status
        commandView = homeSurface?.command
        setContentView(requireNotNull(homeSurface))
        // Session refresh is asynchronous and can finish after onResume. Restore an
        // explicitly enabled overlay here as well, once authentication is available.
        if (!accessToken.isNullOrBlank()) TnFloatingControlService.reconcile(this)
        if (!accessToken.isNullOrBlank() && preferences.getBoolean("wake_word_enabled", false) && TnWakeService.consented(this)) {
            TnWakeService.enableFromVisibleActivity(this)
        }
        maybeStartWelcome()
    }

    private fun welcomeDay(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun welcomePlayedKey(userId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
        return "tn_welcome_last_played_day_" + digest.joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun maybeStartWelcome() {
        val token = accessToken
        if (!welcomeLaunchEligible || token.isNullOrBlank() || !resumed || isFinishing || isDestroyed ||
            setupStep != null || homePanelOpen || homeSurface == null || recording.isActive ||
            actionConfirmation?.isShowing == true) return
        val day = welcomeDay()
        val owner = sessionStore.actionGeneration()
        val attempt = owner to day
        if (welcomeAttempt == attempt) return
        welcomeAttempt = attempt
        val revision = welcomeRevision.get()
        val active = {
            resumed && !isFinishing && !isDestroyed && welcomeRevision.get() == revision &&
                sessionStore.ownsActions(owner)
        }
        welcomeWorker.execute {
            try {
                if (!active()) return@execute
                val userId = api("/v1/me", null, token).optJSONObject("user")?.optString("id").orEmpty()
                if (userId.isBlank() || !active()) return@execute
                val playedKey = welcomePlayedKey(userId)
                if (preferences.getString(playedKey, null) == day) return@execute
                val settings = api("/v1/settings", null, token)
                if (!active()) return@execute
                val enabled = settings.optBoolean("welcome_enabled", true)
                preferences.edit().putBoolean("welcome_enabled", enabled).apply()
                if (!enabled || !active()) return@execute
                val rule = api("/v1/welcome/rule", null, token)
                if (rule.optInt("status", -1) != 0 || !active()) return@execute
                val commands = rule.optJSONObject("data")?.optJSONArray("onboardMessages") ?: return@execute
                var message: String? = null
                for (index in 0 until commands.length()) {
                    val command = commands.optJSONObject(index) ?: continue
                    if (command.optString("id") == "tn.welcome.ready.v1" && command.optString("action") == "SPEAK_ONLY") {
                        message = command.optString("message").trim()
                        break
                    }
                }
                val text = message?.takeIf { it.isNotBlank() && it.length <= 500 } ?: return@execute
                val playbackActive = { active() && preferences.getBoolean("welcome_enabled", true) }
                if (playbackActive() && playSpeech(text, accessToken ?: token, playbackActive) && playbackActive()) {
                    preferences.edit().putString(playedKey, day).commit()
                }
            } catch (_: Exception) {
                // A greeting failure must leave the daily mark empty and the assistant usable.
            }
        }
    }

    private fun homeSuggestions(): List<TnHomeSuggestion> {
        val choices = listOf(
            TnHomeSuggestion("Mở nhạc", R.drawable.tn_home_suggestion_music, "Mở nhạc"),
            TnHomeSuggestion("Tìm đường", R.drawable.tn_home_suggestion_navigate, "Tìm đường đến địa điểm gần đây"),
            TnHomeSuggestion("Tin tức", R.drawable.tn_home_suggestion_news, "Tin tức hôm nay"),
            TnHomeSuggestion("Thời tiết", R.drawable.tn_home_suggestion_utility, "Thời tiết hôm nay"),
            TnHomeSuggestion("Video", R.drawable.tn_home_suggestion_video, "Mở video")
        )
        val offset = preferences.getInt("tn_home_suggestion_offset", 0).let { (it + 1) % choices.size }
        preferences.edit().putInt("tn_home_suggestion_offset", offset).apply()
        return (0 until 3).map { choices[(offset + it) % choices.size] }
    }

    private fun renderGuide() {
        welcomeRevision.incrementAndGet()
        narrator.interrupt()
        userSettings = null
        homePanelOpen = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.statusBarColor = 0xff021f4e.toInt()
        window.navigationBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
            show(WindowInsetsCompat.Type.systemBars())
        }
        val home = requireNotNull(homeSurface)
        home.setGuideMode(true)
        val guide = vn.tn.assistant.ui.TnAssistantGuideSurface(this, { renderHome() }) { text ->
            sendCommand(text)
        }
        guideSurface = guide
        val container = android.widget.FrameLayout(this)
        // The original guide's translucent blue scrim sits over the assistant's
        // dark surface, never over the launcher or another application's window.
        container.setBackgroundColor(Color.rgb(0, 8, 23))
        (home.parent as? ViewGroup)?.removeView(home)
        container.addView(guide, android.widget.FrameLayout.LayoutParams(-1, -1))
        container.addView(home, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(container)
    }

    private fun renderSettings(savedUiState: Bundle? = null) {
        welcomeRevision.incrementAndGet()
        guideSurface = null
        narrator.interrupt()
        homePanelOpen = true
        panelWindow()
        // Original signed-in Settings draws its background beneath the status
        // bar, consumes that top inset, and retains Android's navigation bar.
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            show(WindowInsetsCompat.Type.systemBars())
        }
        preferences.edit().putBoolean("tn_overlay_granted", overlayGranted())
            .putBoolean("floating_enabled", TnFloatingControlService.isEnabled(this))
            .putString("floating_size", TnFloatingControlService.settings(this).getString("floating_size", "standard"))
            .putString("floating_opacity", TnFloatingControlService.settings(this).getString("floating_opacity", "auto")).apply()
        val settings = TnUserSettingsSurface(this, preferences, deviceId, { renderHome() },
            { key -> userSettingsAction(key) }, { key, value -> saveUserSetting(key, value) })
        userSettings = settings
        showHomePanel(settings)
        savedUiState?.let { settings.restoreUiState(it) }
        val planGeneration = vn.tn.assistant.ui.TnFloatingAccountPlan.generation()
        worker.execute {
            try {
                val account = api("/v1/me", null)
                vn.tn.assistant.ui.TnFloatingAccountPlan.update(this, account, planGeneration)
                val values = api("/v1/settings", null)
                storeUserSettings(values)
                main.post {
                    if (userSettings === settings) {
                        settings.account(account)
                        if (!values.optBoolean("wake_word_enabled")) TnWakeService.disable(this)
                    }
                }
            } catch (error: Exception) {
                main.post { if (userSettings === settings) settings.loadError(error.message ?: "Chưa tải được tài khoản TN.") }
            }
        }
    }

    private fun storeUserSettings(values: JSONObject) {
        val editor = preferences.edit()
        values.keys().forEach { key ->
            when (val value = values.opt(key)) {
                is Boolean -> editor.putBoolean(key, if (key == "wake_word_enabled") value && TnWakeService.consented(this) else value)
                is Number -> editor.putInt(key, value.toInt())
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
    }

    private fun saveUserSetting(key: String, value: Any) {
        if (key == "wake_word_enabled") { setWakeEnabled(value == true); return }
        val settings = userSettings
        val local = setOf("floating_enabled", "floating_size", "floating_opacity", "video_warning", "record_mode", "playback_mode")
        if (key in local) {
            if (key == "floating_enabled") {
                if (value == true && !notificationGranted()) {
                    if (settings != null) requestSettingsPermission(2)
                    else { returnFromSettingsToHome = true; setupStep = 2; openSetupSettings(2) }
                    return
                }
                if (value == true && !TnFloatingControlService.enable(this)) {
                    settings?.loadError("Chưa bật được Tiện ích nổi. Kiểm tra quyền hiển thị.")
                    return
                }
                if (value == false) TnFloatingControlService.disable(this)
            }
            if (key in setOf("floating_size", "floating_opacity")) TnFloatingControlService.settings(this).edit().putString(key, value.toString()).apply()
            storeUserSettings(JSONObject().put(key, value)); settings?.refresh()
            return
        }
        settings?.saving(true)
        worker.execute {
            try {
                val values = api("/v1/settings", JSONObject().put(key, value), method = "PUT")
                storeUserSettings(values)
                main.post { if (userSettings === settings) { settings?.saving(false); settings?.refresh() } }
            } catch (error: Exception) {
                main.post { if (userSettings === settings) { settings?.saving(false); settings?.refresh(); settings?.loadError(error.message ?: "Không lưu được cài đặt.") } }
            }
        }
    }

    private fun setWakeEnabled(enabled: Boolean) {
        if (enabled && !resumed) { wakeStartWhenResumed = true; return }
        if (!enabled) wakeStartWhenResumed = false
        val operation = ++wakeRevision
        val settings = userSettings
        if (enabled && Build.VERSION.SDK_INT >= 23 && !TnWakeService.microphoneAllowed(this)) {
            wakePermissionPending = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_WAKE_AUDIO)
            return
        }
        if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            wakePermissionPending = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_WAKE_NOTIFICATION)
            return
        }
        if (!enabled) TnWakeService.disable(this)
        settings?.saving(true)
        worker.execute {
            try {
                val values = api("/v1/settings", JSONObject().put("wake_word_enabled", enabled), method = "PUT")
                if (enabled && !values.optBoolean("wake_word_enabled")) throw IOException("Tài khoản chưa bật được quyền nghe “TN ơi”.")
                storeUserSettings(values)
                main.post {
                    if (isFinishing || isDestroyed || operation != wakeRevision) return@post
                    val started = !enabled || (resumed && TnWakeService.enableFromVisibleActivity(this))
                    preferences.edit().putBoolean("wake_word_enabled", enabled && started && TnWakeService.isReady()).apply()
                    if (userSettings === settings) {
                        settings?.saving(enabled && started && !TnWakeService.isReady()); settings?.refresh()
                        if (!started) settings?.loadError(TnWakeService.lastError(this) ?: "Mở TN Assistant và thử bật nghe nền lại.")
                    }
                }
            } catch (error: Exception) {
                if (enabled) TnWakeService.disable(this)
                main.post {
                    if (userSettings === settings) {
                        settings?.saving(false); settings?.refresh()
                        settings?.loadError(error.message ?: "Không lưu được cài đặt “TN ơi”.")
                    }
                }
            }
        }
    }

    private fun userSettingsAction(key: String) {
        when (key) {
            "traffic-location" -> if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),REQUEST_TRAFFIC_LOCATION)
            }
            "zalo" -> beginZalo(fromSettings = true)
            "logout" -> logout()
            "delete-account" -> deleteAccount()
            "overlay" -> requestSettingsPermission(3)
            "notification" -> requestSettingsPermission(2)
            "updates" -> checkForUpdate()
            "privacy", "terms" -> showPrivacy()
            "guide" -> renderGuide()
            "support-email" -> runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:support@tnfastvn.com"))) }
            "wake" -> setWakeEnabled(!TnWakeService.consented(this))
            "devices", "history", "premium", "diagnostics", "support" -> loadUserPage(key)
        }
    }

    private fun requestSettingsPermission(step: Int) {
        returnToUserSettingsFromPermission = true
        setupStep = step
        openSetupSettings(step)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        (userSettings?.takeIf { it.isAttachedToWindow }?.saveUiState() ?: restoredUserSettings)
            ?.let { outState.putBundle("tn_settings_ui", it) }
        pendingReleaseInstall?.let { outState.putString(PENDING_RELEASE_INSTALL_STATE, it.savedState()) }
        super.onSaveInstanceState(outState)
    }

    private fun loadUserPage(key: String) {
        val surface = userSettings ?: return
        val path = when (key) {
            "devices" -> "/v1/devices"
            "history" -> "/v1/history"
            "premium" -> "/v1/products"
            "diagnostics" -> "/v1/health"
            else -> "/v1/config"
        }
        worker.execute {
            try {
                val response = if (key == "diagnostics") {
                    val health = api("/v1/health", null, token = null)
                    val readiness = api("/v1/ready", null, token = null)
                    JSONObject().put("health", health).put("readiness", readiness)
                } else api(path, null)
                main.post { if (userSettings === surface) surface.dataPage(key, response) }
            } catch (error: Exception) { main.post { if (userSettings === surface) surface.loadError(error.message ?: "Chưa tải được dữ liệu TN.") } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        welcomeRevision.incrementAndGet()
        welcomeLaunchEligible = activationSource(intent) == "launcher"
        if (accessToken.isNullOrBlank()) return
        val source = activationSource(intent)
        val floatingAction = intent.getBooleanExtra(TnFloatingControlService.EXTRA_OPEN_PLAN, false) ||
            intent.getBooleanExtra(TnFloatingControlService.EXTRA_OPEN_TRAFFIC, false) ||
            intent.getBooleanExtra(TnFloatingControlService.EXTRA_OPEN_ASSISTANT, false)
        handleFloatingIntent()
        if (!floatingAction && intent.action == Intent.ACTION_MAIN) renderHome()
        recordEvent("app_activated", source)
    }

    private fun activationSource(intent: Intent?): String = when {
        intent?.getBooleanExtra(TnFloatingControlService.EXTRA_OPEN_PLAN, false) == true -> "floating_plan"
        intent?.getBooleanExtra(TnFloatingControlService.EXTRA_OPEN_TRAFFIC, false) == true -> "floating_traffic"
        intent?.getBooleanExtra(TnFloatingControlService.EXTRA_OPEN_ASSISTANT, false) == true -> "floating_assistant"
        intent?.let { it.getBooleanExtra("from_shortcut", false) && it.getStringExtra("activate_source") == "wake_word" } == true -> "wake_word"
        intent?.getBooleanExtra("from_shortcut", false) == true -> "shortcut"
        intent?.getBooleanExtra("from_touch_screen", false) == true -> "touch_screen"
        intent?.action == Intent.ACTION_MAIN -> "launcher"
        intent?.data != null -> "deep_link"
        else -> "external"
    }

    private fun handleFloatingIntent() {
        if (intent.getBooleanExtra(TnFloatingControlService.EXTRA_OPEN_PLAN, false)) {
            intent.removeExtra(TnFloatingControlService.EXTRA_OPEN_PLAN)
            renderSettings()
            val plan = vn.tn.assistant.ui.TnFloatingAccountPlan.read(this)
            if (plan?.effectiveKind() == "basic") loadUserPage("premium") else userSettings?.planDetails()
            return
        }
        if (intent.getBooleanExtra(TnFloatingControlService.EXTRA_OPEN_TRAFFIC, false)) {
            intent.removeExtra(TnFloatingControlService.EXTRA_OPEN_TRAFFIC)
            renderSettings(); userSettings?.traffic()
        } else if (intent.getBooleanExtra(TnFloatingControlService.EXTRA_OPEN_ASSISTANT, false)) {
            intent.removeExtra(TnFloatingControlService.EXTRA_OPEN_ASSISTANT)
            renderHome(); startVoice()
        }
    }

    private fun showHomePanel(panel: View) {
        val container = android.widget.FrameLayout(this)
        homeSurface?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            container.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        container.addView(panel, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(container)
    }

    private fun checkForUpdate() {
        status("Đang kiểm tra bản cập nhật TN…")
        val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
        worker.execute {
            try {
                val response = api("/v1/releases/latest?application_id=" + Uri.encode(packageName), null)
                val release = TnAppRelease.parse(response, packageName, currentVersionCode)
                main.post {
                    if (isFinishing || isDestroyed || !resumed) return@post
                    if (release == null) {
                        AlertDialog.Builder(this)
                            .setTitle("Cập nhật TN Assistant")
                            .setMessage("Bạn đang dùng phiên bản TN Assistant mới nhất.")
                            .setPositiveButton("ĐÓNG", null)
                            .show()
                    } else {
                        val notes = release.notes.take(1200)
                        val message = buildString {
                            append("Phiên bản ")
                            append(release.versionName)
                            append(" đã sẵn sàng.")
                            if (notes.isNotBlank()) {
                                append("\n\n")
                                append(notes)
                            }
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Cập nhật TN Assistant")
                            .setMessage(message)
                            .setNegativeButton("ĐỂ SAU", null)
                            .setPositiveButton("TẢI VÀ CÀI") { _, _ -> requestReleaseInstall(release) }
                            .show()
                    }
                }
            } catch (error: Exception) {
                main.post {
                    if (!isFinishing && !isDestroyed) status(error.message ?: "Chưa kiểm tra được bản cập nhật.")
                }
            }
        }
    }

    private fun requestReleaseInstall(release: TnAppRelease) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingReleaseInstall = release
            AlertDialog.Builder(this)
                .setTitle("Cho phép cài bản cập nhật")
                .setMessage("Cho phép TN Assistant cài bản cập nhật. Sau khi bạn cấp quyền, TN sẽ tải và kiểm tra APK trước khi mở trình cài đặt Android.")
                .setNegativeButton("ĐỂ SAU") { _, _ -> pendingReleaseInstall = null }
                .setOnCancelListener { pendingReleaseInstall = null }
                .setPositiveButton("MỞ CÀI ĐẶT") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
                    } catch (_: Exception) {
                        pendingReleaseInstall = null
                        status("Không mở được quyền cài đặt APK trong Cài đặt Android.")
                    }
                }
                .show()
            return
        }
        downloadRelease(release)
    }

    private fun resumePendingReleaseInstall() {
        val release = pendingReleaseInstall ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) return
        pendingReleaseInstall = null
        downloadRelease(release)
    }

    private fun downloadRelease(release: TnAppRelease) {
        if (!updateInProgress.compareAndSet(false, true)) {
            status("Đang tải bản cập nhật TN.")
            return
        }
        status("Đang tải và kiểm tra APK TN…")
        worker.execute {
            try {
                val apk = TnReleaseUpdater.download(this, release, client)
                main.post {
                    if (isFinishing || isDestroyed) {
                        apk.delete()
                        return@post
                    }
                    try {
                        TnReleaseUpdater.install(this, apk)
                    } catch (error: Exception) {
                        apk.delete()
                        status(error.message ?: "Không mở được trình cài đặt Android.")
                    }
                }
            } catch (error: Exception) {
                main.post {
                    if (!isFinishing && !isDestroyed) status(error.message ?: "Không tải được bản cập nhật TN.")
                }
            } finally {
                updateInProgress.set(false)
            }
        }
    }

    private fun sendCommand(text: String) {
        val command = text.trim()
        if (command.isBlank()) {
            status("Nhập hoặc đọc một lệnh trước.")
            return
        }
        welcomeRevision.incrementAndGet()
        dismissActionConfirmation()
        if (isWeather(command) && !hasLocationPermission()) {
            pendingText = command
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            } else {
                status("Enable location permission in Android Settings, then try again.")
            }
            return
        }
        val revision = commandRevision.incrementAndGet()
        val owner = sessionStore.actionGeneration()
        val active = { commandRevision.get() == revision && !isFinishing && !isDestroyed && sessionStore.ownsActions(owner) }
        status(command)
        worker.execute {
            try {
                if (!active()) return@execute
                val token = accessToken ?: throw IOException("Phiên TN đã hết hạn")
                val payload = vn.tn.assistant.api.TnAssistantConversation.shared.request(command,
                    UUID.randomUUID().toString(), deviceId, owner, active)
                if (isWeather(command)) {
                    val location = lastLocation()
                    if (location == null) throw IOException("Chưa có vị trí gần đây. Bật quyền vị trí và thử lại ngoài trời.")
                    payload.put("lat", location.latitude).put("lon", location.longitude)
                }
                val response = api("/v1/assistant/text", payload, token)
                if (!active()) return@execute
                handleAssistantResponse(response, token, owner, active)
            } catch (error: Exception) {
                main.post { if (active()) status(error.message ?: "Lệnh chưa thực hiện được.") }
            }
        }
    }

    private fun handleAssistantResponse(response: JSONObject, token: String, owner: Long, active: () -> Boolean) {
        if (!active()) return
        if (response.optBoolean("replayed")) {
            main.post { if (active()) status("Yêu cầu đã được xử lý trước đó; TN không chạy lại thao tác.") }
            return
        }
        val needsConfirmation = response.optBoolean("confirmation_required")
        if (needsConfirmation && !vn.tn.assistant.actions.TnActionConfirmation.shared.offer(response, owner, active))
            throw IOException("Chưa nhận được thông tin xác nhận hợp lệ. Vui lòng gửi lại yêu cầu.")
        val executable = if (needsConfirmation) vn.tn.assistant.actions.TnActionConfirmation.speechOnly(response) else response
        main.post { if (active()) status(response.optString("utterance", "TN đã nhận lệnh.")) }
        val summary = executeDirectives(executable, response.optString("request_id"), token, active)
        main.post {
            if (active() && !summary.cancelled) {
                if (needsConfirmation) showPendingConfirmation()
                else status(when {
                    summary.unsupported > 0 -> "Một số thao tác trong lệnh này chưa được TN hỗ trợ trên thiết bị."
                    summary.failed > 0 -> "Có thao tác chưa thực hiện được. Hãy kiểm tra quyền và ứng dụng trên thiết bị."
                    summary.interactionRequired > 0 -> "Yêu cầu đã chuyển tới ứng dụng trên thiết bị. Vui lòng kiểm tra kết quả tại đó."
                    else -> "TN đã xử lý xong lượt lệnh."
                })
            }
        }
    }

    private fun dismissActionConfirmation() { actionConfirmation?.dismiss(); actionConfirmation = null }

    private fun showPendingConfirmation(): Boolean {
        if (!resumed || isFinishing || isDestroyed || !sessionStore.hasSession()) return false
        if (actionConfirmation?.isShowing == true) return true
        val owner = sessionStore.actionGeneration()
        val pending = vn.tn.assistant.actions.TnActionConfirmation.shared.current(owner) ?: return false
        val revision = commandRevision.get()
        val active = { resumed && !isFinishing && !isDestroyed && commandRevision.get() == revision && sessionStore.ownsActions(owner) }
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val wakePause = TnWakeService.pauseForOutput()
        val expired = Runnable { if (actionConfirmation === dialog) { dialog.dismiss(); status("Xác nhận đã hết hạn. Vui lòng gửi lại yêu cầu.") } }
        dialog.setOnDismissListener {
            main.removeCallbacks(expired)
            vn.tn.assistant.actions.TnActionConfirmation.shared.dismiss(pending)
            wakePause.close()
            if (actionConfirmation === dialog) actionConfirmation = null
        }
        dialog.setContentView(vn.tn.assistant.ui.TnSettingsConfirmation(this, "Xác nhận thao tác", pending.summary,
            "HỦY", "XÁC NHẬN", onLeft = { dialog.dismiss(); status("Đã hủy yêu cầu; TN chưa thực hiện thao tác.") },
            onRight = {
                val request = vn.tn.assistant.actions.TnActionConfirmation.shared.consume(pending, deviceId, active)
                dialog.dismiss()
                if (request == null) status("Xác nhận không còn hiệu lực. Vui lòng gửi lại yêu cầu.")
                else sendConfirmedCommand(request, owner)
            }, onCancel = { dialog.cancel() }))
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        actionConfirmation = dialog
        try { dialog.show() } catch (_: RuntimeException) {
            actionConfirmation = null
            vn.tn.assistant.actions.TnActionConfirmation.shared.dismiss(pending)
            wakePause.close()
            return false
        }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        main.postDelayed(expired, (pending.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        return true
    }

    private fun sendConfirmedCommand(payload: JSONObject, owner: Long) {
        val revision = commandRevision.incrementAndGet()
        // A successful external Clock/Maps launch pauses this Activity. Keep
        // the same owner's receipt channel alive after that expected pause.
        val active = { !isFinishing && !isDestroyed && commandRevision.get() == revision && sessionStore.ownsActions(owner) }
        status("Đang chuyển yêu cầu đã xác nhận tới thiết bị…")
        worker.execute {
            try {
                if (!active() || !resumed) return@execute
                val token = accessToken ?: throw IOException("Phiên TN đã hết hạn")
                val response = api("/v1/assistant/text", payload, token)
                if (!resumed) return@execute
                handleAssistantResponse(response, token, owner, active)
            } catch (error: Exception) { main.post { if (active()) status(error.message ?: "Chưa thực hiện được yêu cầu đã xác nhận.") } }
        }
    }

    private fun executeDirectives(response: JSONObject, requestId: String, token: String, active: () -> Boolean) =
        vn.tn.assistant.actions.TnDirectiveExecutor(active,
            speech = { playSpeech(it, token, active) },
            map = { openMap(it, active) }, app = { openApp(it, active) },
            youtube = { payload ->
                val target = vn.tn.assistant.actions.TnYouTubeIntentCascade.build(this, payload)
                target != null && startIntent(target, active)
            },
            volume = vn.tn.assistant.actions.TnDirectiveExecutor.androidVolume(this),
            media = vn.tn.assistant.actions.TnMediaControls(this, active)::execute,
            alarm = vn.tn.assistant.actions.TnAlarmControls(this, active) { startIntent(it, active) }::execute,
            calendar = vn.tn.assistant.actions.TnCalendarControls(this, active) { requestCalendarAccess(active) }::execute,
            phone = vn.tn.assistant.actions.TnPhoneControls(this, active,
                launch = { startIntent(it, active, foregroundOnly = true) },
                authorize = { requestCallAccess(active) },
                authorizeContacts = { requestContactsAccess(active) },
                chooseContact = { chooseContactNumber(it, active) })::execute,
            receipt = { id, result, details ->
                api("/v1/device/actions/results", JSONObject().put("request_id", requestId)
                    .put("action_id", id).put("result", result).put("details", details), token)
            }).execute(response)

    private fun requestCalendarAccess(active: () -> Boolean) = requestActionAccess(
        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), REQUEST_CALENDAR, active)

    private fun requestCallAccess(active: () -> Boolean) = requestActionAccess(arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL, active)

    private fun requestContactsAccess(active: () -> Boolean) = requestActionAccess(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS, active)

    private fun chooseContactNumber(candidates: List<vn.tn.assistant.actions.TnContactNumber>, active: () -> Boolean): String? {
        if (candidates.isEmpty() || !active() || !resumed || isFinishing || isDestroyed) return null
        val done = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val selected = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var dialog: AlertDialog? = null
        fun finish(number: String?) {
            if (completed.compareAndSet(false, true)) {
                selected.set(number)
                done.countDown()
            }
        }
        main.post {
            if (!active() || !resumed || isFinishing || isDestroyed || contactChoiceDialog != null) {
                finish(null)
                return@post
            }
            val labels = candidates.map { candidate ->
                val digits = candidate.phoneNumber.filter { it in '0'..'9' }
                val suffix = digits.takeLast(4).padStart(minOf(4, digits.length), '•')
                val type = candidate.typeLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                "${candidate.displayName}$type · ••$suffix"
            }.toTypedArray()
            val current = AlertDialog.Builder(this)
                .setTitle("Chọn số điện thoại")
                .setItems(labels) { _, index -> finish(candidates.getOrNull(index)?.phoneNumber) }
                .setNegativeButton("Hủy") { _, _ -> finish(null) }
                .create()
            dialog = current
            contactChoiceDialog = current
            current.setOnCancelListener { finish(null) }
            current.setOnDismissListener {
                if (contactChoiceDialog === current) contactChoiceDialog = null
                finish(selected.get())
            }
            try { current.show() } catch (_: RuntimeException) {
                if (contactChoiceDialog === current) contactChoiceDialog = null
                finish(null)
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 30000
        try {
            while (active() && resumed && !isFinishing && !isDestroyed && SystemClock.elapsedRealtime() < deadline &&
                !done.await(50, TimeUnit.MILLISECONDS)) { }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            finish(null)
            main.post { if (contactChoiceDialog === dialog) contactChoiceDialog?.dismiss() }
        }
        return selected.get()
    }

    private fun requestActionAccess(permissions: Array<String>, code: Int, active: () -> Boolean): Boolean {
        fun granted() = permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (granted()) return true
        if (android.os.Build.VERSION.SDK_INT < 23) return false
        val request = ActionPermissionRequest(code, permissions.toSet())
        main.post {
            if (request.abandoned || !active() || !resumed || isFinishing || isDestroyed || actionPermissionRequest != null) request.done.countDown()
            else {
                actionPermissionRequest = request
                try {
                    request.launched = true
                    requestPermissions(permissions, code)
                }
                catch (_: RuntimeException) { actionPermissionRequest = null; request.done.countDown() }
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 60000
        try {
            while (active() && SystemClock.elapsedRealtime() < deadline && !request.done.await(50, TimeUnit.MILLISECONDS)) { }
            // The permission result can precede onResume. Do not lose a
            // just-approved call, or launch it while the system dialog remains.
            val resumeDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 2000)
            while (request.allowed && active() && !resumed && SystemClock.elapsedRealtime() < resumeDeadline) Thread.sleep(25)
            return active() && request.allowed && resumed && granted()
        } finally {
            request.abandoned = true
            // Keep an outstanding OS dialog associated with its own request.
            // Its late callback must not authorize a subsequent command.
            main.post {
                if (actionPermissionRequest === request && (!request.launched || request.done.count == 0L)) actionPermissionRequest = null
            }
        }
    }

    private fun playSpeech(text: String, token: String, active: () -> Boolean): Boolean {
        if (text.isBlank() || !active()) return false
        val response = api("/v1/tts", JSONObject().put("text", text), token)
        val file = apiAudio(response.optString("url"), token)
        val player = MediaPlayer()
        val completed = CountDownLatch(1)
        val wakePause = TnWakeService.pauseForOutput()
        val terminated = AtomicBoolean(false)
        var success = false
        val focus = TnSpeechAudioFocus(this, preferences.getString("playback_mode", "default") == "advanced") {
            terminated.set(true)
            completed.countDown()
        }
        try {
            if (!active() || !focus.request()) return false
            player.setAudioAttributes(TnSpeechAudioFocus.audioAttributes())
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.setOnCompletionListener { success = !terminated.get(); completed.countDown() }
            player.setOnErrorListener { _, _, _ -> completed.countDown(); true }
            main.post {
                synchronized(player) {
                    if (active() && !terminated.get()) try { player.start() } catch (_: Exception) { completed.countDown() }
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 35000
            while (active() && SystemClock.elapsedRealtime() < deadline && !completed.await(50, TimeUnit.MILLISECONDS)) { }
            return active() && success && !terminated.get()
        } finally {
            terminated.set(true)
            synchronized(player) { try { player.release() } catch (_: Exception) { } }
            focus.close()
            wakePause.close()
            file.delete()
        }
    }

    private fun openMap(rawUrl: String, active: () -> Boolean): Boolean {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host != "www.google.com" || !uri.path.orEmpty().startsWith("/maps/")) return false
        return startIntent(Intent(Intent.ACTION_VIEW, uri), active)
    }

    private fun openApp(packageName: String, active: () -> Boolean): Boolean {
        if (packageName !in setOf("com.google.android.apps.maps", "com.google.android.youtube", "com.spotify.music", "com.android.chrome")) return false
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return startIntent(intent, active)
    }

    private fun startIntent(intent: Intent, active: () -> Boolean, foregroundOnly: Boolean = false): Boolean {
        val completed = CountDownLatch(1)
        val expired = AtomicBoolean(false)
        var success = false
        main.post {
            try { if (!expired.get() && active() && (!foregroundOnly || resumed)) { startActivity(intent); success = true } } catch (_: Exception) { }
            completed.countDown()
        }
        if (!completed.await(5, TimeUnit.SECONDS)) expired.set(true)
        return !expired.get() && active() && success
    }

    private fun startVoice() {
        welcomeRevision.incrementAndGet()
        if (TnWakeService.captureNow()) { homeSurface?.setListening(true); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            else status("Enable microphone permission in Android Settings, then try again.")
            return
        }
        val capture = recording.begin() ?: return
        commandRevision.incrementAndGet()
        val owner = sessionStore.actionGeneration()
        foregroundCapture = capture
        homeSurface?.setListening(true)
        status("TN đang mở micro…")
        Thread({ captureSpeech(capture, owner) }, "tn-foreground-voice").start()
    }

    private fun captureSpeech(capture: TnRecordingSessionGate.Capture, owner: Long) {
        val active = { capture.isActive && !isFinishing && !isDestroyed && sessionStore.ownsActions(owner) }
        var engine: vn.tn.assistant.audio.tn.SileroVad? = null
        var recorder: AudioRecord? = null
        var speechStream: vn.tn.assistant.api.TnSpeechStreamSession? = null
        try {
            // A microphone tap is explicit activation. Use independent VAD
            // without loading a wake model or requiring a spoken wake prefix.
            engine = vn.tn.assistant.audio.tn.SileroVad(this, preferences.getInt("command_end_silence_ms", 1000))
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw IOException("Thiết bị không hỗ trợ micro PCM 16 kHz")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw IOException("Microphone permission is required to record audio")
            }
            recorder = TnRecorderStartupPolicy.process.start(
                advancedRecording = preferences.getString("record_mode", "default") == "advanced",
                create = {
                    AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096)).also {
                        if (it.state != AudioRecord.STATE_INITIALIZED) {
                            it.release()
                            throw IOException("Không mở được micro")
                        }
                    }
                },
                begin = { it.startRecording() },
                discard = {
                    try { it.stop() } catch (_: Exception) { }
                    it.release()
                },
                active = active,
            )
            audioRecorder = recorder
            val audio = ByteArrayOutputStream(128 * 1024)
            val voiceBackend = TnVoiceBackendClient(this)
            val token = accessToken ?: throw IOException("Phiên TN đã hết hạn")
            var streamAttempted = false
            val samples = ShortArray(512)
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var speechSeen = false
            main.post { if (active()) status("Đang nghe. Hãy nói câu lệnh, tối đa 15 giây.") }
            while (active() && SystemClock.elapsedRealtime() < deadline) {
                val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                } else {
                    recorder.read(samples, 0, samples.size)
                }
                if (count < 0) throw IOException("Không đọc được âm thanh từ micro")
                if (count == 0) continue
                val chunk = ByteArray(count * 2)
                for (index in 0 until count) {
                    val value = samples[index].toInt()
                    chunk[index * 2] = (value and 0xff).toByte()
                    chunk[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
                }
                audio.write(chunk)
                val endpoint = engine.accept(samples, count)
                speechSeen = speechSeen || engine.isSpeech
                if (speechSeen && !streamAttempted) {
                    streamAttempted = true
                    speechStream = runCatching {
                        voiceBackend.speechStream(token) { partial ->
                            main.post { if (active()) commandView?.setText(partial) }
                        }
                    }.getOrNull()
                    speechStream?.sendAudio(audio.toByteArray())
                } else if (speechStream != null) {
                    speechStream?.sendAudio(chunk)
                }
                if (endpoint) break
            }
            recorder.stop()
            if (!active()) return
            if (!speechSeen) throw IOException("TN chưa nghe thấy giọng nói. Hãy nhấn micro và nói lại.")
            if (audio.size() < 320) throw IOException("Chưa nhận được câu lệnh")
            main.post { if (active()) status("TN đang nhận dạng giọng nói…") }
            val pcm = audio.toByteArray()
            val recognized = if (speechStream == null) {
                voiceBackend.pcm(pcm).optString("text")
            } else {
                try {
                    speechStream?.finish() ?: voiceBackend.pcm(pcm).optString("text")
                } catch (_: TnSpeechStreamFallback) {
                    voiceBackend.pcm(pcm).optString("text")
                }
            }
            val text = WakePhrase.commandFromMicrophoneTap(recognized)
            if (text.isBlank()) throw IOException("TN chưa nhận được câu lệnh. Hãy nhấn micro và nói lại.")
            main.post {
                if (!active()) return@post
                commandView?.setText(text)
                sendCommand(text)
            }
        } catch (error: Exception) {
            if (active()) main.post { if (active()) status(error.message ?: "Không thể nhận dạng giọng nói.") }
        } finally {
            try { speechStream?.close() } catch (_: Exception) { }
            try { recorder?.release() } catch (_: Exception) { }
            audioRecorder = null
            try { engine?.close() } catch (_: Exception) { }
            // Keep this capture's slot until its queued UI/result work has drained.
            main.post {
                try { homeSurface?.setListening(false) } finally {
                    if (foregroundCapture === capture) foregroundCapture = null
                    capture.close()
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun lastLocation(): Location? {
        if (!hasLocationPermission()) return null
        return try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time <= 10 * 60 * 1000 }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) { null }
    }

    private fun isWeather(text: String) = text.contains("thời tiết", true) || text.contains("nhiệt độ", true) || text.contains("trời hôm nay", true)

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximum, 64 * 1024))
        input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() > maximum - count) throw IOException("Audio response exceeds the size limit")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_TRAFFIC_LOCATION -> {
                userSettings?.refresh()
                if (!vn.tn.assistant.driving.TnRoadMonitor.preciseAllowed(this))
                    userSettings?.loadError("Chưa có quyền vị trí chính xác. Bạn có thể cấp quyền trong Cài đặt Android rồi mở lại mục dữ liệu đường.")
            }
            REQUEST_CALENDAR, REQUEST_CALL, REQUEST_CONTACTS -> actionPermissionRequest?.takeIf { it.code == requestCode }?.let {
                actionPermissionRequest = null
                it.allowed = !it.abandoned && permissions.toSet() == it.permissions &&
                    grantResults.size == it.permissions.size && grantResults.all { result -> result == PackageManager.PERMISSION_GRANTED }
                it.done.countDown()
            }
            REQUEST_WAKE_AUDIO, REQUEST_WAKE_NOTIFICATION -> {
                val pending = wakePermissionPending
                wakePermissionPending = false
                if (pending && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    if (resumed) main.post { setWakeEnabled(true) } else wakeStartWhenResumed = true
                }
                else {
                    userSettings?.refresh()
                    userSettings?.loadError("Bạn chưa cấp quyền micro/thông báo; nghe “TN ơi” vẫn tắt.")
                }
            }
            REQUEST_SETUP_AUDIO -> if (setupStep == 1) {
                if (missingSetupPermissions().isEmpty()) renderSetup(2)
                else {
                    renderSetup(1)
                    showPermissionDeferral(1)
                }
            }
            REQUEST_AUDIO -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
                else status("Bạn chưa cấp quyền micro; TN chưa ghi âm.")
            REQUEST_LOCATION -> {
                val pending = pendingText
                pendingText = null
                if (grantResults.any { it == PackageManager.PERMISSION_GRANTED } && pending != null) sendCommand(pending)
                else status("Bạn chưa cấp quyền vị trí; TN chưa gửi tọa độ thời tiết.")
            }
        }
    }

    private fun logout() {
        val currentRefresh = refreshToken
        val token = accessToken
        clearSession()
        worker.execute {
            if (!currentRefresh.isNullOrBlank()) runCatching {
                api("/v1/auth/logout", JSONObject().put("refresh_token", currentRefresh), token)
            }
        }
        renderAuth("Đã đăng xuất khỏi TN.")
    }

    private fun deleteAccount() {
        val surface = userSettings ?: return
        val token = accessToken
        if (token.isNullOrBlank()) {
            surface.loadError("Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.")
            return
        }
        surface.saving(true)
        worker.execute {
            try {
                api("/v1/me", JSONObject(), token, "DELETE")
                main.post {
                    if (userSettings !== surface || isFinishing || isDestroyed) return@post
                    clearSession()
                    authMode = "choice"
                    renderAuth("Tài khoản đã được xoá.")
                }
            } catch (error: Exception) {
                main.post {
                    if (userSettings === surface && !isFinishing && !isDestroyed) {
                        surface.saving(false)
                        surface.loadError(error.message ?: "Không xoá được tài khoản. Vui lòng thử lại.")
                    }
                }
            }
        }
    }

    private fun recordEvent(kind: String, activationSource: String? = null) {
        val token = accessToken ?: return
        worker.execute {
            runCatching {
                val data = JSONObject().put("app", "TN Assistant").put("version", BuildConfig.VERSION_NAME)
                if (!activationSource.isNullOrBlank()) data.put("activation_source", activationSource)
                api("/v1/device/events", JSONObject().put("event_id", UUID.randomUUID().toString())
                    .put("kind", kind).put("occurred_at", utcTimestamp())
                    .put("data", data), token)
            }
        }
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120).ifBlank { "Android device" }

    private fun utcTimestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private fun clearSession() {
        commandRevision.incrementAndGet()
        welcomeRevision.incrementAndGet()
        dismissActionConfirmation()
        vn.tn.assistant.api.TnAssistantConversation.shared.retire(sessionStore.actionGeneration())
        TnWakeService.disable(this)
        TnFloatingControlService.disable(this)
        sessionStore.clear()
        vn.tn.assistant.ui.TnFloatingAccountPlan.clear(this)
    }

    private fun page(): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        return content
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 26f
        setTextColor(Color.rgb(17, 37, 77))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(14))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.rgb(74, 84, 99))
        setPadding(0, 0, 0, dp(12))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.rgb(45, 55, 69))
        setPadding(0, dp(12), 0, dp(10))
    }

    private fun field(hintText: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintText
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextColor(Color.rgb(25, 35, 48))
        setHintTextColor(Color.GRAY)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(22, 121, 196))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        }
    }

    private fun status(text: String) {
        main.post {
            statusView?.text = text
            homeSurface?.showStatus(text)
        }
    }

    private fun showLoading(text: String) {
        setupWindow(showNavigation = setupStep?.let { it >= 4 } ?: true)
        statusView = null
        val surface = TnOnboardingSurface(this, 0, "TN Assistant", text, "ĐANG KẾT NỐI…", {})
        surface.primaryButton.isEnabled = false
        setContentView(surface)
    }

    private fun renderSessionRetry() {
        setupWindow(showNavigation = setupStep?.let { it >= 4 } ?: true)
        setContentView(TnOnboardingSurface(this, 0, "TN Assistant",
            "Chưa kết nối được dịch vụ TN. Phiên đăng nhập đã lưu vẫn được giữ trên thiết bị.",
            "THỬ LẠI", { recreate() }))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        narrator.interrupt()
        if (setupStep == 4 && authMode == "choice" && setupSurface?.dismissOverflowMenu() == true) return
        if (homePanelOpen) {
            guideSurface?.let { it.closeWithAnimation(); return }
            if (userSettings?.back() == true) return
            userSettings?.let { it.closeWithAnimation(); return }
            renderHome()
            return
        }
        val step = setupStep
        if (step == 4 && authMode != "choice") {
            zaloRevision++
            zaloAttempt = null
            zaloExpiresAt = 0L
            zaloEndDialog?.dismiss()
            zaloEndDialog = null
            closeZaloBrowser()
            authMode = "choice"
            if (zaloFromSettings && !accessToken.isNullOrBlank()) {
                zaloFromSettings = false
                setupStep = null
                renderSettings()
                return
            }
            renderAuth()
            return
        }
        when {
            step == null || step == 0 -> super.onBackPressed()
            else -> renderSetup((step - 1).coerceAtLeast(0))
        }
    }

    override fun onDestroy() {
        commandRevision.incrementAndGet()
        welcomeRevision.incrementAndGet()
        actionPermissionRequest?.let { it.abandoned = true; it.done.countDown() }
        actionPermissionRequest = null
        dismissActionConfirmation()
        TnWakeUi.detach(wakeUi)
        zaloEndDialog?.dismiss()
        closeZaloBrowser()
        narrator.close()
        zaloRevision++
        foregroundCapture?.cancel()
        try { audioRecorder?.stop() } catch (_: Exception) { }
        // The capture worker owns release; stop only unblocks a pending read.
        worker.shutdownNow()
        welcomeWorker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val PENDING_RELEASE_INSTALL_STATE = "tn_pending_release_install"
        private const val REQUEST_AUDIO = 20
        private const val REQUEST_WAKE_AUDIO = 40
        private const val REQUEST_WAKE_NOTIFICATION = 41
        private const val REQUEST_LOCATION = 21
        private const val REQUEST_CALENDAR = 42
        private const val REQUEST_CALL = 43
        private const val REQUEST_CONTACTS = 44
        private const val REQUEST_TRAFFIC_LOCATION = 45
        private const val REQUEST_SETUP_AUDIO = 30
        private const val REQUEST_SETUP_LOCATION = 31
        private const val SAMPLE_RATE = 16000
        private const val MAX_AUDIO_BYTES = 16 shl 20
    }

    private class TNApiException(val statusCode: Int, message: String, val errorCode: String? = null) : IOException(message)
}
