package ai.jarvis.nlp

/**
 * Wake-word matching for always-on listening.
 *
 * This is deliberately simple: the system speech recogniser is not a wake-word
 * engine, so we listen in short sessions and only act when the transcript
 * contains the wake word. That keeps false positives low and battery use sane.
 * A dedicated on-device wake-word model (e.g. Picovoice Porcupine) would be the
 * next step if you want true "say the word from silence".
 */
object WakeWord {

    /** Common mis-hearings of "Jarvis". */
    private val VARIANTS = listOf(
        "jarvis", "jervis", "jarvas", "javis", "charvis", "jarvus", "jarves"
    )

    fun matches(text: String): Boolean {
        val t = text.lowercase()
        return VARIANTS.any { t.contains(it) }
    }

    /** Strip the wake word (and a leading greeting) to leave the actual command. */
    fun strip(text: String): String {
        var t = text.lowercase()
        for (v in VARIANTS) t = t.replace(v, " ")
        return t
            .replace(Regex("\\b(hey|hi|ok|okay|yo|hello)\\b"), " ")
            .replace(Regex("[^a-z0-9+ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
