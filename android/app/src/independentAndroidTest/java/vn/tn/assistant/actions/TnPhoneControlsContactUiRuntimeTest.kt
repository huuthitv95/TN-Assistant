package vn.tn.assistant.actions

import android.Manifest
import android.app.AlertDialog
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Intent
import android.provider.ContactsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.ui.TnIsolatedActivityPreferences
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs contact resolution through the real picker while capturing, never dispatching, ACTION_CALL. */
class TnPhoneControlsContactUiRuntimeTest {
    @Test fun ambiguousContactLookupUsesMaskedForegroundPickerAndCancelDoesNotCall() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val contactName = "TN QA Contact ${UUID.randomUUID()}"
        val numberA = "5550000101"
        val numberB = "5550000102"
        val rawContactIds = mutableListOf<Long>()
        var shellIdentity = false

        TnIsolatedActivityPreferences(context).use {
            try {
                automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
                automation.grantRuntimePermission(context.packageName, Manifest.permission.CALL_PHONE)
                automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
                shellIdentity = true
                rawContactIds += insertSyntheticContact(context, contactName, numberA)
                rawContactIds += insertSyntheticContact(context, contactName, numberB)
                automation.dropShellPermissionIdentity()
                shellIdentity = false

                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val activity = AtomicReference<MainActivity>()
                    scenario.onActivity { activity.set(it) }

                    val selected = startCall(context, activity, contactName)
                    val dialog = awaitDialog(scenario)
                    scenario.onActivity {
                        val labels = (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() }
                        assertEquals(2, labels.size)
                        assertTrue(labels[0].contains(contactName) && labels[0].contains("••0101"))
                        assertTrue(labels[1].contains(contactName) && labels[1].contains("••0102"))
                        assertFalse(labels.any { it.contains(numberA) || it.contains(numberB) })
                        assertTrue(dialog.isShowing)
                        dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, dialog.listView.adapter.getItemId(1))
                    }
                    assertTrue("Contact action resumes after a foreground choice", selected.finished.await(3, TimeUnit.SECONDS))
                    assertNull(selected.failure.get())
                    assertEquals("success", selected.result.get())
                    assertEquals(Intent.ACTION_CALL, selected.intents.single().action)
                    assertEquals("tel:$numberB", selected.intents.single().data.toString())
                    assertTrue(selected.details.optBoolean("contact_selected"))
                    assertFalse(selected.details.toString().contains(contactName))
                    assertFalse(selected.details.toString().contains(numberB))

                    val cancelled = startCall(context, activity, contactName)
                    val cancelDialog = awaitDialog(scenario)
                    scenario.onActivity { cancelDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
                    assertTrue("Cancel resumes the contact action", cancelled.finished.await(3, TimeUnit.SECONDS))
                    assertNull(cancelled.failure.get())
                    assertEquals("cancelled", cancelled.result.get())
                    assertTrue("Cancel never creates a call intent", cancelled.intents.isEmpty())
                    assertEquals("contact_selection_cancelled", cancelled.details.optString("reason"))
                    assertFalse(cancelled.details.toString().contains(contactName))
                    assertFalse(cancelled.details.toString().contains(numberA))
                }
            } finally {
                if (!shellIdentity) {
                    automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
                    shellIdentity = true
                }
                rawContactIds.forEach { id ->
                    context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI,
                        "${ContactsContract.RawContacts._ID}=?", arrayOf(id.toString()))
                }
                if (shellIdentity) automation.dropShellPermissionIdentity()
            }
        }
    }

    private fun startCall(context: android.content.Context, activity: AtomicReference<MainActivity>, name: String): CallRun {
        val result = AtomicReference<String?>()
        val failure = AtomicReference<Throwable?>()
        val details = JSONObject()
        val intents = CopyOnWriteArrayList<Intent>()
        val finished = CountDownLatch(1)
        val choose = MainActivity::class.java.getDeclaredMethod(
            "chooseContactNumber",
            List::class.java,
            kotlin.jvm.functions.Function0::class.java,
        ).apply { isAccessible = true }
        Thread({
            try {
                result.set(TnPhoneControls(
                    context = context,
                    active = { true },
                    launch = { intent -> intents += intent; true },
                    authorize = { true },
                    authorizeContacts = { true },
                    chooseContact = { candidates -> choose.invoke(activity.get(), candidates, { true }) as? String },
                ).execute(JSONObject().put("contact_name", name), details))
            } catch (error: InvocationTargetException) {
                failure.set(error.targetException)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }, "tn-contact-action-test").apply { isDaemon = true; start() }
        return CallRun(result, failure, details, intents, finished)
    }

    private fun awaitDialog(scenario: ActivityScenario<MainActivity>): AlertDialog {
        val field = MainActivity::class.java.getDeclaredField("contactChoiceDialog").apply { isAccessible = true }
        val deadline = android.os.SystemClock.elapsedRealtime() + 7000L
        var dialog: AlertDialog? = null
        while (dialog == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { dialog = field.get(it) as? AlertDialog }
            if (dialog == null) Thread.sleep(25)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(dialog) { "The foreground contact-choice dialog was not shown" }
    }

    private fun insertSyntheticContact(context: android.content.Context, name: String, number: String): Long {
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, name)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build(),
        )
        val inserted = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        return ContentUris.parseId(requireNotNull(inserted.first().uri))
    }

    private data class CallRun(
        val result: AtomicReference<String?>,
        val failure: AtomicReference<Throwable?>,
        val details: JSONObject,
        val intents: CopyOnWriteArrayList<Intent>,
        val finished: CountDownLatch,
    )
}
