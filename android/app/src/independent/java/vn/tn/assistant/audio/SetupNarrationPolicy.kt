package vn.tn.assistant.audio

/** One spoken cue per foreground page visit, even when a page is redrawn. */
class SetupNarrationPolicy {
    private var visit: String? = null
    var muted = false
        private set
    fun enter(cue: String, foreground: Boolean): Boolean {
        if (!foreground || muted || cue == visit) return false
        visit = cue
        return true
    }
    fun leave() { visit = null }
    fun setMuted(value: Boolean) { muted = value }
    fun replay() { visit = null }
}
