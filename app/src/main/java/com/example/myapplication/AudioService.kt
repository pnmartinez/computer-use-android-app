package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
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
    private var textToSpeechManager: TextToSpeechManager? = null
    private var ttsLanguage: String = DEFAULT_TTS_LANGUAGE
    private var ttsRate: Float = DEFAULT_TTS_RATE
    private var ttsPitch: Float = DEFAULT_TTS_PITCH
    private var audioPlaybackEnabled: Boolean = DEFAULT_AUDIO_PLAYBACK_ENABLED
    private var lastResponseMessage: String? = null
    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusProbePlayer: ExoPlayer? = null
    private var isForegroundStarted = false

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
        const val ACTION_RECORDING_STARTED = "com.example.myapplication.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.example.myapplication.RECORDING_STOPPED"
        const val ACTION_RESPONSE_RECEIVED = "com.example.myapplication.RESPONSE_RECEIVED"
        const val ACTION_RESPONSE_PLAYED = "com.example.myapplication.RESPONSE_PLAYED"
        const val ACTION_LOG_MESSAGE = "com.example.myapplication.LOG_MESSAGE"
        const val ACTION_AUDIO_FILE_INFO = "com.example.myapplication.AUDIO_FILE_INFO"
        const val ACTION_PROCESSING_COMPLETED = "com.example.myapplication.PROCESSING_COMPLETED"
        const val ACTION_CONNECTION_TESTED = "com.example.myapplication.CONNECTION_TESTED"
        const val ACTION_TTS_STATUS = "com.example.myapplication.TTS_STATUS"
        const val ACTION_MEDIA_BUTTON_TAP = "com.example.myapplication.MEDIA_BUTTON_TAP"
        const val ACTION_MEDIA_BUTTON_EVENT = "com.example.myapplication.MEDIA_BUTTON_EVENT"
        
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
        const val EXTRA_MEDIA_BUTTON_KEYCODE = "media_button_keycode"
        
        // Default server settings
        const val DEFAULT_SERVER_IP = "your_server_ip_here"
        const val DEFAULT_SERVER_PORT = 5000
        const val DEFAULT_WHISPER_MODEL = "large"
        const val DEFAULT_RESPONSE_TIMEOUT = 20000 // 20 seconds in milliseconds
        const val DEFAULT_TTS_LANGUAGE = "es-ES"
        const val DEFAULT_TTS_RATE = 1.0f
        const val DEFAULT_TTS_PITCH = 1.0f
        const val DEFAULT_AUDIO_PLAYBACK_ENABLED = true
        
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
            onSpeakStart = {
                setSessionState(true)
                sendTtsStatus(TTS_STATUS_PLAYING)
            },
            onSpeakDone = {
                setSessionState(isRecording)
                sendTtsStatus(TTS_STATUS_IDLE)
            },
            onSpeakError = { sendTtsStatus(TTS_STATUS_ERROR) }
        )

        setupMediaSession()
        
        ensureForegroundServiceStarted()
        startAudioFocusProbe()
        sendLogMessage(getString(R.string.simple_computer_use_service_started) + if (testMode) " (MODO PRUEBA)" else "")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundServiceStarted()
        if (intent?.action == ACTION_MEDIA_BUTTON_EVENT) {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                setSessionState(true)
                handleMediaButtonKeycode(keyEvent.keyCode)
                return START_STICKY
            }
        }
        mediaSession?.isActive = true
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
            
            if (newIp != null) {
                updateServerSettings(
                    newIp,
                    newPort,
                    newModel,
                    newTimeout,
                    newTtsLanguage,
                    newTtsRate,
                    newTtsPitch,
                    newAudioPlaybackEnabled
                )
                sendLogMessage(getString(R.string.server_configuration_updated, serverIp, serverPort, whisperModel))
            }
        } else {
            // Handle normal actions
        when (intent?.action) {
            "START_RECORDING" -> startRecording()
            "STOP_RECORDING" -> stopRecordingAndSend()
                "CANCEL_RECORDING" -> stopRecordingWithoutSending()
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

        // Liberar ExoPlayer
        player?.release()
        player = null
        focusProbePlayer?.release()
        focusProbePlayer = null

        textToSpeechManager?.shutdown()
        textToSpeechManager = null

        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        abandonAudioFocus()
        isForegroundStarted = false
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

        val statusText = if (isRecording) "Grabando audio..." else "Servicio activo"
        val mediaStyle = Notification.MediaStyle()
        mediaSession?.sessionToken?.let { mediaStyle.setMediaSession(it) }

        return Notification.Builder(this, channelId)
            .setContentTitle("Simple Computer Use")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(mediaStyle)
            .build()
    }

    private fun startRecording() {
        if (isRecording) {
            sendLogMessage(getString(R.string.already_recording))
            Log.w("AudioService", getString(R.string.already_recording))
            return
        }
        try {
            sendLogMessage(getString(R.string.starting_recording))
            try {
                recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    setOutputFile(getAudioFile().absolutePath)
                    prepare()
                    start()
                }
                isRecording = true
                setSessionState(true)
                
                // Update notification to reflect recording state
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(1, createNotification())
                    
                // Broadcast state change with explicit package name
                val intent = Intent(ACTION_RECORDING_STARTED)
                intent.setPackage(packageName) // Make the intent explicit to ensure delivery
                sendBroadcast(intent)
                Log.d("AudioService", "Broadcasting ACTION_RECORDING_STARTED to package $packageName")
                sendLogMessage(getString(R.string.recording_started))
            } catch (e: IllegalStateException) {
                // Specific error for audio source issues
                sendLogMessage(getString(R.string.microphone_access_error, e.message))
                Log.e("AudioService", getString(R.string.microphone_access_error, e.message), e)
                sendErrorMessage(getString(R.string.microphone_permission_error))
                recorder?.release()
                recorder = null
                isRecording = false
                signalProcessingComplete()
            } catch (e: Exception) {
                // Generic error
                sendLogMessage(getString(R.string.error_starting_recording, e.message))
                Log.e("AudioService", getString(R.string.error_starting_recording, e.message), e)
                sendErrorMessage(getString(R.string.error_starting_recording, e.message))
                recorder?.release()
                recorder = null
                isRecording = false
                signalProcessingComplete()
            }
        } catch (e: Exception) {
            // Handle any unexpected errors
            sendLogMessage(getString(R.string.error_unexpected_starting_recording, e.message))
            Log.e("AudioService", getString(R.string.error_unexpected_starting_recording, e.message), e)
            recorder?.release()
            recorder = null
            isRecording = false
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
            setSessionState(true)
            
            // Update notification
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, createNotification())
                
            // Broadcast state change with explicit package name
            val intent = Intent(ACTION_RECORDING_STOPPED)
            intent.setPackage(packageName) // Make the intent explicit to ensure delivery
            sendBroadcast(intent)
            Log.d("AudioService", "Broadcasting ACTION_RECORDING_STOPPED to package $packageName")
            sendLogMessage(getString(R.string.recording_stopped_broadcast))
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
            setSessionState(false)
            
            // Update notification
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, createNotification())
                
            // Broadcast state change with explicit package name
            val intent = Intent(ACTION_RECORDING_STOPPED)
            intent.setPackage(packageName) // Make the intent explicit to ensure delivery
            sendBroadcast(intent)
            Log.d("AudioService", "Broadcasting ACTION_RECORDING_STOPPED (canceled) to package $packageName")
            sendLogMessage(getString(R.string.recording_canceled_broadcast))
            
            // Signal that processing is complete (in this case, canceled)
            signalProcessingComplete()
        }
        
        // No need to send the audio file since the recording was canceled
    }

    private fun setupMediaSession() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSession(this, "AudioService").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setSessionState(false)
            setCallback(
                object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        if (keyEvent?.action != KeyEvent.ACTION_DOWN) {
                            return true
                        }
                        setSessionState(true)
                        handleMediaButtonKeycode(keyEvent.keyCode)
                        return true
                    }
                }
            )
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setClass(this@AudioService, HeadsetMediaButtonReceiver::class.java)
            }
            val mediaButtonPendingIntent = PendingIntent.getBroadcast(
                this@AudioService,
                0,
                mediaButtonIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setMediaButtonReceiver(mediaButtonPendingIntent)
            isActive = true
        }
        requestAudioFocus()
    }

    private fun ensureForegroundServiceStarted() {
        if (isForegroundStarted) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                1,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(1, createNotification())
        }
        isForegroundStarted = true
    }

    private fun startAudioFocusProbe() {
        if (focusProbePlayer != null) {
            return
        }
        val focusGranted = requestAudioFocus()
        if (!focusGranted) {
            return
        }
        focusProbePlayer = ExoPlayer.Builder(this).build().apply {
            volume = 0f
            playWhenReady = true
        }
        serviceScope.launch(Dispatchers.Main) {
            delay(2000)
            focusProbePlayer?.release()
            focusProbePlayer = null
            if (!isRecording) {
                setSessionState(false)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val focusManager = audioManager ?: return false
        if (audioFocusListener == null) {
            audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                if (focusChange <= 0) {
                    sendLogMessage(getString(R.string.audio_focus_lost))
                    setSessionState(false)
                } else {
                    setSessionState(true)
                }
            }
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(audioFocusListener!!)
                .build()
            val result = focusManager.requestAudioFocus(audioFocusRequest!!)
            sendLogMessage(getString(R.string.audio_focus_request_result, result))
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                sendLogMessage(getString(R.string.audio_focus_not_granted))
            }
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = focusManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            sendLogMessage(getString(R.string.audio_focus_request_result, result))
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                sendLogMessage(getString(R.string.audio_focus_not_granted))
            }
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val focusManager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { focusManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioFocusListener?.let { focusManager.abandonAudioFocus(it) }
        }
        audioFocusRequest = null
        audioFocusListener = null
    }

    private fun handleMediaButtonKeycode(keyCode: Int) {
        sendLogMessage(getString(R.string.media_button_keycode_detected, keyCode))
        sendAppBroadcast(Intent(ACTION_MEDIA_BUTTON_TAP).apply {
            putExtra(EXTRA_MEDIA_BUTTON_KEYCODE, keyCode)
        })
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (isRecording) {
                    stopRecordingAndSend()
                } else {
                    startRecording()
                }
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (isRecording) {
                    stopRecordingWithoutSending()
                } else {
                    speakLastResponse()
                }
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (isRecording) {
                    stopRecordingWithoutSending()
                } else {
                    speakLastResponse()
                }
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (isRecording) {
                    stopRecordingWithoutSending()
                }
            }
        }
    }

    private fun setSessionState(isActive: Boolean) {
        val state = if (isActive) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
    }

    private fun speakLastResponse() {
        val message = lastResponseMessage?.trim().orEmpty()
        if (message.isEmpty()) {
            sendLogMessage(getString(R.string.media_button_no_response))
            return
        }
        serviceScope.launch(Dispatchers.Main) {
            val didSpeak = textToSpeechManager?.speak(message) ?: false
            if (didSpeak) {
                sendLogMessage(getString(R.string.tts_speaking_response))
            } else {
                sendLogMessage(getString(R.string.tts_not_ready))
                sendTtsStatus(TTS_STATUS_ERROR)
            }
        }
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
            lastResponseMessage = responseMessage
            
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
        lastResponseMessage = message
        
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
        audioPlaybackEnabled: Boolean? = null
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
