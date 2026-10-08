package ai.jarvis.ai

import ai.jarvis.memory.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================  AI ENGINE  ============================
 * Talks to any OpenAI-compatible endpoint (Groq, Gemini-compat, OpenAI, …).
 *
 * It is the ONLY interpreter of user intent — there is no rule-based parser.
 *   • classify()       → an action + fields (a target PHRASE, never a package)
 *   • chooseCandidate()→ a NUMBER indexing the resolver's verified shortlist
 *   • ask()            → ordinary conversation
 *
 * All calls are suspend and run on Dispatchers.IO. (Earlier versions called
 * this on the main thread, which Android rejects with
 * NetworkOnMainThreadException — caught and swallowed, so the AI silently
 * failed. That is fixed here.)
 * ====================================================================
 */
class AIEngine(private val memory: Memory) {

    /** A structured intent produced by the model. `target` is a phrase, not a package. */
    data class AiIntent(
        val action: String,
        val target: String = "",
        val channel: String = "",
        val body: String = "",
        val kind: String = "",
        val url: String = "",
        val seconds: Long = 0L,
        val hour: Int = -1,
        val minute: Int = -1
    )

    fun isConfigured(): Boolean = memory.apiKey().isNotBlank()

    suspend fun ask(userText: String, deviceContext: String = ""): String {
        val messages = JSONArray()
            .put(msg("system", withContext(system())))
            .put(msg("user", userText))
        return call(messages, 400) ?: "I couldn't reach the AI just now."
    }

    /** Classify a request into an action + fields. */
    suspend fun classify(text: String, deviceContext: String = ""): AiIntent? {
        val messages = JSONArray()
            .put(msg("system", CLASSIFIER_PROMPT + withContext(system(), deviceContext)))
            .put(msg("user", text))
        val raw = call(messages, 160) ?: return null
        return try {
            val j = JSONObject(jsonSlice(raw))
            val action = j.optString("action").uppercase().trim()
            if (action.isEmpty()) return null
            AiIntent(
                action = action,
                target = j.optString("target").trim(),
                channel = j.optString("channel").trim().lowercase(),
                body = j.optString("body").trim(),
                kind = j.optString("kind").trim().lowercase(),
                url = j.optString("url").trim(),
                seconds = j.optLong("seconds", 0L),
                hour = j.optInt("hour", -1),
                minute = j.optInt("minute", -1)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Choose ONE of the supplied candidates.
     * Returns a 1-based index, 0 for "none fit", or null on failure.
     * The model sees labels only and returns a number, so it cannot invent a
     * package — the caller indexes into its own verified list.
     */
    suspend fun chooseCandidate(query: String, candidates: List<String>): Int? {
        if (candidates.isEmpty()) return null
        val numbered = candidates.mapIndexed { i, label -> "${i + 1}. $label" }.joinToString("\n")
        val messages = JSONArray()
            .put(msg("system", CHOOSER_PROMPT))
            .put(msg("user", "Request: \"$query\"\n\nCandidates:\n$numbered"))
        val raw = call(messages, 8)?.trim() ?: return null
        val n = Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: return null
        return if (n in 1..candidates.size) n else 0
    }

    private fun system(): String = SYSTEM_PROMPT
    private fun withContext(base: String, deviceContext: String): String =
        if (deviceContext.isBlank()) base else "$base\n\nCurrent device context:\n$deviceContext"

    private fun msg(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    /** Pull the first {...} object out of a reply that may carry stray prose. */
    private fun jsonSlice(s: String): String {
        val i = s.indexOf('{')
        val j = s.lastIndexOf('}')
        return if (i >= 0 && j > i) s.substring(i, j + 1) else s
    }

    /** Returns the assistant text, or null on any failure. */
    private suspend fun call(messages: JSONArray, maxTokens: Int): String? =
        withContext(Dispatchers.IO) {
            val base = memory.baseUrl().ifBlank { "https://api.groq.com/openai/v1" }
            val model = memory.model().ifBlank { "llama-3.3-70b-versatile" }
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", maxTokens)
                .put("temperature", 0.2)
                .toString()

            try {
                val conn = (URL(base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer " + memory.apiKey())
                }
                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) return@withContext null

                JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            } catch (e: Exception) {
                null
            }
        }

    companion object {
        const val SYSTEM_PROMPT =
            "You are JARVIS, a concise British AI assistant running on Android. " +
                "Reply in 1-3 short sentences — your answer is spoken aloud. " +
                "Address the user as 'sir' occasionally."

        /**
         * The whole intent vocabulary. The model — not a regex table — decides
         * which action fits, and fills only that action's fields.
         */
        val CLASSIFIER_PROMPT = """
            You convert a spoken request into ONE JSON object for an Android assistant.
            Reply with ONLY compact JSON. No prose, no markdown, no code fences.

            Pick exactly one "action" and fill only the fields that action needs:

            OPEN_APP                 target = the app as the user described it
            WEB_SEARCH               target = the search query
            INSTALL_APP              target = the app to install
            OPEN_URL                 url = the full URL
            CALL                     target = a phone number or a contact name
            SEND_MESSAGE             channel = sms|whatsapp|email, target = recipient, body = the message
            SET_TIMER                seconds = integer seconds
            SET_ALARM                hour = 0-23, minute = 0-59
            ADD_EVENT                target = the event title
            SAVE_NOTE                body = the note text
            ADD_TODO                 body = the task text
            READ_LIST                kind = notes|todos
            WEATHER                  target = city, or "" for the current location
            READ_NOTIFICATIONS       (no fields)
            SUMMARIZE_NOTIFICATIONS  (no fields)
            SYSTEM                   kind = volume_up|volume_down|volume_max|mute|unmute|flashlight_on|flashlight_off|brightness_up|brightness_down|brightness_max|wifi|bluetooth|battery|briefing|mission_control
            ANSWER                   target = the user's question, when it is conversation rather than a device action

            Rules:
            - Use ANSWER for anything that is not a device action.
            - Convert times to 24-hour integers: "7pm" -> hour 19, minute 0.
            - Convert durations to seconds: "10 minutes" -> 600.
            - NEVER output a package name. OPEN_APP takes a plain-language target.

            Examples:
            "open insta"           -> {"action":"OPEN_APP","target":"instagram"}
            "open my video editor" -> {"action":"OPEN_APP","target":"my video editor"}
            "call mom"             -> {"action":"CALL","target":"mom"}
            "text priya I'm late"  -> {"action":"SEND_MESSAGE","channel":"sms","target":"priya","body":"I'm late"}
            "timer for 10 minutes" -> {"action":"SET_TIMER","seconds":600}
            "alarm at 7pm"         -> {"action":"SET_ALARM","hour":19,"minute":0}
            "turn on the torch"    -> {"action":"SYSTEM","kind":"flashlight_on"}
            "note that milk is out"-> {"action":"SAVE_NOTE","body":"milk is out"}
            "what's the weather in Patna" -> {"action":"WEATHER","target":"Patna"}
            "what's 47 times 19"   -> {"action":"ANSWER","target":"what's 47 times 19"}
        """.trimIndent()

        const val CHOOSER_PROMPT =
            "You are choosing which INSTALLED app matches the user's request. " +
                "Reply with ONLY the number of the best candidate, or 0 if none fit. " +
                "No words, no punctuation — just the number."
    }
}
