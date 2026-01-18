package com.example.myapplication

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.myapplication.AudioService.Companion.KEY_SERVER_IP
import com.example.myapplication.AudioService.Companion.KEY_SERVER_PORT
import com.example.myapplication.AudioService.Companion.KEY_TTS_LANGUAGE
import com.example.myapplication.AudioService.Companion.KEY_TTS_PITCH
import com.example.myapplication.AudioService.Companion.KEY_TTS_RATE
import com.example.myapplication.AudioService.Companion.KEY_WHISPER_MODEL
import com.example.myapplication.AudioService.Companion.KEY_RECORDING_START_TONE
import com.example.myapplication.AudioService.Companion.KEY_RECORDING_STOP_TONE
import com.example.myapplication.AudioService.Companion.DEFAULT_RECORDING_START_TONE
import com.example.myapplication.AudioService.Companion.DEFAULT_RECORDING_STOP_TONE
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {
    private lateinit var btnToggleTheme: MaterialButton
    private lateinit var serverIpInput: TextInputEditText
    private lateinit var serverPortInput: TextInputEditText
    private lateinit var unlockPasswordInput: TextInputEditText
    private lateinit var responseTimeoutInput: TextInputEditText
    private lateinit var vncPortInput: TextInputEditText
    private lateinit var vncPasswordInput: TextInputEditText
    private lateinit var ttsLanguageInput: TextInputEditText
    private lateinit var ttsRateInput: TextInputEditText
    private lateinit var ttsPitchInput: TextInputEditText
    private lateinit var btnTtsSystemSettings: MaterialButton
    private lateinit var audioPlaybackSwitch: SwitchMaterial
    private lateinit var headsetFeedbackSwitch: SwitchMaterial
    private lateinit var btnTestConnection: MaterialButton
    private lateinit var btnSaveSettings: MaterialButton
    private lateinit var connectionStatusText: TextView
    private lateinit var whisperModelDropdown: AutoCompleteTextView
    private lateinit var recordingStartToneDropdown: AutoCompleteTextView
    private lateinit var recordingStopToneDropdown: AutoCompleteTextView

    private var isDarkTheme = false
    private var connectionTestTimeoutHandler: Handler? = null

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioService.ACTION_CONNECTION_TESTED) {
                val success = intent.getBooleanExtra(AudioService.EXTRA_CONNECTION_SUCCESS, false)
                val message = intent.getStringExtra(AudioService.EXTRA_CONNECTION_MESSAGE)
                    ?: getString(R.string.unknown_error)

                Log.d("SettingsActivity", getString(R.string.connection_test_received, success, message))
                updateConnectionStatus(success, message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyThemeFromPreferences()
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        initViews()
        loadThemePreference()
        loadServerSettings()
        setupActions()
        setupGlassEffect()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AudioService.ACTION_CONNECTION_TESTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {
            // Receiver might already be unregistered
        }
        connectionTestTimeoutHandler?.removeCallbacksAndMessages(null)
    }

    private fun initViews() {
        btnToggleTheme = findViewById(R.id.btnToggleTheme)
        serverIpInput = findViewById(R.id.serverIpInput)
        serverPortInput = findViewById(R.id.serverPortInput)
        unlockPasswordInput = findViewById(R.id.unlockPasswordInput)
        responseTimeoutInput = findViewById(R.id.responseTimeoutInput)
        vncPortInput = findViewById(R.id.vncPortInput)
        vncPasswordInput = findViewById(R.id.vncPasswordInput)
        ttsLanguageInput = findViewById(R.id.ttsLanguageInput)
        ttsRateInput = findViewById(R.id.ttsRateInput)
        ttsPitchInput = findViewById(R.id.ttsPitchInput)
        btnTtsSystemSettings = findViewById(R.id.btnTtsSystemSettings)
        audioPlaybackSwitch = findViewById(R.id.audioPlaybackSwitch)
        headsetFeedbackSwitch = findViewById(R.id.headsetFeedbackSwitch)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        whisperModelDropdown = findViewById(R.id.whisperModelDropdown)
        recordingStartToneDropdown = findViewById(R.id.recordingStartToneDropdown)
        recordingStopToneDropdown = findViewById(R.id.recordingStopToneDropdown)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.whisper_model_options)
        )
        whisperModelDropdown.setAdapter(adapter)
        
        // Tone dropdowns
        val toneAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.tone_options)
        )
        recordingStartToneDropdown.setAdapter(toneAdapter)
        recordingStopToneDropdown.setAdapter(toneAdapter)
    }

    private fun setupActions() {
        btnToggleTheme.setOnClickListener {
            toggleTheme()
        }

        findViewById<MaterialButton>(R.id.btnServerSetup).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/pnmartinez/simple-computer-use"))
            startActivity(intent)
        }

        btnSaveSettings.setOnClickListener {
            saveServerSettings()
        }

        btnTestConnection.setOnClickListener {
            testServerConnection()
        }

        btnTtsSystemSettings.setOnClickListener {
            openTtsSystemSettings()
        }

        audioPlaybackSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateAudioPlaybackSwitchText(isChecked)
        }

        headsetFeedbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateHeadsetFeedbackSwitchText(isChecked)
        }
        
        // Preview tone when selecting from dropdown
        recordingStartToneDropdown.setOnItemClickListener { _, _, _, _ ->
            val selectedTone = getToneValue(recordingStartToneDropdown.text.toString())
            playTonePreview(selectedTone)
        }
        
        recordingStopToneDropdown.setOnItemClickListener { _, _, _, _ ->
            val selectedTone = getToneValue(recordingStopToneDropdown.text.toString())
            playTonePreview(selectedTone)
        }
    }

    private fun loadThemePreference() {
        updateThemeButtonText()
    }

    private fun applyThemeFromPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isDarkTheme = prefs.getBoolean(KEY_IS_DARK_THEME, false)
        updateTheme()
    }

    private fun saveThemePreference() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_DARK_THEME, isDarkTheme).apply()
    }

    private fun updateTheme() {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (isDarkTheme)
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun updateThemeButtonText() {
        btnToggleTheme.text = if (isDarkTheme) {
            getString(R.string.toggle_theme_light)
        } else {
            getString(R.string.toggle_theme_dark)
        }
        btnToggleTheme.setIconResource(
            if (isDarkTheme) android.R.drawable.ic_menu_day
            else android.R.drawable.ic_dialog_dialer
        )
    }

    private fun toggleTheme() {
        isDarkTheme = !isDarkTheme
        saveThemePreference()
        updateThemeButtonText()
        updateTheme()
    }

    private fun loadServerSettings() {
        val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        val serverIp = prefs.getString(KEY_SERVER_IP, AudioService.DEFAULT_SERVER_IP)
            ?: AudioService.DEFAULT_SERVER_IP
        val serverPort = prefs.getInt(KEY_SERVER_PORT, AudioService.DEFAULT_SERVER_PORT)
        val whisperModel = prefs.getString(KEY_WHISPER_MODEL, AudioService.DEFAULT_WHISPER_MODEL)
            ?: AudioService.DEFAULT_WHISPER_MODEL
        val unlockPassword = prefs.getString(KEY_UNLOCK_PASSWORD, "your_password") ?: "your_password"
        val responseTimeout = prefs.getInt(KEY_RESPONSE_TIMEOUT, AudioService.DEFAULT_RESPONSE_TIMEOUT)
        val vncPort = prefs.getInt(VncPreferences.KEY_VNC_PORT, VncPreferences.DEFAULT_VNC_PORT)
        val vncPassword = prefs.getString(VncPreferences.KEY_VNC_PASSWORD, "")
        val ttsLanguage = prefs.getString(KEY_TTS_LANGUAGE, AudioService.DEFAULT_TTS_LANGUAGE)
            ?: AudioService.DEFAULT_TTS_LANGUAGE
        val ttsRate = prefs.getFloat(KEY_TTS_RATE, AudioService.DEFAULT_TTS_RATE)
        val ttsPitch = prefs.getFloat(KEY_TTS_PITCH, AudioService.DEFAULT_TTS_PITCH)
        val audioPlaybackEnabled = prefs.getBoolean(
            AudioService.KEY_AUDIO_PLAYBACK_ENABLED,
            AudioService.DEFAULT_AUDIO_PLAYBACK_ENABLED
        )
        val headsetFeedbackEnabled = prefs.getBoolean(
            AudioService.KEY_HEADSET_FEEDBACK_ENABLED,
            AudioService.DEFAULT_HEADSET_FEEDBACK_ENABLED
        )
        val recordingStartTone = prefs.getString(KEY_RECORDING_START_TONE, DEFAULT_RECORDING_START_TONE)
            ?: DEFAULT_RECORDING_START_TONE
        val recordingStopTone = prefs.getString(KEY_RECORDING_STOP_TONE, DEFAULT_RECORDING_STOP_TONE)
            ?: DEFAULT_RECORDING_STOP_TONE

        serverIpInput.setText(serverIp)
        serverPortInput.setText(serverPort.toString())
        whisperModelDropdown.setText(whisperModel, false)
        unlockPasswordInput.setText(unlockPassword)
        responseTimeoutInput.setText((responseTimeout / 1000).toString())
        vncPortInput.setText(vncPort.toString())
        vncPasswordInput.setText(vncPassword)
        ttsLanguageInput.setText(ttsLanguage)
        ttsRateInput.setText(ttsRate.toString())
        ttsPitchInput.setText(ttsPitch.toString())
        audioPlaybackSwitch.isChecked = audioPlaybackEnabled
        updateAudioPlaybackSwitchText(audioPlaybackEnabled)
        headsetFeedbackSwitch.isChecked = headsetFeedbackEnabled
        updateHeadsetFeedbackSwitchText(headsetFeedbackEnabled)
        
        // Load tone settings
        recordingStartToneDropdown.setText(getToneDisplayName(recordingStartTone), false)
        recordingStopToneDropdown.setText(getToneDisplayName(recordingStopTone), false)
    }
    
    private fun getToneDisplayName(toneValue: String): String {
        val toneValues = resources.getStringArray(R.array.tone_values)
        val toneOptions = resources.getStringArray(R.array.tone_options)
        val index = toneValues.indexOf(toneValue)
        return if (index >= 0) toneOptions[index] else toneOptions[0]
    }
    
    private fun getToneValue(displayName: String): String {
        val toneValues = resources.getStringArray(R.array.tone_values)
        val toneOptions = resources.getStringArray(R.array.tone_options)
        val index = toneOptions.indexOf(displayName)
        return if (index >= 0) toneValues[index] else toneValues[0]
    }
    
    /**
     * Reproduce una previsualización del tono seleccionado.
     */
    private fun playTonePreview(toneName: String) {
        try {
            val toneType = getToneGeneratorValue(toneName)
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(toneType, 300)
            
            // Liberar después de reproducir
            Handler(mainLooper).postDelayed({
                try {
                    toneGen.release()
                } catch (e: Exception) {
                    Log.e("SettingsActivity", "Error releasing ToneGenerator: ${e.message}")
                }
            }, 400)
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error playing tone preview: ${e.message}")
        }
    }
    
    /**
     * Convierte el nombre del tono a su valor ToneGenerator correspondiente.
     */
    private fun getToneGeneratorValue(toneName: String): Int {
        return when (toneName) {
            "TONE_PROP_BEEP" -> ToneGenerator.TONE_PROP_BEEP
            "TONE_PROP_BEEP2" -> ToneGenerator.TONE_PROP_BEEP2
            "TONE_PROP_ACK" -> ToneGenerator.TONE_PROP_ACK
            "TONE_CDMA_ALERT_INCALL_LITE" -> ToneGenerator.TONE_CDMA_ALERT_INCALL_LITE
            "TONE_CDMA_CALLDROP_LITE" -> ToneGenerator.TONE_CDMA_CALLDROP_LITE
            "TONE_DTMF_0" -> ToneGenerator.TONE_DTMF_0
            "TONE_DTMF_1" -> ToneGenerator.TONE_DTMF_1
            "TONE_DTMF_5" -> ToneGenerator.TONE_DTMF_5
            "TONE_DTMF_9" -> ToneGenerator.TONE_DTMF_9
            "TONE_SUP_RINGTONE" -> ToneGenerator.TONE_SUP_RINGTONE
            "TONE_PROP_PROMPT" -> ToneGenerator.TONE_PROP_PROMPT
            "TONE_SUP_ERROR" -> ToneGenerator.TONE_SUP_ERROR
            else -> ToneGenerator.TONE_PROP_BEEP
        }
    }

    private fun saveServerSettings() {
        val ip = serverIpInput.text.toString().trim()
        val portText = serverPortInput.text.toString().trim()
        val whisperModel = whisperModelDropdown.text.toString().trim()
        val unlockPassword = unlockPasswordInput.text.toString()
        val timeoutText = responseTimeoutInput.text.toString().trim()
        val vncPortText = vncPortInput.text.toString().trim()
        val vncPassword = vncPasswordInput.text.toString()
        val ttsLanguage = ttsLanguageInput.text.toString().trim()
        val ttsRateText = ttsRateInput.text.toString().trim()
        val ttsPitchText = ttsPitchInput.text.toString().trim()
        val audioPlaybackEnabled = audioPlaybackSwitch.isChecked
        val headsetFeedbackEnabled = headsetFeedbackSwitch.isChecked
        val recordingStartTone = getToneValue(recordingStartToneDropdown.text.toString())
        val recordingStopTone = getToneValue(recordingStopToneDropdown.text.toString())

        if (ip.isEmpty()) {
            Toast.makeText(this, getString(R.string.empty_server_ip_error), Toast.LENGTH_SHORT).show()
            return
        }

        if (portText.isEmpty()) {
            Toast.makeText(this, getString(R.string.empty_server_port_error), Toast.LENGTH_SHORT).show()
            return
        }

        val port = try {
            portText.toInt()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, getString(R.string.invalid_port_error), Toast.LENGTH_SHORT).show()
            return
        }

        if (whisperModel.isEmpty()) {
            Toast.makeText(this, getString(R.string.empty_whisper_model_error), Toast.LENGTH_SHORT).show()
            return
        }

        val timeout = try {
            val timeoutSeconds = timeoutText.toInt()
            if (timeoutSeconds < 5 || timeoutSeconds > 120) {
                Toast.makeText(this, getString(R.string.invalid_timeout_error), Toast.LENGTH_SHORT).show()
                return
            }
            timeoutSeconds * 1000
        } catch (e: NumberFormatException) {
            Toast.makeText(this, getString(R.string.invalid_timeout_error), Toast.LENGTH_SHORT).show()
            return
        }

        val vncPort = try {
            vncPortText.toInt()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, getString(R.string.invalid_vnc_port_error), Toast.LENGTH_SHORT).show()
            return
        }

        val rate = try {
            ttsRateText.toFloat().also {
                if (it < 0.1f || it > 2.0f) {
                    Toast.makeText(this, getString(R.string.invalid_tts_rate_error), Toast.LENGTH_SHORT).show()
                    return
                }
            }
        } catch (e: NumberFormatException) {
            Toast.makeText(this, getString(R.string.invalid_tts_rate_error), Toast.LENGTH_SHORT).show()
            return
        }

        val pitch = try {
            ttsPitchText.toFloat().also {
                if (it < 0.1f || it > 2.0f) {
                    Toast.makeText(this, getString(R.string.invalid_tts_pitch_error), Toast.LENGTH_SHORT).show()
                    return
                }
            }
        } catch (e: NumberFormatException) {
            Toast.makeText(this, getString(R.string.invalid_tts_pitch_error), Toast.LENGTH_SHORT).show()
            return
        }

        if (ttsLanguage.isEmpty()) {
            Toast.makeText(this, getString(R.string.invalid_tts_language_error), Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_SERVER_IP, ip)
            putInt(KEY_SERVER_PORT, port)
            putString(KEY_WHISPER_MODEL, whisperModel)
            putString(KEY_UNLOCK_PASSWORD, unlockPassword)
            putInt(KEY_RESPONSE_TIMEOUT, timeout)
            putInt(VncPreferences.KEY_VNC_PORT, vncPort)
            putString(VncPreferences.KEY_VNC_PASSWORD, vncPassword)
            putString(KEY_TTS_LANGUAGE, ttsLanguage)
            putFloat(KEY_TTS_RATE, rate)
            putFloat(KEY_TTS_PITCH, pitch)
            putBoolean(AudioService.KEY_AUDIO_PLAYBACK_ENABLED, audioPlaybackEnabled)
            putBoolean(AudioService.KEY_HEADSET_FEEDBACK_ENABLED, headsetFeedbackEnabled)
            putString(KEY_RECORDING_START_TONE, recordingStartTone)
            putString(KEY_RECORDING_STOP_TONE, recordingStopTone)
            commit()
        }

        val intent = Intent(this, AudioService::class.java).apply {
            action = "UPDATE_SETTINGS"
            putExtra(KEY_SERVER_IP, ip)
            putExtra(KEY_SERVER_PORT, port as Int)
            putExtra(KEY_WHISPER_MODEL, whisperModel)
            putExtra(KEY_RESPONSE_TIMEOUT, timeout)
            putExtra(KEY_TTS_LANGUAGE, ttsLanguage)
            putExtra(KEY_TTS_RATE, rate)
            putExtra(KEY_TTS_PITCH, pitch)
            putExtra(AudioService.KEY_AUDIO_PLAYBACK_ENABLED, audioPlaybackEnabled)
            putExtra(AudioService.KEY_HEADSET_FEEDBACK_ENABLED, headsetFeedbackEnabled)
            putExtra(KEY_RECORDING_START_TONE, recordingStartTone)
            putExtra(KEY_RECORDING_STOP_TONE, recordingStopTone)
        }
        startService(intent)

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun testServerConnection() {
        try {
            saveServerSettings()

            connectionStatusText.text = getString(R.string.checking_connection)
            connectionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))

            val ipPort = "${serverIpInput.text}:${serverPortInput.text}"
            Log.d("SettingsActivity", "Iniciando prueba de conexión a $ipPort")

            connectionTestTimeoutHandler?.removeCallbacksAndMessages(null)
            connectionTestTimeoutHandler = Handler(mainLooper)
            connectionTestTimeoutHandler?.postDelayed({
                if (connectionStatusText.text == getString(R.string.checking_connection)) {
                    connectionStatusText.text = getString(R.string.connection_timeout)
                    connectionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                }
            }, 10000)

            val intent = Intent(this, AudioService::class.java).apply {
                action = "TEST_CONNECTION"
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error al iniciar prueba de conexión", e)
            connectionStatusText.text = getString(R.string.error_generic, e.message)
            connectionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        }
    }

    private fun updateConnectionStatus(status: Boolean, message: String) {
        runOnUiThread {
            connectionStatusText.text = message
            connectionStatusText.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (!status) R.color.error else R.color.success
                )
            )
        }
    }

    private fun updateAudioPlaybackSwitchText(enabled: Boolean) {
        audioPlaybackSwitch.text = getString(
            if (enabled) R.string.audio_playback_enabled else R.string.audio_playback_disabled
        )
    }

    private fun updateHeadsetFeedbackSwitchText(enabled: Boolean) {
        headsetFeedbackSwitch.text = getString(
            if (enabled) R.string.headset_feedback_enabled_on else R.string.headset_feedback_enabled_off
        )
    }

    private fun openTtsSystemSettings() {
        try {
            startActivity(Intent(TTS_SETTINGS_ACTION))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (fallbackException: ActivityNotFoundException) {
                Log.e("SettingsActivity", "TTS settings unavailable", fallbackException)
                Toast.makeText(this, getString(R.string.tts_settings_unavailable), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }
    
    /**
     * Aplica efecto glassmorphism al footer.
     * El efecto se logra con un fondo semitransparente definido en XML.
     */
    private fun setupGlassEffect() {
        // El efecto glass se logra con:
        // 1. Fondo semitransparente (definido en colors.xml: glass_footer_bg)
        // 2. Elevación que crea sombra sutil
        // El blur real requiere librerías externas o API específicas de Window
    }

    companion object {
        private const val PREFS_NAME = "AppPreferences"
        private const val KEY_IS_DARK_THEME = "isDarkTheme"
        private const val KEY_UNLOCK_PASSWORD = "unlockPassword"
        private const val KEY_RESPONSE_TIMEOUT = AudioService.KEY_RESPONSE_TIMEOUT
        private const val TTS_SETTINGS_ACTION = "android.speech.tts.engine.TTS_SETTINGS"
    }
}
