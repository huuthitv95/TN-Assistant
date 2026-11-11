package vn.tn.assistant.actions

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Uses only synthetic contacts; ACTION_CALL is captured and never dispatched. */
class TnPhoneControlsContactRuntimeTest {
    @Test fun fullNameLookupAndAmbiguousSelectionNeverGuessOrLeakContactData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.READ_CONTACTS))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CALL_PHONE))
        val label = "TN QA Contact ${UUID.randomUUID()}"
        val firstNumber = "5550000101"
        val secondNumber = "5550000102"
        val rawContactIds = mutableListOf<Long>()
        var shellIdentity = false
        try {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
            shellIdentity = true
            rawContactIds += insertSyntheticContact(context, label, firstNumber)

            val firstLaunch = arrayListOf<Intent>()
            val firstDetails = JSONObject()
            val firstResult = TnPhoneControls(context, { true }, { firstLaunch += it; true },
                authorize = { true }, authorizeContacts = { true })
                .execute(JSONObject().put("contact_name", label.lowercase()), firstDetails)
            assertEquals("success", firstResult)
            assertEquals(1, firstLaunch.size)
            assertEquals(Intent.ACTION_CALL, firstLaunch.single().action)
            assertEquals("tel:$firstNumber", firstLaunch.single().data.toString())
            assertFalse(firstDetails.toString().contains(label))
            assertFalse(firstDetails.toString().contains(firstNumber))

            rawContactIds += insertSyntheticContact(context, label, secondNumber)
            val ambiguousLaunch = arrayListOf<Intent>()
            val ambiguousDetails = JSONObject()
            val ambiguousResult = TnPhoneControls(context, { true }, { ambiguousLaunch += it; true },
                authorize = { true }, authorizeContacts = { true })
                .execute(JSONObject().put("contact_name", label), ambiguousDetails)
            assertEquals("unsupported", ambiguousResult)
            assertTrue(ambiguousLaunch.isEmpty())
            assertEquals("contact_selection_requires_foreground", ambiguousDetails.optString("reason"))

            val offered = arrayListOf<List<TnContactNumber>>()
            val selectedLaunch = arrayListOf<Intent>()
            val selectedDetails = JSONObject()
            val selectedResult = TnPhoneControls(context, { true }, { selectedLaunch += it; true },
                authorize = { true }, authorizeContacts = { true },
                chooseContact = { candidates ->
                    offered += candidates
                    candidates.firstOrNull { it.phoneNumber == secondNumber }?.phoneNumber
                })
                .execute(JSONObject().put("contact_name", label), selectedDetails)
            assertEquals("success", selectedResult)
            assertEquals(2, offered.single().size)
            assertEquals("tel:$secondNumber", selectedLaunch.single().data.toString())
            assertFalse(selectedDetails.toString().contains(label))
            assertFalse(selectedDetails.toString().contains(secondNumber))
        } finally {
            if (shellIdentity) {
                rawContactIds.forEach { id ->
                    context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI,
                        "${ContactsContract.RawContacts._ID}=?", arrayOf(id.toString()))
                }
                automation.dropShellPermissionIdentity()
            }
        }
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
}
