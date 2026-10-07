package ai.jarvis.core

import android.content.Context
import android.net.Uri
import ai.jarvis.actions.ActionRegistry
import ai.jarvis.ai.AIEngine
import ai.jarvis.apps.AppLauncher
import ai.jarvis.apps.AppResolver
import ai.jarvis.apps.InstalledApp
import ai.jarvis.device.Caller
import ai.jarvis.device.DeviceTools
import ai.jarvis.memory.Memory
import ai.jarvis.nlp.DeviceCommand
import ai.jarvis.nlp.IntentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================  JARVIS CORE  ============================
 * The decision pipeline, extracted so the UI and the background service share
 * exactly one implementation. Enforces the trust chain:
 *
 *   text → IntentParser / AI.classify   (intent + target PHRASE)
 *        → AppResolver                   (installed inventory → package)
 *        → AppResolver.verify            (package really exists?)
 *        → ActionRegistry → AppLauncher  (OS)
 *
 * The AI never hands a package to the launcher.
 * =====================================================================
 */
class JarvisCore(context: Context) {

    val memory = Memory(context)
    private val resolver = AppResolver(context)
    private val launcher = AppLauncher(context)
    private val actions = ActionRegistry(launcher)
    private val parser = IntentParser()
    private val device = DeviceTools(context)
    private val ai = AIEngine(memory)
    private val caller = Caller(context)

    fun rescan(): Int = resolver.refresh().size
    fun inventorySize(): Int = resolver.inventory().size

    suspend fun handle(text: String): String {
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
                    "CALL" -> DeviceCommand.CallNumber(intent.target)
                    else -> null
                }
                if (cmd != null) return execute(cmd)
            }
        }

        // 3) offline device skills
        device.answer(text)?.let { return it }

        // 4) ordinary conversation
        if (ai.isConfigured()) return ai.ask(text)
        return "I don't have an AI key yet, so I can only open apps, call, and handle basic device tasks."
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
        is DeviceCommand.CallNumber -> caller.place(cmd.target)
    }

    private suspend fun resolveAndLaunch(target: String): String {
        // 1) a learned alias, verified before it is trusted
        memory.lookup(target)?.let { pkg ->
            val app = resolver.verify(pkg)
            if (app != null && actions.perform(app)) return "Opening ${app.label}."
            memory.forget(target)
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
                // The AI may only choose from what we verified — by number.
                var chosen = res.options.first().app
                if (ai.isConfigured()) {
                    when (val idx = ai.chooseCandidate(target, res.options.map { it.app.label })) {
                        0 -> return "I couldn't find an installed app for \"$target\"."
                        null -> Unit
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
}
