package ai.jarvis.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ai.jarvis.R
import ai.jarvis.memory.Memory

/**
 * ============================  BOOT RECEIVER  ============================
 * Runs after a reboot.
 *
 * Android 12+ forbids starting a microphone foreground service from the
 * background — so we CANNOT silently resume listening at boot. Starting one
 * here would throw ForegroundServiceStartNotAllowedException and crash.
 *
 * The supported pattern is to post a notification the user taps to resume,
 * which is what this does.
 * =========================================================================
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        if (!Memory(context).backgroundEnabled()) return

        JarvisService.ensureChannel(context)

        val open = Intent(context, ai.jarvis.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, JarvisService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_resume))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(JarvisService.RESUME_NOTIF_ID, notif)
        } catch (e: SecurityException) {
            // Notifications not permitted; nothing else we can legitimately do.
        }
    }
}
