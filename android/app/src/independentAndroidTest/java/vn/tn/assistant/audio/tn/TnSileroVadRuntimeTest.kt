package vn.tn.assistant.audio.tn

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test

class TnSileroVadRuntimeTest {
    @Test fun opensSileroModelAndRunsOnnxInference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SileroVad(context).use { vad ->
            assertFalse("A silent frame must not end a speech utterance", vad.accept(ShortArray(512), 512))
        }
    }
}
