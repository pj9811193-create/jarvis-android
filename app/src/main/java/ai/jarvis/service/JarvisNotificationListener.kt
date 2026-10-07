package ai.jarvis.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * ============================  NOTIFICATION LISTENER  ============================
 * Lets JARVIS read (and therefore summarise) incoming notifications.
 *
 * This is a special-access service: the user must enable it in
 * Settings → Notification access. It cannot be granted silently, and it is the
 * only sanctioned way to read other apps' notifications.
 *
 * We keep a small rolling buffer in memory — no persistence, no upload.
 * ==============================================================================
 */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        if (sbn.packageName == packageName) return

        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val app = sbn.packageName.substringAfterLast('.')
        val line = listOf(app, title, text).filter { it.isNotBlank() }.joinToString(": ")

        if (line.isBlank()) return
        synchronized(buffer) {
            buffer.add(0, line)
            while (buffer.size > MAX) buffer.removeAt(buffer.size - 1)
        }
    }

    companion object {
        private const val MAX = 25
        private val buffer = mutableListOf<String>()

        /** True if the user has granted notification access to this app. */
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun recent(limit: Int = 10): List<String> = synchronized(buffer) {
            buffer.take(limit)
        }

        fun clear() = synchronized(buffer) { buffer.clear() }
    }
}
