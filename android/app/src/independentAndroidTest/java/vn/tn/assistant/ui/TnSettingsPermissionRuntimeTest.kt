package vn.tn.assistant.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.R
import vn.tn.assistant.TnMediaAccessService

/** Original r2.k and b3.a1: notification access precedes overlay access. */
class TnSettingsPermissionRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notificationWarning = "Tính năng cần cấp quyền truy cập thông báo để sử dụng"
    private val overlayWarning = "Tính năng cần có quyền hiển thị để sử dụng"

    @Test fun osPermissionChangesAdvanceTheSourceWarningAndNeverEnableTheFeatureImplicitly() {
        TnNotificationAccessTestScope(context, false).use { notification ->
            TnOverlayAppOpTestScope(context, "ignore").use {
                val prefs = context.getSharedPreferences("tn-permission-priority-test-only", Context.MODE_PRIVATE)
                prefs.edit().clear().commit()
                val actions = mutableListOf<String>()
                val saves = mutableListOf<Pair<String, Any>>()
                lateinit var surface: TnUserSettingsSurface
                onMain {
                    val themed = ContextThemeWrapper(context, R.style.TnAssistantOverlayTheme)
                    surface = TnUserSettingsSurface(themed, prefs, "fixture-device", {}, { actions += it }, { key, value -> saves += key to value })
                    measure(surface)
                    assertTrue(TnMediaAccessService.canRequestAccess(context))
                    assertEquals(1, texts(surface).count { it == notificationWarning })
                    assertFalse(texts(surface).contains(overlayWarning))
                    val account = surface.findViewWithTag<View>("tnSettingsAccountCard")
                    assertFalse("The warning belongs to Utilities, not the account card", texts(account).contains(notificationWarning))
                    surface.floating(); measure(surface)
                    descendants(surface).filterIsInstance<TextView>().single { it.text.toString() == "Cấp quyền" }.performClick()
                    assertEquals(listOf("notification"), actions)
                }
                notification.set(true)
                onMain {
                    surface.refresh(); measure(surface)
                    assertFalse(texts(surface).contains(notificationWarning))
                    assertTrue(texts(surface).contains(overlayWarning))
                    assertEquals("Tiện ích nổi", surface.findViewWithTag<TextView>("tnSettingsHeading").text.toString())
                    descendants(surface).filterIsInstance<TextView>().single { it.text.toString() == "Cấp quyền" }.performClick()
                    assertEquals(listOf("notification", "overlay"), actions)
                }
                TnOverlayAppOpTestScope(context, "allow").use {
                    onMain {
                        surface.refresh(); measure(surface)
                        assertFalse(texts(surface).contains(notificationWarning) || texts(surface).contains(overlayWarning))
                        assertTrue("Granting permissions does not consent to enabling floating UI", saves.isEmpty())
                        assertFalse(prefs.getBoolean("floating_enabled", false))
                    }
                }
                notification.set(false)
                onMain {
                    surface.refresh(); measure(surface)
                    assertTrue("Revoking access restores the first warning", texts(surface).contains(notificationWarning))
                    assertFalse(texts(surface).contains(overlayWarning))
                    assertTrue(saves.isEmpty())
                }
                prefs.edit().clear().commit()
            }
        }
    }

    @Test fun permissionCtaReturnsToTheActualRootOrDetailAndRefreshesAndroidState() {
        TnNotificationAccessTestScope(context, false).use { notification ->
            TnOverlayAppOpTestScope(context, "ignore").use {
                TnIsolatedActivityPreferences(context).use {
                    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                    try {
                        lateinit var surface: TnUserSettingsSurface
                        val invokeAction = MainActivity::class.java.getDeclaredMethod("userSettingsAction", String::class.java)
                            .apply { isAccessible = true }
                        onMain {
                            val prefs = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
                            surface = TnUserSettingsSurface(activity, prefs, "fixture-device", {},
                                { key -> invokeAction.invoke(activity, key) }, { _, _ -> })
                            MainActivity::class.java.getDeclaredField("userSettings").apply { isAccessible = true }.set(activity, surface)
                            activity.setContentView(surface)
                        }
                        instrumentation.waitForIdleSync()
                        openGrant(surface)
                        returnFromAndroidSettings()
                        onMain {
                            assertEquals("Root stays root after returning without granting permission", "Cài đặt",
                                surface.findViewWithTag<TextView>("tnSettingsHeading").text.toString())
                            assertTrue(texts(surface).contains(notificationWarning))
                            surface.floating()
                        }
                        openGrant(surface)
                        notification.set(true)
                        returnFromAndroidSettings()
                        onMain {
                            assertEquals("The detail page stays open after granting notification access", "Tiện ích nổi",
                                surface.findViewWithTag<TextView>("tnSettingsHeading").text.toString())
                            assertTrue(texts(surface).contains(overlayWarning))
                            assertFalse(texts(surface).contains(notificationWarning))
                            val state = surface.saveUiState()
                            assertEquals(setOf("selected_group", "detail", "scroll_y"), state.keySet())
                            assertEquals("Tiện ích nổi", state.getString("detail"))
                        }
                        openGrant(surface)
                        returnFromAndroidSettings()
                        onMain {
                            assertEquals("Tiện ích nổi", surface.findViewWithTag<TextView>("tnSettingsHeading").text.toString())
                            assertTrue("Returning without an overlay grant leaves the warning", texts(surface).contains(overlayWarning))
                        }
                    } finally {
                        onMain { activity.finish() }
                        instrumentation.waitForIdleSync()
                    }
                }
            }
        }
    }

    @Test fun savedNavigationRestoresTheSameDetailWithoutStoringPermissionOrAccountData() {
        onMain {
            val prefs = context.getSharedPreferences("tn-settings-state-test-only", Context.MODE_PRIVATE)
            val themed = ContextThemeWrapper(context, R.style.TnAssistantOverlayTheme)
            val initial = TnUserSettingsSurface(themed, prefs, "fixture-device", {}, {}, { _, _ -> })
            initial.floating(); measure(initial)
            val parcel = android.os.Parcel.obtain()
            val saved: Bundle
            try {
                initial.saveUiState().writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                saved = Bundle.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
            val restored = TnUserSettingsSurface(themed, prefs, "fixture-device", {}, {}, { _, _ -> })
            restored.restoreUiState(saved); measure(restored)
            assertEquals("Tiện ích nổi", restored.findViewWithTag<TextView>("tnSettingsHeading").text.toString())
            assertEquals(setOf("selected_group", "detail", "scroll_y"), saved.keySet())
            assertTrue(restored.back())
            assertEquals("Cài đặt", restored.findViewWithTag<TextView>("tnSettingsHeading").text.toString())
        }
    }

    private fun openGrant(surface: TnUserSettingsSurface) {
        onMain { descendants(surface).filterIsInstance<TextView>().single { it.text.toString() == "Cấp quyền" }.performClick() }
        await("The CTA opens Android's real permission Settings", 8000) { rootPackage() == "com.android.settings" }
    }
    private fun returnFromAndroidSettings() {
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        await("TN resumes after closing Android Settings", 8000) { rootPackage() == context.packageName }
        instrumentation.waitForIdleSync()
    }
    private fun await(message: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
    private fun rootPackage() = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun measure(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1731, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 1731)
    }
}
