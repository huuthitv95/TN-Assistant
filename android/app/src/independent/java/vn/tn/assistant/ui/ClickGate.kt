package vn.tn.assistant.ui

/** One shared gate survives welcome view replacement, like the original persistent CTA. */
class ClickGate(private val thresholdMillis: Long = 500L) {
    private var lastAccepted: Long? = null

    fun accept(nowMillis: Long): Boolean {
        val previous = lastAccepted
        if (previous != null && nowMillis >= previous && nowMillis - previous <= thresholdMillis) return false
        lastAccepted = nowMillis
        return true
    }
}
