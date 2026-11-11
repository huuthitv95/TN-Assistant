package vn.tn.assistant.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

internal data class TnContactNumber(val displayName: String, val phoneNumber: String, val typeLabel: String)

/** TN calls through the public Android contract after server-bound user confirmation. */
internal class TnPhoneControls(
    private val context: Context,
    private val active: () -> Boolean,
    private val launch: (Intent) -> Boolean,
    private val authorize: () -> Boolean = { hasAccess(context) },
    private val authorizeContacts: () -> Boolean = { hasContactsAccess(context) },
    private val lookupContacts: (String) -> List<TnContactNumber> = { findContacts(context, it) },
    private val chooseContact: ((List<TnContactNumber>) -> String?)? = null,
) {
    fun execute(payload: JSONObject, details: JSONObject): String {
        check(Looper.myLooper() != Looper.getMainLooper())
        details.put("intent_started", false).put("completion_verified", false)
        if (!running()) return "cancelled"
        val hasNumber = payload.has("phone_number")
        val hasContact = payload.has("contact_name")
        if (hasNumber == hasContact) {
            details.put("reason", "invalid_call_target"); return "failed"
        }
        val phone = if (hasNumber) {
            (payload.opt("phone_number") as? String)?.takeIf(number::matches)
                ?: run { details.put("reason", "invalid_phone_number"); return "failed" }
        } else resolveContact(payload.opt("contact_name"), details) ?: return contactFailure(details)
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", phone, null))
        if (intent.resolveActivity(context.packageManager) == null) {
            details.put("reason", "call_application_unavailable"); return "unsupported"
        }
        if (!hasAccess(context) && !authorize()) {
            details.put("reason", "call_permission_required"); return "failed"
        }
        if (!running()) return "cancelled"
        if (!hasAccess(context)) { details.put("reason", "call_permission_required"); return "failed" }
        // Never retry a call after an ambiguous dispatch, and never include its
        // phone number in telemetry. The OS, not this receipt, owns call state.
        val started = launch(intent)
        details.put("intent_started", started).put("interaction_required", started)
            .put("reason", if (started) "call_request_dispatched" else "call_request_not_started")
        return if (started) "success" else "failed"
    }

    private fun resolveContact(raw: Any?, details: JSONObject): String? {
        val requested = raw as? String ?: run { details.put("reason", "invalid_contact_name"); return null }
        val query = requested.trim()
        val normalized = normalizedName(query)
        if (query.length > 160 || normalized.length < 2) {
            details.put("reason", "invalid_contact_name"); return null
        }
        if (!hasContactsAccess(context) && !authorizeContacts()) {
            details.put("reason", "contacts_permission_required"); return null
        }
        if (!running()) { details.put("reason", "contact_lookup_cancelled"); return null }
        if (!hasContactsAccess(context)) {
            details.put("reason", "contacts_permission_required"); return null
        }
        val matches = try { lookupContacts(query).distinctBy { it.phoneNumber } }
        catch (_: SecurityException) { details.put("reason", "contacts_permission_required"); return null }
        catch (_: RuntimeException) { details.put("reason", "contact_lookup_failed"); return null }
        if (!running()) { details.put("reason", "contact_lookup_cancelled"); return null }
        if (matches.isEmpty()) { details.put("reason", "contact_not_found"); return null }
        if (matches.size > MAX_CONTACT_CHOICES) {
            details.put("reason", "contact_matches_too_many"); return null
        }
        val selected = when {
            matches.size == 1 -> matches.single().phoneNumber
            chooseContact == null -> {
                details.put("reason", "contact_selection_requires_foreground")
                    .put("interaction_required", true)
                return null
            }
            else -> chooseContact.invoke(matches)
        }
        if (selected == null) { details.put("reason", "contact_selection_cancelled"); return null }
        if (matches.none { it.phoneNumber == selected } || !number.matches(selected)) {
            details.put("reason", "invalid_contact_selection"); return null
        }
        details.put("contact_selected", true)
        return selected
    }

    private fun contactFailure(details: JSONObject): String = when (details.optString("reason")) {
        "contact_selection_requires_foreground", "contact_matches_too_many" -> "unsupported"
        "contact_selection_cancelled", "contact_lookup_cancelled" -> "cancelled"
        else -> "failed"
    }

    private fun running() = active() && !Thread.currentThread().isInterrupted

    companion object {
        private val number = Regex("\\+?[0-9]{3,15}")
        fun hasAccess(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        fun hasContactsAccess(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        private const val MAX_CONTACT_CHOICES = 20

        private fun findContacts(context: Context, query: String): List<TnContactNumber> {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
            val columns = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
            )
            val expected = normalizedName(query)
            val results = linkedMapOf<String, TnContactNumber>()
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                if (nameColumn < 0 || numberColumn < 0) return emptyList()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn)?.trim().orEmpty()
                    if (name.isBlank() || normalizedName(name) != expected) continue
                    val phone = normalizeStoredPhone(cursor.getString(numberColumn).orEmpty()) ?: continue
                    val type = if (typeColumn >= 0) cursor.getInt(typeColumn) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                    val custom = if (labelColumn >= 0) cursor.getString(labelColumn) else null
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, type, custom).toString()
                    if (!results.containsKey(phone)) results[phone] = TnContactNumber(name, phone, label)
                    if (results.size > MAX_CONTACT_CHOICES) break
                }
            }
            return results.values.toList()
        }

        private fun normalizeStoredPhone(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            var plus = false
            var seenDigit = false
            val digits = StringBuilder()
            for (character in value) {
                when {
                    character in '0'..'9' -> { digits.append(character); seenDigit = true }
                    character == '+' && !plus && !seenDigit -> plus = true
                    character.isWhitespace() || character in setOf('-', '(', ')', '.') -> Unit
                    else -> return null
                }
            }
            val normalized = (if (plus) "+" else "") + digits
            return normalized.takeIf(number::matches)
        }

        private fun normalizedName(value: String): String {
            val decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD).lowercase(Locale.ROOT)
            val output = StringBuilder()
            var pendingSpace = false
            for (character in decomposed) {
                when {
                    Character.getType(character) == Character.NON_SPACING_MARK.toInt() ||
                        Character.getType(character) == Character.COMBINING_SPACING_MARK.toInt() -> Unit
                    character == 'đ' -> {
                        if (pendingSpace && output.isNotEmpty()) output.append(' ')
                        output.append('d'); pendingSpace = false
                    }
                    character.isLetterOrDigit() -> {
                        if (pendingSpace && output.isNotEmpty()) output.append(' ')
                        output.append(character); pendingSpace = false
                    }
                    character == '-' || character == '\'' || character == '’' || character == '.' -> Unit
                    else -> pendingSpace = true
                }
            }
            return output.toString().trim()
        }

        fun intent(payload: JSONObject): Intent {
            val phone = payload.opt("phone_number")
            require(phone is String && number.matches(phone))
            // fromParts prevents URI/query/USSD injection; only validated digits
            // and an optional leading '+' can reach the Android call handler.
            return Intent(Intent.ACTION_CALL, Uri.fromParts("tel", phone as String, null))
        }
    }
}
