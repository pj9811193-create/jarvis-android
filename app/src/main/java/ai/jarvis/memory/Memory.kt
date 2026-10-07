package ai.jarvis.memory

import android.content.Context
import ai.jarvis.security.SecureStore

/**
 * ============================  MEMORY  ============================
 * Settings + learned aliases.
 *
 * The API key is kept in [SecureStore] (Keystore-encrypted), NOT in plain
 * SharedPreferences. Everything non-secret stays in normal prefs.
 * =================================================================
 */
class Memory(context: Context) {

    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    init {
        // Migrate any key written by an older plaintext build, then delete it.
        val legacy = prefs.getString("api_key", null)
        if (!legacy.isNullOrBlank()) {
            secure.put(KEY_API, legacy)
            prefs.edit().remove("api_key").apply()
        }
    }

    // ---------- AI provider ----------
    fun apiKey(): String = secure.get(KEY_API) ?: ""

    fun setApiKey(value: String) {
        if (value.isBlank()) secure.remove(KEY_API) else secure.put(KEY_API, value)
    }

    fun baseUrl(): String = prefs.getString("base_url", "") ?: ""
    fun model(): String = prefs.getString("model", "") ?: ""
    fun provider(): String = prefs.getString("provider", "Groq") ?: "Groq"

    fun setAi(apiKey: String, baseUrl: String, model: String, provider: String = provider()) {
        setApiKey(apiKey)
        prefs.edit()
            .putString("base_url", baseUrl)
            .putString("model", model)
            .putString("provider", provider)
            .apply()
    }

    // ---------- voice ----------
    fun ttsSpeed(): Float = prefs.getFloat("tts_speed", 1.0f)
    fun setTtsSpeed(v: Float) = prefs.edit().putFloat("tts_speed", v).apply()

    fun voiceName(): String = prefs.getString("tts_voice", "") ?: ""
    fun setVoiceName(v: String) = prefs.edit().putString("tts_voice", v).apply()

    fun recognitionLang(): String = prefs.getString("asr_lang", "") ?: ""
    fun setRecognitionLang(v: String) = prefs.edit().putString("asr_lang", v).apply()

    // ---------- background listening ----------
    fun backgroundEnabled(): Boolean = prefs.getBoolean("bg_enabled", false)
    fun setBackgroundEnabled(v: Boolean) = prefs.edit().putBoolean("bg_enabled", v).apply()

    fun wakeWordRequired(): Boolean = prefs.getBoolean("wake_required", true)
    fun setWakeWordRequired(v: Boolean) = prefs.edit().putBoolean("wake_required", v).apply()

    // ---------- app resolver ----------
    fun resolverThreshold(): Double = prefs.getFloat("resolver_threshold", 20.0f).toDouble()
    fun setResolverThreshold(v: Double) = prefs.edit().putFloat("resolver_threshold", v.toFloat()).apply()

    // ---------- learned aliases: phrase -> package ----------
    private fun aliasKey(query: String) = "alias_" + query.lowercase().trim()

    fun lookup(query: String): String? = prefs.getString(aliasKey(query), null)

    fun remember(query: String, packageName: String) {
        prefs.edit().putString(aliasKey(query), packageName).apply()
    }

    fun forget(query: String) {
        prefs.edit().remove(aliasKey(query)).apply()
    }

    /** All learned aliases, phrase -> package. */
    fun aliases(): Map<String, String> =
        prefs.all
            .filterKeys { it.startsWith("alias_") }
            .mapKeys { it.key.removePrefix("alias_") }
            .mapValues { it.value as? String ?: "" }

    fun clearAliases() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("alias_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    private companion object {
        const val KEY_API = "api_key"
    }
}
