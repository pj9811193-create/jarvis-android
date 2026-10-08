package ai.jarvis.nlp

/**
 * A device action, as produced by the AI's intent resolver.
 *
 * Note: this type is now the OUTPUT of [ai.jarvis.ai.AIEngine.classify] — there
 * is no regex parser producing it. See IntentResolver.
 */
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
    data class SystemAction(val kind: String) : DeviceCommand
}
