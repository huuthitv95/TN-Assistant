package vn.tn.assistant.audio

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Confirms every pre-auth welcome/login cue decodes and starts from the packaged APK assets. */
class TnOnboardingNarratorRuntimeTest {
    @Test fun allWelcomeAndLoginCuesStartAndCanBeInterrupted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val narrator = TnOnboardingNarrator(instrumentation.targetContext)
        val cues = listOf("start", "permission", "notification", "overlay", "account", "finish",
            "choice", "login", "register", "zalo", "zalo_web")
        try {
            for (cue in cues) {
                val playing = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    narrator.onState = { state -> if (state == "playing") playing.countDown() }
                    narrator.show(cue, visible = true)
                }
                assertTrue("Packaged narration cue '$cue' must decode and begin playback",
                    playing.await(8, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { narrator.interrupt() }
            }
        } finally {
            instrumentation.runOnMainSync { narrator.close() }
        }
    }
}
