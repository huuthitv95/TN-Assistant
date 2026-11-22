package vn.tn.assistant.audio

import android.content.Intent
import java.lang.ref.WeakReference

/** UI attachment has no ownership of the service, microphone or account credential. */
internal object TnWakeUi {
    interface Listener {
        fun state(message: String, listening: Boolean)
        fun transcript(text: String)
        fun launch(intent: Intent): Boolean
        fun confirm(): Boolean = false
    }

    private data class Attachment(
        val listener: WeakReference<Listener>,
        val owner: Long?,
        val token: Long,
    )

    @Volatile private var nextToken = 0L
    @Volatile private var attachment = Attachment(WeakReference<Listener>(null), null, 0L)

    @Synchronized
    fun attach(value: Listener, owner: Long? = null) {
        attachment = Attachment(WeakReference(value), owner, ++nextToken)
    }

    @Synchronized
    fun detach(value: Listener) {
        if (attachment.listener.get() === value) {
            // Invalidate callbacks queued before detach. A future attachment
            // must receive a fresh token even when it belongs to the same
            // session generation.
            attachment = Attachment(
                WeakReference<Listener>(null),
                attachment.owner,
                ++nextToken,
            )
        }
    }

    /** Snapshot used by a service before posting a callback to the main thread. */
    fun currentToken(): Long = attachment.token

    private fun current(owner: Long?, token: Long?): Listener? {
        val value = attachment
        if (owner != null && value.owner != owner) return null
        if (token != null && value.token != token) return null
        return value.listener.get()
    }

    fun state(
        message: String,
        listening: Boolean,
        owner: Long? = null,
        token: Long? = null,
    ) {
        current(owner, token)?.state(message, listening)
    }

    fun transcript(text: String, owner: Long? = null, token: Long? = null) {
        current(owner, token)?.transcript(text)
    }

    fun launch(intent: Intent, owner: Long? = null, token: Long? = null): Boolean =
        current(owner, token)?.launch(intent) == true

    fun confirm(owner: Long? = null, token: Long? = null): Boolean =
        current(owner, token)?.confirm() == true
}
