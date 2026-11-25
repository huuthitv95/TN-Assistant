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
import vn.tn.assistant.api.serverTimeMillis
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
    @Volatile private var recorderOwner: TnWakeRunCoordinator.Run? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var message = "Đang khởi động nghe “TN ơi”…"
    @Volatile private var listeningForCommand = false
    @Volatile private var readFrames = 0L
    @Volatile private var ready = false
    @Volatile private var loginOwner: Long? = null
    @Volatile private var verifyByElapsed = 0L
    @Volatile private var run: TnWakeRunCoordinator.Run? = null
    private val runs get() = processRuns
    private val serviceId = nextServiceId()
    private val recorderLock = Any()

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
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        val request = runs.request(requestedOwner, service = serviceId)
        if (request.alreadyActive) return START_NOT_STICKY
        if (request.run == null) {
            captureThread?.interrupt()
            return START_NOT_STICKY
        }
        startRun(request.run)
        return START_NOT_STICKY // Never open a microphone after boot or silently restart from the background.
    }

    private fun startRun(next: TnWakeRunCoordinator.Run) {
        if (!runs.beginStart(next, serviceId)) return
        // Initial reservations are not tracked replacements; beginStart also
        // atomically claims a replacement and rejects duplicate/stale callbacks.
        run = next
        loginOwner = next.owner
        verifyByElapsed = 0L
        try {
            val notification = notification(message)
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification)
        } catch (_: RuntimeException) {
            // A delayed failure from an old callback must not resolve or stop a
            // newer reservation that has since replaced this run.
            if (!runs.isCurrent(next)) return
            // No listener thread exists to perform the normal terminal cleanup.
            ready = false
            active.set(false)
            val promoted = runs.rejectReplacement(next)
            control(this).edit()
                .apply {
                    if (promoted == null) putBoolean(KEY_CONSENT, false)
                    putString(KEY_ERROR, "Android chưa cho phép nghe nền. Hãy mở TN Assistant và bật lại.")
                }
                .apply()
            update("Android chưa cho phép nghe nền. Hãy mở lại TN Assistant để bật nghe.", false, next)
            main.post {
                if (promoted != null) dispatchReplacement(promoted)
                else if (runs.noWorkerRemains() && instance === this) stopSelf()
            }
            return
        }
        active.set(true)
        val worker = Thread({
            if (!runs.registerWorker(next)) {
                if (runs.isCurrent(next)) active.set(false)
                return@Thread
            }
            listen(next)
        }, "tn-wake-listener")
        try {
            captureThread = worker
            worker.start()
        } catch (_: RuntimeException) {
            runs.unregisterWorker(next)
            if (!runs.isCurrent(next)) return
            ready = false
            active.set(false)
            val promoted = runs.rejectReplacement(next)
            val errorMessage = "Không khởi động được micro TN. Hãy mở lại TN Assistant để bật nghe."
            control(this).edit().putString(KEY_ERROR, errorMessage).apply()
            if (promoted == null) control(this).edit().putBoolean(KEY_CONSENT, false).apply()
            update(errorMessage, false, next)
            main.post {
                if (promoted != null) dispatchReplacement(promoted)
                else if (runs.noWorkerRemains() && instance === this) stopSelf()
            }
        }
    }

    private fun verifyEntitlement(run: TnWakeRunCoordinator.Run) {
        if (!enabled(run)) return
        val verificationStarted = SystemClock.elapsedRealtime()
        val me = backend.json("/v1/me", entitlementRequest = true)
        fun decisionFor(response: JSONObject, verificationElapsedMillis: Long): TnWakeEntitlementPolicy.Decision {
            val entitlementValue = response.opt("entitlements")
            val grants = when {
                !response.has("entitlements") -> TnWakeEntitlementPolicy.Entitlements.absent()
                entitlementValue is org.json.JSONArray -> {
                    val parsed = mutableListOf<TnWakeEntitlementPolicy.Grant>().apply {
                        for (i in 0 until entitlementValue.length()) {
                            val end = entitlementValue.optJSONObject(i)?.opt("expires_at")
                            add(TnWakeEntitlementPolicy.Grant((end as? String)?.let(::serverTimeMillis)))
                        }
                    }
                    TnWakeEntitlementPolicy.Entitlements.present(parsed.toList())
                }
                else -> TnWakeEntitlementPolicy.Entitlements.malformed()
            }
            return TnWakeEntitlementPolicy.evaluate(TnWakeEntitlementPolicy.Request(
                wakeFeature = response.optJSONObject("premium_features")?.opt("wake_word") == true,
                role = response.optJSONObject("user")?.opt("role") as? String,
                entitlements = grants,
                serverTimeMillis = response.optJSONObject("account_plan")?.opt("server_time")
                    .let { (it as? String)?.let(::serverTimeMillis) },
                verificationElapsedMillis = verificationElapsedMillis,
            ))
        }

        val decision = decisionFor(me, 0L)
        if (!decision.authorized) throw IOException(wakeEntitlementMessage(decision.reason))

        if (!enabled(run)) return
        val values = backend.json("/v1/settings", entitlementRequest = true)
        val freshMe = backend.json("/v1/me", entitlementRequest = true)
        val verificationCompletedAt = SystemClock.elapsedRealtime()
        val verificationElapsed = (verificationCompletedAt - verificationStarted).coerceAtLeast(0L)
        val finalDecision = decisionFor(freshMe, verificationElapsed)
        if (!finalDecision.authorized) throw IOException(wakeEntitlementMessage(finalDecision.reason))
        if (values.opt("wake_word_enabled") != true) {
            throw IOException("“TN ơi” đang tắt trong cài đặt tài khoản.")
        }
        if (!enabled(run)) return
        for (key in listOf("conversation_idle_timeout_ms", "command_end_silence_ms")) {
            if (values.has(key)) prefs.edit().putInt(key, values.optInt(key)).apply()
        }
        verifyByElapsed = TnWakeEntitlementPolicy.monotonicDeadline(verificationCompletedAt, finalDecision.recheckAfterMillis)
    }

    private fun wakeEntitlementMessage(reason: TnWakeEntitlementPolicy.Decision.Reason): String = when (reason) {
        TnWakeEntitlementPolicy.Decision.Reason.FEATURE_DENIED -> "Tài khoản chưa có quyền dùng “TN ơi”."
        TnWakeEntitlementPolicy.Decision.Reason.ROLE_MISSING,
        TnWakeEntitlementPolicy.Decision.Reason.MALFORMED_ENTITLEMENTS,
        TnWakeEntitlementPolicy.Decision.Reason.SERVER_TIME_MISSING,
        TnWakeEntitlementPolicy.Decision.Reason.EXPIRY_MISSING -> "Chưa xác minh được thời hạn dùng “TN ơi”."
        TnWakeEntitlementPolicy.Decision.Reason.GRANT_EXPIRED -> "Quyền dùng “TN ơi” đã hết hạn. Hãy kiểm tra lại tài khoản."
        TnWakeEntitlementPolicy.Decision.Reason.AUTHORIZED -> ""
    }

    private fun listen(run: TnWakeRunCoordinator.Run) {
        var lease: TnRecordingSessionGate.Capture? = null
        var engine: NativeAudio? = null
        val window = WakeCommandWindow()
        try {
            verifyEntitlement(run)
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
            var paused = false
            val samples = ShortArray(512)
            update("Đang chờ “TN ơi”", false)
            while (enabled(run) && owned.isActive) {
                if (!microphoneAllowed(this)) throw IOException("Quyền micro đã bị thu hồi. “TN ơi” đã dừng.")
                if (outputPauses.activeCount > 0) {
                    closeRecorder(run)
                    if (!paused) { window.reset(); engine.reset(); paused = true }
                    Thread.sleep(40)
                    continue
                }
                if (verificationDue()) {
                    closeRecorder(run)
                    ready = false
                    window.reset()
                    engine.reset()
                    forcedCapture.set(false)
                    update("Đang kiểm tra quyền dùng “TN ơi”…", false)
                    verifyEntitlement(run)
                    continue
                }
                if (paused) { engine.reset(); window.reset(); paused = false }
                if (recorderOwner !== run && recorder != null) break
                if (recorder == null) {
                    try {
                        val opened = openRecorder()
                        synchronized(recorderLock) {
                            if (runs.isCurrent(run)) {
                                recorder = opened
                                recorderOwner = run
                            } else {
                                runCatching { opened.stop() }; opened.release()
                            }
                        }
                    } catch (error: InterruptedException) {
                        if (enabled(run) && verificationDue()) continue
                        throw error
                    }
                }
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
                if (verificationDue()) continue
                // Admission and every consumer of the returned block are one
                // pause boundary. A pause requested during this section is
                // queued until it ends, rather than racing a snapshot check.
                val admitted = outputPauses.withProcessing {
                    if (verificationDue()) return@withProcessing
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
                        closeRecorder(run) // No microphone frames or TTS echo enter another turn during network/playback.
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
                    closeRecorder(run)
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
            // Release every resource before removing this worker from the process
            // ledger. This makes the synchronized idle predicate a real teardown
            // boundary, including for workers invalidated by a newer service.
            if (runs.isCurrent(run)) {
                ready = false
                active.set(false)
            }
            closeRecorder(run)
            runCatching { engine?.close() }
            window.reset()
            lease?.close()
            val completion = runs.finish(run)
            completion?.replacement?.let { replacement ->
                main.post { dispatchReplacement(replacement) }
            }
        }
    }

    /**
     * Routes an exact handoff only to the currently live service with the
     * candidate's service identity. Missing targets are resolved iteratively;
     * the origin is never allowed to start a target-owned candidate.
     */
    private fun dispatchReplacement(initial: TnWakeRunCoordinator.Run) {
        var candidate = initial
        while (runs.isCurrent(candidate)) {
            val target = instance?.takeIf { it.serviceId == candidate.service }
            if (target != null) {
                target.main.post {
                    // Recheck at execution time: a newer request may have
                    // superseded this callback while it was queued.
                    if (runs.isCurrent(candidate)) target.startReplacement(candidate)
                }
                return
            }
            val outcome = runs.abandonReplacement(candidate, candidate.service)
            if (!outcome.abandoned) return
            candidate = outcome.promoted ?: return
        }
    }

    private fun startReplacement(candidate: TnWakeRunCoordinator.Run) {
        // This method is entered only by dispatchReplacement on the exact live
        // target, but retains both identity checks for delayed callbacks.
        if (instance !== this || candidate.service != serviceId || !runs.isCurrent(candidate)) return
        if (consented(this) && microphoneAllowed(this) &&
            TnSessionStore.get(this).ownsActions(candidate.owner)) {
            startRun(candidate)
            return
        }
        // A newer request may have arrived while this reservation waited for
        // the main thread. Promote it and route it by exact service identity.
        runs.rejectReplacement(candidate)?.let(::dispatchReplacement) ?: run {
            if (instance === this && runs.noWorkerRemains()) stopSelf()
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
            }, begin = { it.startRecording() }, discard = { runCatching { it.stop() }; it.release() }, active = { enabled(currentRun) && !verificationDue() },
        )
    }
    private fun closeRecorder(run: TnWakeRunCoordinator.Run) {
        val held = synchronized(recorderLock) {
            if (recorderOwner !== run) return
            val value = recorder
            recorder = null
            recorderOwner = null
            value
        } ?: return
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
        val owner = currentRun.owner
		if (!canProcessTurn(currentRun)) return false
        val payload = vn.tn.assistant.api.TnAssistantConversation.shared.request(text,
            UUID.randomUUID().toString(), device, owner, { canProcessTurn(currentRun) })
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
                if (canProcessTurn(currentRun)) {
				backend.json("/v1/device/actions/results", JSONObject().put("request_id", response.optString("request_id"))
                    .put("action_id", id).put("result", result).put("details", details))
                }
            }).execute(executable)
        if (turnActive() && summary.unsupported > 0) throw IOException("Một số thao tác chưa được TN hỗ trợ trên thiết bị.")
        if (turnActive() && summary.failed > 0) throw IOException("Có thao tác chưa thực hiện được. Hãy kiểm tra quyền và ứng dụng.")
        if (turnActive() && summary.interactionRequired > 0) throw IOException("Yêu cầu đã chuyển tới ứng dụng trên thiết bị. Vui lòng kiểm tra kết quả tại đó.")
        if (needsConfirmation) {
            val uiToken = TnWakeUi.currentToken()
            main.post {
                if (canProcessTurn(currentRun) &&
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

    private fun verificationDue() = SystemClock.elapsedRealtime() >= verifyByElapsed

    private fun canProcessTurn(run: TnWakeRunCoordinator.Run) =
        !verificationDue() && TnWakeTurnPolicy.canProcess(enabled(run), microphoneAllowed(this))

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
        runs.invalidate(failedRun)
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
        // Make this instance unavailable before resolving any handoff. This
        // prevents a final callback from posting work back to the instance
        // whose callbacks are about to be removed.
        if (instance === this) instance = null
        // Inspect reservations after draining this handler. A worker finishing
        // later can still post its handoff to the live target.
        main.removeCallbacksAndMessages(null)
        val localRun = run
        // A service keeps its historical run reference after that worker has
        // handed off to a replacement. Never invalidate the coordinator's
        // current run merely because this instance is being destroyed. A live
        // worker must finish its resource cleanup and perform the handoff.
        if (localRun != null && runs.isCurrent(localRun) && !runs.hasWorker(localRun)) {
            runs.rejectReplacement(localRun)?.let { dispatchReplacement(it) }
        }
        // Target teardown can remove the queued start callback before it runs.
        // Abandon that exact reservation now, promoting any newer request so
        // neither the current state nor a pending handoff is stranded.
        runs.currentReplacementFor(serviceId)?.let { candidate ->
            runs.abandonReplacement(candidate, serviceId).promoted?.let(::dispatchReplacement)
        }
        // Origin teardown may happen after finish() has reserved an unclaimed
        // replacement. Resolve it by exact target identity; a missing target
        // is abandoned or promoted by dispatchReplacement, never started here.
        runs.currentReplacementFromOrigin(serviceId)?.let { dispatchReplacement(it) }
        val listener = captureThread
        backend.cancel()
        listener?.interrupt()
        // The worker owns release; stop only unblocks its read.
        run?.let { currentRun ->
            synchronized(recorderLock) {
                if (recorderOwner === currentRun) runCatching { recorder?.stop() }
            }
        }
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
        private val processRuns = TnWakeRunCoordinator()
        private val nextService = java.util.concurrent.atomic.AtomicLong()
        private fun nextServiceId() = nextService.incrementAndGet()
        private fun control(context: Context) = context.getSharedPreferences("tn-wake-control-v1", Context.MODE_PRIVATE)
        fun consented(context: Context) = control(context).getBoolean(KEY_CONSENT, false)
        fun isRunning() = instance?.active?.get() == true
        fun isListenerThreadAlive() = instance?.captureThread?.isAlive == true
        /** True only when the coordinator has no reservation or in-flight worker. */
        fun isWorkerComplete() = processRuns.noWorkerRemains()
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
