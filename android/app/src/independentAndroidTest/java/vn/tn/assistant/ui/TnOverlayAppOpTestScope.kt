package vn.tn.assistant.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Temporarily controls the overlay AppOp on the isolated AVD and restores its prior mode. */
internal class TnOverlayAppOpTestScope(context: Context, mode: String) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val packageName = context.packageName
    private val originalMode = readMode()

    init {
        setMode(mode)
    }

    override fun close() {
        setMode(originalMode)
    }

    private fun readMode(): String {
        val output = runShell("appops get $packageName SYSTEM_ALERT_WINDOW")
        return Regex("SYSTEM_ALERT_WINDOW:\\s*([a-z_]+)")
            .find(output)?.groupValues?.get(1) ?: "default"
    }

    private fun setMode(mode: String) {
        require(mode in setOf("allow", "ignore", "deny", "default", "foreground"))
        runShell("appops set $packageName SYSTEM_ALERT_WINDOW $mode")
    }

    private fun runShell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
