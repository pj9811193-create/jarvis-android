package ai.jarvis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ai.jarvis.R
import ai.jarvis.core.JarvisCore
import ai.jarvis.memory.Memory
import ai.jarvis.nlp.WakeWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ============================  JARVIS SERVICE  ============================
 * Keeps JARVIS listening after the app is swiped away, using a *foreground
 * service* with `foregroundServiceType="microphone"` and a persistent
 * notification.
 *
 * What Android permits (and what it does not):
 *   • A foreground service with the microphone type CAN hold the mic while the
 *     app is not on screen — this is the only supported way to listen in the
 *     background.
 *   • The service must be STARTED while the app is in the foreground
 *     (Android 12+). It therefore cannot be auto-started at boot; see
 *     BootReceiver, which posts a tap-to-resume notification instead.
 *   • The mic is cut off the moment the service stops being a microphone-type
 *     foreground service. There is no legitimate way around this.
 *
 * Listening model: the system recogniser is not a wake-word engine, so we run
 * short sessions in a loop and only act when the transcript contains the wake
 * word. That keeps false positives and battery use down.
 * ==========================================================================
 */
class JarvisService : Service() {

    companion object {
        const val ACTION_START = "ai.jarvis.action.START"
        const val ACTION_STOP = "ai.jarvis.action.STOP"
        const val CHANNEL_ID = "jarvis_listening"
        const val NOTIF_ID = 42
        const val RESUME_NOTIF_ID = 43

        fun start(context: Context) {
            val i = Intent(context, JarvisService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }

        /** Create the notification channel if it doesn't exist yet. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while JARVIS is listening in the background."
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private lateinit var core: JarvisCore
    private lateinit var memory: Memory
    private lateinit var tts: TextToSpeech

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var running = false
    private var busy = false
    private var ttsReady = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        core = JarvisCore(this)
        memory = Memory(this)
        ensureChannel(this)

        thread = HandlerThread("jarvis-asr").also { it.start() }
        handler = Handler(thread!!.looper)
        handler?.post { createRecognizer() }

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts.language = Locale.getDefault()
                tts.setSpeechRate(memory.ttsSpeed())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(getString(R.string.notif_listening))

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            acquireWakeLock()
            startListening()
        }
        return START_STICKY
    }

    // ---------------- recognition ----------------

    private fun createRecognizer() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(listener)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            // NO_MATCH / SPEECH_TIMEOUT are normal in a listening loop.
            restart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 400)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            handleTranscript(text)
        }
    }

    private fun startListening() {
        if (!running) return
        val lang = memory.recognitionLang().ifBlank { Locale.getDefault().toLanguageTag() }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            restart(1000)
        }
    }

    private fun restart(delayMs: Long) {
        if (!running) return
        handler?.postDelayed({ startListening() }, delayMs)
    }

    private fun handleTranscript(text: String) {
        if (busy) { restart(300); return }
        if (text.isBlank()) { restart(250); return }

        val command: String
        if (memory.wakeWordRequired()) {
            if (!WakeWord.matches(text)) { restart(200); return }
            command = WakeWord.strip(text)
            if (command.isBlank()) {
                speak("Yes, sir?")
                restart(1500)
                return
            }
        } else {
            command = text
        }

        busy = true
        updateNotification(getString(R.string.notif_working))
        scope.launch {
            val reply = try {
                core.handle(command)
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
            speak(reply)
            updateNotification(getString(R.string.notif_listening))
            // give TTS a moment so it doesn't hear itself
            busy = false
            restart(1800)
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    // ---------------- notification / lifecycle ----------------

    private fun buildNotification(text: String): Notification {
        val open = Intent(this, ai.jarvis.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stop = Intent(this, JarvisService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stop,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.notif_stop), stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        } catch (e: Exception) {
            // e.g. microphone type not permitted for this app state
        }
    }

    private fun updateNotification(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text))
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — the service still runs.
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listening").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) // 1 hour, renewed while the service lives
            }
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (e: Exception) {}
        thread?.quitSafely()
        try { wakeLock?.release() } catch (e: Exception) {}
        tts.stop(); tts.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
