package vn.tn.assistant.audio.tn

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.audio.NativeAudio
import java.io.File

/** Opt-in PCM fixtures are provisioned privately; actual Vosk/Silero inference, not a fake detector. */
class TnWakeAcousticRuntimeTest {
    @Test fun actualPackagedModelsRecognizeWakeAndRejectTheNegativeCorpus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.filesDir, "tn-wake-test")
        assertTrue("Provision the private permitted PCM corpus before running this opt-in class", folder.isDirectory)
        val manifest = JSONArray(File(folder, "manifest.json").readText())
        val results = JSONArray()
        NativeAudio(context, 1000).use { engine ->
            engine.init()
            for (index in 0 until manifest.length()) {
                engine.reset()
                val item = manifest.getJSONObject(index)
                val bytes = File(folder, item.getString("file")).readBytes()
                assertTrue(bytes.isNotEmpty() && bytes.size % 2 == 0)
                var detected = false
                var endpoint = false
                var offset = 0
                while (offset < bytes.size + 32000) {
                    val count = minOf(512, (bytes.size + 32000 - offset) / 2)
                    val frame = ShortArray(count) { sample ->
                        val pos = offset + sample * 2
                        if (pos + 1 < bytes.size) ((bytes[pos].toInt() and 255) or (bytes[pos + 1].toInt() shl 8)).toShort() else 0
                    }
                    detected = engine.wake(frame, count) || detected
                    endpoint = engine.endpoint(frame, count) || endpoint
                    offset += count * 2
                }
                results.put(JSONObject().put("file", item.getString("file"))
                    .put("expected_wake", item.getBoolean("expected_wake")).put("detected", detected).put("vad_endpoint", endpoint))
            }
        }
        File(context.cacheDir, "tn167-wake-acoustic.json").writeText(results.toString(2))
        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            assertEquals("Real acoustic decision for ${item.getString("file")}", item.getBoolean("expected_wake"), item.getBoolean("detected"))
        }
    }

    @Test fun diagnosticGrammarProbeForThePermittedCorpus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val folder = File(context.filesDir, "tn-wake-test")
        val manifest = JSONArray(File(folder, "manifest.json").readText())
        val results = JSONArray()
        val modelPath = File(context.noBackupFilesDir, "tn-models/vosk-small-vn-0.4-v1")
        val grammar = arguments.getString("tnWakeDiagnosticGrammarBase64")
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.NO_WRAP), Charsets.UTF_8) }
            ?: JSONArray(WakePhrase.recognitionGrammar).toString()
        val leadingSilenceMillis = (arguments.getString("tnWakeLeadingSilenceMs") ?: "0").toInt()
        require(leadingSilenceMillis in 0..2000)
        val split = arguments.getString("tnWakeDiagnosticSplit")
        require(split == null || split in setOf("development", "holdout"))
        org.vosk.Model(modelPath.absolutePath).use { model ->
            for (index in 0 until manifest.length()) {
                val item = manifest.getJSONObject(index)
                if (split != null && item.optString("split") != split) continue
                val bytes = File(folder, item.getString("file")).readBytes()
                org.vosk.Recognizer(model, 16000f, grammar).use { decoder ->
                    decoder.setWords(true)
                    decoder.setPartialWords(true)
                    if (leadingSilenceMillis > 0) {
                        val silence = ByteArray(leadingSilenceMillis * 32)
                        decoder.acceptWaveForm(silence, silence.size)
                    }
                    val observations = JSONArray()
                    var previous = ""
                    for (offset in bytes.indices step 1024) {
                        val complete = decoder.acceptWaveForm(bytes.copyOfRange(offset, minOf(offset + 1024, bytes.size)), minOf(1024, bytes.size - offset))
                        val result = JSONObject(if (complete) decoder.result else decoder.partialResult)
                        val text = result.optString(if (complete) "text" else "partial")
                        if (text != previous) { observations.put(result); previous = text }
                    }
                    results.put(JSONObject().put("file", item.getString("file"))
                        .put("leading_silence_ms", leadingSilenceMillis)
                        .put("observations", observations).put("decoded", JSONObject(decoder.finalResult)))
                }
            }
        }
        File(context.cacheDir, "tn167-wake-diagnostic.json").writeText(results.toString(2))
        val selectedCount = (0 until manifest.length()).count { split == null || manifest.getJSONObject(it).optString("split") == split }
        assertEquals(selectedCount, results.length())
    }
}
