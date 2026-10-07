package ai.jarvis.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * ============================  CALLER  ============================
 * Places a call from a spoken number, or from a contact name.
 *
 * Two honest caveats, both Android rules rather than bugs:
 *   • ACTION_CALL (ring immediately) needs the CALL_PHONE runtime permission.
 *     Without it we fall back to ACTION_DIAL, which opens the dialer prefilled.
 *   • Android 10+ blocks starting an activity from the background, so a call
 *     triggered by the background service may be refused. We surface that
 *     instead of pretending it worked.
 * =================================================================
 */
class Caller(private val context: Context) {

    fun place(target: String): String {
        val number = resolveNumber(target)
        if (number.isNullOrBlank()) {
            return "I couldn't find a number for \"$target\", sir."
        }

        val canCallDirectly = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val uri = Uri.fromParts("tel", number, null)
        val action = if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            if (canCallDirectly) "Calling $number, sir." else "Opening the dialer for $number."
        } catch (e: Exception) {
            // Background activity-start restriction (Android 10+).
            "Android blocked the call from the background. Open JARVIS and ask again, sir."
        }
    }

    /** A literal number if one was spoken, otherwise a contact lookup. */
    private fun resolveNumber(target: String): String? {
        val spokenDigits = target.filter { it.isDigit() || it == '+' }
        if (spokenDigits.count { it.isDigit() } >= 3) return spokenDigits
        return lookupContact(target)
    }

    private fun lookupContact(name: String): String? {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) return null

        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(name)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
