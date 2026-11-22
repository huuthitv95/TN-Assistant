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
import vn.tn.assistant.audio.tn.TnWakeTurnPolicy
import vn.tn.assistant.audio.tn.WakeCommandWindow
import vn.tn.assistant.audio.tn.WakePhrase
import vn.tn.assistant.ui.TnFloatingControlService
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in microphone FGS. Wake inference stays on the device; only an activated utterance reaches TN. */
class TnWakeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private val forcedCapture = AtomicBoolean(false)
    private val cancelCapture = AtomicBoolean(false)
    private val outputPauses = TnWakeOutputPauseLeases()
    private lateinit var backend: TnVoiceBackendClient
    private lateinit var prefs: android.content.SharedPreferences
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var message = "Đang khởi động nghe “TN ơi”…"
    @Volatile private var listeningForCommand = false
    @Volatile private var readFrames = 0L
    @Volatile private var ready = false
    @Volatile private var loginOwner: Long? = null
    @Volatile private var run: TnWakeRunCoordinator.Run? = null
    private val runs = TnWakeRunCoordinator()

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        backend = TnVoiceBackendClient(this)
        prefs = getSharedPreferences("tn-session-v1", MODE_PRIVATE)
        instance = this
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { disableRuntime(this); return START_NOT_STICKY }
        val requestedOwner = intent?.getLongExtra(EXTRA_OWNER, Long.MIN_VALUE)
            ?.takeUnless { it == Long.MIN_VALUE }
        val session = TnSessionStore.get(this)
        if (requestedOwner == null || !session.ownsActions(requestedOwner) ||
            !consented(this) || !microphoneAllowed(this)) {
            // A queued start from an older account must be a no-op. Calling
            // fail() here would clear shared runtime consent belonging to the
            // current account, even though this intent was rejected.
            if (run == null) {
                active.set(false)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        val request = runs.request(requestedOwner)
        if (request.alreadyActive) return START_NOT_STICKY
        if (request.run == null) {
            active.set(false)
            captureThread?.interrupt()
            return START_NOT_STICKY
        }
        startRun(request.run)
        return START_NOT_STICKY // Never open a microphone after boot or silently restart from the background.
    }

    private fun startRun(next: TnWakeRunCoordinator.Run) {
        run = next
        loginOwner = next.owner
        try {
            val notification = notification(message)
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification)
        } catch (_: RuntimeException) {
            fail("Android chưa cho phép nghe nền. Hãy mở TN Assistant và bật lại.", next)
            return
        }
        active.set(true)
        captureThread = Thread({ listen(next) }, "tn-wake-listener").also { it.start() }
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

    private fun listen(run: TnWakeRunCoordinator.Run) {
        var lease: TnRecordingSessionGate.Capture? = null
        var engine: NativeAudio? = null
        val window = WakeCommandWindow()
        try {
            verifyEntitlement()
            if (!enabled(run)) return
            val captureDeadline = SystemClock.elapsedRealtime() + 10000
            while (enabled(run) && lease == null) {
                if (SystemClock.elapsedRealtime() >= captureDeadline) throw IOException("Micro đang được dùng. Hãy dừng ghi âm hoặc phát giọng nói rồi bật lại “TN ơi”.")
                lease = TnRecordingSessionGate.process.begin()
                if (lease == null) Thread.sleep(50)
            }
            val owned = lease ?: return
            engine = NativeAudio(this, prefs.getInt("command_end_silence_ms", 1000))
            engine.init()
            if (!enabled(run)) return
            var lastVerification = SystemClock.elapsedRealtime()
            var paused = false
            val samples = ShortArray(512)
            update("Đang chờ “TN ơi”", false)
            while (enabled(run) && owned.isActive) {
                if (!microphoneAllowed(this)) throw IOException("Quyền micro đã bị thu hồi. “TN ơi” đã dừng.")
                if (SystemClock.elapsedRealtime() - lastVerification >= 300000) {
                    closeRecorder()
                    verifyEntitlement()
                    lastVerification = SystemClock.elapsedRealtime()
                }
                if (outputPauses.activeCount > 0) {
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
                if (!enabled(run)) break
                // Admission and every consumer of the returned block are one
                // pause boundary. A pause requested during this section is
                // queued until it ends, rather than racing a snapshot check.
                val admitted = outputPauses.withProcessing {
                    if (count < 0) throw IOException("Không đọc được micro. Hãy đóng ứng dụng đang chiếm micro rồi bật lại.")
                    if (count == 0) { Thread.sleep(10); return@withProcessing }
                    readFrames++
                    if (!ready) {
                        // Recorder readiness is runtime state, not the user's
                        // acknowledged account setting. MainActivity owns the latter;
                        // writing it here would let an old service start race a newer
                        // Tắt operation.
                        ready = true
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
                        if (enabled(run)) update(failure ?: if (continueConversation) "Bạn có thể nói tiếp…" else "Đang chờ “TN ơi”", false)
                    }
                }
                if (!admitted) {
                    closeRecorder()
                    if (!paused) { window.reset(); engine.reset(); paused = true }
                    continue
                }
            }
        } catch (_: InterruptedException) {
            // Explicit stop owns cancellation, not a retry or a new microphone session.
        } catch (error: Throwable) {
            if (active.get() && runs.isCurrent(run)) {
                fail(if (error is UnsatisfiedLinkError) "Thiết bị chưa nạp được mô hình giọng nói TN." else error.message?.take(220) ?: "Không khởi động được micro TN.", run)
            }
        } finally {
            ready = false
            closeRecorder()
            runCatching { engine?.close() }
            window.reset()
            lease?.close()
            active.set(false)
            val replacement = runs.complete(run)
            if (replacement != null && consented(this) && microphoneAllowed(this) &&
                TnSessionStore.get(this).ownsActions(replacement.owner)) {
                main.post { if (instance === this) startRun(replacement) }
            } else {
                main.post { if (instance === this) stopSelf() }
            }
        }
    }

    private fun openRecorder(): AudioRecord {
        val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (size <= 0) throw IOException("Micro không hỗ trợ PCM 16 kHz")
        val currentRun = run ?: throw InterruptedException("Recording run is no longer active")
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
            }, begin = { it.startRecording() }, discard = { runCatching { it.stop() }; it.release() }, active = { enabled(currentRun) },
        )
    }
    private fun closeRecorder() {
        val held = recorder ?: return
        recorder = null
        runCatching { held.stop() }; runCatching { held.release() }
    }

    private fun process(utterance: WakeCommandWindow.Utterance): Boolean {
        val currentRun = run ?: return false
        if (!canProcessTurn(currentRun)) return false
        val transcript = backend.pcm(utterance.pcm).optString("text").trim()
        if (!canProcessTurn(currentRun)) return false
        val text = if (utterance.requiresWakePrefix) WakePhrase.commandAfterConfirmedWake(transcript) ?: return false else transcript
        if (text.isBlank()) return true // A wake alone waits for the next spoken command.
        val uiToken = TnWakeUi.currentToken()
        main.post {
            if (canProcessTurn(currentRun)) {
                TnWakeUi.transcript(text, currentRun.owner, uiToken)
            }
        }
        val device = prefs.getString("device_id", null) ?: throw IOException("Thiếu định danh thiết bị TN")
        val owner = loginOwner ?: return false
        val payload = vn.tn.assistant.api.TnAssistantConversation.shared.request(text,
            UUID.randomUUID().toString(), device, owner, { enabled(currentRun) })
        if (text.contains("thời tiết", true) || text.contains("nhiệt độ", true)) {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val location = try { manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time <= 600000 }.maxByOrNull { it.time } } catch (_: SecurityException) { null }
            if (location == null) throw IOException("Mở TN để cấp quyền vị trí trước khi hỏi thời tiết.")
            payload.put("lat", location.latitude).put("lon", location.longitude)
        }
        if (!canProcessTurn(currentRun)) return false
        val response = backend.json("/v1/assistant/text", payload)
        if (!canProcessTurn(currentRun)) return false
        if (response.optBoolean("replayed")) return true
        val turnActive = { canProcessTurn(currentRun) && !cancelCapture.get() && !forcedCapture.get() }
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
            externalMedia = { type, payload, details ->
                vn.tn.assistant.actions.TnExternalMediaApp.execute(this, type, payload, details) { launch(it, turnActive) }
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
            val uiToken = TnWakeUi.currentToken()
            main.post {
                if (enabled(currentRun) &&
                    !TnWakeUi.confirm(currentRun.owner, uiToken)
                ) {
                    update("Mở TN Assistant để xem và xác nhận thao tác.", false, currentRun)
                }
            }
            return false
        }
        return !summary.cancelled
    }

    private fun launch(intent: Intent, turnActive: () -> Boolean): Boolean {
        val finished = CountDownLatch(1)
        val launched = AtomicBoolean(false)
        val expired = AtomicBoolean(false)
        val owner = loginOwner
        val uiToken = TnWakeUi.currentToken()
        main.post {
            try {
                if (!expired.get() && turnActive() && owner != null) {
                    if (TnWakeUi.launch(intent, owner, uiToken)) launched.set(true)
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

    private fun enabled(run: TnWakeRunCoordinator.Run) =
        active.get() && runs.isCurrent(run) && consented(this) &&
            !Thread.currentThread().isInterrupted && loginOwner == run.owner &&
            TnSessionStore.get(this).ownsActions(run.owner)

    private fun canProcessTurn(run: TnWakeRunCoordinator.Run) =
        TnWakeTurnPolicy.canProcess(enabled(run), microphoneAllowed(this))

    private fun update(text: String, listening: Boolean, currentRun: TnWakeRunCoordinator.Run? = run) {
        message = text
        listeningForCommand = listening
        val owner = currentRun?.owner
        val uiToken = TnWakeUi.currentToken()
        main.post {
            if (instance !== this || (currentRun != null && !runs.isCurrent(currentRun))) return@post
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification(text))
            TnWakeUi.state(text, listening, owner, uiToken)
        }
    }

    private fun fail(text: String, failedRun: TnWakeRunCoordinator.Run? = run) {
        if (failedRun != null && !runs.isCurrent(failedRun)) return
        ready = false
        control(this).edit().putBoolean(KEY_CONSENT, false).putString(KEY_ERROR, text).apply()
        update(text, false, failedRun)
        active.set(false)
        runs.invalidate()
        main.post { if (instance === this) stopSelf() }
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
        runs.invalidate()
        backend.cancel()
        captureThread?.interrupt()
        // The worker owns release; stop only unblocks its read.
        runCatching { recorder?.stop() }
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        TnWakeUi.state(
            control(this).getString(KEY_ERROR, null) ?: "Đã tắt nghe “TN ơi”.",
            false,
            loginOwner,
            TnWakeUi.currentToken(),
        )
        super.onDestroy()
    }

    companion object {
        private const val KEY_CONSENT = "microphone_consent"
        private const val KEY_ERROR = "last_error"
        private const val ACTION_STOP = "vn.tn.assistant.STOP_WAKE_LISTENING"
        private const val EXTRA_OWNER = "vn.tn.assistant.extra.WAKE_OWNER"
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
        fun enableFromVisibleActivity(context: Context, owner: Long? = null): Boolean {
            if (!microphoneAllowed(context)) return false
            val generation = owner ?: TnSessionStore.get(context).actionGeneration()
            if (!TnSessionStore.get(context).ownsActions(generation)) return false
            control(context).edit().putBoolean(KEY_CONSENT, true).remove(KEY_ERROR).apply()
            return try {
                val intent = Intent(context, TnWakeService::class.java).putExtra(EXTRA_OWNER, generation)
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: RuntimeException) {
                control(context).edit().putBoolean(KEY_CONSENT, false)
                    .putString(KEY_ERROR, "Mở TN Assistant để bật nghe nền.").apply()
                false
            }
        }

        /** Stops only the runtime listener; the acknowledged account switch is unchanged. */
        fun disableRuntime(context: Context) {
            control(context).edit().putBoolean(KEY_CONSENT, false).remove(KEY_ERROR).apply()
            context.stopService(Intent(context, TnWakeService::class.java))
        }

        /** Compatibility name for runtime callers; account settings are changed by MainActivity. */
        fun disable(context: Context) = disableRuntime(context)

        fun microphoneRevoked(context: Context) {
            disableRuntime(context)
            val message = "Quyền micro đã bị thu hồi. Hãy cấp lại quyền rồi bật “TN ơi”."
            control(context).edit().putString(KEY_ERROR, message).apply()
            val owner = TnSessionStore.get(context).actionGeneration()
            TnWakeUi.state(message, false, owner, TnWakeUi.currentToken())
        }
        fun captureNow(): Boolean = instance?.let {
            if (!it.active.get()) false else {
                if (it.listeningForCommand) it.cancelCapture.set(true) else it.forcedCapture.set(true)
                true
            }
        } ?: false
        fun pauseForOutput(): AutoCloseable = instance?.outputPauses?.acquire() ?: AutoCloseable { }
    }
}
