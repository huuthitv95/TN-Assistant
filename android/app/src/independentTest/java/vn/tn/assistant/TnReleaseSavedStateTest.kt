package vn.tn.assistant

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TnReleaseSavedStateTest {
    private fun release(versionCode: Long = 26110992, minSdk: Int = 21) = TnAppRelease(
        applicationId = "vn.tn.assistant",
        versionCode = versionCode,
        versionName = "26.11.09.192",
        minSdk = minSdk,
        url = "/downloads/tn-assistant-release192.apk",
        sha256 = "a".repeat(64),
        notes = "Updater process-death recovery.",
    )

    @Test
    fun savedStateRoundTripRevalidatesRelease() {
        val restored = TnAppRelease.restoreSavedState(
            release().savedState(),
            expectedApplicationId = "vn.tn.assistant",
            installedVersionCode = 26110991,
            deviceSdk = 30,
        )

        assertEquals(release(), restored)
    }

    @Test
    fun restoredStateMustStillTargetInstalledPackage() {
        val changed = JSONObject(release().savedState())
            .put("application_id", "vn.tn.assistant.preview")
            .toString()

        expectIOException {
            TnAppRelease.restoreSavedState(changed, "vn.tn.assistant", 26110991, 30)
        }
    }

    @Test
    fun restoredStateMustRemainNewerAndDeviceCompatible() {
        assertNull(TnAppRelease.restoreSavedState(release().savedState(), "vn.tn.assistant", 26110992, 30))
        expectIOException {
            TnAppRelease.restoreSavedState(release(minSdk = 31).savedState(), "vn.tn.assistant", 26110991, 30)
        }
    }

    @Test
    fun malformedOrMissingStateCannotResumeInstall() {
        assertNull(TnAppRelease.restoreSavedState(null, "vn.tn.assistant", 26110991, 30))
        expectIOException {
            val changed = JSONObject(release().savedState()).put("sha256", "bad").toString()
            TnAppRelease.restoreSavedState(changed, "vn.tn.assistant", 26110991, 30)
        }
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Expected.
        }
    }
}
