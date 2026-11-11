package vn.tn.assistant.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import vn.tn.assistant.MainActivity
import vn.tn.assistant.R
import vn.tn.assistant.api.TnVoiceBackendClient
import vn.tn.assistant.auth.TnSessionStore
import vn.tn.assistant.audio.tn.WakeCommandWindow
import vn.tn.assistant.audio.tn.WakePhrase
import vn.tn.assistant.ui.TnFloatingControlService
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in microphone FGS. Wake inference stays on the device; only an activated utterance reaches TN. */
class TnWakeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private val forcedCapture = AtomicBoolean(false)
    private val cancelCapture = AtomicBoolean(false)
    private val outputPauses = AtomicInteger(0)
    private lateinit var backend: TnVoiceBackendClient
    private lateinit var prefs: android.content.SharedPreferences
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var message = "Đang khởi động nghe “TN ơi”…"
    @Volatile private var listeningForCommand = false
    @Volatile private var readFrames = 0L
    @Volatile private var ready = false
    @Volatile private var loginOwner: Long? = null

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        backend = TnVoiceBackendClient(this)
        prefs = getSharedPreferences("tn-session-v1", MODE_PRIVATE)
        instance = this
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { disable(this); return START_NOT_STICKY }
        if (!consented(this) || !microphoneAllowed(this) || !TnSessionStore.get(this).hasSession()) {
            fail("Chưa có quyền micro hoặc phiên đăng nhập TN.")
            return START_NOT_STICKY
        }
        try {
            val notification = notification(message)
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification)
        } catch (_: RuntimeException) {
            fail("Android chưa cho phép nghe nền. Hãy mở TN Assistant và bật lại.")
            return START_NOT_STICKY
        }
        if (active.compareAndSet(false, true)) {
            loginOwner = TnSessionStore.get(this).actionGeneration()
            captureThread = Thread(::listen, "tn-wake-listener").also { it.start() }
        }
        return START_NOT_STICKY // Never open a microphone after boot or silently restart from the background.
    }

    private fun verifyEntitlement() {
        val me = backend.json("/v1/me")
        if (me.optJSONObject("premium_features")?.optBoolean("wake_word") != true) throw IOException("Tài khoản chưa có quyền dùng “TN ơi”.")
        val values = backend.json("/v1/settings")
        if (!values.optBoolean("wake_word_enabled")) throw IOException("“TN ơi” đang tắt trong cài đặt tài khoản.")
        for (key in listOf("conversation_idle_timeout_ms", "command_end_silence_ms")) {
            if (values.has(key)) prefs.edit().putInt(key, values.optInt(key)).apply()
        }
    }

    private fun listen() {
        var lease: TnRecordingSessionGate.Capture? = null
        var engine: NativeAudio? = null
        val window = WakeCommandWindow()
        try {
            verifyEntitlement()
            if (!enabled()) return
            val captureDeadline = SystemClock.elapsedRealtime() + 10000
            while (enabled() && lease == null) {
                if (SystemClock.elapsedRealtime() >= captureDeadline) throw IOException("Micro đang được dùng. Hãy dừng ghi âm hoặc phát giọng nói rồi bật lại “TN ơi”.")
                lease = TnRecordingSessionGate.process.begin()
                if (lease == null) Thread.sleep(50)
            }
            val owned = lease ?: return
            engine = NativeAudio(this, prefs.getInt("command_end_silence_ms", 1000))
            engine.init()
            if (!enabled()) return
            var lastVerification = SystemClock.elapsedRealtime()
            var paused = false
            val samples = ShortArray(512)
            update("Đang chờ “TN ơi”", false)
            while (enabled() && owned.isActive) {
                if (!microphoneAllowed(this)) throw IOException("Quyền micro đã bị thu hồi. “TN ơi” đã dừng.")
                if (SystemClock.elapsedRealtime() - lastVerification >= 300000) {
                    closeRecorder()
                    verifyEntitlement()
                    lastVerification = SystemClock.elapsedRealtime()
                }
                if (outputPauses.get() > 0) {
                    closeRecorder()
                    if (!paused) { window.reset(); engine.reset(); paused = true }
                    Thread.sleep(40)
                    continue
                }
                if (paused) { engine.reset(); window.reset(); paused = false }
                if (recorder == null) recorder = openRecorder()
                if (cancelCapture.compareAndSet(true, false)) {
                    forcedCapture.set(false); window.reset(); engine.reset()
                    update("Đã dừng câu lệnh. Đang chờ “TN ơi”", false)
                }
                if (forcedCapture.compareAndSet(true, false)) {
                    window.followUp(SystemClock.elapsedRealtime(), prefs.getInt("conversation_idle_timeout_ms", 10000))
                    engine.reset()
                    update("Đang nghe câu lệnh…", true)
                }
                val count = recorder!!.read(samples, 0, samples.size)
                if (!enabled()) break
                if (count < 0) throw IOException("Không đọc được micro. Hãy đóng ứng dụng đang chiếm micro rồi bật lại.")
                if (count == 0) { Thread.sleep(10); continue }
                readFrames++
                if (!ready) {
                    ready = true
                    prefs.edit().putBoolean("wake_word_enabled", true).apply()
                    update("Đang chờ “TN ơi” · Micro đang hoạt động", false)
                }
                engine.setEndSilenceMillis(prefs.getInt("command_end_silence_ms", 1000))
                val woke = window.waitingForWake && engine.wake(samples, count)
                val ended = engine.endpoint(samples, count)
                if (woke) update("TN đang nghe…", true)
                val previousPhase = window.phase
                val utterance = window.accept(samples, count, woke, engine.isSpeech, ended, SystemClock.elapsedRealtime())
                if (!woke && previousPhase != WakeCommandWindow.Phase.CAPTURING && window.phase == WakeCommandWindow.Phase.CAPTURING) update("TN đang nghe…", true)
                if (previousPhase == WakeCommandWindow.Phase.FOLLOW_UP && window.waitingForWake) update("Đang chờ “TN ơi”", false)
                if (utterance != null) {
                    closeRecorder() // No microphone frames or TTS echo enter another turn during network/playback.
                    update("TN đang nhận dạng…", false)
                    var failure: String? = null
                    val continueConversation = try { process(utterance) } catch (error: Exception) {
                        failure = error.message ?: "Chưa xử lý được câu lệnh qua VPS."
                        false
                    }
                    engine.reset()
                    if (continueConversation) window.followUp(SystemClock.elapsedRealtime(), prefs.getInt("conversation_idle_timeout_ms", 10000))
                    else window.reset()
                    if (enabled()) update(failure ?: if (continueConversation) "Bạn có thể nói tiếp…" else "Đang chờ “TN ơi”", false)
                }
            }
        } catch (_: InterruptedException) {
            // Explicit stop owns cancellation, not a retry or a new microphone session.
        } catch (error: Throwable) {
            if (active.get()) fail(if (error is UnsatisfiedLinkError) "Thiết bị chưa nạp được mô hình giọng nói TN." else error.message?.take(220) ?: "Không khởi động được micro TN.")
        } finally {
            ready = false
            closeRecorder()
            runCatching { engine?.close() }
            window.reset()
            lease?.close()
            active.set(false)
            main.post { if (instance === this) stopSelf() }
        }
    }

    private fun openRecorder(): AudioRecord {
        val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (size <= 0) throw IOException("Micro không hỗ trợ PCM 16 kHz")
        return TnRecorderStartupPolicy.process.start(
            prefs.getString("record_mode", "default") == "advanced",
            create = {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("Quyền micro chưa được cấp")
                }
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(size, 8192)).also {
                    if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); throw IOException("Không mở được micro") }
                }
            }, begin = { it.startRecording() }, discard = { runCatching { it.stop() }; it.release() }, active = ::enabled,
        )
    }
    private fun closeRecorder() {
        val held = recorder ?: return
        recorder = null
        runCatching { held.stop() }; runCatching { held.release() }
    }

    private fun process(utterance: WakeCommandWindow.Utterance): Boolean {
        if (!enabled()) return false
        val transcript = backend.pcm(utterance.pcm).optString("text").trim()
        if (!enabled()) return false
        val text = if (utterance.requiresWakePrefix) WakePhrase.commandAfterConfirmedWake(transcript) ?: return false else transcript
        if (text.isBlank()) return true // A wake alone waits for the next spoken command.
        main.post { if (enabled()) TnWakeUi.transcript(text) }
        val device = prefs.getString("device_id", null) ?: throw IOException("Thiếu định danh thiết bị TN")
        val owner = loginOwner ?: return false
        val payload = vn.tn.assistant.api.TnAssistantConversation.shared.request(text,
            UUID.randomUUID().toString(), device, owner, ::enabled)
        if (text.contains("thời tiết", true) || text.contains("nhiệt độ", true)) {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val location = try { manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time <= 600000 }.maxByOrNull { it.time } } catch (_: SecurityException) { null }
            if (location == null) throw IOException("Mở TN để cấp quyền vị trí trước khi hỏi thời tiết.")
            payload.put("lat", location.latitude).put("lon", location.longitude)
        }
        val response = backend.json("/v1/assistant/text", payload)
        if (response.optBoolean("replayed")) return true
        val turnActive = { enabled() && !cancelCapture.get() && !forcedCapture.get() }
        val needsConfirmation = response.optBoolean("confirmation_required")
        if (needsConfirmation && !vn.tn.assistant.actions.TnActionConfirmation.shared.offer(response, owner, turnActive))
            throw IOException("Chưa nhận được thông tin xác nhận hợp lệ. Vui lòng gửi lại yêu cầu.")
        val executable = if (needsConfirmation) vn.tn.assistant.actions.TnActionConfirmation.speechOnly(response) else response
        val summary = vn.tn.assistant.actions.TnDirectiveExecutor(turnActive,
            speech = { speak(it, turnActive) },
            map = {
                val uri = Uri.parse(it)
                uri.scheme == "https" && uri.host == "www.google.com" && uri.path.orEmpty().startsWith("/maps/") && launch(Intent(Intent.ACTION_VIEW, uri), turnActive)
            }, app = { pkg ->
                val intent = if (pkg in setOf("com.google.android.apps.maps", "com.google.android.youtube", "com.spotify.music", "com.android.chrome")) packageManager.getLaunchIntentForPackage(pkg) else null
                intent != null && launch(intent, turnActive)
            }, volume = vn.tn.assistant.actions.TnDirectiveExecutor.androidVolume(this),
            youtube = { payload ->
                val intent = vn.tn.assistant.actions.TnYouTubeIntentCascade.build(this, payload)
                intent != null && launch(intent, turnActive)
            },
            media = vn.tn.assistant.actions.TnMediaControls(this, turnActive)::execute,
            alarm = vn.tn.assistant.actions.TnAlarmControls(this, turnActive) { launch(it, turnActive) }::execute,
            calendar = vn.tn.assistant.actions.TnCalendarControls(this, turnActive)::execute,
            phone = vn.tn.assistant.actions.TnPhoneControls(this, turnActive, launch = { launch(it, turnActive) })::execute,
            receipt = { id, result, details ->
                backend.json("/v1/device/actions/results", JSONObject().put("request_id", response.optString("request_id"))
                    .put("action_id", id).put("result", result).put("details", details))
            }).execute(executable)
        if (turnActive() && summary.unsupported > 0) throw IOException("Một số thao tác chưa được TN hỗ trợ trên thiết bị.")
        if (turnActive() && summary.failed > 0) throw IOException("Có thao tác chưa thực hiện được. Hãy kiểm tra quyền và ứng dụng.")
        if (turnActive() && summary.interactionRequired > 0) throw IOException("Yêu cầu đã chuyển tới ứng dụng trên thiết bị. Vui lòng kiểm tra kết quả tại đó.")
        if (needsConfirmation) {
            main.post { if (enabled() && !TnWakeUi.confirm()) update("Mở TN Assistant để xem và xác nhận thao tác.", false) }
            return false
        }
        return !summary.cancelled
    }

    private fun launch(intent: Intent, turnActive: () -> Boolean): Boolean {
        val finished = CountDownLatch(1)
        val launched = AtomicBoolean(false)
        val expired = AtomicBoolean(false)
        main.post {
            try {
                if (!expired.get() && turnActive()) {
                    if (TnWakeUi.launch(intent)) launched.set(true)
                    else if (TnFloatingControlService.isVisible()) { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); launched.set(true) }
                }
            } catch (_: RuntimeException) { } finally { finished.countDown() }
        }
        if (!finished.await(5, TimeUnit.SECONDS)) expired.set(true)
        if (!launched.get() && turnActive()) update("Android chưa cho mở ứng dụng từ nền. Hãy mở TN hoặc bật Tiện ích nổi.", false)
        return !expired.get() && turnActive() && launched.get()
    }

    private fun speak(text: String, turnActive: () -> Boolean): Boolean {
        if (text.isBlank() || !turnActive()) return false
        val response = backend.json("/v1/tts", JSONObject().put("text", text))
        val (bytes, extension) = backend.audio(response.optString("url"))
        val file = File.createTempFile("tn-wake-", ".$extension", cacheDir)
        val done = CountDownLatch(1)
        val success = AtomicBoolean(false)
        val lostFocus = AtomicBoolean(false)
        val player = MediaPlayer()
        val focus = TnSpeechAudioFocus(this, prefs.getString("playback_mode", "default") == "advanced") { lostFocus.set(true); done.countDown() }
        try {
            if (!turnActive() || !focus.request()) return false
            file.writeBytes(bytes)
            player.setAudioAttributes(TnSpeechAudioFocus.audioAttributes())
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { success.set(true); done.countDown() }
            player.setOnErrorListener { _, _, _ -> done.countDown(); true }
            player.prepare(); player.start()
            val deadline = SystemClock.elapsedRealtime() + 35000
            while (turnActive() && SystemClock.elapsedRealtime() < deadline && !done.await(100, TimeUnit.MILLISECONDS)) { }
            return turnActive() && success.get() && !lostFocus.get()
        } finally {
            runCatching { player.release() }; focus.close(); file.delete()
        }
    }

    private fun enabled() = active.get() && consented(this) && !Thread.currentThread().isInterrupted &&
        loginOwner?.let { TnSessionStore.get(this).ownsActions(it) } == true
    private fun update(text: String, listening: Boolean) {
        message = text
        listeningForCommand = listening
        main.post {
            if (instance !== this) return@post
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification(text))
            TnWakeUi.state(text, listening)
        }
    }
    private fun fail(text: String) {
        ready = false
        control(this).edit().putBoolean(KEY_CONSENT, false).putString(KEY_ERROR, text).apply()
        prefs.edit().putBoolean("wake_word_enabled", false).apply()
        update(text, false)
        active.set(false)
        main.post { stopSelf() }
    }
    private fun notification(text: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Nghe TN ơi", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 167, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val stop = PendingIntent.getService(this, 168, Intent(this, TnWakeService::class.java).setAction(ACTION_STOP), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.tn_floating_microphone).setContentTitle("TN ơi · Micro đang bật")
            .setContentText(text).setContentIntent(open).setOngoing(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(0, "TẮT NGHE NỀN", stop).build()).build()
    }
    override fun onDestroy() {
        active.set(false)
        backend.cancel()
        captureThread?.interrupt()
        // The worker owns release; stop only unblocks its read.
        runCatching { recorder?.stop() }
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        TnWakeUi.state(control(this).getString(KEY_ERROR, null) ?: "Đã tắt nghe “TN ơi”.", false)
        super.onDestroy()
    }

    companion object {
        private const val KEY_CONSENT = "microphone_consent"
        private const val KEY_ERROR = "last_error"
        private const val ACTION_STOP = "vn.tn.assistant.STOP_WAKE_LISTENING"
        private const val CHANNEL = "tn-wake-microphone"
        private const val NOTIFICATION = 167
        @Volatile private var instance: TnWakeService? = null
        private fun control(context: Context) = context.getSharedPreferences("tn-wake-control-v1", Context.MODE_PRIVATE)
        fun consented(context: Context) = control(context).getBoolean(KEY_CONSENT, false)
        fun isRunning() = instance?.active?.get() == true
        fun isRecording() = runCatching { instance?.recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING }.getOrDefault(false)
        fun capturedFrames(): Long = instance?.readFrames ?: 0L
        fun isReady(): Boolean = instance?.let { it.ready && it.active.get() && it.readFrames > 0 } == true
        fun status(context: Context): String = lastError(context) ?: when {
            isReady() -> "Đang nghe “TN ơi” · Micro đang hoạt động"
            consented(context) -> "Đang bật nghe “TN ơi”…"
            else -> "Đã tắt nghe “TN ơi”."
        }
        fun lastError(context: Context): String? = control(context).getString(KEY_ERROR, null)
        fun microphoneAllowed(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        fun enableFromVisibleActivity(context: Context): Boolean {
            if (!microphoneAllowed(context)) return false
            control(context).edit().putBoolean(KEY_CONSENT, true).remove(KEY_ERROR).apply()
            return try { ContextCompat.startForegroundService(context, Intent(context, TnWakeService::class.java)); true }
            catch (_: RuntimeException) { control(context).edit().putBoolean(KEY_CONSENT, false).putString(KEY_ERROR, "Mở TN Assistant để bật nghe nền.").apply(); false }
        }
        fun disable(context: Context) {
            control(context).edit().putBoolean(KEY_CONSENT, false).remove(KEY_ERROR).apply()
            context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE).edit().putBoolean("wake_word_enabled", false).apply()
            context.stopService(Intent(context, TnWakeService::class.java))
        }
        fun microphoneRevoked(context: Context) {
            disable(context)
            val message = "Quyền micro đã bị thu hồi. Hãy cấp lại quyền rồi bật “TN ơi”."
            control(context).edit().putString(KEY_ERROR, message).apply()
            TnWakeUi.state(message, false)
        }
        fun captureNow(): Boolean = instance?.let {
            if (!it.active.get()) false else {
                if (it.listeningForCommand) it.cancelCapture.set(true) else it.forcedCapture.set(true)
                true
            }
        } ?: false
        fun pauseForOutput(): AutoCloseable {
            val service = instance
            service?.outputPauses?.incrementAndGet()
            val closed = AtomicBoolean(false)
            return AutoCloseable { if (closed.compareAndSet(false, true)) service?.outputPauses?.decrementAndGet() }
        }
    }
}
