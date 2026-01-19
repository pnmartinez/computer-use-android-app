package com.example.myapplication

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Manages long polling connections for server updates.
 * Extracted from MainActivity to reduce complexity.
 */
class PollingManager(
    private val context: Context,
    private val onStatusChanged: (PollingStatus) -> Unit,
    private val onDebugMessage: (String) -> Unit
) {
    private var longPollingService: LongPollingService? = null
    private var pollingScope: CoroutineScope? = null

    var isEnabled: Boolean = true
        private set

    var isConnected: Boolean = false
        private set

    /**
     * Starts long polling with the given scope
     */
    fun start(scope: CoroutineScope) {
        if (!isEnabled) {
            Log.d(TAG, "Long polling disabled by user")
            onStatusChanged(PollingStatus.Disabled)
            return
        }

        pollingScope = scope
        val prefs = context.getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        val serverIp = prefs.getString(AudioService.KEY_SERVER_IP, AudioService.DEFAULT_SERVER_IP)
            ?: AudioService.DEFAULT_SERVER_IP
        val serverPort = prefs.getInt(AudioService.KEY_SERVER_PORT, AudioService.DEFAULT_SERVER_PORT)

        // Don't start if server is not configured
        if (serverIp == AudioService.DEFAULT_SERVER_IP || serverIp.isBlank()) {
            Log.d(TAG, "Long polling not started: server not configured")
            onStatusChanged(PollingStatus.NotConfigured)
            return
        }

        val serverUrl = "https://$serverIp:$serverPort"

        // Stop existing service
        stop(updateStatus = false)

        // Update status
        onStatusChanged(PollingStatus.Connecting(serverUrl))
        onDebugMessage("Iniciando polling...")

        // Create and start new polling service
        longPollingService = LongPollingService(context, serverUrl)
        longPollingService?.start(scope)

        Log.d(TAG, "Long polling started for $serverUrl")
    }

    /**
     * Stops long polling
     */
    fun stop(updateStatus: Boolean = true) {
        longPollingService?.stop()
        longPollingService = null
        isConnected = false

        if (updateStatus) {
            onStatusChanged(PollingStatus.Stopped)
        }
    }

    /**
     * Restarts long polling
     */
    fun restart() {
        pollingScope?.let { start(it) }
    }

    /**
     * Enables long polling
     */
    fun enable() {
        isEnabled = true
        pollingScope?.let { start(it) }
    }

    /**
     * Disables long polling
     */
    fun disable() {
        isEnabled = false
        stop()
    }

    /**
     * Updates connection status (called from broadcast receiver)
     */
    fun updateConnectionStatus(connected: Boolean) {
        isConnected = connected
        if (connected) {
            onStatusChanged(PollingStatus.Connected)
        } else {
            onStatusChanged(PollingStatus.Disconnected)
        }
    }

    /**
     * Checks if polling service is currently active
     */
    fun isPolling(): Boolean = longPollingService?.isPolling() == true

    /**
     * Gets current server URL
     */
    fun getServerUrl(): String? {
        val prefs = context.getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        val serverIp = prefs.getString(AudioService.KEY_SERVER_IP, AudioService.DEFAULT_SERVER_IP)
            ?: AudioService.DEFAULT_SERVER_IP
        val serverPort = prefs.getInt(AudioService.KEY_SERVER_PORT, AudioService.DEFAULT_SERVER_PORT)

        return if (serverIp != AudioService.DEFAULT_SERVER_IP && serverIp.isNotBlank()) {
            "https://$serverIp:$serverPort"
        } else {
            null
        }
    }

    sealed class PollingStatus {
        data class Connecting(val serverUrl: String) : PollingStatus()
        object Connected : PollingStatus()
        object Disconnected : PollingStatus()
        object Stopped : PollingStatus()
        object Disabled : PollingStatus()
        object NotConfigured : PollingStatus()
        data class Error(val message: String) : PollingStatus()
    }

    companion object {
        private const val TAG = "PollingManager"
    }
}
