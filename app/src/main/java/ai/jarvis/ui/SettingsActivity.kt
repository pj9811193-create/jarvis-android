package ai.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ai.jarvis.R
import ai.jarvis.apps.AppResolver
import ai.jarvis.memory.Memory
import java.util.Locale

/**
 * ============================  SETTINGS  ============================
 * AI Provider · Voice · App Resolver · Permissions · About
 *
 * The API key is written through [Memory] into Keystore-encrypted storage —
 * never plaintext SharedPreferences.
 * ===================================================================
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var memory: Memory
    private var tts: TextToSpeech? = null

    private val providers = listOf(
        Triple("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        Triple("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
        Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Triple("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        Triple("Together", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
        Triple("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest"),
        Triple("Custom", "", "")
    )

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateMicStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        memory = Memory(this)

        bindAiSection()
        bindVoiceSection()
        bindResolverSection()
        bindPermissionsSection()
        findViewById<TextView>(R.id.text_about).text =
            getString(R.string.about_body) + "\n\nVersion " + appVersion()
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }

    // ---------------- AI Provider ----------------

    private fun bindAiSection() {
        val spinner = findViewById<Spinner>(R.id.spinner_provider)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.first }
        )
        val current = memory.provider()
        providers.indexOfFirst { it.first == current }.takeIf { it >= 0 }?.let { spinner.setSelection(it) }

        val editKey = findViewById<EditText>(R.id.edit_key)
        val editModel = findViewById<EditText>(R.id.edit_model)
        val editBase = findViewById<EditText>(R.id.edit_base)
        val status = findViewById<TextView>(R.id.text_ai_status)

        editKey.setText(memory.apiKey())
        editModel.setText(memory.model().ifBlank { providers[0].third })
        editBase.setText(memory.baseUrl().ifBlank { providers[0].second })
        status.text = if (memory.apiKey().isBlank())
            getString(R.string.ai_status_none) else getString(R.string.ai_status_set)

        spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val p = providers[pos]
                if (p.first != "Custom") {
                    editBase.setText(p.second)
                    editModel.setText(p.third)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        findViewById<Button>(R.id.btn_save_ai).setOnClickListener {
            val provider = providers[spinner.selectedItemPosition].first
            memory.setAi(
                editKey.text.toString().trim(),
                editBase.text.toString().trim(),
                editModel.text.toString().trim(),
                provider
            )
            status.text = if (memory.apiKey().isBlank())
                getString(R.string.ai_status_none) else getString(R.string.ai_status_set)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Voice ----------------

    private fun bindVoiceSection() {
        val seekSpeed = findViewById<SeekBar>(R.id.seek_speed)
        val textSpeed = findViewById<TextView>(R.id.text_speed)
        val spinnerVoice = findViewById<Spinner>(R.id.spinner_voice)
        val editLang = findViewById<EditText>(R.id.edit_lang)

        val progress = ((memory.ttsSpeed() - 0.5f) / 0.1f).toInt().coerceIn(0, 15)
        seekSpeed.progress = progress
        fun renderSpeed() {
            textSpeed.text = String.format(Locale.US, "%.1f×", 0.5f + seekSpeed.progress * 0.1f)
        }
        renderSpeed()
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) = renderSpeed()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                memory.setTtsSpeed(0.5f + sb!!.progress * 0.1f)
            }
        })

        editLang.setText(memory.recognitionLang().ifBlank { Locale.getDefault().toLanguageTag() })

        // populate voices once the TTS engine is ready
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val labels = mutableListOf(getString(R.string.voice_system_default))
                val names = mutableListOf("")
                tts?.voices?.sortedBy { it.locale.displayName }?.forEach { v ->
                    names.add(v.name); labels.add("${v.name} (${v.locale.displayName})")
                }
                spinnerVoice.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item, labels
                )
                val saved = memory.voiceName()
                spinnerVoice.setSelection(names.indexOf(saved).takeIf { it >= 0 } ?: 0)
                spinnerVoice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                        memory.setVoiceName(names.getOrElse(pos) { "" })
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }
        }

        findViewById<Button>(R.id.btn_test_voice).setOnClickListener {
            memory.setTtsSpeed(0.5f + seekSpeed.progress * 0.1f)
            memory.setRecognitionLang(editLang.text.toString().trim())
            tts?.setSpeechRate(memory.ttsSpeed())
            val chosen = memory.voiceName()
            if (chosen.isNotBlank()) {
                tts?.voices?.firstOrNull { it.name == chosen }?.let { tts?.voice = it }
            }
            tts?.speak(getString(R.string.voice_sample), TextToSpeech.QUEUE_FLUSH, null, "test")
        }
    }

    // ---------------- App Resolver ----------------

    private fun bindResolverSection() {
        val textIndex = findViewById<TextView>(R.id.text_index)
        val seekThreshold = findViewById<SeekBar>(R.id.seek_threshold)
        val textThreshold = findViewById<TextView>(R.id.text_threshold)

        fun showIndex() {
            textIndex.text = getString(R.string.index_count, AppResolver(this).inventory().size)
        }
        showIndex()

        findViewById<Button>(R.id.btn_refresh_index).setOnClickListener {
            AppResolver(this).refresh()
            showIndex()
            Toast.makeText(this, R.string.rescanned, Toast.LENGTH_SHORT).show()
        }

        seekThreshold.progress = memory.resolverThreshold().toInt().coerceIn(0, 60)
        fun renderThreshold() {
            textThreshold.text = getString(R.string.threshold_value, seekThreshold.progress)
        }
        renderThreshold()
        seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) = renderThreshold()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                memory.setResolverThreshold(sb!!.progress.toDouble())
            }
        })

        renderAliases()

        findViewById<Button>(R.id.btn_clear_aliases).setOnClickListener {
            memory.clearAliases()
            renderAliases()
            Toast.makeText(this, R.string.aliases_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderAliases() {
        val container = findViewById<LinearLayout>(R.id.container_aliases)
        val empty = findViewById<TextView>(R.id.text_aliases_empty)
        container.removeAllViews()

        val aliases = memory.aliases()
        empty.visibility = if (aliases.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        for ((phrase, pkg) in aliases) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            row.addView(TextView(this).apply {
                text = "$phrase  →  $pkg"
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.jarvis_text))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "×"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    memory.forget(phrase)
                    renderAliases()
                }
            })
            container.addView(row)
        }
    }

    // ---------------- Permissions ----------------

    private fun bindPermissionsSection() {
        updateMicStatus()
        findViewById<Button>(R.id.btn_grant_mic).setOnClickListener {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun updateMicStatus() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.text_mic_status).text =
            getString(if (granted) R.string.mic_granted else R.string.mic_denied)
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
