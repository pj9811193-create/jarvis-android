package ai.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * ============================  VOICE ENGINE  ============================
 * Speech -> text (SpeechRecognizer) and text -> speech (TextToSpeech).
 * Speed, voice and recognition language are driven from Settings via
 * [applySettings]; the engine itself holds no settings of its own.
 * ======================================================================
 */
class VoiceEngine(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context) else null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var langTag: String = Locale.getDefault().toLanguageTag()

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                onError("I didn't catch that (speech error $error).")
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) onResult(text) else onError("I didn't catch that.")
            }
        })

        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.forLanguageTag(langTag)
        }
    }

    fun isTtsReady(): Boolean = ttsReady

    /** All voices the TTS engine offers, for the Settings picker. */
    fun availableVoices(): List<Pair<String, String>> =
        tts?.voices
            ?.map { it.name to "${it.name} (${it.locale.displayName})" }
            ?.sortedBy { it.second }
            ?: emptyList()

    /** Push the current Settings values into the engines. */
    fun applySettings(speed: Float, voiceName: String, recognitionLang: String) {
        if (recognitionLang.isNotBlank()) langTag = recognitionLang
        tts?.let { engine ->
            engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
            if (voiceName.isNotBlank()) {
                engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
            }
            runCatching { engine.language = Locale.forLanguageTag(langTag) }
        }
    }

    fun listen() {
        if (recognizer == null) {
            onError("Speech recognition isn't available on this device.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    fun shutdown() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}
