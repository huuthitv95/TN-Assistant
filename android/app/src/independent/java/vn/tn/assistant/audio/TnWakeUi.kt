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
    @Volatile private var listener = WeakReference<Listener>(null)
    fun attach(value: Listener) { listener = WeakReference(value) }
    fun detach(value: Listener) { if (listener.get() === value) listener.clear() }
    fun state(message: String, listening: Boolean) = listener.get()?.state(message, listening)
    fun transcript(text: String) = listener.get()?.transcript(text)
    fun launch(intent: Intent): Boolean = listener.get()?.launch(intent) == true
    fun confirm(): Boolean = listener.get()?.confirm() == true
}
