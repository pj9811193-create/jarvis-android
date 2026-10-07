package ai.jarvis.nlp

/** A device action recognised from the user's words. */
sealed interface DeviceCommand {
    data class OpenApp(val query: String) : DeviceCommand
    data class WebSearch(val query: String) : DeviceCommand
    data class InstallApp(val query: String) : DeviceCommand
    data class OpenUrl(val url: String) : DeviceCommand
    data class CallNumber(val target: String) : DeviceCommand
}

/**
 * ============================  INTENT PARSER  ============================
 * Cheap, offline, deterministic parsing of device-action phrasings. Open-ended
 * language is left to the AI; this catches the "do a thing on my phone" verbs
 * so they always work with or without an API key.
 * ======================================================================
 */
class IntentParser {

    private val url = Regex("^(https?://\\S+)$", RegexOption.IGNORE_CASE)
    private val call = Regex("^(?:call|dial|phone|ring)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val install = Regex("^(?:install|download|get)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val search = Regex("^(?:search(?: for)?|google|look up|find)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val open = Regex(
        "^(?:please\\s+)?(?:open|launch|start|run|go to|switch to|bring up)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )

    fun parse(raw: String): DeviceCommand? {
        val s = raw.trim()
        url.find(s)?.let { return DeviceCommand.OpenUrl(it.groupValues[1]) }
        call.find(s)?.let { return DeviceCommand.CallNumber(it.groupValues[1].trim()) }
        install.find(s)?.let { return DeviceCommand.InstallApp(it.groupValues[1].trim()) }
        search.find(s)?.let { return DeviceCommand.WebSearch(it.groupValues[1].trim()) }
        open.find(s)?.let { return DeviceCommand.OpenApp(it.groupValues[1].trim()) }
        return null
    }
}
