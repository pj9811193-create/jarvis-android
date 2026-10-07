package ai.jarvis.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.jarvis.actions.ActionRegistry
import ai.jarvis.ai.AIEngine
import ai.jarvis.apps.AppLauncher
import ai.jarvis.apps.AppResolver
import ai.jarvis.apps.InstalledApp
import ai.jarvis.device.Caller
import ai.jarvis.device.DeviceTools
import ai.jarvis.device.Messaging
import ai.jarvis.device.NotesStore
import ai.jarvis.device.Scheduler
import ai.jarvis.device.SystemControls
import ai.jarvis.device.Weather
import ai.jarvis.memory.Memory
import ai.jarvis.nlp.DeviceCommand
import ai.jarvis.nlp.IntentParser
import ai.jarvis.service.JarvisNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================  JARVIS CORE  ============================
 * The decision pipeline, shared by the UI and the background service.
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

    private val app: Context = context.applicationContext

    val memory = Memory(app)
    private val resolver = AppResolver(app)
    private val launcher = AppLauncher(app)
    private val actions = ActionRegistry(launcher)
    private val parser = IntentParser()
    private val device = DeviceTools(app)
    private val ai = AIEngine(memory)
    private val caller = Caller(app)

    private val system = SystemControls(app)
    private val messaging = Messaging(app)
    private val scheduler = Scheduler(app)
    private val notes = NotesStore(app)
    private val weather = Weather()

    fun rescan(): Int = resolver.refresh().size
    fun inventorySize(): Int = resolver.inventory().size
    fun aiConfigured(): Boolean = ai.isConfigured()

    suspend fun handle(text: String): String {
        // 1) deterministic device verbs — no AI, no network, no latency
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
        return "I don't have an AI key yet, so I can only run device commands. Add a key in Settings for conversation."
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

        is DeviceCommand.SendMessage -> when (cmd.channel) {
            "whatsapp" -> messaging.whatsapp(cmd.body, cmd.target)
            "email" -> messaging.email(cmd.target, null, cmd.body)
            else -> messaging.sms(cmd.target, cmd.body)
        }

        is DeviceCommand.SetTimer -> scheduler.timer(cmd.seconds)
        is DeviceCommand.SetAlarm -> scheduler.alarm(cmd.hour, cmd.minute, null)
        is DeviceCommand.AddEvent -> scheduler.event(cmd.title)

        is DeviceCommand.SaveNote -> notes.addNote(cmd.text)
        is DeviceCommand.AddTodo -> notes.addTodo(cmd.text)
        is DeviceCommand.ReadList -> if (cmd.kind == "todos") notes.todos() else notes.notes()

        is DeviceCommand.Weather -> weather.current(cmd.city)
        is DeviceCommand.ReadNotifications -> readNotifications(cmd.summarize)
        is DeviceCommand.SystemAction -> systemAction(cmd.kind)
    }

    private fun systemAction(kind: String): String = when (kind) {
        "volume_up" -> system.volumeUp()
        "volume_down" -> system.volumeDown()
        "volume_max" -> system.volumeMax()
        "mute" -> system.mute()
        "unmute" -> system.unmute()
        "flashlight_on" -> system.flashlight(true)
        "flashlight_off" -> system.flashlight(false)
        "brightness_up" -> system.brightnessUp()
        "brightness_down" -> system.brightnessDown()
        "brightness_max" -> system.brightnessMax()
        "wifi" -> system.openWifi()
        "bluetooth" -> system.openBluetooth()
        "battery" -> system.battery()
        "briefing" -> briefing()
        "mission_control" -> openMissionControl()
        else -> "I don't know how to do that yet, sir."
    }

    // ---------------- notifications & briefing ----------------

    private suspend fun readNotifications(summarize: Boolean): String {
        if (!JarvisNotificationListener.isEnabled(app)) {
            return "I need notification access for that — enable it in Settings, sir."
        }
        val items = JarvisNotificationListener.recent(10)
        if (items.isEmpty()) return "No notifications right now, sir."

        if (summarize && ai.isConfigured()) {
            val joined = items.joinToString("\n")
            return ai.ask("Summarise these phone notifications in one short paragraph:\n$joined")
        }
        return "You have ${items.size} recent notification${if (items.size == 1) "" else "s"}: " +
            items.take(5).joinToString("; ")
    }

    private fun briefing(): String {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
        val battery = system.battery()
        val todos = notes.todos()
        val notifCount = if (JarvisNotificationListener.isEnabled(app))
            JarvisNotificationListener.recent(25).size else 0

        return buildString {
            append("Good day, sir. It's $time on $date. ")
            append("$battery ")
            if (notifCount > 0) append("You have $notifCount recent notifications. ")
            else append("No new notifications. ")
            append(todos)
        }
    }

    private fun openMissionControl(): String = try {
        app.startActivity(
            Intent().setClassName(app, "ai.jarvis.ui.MissionControlActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        "Opening Mission Control."
    } catch (e: Exception) {
        "I couldn't open Mission Control, sir."
    }

    // ---------------- app resolution (trust chain) ----------------

    private suspend fun resolveAndLaunch(target: String): String {
        memory.lookup(target)?.let { pkg ->
            val app = resolveVerified(pkg)
            if (app != null && actions.perform(app)) return "Opening ${app.label}."
            memory.forget(target)
        }

        val threshold = memory.resolverThreshold()
        return when (val res = withContext(Dispatchers.IO) { resolver.resolve(target, threshold) }) {

            is AppResolver.Resolution.NotFound -> {
                launcher.searchStore(target)
                "Nothing installed matches \"$target\", so I'm opening the app store."
            }

            is AppResolver.Resolution.Resolved -> launchVerified(res.app, target)

            is AppResolver.Resolution.Ambiguous -> {
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

    private fun resolveVerified(packageName: String): InstalledApp? =
        resolver.verify(packageName)

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
