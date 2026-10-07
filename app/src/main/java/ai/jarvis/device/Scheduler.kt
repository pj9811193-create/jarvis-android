package ai.jarvis.device

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract

/**
 * ============================  SCHEDULER  ============================
 * Timers, alarms and calendar events, handed to the system apps. Using the
 * platform Intents means no background-alarm restrictions to fight and no
 * extra permissions.
 * ===================================================================
 */
class Scheduler(private val context: Context) {

    fun timer(seconds: Long): String {
        if (seconds <= 0) return "How long should the timer run, sir?"
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS timer")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(i, "Timer set for ${humanDuration(seconds)}.")
    }

    fun alarm(hour: Int, minute: Int, label: String?): String {
        if (hour !in 0..23 || minute !in 0..59) return "That doesn't look like a valid time, sir."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "JARVIS alarm")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pretty = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
        return launch(i, "Alarm set for $pretty.")
    }

    fun event(title: String): String {
        val i = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(i, "Opening a new calendar event for \"$title\".")
    }

    private fun launch(intent: Intent, ok: String): String = try {
        context.startActivity(intent)
        ok
    } catch (e: Exception) {
        "I couldn't reach the clock or calendar app, sir."
    }

    private fun humanDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h hour${if (h == 1L) "" else "s"}"
        if (m > 0) parts += "$m minute${if (m == 1L) "" else "s"}"
        if (s > 0) parts += "$s second${if (s == 1L) "" else "s"}"
        return if (parts.isEmpty()) "0 seconds" else parts.joinToString(" ")
    }
}
