package vn.tn.assistant.ui

import android.os.SystemClock
import android.view.Choreographer

/** Owns frame callbacks only while a spring is moving. */
internal class TnFloatingSpringSystem(private val onIdle: () -> Unit = {}) {
    private val updates = linkedMapOf<TnFloatingSpring, (Double) -> Unit>()
    private val active = linkedSetOf<TnFloatingSpring>()
    private var clock = 0L
    private var running = false
    private var disposed = false
    private val choreographer = Choreographer.getInstance()
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if (!running || disposed) return
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - clock) / 1000.0
            for (spring in active.toList()) {
                if (!spring.needsStep) active.remove(spring)
                else { spring.advance(elapsed); updates.getValue(spring)(spring.value) }
            }
            clock = now
            if (active.isEmpty()) { running = false; onIdle() }
            else if (!disposed) choreographer.postFrameCallback(this)
        }
    }
    fun register(spring: TnFloatingSpring, update: (Double) -> Unit) { check(!disposed && !updates.containsKey(spring)); updates[spring] = update }
    private fun activate(spring: TnFloatingSpring) {
        if (disposed) return
        check(updates.containsKey(spring)); active.add(spring)
        if (!running) { running = true; clock = SystemClock.uptimeMillis(); choreographer.postFrameCallback(frame) }
    }
    fun snap(spring: TnFloatingSpring, value: Double) { if (disposed) return; spring.snap(value); activate(spring); updates.getValue(spring)(spring.value) }
    fun target(spring: TnFloatingSpring, value: Double) { if (disposed || spring.target == value && spring.isAtRest) return; spring.target = value; activate(spring) }
    fun stopAtCurrent(spring: TnFloatingSpring) { spring.snap(spring.value) }
    fun dispose() { disposed = true; running = false; active.clear(); choreographer.removeFrameCallback(frame) }
}
