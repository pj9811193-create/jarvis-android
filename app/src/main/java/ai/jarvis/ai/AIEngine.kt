package ai.jarvis.ai

import ai.jarvis.memory.Memory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================  AI ENGINE  ============================
 * Talks to any OpenAI-compatible endpoint (Groq, Gemini-compat, OpenAI,
 * OpenRouter, …). Two uses:
 *   • ask()        — ordinary conversation.
 *   • pickBestApp()— semantic app matching: given the shortlist the
 *                    AppResolver produced, choose the best one.
 * ====================================================================
 */
class AIEngine(private val memory: Memory) {

    fun isConfigured(): Boolean = memory.apiKey().isNotBlank()

    fun ask(userText: String): String {
        val messages = JSONArray()
            .put(msg("system", SYSTEM_PROMPT))
            .put(msg("user", userText))
        return call(messages, 400) ?: "I couldn't reach the AI just now."
    }

    /**
     * Ask the model to choose among candidates the resolver already shortlisted.
     * Returns the exact label or package name, or null.
     */
    fun pickBestApp(query: String, candidates: String): String? {
        val messages = JSONArray()
            .put(msg("system", PICKER_PROMPT))
            .put(msg("user", "Request: \"$query\"\n\nInstalled candidates:\n$candidates"))
        return call(messages, 24)
            ?.trim()
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.trim('"', '.', '`')
            ?.takeIf { it.isNotEmpty() }
    }

    private fun msg(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    /** Returns the assistant text, or null on any failure. */
    private fun call(messages: JSONArray, maxTokens: Int): String? {
        val base = memory.baseUrl().ifBlank { "https://api.groq.com/openai/v1" }
        val model = memory.model().ifBlank { "llama-3.3-70b-versatile" }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.4)
            .toString()

        return try {
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
            if (code !in 200..299) return null

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

        const val PICKER_PROMPT =
            "You choose which INSTALLED Android app best matches the user's request. " +
                "Reply with ONLY the exact app name from the candidate list — no explanation, " +
                "no punctuation, nothing else. If none fit, reply with: NONE"
    }
}
