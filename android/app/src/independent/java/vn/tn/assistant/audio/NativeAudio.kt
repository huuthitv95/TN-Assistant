package vn.tn.assistant.audio

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import vn.tn.assistant.audio.tn.SileroVad
import vn.tn.assistant.audio.tn.WakePhrase

/** Independent Vosk and Silero models; no vendor JNI or original-app runtime. */
class NativeAudio(private val context: Context, private val endSilenceMillis: Int = 600) : AutoCloseable {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var vad: SileroVad? = null
    private var lastWake = -1200L

    fun init() {
        check(recognizer == null) { "Audio engine already initialized" }
        val target = File(context.noBackupFilesDir, "tn-models/vosk-small-vn-0.4-v1")
        synchronized(modelLock) {
            val marker = File(target, ".complete")
            if (!marker.isFile) {
                target.deleteRecursively()
                copyAssets("models/vosk-model-small-vn-0.4", target)
                marker.writeText("efe5c8494212110471a79befc48c79da679e5b1fc52a4ffb500222ff86d622e5")
            }
        }
        try {
            val loaded = Model(target.absolutePath)
            model = loaded
            recognizer = Recognizer(loaded, 16000f, JSONArray(WakePhrase.recognitionGrammar).toString())
            vad = SileroVad(context, endSilenceMillis)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun copyAssets(path: String, destination: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
            return
        }
        destination.mkdirs()
        for (child in children) copyAssets("$path/$child", File(destination, child))
    }

    fun wake(samples: ShortArray, count: Int): Boolean {
        require(count in 0..samples.size)
        val engine = recognizer ?: return false
        val complete = engine.acceptWaveForm(samples, count)
        val result = JSONObject(if (complete) engine.result else engine.partialResult)
        val text = result.optString(if (complete) "text" else "partial")
        val now = android.os.SystemClock.elapsedRealtime()
        val detected = WakePhrase.matches(text) && now - lastWake >= 1200
        if (detected) {
            lastWake = now
            engine.reset()
        }
        return detected
    }

    fun endpoint(samples: ShortArray, count: Int): Boolean = vad?.accept(samples, count) ?: false
    fun resetVad() = vad?.reset()
    fun setEndSilenceMillis(value: Int) = vad?.setEndSilenceMillis(value)
    val isSpeech get() = vad?.isSpeech == true
    fun reset() { recognizer?.reset(); vad?.reset(); lastWake = -1200L }

    override fun close() {
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        vad?.close()
        vad = null
    }

    companion object { private val modelLock = Any() }
}
