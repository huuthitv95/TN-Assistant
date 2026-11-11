package vn.tn.assistant.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity

/** Verifies the permission CTA opens Android Settings and returns to the same TN detail page. */
class TnOverlaySettingsReturnRuntimeTest {
    @Test fun overlayPermissionActionReturnsToFloatingSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notificationAccess = TnNotificationAccessTestScope(context, true)
        val prefs = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        val overlayAppOp = TnOverlayAppOpTestScope(context, "ignore")
        var activity: MainActivity? = null
        try {
            assertTrue(prefs.edit().clear().commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val target = requireNotNull(activity)
            val invokeAction = MainActivity::class.java.getDeclaredMethod("userSettingsAction", String::class.java)
                .apply { isAccessible = true }
            var surface: TnUserSettingsSurface? = null
            instrumentation.runOnMainSync {
                surface = TnUserSettingsSurface(target, prefs, "fixture-device", {},
                    { key -> invokeAction.invoke(target, key) }, { _, _ -> })
                MainActivity::class.java.getDeclaredField("userSettings").apply { isAccessible = true }.set(target, surface)
                target.setContentView(surface)
                surface!!.floating()
            }

            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            fun rootPackage(): String? = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            instrumentation.waitForIdleSync()
            var grant: TextView? = null
            instrumentation.runOnMainSync {
                grant = descendants(requireNotNull(surface)).filterIsInstance<TextView>().firstOrNull { it.text.toString() == "Cấp quyền" }
            }
            assertNotNull("Missing overlay permission presents the original grant action", grant)
            instrumentation.runOnMainSync { grant!!.performClick() }

            val settingsDeadline = android.os.SystemClock.uptimeMillis() + 8_000
            while (android.os.SystemClock.uptimeMillis() < settingsDeadline && rootPackage() == context.packageName) Thread.sleep(100)
            assertNotEquals("The CTA must open Android's overlay-access settings", context.packageName, rootPackage())
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))

            val appDeadline = android.os.SystemClock.uptimeMillis() + 8_000
            while (android.os.SystemClock.uptimeMillis() < appDeadline && rootPackage() != context.packageName) Thread.sleep(100)
            assertEquals("Returning from Android Settings must resume TN", context.packageName, rootPackage())
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val texts = descendants(requireNotNull(surface)).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue("TN returns to the floating-control detail page", texts.any { it == "Tiện ích nổi" })
                assertTrue("The permission warning remains visible until access is granted",
                    texts.any { it == "Tính năng cần có quyền hiển thị để sử dụng" })
            }
        } finally {
            try {
                if (activity != null && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) {
                    instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
                instrumentation.runOnMainSync { activity?.finish() }
                val editor = prefs.edit().clear()
                saved.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> error("Unsupported TN session preference type: ${value?.javaClass?.name ?: "null"}")
                } }
                check(editor.commit())
            } finally {
                overlayAppOp.close()
                notificationAccess.close()
            }
        }
    }
}
