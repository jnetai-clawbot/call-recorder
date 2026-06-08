package com.ciphervault.callrecorder

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.ciphervault.callrecorder.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CR_Settings"
        const val ERROR_PREFIX = "CR-SE-"
        const val PREF_FORMAT = "audio_output_format"
        const val PREF_MIC_SOURCE = "audio_mic_source"
        const val PREF_MIC_VOLUME = "audio_mic_volume"
        const val PREF_SPEAKER_VOLUME = "audio_speaker_volume"
        const val PREF_CAPTURE_SPEAKER = "audio_capture_speaker"
        const val PREF_STORAGE_PATH = "storage_path"
        const val PREF_AUTO_START = "auto_start_app"
    }

    enum class ErrorCode(val code: String, val description: String) {
        SE001("SE001", "Settings save failed"),
        SE002("SE002", "Settings load failed"),
        SE999("SE999", "Unknown settings error")
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    private fun reportError(code: ErrorCode, message: String, exception: Exception? = null) {
        val fullMsg = "[$ERROR_PREFIX${code.code}] ${code.description}: $message"
        Log.e(TAG, fullMsg, exception)
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        try {
            prefs = PreferenceManager.getDefaultSharedPreferences(this)
        } catch (e: Exception) {
            reportError(ErrorCode.SE002, "Failed to get shared preferences", e)
            finish()
            return
        }

        setupFormatSpinner()
        setupMicSourceSpinner()
        setupVolumeSliders()
        loadSettings()
        setupButtons()
    }

    private fun setupFormatSpinner() {
        val formats = AudioEngine.OutputFormat.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats)
        binding.spinnerFormat.adapter = adapter
    }

    private fun setupMicSourceSpinner() {
        val sources = AudioEngine.AudioSource.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sources)
        binding.spinnerMicSource.adapter = adapter
    }

    private fun setupVolumeSliders() {
        binding.sliderMicVolume.addOnChangeListener { _, value, _ ->
            binding.tvMicVolumeValue.text = "${(value * 100).toInt()}%"
        }
        binding.sliderSpeakerVolume.addOnChangeListener { _, value, _ ->
            binding.tvSpeakerVolumeValue.text = "${(value * 100).toInt()}%"
        }
    }

    private fun loadSettings() {
        try {
            val formatName = prefs.getString(PREF_FORMAT, AudioEngine.OutputFormat.WAV.name)
                ?: AudioEngine.OutputFormat.WAV.name
            val format = AudioEngine.OutputFormat.fromName(formatName)
            val formatIndex = AudioEngine.OutputFormat.entries.indexOf(format)
            if (formatIndex >= 0) binding.spinnerFormat.setSelection(formatIndex)

            val micSourceName = prefs.getString(PREF_MIC_SOURCE, AudioEngine.AudioSource.VOICE_COMMUNICATION.name)
                ?: AudioEngine.AudioSource.VOICE_COMMUNICATION.name
            val micSource = AudioEngine.AudioSource.fromName(micSourceName)
            val sourceIndex = AudioEngine.AudioSource.entries.indexOf(micSource)
            if (sourceIndex >= 0) binding.spinnerMicSource.setSelection(sourceIndex)

            val micVol = prefs.getFloat(PREF_MIC_VOLUME, 1.0f)
            val spkVol = prefs.getFloat(PREF_SPEAKER_VOLUME, 0.5f)

            binding.sliderMicVolume.value = micVol
            binding.tvMicVolumeValue.text = "${(micVol * 100).toInt()}%"
            binding.sliderSpeakerVolume.value = spkVol
            binding.tvSpeakerVolumeValue.text = "${(spkVol * 100).toInt()}%"

            binding.switchCaptureSpeaker.isChecked =
                prefs.getBoolean(PREF_CAPTURE_SPEAKER, true)
            binding.switchAutoStart.isChecked =
                prefs.getBoolean(PREF_AUTO_START, true)
            binding.etStoragePath.setText(
                prefs.getString(PREF_STORAGE_PATH, "DCIM/Recordings") ?: "DCIM/Recordings"
            )

            val selectedIncluded = getContactsSummary(ContactPickerActivity.PREF_INCLUDED_CONTACTS)
            val selectedExcluded = getContactsSummary(ContactPickerActivity.PREF_EXCLUDED_CONTACTS)
            binding.tvIncludedContacts.text = "Selected: $selectedIncluded"
            binding.tvExcludedContacts.text = "Selected: $selectedExcluded"

            logDebug("Settings loaded: format=$formatName, micSource=$micSourceName, micVol=$micVol, spkVol=$spkVol")
        } catch (e: Exception) {
            reportError(ErrorCode.SE002, "Error loading settings", e)
        }
    }

    private fun getContactsSummary(prefKey: String): String {
        val contactPrefs = getSharedPreferences("contact_selections", MODE_PRIVATE)
        val ids = contactPrefs.getStringSet(prefKey, emptySet()) ?: emptySet()
        return if (ids.isEmpty()) "All contacts (default)" else "${ids.size} contacts"
    }

    private fun setupButtons() {
        binding.btnIncludedContacts.setOnClickListener {
            startActivity(
                Intent(this, ContactPickerActivity::class.java).apply {
                    putExtra(ContactPickerActivity.EXTRA_MODE, ContactPickerActivity.MODE_INCLUDE)
                    putExtra(ContactPickerActivity.EXTRA_PREF_KEY, ContactPickerActivity.PREF_INCLUDED_CONTACTS)
                }
            )
        }

        binding.btnExcludedContacts.setOnClickListener {
            startActivity(
                Intent(this, ContactPickerActivity::class.java).apply {
                    putExtra(ContactPickerActivity.EXTRA_MODE, ContactPickerActivity.MODE_EXCLUDE)
                    putExtra(ContactPickerActivity.EXTRA_PREF_KEY, ContactPickerActivity.PREF_EXCLUDED_CONTACTS)
                }
            )
        }

        binding.btnSave.setOnClickListener { saveAndFinish() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun saveAndFinish() {
        try {
            val selectedFormat = AudioEngine.OutputFormat.entries[binding.spinnerFormat.selectedItemPosition]
            val selectedMicSource = AudioEngine.AudioSource.entries[binding.spinnerMicSource.selectedItemPosition]
            val micVol = binding.sliderMicVolume.value
            val spkVol = binding.sliderSpeakerVolume.value
            val captureSpeaker = binding.switchCaptureSpeaker.isChecked
            val autoStart = binding.switchAutoStart.isChecked
            val storagePath = binding.etStoragePath.text.toString().ifBlank { "DCIM/Recordings" }

            prefs.edit().apply {
                putString(PREF_FORMAT, selectedFormat.name)
                putString(PREF_MIC_SOURCE, selectedMicSource.name)
                putFloat(PREF_MIC_VOLUME, micVol)
                putFloat(PREF_SPEAKER_VOLUME, spkVol)
                putBoolean(PREF_CAPTURE_SPEAKER, captureSpeaker)
                putBoolean(PREF_AUTO_START, autoStart)
                putString(PREF_STORAGE_PATH, storagePath)
                apply()
            }

            logDebug("Settings saved: format=${selectedFormat.displayName}, micSrc=${selectedMicSource.displayName}, " +
                    "micVol=${(micVol*100).toInt()}%, spkVol=${(spkVol*100).toInt()}%, " +
                    "captureSpk=$captureSpeaker, autoStart=$autoStart")

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            reportError(ErrorCode.SE001, "Failed to save settings", e)
            Toast.makeText(this, "Error saving settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val selectedIncluded = getContactsSummary(ContactPickerActivity.PREF_INCLUDED_CONTACTS)
        val selectedExcluded = getContactsSummary(ContactPickerActivity.PREF_EXCLUDED_CONTACTS)
        binding.tvIncludedContacts.text = "Selected: $selectedIncluded"
        binding.tvExcludedContacts.text = "Selected: $selectedExcluded"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
