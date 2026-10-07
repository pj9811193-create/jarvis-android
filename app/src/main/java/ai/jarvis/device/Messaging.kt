package ai.jarvis.device

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * ============================  MESSAGING  ============================
 * Drafts messages in the user's own apps. Nothing is ever sent silently —
 * every path lands the user in the compose screen with the text prefilled, so
 * a human still presses send. That is both the polite design and the only one
 * Android sanctions for SMS/email without being the default handler.
 * ===================================================================
 */
class Messaging(private val context: Context) {

    fun sms(number: String?, body: String): String {
        val uri = Uri.parse("smsto:" + (number?.filter { it.isDigit() || it == '+' } ?: ""))
        val i = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(i, "Draft ready — press send.")
    }

    /**
     * WhatsApp exposes no documented "send text" Intent, but wa.me links are
     * supported and land in the app with the message prefilled.
     */
    fun whatsapp(body: String, number: String?): String {
        val digits = number?.filter { it.isDigit() }
        val url = if (digits.isNullOrBlank()) {
            "https://wa.me/?text=" + Uri.encode(body)
        } else {
            "https://wa.me/$digits?text=" + Uri.encode(body)
        }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(i, "WhatsApp draft ready.")
    }

    fun email(to: String?, subject: String?, body: String): String {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + (to ?: "")))
            .putExtra(Intent.EXTRA_SUBJECT, subject ?: "")
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(i, "Email draft ready.")
    }

    private fun launch(intent: Intent, ok: String): String = try {
        context.startActivity(intent)
        ok
    } catch (e: Exception) {
        "Android blocked opening that from the background, sir. Try again with JARVIS in the foreground."
    }
}
