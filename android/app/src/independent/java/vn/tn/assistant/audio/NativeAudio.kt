package vn.tn.assistant.audio

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
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
        try {
            val loaded = loadModel()
            model = loaded
            recognizer = Recognizer(loaded, 16000f, JSONArray(WakePhrase.recognitionGrammar).toString())
            vad = SileroVad(context, endSilenceMillis)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun loadModel(): Model = synchronized(modelLock) {
        val target = File(context.noBackupFilesDir, "tn-models/vosk-small-vn-0.4-v1")
        val marker = File(target, ".complete")
        if (marker.isFile && marker.length() == MODEL_REVISION.length.toLong()) {
            try {
                if (marker.readText(Charsets.US_ASCII) == MODEL_REVISION) {
                    try {
                        return@synchronized Model(target.absolutePath)
                    } catch (_: Throwable) {
                        // The marker is not proof that every model file is intact. A
                        // process or emulator crash can leave a complete marker beside
                        // a truncated file, so fall through and rebuild the cache.
                    }
                }
            } catch (_: IOException) {
                // A damaged marker is handled by the same complete rebuild below.
            }
        }
        if (target.exists() && !target.deleteRecursively()) throw IOException("Không thể làm mới model giọng nói.")
        copyAssets("models/vosk-model-small-vn-0.4", target)
        val loaded = Model(target.absolutePath)
        try {
            writeCompletionMarker(marker)
        } catch (error: IOException) {
            loaded.close()
            throw error
        }
        loaded
    }

    private fun writeCompletionMarker(marker: File) {
        marker.outputStream().use { stream ->
            stream.write(MODEL_REVISION.toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
        }
    }

    private fun copyAssets(path: String, destination: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(path).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
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

    companion object {
        private val modelLock = Any()
        private const val MODEL_REVISION = "efe5c8494212110471a79befc48c79da679e5b1fc52a4ffb500222ff86d622e5"
    }
}
