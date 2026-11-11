package vn.tn.assistant.audio

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.api.TnVoiceBackendClient
import vn.tn.assistant.audio.tn.WakePhrase
import java.io.File

/** Live ASR and command API with an explicitly synthetic permitted sample, no device action execution. */
class TnWakeVpsTurnRuntimeTest {
    @Test fun activatedAudioUsesRealVpsAsrAndCommandRouting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("true", InstrumentationRegistry.getArguments().getString("tnLiveWake"))
        val client = TnVoiceBackendClient(context)
        try {
            val audio = File(context.filesDir, "tn-wake-test/00-04.pcm").readBytes()
            val transcript = client.pcm(audio).optString("text").trim()
            val result = JSONObject().put("synthetic_input", true).put("real_vps_asr", true).put("transcript", transcript)
            File(context.cacheDir, "tn167-wake-vps-turn.json").writeText(result.toString(2))
            assertTrue("Live ASR must return recognized words", transcript.isNotBlank())
            val command = WakePhrase.commandAfterConfirmedWake(transcript)
            assertNotNull("A locally confirmed wake must tolerate the real ASR spelling of the app's name", command)
            assertTrue(requireNotNull(command).isNotBlank())
            val device = context.getSharedPreferences("tn-session-v1", 0).getString("device_id", null)
            val response = client.json("/v1/assistant/text", JSONObject().put("text", command).put("mode", "commands")
                .put("device_id", device).put("request_id", java.util.UUID.randomUUID().toString()))
            val directives = response.optJSONArray("directives") ?: JSONArray()
            val types = JSONArray()
            for (index in 0 until directives.length()) types.put(directives.getJSONObject(index).optString("type"))
            result.put("command_api_success", true).put("directive_types", types).put("device_actions_executed", false)
            File(context.cacheDir, "tn167-wake-vps-turn.json").writeText(result.toString(2))
            assertTrue("VPS must return a real command response", directives.length() > 0)
        } finally { client.cancel() }
    }
}
