package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {
    private lateinit var btnToggleTheme: MaterialButton
    private lateinit var serverIpInput: TextInputEditText
    private lateinit var serverPortInput: TextInputEditText
    private lateinit var unlockPasswordInput: TextInputEditText
    private lateinit var responseTimeoutInput: TextInputEditText
    private lateinit var ttsLanguageInput: TextInputEditText
    private lateinit var ttsRateInput: TextInputEditText
    private lateinit var ttsPitchInput: TextInputEditText
    private lateinit var btnTestConnection: MaterialButton
    private lateinit var btnSaveSettings: MaterialButton
    private lateinit var connectionStatusText: TextView
    private lateinit var whisperModelDropdown: AutoCompleteTextView

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
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        initViews()
        loadThemePreference()
        loadServerSettings()
        setupActions()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            serviceReceiver,
            IntentFilter(AudioService.ACTION_CONNECTION_TESTED),
            Context.RECEIVER_NOT_EXPORTED
        )
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
        ttsLanguageInput = findViewById(R.id.ttsLanguageInput)
        ttsRateInput = findViewById(R.id.ttsRateInput)
        ttsPitchInput = findViewById(R.id.ttsPitchInput)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        whisperModelDropdown = findViewById(R.id.whisperModelDropdown)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.whisper_model_options)
        )
        whisperModelDropdown.setAdapter(adapter)
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
    }

    private fun loadThemePreference() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isDarkTheme = prefs.getBoolean(KEY_IS_DARK_THEME, false)
        updateThemeButtonText()
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
        val ttsLanguage = prefs.getString(KEY_TTS_LANGUAGE, AudioService.DEFAULT_TTS_LANGUAGE)
            ?: AudioService.DEFAULT_TTS_LANGUAGE
        val ttsRate = prefs.getFloat(KEY_TTS_RATE, AudioService.DEFAULT_TTS_RATE)
        val ttsPitch = prefs.getFloat(KEY_TTS_PITCH, AudioService.DEFAULT_TTS_PITCH)

        serverIpInput.setText(serverIp)
        serverPortInput.setText(serverPort.toString())
        whisperModelDropdown.setText(whisperModel, false)
        unlockPasswordInput.setText(unlockPassword)
        responseTimeoutInput.setText((responseTimeout / 1000).toString())
        ttsLanguageInput.setText(ttsLanguage)
        ttsRateInput.setText(ttsRate.toString())
        ttsPitchInput.setText(ttsPitch.toString())
    }

    private fun saveServerSettings() {
        val ip = serverIpInput.text.toString().trim()
        val portText = serverPortInput.text.toString().trim()
        val whisperModel = whisperModelDropdown.text.toString().trim()
        val unlockPassword = unlockPasswordInput.text.toString()
        val timeoutText = responseTimeoutInput.text.toString().trim()
        val ttsLanguage = ttsLanguageInput.text.toString().trim()
        val ttsRateText = ttsRateInput.text.toString().trim()
        val ttsPitchText = ttsPitchInput.text.toString().trim()

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
            putString(KEY_TTS_LANGUAGE, ttsLanguage)
            putFloat(KEY_TTS_RATE, rate)
            putFloat(KEY_TTS_PITCH, pitch)
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

    companion object {
        private const val PREFS_NAME = "AppPreferences"
        private const val KEY_IS_DARK_THEME = "isDarkTheme"
        private const val KEY_UNLOCK_PASSWORD = "unlockPassword"
        private const val KEY_RESPONSE_TIMEOUT = AudioService.KEY_RESPONSE_TIMEOUT
    }
}
