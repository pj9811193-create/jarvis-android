package ai.jarvis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.jarvis.R
import ai.jarvis.actions.ActionRegistry
import ai.jarvis.ai.AIEngine
import ai.jarvis.apps.AppLauncher
import ai.jarvis.apps.AppResolver
import ai.jarvis.apps.InstalledApp
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
 * Wires the modules together and enforces the trust chain:
 *
 *   text → IntentParser / AI.classify   (intent + target PHRASE)
 *        → AppResolver                   (installed inventory → package)
 *        → AppResolver.verify            (package really exists?)
 *        → ActionRegistry → AppLauncher  (OS)
 *
 * The AI can never hand a package name to the launcher. It either classifies
 * an intent, or picks a NUMBER from a list the resolver already verified.
 * =============================================================
 */
class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView

    private lateinit var resolver: AppResolver
    private lateinit var launcher: AppLauncher
    private lateinit var actions: ActionRegistry
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
        actions = ActionRegistry(launcher)
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
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        append("JARVIS: Online. I can see the apps installed on this device.")
        rescan()
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed the voice; re-apply on the way back.
        voice?.applySettings(memory.ttsSpeed(), memory.voiceName(), memory.recognitionLang())
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

    // ------------------------------------------------------------------
    //  Pipeline
    // ------------------------------------------------------------------

    private suspend fun handle(text: String): String {
        // 1) deterministic device verbs — no AI needed
        parser.parse(text)?.let { return execute(it) }

        // 2) AI classifies intent + target PHRASE (never a package)
        if (ai.isConfigured()) {
            val intent = ai.classify(text)
            if (intent != null && intent.action != "ANSWER" && intent.target.isNotBlank()) {
                val cmd = when (intent.action) {
                    "OPEN_APP" -> DeviceCommand.OpenApp(intent.target)
                    "WEB_SEARCH" -> DeviceCommand.WebSearch(intent.target)
                    "INSTALL_APP" -> DeviceCommand.InstallApp(intent.target)
                    else -> null
                }
                if (cmd != null) return execute(cmd)
            }
        }

        // 3) offline device skills
        device.answer(text)?.let { return it }

        // 4) ordinary conversation
        if (ai.isConfigured()) return ai.ask(text)
        return "I don't have an AI key yet, so I can only open apps and handle basic device tasks."
    }

    private suspend fun execute(cmd: DeviceCommand): String = when (cmd) {
        is DeviceCommand.OpenApp -> resolveAndLaunch(cmd.query)
        is DeviceCommand.WebSearch -> {
            launcher.openUri("https://www.google.com/search?q=" + Uri.encode(cmd.query))
            "Searching the web for ${cmd.query}."
        }
        is DeviceCommand.InstallApp -> {
            launcher.searchStore(cmd.query)
            "Opening the app store for ${cmd.query}."
        }
        is DeviceCommand.OpenUrl -> {
            launcher.openUri(cmd.url)
            "Opening ${cmd.url}."
        }
    }

    /**
     * The trust chain, in code:
     *   memory alias  → VERIFY it still exists
     *   resolver      → ranking over the real installed inventory
     *   AI            → may only choose an INDEX from those candidates
     *   verify again  → then, and only then, launch
     */
    private suspend fun resolveAndLaunch(target: String): String {
        // 1) a learned alias, but never trusted blindly — verify it first
        memory.lookup(target)?.let { pkg ->
            val app = resolver.verify(pkg)
            if (app != null && actions.perform(app)) return "Opening ${app.label}."
            memory.forget(target)   // stale alias: the app is gone or changed
        }

        // 2) discovery + ranking against the real inventory
        val threshold = memory.resolverThreshold()
        return when (val res = withContext(Dispatchers.IO) { resolver.resolve(target, threshold) }) {

            is AppResolver.Resolution.NotFound -> {
                launcher.searchStore(target)
                "Nothing installed matches \"$target\", so I'm opening the app store."
            }

            is AppResolver.Resolution.Resolved -> launchVerified(res.app, target)

            is AppResolver.Resolution.Ambiguous -> {
                // The AI may only pick from what we verified — by number.
                var chosen = res.options.first().app
                if (ai.isConfigured()) {
                    when (val idx = ai.chooseCandidate(target, res.options.map { it.app.label })) {
                        0 -> return "I couldn't find an installed app for \"$target\"."
                        null -> Unit                       // AI failed; keep local best
                        else -> chosen = res.options[idx - 1].app
                    }
                }
                launchVerified(chosen, target)
            }
        }
    }

    /** Final gate: re-verify the package, then hand it to the ActionRegistry. */
    private fun launchVerified(app: InstalledApp, target: String): String {
        val verified = resolver.verify(app.packageName)
            ?: return "That app isn't installed any more, so I'm opening the app store."

        memory.remember(target, verified.packageName)

        return if (actions.perform(verified)) {
            "Opening ${verified.label}."
        } else {
            launcher.openStoreListing(verified.packageName)
            "${verified.label} wouldn't launch, so I'm opening its store page."
        }
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
