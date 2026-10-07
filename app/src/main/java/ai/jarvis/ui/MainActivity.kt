package ai.jarvis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.jarvis.R
import ai.jarvis.core.JarvisCore
import ai.jarvis.service.JarvisService
import ai.jarvis.voice.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ============================  UI  ============================
 * Thin wrapper over JarvisCore (which the background service shares) plus the
 * controls for permissions and 24/7 background listening.
 * =============================================================
 */
class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var bgStatus: TextView

    private lateinit var core: JarvisCore
    private var voice: VoiceEngine? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else append("JARVIS: I need microphone permission for voice input.")
        }

    private val bgPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val mic = result[Manifest.permission.RECORD_AUDIO]
                ?: has(Manifest.permission.RECORD_AUDIO)
            if (mic) launchService() else append("JARVIS: ${getString(R.string.bg_need_mic)}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        log = findViewById(R.id.log)
        input = findViewById(R.id.input)
        scroll = findViewById(R.id.scroll)
        bgStatus = findViewById(R.id.bg_status)

        core = JarvisCore(this)

        voice = VoiceEngine(
            context = this,
            onResult = { runCommand(it) },
            onError = { append("JARVIS: $it") }
        )

        findViewById<Button>(R.id.send).setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                input.setText("")
                runCommand(t)
            }
        }
        findViewById<Button>(R.id.mic).setOnClickListener {
            if (has(Manifest.permission.RECORD_AUDIO)) startListening()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<Button>(R.id.refresh).setOnClickListener { rescan() }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.mission).setOnClickListener {
            startActivity(Intent(this, MissionControlActivity::class.java))
        }
        findViewById<Button>(R.id.bg_start).setOnClickListener { requestBackground() }
        findViewById<Button>(R.id.bg_stop).setOnClickListener { stopBackground() }

        append("JARVIS: Online. I can see the apps installed on this device.")
        rescan()
        updateBgStatus()
    }

    override fun onResume() {
        super.onResume()
        voice?.applySettings(core.memory.ttsSpeed(), core.memory.voiceName(), core.memory.recognitionLang())
        updateBgStatus()
    }

    // ---------------- pipeline ----------------

    private fun runCommand(text: String) {
        append("You: $text")
        lifecycleScope.launch {
            val reply = core.handle(text)
            append("JARVIS: $reply")
            voice?.speak(reply)
        }
    }

    private fun rescan() {
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { core.rescan() }
            append("JARVIS: Indexed $n launchable apps on this device.")
        }
    }

    private fun startListening() {
        append("You: (listening…)")
        voice?.listen()
    }

    // ---------------- background listening ----------------

    private fun requestBackground() {
        val needed = mutableListOf<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO
        if (!has(Manifest.permission.CALL_PHONE)) needed += Manifest.permission.CALL_PHONE
        if (!has(Manifest.permission.READ_CONTACTS)) needed += Manifest.permission.READ_CONTACTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !has(Manifest.permission.POST_NOTIFICATIONS)
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) launchService() else bgPermissions.launch(needed.toTypedArray())
    }

    private fun launchService() {
        core.memory.setBackgroundEnabled(true)
        JarvisService.start(this)
        append("JARVIS: Background listening on. Say \"Jarvis\" any time — even with the app closed.")
        updateBgStatus()
        promptBattery()
    }

    private fun stopBackground() {
        core.memory.setBackgroundEnabled(false)
        JarvisService.stop(this)
        append("JARVIS: Background listening off.")
        updateBgStatus()
    }

    private fun updateBgStatus() {
        val on = core.memory.backgroundEnabled()
        bgStatus.text = getString(if (on) R.string.bg_status_on else R.string.bg_status_off)
    }

    private fun promptBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle(R.string.bg_battery_title)
            .setMessage(R.string.bg_battery)
            .setPositiveButton(R.string.bg_allow) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    openBatteryList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openBatteryList() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            // nothing more we can do
        }
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun append(line: String) {
        log.append(line + "\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        voice?.shutdown()
        super.onDestroy()
    }
}
