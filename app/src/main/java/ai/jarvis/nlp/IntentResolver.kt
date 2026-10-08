package ai.jarvis.nlp

import ai.jarvis.ai.AIEngine

/**
 * ============================  INTENT RESOLVER  ============================
 * Turns free text into a [DeviceCommand] using the AI, and only the AI.
 *
 * There is deliberately NO regex or keyword table in this path. The model is
 * given the action schema (see AIEngine.CLASSIFIER_PROMPT) and returns a
 * structured intent; this class just maps that intent onto the command type
 * and validates it. Anything it can't map returns null, which the caller treats
 * as ordinary conversation.
 *
 * Trade-off, stated plainly: with no rules there is no offline device control
 * and every request costs one model round-trip.
 * =========================================================================
 */
class IntentResolver(private val ai: AIEngine) {

    suspend fun resolve(text: String, deviceContext: String): DeviceCommand? {
        val i = ai.classify(text, deviceContext) ?: return null

        return when (i.action) {
            "OPEN_APP" -> nonBlank(i.target)?.let { DeviceCommand.OpenApp(it) }
            "WEB_SEARCH" -> nonBlank(i.target)?.let { DeviceCommand.WebSearch(it) }
            "INSTALL_APP" -> nonBlank(i.target)?.let { DeviceCommand.InstallApp(it) }

            "OPEN_URL" -> nonBlank(i.url.ifBlank { i.target })?.let { DeviceCommand.OpenUrl(it) }

            "CALL" -> nonBlank(i.target)?.let { DeviceCommand.CallNumber(it) }

            "SEND_MESSAGE" -> {
                val channel = i.channel.takeIf { it in setOf("sms", "whatsapp", "email") } ?: "sms"
                val body = i.body.ifBlank { i.target }
                if (body.isBlank()) null
                else DeviceCommand.SendMessage(channel, i.target.ifBlank { null }, body)
            }

            "SET_TIMER" -> if (i.seconds > 0) DeviceCommand.SetTimer(i.seconds) else null

            "SET_ALARM" ->
                if (i.hour in 0..23) DeviceCommand.SetAlarm(i.hour, i.minute.coerceIn(0, 59)) else null

            "ADD_EVENT" -> nonBlank(i.target)?.let { DeviceCommand.AddEvent(it) }

            "SAVE_NOTE" -> nonBlank(i.body.ifBlank { i.target })?.let { DeviceCommand.SaveNote(it) }

            "ADD_TODO" -> nonBlank(i.body.ifBlank { i.target })?.let { DeviceCommand.AddTodo(it) }

            "READ_LIST" -> DeviceCommand.ReadList(if (i.kind == "todos") "todos" else "notes")

            "WEATHER" -> DeviceCommand.Weather(i.target)

            "READ_NOTIFICATIONS" -> DeviceCommand.ReadNotifications(summarize = false)
            "SUMMARIZE_NOTIFICATIONS" -> DeviceCommand.ReadNotifications(summarize = true)

            "SYSTEM" -> nonBlank(i.kind)?.let { DeviceCommand.SystemAction(it) }

            // ANSWER, and anything unrecognised, is conversation — not a command.
            else -> null
        }
    }

    private fun nonBlank(s: String): String? = s.trim().takeIf { it.isNotBlank() }
}
