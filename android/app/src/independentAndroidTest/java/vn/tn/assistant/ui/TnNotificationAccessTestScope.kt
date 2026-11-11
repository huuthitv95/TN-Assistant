package vn.tn.assistant.ui

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import vn.tn.assistant.TnMediaAccessService

/** Only the isolated SDK AVD: restore the exact app listener grant after each scenario. */
internal class TnNotificationAccessTestScope(private val context: Context, granted: Boolean) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val component = ComponentName(context, TnMediaAccessService::class.java).flattenToString()
    private val originallyGranted = TnMediaAccessService.hasAccess(context)

    init {
        val avd = runShell("getprop ro.kernel.qemu.avd_name").trim()
        assumeTrue("Permission mutation is confined to the isolated SDK emulator", Build.FINGERPRINT.contains("generic") && avd == "TNNative166")
        set(granted)
    }

    fun set(granted: Boolean) {
        runShell("cmd notification ${if (granted) "allow_listener" else "disallow_listener"} $component")
        val deadline = SystemClock.uptimeMillis() + 5000
        while (TnMediaAccessService.hasAccess(context) != granted && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        check(TnMediaAccessService.hasAccess(context) == granted) { "Android did not apply the scoped notification-listener grant" }
    }

    override fun close() { set(originallyGranted) }

    private fun runShell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
