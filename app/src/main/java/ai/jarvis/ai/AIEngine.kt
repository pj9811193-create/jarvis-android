package ai.jarvis.ai

import ai.jarvis.memory.Memory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================  AI ENGINE  ============================
 * Talks to any OpenAI-compatible endpoint (Groq, Gemini-compat, OpenAI, …).
 *
 * IMPORTANT — the AI never decides a package name. It can only:
 *   • classify(text)        → an intent (OPEN_APP / WEB_SEARCH / …) + a target
 *                             *phrase*, never a package
 *   • chooseCandidate(...)  → a NUMBER, indexing into the candidate list that
 *                             AppResolver already produced and verified
 *   • ask(text)             → ordinary conversation
 *
 * The package name is always produced by AppResolver, from the real installed
 * inventory. See the trust chain in the README.
 * ====================================================================
 */
class AIEngine(private val memory: Memory) {

    /** An intent classified from free text. `target` is a phrase, not a package. */
    data class AiIntent(val action: String, val target: String)

    fun isConfigured(): Boolean = memory.apiKey().isNotBlank()

    fun ask(userText: String): String {
        val messages = JSONArray()
            .put(msg("system", SYSTEM_PROMPT))
            .put(msg("user", userText))
        return call(messages, 400) ?: "I couldn't reach the AI just now."
    }

    /** Classify a request into an action + target phrase. */
    fun classify(text: String): AiIntent? {
        val messages = JSONArray()
            .put(msg("system", CLASSIFIER_PROMPT))
            .put(msg("user", text))
        val raw = call(messages, 60) ?: return null
        return try {
            val json = JSONObject(jsonSlice(raw))
            val action = json.optString("action").uppercase().trim()
            val target = json.optString("target").trim()
            if (action.isEmpty()) null else AiIntent(action, target)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Choose ONE of the supplied candidates.
     * Returns a 1-based index, or 0 for "none fit", or null on failure.
     *
     * The model only ever sees labels and returns a number, so it cannot invent
     * or hallucinate a package — the caller indexes into its own verified list.
     */
    fun chooseCandidate(query: String, candidates: List<String>): Int? {
        if (candidates.isEmpty()) return null
        val numbered = candidates.mapIndexed { i, label -> "${i + 1}. $label" }.joinToString("\n")
        val messages = JSONArray()
            .put(msg("system", CHOOSER_PROMPT))
            .put(msg("user", "Request: \"$query\"\n\nCandidates:\n$numbered"))
        val raw = call(messages, 8)?.trim() ?: return null
        val n = Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: return null
        return if (n in 1..candidates.size) n else 0
    }

    private fun msg(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    /** Pull the first {...} object out of a reply that may carry stray prose. */
    private fun jsonSlice(s: String): String {
        val i = s.indexOf('{')
        val j = s.lastIndexOf('}')
        return if (i >= 0 && j > i) s.substring(i, j + 1) else s
    }

    /** Returns the assistant text, or null on any failure. */
    private fun call(messages: JSONArray, maxTokens: Int): String? {
        val base = memory.baseUrl().ifBlank { "https://api.groq.com/openai/v1" }
        val model = memory.model().ifBlank { "llama-3.3-70b-versatile" }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.2)
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

        /**
         * The classifier must emit intent + a target PHRASE. It must never emit
         * a package name — it has no idea what is installed, and we do not trust
         * it to guess.
         */
        const val CLASSIFIER_PROMPT =
            "Classify the user's request for an Android assistant. Reply with ONLY compact JSON, " +
                "no prose, in this exact shape:\n" +
                "{\"action\":\"OPEN_APP\",\"target\":\"video editor\"}\n" +
                "action is one of: OPEN_APP, WEB_SEARCH, INSTALL_APP, ANSWER.\n" +
                "Use OPEN_APP when the user wants to open/launch an app; target is the phrase they " +
                "used to describe it (e.g. \"my video editor\", \"the app I use for coding\").\n" +
                "Use WEB_SEARCH when they want to search the web (target = the query).\n" +
                "Use INSTALL_APP when they want to install/download something.\n" +
                "Use ANSWER for everything else (target = the question).\n" +
                "NEVER output a package name — only a plain-language target."

        /**
         * The chooser picks a NUMBER from a list we supply. That is the whole
         * safety property: it cannot name something that isn't in the list.
         */
        const val CHOOSER_PROMPT =
            "You are choosing which INSTALLED app matches the user's request. " +
                "Reply with ONLY the number of the best candidate, or 0 if none fit. " +
                "No words, no punctuation — just the number."
    }
}
