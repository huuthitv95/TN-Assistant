package vn.tn.assistant.ui

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.boot.TnBootWelcomeReceiver

/** Exercises the real Settings radio control and Android 13+ runtime permission dialog. */
class TnBootWelcomePermissionRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun reminderOptInStaysOffWhenAndroidPermissionIsDenied() {
        assumeTrue("Run notification permission acceptance on Preview API 33+", context.packageName == "vn.tn.assistant.preview" && Build.VERSION.SDK_INT >= 33)
        val originalPermission = permissionState()
        assumeTrue("Start with Android notification permission denied",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        try {
            withBootSettings { scenario, surface, prefs ->
                scenario.onActivity {
                    findAll(surface).first { it.contentDescription == "Nhóm Tiện ích" }.performClick()
                    bootReminderToggle(surface).performClick()
                    assertFalse("Opt-in is not persisted while the permission request is pending",
                        prefs.getBoolean(TnBootWelcomeReceiver.KEY_ENABLED, false))
                }

                assertTrue("Android permission dialog must allow an explicit denial", clickPermissionDialog(allow = false))
                instrumentation.waitForIdleSync()
                assertEquals(PackageManager.PERMISSION_DENIED,
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
                assertFalse("Denying notifications must leave boot greeting opt-in off",
                    prefs.getBoolean(TnBootWelcomeReceiver.KEY_ENABLED, false))
                scenario.onActivity {
                    surface.refresh()
                    assertFalse("The Settings radio button reflects the denied permission",
                        bootReminderToggle(surface).isChecked)
                }
            }
        } finally {
            restorePermission(originalPermission)
        }
    }

    @Test fun grantedNotificationPermissionAllowsTheReminderSettingToPersist() {
        assumeTrue("Run the grant branch after the host grants notification permission",
            context.packageName == "vn.tn.assistant.preview" && Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        withBootSettings { scenario, surface, prefs ->
            scenario.onActivity {
                findAll(surface).first { it.contentDescription == "Nhóm Tiện ích" }.performClick()
                bootReminderToggle(surface).performClick()
                assertTrue("Granted notification permission allows the reminder setting to persist",
                    prefs.getBoolean(TnBootWelcomeReceiver.KEY_ENABLED, false))
            }
        }
    }

    private fun withBootSettings(block: (ActivityScenario<MainActivity>, TnUserSettingsSurface, SharedPreferences) -> Unit) {
        val prefs = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val isolatedPreferences = TnIsolatedActivityPreferences(context)
        var dialog: Dialog? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    lateinit var surface: TnUserSettingsSurface
                    scenario.onActivity { activity ->
                        surface = TnUserSettingsSurface(activity, prefs, "boot-permission-test", {}, {}, { key, value ->
                            MainActivity::class.java.getDeclaredMethod("saveUserSetting", String::class.java, Any::class.java)
                                .apply { isAccessible = true }.invoke(activity, key, value)
                        })
                        MainActivity::class.java.getDeclaredField("userSettings").apply {
                            isAccessible = true
                            set(activity, surface)
                        }
                        dialog = Dialog(activity).apply {
                            setContentView(surface)
                            show()
                            window!!.setLayout(-1, -1)
                        }
                    }
                    block(scenario, surface, prefs)
                } finally {
                    instrumentation.runOnMainSync { dialog?.dismiss() }
                }
            }
        } finally {
            isolatedPreferences.close()
        }
    }

    private fun bootReminderToggle(surface: TnUserSettingsSurface): CompoundButton =
        findAll(surface).filterIsInstance<CompoundButton>().first {
            it.contentDescription == "Nhắc lời chào sau khi khởi động thiết bị: Bật"
        }

    private fun findAll(root: android.view.View): List<android.view.View> = buildList {
        add(root)
        if (root is android.view.ViewGroup) for (index in 0 until root.childCount) addAll(findAll(root.getChildAt(index)))
    }

    private fun clickPermissionDialog(allow: Boolean): Boolean {
        val automation = instrumentation.uiAutomation
        val deadline = android.os.SystemClock.elapsedRealtime() + 8000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                val labels = if (allow) listOf("Allow", "Cho phép") else
                    listOf("Don’t allow", "Don't allow", "Không cho phép", "Deny")
                val target = labels.firstNotNullOfOrNull { label ->
                    root.findAccessibilityNodeInfosByText(label).firstOrNull { it.isClickable && it.isEnabled }
                }
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            android.os.SystemClock.sleep(50)
        }
        return false
    }

    private data class PermissionState(val granted: Boolean, val mutableFlags: Set<String>)

    private fun permissionState(): PermissionState {
        val output = shell("dumpsys package ${context.packageName}")
        val entry = Regex("android\\.permission\\.POST_NOTIFICATIONS:\\s*granted=(true|false),\\s*flags=\\[([^]]*)]")
            .find(output)?.groupValues ?: error("Cannot snapshot Android notification permission state")
        val mutable = setOf("user-set", "user-fixed")
        val flags = entry[2].split('|').map { it.trim().lowercase().replace('_', '-') }.filter { it in mutable }.toSet()
        return PermissionState(entry[1] == "true", flags)
    }

    private fun restorePermission(original: PermissionState) {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val current = permissionState()
        if (current.granted != original.granted) {
            shell("pm ${if (original.granted) "grant" else "revoke"} ${context.packageName} $permission")
        }
        val mutable = listOf("user-set", "user-fixed")
        shell("pm clear-permission-flags ${context.packageName} $permission ${mutable.joinToString(" ")}")
        if (original.mutableFlags.isNotEmpty()) {
            shell("pm set-permission-flags ${context.packageName} $permission ${original.mutableFlags.joinToString(" ")}")
        }
        assertEquals("Android permission state must be restored after the interaction", original, permissionState())
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
