package ai.jarvis.device

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================  DEVICE TOOLS  ============================
 * Small, offline device skills that should work with no AI key at all.
 * Extend this class with more (torch, volume, alarms, …) as needed.
 * ======================================================================
 */
class DeviceTools(private val context: Context) {

    fun answer(text: String): String? {
        val s = text.lowercase().trim()

        if (Regex("\\b(time|clock)\\b").containsMatchIn(s) && !s.contains("timezone")) {
            val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return "It is $t, sir."
        }
        if (Regex("\\b(date|day|today)\\b").containsMatchIn(s)) {
            val d = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
            return "Today is $d."
        }
        math(s)?.let { return "That comes to $it." }
        return null
    }

    private fun math(input: String): String? {
        val s = input
            .replace(Regex("what(?:'s| is)"), " ")
            .replace("plus", "+")
            .replace("minus", "-")
            .replace(Regex("(times|multiplied by|multiply by)"), "*")
            .replace(Regex("(divided by|divide by|over)"), "/")
            .replace(Regex("(to the power of|power of|raised to)"), "^")
            .replace("?", "")
            .trim()

        if (!s.matches(Regex("[-+*/%().\\d\\s^]+"))) return null
        if (s.none { it.isDigit() }) return null
        if (s.none { it in "+-*/%^" }) return null

        return try {
            val r = MathEval.eval(s)
            if (!r.isFinite()) null
            else if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
        } catch (e: Exception) {
            null
        }
    }
}
