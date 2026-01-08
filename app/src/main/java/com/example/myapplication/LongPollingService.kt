package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Service that implements long polling to receive updates from the server.
 * Uses the same broadcast pattern as AudioService for consistency.
 */
class LongPollingService(
    private val context: Context,
    private val serverUrl: String
) {
    companion object {
        private const val TAG = "LongPollingService"
        
        // Broadcast actions
        const val ACTION_UPDATE_RECEIVED = "com.example.myapplication.UPDATE_RECEIVED"
        const val ACTION_POLLING_STATUS = "com.example.myapplication.POLLING_STATUS"
        const val ACTION_POLLING_ERROR = "com.example.myapplication.POLLING_ERROR"
        const val ACTION_POLLING_DEBUG = "com.example.myapplication.POLLING_DEBUG"
        
        // Broadcast extras
        const val EXTRA_UPDATE_ID = "update_id"
        const val EXTRA_DEBUG_MESSAGE = "debug_message"
        const val EXTRA_UPDATE_TYPE = "update_type"
        const val EXTRA_UPDATE_SUMMARY = "update_summary"
        const val EXTRA_UPDATE_CHANGES = "update_changes"
        const val EXTRA_UPDATE_TIMESTAMP = "update_timestamp"
        const val EXTRA_POLLING_CONNECTED = "polling_connected"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        
        // Polling configuration
        const val DEFAULT_POLLING_TIMEOUT = 30  // seconds
        const val RETRY_DELAY_INITIAL_MS = 1000L
        const val RETRY_DELAY_MAX_MS = 60000L
    }
    
    private val client: OkHttpClient by lazy {
        NetworkUtils.createLongPollingClient()
    }
    
    private val gson = Gson()
    
    @Volatile
    private var isRunning = false
    
    private var pollingJob: Job? = null
    private var retryDelayMs = RETRY_DELAY_INITIAL_MS
    
    /**
     * Starts the long polling loop in the given coroutine scope
     */
    fun start(scope: CoroutineScope) {
        if (isRunning) {
            Log.d(TAG, "Long polling already running")
            sendPollingDebug("Ya está corriendo")
            return
        }
        
        isRunning = true
        Log.d(TAG, "Starting long polling to $serverUrl")
        sendPollingDebug("Iniciando a $serverUrl")
        
        pollingJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Coroutine started")
            withContext(Dispatchers.Main) {
                sendPollingDebug("Coroutine iniciada")
            }
            
            while (isRunning && isActive) {
                try {
                    pollForUpdates()
                    // Reset retry delay on successful poll
                    retryDelayMs = RETRY_DELAY_INITIAL_MS
                    sendPollingStatus(true)
                } catch (e: CancellationException) {
                    // Coroutine was cancelled, stop polling
                    Log.d(TAG, "Polling coroutine cancelled")
                    withContext(Dispatchers.Main) {
                        sendPollingDebug("Coroutine cancelada")
                    }
                    throw e
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error during polling: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            sendPollingError(e.message ?: "Unknown error")
                            sendPollingStatus(false)
                        }
                        
                        // Exponential backoff
                        delay(retryDelayMs)
                        retryDelayMs = minOf(retryDelayMs * 2, RETRY_DELAY_MAX_MS)
                    }
                }
            }
        }
    }
    
    /**
     * Stops the long polling loop
     */
    fun stop() {
        Log.d(TAG, "Stopping long polling")
        isRunning = false
        pollingJob?.cancel()
        pollingJob = null
        sendPollingStatus(false)
    }
    
    /**
     * Check if polling is currently running
     */
    fun isPolling(): Boolean = isRunning
    
    /**
     * Performs a single long polling request to the server
     */
    private suspend fun pollForUpdates() {
        val url = "$serverUrl/pending-updates?timeout=$DEFAULT_POLLING_TIMEOUT"
        Log.d(TAG, "Polling: $url")
        
        // Send debug info (must be on Main thread)
        withContext(Dispatchers.Main) {
            sendPollingDebug("Polling...")
        }
        
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Android LongPolling Client")
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IOException("Server error: ${response.code} ${response.message}")
        }
        
        val body = response.body?.string()
        if (body.isNullOrEmpty()) {
            Log.d(TAG, "Empty response body")
            withContext(Dispatchers.Main) {
                sendPollingDebug("Respuesta vacía")
            }
            return
        }
        
        Log.d(TAG, "Response received: ${body.take(200)}...")
        
        // Debug: show raw response
        withContext(Dispatchers.Main) {
            sendPollingDebug("Resp: ${body.take(80)}")
        }
        
        // Parse JSON response
        val jsonObject = JSONObject(body)
        val status = jsonObject.optString("status", "")
        
        if (status != "success") {
            Log.w(TAG, "Server returned non-success status: $status")
            withContext(Dispatchers.Main) {
                sendPollingDebug("Status: $status")
            }
            return
        }
        
        // Check if this was a timeout (no updates)
        val timeout = jsonObject.optBoolean("timeout", false)
        if (timeout) {
            Log.d(TAG, "Poll timeout - no updates available")
            withContext(Dispatchers.Main) {
                sendPollingDebug("⏱️ Timeout OK")
            }
            return
        }
        
        // Parse updates array
        val updatesArray = jsonObject.optJSONArray("updates")
        if (updatesArray == null || updatesArray.length() == 0) {
            Log.d(TAG, "No updates in response")
            withContext(Dispatchers.Main) {
                sendPollingDebug("0 updates")
            }
            return
        }
        
        withContext(Dispatchers.Main) {
            sendPollingDebug("📨 ${updatesArray.length()} update(s)!")
        }
        
        // Process each update
        for (i in 0 until updatesArray.length()) {
            val updateJson = updatesArray.getJSONObject(i)
            val update = parseServerUpdate(updateJson)
            
            Log.d(TAG, "Processing update: ${update.id} - ${update.summary}")
            
            // Send broadcast for this update (on main thread context)
            withContext(Dispatchers.Main) {
                sendUpdateBroadcast(update)
            }
        }
        
        // Check if there are more updates to fetch
        val hasMore = jsonObject.optBoolean("has_more", false)
        if (hasMore) {
            Log.d(TAG, "Server has more updates, will fetch immediately")
            // Don't delay before next poll if there are more updates
        }
    }
    
    /**
     * Parse a JSONObject into a ServerUpdate
     */
    private fun parseServerUpdate(json: JSONObject): ServerUpdate {
        val changesArray = json.optJSONArray("changes")
        val changes = mutableListOf<String>()
        if (changesArray != null) {
            for (i in 0 until changesArray.length()) {
                changes.add(changesArray.getString(i))
            }
        }
        
        // Parse metadata as a map
        val metadataJson = json.optJSONObject("metadata")
        val metadata: Map<String, Any>? = if (metadataJson != null) {
            try {
                gson.fromJson(metadataJson.toString(), object : TypeToken<Map<String, Any>>() {}.type)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        
        return ServerUpdate(
            id = json.optString("id", ""),
            timestamp = json.optString("timestamp", ""),
            type = json.optString("type", ""),
            summary = json.optString("summary", ""),
            changes = changes,
            metadata = metadata
        )
    }
    
    /**
     * Send a broadcast when an update is received
     */
    private fun sendUpdateBroadcast(update: ServerUpdate) {
        val intent = Intent(ACTION_UPDATE_RECEIVED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_UPDATE_ID, update.id)
            putExtra(EXTRA_UPDATE_TYPE, update.type)
            putExtra(EXTRA_UPDATE_SUMMARY, update.summary)
            putExtra(EXTRA_UPDATE_TIMESTAMP, update.timestamp)
            putStringArrayListExtra(EXTRA_UPDATE_CHANGES, ArrayList(update.changes))
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent for update: ${update.id}")
    }
    
    /**
     * Send polling status broadcast
     */
    private fun sendPollingStatus(connected: Boolean) {
        val intent = Intent(ACTION_POLLING_STATUS).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_POLLING_CONNECTED, connected)
        }
        context.sendBroadcast(intent)
    }
    
    /**
     * Send polling error broadcast
     */
    private fun sendPollingError(message: String) {
        val intent = Intent(ACTION_POLLING_ERROR).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        context.sendBroadcast(intent)
    }
    
    /**
     * Send polling debug broadcast for UI feedback
     */
    private fun sendPollingDebug(message: String) {
        val intent = Intent(ACTION_POLLING_DEBUG).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_DEBUG_MESSAGE, message)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Debug broadcast: $message")
    }
}
