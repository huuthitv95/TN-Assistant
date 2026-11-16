package vn.tn.assistant.audio.tn

/** A wake turn may process captured speech only while both session consent and mic access remain valid. */
internal object TnWakeTurnPolicy {
    fun canProcess(sessionActive: Boolean, microphonePermissionGranted: Boolean): Boolean =
        sessionActive && microphonePermissionGranted
}
