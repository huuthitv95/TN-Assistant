package vn.tn.assistant.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TnExternalMediaAppTest {
    @Test fun directiveTypesMapOnlyToTheirOwnSettingsKeys() {
        assertEquals("music_package", TnExternalMediaApp.preferenceKey("PlayMusicApp"))
        assertEquals("radio_package", TnExternalMediaApp.preferenceKey("PlayRadioApp"))
        assertEquals("tv_package", TnExternalMediaApp.preferenceKey("PlayTVApp"))
        assertNull(TnExternalMediaApp.preferenceKey("PlayVideoApp"))
        assertNull(TnExternalMediaApp.preferenceKey("PlayerMP3"))
    }

    @Test fun packagePreferenceMustBeARealPackageAndCannotReopenSourceOrSelf() {
        assertEquals("com.spotify.music", TnExternalMediaApp.normalizePackage("  com.spotify.music "))
        assertNull(TnExternalMediaApp.normalizePackage("https://example.com"))
        assertNull(TnExternalMediaApp.normalizePackage("ai.zalo.kiki.car"))
        assertNull(TnExternalMediaApp.normalizePackage("spotify"))
        assertNull(TnExternalMediaApp.normalizePackage("com..example"))
    }
}
