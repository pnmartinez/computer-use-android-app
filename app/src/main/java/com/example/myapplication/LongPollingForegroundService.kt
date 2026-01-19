package com.example.myapplication

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.IOException

/**
 * Foreground Service for Long Polling that works even when screen is locked.
 * Uses WakeLock to keep device awake during polling.
 */
class LongPollingForegroundService : Service() {
    companion object {
        private const val TAG = "LongPollingForeground"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "long_polling_channel"
        
        // Actions
        const val ACTION_START_POLLING = "com.example.myapplication.START_POLLING"
        const val ACTION_STOP_POLLING = "com.example.myapplication.STOP_POLLING"
        
        // Extras
        const val EXTRA_SERVER_URL = "server_url"
    }
    
    private val binder = LocalBinder()
    private var pollingJob: Job? = null
    private var pollingScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var serverUrl: String? = null
    private var isPolling = false
    
    inner class LocalBinder : Binder() {
        fun getService(): LongPollingForegroundService = this@LongPollingForegroundService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_POLLING -> {
                val url = intent.getStringExtra(EXTRA_SERVER_URL)
                if (url != null) {
                    startPolling(url)
                }
            }
            ACTION_STOP_POLLING -> {
                stopPolling()
                stopSelf()
            }
        }
        return START_STICKY // Restart if killed
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        releaseWakeLock()
        Log.d(TAG, "Service destroyed")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Long Polling",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene la conexión con el servidor LLM Control"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MCP Server conectado")
            .setContentText("Recibiendo actualizaciones del servidor")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LongPollingForegroundService::WakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
            Log.d(TAG, "WakeLock acquired")
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
            wakeLock = null
        }
    }
    
    private fun startPolling(url: String) {
        if (isPolling && serverUrl == url) {
            Log.d(TAG, "Already polling to $url")
            return
        }
        
        stopPolling() // Stop existing polling
        
        serverUrl = url
        isPolling = true
        
        // Acquire wake lock to keep device awake
        acquireWakeLock()
        
        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Create coroutine scope for polling
        pollingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        pollingJob = pollingScope?.launch {
            Log.d(TAG, "Starting long polling to $url")
            sendPollingStatus(true)
            
            while (isPolling && isActive) {
                try {
                    pollForUpdates(url)
                    // Reset retry delay on successful poll
                } catch (e: CancellationException) {
                    Log.d(TAG, "Polling cancelled")
                    break
                } catch (e: Exception) {
                    if (isPolling) {
                        Log.e(TAG, "Error during polling: ${e.message}", e)
                        sendPollingError(e.message ?: "Unknown error")
                        sendPollingStatus(false)
                        
                        // Wait before retry
                        delay(5000)
                    }
                }
            }
        }
        
        Log.d(TAG, "Long polling started")
    }
    
    private fun stopPolling() {
        isPolling = false
        pollingJob?.cancel()
        pollingJob = null
        pollingScope?.cancel()
        pollingScope = null
        releaseWakeLock()
        sendPollingStatus(false)
        Log.d(TAG, "Long polling stopped")
    }
    
    private suspend fun pollForUpdates(url: String) {
        val pollingUrl = "$url/pending-updates?timeout=30"
        Log.d(TAG, "Polling: $pollingUrl")
        
        val client = NetworkUtils.createLongPollingClient()
        val request = okhttp3.Request.Builder()
            .url(pollingUrl)
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
            return
        }
        
        Log.d(TAG, "Response received: ${body.take(200)}...")
        
        // Parse JSON response
        val jsonObject = org.json.JSONObject(body)
        val status = jsonObject.optString("status", "")
        
        if (status != "success") {
            Log.w(TAG, "Server returned non-success status: $status")
            return
        }
        
        // Check if this was a timeout (no updates)
        val timeout = jsonObject.optBoolean("timeout", false)
        if (timeout) {
            Log.d(TAG, "Poll timeout - no updates available")
            return
        }
        
        // Parse updates array
        val updatesArray = jsonObject.optJSONArray("updates")
        if (updatesArray == null || updatesArray.length() == 0) {
            Log.d(TAG, "No updates in response")
            return
        }
        
        Log.d(TAG, "Received ${updatesArray.length()} update(s)")
        
        // Process each update
        for (i in 0 until updatesArray.length()) {
            val updateJson = updatesArray.getJSONObject(i)
            val update = parseServerUpdate(updateJson)
            
            Log.d(TAG, "Processing update: ${update.id} - ${update.summary}")
            
            // Send broadcast for this update
            sendUpdateBroadcast(update)
        }
    }
    
    private fun parseServerUpdate(json: org.json.JSONObject): ServerUpdate {
        val changesArray = json.optJSONArray("changes")
        val changes = mutableListOf<String>()
        if (changesArray != null) {
            for (i in 0 until changesArray.length()) {
                changes.add(changesArray.getString(i))
            }
        }
        
        return ServerUpdate(
            id = json.optString("id", ""),
            timestamp = json.optString("timestamp", ""),
            type = json.optString("type", ""),
            summary = json.optString("summary", ""),
            changes = changes,
            metadata = null
        )
    }
    
    private fun sendUpdateBroadcast(update: ServerUpdate) {
        val intent = Intent(LongPollingService.ACTION_UPDATE_RECEIVED).apply {
            setPackage(packageName)
            putExtra(LongPollingService.EXTRA_UPDATE_ID, update.id)
            putExtra(LongPollingService.EXTRA_UPDATE_TYPE, update.type)
            putExtra(LongPollingService.EXTRA_UPDATE_SUMMARY, update.summary)
            putExtra(LongPollingService.EXTRA_UPDATE_TIMESTAMP, update.timestamp)
            putStringArrayListExtra(LongPollingService.EXTRA_UPDATE_CHANGES, ArrayList(update.changes))
        }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent for update: ${update.id}")
    }
    
    private fun sendPollingStatus(connected: Boolean) {
        val intent = Intent(LongPollingService.ACTION_POLLING_STATUS).apply {
            setPackage(packageName)
            putExtra(LongPollingService.EXTRA_POLLING_CONNECTED, connected)
        }
        sendBroadcast(intent)
    }
    
    private fun sendPollingError(message: String) {
        val intent = Intent(LongPollingService.ACTION_POLLING_ERROR).apply {
            setPackage(packageName)
            putExtra(LongPollingService.EXTRA_ERROR_MESSAGE, message)
        }
        sendBroadcast(intent)
    }
}
