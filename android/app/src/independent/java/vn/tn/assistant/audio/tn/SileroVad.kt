package vn.tn.assistant.audio.tn

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/** MIT-licensed Silero VAD v5.1.2, kept in per-recording recurrent state. */
internal class SileroVad(context: Context, endSilenceMillis: Int = 600) : AutoCloseable {
    private var endSilenceSamples = endSilenceMillis.coerceIn(500, 3000) * 16
    var isSpeech = false
        private set
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        environment.createSession(context.assets.open("models/silero_vad.onnx").use { it.readBytes() }, options)
    }
    private var state = Array(2) { Array(1) { FloatArray(128) } }
    private val context = FloatArray(64)
    private val frame = FloatArray(512)
    private var filled = 0
    private var quietSamples = 0
    private var speechSeen = false

    fun accept(samples: ShortArray, count: Int): Boolean {
        require(count in 0..samples.size)
        var endpoint = false
        for (index in 0 until count) {
            frame[filled++] = samples[index] / 32768f
            if (filled != frame.size) continue
            val input = Array(1) { FloatArray(576) }
            context.copyInto(input[0])
            frame.copyInto(input[0], 64)
            OnnxTensor.createTensor(environment, input).use { audio ->
                OnnxTensor.createTensor(environment, state).use { recurrent ->
                    OnnxTensor.createTensor(environment, longArrayOf(16000)).use { rate ->
                        session.run(mapOf("input" to audio, "state" to recurrent, "sr" to rate)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val probability = (result[0].value as Array<FloatArray>)[0][0]
                            isSpeech = probability >= .5f
                            @Suppress("UNCHECKED_CAST")
                            state = result[1].value as Array<Array<FloatArray>>
                            if (probability >= .5f) {
                                speechSeen = true
                                quietSamples = 0
                            } else if (speechSeen) {
                                quietSamples += frame.size
                            }
                            if (speechSeen && quietSamples >= endSilenceSamples) {
                                endpoint = true
                                speechSeen = false
                                quietSamples = 0
                            }
                        }
                    }
                }
            }
            frame.copyInto(context, 0, frame.size - context.size)
            filled = 0
        }
        return endpoint
    }

    fun reset() {
        state = Array(2) { Array(1) { FloatArray(128) } }
        context.fill(0f)
        frame.fill(0f)
        filled = 0
        quietSamples = 0
        speechSeen = false
        isSpeech = false
    }

    fun setEndSilenceMillis(value: Int) { endSilenceSamples = value.coerceIn(500, 3000) * 16 }

    override fun close() = session.close()
}
