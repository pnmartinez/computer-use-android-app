package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AudioService : Service() {

    private var recorder: MediaRecorder? = null
    private var player: ExoPlayer? = null
    private var silentPlayer: ExoPlayer? = null  // For headset control - plays silent audio
    private var textToSpeechManager: TextToSpeechManager? = null
    private var ttsLanguage: String = DEFAULT_TTS_LANGUAGE
    private var ttsRate: Float = DEFAULT_TTS_RATE
    private var ttsPitch: Float = DEFAULT_TTS_PITCH
    private var audioPlaybackEnabled: Boolean = DEFAULT_AUDIO_PLAYBACK_ENABLED
    private var headsetFeedbackEnabled: Boolean = DEFAULT_HEADSET_FEEDBACK_ENABLED
    private lateinit var mediaSession: MediaSessionCompat
    private val mediaButtonHandler = Handler(Looper.getMainLooper())
    // Timings optimizados para Bluetooth - compensa latencia BT (50-200ms según codec)
    private val multiClickWindowMs = MULTI_CLICK_WINDOW_MS
    private val debounceMs = DEBOUNCE_MS
    private var lastEventTime = 0L         // Para debounce
    private var lastClickAt = 0L
    private var clickCount = 0
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var headsetControlEnabled = false
    
    // Bluetooth SCO para usar micrófono de auriculares Bluetooth
    private var isBluetoothScoOn = false
    private var bluetoothScoReceiver: BroadcastReceiver? = null
    private var pendingRecordingAfterSco = false
    private var bluetoothCommunicationDevice: AudioDeviceInfo? = null  // Para Android 12+

    // Para coroutines con un Job que cancelaremos en onDestroy()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)

    // REST API URL - will be loaded from SharedPreferences
    private var serverIp: String = DEFAULT_SERVER_IP
    private var serverPort: Int = DEFAULT_SERVER_PORT
    private var whisperModel: String = DEFAULT_WHISPER_MODEL
    private var responseTimeout: Int = DEFAULT_RESPONSE_TIMEOUT
    private val apiBaseUrl: String
        get() = "https://$serverIp:$serverPort"
    
    // Flag for testing - when true, we create a dummy response when "sending" audio
    private val testMode = false

    // Configuración OkHttp con timeouts
    private val httpClient: OkHttpClient by lazy {
        NetworkUtils.createTrustAllClient(
            connectTimeout = 30,
            readTimeout = 30,
            writeTimeout = 30
        )
    }

    // Flag para controlar el estado de grabación
    private var isRecording = false

    companion object {
        // Timing constants - adjust based on testing with different Bluetooth devices
        const val MULTI_CLICK_WINDOW_MS = 450L      // Window for multi-click detection
        const val DEBOUNCE_MS = 80L                  // Minimum time between events to avoid button bounce
        const val LONG_PRESS_THRESHOLD_MS = 700L    // Hold time for long-press (future use)
        const val SINGLE_CLICK_DELAY_MS = 500L      // Wait before executing single-click action
        
        const val ACTION_RECORDING_STARTED = "com.example.myapplication.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.example.myapplication.RECORDING_STOPPED"
        const val ACTION_RESPONSE_RECEIVED = "com.example.myapplication.RESPONSE_RECEIVED"
        const val ACTION_RESPONSE_PLAYED = "com.example.myapplication.RESPONSE_PLAYED"
        const val ACTION_LOG_MESSAGE = "com.example.myapplication.LOG_MESSAGE"
        const val ACTION_AUDIO_FILE_INFO = "com.example.myapplication.AUDIO_FILE_INFO"
        const val ACTION_PROCESSING_COMPLETED = "com.example.myapplication.PROCESSING_COMPLETED"
        const val ACTION_CONNECTION_TESTED = "com.example.myapplication.CONNECTION_TESTED"
        const val ACTION_TTS_STATUS = "com.example.myapplication.TTS_STATUS"
        const val ACTION_ENABLE_HEADSET_CONTROL = "com.example.myapplication.ENABLE_HEADSET_CONTROL"
        const val ACTION_DISABLE_HEADSET_CONTROL = "com.example.myapplication.DISABLE_HEADSET_CONTROL"
        const val ACTION_HEADSET_CONTROL_STATUS = "com.example.myapplication.HEADSET_CONTROL_STATUS"
        const val ACTION_QUERY_HEADSET_CONTROL_STATUS = "com.example.myapplication.QUERY_HEADSET_CONTROL_STATUS"
        const val ACTION_TEST_MEDIA_BUTTON = "com.example.myapplication.TEST_MEDIA_BUTTON"
        const val ACTION_MICROPHONE_CHANGED = "com.example.myapplication.MICROPHONE_CHANGED"
        
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val EXTRA_AUDIO_FILE_PATH = "audio_file_path"
        const val EXTRA_AUDIO_FILE_SIZE = "audio_file_size"
        const val EXTRA_AUDIO_DURATION = "audio_duration"
        const val EXTRA_AUDIO_TYPE = "audio_type"  // recording or response
        const val EXTRA_CONNECTION_SUCCESS = "connection_success"
        const val EXTRA_CONNECTION_MESSAGE = "connection_message"
        const val EXTRA_RESPONSE_MESSAGE = "response_message"
        const val EXTRA_RESPONSE_SUCCESS = "response_success"
        const val EXTRA_SCREEN_SUMMARY = "screen_summary"
        const val EXTRA_TTS_STATUS = "tts_status"
        const val EXTRA_HEADSET_CONTROL_ENABLED = "headset_control_enabled"
        const val EXTRA_MICROPHONE_NAME = "microphone_name"
        
        // Default server settings
        const val DEFAULT_SERVER_IP = "your_server_ip_here"
        const val DEFAULT_SERVER_PORT = 5000
        const val DEFAULT_WHISPER_MODEL = "large"
        const val DEFAULT_RESPONSE_TIMEOUT = 20000 // 20 seconds in milliseconds
        const val DEFAULT_TTS_LANGUAGE = "es-ES"
        const val DEFAULT_TTS_RATE = 1.0f
        const val DEFAULT_TTS_PITCH = 1.0f
        const val DEFAULT_AUDIO_PLAYBACK_ENABLED = true
        const val DEFAULT_HEADSET_FEEDBACK_ENABLED = true  // Feedback auditivo activado por defecto
        
        // SharedPreferences keys
        const val PREFS_NAME = "AudioServicePrefs"
        const val KEY_SERVER_IP = "server_ip"
        const val KEY_SERVER_PORT = "server_port"
        const val KEY_WHISPER_MODEL = "whisper_model"
        const val KEY_RESPONSE_TIMEOUT = "response_timeout"
        const val KEY_TTS_LANGUAGE = "tts_language"
        const val KEY_TTS_RATE = "tts_rate"
        const val KEY_TTS_PITCH = "tts_pitch"
        const val KEY_AUDIO_PLAYBACK_ENABLED = "audio_playback_enabled"
        const val KEY_HEADSET_FEEDBACK_ENABLED = "headset_feedback_enabled"

        const val TTS_STATUS_PLAYING = "playing"
        const val TTS_STATUS_IDLE = "idle"
        const val TTS_STATUS_ERROR = "error"
        
        // Get current response timeout from SharedPreferences
        fun getCurrentResponseTimeout(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_RESPONSE_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT)
        }
        
        // Start log capture process
        fun startLogCapture(context: Context) {
            try {
                val pid = android.os.Process.myPid()
                val packageName = context.packageName
                
                // Start a background thread to read logcat
                Thread {
                    try {
                        // Clear the log first to reduce noise
                        Runtime.getRuntime().exec("logcat -c")
                        
                        // Start logcat process - filter to show all logs from our app
                        val process = Runtime.getRuntime().exec(
                            arrayOf("logcat", 
                                    "-v", "threadtime", // Include thread info
                                    // Filter to include all tags but only from our package
                                    "*:V")  // Verbose level to catch everything
                        )
                        
                        val bufferedReader = process.inputStream.bufferedReader()
                        var line: String?
                        
                        while (bufferedReader.readLine().also { line = it } != null) {
                            if (line == null) continue
                            
                            // Only process lines from our app - relaxed filter to catch more logs
                            if (line!!.contains(packageName) || 
                                line!!.contains("AudioService") || 
                                line!!.contains("WebSocket")) {
                                // Parse the logcat line
                                try {
                                    // Filter out some noisy logs
                                    if (line!!.contains("BufferQueue") || 
                                        line!!.contains("ViewRootImpl") || 
                                        line!!.contains("Choreographer") ||
                                        line!!.contains("ANR") ||
                                        line!!.contains("ActivityThread")) {
                                        continue
                                    }
                                    
                                    // Broadcast the log line to the UI
                                    val intent = Intent(ACTION_LOG_MESSAGE).apply {
                                        putExtra(EXTRA_LOG_MESSAGE, formatLogcatLine(line!!))
                                    }
                                    context.sendBroadcast(intent)
                                } catch (e: Exception) {
                                    // If parsing fails, just use the raw line
                                    val intent = Intent(ACTION_LOG_MESSAGE).apply {
                                        putExtra(EXTRA_LOG_MESSAGE, "LOGCAT: ${line!!}")
                                    }
                                    context.sendBroadcast(intent)
                                }
                            }
                            
                            // Small delay to not overload the UI with too many logs at once
                            Thread.sleep(10)
                        }
                    } catch (e: Exception) {
                        // If logcat reading fails, send error to the UI
                        val intent = Intent(ACTION_LOG_MESSAGE).apply {
                            putExtra(EXTRA_LOG_MESSAGE, "[ERROR] Log capture failed: ${e.message}")
                        }
                        context.sendBroadcast(intent)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                Log.e("LogCapture", "Failed to start log capture", e)
            }
        }
        
        // Format a logcat line to be more readable in the UI
        private fun formatLogcatLine(line: String): String {
            // Example logcat line with threadtime format:
            // 2025-03-17 17:50:42.798 10130-10130 AudioService com.example.myapplication D Message
            
            try {
                // Split the line by spaces, but respect quotes
                val parts = line.split(" +".toRegex()).filter { it.isNotEmpty() }
                if (parts.size < 7) return "LOG: $line" // Not enough parts
                
                // Extract the parts
                val date = parts[0]
                val time = parts[1]
                val pid = parts[2]
                val tag = parts[4] 
                val level = parts[6]
                
                // Get the message starting position
                val logLevelIndex = line.indexOf(" $level ")
                val messageIndex = if (logLevelIndex > 0) logLevelIndex + level.length + 1 else 0
                val message = if (messageIndex < line.length) line.substring(messageIndex).trim() else ""
                
                // Simplify the timestamp
                val simpleTime = time.split("\\.".toRegex())[0]
                
                // Format based on log level
                val prefix = when {
                    level.contains("E") -> "🔴 "  // Error
                    level.contains("W") -> "🟠 "  // Warning
                    level.contains("I") -> "🔵 "  // Info
                    level.contains("D") -> "⚫ "  // Debug
                    level.contains("V") -> "⚪ "  // Verbose
                    else -> ""
                }
                
                // Special highlight for audio sending logs
                val formattedMessage = if (message.contains("audio sending", ignoreCase = true) || 
                                         message.contains("Sending", ignoreCase = true) ||
                                         message.contains("audio sent", ignoreCase = true)) {
                    "📤 $message"  // Add upload emoji for sending
                } else if (message.contains("received", ignoreCase = true) || 
                          message.contains("response", ignoreCase = true)) {
                    "📥 $message"  // Add download emoji for receiving
                } else {
                    "$prefix$message"
                }
                
                // Return formatted message
                return "$prefix[$simpleTime] $tag: $formattedMessage"
            } catch (e: Exception) {
                // If parsing fails, return the original line
                return "LOG: $line"
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Load settings from SharedPreferences
        loadSettings()
        mediaSession = MediaSessionCompat(this, "HeadsetControls").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE
                )
                .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 1.0f)
                .build()
            setPlaybackState(state)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent?): Boolean {
                    Log.d("AudioService", "=== MediaSession.onMediaButtonEvent CALLED ===")
                    Log.d("AudioService", "Intent action: ${mediaButtonIntent?.action}")
                    Log.d("AudioService", "headsetControlEnabled: $headsetControlEnabled")
                    
                    // MASTER SWITCH: If headset control is disabled, ignore all button events
                    if (!headsetControlEnabled) {
                        Log.d("AudioService", "Headset control disabled - ignoring button event")
                        sendLogMessage("⚠️ Button pressed but headset control is OFF")
                        return false
                    }
                    
                    val event = mediaButtonIntent
                        ?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    
                    if (event == null) {
                        Log.d("AudioService", "No KeyEvent in intent, returning false")
                        return false
                    }
                    
                    Log.d("AudioService", "KeyEvent: keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}), action=${event.action}")
                    
                    // Solo procesamos ACTION_DOWN - ignorar ACTION_UP y otros
                    if (event.action != KeyEvent.ACTION_DOWN) {
                        Log.d("AudioService", "Ignoring non-ACTION_DOWN event")
                        return false
                    }
                    
                    // Log para debug - sin debounce para mejor respuesta
                    Log.d("AudioService", "Processing media button event, isRecording=$isRecording")
                    
                    return when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            // Toggle directo - sin delays de multi-click
                            if (isRecording) {
                                Log.d("AudioService", "PLAY_PAUSE/HEADSETHOOK while recording -> stop & send")
                                sendLogMessage("🎧 Toque → detener y enviar")
                                if (headsetFeedbackEnabled) playClickFeedback(2)
                                stopRecordingAndSend()
                            } else {
                                Log.d("AudioService", "PLAY_PAUSE/HEADSETHOOK no recording -> starting recording")
                                sendLogMessage("🎧 Toque → iniciar grabación")
                                if (headsetFeedbackEnabled) playClickFeedback(1)
                                startRecording()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            // MEDIA_PLAY - actuar como toggle igual que MEDIA_NEXT
                            if (isRecording) {
                                Log.d("AudioService", "MEDIA_PLAY while recording -> stop & send")
                                sendLogMessage("🎧 Toque (PLAY) → detener y enviar")
                                if (headsetFeedbackEnabled) playClickFeedback(2)
                                stopRecordingAndSend()
                            } else {
                                Log.d("AudioService", "MEDIA_PLAY no recording -> starting recording")
                                sendLogMessage("🎧 Toque (PLAY) → iniciar grabación")
                                if (headsetFeedbackEnabled) playClickFeedback(1)
                                startRecording()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            // MEDIA_PAUSE se envía cuando hay audio activo (grabando)
                            // Actuar como toggle igual que MEDIA_NEXT
                            if (isRecording) {
                                Log.d("AudioService", "MEDIA_PAUSE while recording -> stop & send")
                                sendLogMessage("🎧 Toque (PAUSE) → detener y enviar")
                                if (headsetFeedbackEnabled) playClickFeedback(2)
                                stopRecordingAndSend()
                            } else {
                                Log.d("AudioService", "MEDIA_PAUSE no recording -> starting recording")
                                sendLogMessage("🎧 Toque (PAUSE) → iniciar grabación")
                                if (headsetFeedbackEnabled) playClickFeedback(1)
                                startRecording()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            // Algunos auriculares envían NEXT como toque único
                            // Si hay grabación: detener y enviar. Si no: iniciar.
                            if (isRecording) {
                                Log.d("AudioService", "MEDIA_NEXT while recording -> stop & send")
                                sendLogMessage("🎧 Toque (NEXT) → detener y enviar")
                                if (headsetFeedbackEnabled) playClickFeedback(2)
                                stopRecordingAndSend()
                            } else {
                                Log.d("AudioService", "MEDIA_NEXT no recording -> starting recording")
                                sendLogMessage("🎧 Toque (NEXT) → iniciar grabación")
                                if (headsetFeedbackEnabled) playClickFeedback(1)
                                startRecording()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            // Algunos auriculares envían PREVIOUS como toque
                            if (isRecording) {
                                Log.d("AudioService", "MEDIA_PREVIOUS while recording -> cancel")
                                sendLogMessage("🎧 Toque (PREV) → cancelar grabación")
                                if (headsetFeedbackEnabled) playClickFeedback(3)
                                stopRecordingWithoutSending()
                            } else {
                                Log.d("AudioService", "MEDIA_PREVIOUS no recording -> ignored")
                                sendLogMessage("🎧 Toque (PREV) → nada que cancelar")
                            }
                            true
                        }
                        else -> {
                            Log.d("AudioService", "Unhandled keyCode: ${event.keyCode}")
                            false
                        }
                    }
                }
                
                override fun onPlay() {
                    Log.d("AudioService", "MediaSession.onPlay() called")
                    sendLogMessage("🎧 MediaSession: onPlay")
                }
                
                override fun onPause() {
                    Log.d("AudioService", "MediaSession.onPause() called")
                    sendLogMessage("🎧 MediaSession: onPause")
                }
                
                override fun onStop() {
                    Log.d("AudioService", "MediaSession.onStop() called")
                    sendLogMessage("🎧 MediaSession: onStop")
                }
            })
            isActive = false
        }
        sendHeadsetControlStatus(false)
        textToSpeechManager = TextToSpeechManager(
            this,
            onReadyChanged = { isReady ->
                if (isReady) {
                    sendLogMessage(getString(R.string.tts_initialized))
                    applyTtsSettings()
                } else {
                    sendLogMessage(getString(R.string.tts_init_failed))
                }
            },
            onSpeakStart = { sendTtsStatus(TTS_STATUS_PLAYING) },
            onSpeakDone = { sendTtsStatus(TTS_STATUS_IDLE) },
            onSpeakError = { sendTtsStatus(TTS_STATUS_ERROR) }
        )
        
        // Start as foreground service with media playback type initially.
        // We'll use microphone type only when actually recording (if permission is granted).
        val serviceType = if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED) {
            // Permission granted, can use microphone type
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            // Permission not granted yet, use only media playback
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        startForeground(1, createNotification(), serviceType)
        sendLogMessage(getString(R.string.simple_computer_use_service_started) + if (testMode) " (MODO PRUEBA)" else "")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(
            "AudioService",
            "onStartCommand action=${intent?.action} extras=${intent?.extras?.keySet()}"
        )
        if (intent != null) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        // Check if we need to update settings
        if (intent?.action == "UPDATE_SETTINGS") {
            val newIp = intent.getStringExtra(KEY_SERVER_IP)
            val newPort = intent.getIntExtra(KEY_SERVER_PORT, DEFAULT_SERVER_PORT)
            val newModel = intent.getStringExtra(KEY_WHISPER_MODEL)
            val newTimeout = intent.getIntExtra(KEY_RESPONSE_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT)
            val newTtsLanguage = intent.getStringExtra(KEY_TTS_LANGUAGE)
            val newTtsRate = intent.getFloatExtra(KEY_TTS_RATE, DEFAULT_TTS_RATE)
            val newTtsPitch = intent.getFloatExtra(KEY_TTS_PITCH, DEFAULT_TTS_PITCH)
            val newAudioPlaybackEnabled = intent.getBooleanExtra(
                KEY_AUDIO_PLAYBACK_ENABLED,
                audioPlaybackEnabled
            )
            val newHeadsetFeedbackEnabled = intent.getBooleanExtra(
                KEY_HEADSET_FEEDBACK_ENABLED,
                headsetFeedbackEnabled
            )
            
            if (newIp != null) {
                updateServerSettings(
                    newIp,
                    newPort,
                    newModel,
                    newTimeout,
                    newTtsLanguage,
                    newTtsRate,
                    newTtsPitch,
                    newAudioPlaybackEnabled,
                    newHeadsetFeedbackEnabled
                )
                sendLogMessage(getString(R.string.server_configuration_updated, serverIp, serverPort, whisperModel))
            }
        } else {
            // Handle normal actions
        when (intent?.action) {
            "START_RECORDING" -> startRecording()
            "STOP_RECORDING" -> stopRecordingAndSend()
                "CANCEL_RECORDING" -> stopRecordingWithoutSending()
            ACTION_ENABLE_HEADSET_CONTROL -> enableHeadsetControlMode()
            ACTION_DISABLE_HEADSET_CONTROL -> disableHeadsetControlMode()
            ACTION_QUERY_HEADSET_CONTROL_STATUS -> sendHeadsetControlStatus(headsetControlEnabled)
            ACTION_TEST_MEDIA_BUTTON -> simulateMediaButtonPress()
            "PLAY_RESPONSE" -> playLastResponse()
                "SPEAK_SUMMARY" -> {
                    val summaryText = intent.getStringExtra(EXTRA_SCREEN_SUMMARY).orEmpty()
                    if (summaryText.isNotBlank()) {
                        serviceScope.launch(Dispatchers.Main) {
                            val didSpeak = textToSpeechManager?.speak(summaryText) ?: false
                            if (didSpeak) {
                                sendLogMessage(getString(R.string.tts_speaking_response))
                            } else {
                                sendLogMessage(getString(R.string.tts_not_ready))
                                sendTtsStatus(TTS_STATUS_ERROR)
                            }
                        }
                    } else {
                        sendLogMessage(getString(R.string.summary_unavailable))
                        sendTtsStatus(TTS_STATUS_ERROR)
                    }
                }
                "TEST_CONNECTION" -> testServerConnection()
                "RESET_STATE" -> {
                    // Force reset recording state regardless of current state
                    if (isRecording) {
                        Log.d("AudioService", "Forced reset of recording state")
                        sendLogMessage(getString(R.string.forced_reset_recording_state))
                        recorder?.release()
                        recorder = null
                        isRecording = false
                        
                        // Update notification
                        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(1, createNotification())
                            
                        // Broadcast state change with explicit package name
                        val intent = Intent(ACTION_RECORDING_STOPPED)
                        intent.setPackage(packageName)
                        sendBroadcast(intent)
                        Log.d("AudioService", "Broadcasting ACTION_RECORDING_STOPPED (reset) to package $packageName")
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        sendLogMessage(getString(R.string.audio_service_stopped))
        
        // Cancelar coroutines en curso
        serviceJob.cancel()

        mediaButtonHandler.removeCallbacksAndMessages(null)
        disableHeadsetControlMode()
        stopBluetoothSco()  // Limpiar SCO al destruir el servicio
        mediaSession.release()
        abandonAudioFocus()

        // Liberar ExoPlayer
        player?.release()
        player = null
        
        // Liberar silent player for headset control
        silentPlayer?.release()
        silentPlayer = null

        textToSpeechManager?.shutdown()
        textToSpeechManager = null
    }

    /**
     * Crea y devuelve el archivo para la grabación en almacenamiento interno.
     */
    private fun getAudioFile(): File {
        return File(filesDir, "recorded_audio.ogg")
    }

    /**
     * Crea y devuelve el archivo para guardar la respuesta en almacenamiento interno.
     */
    private fun getResponseFile(): File {
        return File(filesDir, "response_audio.ogg")
    }

    private fun createNotification(): Notification {
        val channelId = "audio_service"
        val channelName = "Simple Computer Use Service"
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the status of Simple Computer Use service"
            enableLights(false)
            enableVibration(false)
        }
        
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)

        // Create a pending intent to open the app when notification is tapped
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Simple Computer Use")
            .setContentText(if (isRecording) "Grabando audio..." else "Servicio activo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(if (isRecording) 
                    "Grabando audio para enviar al servidor" 
                    else "Servicio de audio activo y listo para grabar"))
        if (headsetControlEnabled) {
            builder.setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
            )
        }
        return builder.build()
    }

    private fun handleMultiClick() {
        val now = SystemClock.uptimeMillis()
        val timeSinceLastClick = if (lastClickAt > 0) now - lastClickAt else Long.MAX_VALUE
        
        Log.d("AudioService", "handleMultiClick: timeSinceLastClick=${timeSinceLastClick}ms, window=${multiClickWindowMs}ms, previousCount=$clickCount")
        
        clickCount = if (timeSinceLastClick <= multiClickWindowMs) clickCount + 1 else 1
        lastClickAt = now
        
        Log.d("AudioService", "Click count updated to: $clickCount")

        mediaButtonHandler.removeCallbacksAndMessages(null)
        
        // Feedback auditivo para confirmar detección (configurable en Settings)
        if (headsetFeedbackEnabled) {
            playClickFeedback(clickCount)
        }
        
        when (clickCount) {
            1 -> {
                Log.d("AudioService", "Single click detected - waiting ${SINGLE_CLICK_DELAY_MS}ms before action")
                mediaButtonHandler.postDelayed({
                    if (clickCount == 1) {
                        // Toggle behavior: if recording, stop and send; otherwise start recording
                        if (isRecording) {
                            Log.d("AudioService", "Single click while recording -> stopping and sending")
                            sendLogMessage("🎧 Button pressed while recording → stopping and sending")
                            stopRecordingAndSend()
                        } else {
                            Log.d("AudioService", "Single click -> starting recording")
                            startRecording()
                        }
                        clickCount = 0
                    }
                }, SINGLE_CLICK_DELAY_MS)
            }
            2 -> {
                Log.d("AudioService", "Double click detected -> stopping and sending")
                stopRecordingAndSend()
                clickCount = 0
            }
            3 -> {
                Log.d("AudioService", "Triple click detected -> canceling recording")
                stopRecordingWithoutSending()
                clickCount = 0
            }
        }
    }
    
    /**
     * Proporciona feedback auditivo para confirmar la detección de clicks.
     * Esto ayuda al usuario a saber que sus pulsaciones fueron detectadas.
     * 
     * IMPORTANTE: Usa STREAM_MUSIC para que el sonido se enrute a los auriculares Bluetooth.
     * STREAM_NOTIFICATION no se enruta a BT en muchos dispositivos.
     */
    private fun playClickFeedback(clickCount: Int) {
        if (!headsetFeedbackEnabled) return
        
        try {
            // STREAM_MUSIC para que el tono se reproduzca en los auriculares BT
            // Volumen al 100% para máxima audibilidad
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            
            Log.d("AudioService", "Playing feedback tone for click count: $clickCount")
            
            when (clickCount) {
                1 -> {
                    // Tono corto para single click (150ms)
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                }
                2 -> {
                    // Tono doble para double click (200ms)
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
                }
                3 -> {
                    // Tono de confirmación para triple click (250ms)
                    toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                }
            }
            
            // Liberar recursos después de que termine el tono
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    toneGen.release()
                } catch (e: Exception) {
                    Log.e("AudioService", "Error releasing ToneGenerator: ${e.message}")
                }
            }, 300)
        } catch (e: Exception) {
            Log.e("AudioService", "Error playing click feedback: ${e.message}", e)
            sendLogMessage("⚠️ Error en feedback auditivo: ${e.message}")
        }
    }
    
    /**
     * Feedback auditivo para inicio de grabación.
     * Tono ascendente para indicar que la grabación ha comenzado.
     */
    private fun playRecordingStartFeedback() {
        if (!headsetFeedbackEnabled || !headsetControlEnabled) return
        
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            Log.d("AudioService", "Playing recording START feedback")
            // Tono ascendente para indicar inicio
            toneGen.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 200)
            
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    toneGen.release()
                } catch (e: Exception) {
                    Log.e("AudioService", "Error releasing ToneGenerator: ${e.message}")
                }
            }, 300)
        } catch (e: Exception) {
            Log.e("AudioService", "Error playing recording start feedback: ${e.message}", e)
        }
    }
    
    /**
     * Feedback auditivo para fin de grabación.
     * Doble tono para indicar que la grabación ha terminado.
     */
    private fun playRecordingStopFeedback() {
        if (!headsetFeedbackEnabled || !headsetControlEnabled) return
        
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            Log.d("AudioService", "Playing recording STOP feedback")
            // Tono descendente para indicar fin
            toneGen.startTone(ToneGenerator.TONE_CDMA_NETWORK_BUSY_ONE_SHOT, 200)
            
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    toneGen.release()
                } catch (e: Exception) {
                    Log.e("AudioService", "Error releasing ToneGenerator: ${e.message}")
                }
            }, 300)
        } catch (e: Exception) {
            Log.e("AudioService", "Error playing recording stop feedback: ${e.message}", e)
        }
    }

    private fun enableHeadsetControlMode() {
        try {
            Log.d("AudioService", "enableHeadsetControlMode() called, current state: $headsetControlEnabled")
            
            if (headsetControlEnabled) {
                sendHeadsetControlStatus(true)
                Log.d("AudioService", "Headset control ENABLED (no change)")
                return
            }
            
            Log.d("AudioService", "Requesting audio focus...")
            if (!requestAudioFocus()) {
                sendLogMessage("AudioFocus DENIED: no puedo tomar control de botones")
                Log.d("AudioService", "Headset control ENABLED failed: audio focus denied")
                headsetControlEnabled = false
                sendHeadsetControlStatus(false)
                return
            }
            Log.d("AudioService", "Audio focus granted!")
            
            // CRITICAL: On Android 11+, we need to actually play media to receive button events
            // Start playing silent audio in a loop
            startSilentPlayback()
            
            // Set playback state to PLAYING to indicate we're the active media app
            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                .build()
            mediaSession.setPlaybackState(state)
            Log.d("AudioService", "PlaybackState set to PLAYING")
            
            // CRITICAL: Activate the MediaSession to receive media button events
            mediaSession.isActive = true
            Log.d("AudioService", "MediaSession isActive = true")
            Log.d("AudioService", "MediaSession token: ${mediaSession.sessionToken}")
            
            headsetControlEnabled = true
            
            // NOTE: No activamos SCO aquí - lo haremos solo cuando se inicie la grabación
            // Activar SCO aquí interfiere con la detección de botones de media
            
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, createNotification())
            sendLogMessage("🎧 Headset control ENABLED - Playing silent audio to capture buttons")
            Log.d("AudioService", "Headset control ENABLED successfully")
            sendHeadsetControlStatus(true)
        } catch (t: Throwable) {
            Log.e("AudioService", "enableHeadsetControlMode crashed", t)
            sendLogMessage("❌ Error enabling headset control: ${t.message}")
            headsetControlEnabled = false
            sendHeadsetControlStatus(false)
        }
    }
    
    private fun startSilentPlayback() {
        try {
            Log.d("AudioService", "Starting silent playback for headset control...")
            
            // Release existing silent player if any
            silentPlayer?.release()
            
            // Create ExoPlayer for silent audio
            silentPlayer = ExoPlayer.Builder(this).build().apply {
                // Set very low volume (near silent but not zero)
                volume = 0.01f
                
                // Set repeat mode to loop forever
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                
                // Load silence audio file
                val silenceUri = android.net.Uri.parse("android.resource://${packageName}/${R.raw.silence_1s}")
                val mediaItem = androidx.media3.common.MediaItem.fromUri(silenceUri)
                setMediaItem(mediaItem)
                
                // Prepare and start playback
                prepare()
                play()
                
                Log.d("AudioService", "Silent player started, isPlaying: $isPlaying")
            }
            
            sendLogMessage("🔇 Silent audio playback started")
        } catch (e: Exception) {
            Log.e("AudioService", "Error starting silent playback: ${e.message}", e)
            sendLogMessage("⚠️ Could not start silent playback: ${e.message}")
        }
    }
    
    private fun stopSilentPlayback() {
        try {
            silentPlayer?.let { player ->
                Log.d("AudioService", "Stopping silent playback...")
                player.stop()
                player.release()
            }
            silentPlayer = null
            sendLogMessage("🔇 Silent audio playback stopped")
        } catch (e: Exception) {
            Log.e("AudioService", "Error stopping silent playback: ${e.message}", e)
        }
    }
    
    /**
     * Simulates a media button press for testing purposes.
     * This bypasses the MediaButtonReceiver and directly triggers the MediaSession callback.
     */
    private fun simulateMediaButtonPress() {
        Log.d("AudioService", "=== SIMULATING MEDIA BUTTON PRESS ===")
        sendLogMessage("🧪 Simulating media button press (HEADSETHOOK)")
        
        if (!headsetControlEnabled) {
            sendLogMessage("⚠️ Headset control not enabled - enabling it first")
            enableHeadsetControlMode()
        }
        
        // Create a simulated KeyEvent for HEADSETHOOK (the common headset button)
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)
        
        // Create an intent with the KeyEvent
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
        }
        
        // Dispatch directly to our MediaSession callback
        Log.d("AudioService", "Dispatching simulated KeyEvent to MediaSession callback")
        val handled = mediaSession.controller.dispatchMediaButtonEvent(keyEvent)
        Log.d("AudioService", "MediaSession dispatchMediaButtonEvent returned: $handled")
        
        if (!handled) {
            // Try calling handleMultiClick directly as fallback
            sendLogMessage("🧪 Direct dispatch didn't work, calling handleMultiClick directly")
            handleMultiClick()
        }
    }

    private fun disableHeadsetControlMode() {
        try {
            Log.d("AudioService", "disableHeadsetControlMode() called, current state: $headsetControlEnabled")
            
            if (!headsetControlEnabled) {
                sendHeadsetControlStatus(false)
                Log.d("AudioService", "Headset control DISABLED (no change)")
                return
            }
            
            // Stop silent playback immediately
            stopSilentPlayback()
            
            // Set MediaSession to inactive and stopped state
            val state = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 1.0f)
                .build()
            mediaSession.setPlaybackState(state)
            
            // Deactivate MediaSession completely
            mediaSession.isActive = false
            
            // Release audio focus
            abandonAudioFocus()
            
            // Clear state
            headsetControlEnabled = false
            clickCount = 0
            lastClickAt = 0
            lastEventTime = 0L  // Limpiar también el debounce
            mediaButtonHandler.removeCallbacksAndMessages(null)
            
            // Detener Bluetooth SCO cuando se desactiva el modo manos libres
            stopBluetoothSco()
            
            sendLogMessage("🎧 Headset control DISABLED - All resources released")
            Log.d("AudioService", "Headset control DISABLED - MediaSession inactive, silent playback stopped")
            sendHeadsetControlStatus(false)
            sendMicrophoneChanged(null)
            
            // Update notification
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, createNotification())
        } catch (t: Throwable) {
            Log.e("AudioService", "disableHeadsetControlMode crashed", t)
            headsetControlEnabled = false
            sendHeadsetControlStatus(false)
            sendMicrophoneChanged(null)
        }
    }

    private fun sendHeadsetControlStatus(enabled: Boolean) {
        val intent = Intent(ACTION_HEADSET_CONTROL_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_HEADSET_CONTROL_ENABLED, enabled)
        }
        sendBroadcast(intent)
    }

    private fun sendMicrophoneChanged(micName: String?) {
        val intent = Intent(ACTION_MICROPHONE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_MICROPHONE_NAME, micName)
        }
        sendBroadcast(intent)
    }

    private fun requestAudioFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        return audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }
    
    /**
     * Verifica y activa Bluetooth SCO si está disponible.
     * SCO permite usar el micrófono de los auriculares Bluetooth.
     * 
     * En Android 12+ (API 33+) usa setCommunicationDevice().
     * En versiones anteriores usa startBluetoothSco().
     */
    private fun startBluetoothScoIfAvailable(): Boolean {
        if (!headsetControlEnabled) {
            Log.d("AudioService", "Headset control not enabled, skipping SCO")
            return false
        }
        
        // Android 12+ (API 33+): usar setCommunicationDevice()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return startBluetoothScoModern()
        } else {
            // Android 11 y anteriores: usar startBluetoothSco()
            return startBluetoothScoLegacy()
        }
    }
    
    /**
     * Método moderno para Android 12+ usando setCommunicationDevice()
     * IMPORTANTE: Solo TYPE_BLUETOOTH_SCO soporta micrófono, A2DP es solo salida
     */
    @Suppress("DEPRECATION")
    private fun startBluetoothScoModern(): Boolean {
        try {
            sendLogMessage("🔍 Buscando micrófono Bluetooth (API moderna)...")
            Log.d("AudioService", "Using modern Bluetooth SCO method (setCommunicationDevice)")
            
            // Verificar permiso BLUETOOTH_CONNECT (requerido en Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    sendLogMessage("⚠️ Falta permiso BLUETOOTH_CONNECT")
                    Log.w("AudioService", "BLUETOOTH_CONNECT permission not granted, trying legacy method")
                    // Intentar con método legacy como fallback
                    return startBluetoothScoLegacy()
                }
            }
            
            // CRÍTICO: Establecer modo de comunicación ANTES de buscar dispositivos
            val previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            sendLogMessage("📞 Modo audio: COMUNICACIÓN")
            Log.d("AudioService", "Audio mode set to MODE_IN_COMMUNICATION (previous: $previousMode)")
            
            // Buscar dispositivo Bluetooth SCO (el único que soporta micrófono)
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            var bluetoothDevice: AudioDeviceInfo? = null
            
            sendLogMessage("📋 Dispositivos de entrada: ${inputDevices.size}")
            Log.d("AudioService", "Searching ${inputDevices.size} input devices for Bluetooth SCO...")
            
            // Listar todos los dispositivos en la UI
            for (device in inputDevices) {
                val type = device.type
                val name = device.productName?.toString() ?: "Unknown"
                val hasMic = device.isSource
                val typeName = when (type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "MIC_INTERNO"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO ✓"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "CABLE"
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                    else -> "TIPO_$type"
                }
                
                sendLogMessage("  • $name ($typeName)")
                Log.d("AudioService", "Input device: type=$type (SCO=${AudioDeviceInfo.TYPE_BLUETOOTH_SCO}), name=$name, isSource=$hasMic")
                
                // SOLO TYPE_BLUETOOTH_SCO soporta micrófono bidireccional
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    bluetoothDevice = device
                    sendLogMessage("✅ Encontrado SCO: $name")
                    Log.d("AudioService", "✓ Found Bluetooth SCO device: $name")
                    break
                }
            }
            
            // Si no encontramos SCO en inputs, buscar en outputs
            if (bluetoothDevice == null) {
                val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                sendLogMessage("🔍 Buscando en salidas: ${outputDevices.size}")
                Log.d("AudioService", "Searching ${outputDevices.size} output devices for Bluetooth SCO...")
                for (device in outputDevices) {
                    val type = device.type
                    val name = device.productName?.toString() ?: "Unknown"
                    Log.d("AudioService", "Output device: type=$type, name=$name")
                    
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        sendLogMessage("  • $name (BT_SCO salida)")
                        for (inputDevice in inputDevices) {
                            if (inputDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                                bluetoothDevice = inputDevice
                                sendLogMessage("✅ SCO entrada encontrado")
                                Log.d("AudioService", "✓ Found matching SCO input device: ${inputDevice.productName}")
                                break
                            }
                        }
                        if (bluetoothDevice != null) break
                    }
                }
            }
            
            if (bluetoothDevice != null) {
                val deviceName = bluetoothDevice.productName?.toString() ?: "Bluetooth"
                sendLogMessage("⚙️ Configurando dispositivo: $deviceName")
                
                val result = audioManager.setCommunicationDevice(bluetoothDevice)
                if (result) {
                    bluetoothCommunicationDevice = bluetoothDevice
                    isBluetoothScoOn = true
                    sendLogMessage("🎧 ¡Micrófono Bluetooth ACTIVADO! ($deviceName)")
                    Log.d("AudioService", "✓ Bluetooth communication device set successfully: $deviceName")
                    sendMicrophoneChanged(deviceName)
                    return true
                } else {
                    sendLogMessage("⚠️ setCommunicationDevice falló, probando método legacy...")
                    Log.w("AudioService", "setCommunicationDevice failed, trying legacy startBluetoothSco")
                    audioManager.mode = previousMode
                    
                    // Fallback: intentar con método legacy
                    return startBluetoothScoLegacy()
                }
            } else {
                sendLogMessage("❌ No hay dispositivo Bluetooth SCO disponible")
                sendLogMessage("💡 Asegúrate que los auriculares están conectados como 'Audio para llamadas'")
                Log.w("AudioService", "✗ No Bluetooth SCO device found")
                audioManager.mode = previousMode
                return false
            }
        } catch (e: Exception) {
            Log.e("AudioService", "Error in modern Bluetooth SCO: ${e.message}", e)
            sendLogMessage("❌ Error: ${e.message}")
            return false
        }
    }
    
    /**
     * Método legacy para Android 11 y anteriores usando startBluetoothSco()
     */
    @Suppress("DEPRECATION")
    private fun startBluetoothScoLegacy(): Boolean {
        sendLogMessage("🔍 Buscando micrófono Bluetooth (API legacy)...")
        
        // Verificar si hay dispositivo BT conectado con micrófono
        val scoAvailable = audioManager.isBluetoothScoAvailableOffCall
        sendLogMessage("📋 SCO disponible: ${if (scoAvailable) "SÍ" else "NO"}")
        
        if (scoAvailable) {
            Log.d("AudioService", "Bluetooth SCO available (legacy), starting...")
            
            // Registrar receiver para saber cuando SCO está listo
            registerBluetoothScoReceiver()
            
            try {
                // CRÍTICO: Establecer modo de comunicación ANTES de iniciar SCO
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                sendLogMessage("📞 Modo audio: COMUNICACIÓN")
                Log.d("AudioService", "Audio mode set to MODE_IN_COMMUNICATION (legacy)")
                
                // Iniciar SCO
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                isBluetoothScoOn = true
                
                sendLogMessage("🎧 Activando micrófono Bluetooth (esperando conexión)...")
                return true
            } catch (e: Exception) {
                Log.e("AudioService", "Error starting Bluetooth SCO: ${e.message}", e)
                sendLogMessage("❌ Error SCO: ${e.message}")
                audioManager.mode = AudioManager.MODE_NORMAL
                isBluetoothScoOn = false
                return false
            }
        }
        
        sendLogMessage("❌ Bluetooth SCO no disponible")
        sendLogMessage("💡 Verifica conexión de auriculares Bluetooth")
        Log.d("AudioService", "Bluetooth SCO not available (legacy)")
        return false
    }
    
    /**
     * Registra un BroadcastReceiver para escuchar cambios en el estado de SCO.
     */
    private fun registerBluetoothScoReceiver() {
        if (bluetoothScoReceiver != null) {
            Log.d("AudioService", "Bluetooth SCO receiver already registered")
            return
        }
        
        bluetoothScoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR
                )
                
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.d("AudioService", "Bluetooth SCO CONNECTED")
                        sendLogMessage("🎧 Micrófono Bluetooth conectado")
                        isBluetoothScoOn = true
                        sendMicrophoneChanged("Auriculares Bluetooth")
                        
                        if (pendingRecordingAfterSco) {
                            pendingRecordingAfterSco = false
                            Log.d("AudioService", "Starting recording with Bluetooth mic")
                            startRecordingInternal()
                        }
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Log.d("AudioService", "Bluetooth SCO DISCONNECTED")
                        isBluetoothScoOn = false
                        bluetoothCommunicationDevice = null
                        
                        // CRÍTICO: Restaurar MODE_NORMAL para que los botones de media funcionen
                        // Sin esto, el modo queda en IN_COMMUNICATION e interfiere con la detección
                        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                            audioManager.mode = AudioManager.MODE_NORMAL
                            Log.d("AudioService", "Audio mode restored to MODE_NORMAL (SCO disconnected)")
                        }
                        
                        if (headsetControlEnabled) {
                            sendMicrophoneChanged(null) // Actualizar UI
                        }
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        Log.e("AudioService", "Bluetooth SCO ERROR")
                        isBluetoothScoOn = false
                        sendLogMessage("⚠️ Error con micrófono Bluetooth")
                        
                        // Si estaba esperando para grabar, usar mic del dispositivo
                        if (pendingRecordingAfterSco) {
                            pendingRecordingAfterSco = false
                            sendLogMessage("⚠️ Usando micrófono del dispositivo")
                            startRecordingInternal()
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothScoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(bluetoothScoReceiver, filter)
            }
            Log.d("AudioService", "Bluetooth SCO receiver registered")
        } catch (e: Exception) {
            Log.e("AudioService", "Error registering SCO receiver: ${e.message}", e)
            bluetoothScoReceiver = null
        }
    }
    
    /**
     * Detiene Bluetooth SCO y limpia el receiver.
     * Usa clearCommunicationDevice() en Android 12+ y stopBluetoothSco() en versiones anteriores.
     */
    @Suppress("DEPRECATION")
    private fun stopBluetoothSco() {
        if (isBluetoothScoOn) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+: limpiar dispositivo de comunicación
                    audioManager.clearCommunicationDevice()
                    Log.d("AudioService", "Bluetooth communication device cleared")
                } else {
                    // Android 11 y anteriores: detener SCO
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    Log.d("AudioService", "Bluetooth SCO stopped (legacy)")
                }
                
                // CRÍTICO: Restaurar modo de audio normal
                audioManager.mode = AudioManager.MODE_NORMAL
                Log.d("AudioService", "Audio mode restored to MODE_NORMAL")
                
                isBluetoothScoOn = false
                bluetoothCommunicationDevice = null
            } catch (e: Exception) {
                Log.e("AudioService", "Error stopping SCO: ${e.message}", e)
            }
        }
        
        bluetoothScoReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d("AudioService", "Bluetooth SCO receiver unregistered")
            } catch (e: Exception) {
                Log.e("AudioService", "Error unregistering SCO receiver: ${e.message}", e)
            }
            bluetoothScoReceiver = null
        }
        
        pendingRecordingAfterSco = false
    }

    private fun startRecording() {
        if (isRecording) {
            sendLogMessage(getString(R.string.already_recording))
            Log.w("AudioService", getString(R.string.already_recording))
            return
        }
        
        Log.d("AudioService", "startRecording() called, headsetControlEnabled=$headsetControlEnabled")
        
        if (headsetControlEnabled) {
            // Intentar activar SCO SIN cambiar modo de audio
            // Esto podría permitir usar mic BT sin bloquear botones
            activateScoWithoutModeChange()
        }
        
        startRecordingInternal()
    }
    
    /**
     * Activa Bluetooth SCO sin cambiar el modo de audio a MODE_IN_COMMUNICATION.
     * Esto es un intento de usar el micrófono BT sin bloquear los eventos de botones.
     */
    @Suppress("DEPRECATION")
    private fun activateScoWithoutModeChange() {
        try {
            Log.d("AudioService", "Activating SCO WITHOUT mode change")
            sendLogMessage("🔵 Activando micrófono Bluetooth...")
            
            // Solo llamar startBluetoothSco, NO cambiar audioManager.mode
            if (!audioManager.isBluetoothScoOn) {
                audioManager.startBluetoothSco()
                Log.d("AudioService", "startBluetoothSco() called")
                
                // Esperar un poco para que SCO se active
                Thread.sleep(300)
                
                if (audioManager.isBluetoothScoOn) {
                    sendLogMessage("✅ SCO activado (modo: ${audioManager.mode})")
                    Log.d("AudioService", "SCO activated, mode=${audioManager.mode}")
                    isBluetoothScoOn = true
                } else {
                    sendLogMessage("⚠️ SCO no se activó")
                    Log.d("AudioService", "SCO did not activate")
                }
            } else {
                sendLogMessage("✅ SCO ya estaba activo")
                isBluetoothScoOn = true
            }
        } catch (e: Exception) {
            Log.e("AudioService", "Error activating SCO: ${e.message}", e)
            sendLogMessage("❌ Error SCO: ${e.message}")
        }
    }
    
    /**
     * Busca un micrófono Bluetooth SCO disponible.
     * Retorna null si no hay ninguno conectado.
     */
    private fun findBluetoothMicrophone(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        Log.d("AudioService", "=== Searching for Bluetooth microphone ===")
        for (device in devices) {
            Log.d("AudioService", "Input device: ${device.productName}, type=${device.type}")
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                Log.d("AudioService", "Found Bluetooth SCO microphone: ${device.productName}")
                return device
            }
        }
        Log.d("AudioService", "No Bluetooth SCO microphone found")
        return null
    }
    
    /**
     * Inicia la grabación de audio.
     * Si hay micrófono Bluetooth disponible, intenta usarlo con setPreferredDevice.
     * NO cambia el modo de audio a MODE_IN_COMMUNICATION para mantener los botones activos.
     */
    @Suppress("DEPRECATION")
    private fun startRecordingInternal() {
        try {
            sendLogMessage(getString(R.string.starting_recording))
            Log.d("AudioService", "startRecordingInternal() - starting")
            try {
                // NO llamar requestAudioFocus aquí - el enableHeadsetControlMode ya lo hizo
                // y no queremos interferir con el silent playback
                
                // Seleccionar fuente de audio según estado de SCO
                val audioSource = if (isBluetoothScoOn) {
                    sendLogMessage("🎤 Usando VOICE_COMMUNICATION (BT)")
                    Log.d("AudioService", "Using VOICE_COMMUNICATION source for Bluetooth")
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    sendLogMessage("🎤 Usando micrófono del dispositivo")
                    Log.d("AudioService", "Using MIC source for device microphone")
                    MediaRecorder.AudioSource.MIC
                }
                
                recorder = MediaRecorder().apply {
                    setAudioSource(audioSource)
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    setOutputFile(getAudioFile().absolutePath)
                    
                    // Si SCO está activo, intentar setPreferredDevice al mic BT
                    if (isBluetoothScoOn) {
                        findBluetoothMicrophone()?.let { btMic ->
                            Log.d("AudioService", "Setting preferred device: ${btMic.productName}")
                            setPreferredDevice(btMic)
                        }
                    }
                    
                    prepare()
                    start()
                }
                isRecording = true
                Log.d("AudioService", "Recording started, isRecording=$isRecording, scoOn=$isBluetoothScoOn")
                
                // Verificar dispositivo activo
                recorder?.activeRecordingConfiguration?.let { config ->
                    val device = config.audioDevice
                    sendLogMessage("📍 Grabando: ${device?.productName ?: "Desconocido"}")
                    Log.d("AudioService", "Active device: ${device?.productName}, type=${device?.type}")
                }
                
                // Update notification to reflect recording state
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(1, createNotification())
                    
                // Broadcast state change with explicit package name
                val intent = Intent(ACTION_RECORDING_STARTED)
                intent.setPackage(packageName) // Make the intent explicit to ensure delivery
                sendBroadcast(intent)
                Log.d("AudioService", "Broadcasting ACTION_RECORDING_STARTED to package $packageName")
                sendLogMessage(getString(R.string.recording_started))
                
                // Feedback auditivo para indicar inicio de grabación
                playRecordingStartFeedback()
            } catch (e: IllegalStateException) {
                // Specific error for audio source issues
                sendLogMessage(getString(R.string.microphone_access_error, e.message))
                Log.e("AudioService", getString(R.string.microphone_access_error, e.message), e)
                sendErrorMessage(getString(R.string.microphone_permission_error))
                recorder?.release()
                recorder = null
                isRecording = false
                abandonAudioFocus()
                stopBluetoothSco()  // Limpiar SCO si hay error
                signalProcessingComplete()
            } catch (e: Exception) {
                // Generic error
                sendLogMessage(getString(R.string.error_starting_recording, e.message))
                Log.e("AudioService", getString(R.string.error_starting_recording, e.message), e)
                sendErrorMessage(getString(R.string.error_starting_recording, e.message))
                recorder?.release()
                recorder = null
                isRecording = false
                abandonAudioFocus()
                stopBluetoothSco()  // Limpiar SCO si hay error
                signalProcessingComplete()
            }
        } catch (e: Exception) {
            // Handle any unexpected errors
            sendLogMessage(getString(R.string.error_unexpected_starting_recording, e.message))
            Log.e("AudioService", getString(R.string.error_unexpected_starting_recording, e.message), e)
            recorder?.release()
            recorder = null
            isRecording = false
            abandonAudioFocus()
            stopBluetoothSco()  // Limpiar SCO si hay error
            signalProcessingComplete()
        }
    }

    private fun stopRecordingAndSend() {
        if (!isRecording) {
            sendLogMessage(getString(R.string.no_recording_in_progress))
            Log.w("AudioService", getString(R.string.no_recording_in_progress))
            return
        }
        try {
            sendLogMessage(getString(R.string.stopping_recording))
            recorder?.stop()
            sendLogMessage(getString(R.string.recording_stopped))
        } catch (e: Exception) {
            sendLogMessage(getString(R.string.recording_stop_error, e.message))
            Log.e("AudioService", getString(R.string.recording_stop_error, e.message))
        } finally {
            recorder?.release()
            recorder = null
            isRecording = false
            abandonAudioFocus()
            
            // Update notification
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, createNotification())
                
            // Broadcast state change with explicit package name
            val intent = Intent(ACTION_RECORDING_STOPPED)
            intent.setPackage(packageName) // Make the intent explicit to ensure delivery
            sendBroadcast(intent)
            Log.d("AudioService", "Broadcasting ACTION_RECORDING_STOPPED to package $packageName")
            sendLogMessage(getString(R.string.recording_stopped_broadcast))
            
            // Feedback auditivo para indicar fin de grabación
            playRecordingStopFeedback()
            
            // NO detener SCO aquí - se mantiene activo mientras el modo manos libres esté activo
            // SCO solo se detiene cuando se desactiva el modo manos libres
        }

        // Enviar archivo al servidor
        val audioFile = getAudioFile()
        sendAudioFileInfo(audioFile, "recording")
        serviceScope.launch(Dispatchers.IO) {
            sendAudioOverWebSocket(audioFile)
        }
    }

    private fun stopRecordingWithoutSending() {
        if (!isRecording) {
            sendLogMessage(getString(R.string.no_recording_in_progress_for_cancel))
            return
        }
        try {
            sendLogMessage(getString(R.string.canceling_recording))
            recorder?.stop()
            sendLogMessage(getString(R.string.recording_canceled))
        } catch (e: Exception) {
            sendLogMessage(getString(R.string.error_canceling_recording, e.message))
            Log.e("AudioService", getString(R.string.error_canceling_recording, e.message))
        } finally {
            recorder?.release()
            recorder = null
            isRecording = false
            abandonAudioFocus()
            
            // Update notification
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, createNotification())
                
            // Broadcast state change with explicit package name
            val intent = Intent(ACTION_RECORDING_STOPPED)
            intent.setPackage(packageName) // Make the intent explicit to ensure delivery
            sendBroadcast(intent)
            Log.d("AudioService", "Broadcasting ACTION_RECORDING_STOPPED (canceled) to package $packageName")
            sendLogMessage(getString(R.string.recording_canceled_broadcast))
            
            // NO detener SCO aquí - se mantiene activo mientras el modo manos libres esté activo
            // SCO solo se detiene cuando se desactiva el modo manos libres
            
            // Signal that processing is complete (in this case, canceled)
            signalProcessingComplete()
        }
        
        // No need to send the audio file since the recording was canceled
    }

    private fun sendAudioOverWebSocket(file: File) {
        // Log with distinctive tag for better capture
        Log.d("AudioSending", "Inicio de proceso de envío de audio")
        
        // Verificar tamaño del archivo antes de leerlo
        val maxFileSize = 5 * 1024 * 1024 // 5 MB, por ejemplo
        if (file.length() > maxFileSize) {
            sendLogMessage(getString(R.string.file_too_large, file.length()))
            Log.e("AudioSending", getString(R.string.file_too_large, file.length()))
            return
        }

        // In test mode, simulate sending and receiving audio
        if (testMode) {
            sendLogMessage(getString(R.string.test_mode_simulating))
            Log.d("AudioSending", getString(R.string.test_mode_simulating))
            
            // Use coroutines to simulate network delay
            serviceScope.launch(Dispatchers.Main) {
                delay(2000) // Simulate 2 seconds of network delay
                sendLogMessage(getString(R.string.test_mode_sent))
                Log.d("AudioSending", getString(R.string.test_mode_sent))
                
                // Simulate receiving a response by copying the input file to the response file
                delay(1500) // Add more delay for "server processing"
                Log.d("AudioSending", getString(R.string.test_mode_creating))
                createTestResponse(file)
            }
            return
        }

        // Send audio using REST API
        serviceScope.launch(Dispatchers.IO) {
            sendAudioViaRestApi(file)
        }
    }

    /**
     * Sends audio to the server using a REST API call instead of WebSockets
     */
    private fun sendAudioViaRestApi(audioFile: File) {
        try {
            sendLogMessage(getString(R.string.sending_audio_file, audioFile.length()))
            sendLogMessage(getString(R.string.using_whisper_model, whisperModel))
            
            // Create multipart request body with the audio file in the 'audio_file' field
            val mediaType = "audio/ogg".toMediaTypeOrNull()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio", 
                    audioFile.name, 
                    audioFile.asRequestBody(mediaType)
                )
                // Optional parameters for updated API
                .addFormDataPart("language", "es")  // Default to Spanish
                .addFormDataPart("model", whisperModel)   // Use configured model 
                .addFormDataPart("capture_screenshot", "false") // Don't capture screenshots by default
                .build()
            
            // Build the request with the correct endpoint
            val request = Request.Builder()
                .url("$apiBaseUrl/voice-command")  // Correct endpoint
                .post(requestBody)
                .header("User-Agent", "Android Audio Client")
                .build()
            
            // Execute the request
            sendLogMessage(getString(R.string.sending_to_rest))
            Log.d("AudioSending", "Enviando audio a: ${request.url}")
            
            try {
                // Execute the request synchronously (we're already in a background thread)
                val response = httpClient.newCall(request).execute()
                
                try {
                    if (response.isSuccessful) {
                        sendLogMessage(getString(R.string.audio_sent_successfully))
                        Log.d("AudioSending", "Respuesta exitosa: ${response.code}")
                        
                        // Get the response body as JSON
                        val responseBody = response.body
                        if (responseBody != null) {
                            val responseString = responseBody.string()
                            
                            // Process the JSON response on the main thread
                            serviceScope.launch(Dispatchers.Main) {
                                processJsonResponse(responseString)
                                // We don't signal completion here as it's done in processJsonResponse
                            }
                        } else {
                            sendLogMessage(getString(R.string.empty_response))
                            Log.e("AudioSending", "La respuesta del servidor está vacía")
                            sendErrorMessage(getString(R.string.empty_response))
                            // Send response received signal for empty response
                            sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                                putExtra(EXTRA_RESPONSE_MESSAGE, getString(R.string.empty_response))
                                putExtra(EXTRA_RESPONSE_SUCCESS, false)
                            })
                            signalProcessingComplete()
                        }
                    } else {
                        // Handle error response
                        sendLogMessage(getString(R.string.server_error, response.code, response.message))
                        Log.e("AudioSending", "Error HTTP: ${response.code} ${response.message}")
                        
                        // Log more detailed error info if available
                        val errorBody = response.body?.string() ?: "Sin cuerpo de respuesta"
                        Log.e("AudioSending", "Cuerpo de error: $errorBody")
                        sendLogMessage(getString(R.string.error_details, errorBody))
                        
                        // Special handling for 'No command detected' error
                        if (response.code == 400 || response.code == 422) {
                            try {
                                val errorJson = org.json.JSONObject(errorBody)
                                val errorMessage = errorJson.optString("error", "")
                                val status = errorJson.optString("status", "")
                                
                                // Check for the new error format
                                if (status == "error" && errorMessage.isNotEmpty()) {
                                    sendLogMessage(getString(R.string.warning, errorMessage))
                                    sendErrorMessage(errorMessage)
                                    // Send response received signal for error
                                    sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                                        putExtra(EXTRA_RESPONSE_MESSAGE, errorMessage)
                                        putExtra(EXTRA_RESPONSE_SUCCESS, false)
                                    })
                                    signalProcessingComplete()
                                    return
                                }
                                // Backward compatibility with old format
                                else if (errorMessage.contains("No command detected")) {
                                    sendLogMessage(getString(R.string.no_command_detected))
                                    
                                    // Use the specific error message method for UI feedback
                                    sendErrorMessage(getString(R.string.no_command_detected_message))
                                    
                                    // Display a toast notification to the user
                                    serviceScope.launch(Dispatchers.Main) {
                                        Toast.makeText(
                                            applicationContext,
                                            getString(R.string.no_command_detected_message),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        
                                        // Create a text response for this specific error
                                        createTextToSpeechResponse(
                                            getString(R.string.no_command_detected), 
                                            getString(R.string.no_command_detected_message)
                                        )
                                    }
                                    // Send response received signal for no command detected
                                    sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                                        putExtra(EXTRA_RESPONSE_MESSAGE, getString(R.string.no_command_detected_message))
                                        putExtra(EXTRA_RESPONSE_SUCCESS, false)
                                    })
                                    signalProcessingComplete()
                                    return
                                } else {
                                    // Other error cases
                                    sendErrorMessage(getString(R.string.server_error, errorMessage))
                                    // Send response received signal for other errors
                                    sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                                        putExtra(EXTRA_RESPONSE_MESSAGE, errorMessage)
                                        putExtra(EXTRA_RESPONSE_SUCCESS, false)
                                    })
                                    signalProcessingComplete()
                                }
                            } catch (e: Exception) {
                                // Error parsing the JSON, continue with normal error handling
                                Log.e("AudioSending", "Error parsing error response: ${e.message}")
                                sendErrorMessage(getString(R.string.error_processing_response, e.message))
                                // Send response received signal for parsing error
                                sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                                    putExtra(EXTRA_RESPONSE_MESSAGE, getString(R.string.error_processing_response, e.message))
                                    putExtra(EXTRA_RESPONSE_SUCCESS, false)
                                })
                                signalProcessingComplete()
                            }
                        } else {
                            // Generic HTTP error
                            sendErrorMessage(getString(R.string.http_error, response.code, response.message))
                            // Send response received signal for HTTP error
                            sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                                putExtra(EXTRA_RESPONSE_MESSAGE, getString(R.string.http_error, response.code, response.message))
                                putExtra(EXTRA_RESPONSE_SUCCESS, false)
                            })
                            signalProcessingComplete()
                        }
                    }
                } catch (e: IOException) {
                    // Handle network errors
                    sendLogMessage(getString(R.string.network_error, e.message))
                    Log.e("AudioSending", "Error de red", e)
                    sendErrorMessage(getString(R.string.network_error, e.message ?: getString(R.string.connection_failed)))
                    // Send response received signal for network error
                    sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                        putExtra(EXTRA_RESPONSE_MESSAGE, getString(R.string.network_error, e.message ?: getString(R.string.connection_failed)))
                        putExtra(EXTRA_RESPONSE_SUCCESS, false)
                    })
                    signalProcessingComplete()
                }
            } catch (e: Exception) {
                // Handle other exceptions
                sendLogMessage(getString(R.string.error_unexpected, e.javaClass.simpleName, e.message))
                Log.e("AudioSending", "Error inesperado", e)
                sendErrorMessage(getString(R.string.error_unexpected, e.message ?: e.javaClass.simpleName))
                // Send response received signal for unexpected error
                sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                    putExtra(EXTRA_RESPONSE_MESSAGE, getString(R.string.error_unexpected, e.message ?: e.javaClass.simpleName))
                    putExtra(EXTRA_RESPONSE_SUCCESS, false)
                })
                signalProcessingComplete()
            }
        } catch (e: Exception) {
            // Handle other exceptions
            sendLogMessage(getString(R.string.error_unexpected, e.javaClass.simpleName, e.message))
            Log.e("AudioSending", "Error inesperado", e)
            sendErrorMessage(getString(R.string.error_unexpected, e.message ?: e.javaClass.simpleName))
            // Send response received signal for outer exception
            sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                putExtra(EXTRA_RESPONSE_MESSAGE, getString(R.string.error_unexpected, e.message ?: e.javaClass.simpleName))
                putExtra(EXTRA_RESPONSE_SUCCESS, false)
            })
            signalProcessingComplete()
        }
    }
    
    /**
     * Process JSON response from the REST API
     */
    private fun processJsonResponse(jsonResponse: String) {
        try {
            sendLogMessage(getString(R.string.processing_json))
            Log.d("AudioResponse", "Respuesta JSON: $jsonResponse")
            
            // Parse JSON using Android's org.json
            val jsonObject = org.json.JSONObject(jsonResponse)
            
            // Check for success/error
            if (jsonObject.has("error")) {
                val errorMessage = jsonObject.getString("error")
                val status = jsonObject.optString("status", "")
                
                sendLogMessage(getString(R.string.server_returned_error, errorMessage))
                
                // Check if this is the new error format
                if (status == "error") {
                    sendErrorMessage(errorMessage)
                    // Send response received signal for error case too
                    sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                        putExtra(EXTRA_RESPONSE_MESSAGE, errorMessage)
                        putExtra(EXTRA_RESPONSE_SUCCESS, false)
                    })
                    signalProcessingComplete()
                    return
                }
                
                // Special handling for "No command detected" error
                if (errorMessage.contains("No command detected")) {
                    sendLogMessage(getString(R.string.no_command_detected))
                    
                    // Display a toast notification to the user
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.no_command_detected_message),
                        Toast.LENGTH_LONG
                    ).show()
                    
                    // Create a text response for this specific error
                    createTextToSpeechResponse(
                        getString(R.string.no_command_detected), 
                        getString(R.string.no_command_detected_message)
                    )
                }
                
                // Signal completion with error
                sendErrorMessage(errorMessage)
                // Send response received signal for error case too
                sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                    putExtra(EXTRA_RESPONSE_MESSAGE, errorMessage)
                    putExtra(EXTRA_RESPONSE_SUCCESS, false)
                })
                signalProcessingComplete()
                return
            }
            
            // Extract transcription and result
            val status = jsonObject.optString("status", "")
            val transcription = jsonObject.optString("transcription", "")
            val language = jsonObject.optString("language", "unknown")
            val steps = jsonObject.optInt("steps", 0)
            val result = jsonObject.optString("result", "")
            val screenSummary = jsonObject.optString("screen_summary", "").trim()
            val summaryFallback = jsonObject.optString("summary", "").trim()
            val success = jsonObject.optBoolean("success", true) // Extract success field from server
            
            // Check if translation was performed
            val isTranslated = jsonObject.optBoolean("translated", false)
            val translation = if (isTranslated) jsonObject.optString("translation", "") else ""
            
            // Log the response information
            val responseInfo = StringBuilder()
            responseInfo.append(getString(R.string.command_recognized, transcription))
            
            if (isTranslated) {
                responseInfo.append("\n").append(getString(R.string.translated_to, translation))
            }
            
            responseInfo.append("\n").append(getString(R.string.detected_language, language))
            responseInfo.append("\n").append(getString(R.string.steps_executed, steps))
            responseInfo.append("\n").append(getString(R.string.result, result))
            val summaryForUi = when {
                screenSummary.isNotEmpty() -> screenSummary
                summaryFallback.isNotEmpty() -> summaryFallback
                else -> ""
            }

            if (summaryForUi.isNotEmpty()) {
                responseInfo.append("\n").append(getString(R.string.screen_summary_label, summaryForUi))
            }
            
            sendLogMessage(responseInfo.toString())
            
            // Check if audio response is available
            val hasAudioResponse = jsonObject.optBoolean("audio_response_available", false)
            val responseMessage = if (summaryForUi.isNotEmpty()) summaryForUi else result
            
            if (hasAudioResponse) {
                sendLogMessage(getString(R.string.audio_response_available))
                // Launch a coroutine to download the audio in the background
                serviceScope.launch(Dispatchers.IO) {
                    downloadAudioResponse(transcription)
                    // Send response received signal
                    sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                        putExtra(EXTRA_RESPONSE_MESSAGE, responseMessage)
                        putExtra(EXTRA_RESPONSE_SUCCESS, success)
                        putExtra(EXTRA_SCREEN_SUMMARY, summaryForUi)
                    })
                    // Signal completion after downloading
                    signalProcessingComplete()
                }
            } else {
                // Create a text-to-speech response instead
                serviceScope.launch(Dispatchers.IO) {
                    createTextToSpeechResponse(transcription, responseMessage)
                    // Send response received signal
                    sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                        putExtra(EXTRA_RESPONSE_MESSAGE, responseMessage)
                        putExtra(EXTRA_RESPONSE_SUCCESS, success)
                        putExtra(EXTRA_SCREEN_SUMMARY, summaryForUi)
                    })
                    // Signal completion after TTS
                    signalProcessingComplete()
                }
            }
        } catch (e: Exception) {
            sendLogMessage(getString(R.string.error_processing_json, e.message))
            Log.e("AudioResponse", "Error al procesar respuesta JSON", e)
            sendErrorMessage(getString(R.string.error_processing_response, e.message))
            // Send response received signal for JSON processing error
            sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                putExtra(EXTRA_RESPONSE_MESSAGE, getString(R.string.error_processing_response, e.message))
                putExtra(EXTRA_RESPONSE_SUCCESS, false)
            })
            signalProcessingComplete()
        }
    }
    
    /**
     * Creates a text-to-speech response when the server doesn't provide audio
     */
    private fun createTextToSpeechResponse(title: String, message: String) {
        // Native TTS plays the response text; saving synthesized audio remains a future step.
        if (!audioPlaybackEnabled) {
            sendLogMessage(getString(R.string.audio_playback_disabled))
            return
        }
        
        if (testMode) {
            sendLogMessage(getString(R.string.test_mode_tts))
            
            // In test mode, simulate a delay and create a dummy response
            serviceScope.launch(Dispatchers.Main) {
                delay(1000) // Simulate TTS generation time
                
                // Use original recording as a placeholder for TTS response
                val responseFile = getResponseFile()
                val recordingFile = getAudioFile()
                
                if (recordingFile.exists() && recordingFile.length() > 0) {
                    recordingFile.copyTo(responseFile, overwrite = true)
                    
                    // Announce the response is ready
                    sendAudioFileInfo(responseFile, "response")
                    sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED).apply {
                        putExtra(EXTRA_RESPONSE_MESSAGE, getString(R.string.test_mode_tts_ready))
                        putExtra(EXTRA_RESPONSE_SUCCESS, true)
                    })
                    
                    sendLogMessage(getString(R.string.test_mode_tts_ready))
                }
            }
        } else {
            val responseText = message.ifBlank { title }
            serviceScope.launch(Dispatchers.Main) {
                val didSpeak = textToSpeechManager?.speak(responseText) ?: false
                if (didSpeak) {
                    sendLogMessage(getString(R.string.tts_speaking_response))
                } else {
                    sendLogMessage(getString(R.string.tts_not_ready))
                    sendTtsStatus(TTS_STATUS_ERROR)
                }
            }
            
            // No extra response broadcast here; processJsonResponse already notifies the UI.
        }
    }

    private fun playAudioResponse(audioBytes: ByteString) {
        // This method is no longer used with REST API approach
        // Keeping it for backward compatibility
        if (!audioPlaybackEnabled) {
            sendLogMessage(getString(R.string.audio_playback_disabled))
            return
        }
        if (audioBytes.size == 0) {
            sendLogMessage(getString(R.string.empty_response))
            return
        }
        
        // For binary data, convert to string and try to parse as JSON
        try {
            val jsonString = audioBytes.utf8()
            processJsonResponse(jsonString)
        } catch (e: Exception) {
            sendLogMessage(getString(R.string.error_processing_bytes_as_json, e.message))
            Log.e("AudioResponse", "Error al procesar bytes como JSON", e)
        }
    }

    private fun isOggFormat(data: ByteString): Boolean {
        // Comprobar "OggS"
        if (data.size < 4) return false
        return data[0] == 0x4F.toByte() &&
               data[1] == 0x67.toByte() &&
               data[2] == 0x67.toByte() &&
               data[3] == 0x53.toByte()
    }

    // Method to check if data is OGG format (for ByteArray)
    private fun isOggFormat(data: ByteArray): Boolean {
        // Comprobar "OggS"
        if (data.size < 4) return false
        return data[0] == 0x4F.toByte() &&
               data[1] == 0x67.toByte() &&
               data[2] == 0x67.toByte() &&
               data[3] == 0x53.toByte()
    }

    private fun playLastResponse() {
        // Por si el usuario quiere reproducir la última respuesta almacenada
        if (!audioPlaybackEnabled) {
            sendLogMessage(getString(R.string.audio_playback_disabled))
            return
        }
        val responseFile = getResponseFile()
        
        // Log more detailed file information for debugging
        sendLogMessage(getString(R.string.trying_to_play, responseFile.absolutePath))
        
        if (!responseFile.exists()) {
            sendLogMessage(getString(R.string.no_previous_response))
            Toast.makeText(this, getString(R.string.no_audio_available), Toast.LENGTH_SHORT).show()
            return
        }
        
        if (responseFile.length() == 0L) {
            sendLogMessage(getString(R.string.response_file_empty))
            Toast.makeText(this, getString(R.string.audio_file_corrupt), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            sendLogMessage(getString(R.string.playing_saved_response, formatFileSize(responseFile.length())))
            sendAudioFileInfo(responseFile, "response")
            
            player?.release()
            player = ExoPlayer.Builder(this).build().apply {
                val mediaItem = MediaItem.fromUri(responseFile.path)
                setMediaItem(mediaItem)
                prepare()
                play()
            }
            
            // Send a broadcast that playback has started
            sendBroadcast(Intent(ACTION_RESPONSE_PLAYED).apply {
                putExtra(EXTRA_RESPONSE_MESSAGE, getString(R.string.playing_audio_response))
                putExtra(EXTRA_RESPONSE_SUCCESS, true)
            })
        } catch (e: Exception) {
            sendLogMessage(getString(R.string.error_playing_audio, e.message))
            Toast.makeText(this, getString(R.string.error_playing_audio, e.message), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun sendLogMessage(message: String) {
        // Log to LogCat
        Log.d("AudioService", message)
        
        // Format message with timestamp
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val formattedMessage = "[$timestamp] $message"
        
        // Broadcast the message to the activity
        val intent = Intent(ACTION_LOG_MESSAGE).apply {
            putExtra(EXTRA_LOG_MESSAGE, formattedMessage)
        }
        sendBroadcast(intent)
    }
    
    private fun sendAudioFileInfo(file: File, type: String) {
        // Verify the file exists and has content before sending info
        if (!file.exists()) {
            sendLogMessage(getString(R.string.cannot_send_file_info))
            return
        }
        
        if (file.length() == 0L) {
            sendLogMessage(getString(R.string.file_empty_warning, file.name))
        }
        
        val intent = Intent(ACTION_AUDIO_FILE_INFO).apply {
            putExtra(EXTRA_AUDIO_FILE_PATH, file.absolutePath)
            putExtra(EXTRA_AUDIO_FILE_SIZE, file.length())
            putExtra(EXTRA_AUDIO_TYPE, type)
            // We could calculate real duration with MediaMetadataRetriever
            // but for simplicity, we'll estimate based on file size
            val estimatedDuration = if (file.length() > 0) (file.length() / 1024) * 500 else 0 // rough estimate: 500ms per KB
            putExtra(EXTRA_AUDIO_DURATION, estimatedDuration)
        }
        sendBroadcast(intent)
        sendLogMessage(getString(R.string.audio_available, type, formatFileSize(file.length()), file.absolutePath))
    }
    
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> getString(R.string.bytes, size)
            size < 1024 * 1024 -> getString(R.string.kb, size / 1024)
            else -> String.format(getString(R.string.mb), size / (1024.0 * 1024.0))
        }
    }

    // Method to directly check if we have a valid response file
    private fun hasValidResponseFile(): Boolean {
        val file = getResponseFile()
        return file.exists() && file.length() > 0
    }

    /**
     * Creates a test response file for testing when we can't connect to a real server
     */
    private fun createTestResponse(sourceFile: File) {
        try {
            val responseFile = getResponseFile()
            
            // If we have a source file with content, let's copy it (with modification to simulate processing)
            if (sourceFile.exists() && sourceFile.length() > 0) {
                sendLogMessage(getString(R.string.test_mode_response))
                
                // Copy the file with modification (just to make it different)
                sourceFile.copyTo(responseFile, overwrite = true)
                
                // Now let's announce we received a response
                sendAudioFileInfo(responseFile, "response")
                sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED))
                
                sendLogMessage(getString(R.string.test_mode_response_created, responseFile.length()))
            } else {
                // Create a dummy file with some content if source file doesn't exist
                sendLogMessage(getString(R.string.test_mode_empty_response))
                
                // Even in test mode, we need a valid OGG file header "OggS"
                val dummyHeader = byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0x00)
                
                FileOutputStream(responseFile).use { outputStream ->
                    outputStream.write(dummyHeader)
                    // Add some random data
                    for (i in 0 until 1024) {
                        outputStream.write(i % 255)
                    }
                }
                
                // Now let's announce we received a response
                sendAudioFileInfo(responseFile, "response")
                sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED))
                
                sendLogMessage(getString(R.string.test_mode_empty_response_created, responseFile.length()))
            }
        } catch (e: IOException) {
            sendLogMessage(getString(R.string.error_processing_response, e.message))
            Log.e("AudioResponse", "Error creating test response", e)
        }
    }

    /**
     * Downloads audio response from the server when available
     */
    private fun downloadAudioResponse(transcription: String) {
        try {
            sendLogMessage(getString(R.string.downloading_audio))
            
            // Build the request for downloading the audio response
            val request = Request.Builder()
                .url("$apiBaseUrl/get-audio-response")  // Endpoint for audio download
                .get()
                .header("User-Agent", "Android Audio Client")
                .build()
            
            // Execute the request
            try {
                val response = httpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body
                    if (responseBody != null) {
                        val responseBytes = responseBody.bytes()
                        
                        // Check if the response is audio data
                        if (isAudioData(responseBytes)) {
                            // Save the audio file
                            val responseFile = getResponseFile()
                            responseFile.writeBytes(responseBytes)
                            
                            // Log and send info
                            sendLogMessage(getString(R.string.downloaded_audio, responseBytes.size))
                            sendAudioFileInfo(responseFile, "response")
                            
                            // Play the audio on the main thread
                            serviceScope.launch(Dispatchers.Main) {
                                playDownloadedAudio(responseFile)
                            }
                        } else {
                            sendLogMessage(getString(R.string.invalid_audio_response))
                            // Fall back to text-to-speech
                            createTextToSpeechResponse(transcription, getString(R.string.command_executed))
                        }
                    } else {
                        // Empty response
                        sendLogMessage(getString(R.string.empty_response))
                        createTextToSpeechResponse(transcription, getString(R.string.sorry_no_audio))
                    }
                } else {
                    // Generic HTTP error
                    sendErrorMessage(getString(R.string.http_error, response.code, response.message))
                }
            } catch (e: IOException) {
                // Handle network errors
                sendLogMessage(getString(R.string.network_error, e.message))
                Log.e("AudioSending", "Error de red", e)
                sendErrorMessage(getString(R.string.network_error, e.message ?: getString(R.string.connection_failed)))
                createTextToSpeechResponse(transcription, getString(R.string.command_executed_network_error))
            }
        } catch (e: Exception) {
            // Handle any other exceptions
            sendLogMessage(getString(R.string.error_downloading_audio, e.message))
            Log.e("AudioResponse", "Error descargando audio", e)
            createTextToSpeechResponse(transcription, getString(R.string.command_executed))
        } finally {
            // Always signal completion regardless of what happened
            signalProcessingComplete()
        }
    }
    
    /**
     * Plays a downloaded audio file
     */
    private fun playDownloadedAudio(audioFile: File) {
        if (!audioPlaybackEnabled) {
            sendLogMessage(getString(R.string.audio_playback_disabled))
            return
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            sendLogMessage(getString(R.string.error_downloaded_file))
            return
        }
        
        try {
            // Play the audio
            player?.release()
            player = ExoPlayer.Builder(this).build().apply {
                val mediaItem = MediaItem.fromUri(audioFile.path)
                setMediaItem(mediaItem)
                prepare()
                play()
            }
            
            sendLogMessage(getString(R.string.playing_audio_response))
            sendAppBroadcast(Intent(ACTION_RESPONSE_RECEIVED))
        } catch (e: Exception) {
            sendLogMessage(getString(R.string.error_playing_audio, e.message))
        }
    }
    
    /**
     * Checks if the byte array is likely audio data by looking for common audio file headers
     */
    private fun isAudioData(data: ByteArray): Boolean {
        if (data.size < 4) return false
        
        // Check for OGG header "OggS"
        val isOgg = data[0] == 0x4F.toByte() && 
                    data[1] == 0x67.toByte() && 
                    data[2] == 0x67.toByte() && 
                    data[3] == 0x53.toByte()
        
        // Check for MP3 header (most MP3s start with ID3 or 0xFF 0xFB)
        val isMp3 = (data[0] == 0x49.toByte() && 
                    data[1] == 0x44.toByte() && 
                    data[2] == 0x33.toByte()) || 
                    (data[0] == 0xFF.toByte() && 
                    (data[1] == 0xFB.toByte() || data[1] == 0xF3.toByte() || data[1] == 0xF2.toByte()))
        
        // Check for WAV header "RIFF"
        val isWav = data[0] == 0x52.toByte() && 
                    data[1] == 0x49.toByte() && 
                    data[2] == 0x46.toByte() && 
                    data[3] == 0x46.toByte()
        
        return isOgg || isMp3 || isWav
    }

    /**
     * Sends a specialized error message that will trigger the UI to hide the progress indicator
     */
    private fun sendErrorMessage(message: String) {
        val errorMessage = "ERROR: $message"
        sendLogMessage(errorMessage)
        
        // This specific broadcast could be used by the UI to know when to hide the progress indicator
        val intent = Intent(ACTION_LOG_MESSAGE).apply {
            putExtra(EXTRA_LOG_MESSAGE, errorMessage)
        }
        sendAppBroadcast(intent)
    }

    /**
     * Signals to the UI that processing is complete (success or failure)
     * This can be used to hide progress indicators
     */
    private fun signalProcessingComplete() {
        sendAppBroadcast(Intent(ACTION_PROCESSING_COMPLETED))
    }

    private fun sendAppBroadcast(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun sendTtsStatus(status: String) {
        sendAppBroadcast(Intent(ACTION_TTS_STATUS).apply {
            putExtra(EXTRA_TTS_STATUS, status)
        })
    }

    private fun applyTtsSettings() {
        val didApply = textToSpeechManager?.updateConfig(ttsLanguage, ttsRate, ttsPitch) ?: false
        if (!didApply) {
            sendLogMessage(getString(R.string.tts_config_failed, ttsLanguage))
        }
    }

    /**
     * Loads server settings from SharedPreferences
     */
    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        serverIp = prefs.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP) ?: DEFAULT_SERVER_IP
        serverPort = prefs.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT)
        whisperModel = prefs.getString(KEY_WHISPER_MODEL, DEFAULT_WHISPER_MODEL) ?: DEFAULT_WHISPER_MODEL
        responseTimeout = prefs.getInt(KEY_RESPONSE_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT)
        ttsLanguage = prefs.getString(KEY_TTS_LANGUAGE, DEFAULT_TTS_LANGUAGE) ?: DEFAULT_TTS_LANGUAGE
        ttsRate = prefs.getFloat(KEY_TTS_RATE, DEFAULT_TTS_RATE)
        ttsPitch = prefs.getFloat(KEY_TTS_PITCH, DEFAULT_TTS_PITCH)
        audioPlaybackEnabled = prefs.getBoolean(
            KEY_AUDIO_PLAYBACK_ENABLED,
            DEFAULT_AUDIO_PLAYBACK_ENABLED
        )
        headsetFeedbackEnabled = prefs.getBoolean(
            KEY_HEADSET_FEEDBACK_ENABLED,
            DEFAULT_HEADSET_FEEDBACK_ENABLED
        )
        sendLogMessage(getString(R.string.configuration_loaded, serverIp, serverPort, whisperModel))
    }
    
    /**
     * Updates and saves server settings
     */
    private fun updateServerSettings(
        ip: String,
        port: Int,
        model: String? = null,
        timeout: Int? = null,
        language: String? = null,
        rate: Float? = null,
        pitch: Float? = null,
        audioPlaybackEnabled: Boolean? = null,
        headsetFeedbackEnabled: Boolean? = null
    ) {
        serverIp = ip
        serverPort = port
        if (model != null) {
            whisperModel = model
        }
        if (timeout != null) {
            responseTimeout = timeout
        }
        if (language != null) {
            ttsLanguage = language
        }
        if (rate != null) {
            ttsRate = rate
        }
        if (pitch != null) {
            ttsPitch = pitch
        }
        if (audioPlaybackEnabled != null) {
            this.audioPlaybackEnabled = audioPlaybackEnabled
        }
        if (headsetFeedbackEnabled != null) {
            this.headsetFeedbackEnabled = headsetFeedbackEnabled
        }
        
        // Save to SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_SERVER_IP, serverIp)
            putInt(KEY_SERVER_PORT, serverPort)
            putString(KEY_WHISPER_MODEL, whisperModel)
            putInt(KEY_RESPONSE_TIMEOUT, responseTimeout)
            putString(KEY_TTS_LANGUAGE, ttsLanguage)
            putFloat(KEY_TTS_RATE, ttsRate)
            putFloat(KEY_TTS_PITCH, ttsPitch)
            putBoolean(KEY_AUDIO_PLAYBACK_ENABLED, this@AudioService.audioPlaybackEnabled)
            putBoolean(KEY_HEADSET_FEEDBACK_ENABLED, this@AudioService.headsetFeedbackEnabled)
            apply()
        }

        applyTtsSettings()
        
        sendLogMessage(getString(R.string.configuration_updated, serverIp, serverPort, whisperModel))
    }
    
    /**
     * Tests connection to the configured server
     */
    private fun testServerConnection() {
        sendLogMessage(getString(R.string.testing_connection_to, apiBaseUrl))
        Log.d("ConnectionTest", "Iniciando prueba de conexión")
        
        // Use coroutines to test connection in background
        serviceScope.launch(Dispatchers.IO) {
            var success = false
            var message = "Error de conexión"
            
            try {
                // First try the health endpoint
                var request = Request.Builder()
                    .url("$apiBaseUrl/health")
                    .get()
                    .header("User-Agent", "Android Audio Client")
                    .build()
                
                Log.d("ConnectionTest", "Intentando endpoint: ${request.url}")
                
                // Execute the request with a shorter timeout
                val client = NetworkUtils.createTrustAllClient(
                    connectTimeout = 5,
                    readTimeout = 5,
                    writeTimeout = 5
                )
                
                var response = client.newCall(request).execute()
                
                // If response is successful, check JSON for server health status
                if (response.isSuccessful) {
                    try {
                        // Get response body as string
                        val responseBody = response.body?.string()
                        Log.d("ConnectionTest", "Response body: $responseBody")
                        
                        // Parse JSON if response is not empty
                        if (!responseBody.isNullOrEmpty()) {
                            // Use JSONObject to parse response
                            val jsonResponse = org.json.JSONObject(responseBody)
                            
                            // Check if the status field exists and is "ok"
                            if (jsonResponse.has("status") && jsonResponse.getString("status") == "ok") {
                                success = true
                                message = "Conectado"  // Simple, clear message
                                sendLogMessage(getString(R.string.connection_successful, apiBaseUrl))
                                Log.d("ConnectionTest", "Servidor saludable: ${jsonResponse.getString("status")}")
                            } else {
                                // Status field missing or not "ok"
                                message = "Servidor no saludable"
                                sendLogMessage(getString(R.string.connection_established_but_not_healthy, responseBody))
                                Log.w("ConnectionTest", "Servidor no saludable: $responseBody")
                            }
                        } else {
                            message = "Respuesta vacía"
                            sendLogMessage(getString(R.string.connection_established_but_empty))
                            Log.w("ConnectionTest", "Respuesta vacía de /health")
                        }
                    } catch (e: Exception) {
                        // Error parsing JSON
                        message = "Error analizando respuesta"
                        sendLogMessage(getString(R.string.error_analyzing_response, e.message))
                        Log.e("ConnectionTest", "Error analizando respuesta JSON", e)
                    }
                } else {
                    // If health fails, try voice-command endpoint with HEAD method
                    Log.d("ConnectionTest", "/health falló, intentando /voice-command")
                    
                    request = Request.Builder()
                        .url("$apiBaseUrl/voice-command")
                        .head() // Use HEAD to avoid sending body
                        .header("User-Agent", "Android Audio Client")
                        .build()
                    
                    response = client.newCall(request).execute()
                    
                    // Check if voice-command endpoint is reachable
                    if (response.isSuccessful) {
                        success = true
                        message = "Conectado"  // Simple, clear message
                        sendLogMessage(getString(R.string.connection_successful_alternative_endpoint))
                        Log.d("ConnectionTest", "Conexión exitosa con endpoint alternativo")
                    } else {
                        message = "Error HTTP: ${response.code}"
                        sendLogMessage(getString(R.string.connection_error, response.code))
                        Log.e("ConnectionTest", "Error HTTP: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                message = "Error: ${e.message}"
                sendLogMessage(getString(R.string.error_connecting, e.message))
                Log.e("ConnectionTest", "Excepción al conectar", e)
            }
            
            // Switch to main thread for broadcasting result
            serviceScope.launch(Dispatchers.Main) {
                // Broadcast result with explicit action
                val intent = Intent(ACTION_CONNECTION_TESTED).apply {
                    putExtra(EXTRA_CONNECTION_SUCCESS, success)
                    putExtra(EXTRA_CONNECTION_MESSAGE, message)
                    // Make the intent explicit by setting the package
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                sendLogMessage(getString(R.string.test_completed, if (success) "Conexión exitosa" else "Error de conexión", message))
                Log.d("ConnectionTest", "Broadcast enviado: action=${ACTION_CONNECTION_TESTED}, success=$success, message=$message, package=$packageName")
            }
        }
    }
} 
