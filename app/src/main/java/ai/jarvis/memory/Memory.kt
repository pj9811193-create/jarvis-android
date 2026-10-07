package ai.jarvis.memory

import android.content.Context

/**
 * ============================  MEMORY  ============================
 * Two jobs:
 *   • settings — the AI key / endpoint / model.
 *   • learned aliases — once "my video editor" resolves to a package, we
 *     remember it, so the next time it is instant and needs no AI call.
 * =================================================================
 */
class Memory(context: Context) {

    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    // ---- settings ----
    fun apiKey(): String = prefs.getString("api_key", "") ?: ""
    fun baseUrl(): String = prefs.getString("base_url", "") ?: ""
    fun model(): String = prefs.getString("model", "") ?: ""

    fun setAi(key: String, base: String, model: String) {
        prefs.edit()
            .putString("api_key", key)
            .putString("base_url", base)
            .putString("model", model)
            .apply()
    }

    // ---- learned aliases: phrase -> package name ----
    private fun aliasKey(query: String) = "alias_" + query.lowercase().trim()

    fun lookup(query: String): String? = prefs.getString(aliasKey(query), null)

    fun remember(query: String, packageName: String) {
        prefs.edit().putString(aliasKey(query), packageName).apply()
    }
}
