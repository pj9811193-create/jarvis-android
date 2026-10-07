package ai.jarvis.nlp

/** A device action recognised from the user's words. */
sealed interface DeviceCommand {
    data class OpenApp(val query: String) : DeviceCommand
    data class WebSearch(val query: String) : DeviceCommand
    data class InstallApp(val query: String) : DeviceCommand
    data class OpenUrl(val url: String) : DeviceCommand
    data class CallNumber(val target: String) : DeviceCommand

    /** channel = "sms" | "whatsapp" | "email" */
    data class SendMessage(val channel: String, val target: String?, val body: String) : DeviceCommand

    data class SetTimer(val seconds: Long) : DeviceCommand
    data class SetAlarm(val hour: Int, val minute: Int) : DeviceCommand
    data class AddEvent(val title: String) : DeviceCommand

    data class SaveNote(val text: String) : DeviceCommand
    data class AddTodo(val text: String) : DeviceCommand
    /** kind = "notes" | "todos" */
    data class ReadList(val kind: String) : DeviceCommand

    data class Weather(val city: String) : DeviceCommand
    data class ReadNotifications(val summarize: Boolean) : DeviceCommand

    /** kind = volume_up, flashlight_on, brightness_max, wifi, battery, briefing, … */
    data class SystemAction(val kind: String, val arg: String? = null) : DeviceCommand
}

/**
 * ============================  INTENT PARSER  ============================
 * Offline, deterministic parsing of device-action phrasings. Deliberately
 * rule-based: these commands must work with no API key, no network and no
 * latency. Anything not matched here falls through to the AI.
 *
 * Order matters — specific patterns are tested before the generic "open X".
 * ======================================================================
 */
class IntentParser {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // ---- generic ----
    private val url = rx("^(https?://\\S+)$")
    private val open = rx("^(?:please\\s+)?(?:open|launch|start|run|go to|switch to|bring up)\\s+(.+)$")
    private val search = rx("^(?:search(?: for)?|google|look up|find)\\s+(.+)$")
    private val install = rx("^(?:install|download|get)\\s+(.+)$")
    private val call = rx("^(?:call|dial|phone|ring)\\s+(.+)$")

    // ---- system ----
    private val flashlightOn = rx("\\b(?:turn|switch|put)\\s+on\\s+(?:the\\s+)?(?:flashlight|torch)\\b|\\b(?:flashlight|torch)\\s+on\\b")
    private val flashlightOff = rx("\\b(?:turn|switch|put)\\s+off\\s+(?:the\\s+)?(?:flashlight|torch)\\b|\\b(?:flashlight|torch)\\s+off\\b")
    private val volumeUp = rx("\\b(?:volume up|increase (?:the )?volume|louder|turn (?:the )?volume up)\\b")
    private val volumeDown = rx("\\b(?:volume down|decrease (?:the )?volume|quieter|lower (?:the )?volume|turn (?:the )?volume down)\\b")
    private val volumeMax = rx("\\b(?:max(?:imum)? volume|full volume)\\b")
    private val mute = rx("^\\s*(?:mute|silence)(?:\\s+the\\s+(?:phone|volume|sound))?\\s*$")
    private val unmute = rx("\\bunmute\\b")
    private val brightnessUp = rx("\\b(?:brightness up|brighter|increase (?:the )?brightness|turn up (?:the )?brightness)\\b")
    private val brightnessDown = rx("\\b(?:brightness down|dimmer|decrease (?:the )?brightness|lower (?:the )?brightness|turn down (?:the )?brightness)\\b")
    private val brightnessMax = rx("\\b(?:max(?:imum)? brightness|full brightness)\\b")
    private val wifi = rx("\\b(?:wi-?fi)\\b")
    private val bluetooth = rx("\\bbluetooth\\b")
    private val battery = rx("\\bbattery\\b")
    private val briefing = rx("\\b(?:brief me|daily briefing|my briefing|what'?s my day|morning briefing)\\b")
    private val missionControl = rx("\\b(?:mission control|system status|jarvis status|diagnostics)\\b")

    // ---- messaging ----
    private val whatsapp = rx("^(?:send (?:a )?whats?app(?: message)?|whats?app)\\s*(?:to\\s+(.+?))?\\s*(?:saying|that says|:)\\s*(.+)$")
    private val sms = rx("^(?:send (?:a )?(?:sms|text|message)|text)\\s+(?:to\\s+)?(.+?)\\s+(?:saying|that says|:)\\s*(.+)$")
    private val email = rx("^(?:send (?:an )?email|email|mail)\\s+(?:to\\s+)?([^\\s]+@[^\\s]+)(?:\\s+(?:saying|about|:)\\s+(.+))?$")

    // ---- scheduling ----
    private val timer = rx("^(?:set )?(?:a )?timer (?:for )?(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)?$")
    private val alarm = rx("^(?:set )?(?:an )?alarm (?:for )?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$")
    private val event = rx("^(?:create|add|schedule)\\s+(?:a\\s+)?(?:calendar\\s+)?(?:event|appointment|meeting)\\s+(?:called\\s+|titled\\s+|for\\s+)?(.+)$")

    // ---- notes ----
    private val noteAdd = rx("^(?:take|make|create|write|add)\\s+(?:a\\s+)?note\\s*(?:that\\s+|saying\\s+|:\\s*)?(.+)$")
    private val noteRead = rx("^(?:read|show|list)\\s+(?:me\\s+)?my\\s+notes\\b.*$|^what (?:are|were) my notes\\b.*$")
    private val todoAdd1 = rx("^(?:add|put)\\s+(.+?)\\s+(?:to|on)\\s+my\\s+(?:to-?do|task)(?:\\s+list)?$")
    private val todoAdd2 = rx("^(?:add|create)\\s+(?:a\\s+)?(?:to-?do|task)\\s*:?\\s*(.+)$")
    private val todoRead = rx("^(?:read|show|list)\\s+(?:me\\s+)?my\\s+(?:to-?dos?|tasks)\\b.*$|^what (?:are|were) my (?:to-?dos?|tasks)\\b.*$")

    // ---- info ----
    private val weather = rx("^(?:what'?s\\s+(?:the\\s+)?)?weather(?:\\s+(?:in|at|for)\\s+(.+))?$")
    private val notifSummarize = rx("^(?:summari[sz]e)\\s+(?:my\\s+)?notifications\\b.*$")
    private val notifRead = rx("^(?:read|show|check|any)\\s+(?:me\\s+)?(?:my\\s+)?notifications\\b.*$|^what(?:'s| are)\\s+my\\s+notifications\\b.*$")

    fun parse(raw: String): DeviceCommand? {
        val s = raw.trim()

        url.find(s)?.let { return DeviceCommand.OpenUrl(it.groupValues[1]) }

        // ---- system controls (before "open X", so "open wifi" hits Wi-Fi) ----
        when {
            flashlightOn.containsMatchIn(s) -> return DeviceCommand.SystemAction("flashlight_on")
            flashlightOff.containsMatchIn(s) -> return DeviceCommand.SystemAction("flashlight_off")
            volumeUp.containsMatchIn(s) -> return DeviceCommand.SystemAction("volume_up")
            volumeDown.containsMatchIn(s) -> return DeviceCommand.SystemAction("volume_down")
            volumeMax.containsMatchIn(s) -> return DeviceCommand.SystemAction("volume_max")
            mute.matches(s) -> return DeviceCommand.SystemAction("mute")
            unmute.containsMatchIn(s) -> return DeviceCommand.SystemAction("unmute")
            brightnessMax.containsMatchIn(s) -> return DeviceCommand.SystemAction("brightness_max")
            brightnessUp.containsMatchIn(s) -> return DeviceCommand.SystemAction("brightness_up")
            brightnessDown.containsMatchIn(s) -> return DeviceCommand.SystemAction("brightness_down")
            wifi.containsMatchIn(s) -> return DeviceCommand.SystemAction("wifi")
            bluetooth.containsMatchIn(s) -> return DeviceCommand.SystemAction("bluetooth")
            missionControl.containsMatchIn(s) -> return DeviceCommand.SystemAction("mission_control")
            briefing.containsMatchIn(s) -> return DeviceCommand.SystemAction("briefing")
            battery.containsMatchIn(s) -> return DeviceCommand.SystemAction("battery")
        }

        // ---- messaging ----
        whatsapp.find(s)?.let {
            return DeviceCommand.SendMessage("whatsapp", it.groupValues[1].ifBlank { null }, it.groupValues[2])
        }
        sms.find(s)?.let {
            return DeviceCommand.SendMessage("sms", it.groupValues[1], it.groupValues[2])
        }
        email.find(s)?.let {
            return DeviceCommand.SendMessage("email", it.groupValues[1], it.groupValues[2])
        }

        call.find(s)?.let { return DeviceCommand.CallNumber(it.groupValues[1].trim()) }

        // ---- scheduling ----
        timer.find(s)?.let {
            val n = it.groupValues[1].toLongOrNull() ?: return@let
            val unit = it.groupValues[2].lowercase()
            val seconds = when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("s") -> n
                else -> n * 60          // bare number -> minutes
            }
            return DeviceCommand.SetTimer(seconds)
        }
        alarm.find(s)?.let {
            var hour = it.groupValues[1].toIntOrNull() ?: return@let
            val minute = it.groupValues[2].toIntOrNull() ?: 0
            when (it.groupValues[3].lowercase()) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            if (hour !in 0..23 || minute !in 0..59) return@let
            return DeviceCommand.SetAlarm(hour, minute)
        }
        event.find(s)?.let { return DeviceCommand.AddEvent(it.groupValues[1].trim()) }

        // ---- notes ----
        noteRead.find(s)?.let { return DeviceCommand.ReadList("notes") }
        todoRead.find(s)?.let { return DeviceCommand.ReadList("todos") }
        noteAdd.find(s)?.let { return DeviceCommand.SaveNote(it.groupValues[1].trim()) }
        todoAdd1.find(s)?.let { return DeviceCommand.AddTodo(it.groupValues[1].trim()) }
        todoAdd2.find(s)?.let { return DeviceCommand.AddTodo(it.groupValues[1].trim()) }

        // ---- info ----
        notifSummarize.find(s)?.let { return DeviceCommand.ReadNotifications(summarize = true) }
        notifRead.find(s)?.let { return DeviceCommand.ReadNotifications(summarize = false) }
        weather.find(s)?.let { return DeviceCommand.Weather(it.groupValues[1].trim()) }

        // ---- generic (least specific, so last) ----
        install.find(s)?.let { return DeviceCommand.InstallApp(it.groupValues[1].trim()) }
        search.find(s)?.let { return DeviceCommand.WebSearch(it.groupValues[1].trim()) }
        open.find(s)?.let { return DeviceCommand.OpenApp(it.groupValues[1].trim()) }

        return null
    }
}
