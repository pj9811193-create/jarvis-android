package ai.jarvis.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.jarvis.R
import ai.jarvis.core.JarvisCore
import ai.jarvis.service.JarvisNotificationListener
import ai.jarvis.service.JarvisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ============================  MISSION CONTROL  ============================
 * One screen showing AI status, app index, background listening, notification
 * access, memory and voice settings.
 * =========================================================================
 */
class MissionControlActivity : AppCompatActivity() {

    private lateinit var core: JarvisCore
    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mission_control)
        core = JarvisCore(this)
        body = findViewById(R.id.mc_body)

        findViewById<Button>(R.id.mc_refresh).setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { core.rescan() }
                render()
            }
        }
        findViewById<Button>(R.id.mc_stop).setOnClickListener {
            core.memory.setBackgroundEnabled(false)
            JarvisService.stop(this)
            render()
        }
        findViewById<Button>(R.id.mc_close).setOnClickListener { finish() }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val notifications = if (JarvisNotificationListener.isEnabled(this)) "granted" else "not granted"
        body.text = buildString {
            appendLine("AI BRAIN")
            appendLine("  status      : " + if (core.aiConfigured()) "configured" else "offline mode")
            appendLine("  model       : " + core.memory.model().ifBlank { "—" })
            appendLine()
            appendLine("APP RESOLVER")
            appendLine("  indexed apps: " + core.inventorySize())
            appendLine("  aliases     : " + core.memory.aliases().size + " learned")
            appendLine("  threshold   : " + core.memory.resolverThreshold().toInt())
            appendLine()
            appendLine("BACKGROUND")
            appendLine("  listening   : " + if (core.memory.backgroundEnabled()) "ON" else "OFF")
            appendLine("  notifications: $notifications")
            appendLine()
            appendLine("VOICE")
            appendLine("  speed       : " + core.memory.ttsSpeed() + "x")
            appendLine("  language    : " + core.memory.recognitionLang().ifBlank { "system default" })
        }
    }
}
