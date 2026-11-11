package vn.tn.assistant.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Actual overlay windows and MotionEvents; the original b2.o uses distance to its entire body rectangle. */
class TnFloatingDragMagnetRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun bodyEdgeEntersAndHoldsTheSourceMagnetThenCancelPersistsOutside() = withOverlay(false) { f ->
        f.startDrag()
        f.moveBesideTarget(.65f)
        f.assertCaptured()
        f.moveBesideTarget(1.10f)
        f.assertCaptured()
        f.moveBesideTarget(1.30f)
        onMain {
            assertFalse("Leaving the source 125% radius releases the magnet", f.isMagnetized())
            assertEquals(0, f.hides)
            assertTrue(f.release(cancelled = true))
        }
        await("A completed outside drag persists only after springs settle") { f.saves == 1 }
        onMain { assertEquals(0, f.hides); assertEquals(1f, f.view.scaleX, .01f) }
    }

    @Test fun firstUseHintCannotResizeTheSourceBodyOrShiftItsSnapCenter() = withOverlay(true) { f ->
        f.startDrag()
        f.moveBesideTarget(.65f)
        f.assertCaptured()
        onMain {
            assertEquals("Close scale uses the source 152px body, excluding the passive hint",
                minOf(f.close.width / 152.0, 1.0), f.spring("scale").target, .00001)
            assertEquals("Scaling pivots around the body, not around the combined hint window",
                f.bodyTop + 79f, f.view.pivotY, .001f)
        }
    }

    @Test fun releasingInsideTheMagnetHidesOnceAndDisposalRemovesCloseWindows() = withOverlay(false) { f ->
        f.startDrag()
        f.moveBesideTarget(0f)
        f.assertCaptured()
        onMain {
            assertEquals(0, f.hides)
            assertTrue(f.release(cancelled = false))
            assertEquals("Dropping in the close target dispatches one user-hide", 1, f.hides)
            assertEquals("A hide does not save a new outside position", 0, f.saves)
            f.controller.dispose()
        }
        // WindowManager.removeView queues detachment on the main looper.
        // Observe its actual completion instead of requiring a synchronous API.
        await("Dispose removes the actual close window") { !f.close.isAttachedToWindow }
    }

    private fun withOverlay(hint: Boolean, block: (Fixture) -> Unit) {
        check(Build.VERSION.SDK_INT >= 29)
        val avd = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
            .executeShellCommand("getprop ro.kernel.qemu.avd_name")).bufferedReader().use { it.readText().trim() }
        check(avd == "TNNative166") { "Drag tests are restricted to the isolated test AVD" }
        val scope = TnOverlayAppOpTestScope(instrumentation.targetContext, "allow")
        var fixture: Fixture? = null
        try {
            onMain { fixture = Fixture(hint) }
            block(requireNotNull(fixture))
        } finally {
            try { onMain { fixture?.close() } } finally { scope.close() }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5000
        do {
            var done = false
            onMain { done = condition() }
            if (done) return
            Thread.sleep(30)
        } while (SystemClock.uptimeMillis() < end)
        fail(message)
    }

    private inner class Fixture(hint: Boolean) : AutoCloseable {
        private val context = instrumentation.targetContext
        private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = TnFloatingClusterView(context).apply { unit = 135; suggestionVisible = hint }
        private val params: WindowManager.LayoutParams
        val controller: TnFloatingDragController
        lateinit var close: View
        var saves = 0
        var hides = 0
        val bodyLeft: Int get() = (view.width - 152) / 2
        val bodyTop: Int get() = view.height - 158
        private var downAt = 0L
        private var pointerOffsetX = 0f
        private var pointerOffsetY = 0f
        private var rawX = 0f
        private var rawY = 0f
        private var lastCenterX = 0
        private var lastCenterY = 0

        init {
            val size = TnFloatingGeometry.dimensions(135, hint, context.resources.displayMetrics.density)
            view.measure(exact(size.first), exact(size.second)); view.layout(0, 0, size.first, size.second)
            params = WindowManager.LayoutParams(size.first, size.second,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = 100; y = 200 }
            windows.addView(view, params)
            controller = TnFloatingDragController(context, windows, view, params,
                moved = { x, y -> params.x = x; params.y = y; windows.updateViewLayout(view, params) },
                savePosition = { saves++ }, hide = { hides++ })
        }

        fun startDrag() {
            onMain {
                pointerOffsetX = view.anchorCenterX.toFloat(); pointerOffsetY = view.anchorCenterY.toFloat()
                downAt = SystemClock.uptimeMillis()
                rawX = params.x + pointerOffsetX; rawY = params.y + pointerOffsetY
                event(MotionEvent.ACTION_DOWN) { controller.down(it) }
            }
            Thread.sleep(230)
            onMain { moveTo(160f, 500f) }
            await("The real close window must be laid out after the source 200ms drag delay") {
                val candidate = WindowInspector.getGlobalWindowViews().firstOrNull {
                    it.contentDescription == "Xóa tiện ích nổi" && it.isAttachedToWindow && it.isLaidOut && it.visibility == View.VISIBLE }
                if (candidate != null) close = candidate
                candidate != null
            }
        }

        fun moveBesideTarget(gap: Float) = onMain {
            val xy = IntArray(2); close.getLocationOnScreen(xy)
            lastCenterX = xy[0] + close.width / 2; lastCenterY = xy[1] + close.height / 2
            // Source b2.o / ChatHeadService uses a 152x158 body rectangle, not
            // center-to-center distance and not the extra passive hint window.
            moveTo(lastCenterX - gap * close.width - 152 - bodyLeft,
                lastCenterY - 79f - bodyTop)
        }

        fun assertCaptured() = onMain {
            assertTrue("The source rectangle edge is inside the close radius even when its center is outside", isMagnetized())
            assertEquals("Snap x follows the source body-window center", (lastCenterX - bodyLeft - 76).toDouble(), spring("x").target, .001)
            assertEquals("Snap y follows the source body-window center", (lastCenterY - bodyTop - 79).toDouble(), spring("y").target, .001)
        }

        fun isMagnetized() = TnFloatingDragController::class.java.getDeclaredField("magnet")
            .apply { isAccessible = true }.getBoolean(controller)
        fun spring(name: String) = TnFloatingDragController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller) as TnFloatingSpring
        fun release(cancelled: Boolean): Boolean {
            var consumed = false
            event(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP) { consumed = controller.release(it, cancelled) }
            return consumed
        }
        private fun moveTo(left: Float, top: Float) {
            rawX = left + pointerOffsetX; rawY = top + pointerOffsetY
            event(MotionEvent.ACTION_MOVE) { controller.move(it) }
        }
        private fun event(action: Int, consume: (MotionEvent) -> Unit) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, rawX, rawY, 0)
            try { consume(event) } finally { event.recycle() }
        }
        override fun close() {
            controller.dispose()
            if (view.isAttachedToWindow) windows.removeViewImmediate(view)
        }
        private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    }
}
