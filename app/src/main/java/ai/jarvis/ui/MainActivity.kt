package ai.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.jarvis.R
import ai.jarvis.ai.AIEngine
import ai.jarvis.apps.AppLauncher
import ai.jarvis.apps.AppResolver
import ai.jarvis.device.DeviceTools
import ai.jarvis.memory.Memory
import ai.jarvis.nlp.DeviceCommand
import ai.jarvis.nlp.IntentParser
import ai.jarvis.voice.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ============================  UI  ============================
 * Wires the modules together. Note how little logic lives here — the
 * interesting work is in AppResolver / IntentParser / AIEngine, so adding a
 * new capability never means editing the UI.
 * =============================================================
 */
class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView

    private lateinit var resolver: AppResolver
    private lateinit var launcher: AppLauncher
    private lateinit var memory: Memory
    private lateinit var parser: IntentParser
    private lateinit var device: DeviceTools
    private lateinit var ai: AIEngine
    private var voice: VoiceEngine? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else append("JARVIS: I need microphone permission for voice input.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        log = findViewById(R.id.log)
        input = findViewById(R.id.input)
        scroll = findViewById(R.id.scroll)

        resolver = AppResolver(this)
        launcher = AppLauncher(this)
        memory = Memory(this)
        parser = IntentParser()
        device = DeviceTools(this)
        ai = AIEngine(memory)

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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<Button>(R.id.refresh).setOnClickListener { rescan() }
        findViewById<TextView>(R.id.title).setOnLongClickListener { showSettings(); true }

        append("JARVIS: Online. Long-press the title to set your AI key.")
        rescan()
    }

    private fun rescan() {
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { resolver.refresh().size }
            append("JARVIS: Indexed $n launchable apps on this device.")
        }
    }

    private fun startListening() {
        append("You: (listening…)")
        voice?.listen()
    }

    private fun runCommand(text: String) {
        append("You: $text")
        lifecycleScope.launch {
            val reply = handle(text)
            append("JARVIS: $reply")
            voice?.speak(reply)
        }
    }

    /** The whole decision pipeline for one utterance. */
    private suspend fun handle(text: String): String {
        when (val cmd = parser.parse(text)) {
            is DeviceCommand.OpenApp -> return resolveAndLaunch(cmd.query)
            is DeviceCommand.WebSearch -> {
                launcher.openUri("https://www.google.com/search?q=" + Uri.encode(cmd.query))
                return "Searching the web for ${cmd.query}."
            }
            is DeviceCommand.InstallApp -> {
                launcher.searchStore(cmd.query)
                return "Opening the app store for ${cmd.query}."
            }
            is DeviceCommand.OpenUrl -> {
                launcher.openUri(cmd.url)
                return "Opening ${cmd.url}."
            }
            null -> Unit
        }

        // offline device skills
        device.answer(text)?.let { return it }

        // otherwise the AI
        if (ai.isConfigured()) return ai.ask(text)
        return "I don't have an AI key yet, so I can only open apps and handle basic device tasks."
    }

    private suspend fun resolveAndLaunch(query: String): String {
        // 1) memory — has this phrase been resolved before?
        memory.lookup(query)?.let { pkg ->
            val app = resolver.inventory().firstOrNull { it.packageName == pkg }
            if (app != null && launcher.launch(app.packageName)) return "Opening ${app.label}."
        }

        // 2) discovery + local scoring against the real installed inventory
        val candidates = withContext(Dispatchers.IO) { resolver.candidates(query, 8) }
        if (candidates.isEmpty()) {
            launcher.searchStore(query)
            return "Nothing installed matches \"$query\", so I'm opening the app store."
        }

        // 3) semantic tie-break via the AI when the result is close or weak
        val top = candidates.first()
        val runnerUp = candidates.getOrNull(1)
        val ambiguous = runnerUp != null && (top.score - runnerUp.score) < 8.0
        var chosen = top
        if ((ambiguous || top.score < 20.0) && ai.isConfigured()) {
            val list = candidates.joinToString("\n") { "- ${it.app.label} (${it.app.packageName})" }
            ai.pickBestApp(query, list)?.let { pick ->
                if (pick.equals("NONE", true)) {
                    return "I couldn't find an installed app for \"$query\"."
                }
                candidates.firstOrNull {
                    it.app.label.equals(pick, true) || it.app.packageName.equals(pick, true)
                }?.let { chosen = it }
            }
        }

        // 4) learn the phrase so next time is instant
        memory.remember(query, chosen.app.packageName)

        return if (launcher.launch(chosen.app.packageName)) {
            "Opening ${chosen.app.label}."
        } else {
            launcher.openStoreListing(chosen.app.packageName)
            "${chosen.app.label} wouldn't launch, so I'm opening its store page."
        }
    }

    private fun showSettings() {
        val key = EditText(this).apply {
            hint = "API key (Groq / Gemini / OpenAI …)"
            setText(memory.apiKey())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val base = EditText(this).apply {
            hint = "Base URL"
            setText(memory.baseUrl().ifBlank { "https://api.groq.com/openai/v1" })
        }
        val model = EditText(this).apply {
            hint = "Model"
            setText(memory.model().ifBlank { "llama-3.3-70b-versatile" })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(key)
            addView(base)
            addView(model)
        }
        AlertDialog.Builder(this)
            .setTitle("JARVIS — AI settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                memory.setAi(
                    key.text.toString().trim(),
                    base.text.toString().trim(),
                    model.text.toString().trim()
                )
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                append("JARVIS: AI settings saved.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun append(line: String) {
        log.append(line + "\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        voice?.shutdown()
        super.onDestroy()
    }
}
