package com.example.myapplication

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VncConnectionActivity : AppCompatActivity() {
    private lateinit var serverHostInput: TextInputEditText
    private lateinit var serverPortInput: TextInputEditText
    private lateinit var vncPortInput: TextInputEditText
    private lateinit var vncPasswordInput: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnOpenStream: MaterialButton
    private lateinit var btnStopVnc: MaterialButton

    private var latestVncInfo: VncInfo? = null
    private var latestServerHost: String = ""
    private var refreshJob: Job? = null
    private var isRefreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vnc_connection)

        findViewById<MaterialToolbar>(R.id.vncToolbar).setNavigationOnClickListener {
            finish()
        }

        bindViews()
        loadPreferences()
        setupActions()
    }

    override fun onResume() {
        super.onResume()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun bindViews() {
        serverHostInput = findViewById(R.id.vncServerHostInput)
        serverPortInput = findViewById(R.id.vncServerPortInput)
        vncPortInput = findViewById(R.id.vncPortInput)
        vncPasswordInput = findViewById(R.id.vncPasswordInput)
        statusText = findViewById(R.id.vncStatusText)
        btnConnect = findViewById(R.id.btnVncConnect)
        btnOpenStream = findViewById(R.id.btnVncOpenStream)
        btnStopVnc = findViewById(R.id.btnVncStop)
    }

    private fun setupActions() {
        btnConnect.setOnClickListener { performConnectionFlow() }
        btnOpenStream.setOnClickListener { openVncStream() }
        btnStopVnc.setOnClickListener { stopVncServer() }
    }

    private fun loadPreferences() {
        val serverPrefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        serverHostInput.setText(
            serverPrefs.getString(AudioService.KEY_SERVER_IP, AudioService.DEFAULT_SERVER_IP)
        )
        serverPortInput.setText(
            serverPrefs.getInt(AudioService.KEY_SERVER_PORT, AudioService.DEFAULT_SERVER_PORT).toString()
        )

        val vncPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        vncPortInput.setText(vncPrefs.getInt(KEY_VNC_PORT, DEFAULT_VNC_PORT).toString())
        vncPasswordInput.setText(vncPrefs.getString(KEY_VNC_PASSWORD, ""))
    }

    private fun savePreferences() {
        val vncPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        vncPrefs.edit()
            .putInt(KEY_VNC_PORT, vncPortInput.text?.toString()?.toIntOrNull() ?: DEFAULT_VNC_PORT)
            .putString(KEY_VNC_PASSWORD, vncPasswordInput.text?.toString().orEmpty())
            .apply()
    }

    private fun performConnectionFlow() {
        savePreferences()
        updateUiLoading(true)

        val serverConfig = getServerConfig()
        if (serverConfig == null) {
            updateStatus(getString(R.string.vnc_missing_server_info))
            updateUiLoading(false)
            return
        }

        latestServerHost = serverConfig.host
        val apiClient = VncApiClient(serverConfig.baseUrl)

        lifecycleScope.launch {
            val refreshResult = refreshVncStatus(apiClient, allowPrompt = false)
            if (refreshResult != null && !refreshResult.running && refreshResult.enabled) {
                updateUiLoading(false)
                promptStartVnc(apiClient)
            } else {
                updateUiLoading(false)
                updateStreamButtons(refreshResult?.running == true)
            }
        }
    }

    private fun promptStartVnc(apiClient: VncApiClient) {
        AlertDialog.Builder(this)
            .setTitle(R.string.vnc_start_prompt_title)
            .setMessage(R.string.vnc_start_prompt_message)
            .setPositiveButton(R.string.vnc_start_confirm) { _, _ ->
                lifecycleScope.launch {
                    updateUiLoading(true)
                    val startResult = withContext(Dispatchers.IO) { apiClient.startVnc() }
                    if (startResult.error != null) {
                        updateStatus(getString(R.string.vnc_start_failed, startResult.error))
                        updateUiLoading(false)
                        return@launch
                    }
                    val vncInfo = startResult.data?.vnc
                    latestVncInfo = vncInfo
                    updateStatus(formatStatus(vncInfo))
                    updateUiLoading(false)
                    updateStreamButtons(vncInfo?.running == true)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                updateStatus(getString(R.string.vnc_start_cancelled))
                updateStreamButtons(false)
            }
            .show()
    }

    private fun stopVncServer() {
        val serverConfig = getServerConfig()
        if (serverConfig == null) {
            updateStatus(getString(R.string.vnc_missing_server_info))
            return
        }
        val apiClient = VncApiClient(serverConfig.baseUrl)
        lifecycleScope.launch {
            updateUiLoading(true)
            val stopResult = withContext(Dispatchers.IO) { apiClient.stopVnc() }
            if (stopResult.error != null) {
                updateStatus(getString(R.string.vnc_stop_failed, stopResult.error))
                updateUiLoading(false)
                return@launch
            }
            latestVncInfo = stopResult.data?.vnc
            updateStatus(getString(R.string.vnc_stopped))
            updateUiLoading(false)
            updateStreamButtons(false)
        }
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (true) {
                if (!isRefreshing) {
                    val serverConfig = getServerConfig()
                    if (serverConfig != null) {
                        latestServerHost = serverConfig.host
                        val apiClient = VncApiClient(serverConfig.baseUrl)
                        updateStatus(getString(R.string.vnc_status_refreshing))
                        refreshVncStatus(apiClient, allowPrompt = false)
                    }
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshVncStatus(
        apiClient: VncApiClient,
        allowPrompt: Boolean = true
    ): VncInfo? {
        isRefreshing = true
        try {
            val healthResult = withContext(Dispatchers.IO) { apiClient.fetchHealth() }
            if (healthResult.error != null) {
                updateStatus(getString(R.string.vnc_health_failed, healthResult.error))
                return null
            }
            val statusResult = withContext(Dispatchers.IO) { apiClient.fetchStatus() }
            if (statusResult.error != null) {
                updateStatus(getString(R.string.vnc_status_failed, statusResult.error))
                return null
            }
            val vncInfo = statusResult.data?.vnc
            latestVncInfo = vncInfo
            if (vncInfo == null) {
                updateStatus(getString(R.string.vnc_status_unavailable))
                return null
            }
            updateStatus(formatStatus(vncInfo))
            if (!vncInfo.running && vncInfo.enabled && allowPrompt) {
                promptStartVnc(apiClient)
            }
            updateStreamButtons(vncInfo.running)
            return vncInfo
        } finally {
            isRefreshing = false
        }
    }

    private fun getServerConfig(): ServerConfig? {
        val serverHost = serverHostInput.text?.toString()?.trim().orEmpty()
        val serverPort = serverPortInput.text?.toString()?.trim().orEmpty()
        if (serverHost.isBlank() || serverPort.isBlank()) {
            return null
        }
        return ServerConfig(
            host = serverHost,
            baseUrl = "https://$serverHost:$serverPort"
        )
    }

    private fun openVncStream() {
        val vncInfo = latestVncInfo
        val vncHost = vncInfo?.host?.takeIf { it.isNotBlank() } ?: latestServerHost
        val vncPort = vncInfo?.port ?: vncPortInput.text?.toString()?.toIntOrNull() ?: DEFAULT_VNC_PORT
        val password = vncPasswordInput.text?.toString().orEmpty()

        val uri = if (password.isNotBlank()) {
            Uri.parse("vnc://$password@$vncHost:$vncPort")
        } else {
            Uri.parse("vnc://$vncHost:$vncPort")
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.vnc_client_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUiLoading(loading: Boolean) {
        btnConnect.isEnabled = !loading
        btnStopVnc.isEnabled = !loading
        btnOpenStream.isEnabled = !loading && (latestVncInfo?.running == true)
        statusText.visibility = View.VISIBLE
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    private fun formatStatus(vncInfo: VncInfo?): String {
        if (vncInfo == null) {
            return getString(R.string.vnc_status_unavailable)
        }
        val runningText = if (vncInfo.running) getString(R.string.vnc_status_running) else getString(R.string.vnc_status_stopped)
        return getString(
            R.string.vnc_status_summary,
            runningText,
            vncInfo.host ?: latestServerHost,
            vncInfo.port ?: DEFAULT_VNC_PORT,
            vncInfo.display ?: "--"
        )
    }

    private fun updateStreamButtons(running: Boolean) {
        btnOpenStream.isEnabled = running
        btnStopVnc.isEnabled = running
    }

    private data class ServerConfig(
        val host: String,
        val baseUrl: String
    )

    companion object {
        private const val PREFS_NAME = "VncPreferences"
        private const val KEY_VNC_PORT = "vnc_port"
        private const val KEY_VNC_PASSWORD = "vnc_password"
        private const val DEFAULT_VNC_PORT = 5901
        private const val REFRESH_INTERVAL_MS = 10_000L
    }
}
