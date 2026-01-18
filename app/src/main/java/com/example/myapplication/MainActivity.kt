package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.appcompat.widget.AppCompatButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException
import okhttp3.*
import java.util.Date
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.app.Dialog
import android.view.WindowManager
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.OnScaleGestureListener
import android.graphics.Matrix
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import kotlinx.coroutines.TimeoutCancellationException
import com.example.myapplication.CommandHistoryUtils.CommandHistoryEntry
import com.example.myapplication.AudioService.Companion.KEY_SERVER_IP
import com.example.myapplication.AudioService.Companion.KEY_SERVER_PORT
import kotlin.math.sqrt
import android.widget.ProgressBar
import android.content.ClipData
import android.content.ClipboardManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {
    
    // Main controls
    private lateinit var btnStartRecording: MaterialButton
    private lateinit var btnProcessingRecording: MaterialButton
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var drawerLayout: DrawerLayout
    
    // Drawer footer - Handsfree controls
    private lateinit var drawerHandsfreeSwitch: SwitchMaterial
    private lateinit var drawerMicrophoneStatus: TextView
    private val headsetControlHandler = Handler(Looper.getMainLooper())
    private var headsetControlTimeout: Runnable? = null
    private var headsetControlPending = false
    
    // Countdown timer for handsfree recording
    private var recordingCountdownTimer: android.os.CountDownTimer? = null
    private val HANDSFREE_RECORDING_TIMEOUT_MS = 15000L
    
    // Logs - Find ScrollView directly by ID
    private lateinit var logsTextView: TextView
    private lateinit var btnCopyLogs: MaterialButton
    private lateinit var btnClearLogs: MaterialButton
    private lateinit var logsScrollView: ScrollView
    private lateinit var btnToggleLogs: MaterialButton
    private lateinit var logsContent: LinearLayout
    private var isLogsExpanded = false
    
    
    // Screenshot section
    private lateinit var screenshotImageView: ImageView
    private lateinit var screenshotStatusText: TextView
    private lateinit var btnCaptureScreenshot: MaterialButton
    private lateinit var btnUnlockScreen: MaterialButton
    private lateinit var btnRefreshPeriod: MaterialButton
    private lateinit var switchVncMode: SwitchMaterial
    private lateinit var vncStreamContainer: FrameLayout
    lateinit var screenshotLoadingProgress: ProgressBar
    private var vncView: android.vnc.VncCanvasView? = null
    private var isVncModeEnabled: Boolean = false

    // Screen summary section
    private lateinit var summaryCard: MaterialCardView
    private lateinit var summaryTextView: TextView
    private lateinit var btnPlaySummary: MaterialButton
    private lateinit var btnPlayLastAudio: MaterialButton
    private lateinit var summaryStatusTextView: TextView
    private var lastScreenSummary: String = ""
    private var isSummaryPlaying: Boolean = false
    private var summaryUpdateAnimator: ValueAnimator? = null
    
    // Keep track of app state
    private var isRecording = false
    private var currentAudioFile: File? = null
    private var audioPlayer: android.media.MediaPlayer? = null
    private val logBuffer = SpannableStringBuilder()
    private var headsetControlEnabled = false
    
    // Track active fullscreen dialog
    private var activeFullscreenDialog: FullscreenImageDialog? = null
    
    // Handler for response timeout
    private var responseTimeoutHandler: Handler? = null
    
    // For screenshot downloading
    private val screenshotJob = Job()
    private val screenshotScope = CoroutineScope(Dispatchers.Main + screenshotJob)
    private val okHttpClient by lazy {
        NetworkUtils.createTrustAllClient(
            connectTimeout = 10,
            readTimeout = 30,
            writeTimeout = 30
        )
    }
    
    // Long Polling for server updates
    private var longPollingService: LongPollingService? = null
    private val pollingJob = Job()
    private val pollingScope = CoroutineScope(Dispatchers.Main + pollingJob)
    private var isPollingConnected = false
    
    // Long Polling Debug UI
    private lateinit var pollingStatusDot: View
    private lateinit var pollingStatusText: TextView
    private lateinit var pollingServerUrl: TextView
    private lateinit var pollingLastEvent: TextView
    private lateinit var btnPollingReconnect: MaterialButton
    private lateinit var switchPollingEnabled: com.google.android.material.materialswitch.MaterialSwitch
    private var isPollingEnabled = true
    
    // Broadcast receiver to listen for service state changes
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioService.ACTION_RECORDING_STARTED -> {
                    isRecording = true
                    Log.d("MainActivity", "Recording started, isRecording = $isRecording")
                    updateButtonStates()
                    
                    // Start countdown timer if in handsfree mode
                    if (headsetControlEnabled) {
                        startRecordingCountdown()
                    }
                }
                AudioService.ACTION_RECORDING_STOPPED -> {
                    Log.d("MainActivity", "Recording stopped, showing processing state")
                    // Cancel countdown timer
                    cancelRecordingCountdown()
                    // Show the processing state when recording is stopped and we're waiting for the server response
                    showProcessingState()
                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.recording_finished_processing)}")
                }
                AudioService.ACTION_RESPONSE_RECEIVED -> {
                    // Cancel the response timeout since we got a response
                    Log.d("MainActivity", "ACTION_RESPONSE_RECEIVED received - canceling timeout")
                    responseTimeoutHandler?.removeCallbacksAndMessages(null)
                    responseTimeoutHandler = null
                    
                    // When a response is received, return to the ready state
                    Log.d("MainActivity", "Response received, returning to ready state")
                    showReadyState()
                    
                    // Show toast message for user feedback
                    val message = intent.getStringExtra(AudioService.EXTRA_RESPONSE_MESSAGE) ?: getString(R.string.command_processed_successfully)
                    val screenSummary = intent.getStringExtra(AudioService.EXTRA_SCREEN_SUMMARY).orEmpty()
                    val success = intent.getBooleanExtra(AudioService.EXTRA_RESPONSE_SUCCESS, true)

                    val summaryForUi = if (screenSummary.isNotBlank()) screenSummary else message
                    updateScreenSummary(summaryForUi)
                    
                    // Call our handler with the response details
                    handleVoiceCommandResponse(success, message)
                    
                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.response_received, message)}")
                }
                AudioService.ACTION_TTS_STATUS -> {
                    val status = intent.getStringExtra(AudioService.EXTRA_TTS_STATUS).orEmpty()
                    when (status) {
                        AudioService.TTS_STATUS_PLAYING -> {
                            isSummaryPlaying = true
                            updateSummaryStatus(getString(R.string.summary_status_playing))
                        }
                        AudioService.TTS_STATUS_IDLE -> {
                            isSummaryPlaying = false
                            updateSummaryStatus(
                                if (lastScreenSummary.isNotBlank()) {
                                    getString(R.string.summary_status_ready)
                                } else {
                                    getString(R.string.summary_status_empty)
                                }
                            )
                        }
                        AudioService.TTS_STATUS_ERROR -> {
                            isSummaryPlaying = false
                            updateSummaryStatus(getString(R.string.summary_status_error))
                        }
                    }
                }
                AudioService.ACTION_HEADSET_CONTROL_STATUS -> {
                    val enabled = intent.getBooleanExtra(
                        AudioService.EXTRA_HEADSET_CONTROL_ENABLED,
                        false
                    )
                    headsetControlEnabled = enabled
                    updateHeadsetControlUi(enabled)
                }
                AudioService.ACTION_MICROPHONE_CHANGED -> {
                    val micName = intent.getStringExtra(AudioService.EXTRA_MICROPHONE_NAME)
                    updateMicrophoneStatus(micName)
                }
                AudioService.ACTION_LOG_MESSAGE -> {
                    val message = intent.getStringExtra(AudioService.EXTRA_LOG_MESSAGE) ?: return
                    
                    // Check for error messages and go back to ready state for more error patterns
                    if (message.contains("Error", ignoreCase = true) || 
                        message.contains("Failed", ignoreCase = true) ||
                        message.contains("Could not", ignoreCase = true) ||
                        message.contains("No command detected", ignoreCase = true) ||
                        message.contains("ERROR:", ignoreCase = true) ||
                        message.contains("Exception", ignoreCase = true) ||
                        message.contains("Timeout", ignoreCase = true) ||
                        (message.contains("response", ignoreCase = true) && message.contains("empty", ignoreCase = true)) ||
                        message.contains("Connection failed", ignoreCase = true)) {
                        
                        Log.d("MainActivity", "Error condition detected in logs, ensuring UI is reset: $message")
                        showReadyState()
                    }
                    
                    addLogMessage(message)
                }
                AudioService.ACTION_AUDIO_FILE_INFO -> {
                    val filePath = intent.getStringExtra(AudioService.EXTRA_AUDIO_FILE_PATH) ?: return
                    val fileSize = intent.getLongExtra(AudioService.EXTRA_AUDIO_FILE_SIZE, 0)
                    val duration = intent.getLongExtra(AudioService.EXTRA_AUDIO_DURATION, 0)
                    val type = intent.getStringExtra(AudioService.EXTRA_AUDIO_TYPE) ?: "unknown"
                    
                    updateAudioFileInfo(filePath, fileSize, duration, type)
                }
                AudioService.ACTION_PROCESSING_COMPLETED -> {
                    // Cancel the response timeout since processing is complete
                    Log.d("MainActivity", "ACTION_PROCESSING_COMPLETED received - canceling timeout")
                    responseTimeoutHandler?.removeCallbacksAndMessages(null)
                    responseTimeoutHandler = null
                    
                    // Ensure we return to the ready state when processing completes
                    showReadyState()
                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.processing_completed)}")
                }
                AudioService.ACTION_CONNECTION_TESTED -> {
                    val success = intent.getBooleanExtra(AudioService.EXTRA_CONNECTION_SUCCESS, false)
                    val message = intent.getStringExtra(AudioService.EXTRA_CONNECTION_MESSAGE) ?: getString(R.string.unknown_error)
                    
                    Log.d("MainActivity", getString(R.string.connection_test_received, success, message))
                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.connection_test_received, success, message)}")
                }
                
                // Long Polling broadcasts
                LongPollingService.ACTION_UPDATE_RECEIVED -> {
                    val updateId = intent.getStringExtra(LongPollingService.EXTRA_UPDATE_ID) ?: ""
                    val updateType = intent.getStringExtra(LongPollingService.EXTRA_UPDATE_TYPE) ?: ""
                    val updateSummary = intent.getStringExtra(LongPollingService.EXTRA_UPDATE_SUMMARY) ?: ""
                    val updateChanges = intent.getStringArrayListExtra(LongPollingService.EXTRA_UPDATE_CHANGES) ?: arrayListOf()
                    
                    Log.d("MainActivity", "Server update received: $updateId - $updateSummary")
                    handleServerUpdate(updateType, updateSummary, updateChanges)
                }
                LongPollingService.ACTION_POLLING_STATUS -> {
                    val connected = intent.getBooleanExtra(LongPollingService.EXTRA_POLLING_CONNECTED, false)
                    isPollingConnected = connected
                    updatePollingStatusUi(connected)
                }
                LongPollingService.ACTION_POLLING_ERROR -> {
                    val errorMessage = intent.getStringExtra(LongPollingService.EXTRA_ERROR_MESSAGE) ?: ""
                    Log.e("MainActivity", "Polling error: $errorMessage")
                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.polling_error, errorMessage)}")
                    // Update debug UI with error
                    updatePollingDebugUI("error", "Error")
                    pollingLastEvent.text = "❌ ${getCurrentTime()}: ${errorMessage.take(50)}"
                }
                LongPollingService.ACTION_POLLING_DEBUG -> {
                    val debugMessage = intent.getStringExtra(LongPollingService.EXTRA_DEBUG_MESSAGE) ?: ""
                    pollingLastEvent.text = "🔍 ${getCurrentTime()}: $debugMessage"
                }
            }
        }
    }
    
    // Screenshot related sealed classes
    sealed class ScreenshotState {
        data class Success(val timestamp: String) : ScreenshotState()
        data class Error(val message: String) : ScreenshotState()
        object NoScreenshots : ScreenshotState()
        object Loading : ScreenshotState()
    }

    
    
    // Add these properties to track refresh state
    private var refreshPeriodMs: Long = 30000 // Default: 30 seconds
    private var refreshHandler: Handler? = null
    private var refreshRunnable: Runnable? = null
    private var autoRefreshEnabled = true
    
    // Command History properties
    private lateinit var commandHistoryRecyclerView: androidx.recyclerview.widget.RecyclerView
    private lateinit var commandHistoryAdapter: CommandHistoryAdapter
    private lateinit var noCommandHistoryText: TextView
    private lateinit var btnRefreshCommandHistory: com.google.android.material.button.MaterialButton
    private lateinit var btnToggleCommandHistory: com.google.android.material.button.MaterialButton
    private lateinit var commandHistoryContent: LinearLayout
    private var isCommandHistoryExpanded = false
    
    // Favorites
    private lateinit var favoritesRecyclerView: androidx.recyclerview.widget.RecyclerView
    private lateinit var favoritesAdapter: FavoritesAdapter
    private lateinit var noFavoritesText: TextView
    private lateinit var btnRefreshFavorites: com.google.android.material.button.MaterialButton
    private lateinit var btnToggleFavorites: com.google.android.material.button.MaterialButton
    private lateinit var favoritesContent: LinearLayout
    private var isFavoritesExpanded = false
    
    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 101
        private const val PREFS_NAME = "AppPreferences"
        private const val KEY_IS_LOGS_EXPANDED = "isLogsExpanded"
        private const val KEY_IS_DARK_THEME = "isDarkTheme"
        private const val KEY_REFRESH_PERIOD = "refreshPeriod"
        private const val KEY_AUTO_REFRESH_ENABLED = "autoRefreshEnabled"
        private const val KEY_UNLOCK_PASSWORD = "unlockPassword"
        private const val KEY_IS_COMMAND_HISTORY_EXPANDED = "isCommandHistoryExpanded"
        private const val KEY_IS_FAVORITES_EXPANDED = "isFavoritesExpanded"
        private const val KEY_SCREENSHOT_REFRESH_PERIOD = "screenshotRefreshPeriod"
        private const val KEY_TUTORIAL_SHOWN = "tutorialShown"
        private const val KEY_LAST_SUMMARY = "lastScreenSummary"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 100
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyThemeFromPreferences()
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        // Setup edge-to-edge content
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // Initialize views
        initViews()
        
        // Load app preferences
        loadAppPreferences()

        maybeShowTutorial()
        
        // Configure initial button states
        updateButtonStates()
        
        // Setup click listeners
        setupButtonListeners()

        // Setup screenshot section
        setupScreenshotSection()
        
        // Setup glass effect on footer
        setupGlassEffect()
        
        // Load logs state
        loadLogsState()
        
        // Add initial log message
        addLogMessage("[${getCurrentTime()}] ${getString(R.string.app_started)}")
        
        // Register broadcast receiver
        registerReceiver()
        
        // Start capturing logcat
        AudioService.startLogCapture(applicationContext)
        
        // Setup sections (make sure these are called BEFORE fetching data)
        setupCommandHistory()
        setupFavorites()
        
        // Fetch command history after all views are initialized
        // This will also fetch favorites after completion
        fetchCommandHistory()
        
        // Start long polling for server updates
        startLongPolling()
    }
    
    override fun onResume() {
        super.onResume()
        applyThemeFromPreferences()
        try {
            registerReceiver()
        } catch (e: Exception) {
            // Already registered
        }
        
        // Restore logs state
        loadLogsState()
        
        // Restore last audio file and summary from cache (in case broadcasts were missed while in background)
        restoreLastAudioFile()
        restoreLastSummary()
        
        // If auto-refresh was enabled, restart it
        if (autoRefreshEnabled) {
            startAutoRefresh()
        }
        
        // Restart long polling if not running
        if (longPollingService?.isPolling() != true) {
            startLongPolling()
        }
    }
    
    /**
     * Restaura la referencia al último archivo de audio desde la caché.
     * Esto es necesario porque si la Activity estaba en background (pantalla bloqueada),
     * los broadcasts se pierden y currentAudioFile no se actualiza.
     */
    private fun restoreLastAudioFile() {
        // Primero intentar con el archivo de caché
        val cacheFile = File(cacheDir, "last_sent_recording.ogg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            currentAudioFile = cacheFile
            Log.d("MainActivity", "Restored last audio file from cache: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            return
        }
        
        // Si no existe el de caché, intentar con el archivo original de grabación
        // (puede que el broadcast se haya perdido mientras estábamos en background)
        val originalFile = File(filesDir, "recorded_audio.ogg")
        if (originalFile.exists() && originalFile.length() > 0) {
            try {
                // Copiar a caché para uso futuro
                originalFile.copyTo(cacheFile, overwrite = true)
                currentAudioFile = cacheFile
                Log.d("MainActivity", "Restored audio file from original recording: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error copying original audio file to cache: ${e.message}", e)
                // Usar el archivo original directamente
                currentAudioFile = originalFile
            }
        } else {
            Log.d("MainActivity", "No audio file found to restore (cache or original)")
        }
    }
    
    /**
     * Restaura el último resumen desde SharedPreferences.
     * Esto es necesario porque si la Activity estaba en background (pantalla bloqueada),
     * el broadcast ACTION_RESPONSE_RECEIVED se pierde y la tarjeta de resumen no se actualiza.
     */
    private fun restoreLastSummary() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedSummary = prefs.getString(KEY_LAST_SUMMARY, null)
        
        if (!savedSummary.isNullOrBlank() && lastScreenSummary.isBlank()) {
            // Solo restaurar si no tenemos un resumen actual
            Log.d("MainActivity", "Restoring last summary from SharedPreferences: ${savedSummary.take(50)}...")
            lastScreenSummary = savedSummary
            summaryTextView.text = savedSummary
            btnPlaySummary.isEnabled = true
            updateSummaryStatus(getString(R.string.summary_status_ready))
        } else if (savedSummary.isNullOrBlank()) {
            Log.d("MainActivity", "No saved summary to restore")
        } else {
            Log.d("MainActivity", "Current summary already present, not restoring")
        }
    }
    
    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        
        // Cancel countdown timer to avoid memory leaks
        cancelRecordingCountdown()
        
        // Stop audio playback to avoid memory leaks
        stopAudioPlayback()
        
        // Save logs state
        saveLogsState()
        
        // Save preferences
        saveAppPreferences()
        
        // Stop any scheduled timers
        responseTimeoutHandler?.removeCallbacksAndMessages(null)
        stopAutoRefresh()
        stopVncStream()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Cancel any ongoing screenshot jobs
        screenshotJob.cancel()
        
        // Stop long polling
        stopLongPolling()
        pollingJob.cancel()
    }
    
    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioService.ACTION_RECORDING_STARTED)
            addAction(AudioService.ACTION_RECORDING_STOPPED)
            addAction(AudioService.ACTION_RESPONSE_RECEIVED)
            addAction(AudioService.ACTION_LOG_MESSAGE)
            addAction(AudioService.ACTION_AUDIO_FILE_INFO)
            addAction(AudioService.ACTION_PROCESSING_COMPLETED)
            addAction(AudioService.ACTION_CONNECTION_TESTED)
            addAction(AudioService.ACTION_TTS_STATUS)
            addAction(AudioService.ACTION_HEADSET_CONTROL_STATUS)
            addAction(AudioService.ACTION_MICROPHONE_CHANGED)
            // Long Polling broadcasts
            addAction(LongPollingService.ACTION_UPDATE_RECEIVED)
            addAction(LongPollingService.ACTION_POLLING_STATUS)
            addAction(LongPollingService.ACTION_POLLING_ERROR)
            addAction(LongPollingService.ACTION_POLLING_DEBUG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
        registerReceiver(serviceReceiver, filter)
        }
    }
    
    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        val navigationView = findViewById<com.google.android.material.navigation.NavigationView>(R.id.navigationView)
        val topAppBar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        topAppBar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        // Setup drawer menu item clicks (custom drawer layout)
        setupDrawerMenuClicks(navigationView)

        // Main controls
        btnStartRecording = findViewById(R.id.btnStartRecording)
        btnProcessingRecording = findViewById(R.id.btnProcessingRecording)
        progressIndicator = findViewById(R.id.progressIndicator)
        
        // Drawer footer - Handsfree controls
        drawerHandsfreeSwitch = findViewById(R.id.drawerHandsfreeSwitch)
        drawerMicrophoneStatus = findViewById(R.id.drawerMicrophoneStatus)
        
        // Logs - Find ScrollView directly by ID
        logsTextView = findViewById(R.id.logsTextView)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        logsScrollView = findViewById(R.id.logsScrollView)
        
        // Make the logs content area capture focus for scrolling
        setupLogsScrollingBehavior()
        
        btnToggleLogs = findViewById(R.id.btnToggleLogs)
        logsContent = findViewById(R.id.logsContent)
        
        // Command History section
        setupCommandHistory()
        
        // Screenshot section
        screenshotImageView = findViewById(R.id.screenshotImageView)
        screenshotStatusText = findViewById(R.id.screenshotStatusText)
        btnCaptureScreenshot = findViewById(R.id.btnCaptureScreenshot)
        btnUnlockScreen = findViewById(R.id.btnUnlockScreen)
        btnRefreshPeriod = findViewById(R.id.btnRefreshPeriod)
        switchVncMode = findViewById(R.id.switchVncMode)
        vncStreamContainer = findViewById(R.id.vncStreamContainerMain)
        screenshotLoadingProgress = findViewById(R.id.screenshotLoadingProgress)

        // Screen summary section
        summaryCard = findViewById(R.id.summaryCard)
        summaryTextView = findViewById(R.id.summaryText)
        btnPlaySummary = findViewById(R.id.btnPlaySummary)
        btnPlayLastAudio = findViewById(R.id.btnPlayLastAudio)
        summaryStatusTextView = findViewById(R.id.summaryStatusText)
        summaryCard.setCardForegroundColor(ColorStateList.valueOf(Color.TRANSPARENT))
        btnPlaySummary.setOnClickListener {
            if (lastScreenSummary.isNotBlank()) {
                speakSummary(lastScreenSummary)
            } else {
                Toast.makeText(this, getString(R.string.summary_unavailable), Toast.LENGTH_SHORT).show()
            }
        }
        btnPlayLastAudio.setOnClickListener {
            playLastAudio()
        }
        
        updateScreenSummary("")
        
        // Long Polling / MCP Server UI
        pollingStatusDot = findViewById(R.id.pollingStatusDot)
        pollingStatusText = findViewById(R.id.pollingStatusText)
        pollingServerUrl = findViewById(R.id.pollingServerUrl)
        pollingLastEvent = findViewById(R.id.pollingLastEvent)
        btnPollingReconnect = findViewById(R.id.btnPollingReconnect)
        switchPollingEnabled = findViewById(R.id.switchPollingEnabled)
        
        // Load polling preference
        val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        isPollingEnabled = prefs.getBoolean("polling_enabled", true)
        switchPollingEnabled.isChecked = isPollingEnabled
        
        switchPollingEnabled.setOnCheckedChangeListener { _, isChecked ->
            isPollingEnabled = isChecked
            prefs.edit().putBoolean("polling_enabled", isChecked).apply()
            if (isChecked) {
                updatePollingDebugUI("connecting", "Conectando...")
                startLongPolling()
            } else {
                stopLongPolling()
                updatePollingDebugUI("disabled", "Desactivado")
                pollingLastEvent.text = "Activa el switch para recibir notificaciones"
            }
        }
        
        btnPollingReconnect.setOnClickListener {
            if (isPollingEnabled) {
                addLogMessage("[${getCurrentTime()}] 🔄 Reconectando MCP Server...")
                updatePollingDebugUI("reconnecting", "Reconectando...")
                stopLongPolling()
                startLongPolling()
            }
        }
        
        // Initialize polling UI based on preference
        if (isPollingEnabled) {
            updatePollingDebugUI("inactive", "Esperando...")
        } else {
            updatePollingDebugUI("disabled", "Desactivado")
            pollingLastEvent.text = "Activa el switch para recibir notificaciones"
        }

        updateHeadsetControlUi(headsetControlEnabled)
        updateMicrophoneStatus(null) // Initial state

        val vncPrefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        isVncModeEnabled = vncPrefs.getBoolean(VncPreferences.KEY_VNC_MODE_ENABLED, false)
        switchVncMode.isChecked = isVncModeEnabled
        switchVncMode.setOnCheckedChangeListener { _, isChecked ->
            isVncModeEnabled = isChecked
            vncPrefs.edit().putBoolean(VncPreferences.KEY_VNC_MODE_ENABLED, isChecked).apply()
            applyVncMode(isChecked)
        }
        
        // Setup log clear button
        btnClearLogs.setOnClickListener {
            logBuffer.clear()
            logsTextView.text = ""
            addLogMessage("[${getCurrentTime()}] ${getString(R.string.logs_cleared)}")
        }

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(
                getString(R.string.logs_title),
                logsTextView.text.toString()
            )
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
        }
        
        // Setup log toggle button
        btnToggleLogs.setOnClickListener {
            isLogsExpanded = !isLogsExpanded
            toggleLogsVisibility()
        }
        
        // Setup logs header to toggle expansion when clicked
        findViewById<LinearLayout>(R.id.logsHeader).setOnClickListener {
            isLogsExpanded = !isLogsExpanded
            toggleLogsVisibility()
        }
        
        // Reset the recording state when the app starts
        // This handles cases where the service might be in recording mode but the activity doesn't know
        isRecording = false
        updateButtonStates() // Update UI immediately
        
        // Use the special RESET_STATE action to ensure the service resets too
        Log.d("MainActivity", "Sending RESET_STATE on app init to reset service state")
        startAudioService(this, "RESET_STATE")
        
        addLogMessage("[${getCurrentTime()}] ${getString(R.string.recording_state_reset)}")
        
    }
    
    /**
     * Setup custom scrolling behavior for logs area
     */
    private fun setupLogsScrollingBehavior() {
        // Set the logs parent container to intercept touch events
        val logsContent = findViewById<LinearLayout>(R.id.logsContent)
        
        // Make logs content clickable to be able to handle its own touch events
        logsContent.isClickable = true
        logsContent.isFocusable = true
        
        // Get reference to the parent NestedScrollView
        val parentScrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.nestedScrollView)
        
        // Handler to reset the highlight after scrolling stops
        val highlightHandler = Handler(mainLooper)
        val resetHighlightRunnable = Runnable {
            logsScrollView.setBackgroundResource(R.drawable.logs_scrollview_normal)
        }
        
        // Custom touch listener to handle scroll events
        var lastY = 0f
        var isScrolling = false
        logsScrollView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Disable the parent NestedScrollView from intercepting touch events
                    // when the user touches inside the logs ScrollView
                    parentScrollView.requestDisallowInterceptTouchEvent(true)
                    lastY = event.y
                    
                    // Highlight the border when user touches it
                    logsScrollView.setBackgroundResource(R.drawable.logs_scrollview_active)
                }
                MotionEvent.ACTION_MOVE -> {
                    // Calculate if user is trying to scroll beyond what logsScrollView can handle
                    val delta = event.y - lastY
                    val scrollView = view as ScrollView
                    
                    // Mark that we're scrolling
                    isScrolling = true
                    
                    val isScrollingUp = delta > 0
                    val isScrollingDown = delta < 0
                    
                    val isAtTop = !scrollView.canScrollVertically(-1)
                    val isAtBottom = !scrollView.canScrollVertically(1)
                    
                    // If we're at top or bottom and trying to scroll beyond, let parent handle it
                    val shouldParentIntercept = (isAtTop && isScrollingUp) || (isAtBottom && isScrollingDown)
                    parentScrollView.requestDisallowInterceptTouchEvent(!shouldParentIntercept)
                    
                    lastY = event.y
                    
                    // Ensure border is highlighted while scrolling
                    logsScrollView.setBackgroundResource(R.drawable.logs_scrollview_active)
                    
                    // Cancel any pending reset
                    highlightHandler.removeCallbacks(resetHighlightRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Reset when the gesture ends
                    parentScrollView.requestDisallowInterceptTouchEvent(false)
                    
                    // Keep highlight for a short period after scrolling ends
                    if (isScrolling) {
                        highlightHandler.removeCallbacks(resetHighlightRunnable)
                        highlightHandler.postDelayed(resetHighlightRunnable, 800) // Keep highlight for 800ms
                        isScrolling = false
                    }
                }
            }
            // Always pass the event to the ScrollView's normal handler
            return@setOnTouchListener view.onTouchEvent(event)
        }
        
        // Also handle the scroll change events for fling gestures
        logsScrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            // Highlight the border while scrolling
            logsScrollView.setBackgroundResource(R.drawable.logs_scrollview_active)
            
            // Cancel any previous reset timer and set a new one
            highlightHandler.removeCallbacks(resetHighlightRunnable)
            highlightHandler.postDelayed(resetHighlightRunnable, 800) // Keep highlight for 800ms after last scroll
        }
    }
    
    private fun updateButtonStates() {
        Log.d("MainActivity", "updateButtonStates() called - isRecording: $isRecording")
        if (isRecording) {
            Log.d("MainActivity", "Setting button to recording state (red)")
            btnStartRecording.text = getString(R.string.stop_recording)
            // Set stop icon using MaterialButton API
            btnStartRecording.setIconResource(android.R.drawable.ic_media_pause)
            // Set red gradient background
            btnStartRecording.background = ContextCompat.getDrawable(this, R.drawable.btn_record_stop_background)
            btnStartRecording.backgroundTintList = null
        } else {
            Log.d("MainActivity", "Setting button to ready state (green)")
            btnStartRecording.text = getString(R.string.start_recording_button)
            // Set microphone icon using MaterialButton API
            btnStartRecording.setIconResource(android.R.drawable.ic_btn_speak_now)
            // Set green gradient background
            btnStartRecording.background = ContextCompat.getDrawable(this, R.drawable.btn_record_background)
            btnStartRecording.backgroundTintList = null
        }
    }
    
    private fun startRecordingCountdown() {
        cancelRecordingCountdown() // Cancel any existing timer
        
        recordingCountdownTimer = object : android.os.CountDownTimer(HANDSFREE_RECORDING_TIMEOUT_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = (millisUntilFinished / 1000).toInt()
                runOnUiThread {
                    // Update button text with countdown
                    btnStartRecording.text = "${secondsRemaining}s"
                    btnStartRecording.icon = null // Remove icon during countdown
                }
            }
            
            override fun onFinish() {
                // Timer finished - recording will be stopped by AudioService
                runOnUiThread {
                    btnStartRecording.text = "0s"
                }
            }
        }.start()
        
        Log.d("MainActivity", "Recording countdown started: ${HANDSFREE_RECORDING_TIMEOUT_MS}ms")
    }
    
    private fun cancelRecordingCountdown() {
        recordingCountdownTimer?.cancel()
        recordingCountdownTimer = null
        // Restore button state immediately to avoid frozen countdown text
        runOnUiThread {
            updateButtonStates()
        }
        Log.d("MainActivity", "Recording countdown cancelled and button state restored")
    }

    private fun updateHeadsetControlUi(enabled: Boolean) {
        headsetControlPending = false
        drawerHandsfreeSwitch.isEnabled = true
        headsetControlTimeout?.let { headsetControlHandler.removeCallbacks(it) }
        
        // Update switch state without triggering listener
        drawerHandsfreeSwitch.setOnCheckedChangeListener(null)
        drawerHandsfreeSwitch.isChecked = enabled
        setupHandsfreeSwitchListener()
        
        // Update microphone status when disabled
        if (!enabled) {
            updateMicrophoneStatus(null)
        }
    }
    
    private fun updateMicrophoneStatus(micName: String?) {
        drawerMicrophoneStatus.text = if (micName != null) {
            getString(R.string.drawer_mic_device, micName)
        } else {
            getString(R.string.drawer_mic_inactive)
        }
    }
    
    private fun setupDrawerMenuClicks(navigationView: com.google.android.material.navigation.NavigationView) {
        // Find custom menu items in drawer_content layout
        navigationView.findViewById<View>(R.id.nav_captures)?.setOnClickListener {
            startActivity(Intent(this, CapturesActivity::class.java))
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        navigationView.findViewById<View>(R.id.nav_settings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        navigationView.findViewById<View>(R.id.nav_tutorial)?.setOnClickListener {
            showTutorialDialog(markAsSeen = false)
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }
    
    private fun setupHandsfreeSwitchListener() {
        drawerHandsfreeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Mostrar diálogo de advertencia al activar modo manos libres
                showHandsfreeBetaDialog()
        } else {
                // Desactivar directamente
                activateHandsfreeMode(false)
            }
        }
    }
    
    private fun showHandsfreeBetaDialog() {
        // Revertir el switch temporalmente hasta que el usuario confirme
        drawerHandsfreeSwitch.setOnCheckedChangeListener(null)
        drawerHandsfreeSwitch.isChecked = false
        setupHandsfreeSwitchListener()
        
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_handsfree_beta)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        dialog.findViewById<MaterialButton>(R.id.btnHandsfreeBetaConfirm).setOnClickListener {
            dialog.dismiss()
            activateHandsfreeMode(true)
        }
        dialog.findViewById<MaterialButton>(R.id.btnHandsfreeBetaCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    
    private fun activateHandsfreeMode(enable: Boolean) {
        val action = if (enable) {
            AudioService.ACTION_ENABLE_HEADSET_CONTROL
        } else {
            AudioService.ACTION_DISABLE_HEADSET_CONTROL
        }
        headsetControlPending = true
        drawerHandsfreeSwitch.isEnabled = false
        
        // Actualizar el switch si estamos activando
        if (enable) {
            drawerHandsfreeSwitch.setOnCheckedChangeListener(null)
            drawerHandsfreeSwitch.isChecked = true
            setupHandsfreeSwitchListener()
        }
        
        addLogMessage("[${getCurrentTime()}] ${getString(R.string.headset_control_status_pending)}")
        headsetControlTimeout?.let { headsetControlHandler.removeCallbacks(it) }
        headsetControlTimeout = Runnable {
            if (headsetControlPending) {
                headsetControlPending = false
                drawerHandsfreeSwitch.isEnabled = true
                addLogMessage("[${getCurrentTime()}] ${getString(R.string.headset_control_status_timeout)}")
            }
        }
        headsetControlHandler.postDelayed(headsetControlTimeout!!, 3000)
        startAudioService(this, action)
        headsetControlHandler.postDelayed({
            startAudioService(this, AudioService.ACTION_QUERY_HEADSET_CONTROL_STATUS)
        }, 250)
    }
    
    private fun setupButtonListeners() {
        // Setup the main recording button
        btnStartRecording.setOnClickListener {
            if (!isRecording) {
                startRecording()
            } else {
                stopRecording()
            }
        }
        
        // Add long press listener for emergency reset
        btnStartRecording.setOnLongClickListener {
            // Force reset the recording state
            Log.d("MainActivity", "Long press detected - emergency reset")
            addLogMessage("[${getCurrentTime()}] ⚠️ ${getString(R.string.emergency_reset_activated)}")
            
            // Reset app state
            isRecording = false
            updateButtonStates()
            
            // Reset service state
            startAudioService(this, "RESET_STATE")
            
            // Hide progress indicator if visible
            progressIndicator.visibility = View.GONE
            
            // Show toast
            Toast.makeText(this, "Estado de grabación reiniciado", Toast.LENGTH_SHORT).show()
            
            true // Consume the long press
        }

        // Setup handsfree switch listener
        setupHandsfreeSwitchListener()
        
        // Setup log clear button
        btnClearLogs.setOnClickListener {
            logBuffer.clear()
            logsTextView.text = ""
            addLogMessage("[${getCurrentTime()}] ${getString(R.string.logs_cleared)}")
        }
        
        // Setup unlock screen button
        btnUnlockScreen.setOnClickListener {
            unlockScreen()
        }
    }
    
    private fun startRecording() {
        // Check for recording permission
        if (!hasRecordAudioPermission(this)) {
            // Request permission
            requestRecordAudioPermission(this)
            // Log that we're waiting for permission
            addLogMessage("[${getCurrentTime()}] ${getString(R.string.requesting_audio_permission)}")
            Log.d("MainActivity", "Requesting audio permission")
            return
        }

        // Permission granted, proceed with recording
        addLogMessage("[${getCurrentTime()}] ${getString(R.string.starting_recording)}")
        Log.d("MainActivity", "Starting recording, waiting for broadcast back")
        startAudioService(this, "START_RECORDING")
        
        // Safety timeout - if we don't get the RECORDING_STARTED broadcast within 3 seconds,
        // reset to non-recording state
        Handler(mainLooper).postDelayed({
            if (!isRecording) {
                Log.d("MainActivity", "No recording started broadcast received, resetting UI")
                updateButtonStates()
            }
        }, 3000)
    }
    
    private fun stopRecording() {
        // Don't check isRecording here, force it to stop
        addLogMessage("[${getCurrentTime()}] ${getString(R.string.stopping_recording)}")
        Log.d("MainActivity", "Stopping recording, waiting for broadcast back")
        startAudioService(this, "STOP_RECORDING")
        progressIndicator.visibility = View.VISIBLE // Show progress while sending
        Toast.makeText(this, "Grabación detenida, enviando...", Toast.LENGTH_SHORT).show()
        
        // Safety timeout - if we don't get the RECORDING_STOPPED broadcast within 3 seconds,
        // force reset to non-recording state
        Handler(mainLooper).postDelayed({
            if (isRecording) {
                Log.d("MainActivity", "No recording stopped broadcast received, forcing state reset")
                isRecording = false
                updateButtonStates()
            }
        }, 3000)
        
        // Cancel any existing timeout
        responseTimeoutHandler?.removeCallbacksAndMessages(null)
        
        // Add a timeout to reset the UI if no response is received
        responseTimeoutHandler = Handler(mainLooper)
        val currentTimeout = AudioService.getCurrentResponseTimeout(this)
        Log.d("MainActivity", "Setting ${currentTimeout/1000}-second response timeout")
        responseTimeoutHandler?.postDelayed({
            Log.d("MainActivity", "Response timeout triggered - checking UI state")
            Log.e("MainActivity", "TIMEOUT: ${AudioService.getCurrentResponseTimeout(this)/1000} seconds elapsed, checking UI state")
            if (progressIndicator.visibility == View.VISIBLE || btnProcessingRecording.visibility == View.VISIBLE) {
                Log.d("MainActivity", "Response timeout - resetting UI to ready state")
                Log.e("MainActivity", "TIMEOUT: Calling showReadyState() from timeout")
                showReadyState()
                addLogMessage("[${getCurrentTime()}] ${getString(R.string.waiting_for_response)}")
                Toast.makeText(this, "No se recibió respuesta del servidor", Toast.LENGTH_SHORT).show()
            } else {
                Log.d("MainActivity", "Response timeout triggered but UI already reset - ignoring")
                Log.e("MainActivity", "TIMEOUT: UI already reset, ignoring timeout")
            }
        }, AudioService.getCurrentResponseTimeout(this).toLong()) // Use configurable timeout
        
        // Update UI to show processing state
        showProcessingState()
    }
    
    private fun showProcessingState() {
        runOnUiThread {
            // Use AnimationUtils instead of AnimatorInflater
            val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out)
            val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in)
            
            // Set animation listener
            fadeOut.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                
                override fun onAnimationEnd(animation: Animation?) {
                    // After fade out, hide the button and show the processing button
                    btnStartRecording.visibility = View.GONE
                    btnProcessingRecording.visibility = View.VISIBLE
                    progressIndicator.visibility = View.VISIBLE
                    
                    // Start fade in animation
                    btnProcessingRecording.startAnimation(fadeIn)
                }
                
                override fun onAnimationRepeat(animation: Animation?) {}
            })
            
            // Start fade out animation
            btnStartRecording.startAnimation(fadeOut)
        }
    }
    
    private fun showReadyState() {
        runOnUiThread {
            Log.d("MainActivity", "showReadyState() called - current isRecording: $isRecording")
            Log.e("MainActivity", "SHOW_READY: showReadyState() called - current isRecording: $isRecording")
            
            // Use AnimationUtils instead of AnimatorInflater
            val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out)
            val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in)
            
            // Set animation listener
            fadeOut.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                    Log.d("MainActivity", "Fade out animation started")
                }
                
                override fun onAnimationEnd(animation: Animation?) {
                    Log.d("MainActivity", "Fade out animation ended - resetting UI")
                    Log.e("MainActivity", "SHOW_READY: Fade out animation ended - resetting UI")
                    // After fade out, hide the processing button and show the main button
                    btnProcessingRecording.visibility = View.GONE
                    btnStartRecording.visibility = View.VISIBLE
                    progressIndicator.visibility = View.GONE
                    isRecording = false
                    Log.d("MainActivity", "isRecording set to false, calling updateButtonStates()")
                    Log.e("MainActivity", "SHOW_READY: isRecording set to false, calling updateButtonStates()")
                    updateButtonStates()
                    
                    // Start fade in animation
                    btnStartRecording.startAnimation(fadeIn)
                    Log.d("MainActivity", "Fade in animation started")
                    Log.e("MainActivity", "SHOW_READY: Fade in animation started")
                }
                
                override fun onAnimationRepeat(animation: Animation?) {}
            })
            
            // Start fade out animation
            Log.d("MainActivity", "Starting fade out animation on processing button")
            btnProcessingRecording.startAnimation(fadeOut)
        }
    }
    
    private fun updateAudioFileInfo(filePath: String, fileSize: Long, duration: Long, type: String) {
        // Update UI with file info
        val file = File(filePath)
        
        // Verify file exists and has content
        if (!file.exists()) {
            addLogMessage("[${getCurrentTime()}] ${getString(R.string.file_not_found, filePath)}")
            return
        }
        
        if (fileSize <= 0) {
            addLogMessage("[${getCurrentTime()}] ${getString(R.string.file_empty_corrupt, filePath)}")
            return
        }
        
        // Only persist recording files (not response files) for playback
        if (type == "recording") {
            try {
                // Copy to a persistent cache file that won't be overwritten
                val cacheFile = File(cacheDir, "last_sent_recording.ogg")
                file.copyTo(cacheFile, overwrite = true)
                currentAudioFile = cacheFile
                Log.d("MainActivity", "Audio file cached for playback: ${cacheFile.absolutePath} (${fileSize} bytes)")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error caching audio file: ${e.message}", e)
                currentAudioFile = null
            }
        }
        
        // Format file size for logging
        val fileType = if (type == "recording") getString(R.string.audio_recording) else getString(R.string.audio_response)
        val formattedSize = when {
            fileSize < 1024 -> getString(R.string.bytes, fileSize)
            fileSize < 1024 * 1024 -> getString(R.string.kb, fileSize / 1024)
            else -> String.format(getString(R.string.mb), fileSize / (1024.0 * 1024.0))
        }
        
        // Format duration
        val formattedDuration = formatDuration(duration)
        
        // Log the file information
        addLogMessage("[${getCurrentTime()}] ${getString(R.string.audio_recording_info, formattedSize, formattedDuration)}")
        
        // No need to update UI for audio visualization since it's removed
    }
    
    private fun addLogMessage(message: String) {
        // Check if the buffer has grown too large and trim if needed
        if (logBuffer.length > 10000) {
            // Find the position of a new line after the first 5000 characters
            val trimPosition = logBuffer.indexOf('\n', 5000)
            if (trimPosition > 0) {
                // Keep only the last portion of the log
                logBuffer.delete(0, trimPosition + 1)
                // Add a message indicating logs were trimmed
                logBuffer.insert(0, "--- Older logs trimmed ---\n")
            }
        }
        
        // Add to buffer
        if (logBuffer.isNotEmpty()) {
            logBuffer.append("\n")
        }
        logBuffer.append(message)
        
        // Update TextView without auto-scrolling
        logsTextView.text = logBuffer
        
        // Removed auto-scrolling code to prevent unwanted global scrolling behavior
        // User can manually scroll within the logs area if needed
    }
    
    private fun formatDuration(durationMs: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
    
    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permiso concedido, iniciar grabación
                addLogMessage("[${getCurrentTime()}] ${getString(R.string.audio_permission_granted)}")
                startRecording()
            } else {
                // Permiso denegado, mostrar mensaje
                addLogMessage("[${getCurrentTime()}] ${getString(R.string.audio_permission_denied)}")
                Toast.makeText(
                    this,
                    getString(R.string.audio_permission_required),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Utility function to start the AudioService
     */
    private fun startAudioService(context: Context, action: String) {
        val intent = Intent(context, AudioService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun speakSummary(summary: String) {
        val intent = Intent(this, AudioService::class.java).apply {
            action = "SPEAK_SUMMARY"
            putExtra(AudioService.EXTRA_SCREEN_SUMMARY, summary)
        }
        startService(intent)
    }

    private fun updateScreenSummary(summary: String) {
        val trimmedSummary = summary.trim()
        val previousSummary = lastScreenSummary
        lastScreenSummary = trimmedSummary
        val summaryText = if (trimmedSummary.isNotEmpty()) {
            trimmedSummary
        } else {
            getString(R.string.summary_unavailable)
        }
        summaryTextView.text = summaryText
        btnPlaySummary.isEnabled = trimmedSummary.isNotEmpty()
        if (!isSummaryPlaying) {
            updateSummaryStatus(
                if (trimmedSummary.isNotEmpty()) {
                    getString(R.string.summary_status_ready)
                } else {
                    getString(R.string.summary_status_empty)
                }
            )
        }
        if (trimmedSummary.isNotEmpty() && trimmedSummary != previousSummary) {
            animateSummaryCardUpdate()
        }
        
        // Persistir el resumen en SharedPreferences para recuperarlo si la Activity estaba en background
        if (trimmedSummary.isNotEmpty()) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_SUMMARY, trimmedSummary)
                .apply()
            Log.d("MainActivity", "Summary saved to SharedPreferences: ${trimmedSummary.take(50)}...")
        }
    }

    private fun updateSummaryStatus(status: String) {
        summaryStatusTextView.text = status
    }

    private fun animateSummaryCardUpdate() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            return
        }
        summaryUpdateAnimator?.cancel()
        val highlightColor = MaterialColors.getColor(
            summaryCard,
            com.google.android.material.R.attr.colorSecondaryContainer
        )
        val transparentHighlight = MaterialColors.compositeARGBWithAlpha(highlightColor, 0)
        summaryUpdateAnimator = ValueAnimator.ofArgb(
            transparentHighlight,
            highlightColor,
            transparentHighlight
        ).apply {
            duration = 1400L
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                summaryCard.setCardForegroundColor(ColorStateList.valueOf(color))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    summaryCard.setCardForegroundColor(ColorStateList.valueOf(transparentHighlight))
                }
            })
        }
        summaryUpdateAnimator?.start()
    }

    // ==================== Long Polling Functions ====================
    
    /**
     * Starts the long polling service to receive server updates
     */
    private fun startLongPolling() {
        // Don't start if polling is disabled
        if (!isPollingEnabled) {
            Log.d("MainActivity", "Long polling disabled by user")
            return
        }
        
        val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        val serverIp = prefs.getString(AudioService.KEY_SERVER_IP, AudioService.DEFAULT_SERVER_IP) ?: AudioService.DEFAULT_SERVER_IP
        val serverPort = prefs.getInt(AudioService.KEY_SERVER_PORT, AudioService.DEFAULT_SERVER_PORT)
        
        // Don't start if server is not configured
        if (serverIp == AudioService.DEFAULT_SERVER_IP || serverIp.isBlank()) {
            Log.d("MainActivity", "Long polling not started: server not configured")
            updatePollingDebugUI("inactive", "No configurado")
            pollingServerUrl.text = "Servidor: no configurado"
            return
        }
        
        val serverUrl = "https://$serverIp:$serverPort"
        
        // Stop existing service if running (without updating UI)
        longPollingService?.stop()
        longPollingService = null
        
        // Update debug UI AFTER stopping old service
        pollingServerUrl.text = "Server: $serverUrl"
        updatePollingDebugUI("connecting", "Conectando...")
        pollingLastEvent.text = "Iniciando polling..."
        
        // Create and start new polling service
        longPollingService = LongPollingService(this, serverUrl)
        longPollingService?.start(pollingScope)
        
        Log.d("MainActivity", "Long polling started for $serverUrl")
        addLogMessage("[${getCurrentTime()}] ${getString(R.string.polling_status_active)}")
    }
    
    /**
     * Stops the long polling service
     */
    private fun stopLongPolling() {
        longPollingService?.stop()
        longPollingService = null
        isPollingConnected = false
        updatePollingDebugUI("inactive", "Detenido")
    }
    
    /**
     * Handles a server update received via long polling.
     * Behavior is identical to ACTION_RESPONSE_RECEIVED: updates the summary card and speaks via TTS.
     */
    private fun handleServerUpdate(updateType: String, summary: String, changes: List<String>) {
        // Format the update message
        val typeLabel = when (updateType) {
            "cursor_update" -> getString(R.string.update_type_cursor)
            else -> getString(R.string.update_type_generic)
        }
        
        // Update debug UI
        pollingLastEvent.text = "📥 ${getCurrentTime()}: $typeLabel recibido"
        
        // Update the summary card with the new update
        val formattedSummary = if (changes.isNotEmpty()) {
            "$summary\n\n${getString(R.string.update_changes, changes.joinToString(", "))}"
        } else {
            summary
        }
        
        updateScreenSummary(formattedSummary)
        
        // Log the update
        addLogMessage("[${getCurrentTime()}] 📥 $typeLabel: $summary")
        
        // Speak the summary via TTS (same behavior as voice command responses)
        if (summary.isNotBlank()) {
            speakSummary(summary)
        }
    }
    
    /**
     * Updates the UI to reflect polling connection status
     */
    private fun updatePollingStatusUi(connected: Boolean) {
        isPollingConnected = connected
        if (connected) {
            updatePollingDebugUI("connected", "Conectado")
            pollingLastEvent.text = "✅ ${getCurrentTime()}: Conexión establecida"
        } else {
            updatePollingDebugUI("disconnected", "Desconectado")
        }
        Log.d("MainActivity", "Polling status: ${if (connected) "connected" else "disconnected"}")
    }
    
    /**
     * Updates the Long Polling debug UI card
     */
    private fun updatePollingDebugUI(status: String, message: String) {
        val colorRes = when (status) {
            "connected" -> R.color.md_theme_primary  // Green
            "connecting", "reconnecting" -> R.color.md_theme_secondary  // Yellow/Orange
            "error" -> R.color.md_theme_error  // Red
            "disabled" -> R.color.md_theme_outline  // Gray for disabled
            else -> R.color.md_theme_outline  // Gray for inactive/disconnected
        }
        pollingStatusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, colorRes)
        )
        pollingStatusText.text = when (status) {
            "connected" -> "🟢 $message"
            "connecting", "reconnecting" -> "🟡 $message"
            "error" -> "🔴 $message"
            "disabled" -> "⚫ $message"
            else -> "⚪ $message"
        }
        
        // Show/hide reconnect button based on status
        btnPollingReconnect.visibility = if (status == "disabled") View.GONE else View.VISIBLE
    }

    /**
     * Check if we have recording permission
     */
    private fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request recording permission
     */
    private fun requestRecordAudioPermission(activity: MainActivity) {
        val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 1001
        activity.requestPermissions(
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            RECORD_AUDIO_PERMISSION_REQUEST_CODE
        )
    }

    private fun logMessage(message: String) {
        runOnUiThread {
            logsTextView.append("$message\n")
        }
    }

    private fun playLastAudio() {
        if (currentAudioFile == null || !currentAudioFile!!.exists()) {
            Toast.makeText(this, getString(R.string.no_audio_available), Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "No audio file available to play")
            return
        }
        
        // Stop any currently playing audio
        stopAudioPlayback()
        
        try {
            audioPlayer = android.media.MediaPlayer().apply {
                setDataSource(currentAudioFile!!.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopAudioPlayback()
                    Log.d("MainActivity", "Audio playback completed")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("MainActivity", "Error playing audio: what=$what, extra=$extra")
                    stopAudioPlayback()
                    Toast.makeText(this@MainActivity, getString(R.string.error_playing_audio, "MediaPlayer error"), Toast.LENGTH_SHORT).show()
                    true
                }
                start()
            }
            Log.d("MainActivity", "Playing audio file: ${currentAudioFile!!.absolutePath}")
            Toast.makeText(this, getString(R.string.playing_audio_response), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error playing audio: ${e.message}", e)
            Toast.makeText(this, getString(R.string.error_playing_audio, e.message ?: "Unknown error"), Toast.LENGTH_SHORT).show()
            stopAudioPlayback()
        }
    }
    
    private fun stopAudioPlayback() {
        audioPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error stopping audio playback: ${e.message}", e)
            }
            audioPlayer = null
        }
    }

    private fun processAudioFile(file: File) {
        if (!file.exists()) {
            Log.e("MainActivity", getString(R.string.file_not_found, file.absolutePath))
            return
        }

        val fileSize = file.length()
        if (fileSize == 0L) {
            Log.e("MainActivity", getString(R.string.file_empty_corrupt, file.absolutePath))
            return
        }

        val sizeText = when {
            fileSize < 1024 -> getString(R.string.bytes, fileSize)
            fileSize < 1024 * 1024 -> getString(R.string.kb, fileSize / 1024)
            else -> getString(R.string.mb, fileSize / (1024.0 * 1024.0))
        }

        Log.d("MainActivity", getString(R.string.audio_recording_info, 
            getString(R.string.audio_recording),
            sizeText,
            file.absolutePath))
    }

    private fun onAudioPermissionGranted() {
        Log.d("MainActivity", getString(R.string.audio_permission_granted))
        startSimpleRecording()
    }

    private fun onAudioPermissionDenied() {
        Log.e("MainActivity", getString(R.string.audio_permission_denied))
    }

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
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

    private fun setupScreenshotSection() {
        // Setup click listener for fullscreen view
        screenshotImageView.setOnClickListener {
            showFullscreenImage()
        }
        
        // Setup refresh period button
        btnRefreshPeriod.text = if (autoRefreshEnabled) formatRefreshPeriod(refreshPeriodMs) else getString(R.string.disabled)
        btnRefreshPeriod.setOnClickListener {
            showRefreshPeriodMenu()
        }
        
        // Setup unlock screen button
        btnUnlockScreen.setOnClickListener {
            unlockScreen()
        }
        
        // Fetch initial screenshot with a slight delay to allow the UI to initialize
        Handler(mainLooper).postDelayed({
            if (!isVncModeEnabled) {
                captureNewScreenshot()
            }
        }, 1000)
        
        // Setup auto-refresh
        if (autoRefreshEnabled) {
            if (!isVncModeEnabled) {
                startAutoRefresh()
            }
        }
        
        btnCaptureScreenshot.setOnClickListener {
            if (!isVncModeEnabled) {
                captureNewScreenshot()
            }
        }
        applyVncMode(isVncModeEnabled)
    }
    
    private fun fetchLatestScreenshot() {
        if (isVncModeEnabled) {
            return
        }
        screenshotScope.launch {
            try {
                updateScreenshotState(ScreenshotState.Loading)
                
                val serverUrl = getServerUrl()
                val request = buildApiRequest("$serverUrl/screenshots/latest?limit=1")
                
                withContext(Dispatchers.IO) {
                    try {
                        val response = okHttpClient.newCall(request).execute()
                        handleScreenshotResponse(response)
                    } catch (e: IOException) {
                        Log.e("Screenshot", "Network error fetching screenshot", e)
                        // Ensure we handle this on the main thread
                        withContext(Dispatchers.Main) {
                            handleScreenshotError(e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Screenshot", "General error fetching screenshot", e)
                // Ensure we handle this on the main thread
                withContext(Dispatchers.Main) {
                    handleScreenshotError(e)
                }
            }
        }
    }
    
    private fun buildApiRequest(url: String) = Request.Builder()
        .url(url)
        .build()

    private suspend fun handleScreenshotResponse(response: Response) {
        when {
            response.isSuccessful -> {
                val jsonStr = response.body?.string()
                if (jsonStr.isNullOrEmpty()) {
                    updateScreenshotState(ScreenshotState.Error("Respuesta vacía del servidor"))
                    return
                }
                
                try {
                    val json = JSONObject(jsonStr)
                    if (json.has("status") && json.getString("status") == "success") {
                        processScreenshotJson(json)
                    } else if (json.has("error")) {
                        val errorMessage = json.optString("error", "Error desconocido")
                        updateScreenshotState(ScreenshotState.Error(errorMessage))
                    } else {
                        updateScreenshotState(ScreenshotState.Error("Formato de respuesta no reconocido"))
                    }
                } catch (e: Exception) {
                    Log.e("Screenshot", "Error parsing JSON response", e)
                    updateScreenshotState(ScreenshotState.Error("Error al procesar respuesta: ${e.message}"))
                }
            }
            else -> {
                // Handle error response with new format
                val errorBody = response.body?.string()
                try {
                    if (!errorBody.isNullOrEmpty()) {
                        val errorJson = JSONObject(errorBody)
                        if (errorJson.has("error") && errorJson.has("status") && errorJson.getString("status") == "error") {
                            val errorMessage = errorJson.getString("error")
                            updateScreenshotState(ScreenshotState.Error(errorMessage))
                            return
                        }
                    }
                } catch (e: Exception) {
                    // If parsing fails, fall back to generic error
                    Log.e("Screenshot", "Error parsing error response", e)
                }
                // Default error handling if new format not detected
                updateScreenshotState(ScreenshotState.Error("Error ${response.code}"))
            }
        }
    }

    private suspend fun processScreenshotJson(json: JSONObject) {
        Log.d("Screenshot", "Processing JSON response: $json")
        
        if (!json.has("screenshots")) {
            Log.d("Screenshot", "No 'screenshots' field in JSON response")
            updateScreenshotState(ScreenshotState.Error("Formato de respuesta no válido"))
            return
        }

        try {
            // Check if response count is 0
            val count = json.optInt("count", 0)
            if (count == 0) {
                Log.d("Screenshot", "No screenshots available (count=0)")
                updateScreenshotState(ScreenshotState.NoScreenshots)
                disableScreenshotControls()
                return
            }

            // Handle screenshots as an array
            val screenshotsArray = json.getJSONArray("screenshots")
            if (screenshotsArray.length() == 0) {
                Log.d("Screenshot", "No screenshots available (empty array)")
                updateScreenshotState(ScreenshotState.NoScreenshots)
                disableScreenshotControls()
                return
            }

            // Get the latest screenshot (first in the array)
            val latestFilename = screenshotsArray.getString(0)
            Log.d("Screenshot", "Latest screenshot: $latestFilename")

            // Get the file's modification time
            val timestamp = getCurrentTime()
            
            withContext(Dispatchers.Main) {
                updateScreenshotState(ScreenshotState.Success(timestamp))
                
                if (latestFilename.isNotEmpty()) {
                    fetchScreenshot(latestFilename)
                }
            }
        } catch (e: Exception) {
            Log.e("Screenshot", "Error processing screenshots JSON", e)
            updateScreenshotState(ScreenshotState.Error("Error al procesar los datos: ${e.message}"))
        }
    }

    private fun JSONObject.formatTimestamp(): String {
        return optLong("modified", 0).let { modified ->
            if (modified > 0) {
                Date(modified * 1000).formatToDateTime()
            } else {
                "desconocido"
            }
        }
    }

    private fun Date.formatToDateTime(): String {
        return SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(this)
    }

    private suspend fun updateScreenshotState(state: ScreenshotState) {
        // Always ensure we're on the main thread for any UI updates
        runOnUiThread {
            when (state) {
                is ScreenshotState.Success -> {
                    screenshotStatusText.text = getString(R.string.last_capture, state.timestamp)
                    screenshotLoadingProgress.visibility = View.GONE
                    // Also hide in fullscreen dialog if open
                    activeFullscreenDialog?.setScreenshotProgressVisible(false)
                    // Enable the refresh and capture buttons
                    btnCaptureScreenshot.isEnabled = true
                }
                is ScreenshotState.Error -> {
                    screenshotStatusText.text = state.message
                    screenshotLoadingProgress.visibility = View.GONE
                    // Also hide in fullscreen dialog if open
                    activeFullscreenDialog?.setScreenshotProgressVisible(false)
                    disableScreenshotControls()
                    // Still enable the capture button on error to allow retry
                    btnCaptureScreenshot.isEnabled = true
                }
                is ScreenshotState.NoScreenshots -> {
                    screenshotStatusText.text = getString(R.string.no_screenshots)
                    screenshotLoadingProgress.visibility = View.GONE
                    // Also hide in fullscreen dialog if open
                    activeFullscreenDialog?.setScreenshotProgressVisible(false)
                    disableScreenshotControls()
                    // Still enable the capture button when no screenshots
                    btnCaptureScreenshot.isEnabled = true
                    addLogMessage("[${getCurrentTime()}] ⚠️ ${getString(R.string.no_screenshots_available)}")
                }
                is ScreenshotState.Loading -> {
                    screenshotStatusText.text = getString(R.string.loading_screenshots)
                    screenshotLoadingProgress.visibility = View.VISIBLE
                    // Also show in fullscreen dialog if open
                    activeFullscreenDialog?.setScreenshotProgressVisible(true)
                    // Disable capture button while loading
                    btnCaptureScreenshot.isEnabled = false
                }
            }
        }
    }

    private fun disableScreenshotControls() {
        // Always run UI updates on the main thread
        runOnUiThread {
            screenshotImageView.setImageBitmap(null)
            screenshotImageView.visibility = View.INVISIBLE  // Hide the image view when no image
            screenshotLoadingProgress.visibility = View.GONE
            // Also hide in fullscreen dialog if open
            activeFullscreenDialog?.setScreenshotProgressVisible(false)
        }
    }

    private suspend fun handleScreenshotError(error: Exception) {
        val errorMessage = when (error) {
            is IOException -> "Error de conexión: ${error.message}"
            else -> "Error: ${error.message}"
        }
        
        // Always update UI on the main thread
        runOnUiThread {
            // Clear and hide the image view
            screenshotImageView.apply {
                setImageBitmap(null)
                visibility = View.INVISIBLE
            }
            
            // Hide loading progress bar
            screenshotLoadingProgress.visibility = View.GONE
            // Also hide in fullscreen dialog if open
            activeFullscreenDialog?.setScreenshotProgressVisible(false)
            
            // Update status and log error
            screenshotStatusText.text = errorMessage
            addLogMessage("[${getCurrentTime()}] ❌ $errorMessage")
        }
        
        Log.e("Screenshot", "Error fetching screenshot", error)
    }

    private fun fetchScreenshot(filename: String) {
        screenshotScope.launch {
            try {
                val serverUrl = getServerUrl()
                // Add a timestamp to prevent caching
                val timestamp = System.currentTimeMillis()
                val imageUrl = "$serverUrl/screenshots/$filename?t=$timestamp"
                
                Log.d("Screenshot", "Fetching screenshot from URL: $imageUrl")
                addLogMessage("[${getCurrentTime()}] 🖼️ Descargando captura: $filename")
                
                // Show loading progress bar and hide image view while loading
                withContext(Dispatchers.Main) {
                    screenshotLoadingProgress.visibility = View.VISIBLE
                    // Also show in fullscreen dialog if open
                    activeFullscreenDialog?.setScreenshotProgressVisible(true)
                    screenshotImageView.visibility = View.INVISIBLE
                    screenshotImageView.setImageBitmap(null)
                }
                
                withContext(Dispatchers.IO) {
                    try {
                        val request = Request.Builder()
                            .url(imageUrl)
                            .header("Cache-Control", "no-cache")
                            .build()
                        
                        val response = okHttpClient.newCall(request).execute()
                        Log.d("Screenshot", "Response code: ${response.code}")
                        
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string()
                            if (!errorBody.isNullOrEmpty()) {
                                try {
                                    val errorJson = JSONObject(errorBody)
                                    if (errorJson.has("error")) {
                                        throw IOException("Error del servidor: ${errorJson.getString("error")}")
                                    }
                                } catch (e: Exception) {
                                    // If parsing fails, use the default error message
                                }
                            }
                            throw IOException("Error al obtener imagen: ${response.code}")
                        }
                        
                        val contentType = response.header("Content-Type")
                        Log.d("Screenshot", "Response Content-Type: $contentType")
                        
                        if (contentType?.startsWith("image/") != true) {
                            throw IOException("Tipo de contenido no válido: $contentType")
                        }
                        
                        val imageBytes = response.body?.bytes() 
                            ?: throw IOException("Respuesta vacía del servidor")
                            
                        Log.d("Screenshot", "Received ${imageBytes.size} bytes")
                        
                        if (imageBytes.isEmpty()) {
                            throw IOException("Imagen vacía recibida")
                        }
                        
                        // Decode with downsampling to reduce memory usage
                        val (targetWidth, targetHeight) = getScreenshotTargetDimensions()
                        val bitmap = decodeScaledBitmap(imageBytes, targetWidth, targetHeight)
                            ?: throw IOException("Error al decodificar la imagen")
                        
                        withContext(Dispatchers.Main) {
                            // Hide loading progress bar
                            screenshotLoadingProgress.visibility = View.GONE
                            // Also hide in fullscreen dialog if open
                            activeFullscreenDialog?.setScreenshotProgressVisible(false)
                            
                            // Update the image view
                            screenshotImageView.apply {
                                setImageBitmap(bitmap)
                                visibility = View.VISIBLE
                            }
                            
                            // Update fullscreen dialog if it's open
                            activeFullscreenDialog?.updateBitmap(bitmap)
                            
                            // Log success
                            addLogMessage("[${getCurrentTime()}] ✅ Captura cargada correctamente: $filename")
                            
                            // Update UI state based on filename
                            updateScreenshotUIState(filename)
                        }
                    } catch (e: Exception) {
                        Log.e("Screenshot", "Error fetching screenshot", e)
                        withContext(Dispatchers.Main) {
                            // Hide loading progress bar on error
                            screenshotLoadingProgress.visibility = View.GONE
                            // Also hide in fullscreen dialog if open
                            activeFullscreenDialog?.setScreenshotProgressVisible(false)
                            
                            // Handle error
                            handleScreenshotError(e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Screenshot", "General error in fetchScreenshot", e)
                withContext(Dispatchers.Main) {
                    // Hide loading progress bar on error
                    screenshotLoadingProgress.visibility = View.GONE
                    // Also hide in fullscreen dialog if open
                    activeFullscreenDialog?.setScreenshotProgressVisible(false)
                    
                    // Handle error
                    handleScreenshotError(e)
                }
            }
        }
    }
    
    private fun startAutoRefresh() {
        // Cancel any existing auto-refresh
        stopAutoRefresh()

        if (isVncModeEnabled) {
            return
        }
        
        // Don't start if auto-refresh is disabled
        if (!autoRefreshEnabled) {
            return
        }
        
        // Create a new handler and runnable
        refreshHandler = Handler(mainLooper)
        refreshRunnable = object : Runnable {
            override fun run() {
                captureNewScreenshot()
                refreshHandler?.postDelayed(this, refreshPeriodMs)
            }
        }
        
        // Start the initial refresh after the configured delay
        refreshHandler?.postDelayed(refreshRunnable!!, refreshPeriodMs)
    }

    private fun stopAutoRefresh() {
        refreshRunnable?.let { runnable ->
            refreshHandler?.removeCallbacks(runnable)
        }
        refreshRunnable = null
    }

    private fun applyVncMode(enabled: Boolean) {
        if (enabled) {
            stopAutoRefresh()
            screenshotImageView.visibility = View.GONE
            vncStreamContainer.visibility = View.VISIBLE
            screenshotLoadingProgress.visibility = View.GONE
            btnCaptureScreenshot.isEnabled = false
            btnUnlockScreen.isEnabled = false
            btnRefreshPeriod.isEnabled = false
            screenshotStatusText.text = getString(R.string.vnc_mode_active)
            startVncStream()
        } else {
            stopVncStream()
            screenshotImageView.visibility = View.VISIBLE
            vncStreamContainer.visibility = View.GONE
            screenshotLoadingProgress.visibility = View.GONE
            btnCaptureScreenshot.isEnabled = true
            btnUnlockScreen.isEnabled = true
            btnRefreshPeriod.isEnabled = true
            screenshotStatusText.text = getString(R.string.no_screenshots_available)
            if (autoRefreshEnabled) {
                startAutoRefresh()
            }
        }
    }

    private fun startVncStream() {
        val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(AudioService.KEY_SERVER_IP, AudioService.DEFAULT_SERVER_IP)
            ?: AudioService.DEFAULT_SERVER_IP
        val vncPort = prefs.getInt(VncPreferences.KEY_VNC_PORT, VncPreferences.DEFAULT_VNC_PORT)
        val vncPassword = prefs.getString(VncPreferences.KEY_VNC_PASSWORD, "").orEmpty()
        if (vncView == null) {
            vncView = android.vnc.VncCanvasView(this)
            vncStreamContainer.removeAllViews()
            vncStreamContainer.addView(vncView)
        }
        vncView?.setConnectionInfo(host, vncPort, vncPassword)
        vncView?.connect()
    }

    private fun stopVncStream() {
        vncView?.disconnect()
        vncView?.shutdown()
        vncView = null
        vncStreamContainer.removeAllViews()
    }

    private fun showRefreshPeriodMenu() {
        val popup = PopupMenu(this, btnRefreshPeriod)
        popup.menu.apply {
            add("5s").setOnMenuItemClickListener {
                updateRefreshPeriod(5000)
                true
            }
            add("10s").setOnMenuItemClickListener {
                updateRefreshPeriod(10000)
                true
            }
            add("30s").setOnMenuItemClickListener {
                updateRefreshPeriod(30000)
                true
            }
            add("60s").setOnMenuItemClickListener {
                updateRefreshPeriod(60000)
                true
            }
            add("Disable").setOnMenuItemClickListener {
                disableAutoRefresh()
                true
            }
        }
        popup.show()
    }
    
    private fun updateRefreshPeriod(periodMs: Long) {
        refreshPeriodMs = periodMs
        btnRefreshPeriod.text = formatRefreshPeriod(periodMs)
        autoRefreshEnabled = true
        
        // Restart auto-refresh with the new period
        startAutoRefresh()
        
        // Notify the user
        addLogMessage("[${getCurrentTime()}] ⏱️ ${getString(R.string.refresh_period_changed, formatRefreshPeriod(periodMs))}")
    }
    
    private fun disableAutoRefresh() {
        autoRefreshEnabled = false
        btnRefreshPeriod.text = getString(R.string.disabled)
        stopAutoRefresh()
        
        // Notify the user
        addLogMessage("[${getCurrentTime()}] ⏱️ ${getString(R.string.auto_refresh_disabled)}")
    }
    
    private fun formatRefreshPeriod(periodMs: Long): String {
        return when (periodMs) {
            5000L -> "5s"
            10000L -> "10s"
            30000L -> "30s"
            60000L -> "60s"
            else -> "${periodMs/1000}s"
        }
    }

    private fun updateScreenshotUIState(filename: String) {
        // Update status text while preserving timestamp
        val currentText = screenshotStatusText.text.toString()
        val baseText = if (currentText.contains(" (Vista:")) {
            currentText.split(" (Vista:").first()
        } else {
            currentText
        }
        
        screenshotStatusText.text = "$baseText (Vista: Captura)"
    }

    /**
     * Decodes a bitmap from byte array with downsampling to fit the target ImageView size.
     * This reduces memory usage significantly when displaying large screenshots.
     */
    private fun decodeScaledBitmap(imageBytes: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap? {
        // First decode with inJustDecodeBounds=true to get dimensions
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        
        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
        options.inJustDecodeBounds = false
        
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
    }

    /**
     * Calculate the largest inSampleSize value that is a power of 2 and keeps both
     * height and width larger than the requested height and width.
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Gets the target dimensions for screenshot ImageView based on screen density.
     * ImageView height is 200dp, width is match_parent.
     */
    private fun getScreenshotTargetDimensions(): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val targetHeight = (200 * density).toInt()  // 200dp as defined in layout
        val targetWidth = resources.displayMetrics.widthPixels
        return Pair(targetWidth, targetHeight)
    }

    private fun showFullscreenImage() {
        // Get the current bitmap from the ImageView
        val bitmap = (screenshotImageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        
        // Create and show the fullscreen dialog
        activeFullscreenDialog = FullscreenImageDialog(this, bitmap) { 
            // Clear reference when dialog is dismissed
            activeFullscreenDialog = null
        }
        activeFullscreenDialog?.show()
        
        // Sync screenshot progress indicator state with the dialog
        activeFullscreenDialog?.setScreenshotProgressVisible(screenshotLoadingProgress.visibility == View.VISIBLE)
    }

    private fun loadAppPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isLogsExpanded = prefs.getBoolean(KEY_IS_LOGS_EXPANDED, false)
        refreshPeriodMs = prefs.getLong(KEY_REFRESH_PERIOD, 30000)
        autoRefreshEnabled = prefs.getBoolean(KEY_AUTO_REFRESH_ENABLED, true)
    }

    private fun maybeShowTutorial() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_TUTORIAL_SHOWN, false)) {
            window.decorView.post {
                showTutorialDialog(markAsSeen = true)
            }
        }
    }

    private fun showTutorialDialog(markAsSeen: Boolean) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_tutorial)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        dialog.findViewById<MaterialButton>(R.id.tutorialCloseButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<MaterialButton>(R.id.tutorialSettingsButton).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        dialog.setOnDismissListener {
            if (markAsSeen) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_TUTORIAL_SHOWN, true).apply()
            }
        }

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    
    private fun saveAppPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_IS_LOGS_EXPANDED, isLogsExpanded)
            .putBoolean(KEY_IS_COMMAND_HISTORY_EXPANDED, isCommandHistoryExpanded)
            .putBoolean(KEY_IS_FAVORITES_EXPANDED, isFavoritesExpanded)
            .putString(KEY_SCREENSHOT_REFRESH_PERIOD, btnRefreshPeriod.text.toString())
            .apply()
    }

    private fun applyThemeFromPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isDarkTheme = prefs.getBoolean(KEY_IS_DARK_THEME, false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkTheme) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }
    
    private fun captureNewScreenshot() {
        if (isVncModeEnabled) {
            return
        }
        screenshotScope.launch {
            try {
                // Show loading progress bar instead of just text
                withContext(Dispatchers.Main) {
                    screenshotLoadingProgress.visibility = View.VISIBLE
                    // Also show in fullscreen dialog if open
                    activeFullscreenDialog?.setScreenshotProgressVisible(true)
                    screenshotStatusText.text = getString(R.string.capturing_screenshot)
                    btnCaptureScreenshot.isEnabled = false
                }
                
                val serverUrl = getServerUrl()
                // Update to request JSON format for proper response handling
                val request = Request.Builder()
                    .url("${serverUrl}/screenshot/capture?format=json")
                    .post(RequestBody.Companion.create(null, ByteArray(0)))
                    .build()
                
                Log.d("Screenshot", "Enviando petición de captura a: ${request.url}")

                withContext(Dispatchers.IO) {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            // Check for structured error response
                            val errorBody = response.body?.string()
                            if (!errorBody.isNullOrEmpty()) {
                                try {
                                    val errorJson = JSONObject(errorBody)
                                    if (errorJson.has("error") && errorJson.has("status") && 
                                        errorJson.getString("status") == "error") {
                                        val errorMessage = errorJson.getString("error")
                                        throw IOException(getString(R.string.error_generic, errorMessage))
                                    }
                                } catch (e: Exception) {
                                    // If parsing fails, use the default error message
                                    Log.e("Screenshot", "Error parsing capture error response", e)
                                }
                            }
                            throw IOException(getString(R.string.error_generic, response.code))
                        }
                        
                        // Check content type to determine how to handle the response
                        val contentType = response.header("Content-Type")
                        
                        if (contentType?.contains("application/json") == true) {
                            // Handle JSON response
                            val responseBody = response.body?.string()
                            val jsonResponse = JSONObject(responseBody)
                            
                            if (jsonResponse.has("status") && jsonResponse.getString("status") == "success") {
                                // Extract filename directly from response instead of refetching
                                val filename = jsonResponse.optString("filename", "")
                                
                                withContext(Dispatchers.Main) {
                                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.screenshot_taken_successfully)}")
                                    
                                    // Check if we have base64 image data in the response
                                    if (jsonResponse.has("image_data") && filename.isNotEmpty()) {
                                        val base64ImageData = jsonResponse.getString("image_data")
                                        try {
                                            // Convert base64 string to bitmap with downsampling
                                            val imageBytes = android.util.Base64.decode(base64ImageData, android.util.Base64.DEFAULT)
                                            val (targetWidth, targetHeight) = getScreenshotTargetDimensions()
                                            val bitmap = decodeScaledBitmap(imageBytes, targetWidth, targetHeight)
                                            
                                            // Display bitmap directly
                                            if (bitmap != null) {
                                                screenshotImageView.apply {
                                                    setImageBitmap(bitmap)
                                                    visibility = View.VISIBLE
                                                }
                                                
                                                // Update fullscreen dialog if it's open
                                                activeFullscreenDialog?.updateBitmap(bitmap)
                                                
                                                // Update UI state
                                                val timestamp = jsonResponse.optLong("timestamp", 0)
                                                val date = if (timestamp > 0) Date(timestamp * 1000).formatToDateTime() else "desconocido"
                                                screenshotStatusText.text = getString(R.string.last_capture, date)
                                                screenshotLoadingProgress.visibility = View.GONE
                                                // Also hide in fullscreen dialog if open
                                                activeFullscreenDialog?.setScreenshotProgressVisible(false)
                                                addLogMessage("[${getCurrentTime()}] ✅ ${getString(R.string.screenshot_displayed_successfully)}")
                                                return@withContext
                                            }
                                        } catch (e: Exception) {
                                            Log.e("Screenshot", "Error decoding base64 image", e)
                                            // Fall back to fetching if direct display fails
                                        }
                                    }
                                    
                                    // Fall back to fetching the latest screenshot if direct display isn't possible
                                    fetchLatestScreenshot()
                                }
                            } else {
                                throw IOException(getString(R.string.response_format_invalid))
                            }
                        } else {
                            // Assume success if we got a non-JSON response with successful status code
                            withContext(Dispatchers.Main) {
                                addLogMessage("[${getCurrentTime()}] ${getString(R.string.screenshot_taken_successfully)}")
                                // After capture, fetch latest screenshot
                                fetchLatestScreenshot()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    screenshotStatusText.text = getString(R.string.error_generic, e.message)
                    screenshotLoadingProgress.visibility = View.GONE
                    // Also hide in fullscreen dialog if open
                    activeFullscreenDialog?.setScreenshotProgressVisible(false)
                    btnCaptureScreenshot.isEnabled = true
                    addLogMessage("[${getCurrentTime()}] ❌ ${getString(R.string.error_generic, e.message)}")
                }
                Log.e("Screenshot", "Error capturing screenshot", e)
            }
        }
    }



    private fun getServerUrl(): String {
        val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        val serverIp = prefs.getString(KEY_SERVER_IP, AudioService.DEFAULT_SERVER_IP)
            ?: AudioService.DEFAULT_SERVER_IP
        val serverPort = prefs.getInt(KEY_SERVER_PORT, AudioService.DEFAULT_SERVER_PORT)
        return "https://$serverIp:$serverPort"
    }

    private fun getUnlockPassword(): String {
        val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_UNLOCK_PASSWORD, "your_password") ?: "your_password"
    }

    /**
     * Update the logs toggle button icon based on current state
     */
    private fun updateLogsToggleButton() {
        val iconResId = if (isLogsExpanded) 
            R.drawable.ic_collapse else R.drawable.ic_expand
        btnToggleLogs.setIconResource(iconResId)
    }

    /**
     * Toggle the visibility of the logs section with animation
     */
    private fun toggleLogsVisibility() {
        // Always run UI updates on the main thread
        runOnUiThread {
            // Update button icon
            updateLogsToggleButton()
            
            // Update content visibility with animation
            if (isLogsExpanded) {
                logsContent.visibility = View.VISIBLE
                logsContent.alpha = 0f
                logsContent.animate().alpha(1f).setDuration(500).start()
                
                // Add an initial hint message if the logs are empty
                if (logBuffer.isEmpty()) {
                    addLogMessage(getString(R.string.log_initial_hint))
                    addLogMessage(getString(R.string.log_highlight_hint))
                    addLogMessage(getString(R.string.log_separator))
                }
                
                // Find the parent NestedScrollView
                val nestedScrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.nestedScrollView)
                
                // We need to wait until the content is laid out to calculate proper scroll position
                logsContent.post {
                    // Get the coordinates of the card that contains this section
                    val loggingCard = findViewById<View>(R.id.loggingCard)
                    
                    // Calculate the position to scroll to - we want to show the entire card
                    // by scrolling to a position that makes the card visible
                    if (loggingCard != null) {
                        val scrollViewHeight = nestedScrollView.height
                        val cardTop = loggingCard.top
                        val cardBottom = loggingCard.bottom
                        val cardHeight = loggingCard.height
                        
                        // Calculate the appropriate scroll position
                        val targetScrollY = when {
                            cardHeight > scrollViewHeight -> cardTop // If card is taller than scroll view, scroll to top
                            cardBottom > (nestedScrollView.scrollY + scrollViewHeight) -> cardBottom - scrollViewHeight // Show bottom if below view
                            cardTop < nestedScrollView.scrollY -> cardTop // Show top if above view
                            else -> nestedScrollView.scrollY // Don't scroll if already visible
                        }
                        
                        // Use a custom smooth scroll with 500ms duration
                        smoothScrollTo(nestedScrollView, targetScrollY, 500)
                    }
                }
            } else {
                logsContent.animate().alpha(0f).setDuration(500)
                    .withEndAction { logsContent.visibility = View.GONE }.start()
            }
        }
    }
    
    /**
     * Custom smooth scroll function with specified duration
     */
    private fun smoothScrollTo(scrollView: androidx.core.widget.NestedScrollView, targetY: Int, duration: Long) {
        val startY = scrollView.scrollY
        val distanceY = targetY - startY
        val startTime = System.currentTimeMillis()
        
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val t = if (elapsed > duration) 1f else elapsed.toFloat() / duration
                // Use ease-in-out interpolation for smoother scrolling feel
                val interpolatedT = interpolateEaseInOut(t)
                val y = startY + (distanceY * interpolatedT).toInt()
                
                scrollView.scrollTo(0, y)
                
                if (elapsed < duration) {
                    scrollView.postOnAnimation(this)
                }
            }
        }
        
        scrollView.postOnAnimation(runnable)
    }
    
    /**
     * Ease-in-out interpolation for smoother motion
     */
    private fun interpolateEaseInOut(t: Float): Float {
        return if (t < 0.5) 2 * t * t else -1 + (4 - 2 * t) * t
    }

    /**
     * Load logs expanded state from preferences
     */
    private fun loadLogsState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isLogsExpanded = prefs.getBoolean(KEY_IS_LOGS_EXPANDED, false)
        updateLogsToggleButton()
        
        // Apply the state immediately
        if (isLogsExpanded) {
            logsContent.visibility = View.VISIBLE
            logsContent.alpha = 1f
        } else {
            logsContent.visibility = View.GONE
            logsContent.alpha = 0f
        }
    }
    
    /**
     * Save logs expanded state to preferences
     */
    private fun saveLogsState() {
        // This functionality has been moved to saveAppPreferences
        // Kept for backward compatibility but simply calls the new method
        saveAppPreferences()
    }

    // Add this inside the BroadcastReceiver code or wherever you handle responses from the voice command
    private fun handleVoiceCommandResponse(success: Boolean, message: String) {
        // Process the response...
        
        // When processing is complete, switch back to the ready state
        showReadyState()
        
        // Display result to user
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Sends a request to the server to unlock the remote screen
     */
    private fun unlockScreen() {
        screenshotScope.launch {
            try {
                // Get the server URL
                val serverUrl = getServerUrl()
                
                // Get the password from saved settings
                val password = getUnlockPassword()
                
                // Create a simple JSON object with unlock parameters
                val jsonObject = JSONObject().apply {
                    put("password", password) // Use the configured password
                    put("delay", 2)
                    put("interval", 0.1)
                }
                
                // Create request body using non-deprecated method
                val requestBody = jsonObject.toString().toByteArray()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                
                // Build the request
                val request = Request.Builder()
                    .url("${serverUrl}/unlock-screen")
                    .post(requestBody)
                    .build()
                
                addLogMessage("[${getCurrentTime()}] 🔓 Enviando solicitud para desbloquear pantalla...")
                
                // Execute the request in IO thread
                withContext(Dispatchers.IO) {
                    try {
                        okHttpClient.newCall(request).execute().use { response ->
                            val responseBody = response.body?.string()
                            
                            withContext(Dispatchers.Main) {
                                if (response.isSuccessful && responseBody != null) {
                                    try {
                                        val jsonResponse = JSONObject(responseBody)
                                        if (jsonResponse.optString("status") == "success") {
                                            addLogMessage("[${getCurrentTime()}] ✅ ${getString(R.string.unlock_screen_started)}")
                                            Toast.makeText(this@MainActivity, getString(R.string.unlock_screen_started), Toast.LENGTH_SHORT).show()
                                            
                                            // Add a small delay and then take a screenshot to verify unlock
                                            screenshotScope.launch {
                                                // Wait for the unlock to complete
                                                delay(1500) // 1.5 seconds delay - allow time for unlock to finish
                                                addLogMessage("[${getCurrentTime()}] 📸 ${getString(R.string.capturing_screenshot_after_unlock)}")
                                                captureNewScreenshot() // Take a screenshot to confirm unlock
                                            }
                                        } else {
                                            val errorMessage = jsonResponse.optString("message", "Error desconocido")
                                            addLogMessage("[${getCurrentTime()}] ❌ ${getString(R.string.error_unlocking_screen, errorMessage)}")
                                            Toast.makeText(this@MainActivity, getString(R.string.error_generic, errorMessage), Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: JSONException) {
                                        addLogMessage("[${getCurrentTime()}] ❌ ${getString(R.string.error_processing_data, e.message)}")
                                        Toast.makeText(this@MainActivity, getString(R.string.error_processing_data, e.message), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    val errorMsg = getString(R.string.error_unlocking_screen, response.code)
                                    addLogMessage("[${getCurrentTime()}] ❌ $errorMsg")
                                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: IOException) {
                        withContext(Dispatchers.Main) {
                            val errorMsg = getString(R.string.connection_error_generic, e.message)
                            addLogMessage("[${getCurrentTime()}] ❌ $errorMsg")
                            Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                // Handle any other exceptions
                withContext(Dispatchers.Main) {
                    val errorMsg = getString(R.string.error_generic, e.message)
                    addLogMessage("[${getCurrentTime()}] ❌ $errorMsg")
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupCommandHistory() {
        // Initialize views
        commandHistoryRecyclerView = findViewById(R.id.commandHistoryRecyclerView)
        noCommandHistoryText = findViewById(R.id.noCommandHistoryText)
        btnRefreshCommandHistory = findViewById(R.id.btnRefreshCommandHistory)
        btnToggleCommandHistory = findViewById(R.id.btnToggleCommandHistory)
        commandHistoryContent = findViewById(R.id.commandHistoryContent)
        
        // Get the command history scroll view
        val commandHistoryScrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.commandHistoryScrollView)
        
        // Configure the scroll view
        commandHistoryScrollView.apply {
            isNestedScrollingEnabled = true
            isFillViewport = true
            // Set a fixed height limit in case of many items
            val layoutParams = this.layoutParams
            layoutParams.height = resources.displayMetrics.heightPixels / 3 // Take up to 1/3 of screen height
            this.layoutParams = layoutParams
        }
        
        // Set up RecyclerView with retry command listener
        commandHistoryRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        commandHistoryAdapter = CommandHistoryAdapter(
            emptyList(),
            object : CommandHistoryAdapter.OnRetryCommandListener {
                override fun onRetryCommand(command: CommandHistoryEntry) {
                    handleRetryCommand(command)
                }
            },
            object : CommandHistoryAdapter.OnSaveAsFavoriteListener {
                override fun onSaveAsFavorite(command: CommandHistoryEntry) {
                    handleSaveAsFavorite(command)
                }
            }
        )
        commandHistoryRecyclerView.adapter = commandHistoryAdapter
        
        // Set up toggle button
        btnToggleCommandHistory.setOnClickListener {
            isCommandHistoryExpanded = !isCommandHistoryExpanded
            toggleCommandHistoryVisibility()
        }
        
        // Set up refresh button
        btnRefreshCommandHistory.setOnClickListener {
            fetchCommandHistory()
        }
        
        // Allow clicking on header to toggle
        findViewById<LinearLayout>(R.id.commandHistoryHeader).setOnClickListener {
            isCommandHistoryExpanded = !isCommandHistoryExpanded
            toggleCommandHistoryVisibility()
        }
        
        // Load initial state
        loadCommandHistoryState()
        
        // Don't fetch command history immediately - we'll do this later
        // to avoid accessing uninitialized properties
    }
    
    /**
     * Handles retry of a failed command by sending it directly to the /command endpoint
     */
    private fun handleRetryCommand(command: CommandHistoryEntry) {
        // Get the server URL
        val serverUrl = getServerUrl()
        
        // Show toast to inform user that we're retrying the command
        Toast.makeText(
            this,
            getString(R.string.retrying_command, command.command),
            Toast.LENGTH_SHORT
        ).show()
        
        // Log the retry attempt
        addLogMessage("[${getCurrentTime()}] Retrying command: ${command.command}")
        
        // Show processing state
        showProcessingState()
        
        // Send the command to the server
        lifecycleScope.launch {
            try {
                // Send the command text to the server's /command endpoint
                val result = CommandHistoryUtils.sendTextCommand(
                    serverUrl = serverUrl,
                    client = okHttpClient,
                    commandText = command.command
                )
                
                // Return to ready state
                showReadyState()
                
                // Show the result to the user
                val message = if (result.success) {
                    getString(R.string.command_retry_success)
                } else {
                    getString(R.string.command_retry_failed, result.message)
                }
                
                // Show toast with the result
                Toast.makeText(
                    this@MainActivity,
                    message,
                    Toast.LENGTH_LONG
                ).show()
                
                // Log the result
                addLogMessage("[${getCurrentTime()}] $message")
                
                // Refresh the command history
                fetchCommandHistory()
            } catch (e: Exception) {
                // Handle any exceptions
                showReadyState()
                
                // Show error toast
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.command_retry_error, e.message),
                    Toast.LENGTH_LONG
                ).show()
                
                // Log the error
                addLogMessage("[${getCurrentTime()}] Error retrying command: ${e.message}")
            }
        }
    }
    
    private fun toggleCommandHistoryVisibility() {
        if (isCommandHistoryExpanded) {
            // Show content with animation
            commandHistoryContent.visibility = View.VISIBLE
            commandHistoryContent.animate()
                .alpha(1f)
                .setDuration(300)
                .setListener(null)
            
            // Update toggle button icon
            btnToggleCommandHistory.setIconResource(R.drawable.ic_collapse)
        } else {
            // Hide content with animation
            commandHistoryContent.animate()
                .alpha(0f)
                .setDuration(300)
                .setListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        commandHistoryContent.visibility = View.GONE
                    }
                })
            
            // Update toggle button icon
            btnToggleCommandHistory.setIconResource(R.drawable.ic_expand)
        }
        
        // Save state to preferences
        saveAppPreferences()
    }
    
    private fun loadCommandHistoryState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isCommandHistoryExpanded = prefs.getBoolean(KEY_IS_COMMAND_HISTORY_EXPANDED, false)
        
        // Apply the state immediately
        if (isCommandHistoryExpanded) {
            commandHistoryContent.visibility = View.VISIBLE
            commandHistoryContent.alpha = 1f
            btnToggleCommandHistory.setIconResource(R.drawable.ic_collapse)
        } else {
            commandHistoryContent.visibility = View.GONE
            commandHistoryContent.alpha = 0f
            btnToggleCommandHistory.setIconResource(R.drawable.ic_expand)
        }
    }
    
    private fun fetchCommandHistory() {
        // Safety check for uninitialized views
        if (!::noCommandHistoryText.isInitialized || !::commandHistoryRecyclerView.isInitialized || 
            !::commandHistoryAdapter.isInitialized) {
            Log.e("MainActivity", "Cannot fetch command history - views not initialized yet")
            return
        }
            
        // Show loading state
        noCommandHistoryText.text = getString(R.string.loading_command_history)
        noCommandHistoryText.visibility = View.VISIBLE
        findViewById<androidx.core.widget.NestedScrollView>(R.id.commandHistoryScrollView).visibility = View.GONE
        
        // Get the server URL
        val serverUrl = getServerUrl()
        
        // Use coroutine to fetch data in background
        lifecycleScope.launch {
            try {
                addLogMessage("[${getCurrentTime()}] Obteniendo historial de comandos...")
                
                // Get command history
                val history = CommandHistoryUtils.getCommandHistory(serverUrl, okHttpClient)
                
                // Update UI with results
                if (history.isEmpty()) {
                    noCommandHistoryText.text = getString(R.string.no_commands_available)
                    noCommandHistoryText.visibility = View.VISIBLE
                    findViewById<androidx.core.widget.NestedScrollView>(R.id.commandHistoryScrollView).visibility = View.GONE
                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.no_commands_in_history)}")
                } else {
                    noCommandHistoryText.visibility = View.GONE
                    findViewById<androidx.core.widget.NestedScrollView>(R.id.commandHistoryScrollView).visibility = View.VISIBLE
                    commandHistoryAdapter.updateCommands(history)
                    addLogMessage("[${getCurrentTime()}] Se encontraron ${history.size} comandos en el historial")
                }
                
                // After command history is loaded, also load favorites
                fetchFavorites()
            } catch (e: Exception) {
                // Handle error
                noCommandHistoryText.text = getString(R.string.error_loading_history, e.message)
                noCommandHistoryText.visibility = View.VISIBLE
                findViewById<androidx.core.widget.NestedScrollView>(R.id.commandHistoryScrollView).visibility = View.GONE
                addLogMessage("[${getCurrentTime()}] Error al obtener historial de comandos: ${e.message}")
                
                // Still try to fetch favorites even if command history fails
                fetchFavorites()
            }
        }
    }

    private fun startSimpleRecording() {
        if (checkAudioPermission()) {
            Log.d("MainActivity", getString(R.string.starting_recording))
            // Start recording logic here
        } else {
            Log.d("MainActivity", getString(R.string.requesting_audio_permission))
            requestAudioPermission()
        }
    }

    private fun setupFavorites() {
        // Initialize views
        favoritesRecyclerView = findViewById(R.id.favoritesRecyclerView)
        noFavoritesText = findViewById(R.id.noFavoritesText)
        btnRefreshFavorites = findViewById(R.id.btnRefreshFavorites)
        btnToggleFavorites = findViewById(R.id.btnToggleFavorites)
        favoritesContent = findViewById(R.id.favoritesContent)
        
        // Get the favorites scroll view
        val favoritesScrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.favoritesScrollView)
        
        // Configure the scroll view
        favoritesScrollView.apply {
            isNestedScrollingEnabled = true
            isFillViewport = true
            // Set a fixed height limit in case of many items
            val layoutParams = this.layoutParams
            layoutParams.height = resources.displayMetrics.heightPixels / 3 // Take up to 1/3 of screen height
            this.layoutParams = layoutParams
        }
        
        // Set up RecyclerView with action listeners
        favoritesRecyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        favoritesAdapter = FavoritesAdapter(
            emptyList(),
            object : FavoritesAdapter.OnFavoriteActionListener {
                override fun onRunFavorite(favorite: FavoriteEntry) {
                    handleRunFavorite(favorite)
                }
                
                override fun onDeleteFavorite(favorite: FavoriteEntry) {
                    handleDeleteFavorite(favorite)
                }
            }
        )
        favoritesRecyclerView.adapter = favoritesAdapter
        
        // Set up toggle button
        btnToggleFavorites.setOnClickListener {
            isFavoritesExpanded = !isFavoritesExpanded
            toggleFavoritesVisibility()
        }
        
        // Set up refresh button
        btnRefreshFavorites.setOnClickListener {
            fetchFavorites()
        }
        
        // Allow clicking on header to toggle
        findViewById<LinearLayout>(R.id.favoritesHeader).setOnClickListener {
            isFavoritesExpanded = !isFavoritesExpanded
            toggleFavoritesVisibility()
        }
        
        // Load initial state
        loadFavoritesState()
        
        // Don't fetch favorites immediately - we'll do this after command history
    }
    
    private fun toggleFavoritesVisibility() {
        if (isFavoritesExpanded) {
            // Show content with animation
            favoritesContent.visibility = View.VISIBLE
            favoritesContent.animate()
                .alpha(1f)
                .setDuration(300)
                .setListener(null)
            
            // Update toggle button icon
            btnToggleFavorites.setIconResource(R.drawable.ic_collapse)
        } else {
            // Hide content with animation
            favoritesContent.animate()
                .alpha(0f)
                .setDuration(300)
                .setListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        favoritesContent.visibility = View.GONE
                    }
                })
            
            // Update toggle button icon
            btnToggleFavorites.setIconResource(R.drawable.ic_expand)
        }
        
        // Save state to preferences
        saveAppPreferences()
    }
    
    private fun loadFavoritesState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isFavoritesExpanded = prefs.getBoolean(KEY_IS_FAVORITES_EXPANDED, false)
        
        // Apply the state immediately
        if (isFavoritesExpanded) {
            favoritesContent.visibility = View.VISIBLE
            favoritesContent.alpha = 1f
            btnToggleFavorites.setIconResource(R.drawable.ic_collapse)
        } else {
            favoritesContent.visibility = View.GONE
            favoritesContent.alpha = 0f
            btnToggleFavorites.setIconResource(R.drawable.ic_expand)
        }
    }
    
    private fun fetchFavorites() {
        // Safety check for uninitialized views
        if (!::noFavoritesText.isInitialized || !::favoritesRecyclerView.isInitialized || 
            !::favoritesAdapter.isInitialized) {
            Log.e("MainActivity", "Cannot fetch favorites - views not initialized yet")
            return
        }
            
        // Show loading state
        noFavoritesText.text = getString(R.string.loading_favorites)
        noFavoritesText.visibility = View.VISIBLE
        findViewById<androidx.core.widget.NestedScrollView>(R.id.favoritesScrollView).visibility = View.GONE
        
        // Get the server URL
        val serverUrl = getServerUrl()
        
        // Use coroutine to fetch data in background
        lifecycleScope.launch {
            try {
                addLogMessage("[${getCurrentTime()}] Fetching favorites...")
                
                // Get favorites
                val favorites = FavoritesUtils.getFavorites(serverUrl, okHttpClient)
                
                // Update UI with results
                if (favorites.isEmpty()) {
                    // Show "no favorites" message
                    noFavoritesText.text = getString(R.string.no_favorites)
                    noFavoritesText.visibility = View.VISIBLE
                    findViewById<androidx.core.widget.NestedScrollView>(R.id.favoritesScrollView).visibility = View.GONE
                } else {
                    // Update adapter with data
                    favoritesAdapter.updateFavorites(favorites)
                    
                    // Show the recycler view, hide the message
                    noFavoritesText.visibility = View.GONE
                    findViewById<androidx.core.widget.NestedScrollView>(R.id.favoritesScrollView).visibility = View.VISIBLE
                }
                
                addLogMessage("[${getCurrentTime()}] Loaded ${favorites.size} favorites")
            } catch (e: Exception) {
                // Show the error in the UI
                noFavoritesText.text = getString(R.string.error_loading_history, e.message)
                noFavoritesText.visibility = View.VISIBLE
                findViewById<androidx.core.widget.NestedScrollView>(R.id.favoritesScrollView).visibility = View.GONE
                
                // Log the error
                Log.e("MainActivity", "Error fetching favorites", e)
                addLogMessage("[${getCurrentTime()}] Error fetching favorites: ${e.message}")
            }
        }
    }
    
    private fun handleRunFavorite(favorite: FavoriteEntry) {
        // Get the server URL
        val serverUrl = getServerUrl()
        
        // Show toast to inform user that we're running the favorite
        Toast.makeText(
            this,
            "Running favorite: ${favorite.name}",
            Toast.LENGTH_SHORT
        ).show()
        
        // Log the run attempt
        addLogMessage("[${getCurrentTime()}] Running favorite: ${favorite.name} (${favorite.command})")
        
        // Show processing state
        showProcessingState()
        
        // Execute the favorite command
        lifecycleScope.launch {
            try {
                // Run the favorite
                val result = FavoritesUtils.runFavorite(
                    serverUrl = serverUrl,
                    client = okHttpClient,
                    scriptId = favorite.scriptId
                )
                
                // Return to ready state
                showReadyState()
                
                // Show the result to the user
                val message = if (result.success) {
                    getString(R.string.favorite_executed)
                } else {
                    getString(R.string.favorite_execute_failed, result.message)
                }
                
                // Show toast with the result
                Toast.makeText(
                    this@MainActivity,
                    message,
                    Toast.LENGTH_LONG
                ).show()
                
                // Log the result
                addLogMessage("[${getCurrentTime()}] $message")
                
                // Refresh the command history (since running a favorite will add entries)
                fetchCommandHistory()
            } catch (e: Exception) {
                // Handle any exceptions
                showReadyState()
                
                // Show error toast
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.favorite_execute_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                
                // Log the error
                addLogMessage("[${getCurrentTime()}] Error executing favorite: ${e.message}")
            }
        }
    }
    
    private fun handleDeleteFavorite(favorite: FavoriteEntry) {
        // Get the server URL
        val serverUrl = getServerUrl()
        
        // Show toast to inform user that we're deleting the favorite
        Toast.makeText(
            this,
            "Deleting favorite: ${favorite.name}",
            Toast.LENGTH_SHORT
        ).show()
        
        // Log the delete attempt
        addLogMessage("[${getCurrentTime()}] Deleting favorite: ${favorite.name}")
        
        // Delete the favorite
        lifecycleScope.launch {
            try {
                // Delete the favorite
                val result = FavoritesUtils.deleteFavorite(
                    serverUrl = serverUrl,
                    client = okHttpClient,
                    scriptId = favorite.scriptId
                )
                
                // Show the result to the user
                val message = if (result.success) {
                    getString(R.string.favorite_deleted)
                } else {
                    getString(R.string.favorite_delete_failed, result.message)
                }
                
                // Show toast with the result
                Toast.makeText(
                    this@MainActivity,
                    message,
                    Toast.LENGTH_LONG
                ).show()
                
                // Log the result
                addLogMessage("[${getCurrentTime()}] $message")
                
                // Refresh the favorites list
                fetchFavorites()
            } catch (e: Exception) {
                // Show error toast
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.favorite_delete_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                
                // Log the error
                addLogMessage("[${getCurrentTime()}] Error deleting favorite: ${e.message}")
            }
        }
    }
    
    private fun handleSaveAsFavorite(command: CommandHistoryEntry) {
        // Get the server URL
        val serverUrl = getServerUrl()
        
        // Show toast to inform user that we're saving the favorite
        Toast.makeText(
            this,
            getString(R.string.save_as_favorite) + ": " + command.command,
            Toast.LENGTH_SHORT
        ).show()
        
        // Log the save attempt
        addLogMessage("[${getCurrentTime()}] Saving command as favorite: ${command.command}")
        
        // Create a default name based on the command
        val defaultName = if (command.command.length > 20) {
            command.command.substring(0, 20) + "..."
        } else {
            command.command
        }
        
        // Save the favorite
        lifecycleScope.launch {
            try {
                // Save the command as a favorite
                val result = FavoritesUtils.saveFavorite(
                    serverUrl = serverUrl,
                    client = okHttpClient,
                    command = command.command,
                    name = defaultName,
                    code = command.code,
                    steps = command.steps,
                    success = command.success
                )
                
                // Show the result to the user
                val message = if (result.success) {
                    getString(R.string.favorite_saved)
                } else {
                    getString(R.string.favorite_save_failed, result.message)
                }
                
                // Show toast with the result
                Toast.makeText(
                    this@MainActivity,
                    message,
                    Toast.LENGTH_LONG
                ).show()
                
                // Log the result
                addLogMessage("[${getCurrentTime()}] $message")
                
                // Refresh the favorites list
                fetchFavorites()
                
                // Show the favorites section if it was hidden
                if (!isFavoritesExpanded && result.success) {
                    isFavoritesExpanded = true
                    toggleFavoritesVisibility()
                }
            } catch (e: Exception) {
                // Show error toast
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.favorite_save_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                
                // Log the error
                addLogMessage("[${getCurrentTime()}] Error saving favorite: ${e.message}")
            }
        }
    }
}

/**
 * Dialog for displaying images in fullscreen
 */
private class FullscreenImageDialog(
    context: Context, 
    private var bitmap: Bitmap,
    private val onDismissCallback: (() -> Unit)? = null
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    
    private var scaleFactor = 1f
    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    private val matrix = Matrix()
    private lateinit var imageView: ImageView
    
    // Variables for drag functionality
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var currentTranslateX = 0f
    private var currentTranslateY = 0f
    
    // Variables for touch detection
    private var isInPinchGesture = false
    private var touchStartTime = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val tapThreshold = 200L // milliseconds
    private val moveThreshold = 50f // pixels
    
    // Store display dimensions
    private val displayMetrics = context.resources.displayMetrics
    private val screenWidth = displayMetrics.widthPixels.toFloat()
    private val screenHeight = displayMetrics.heightPixels.toFloat()
    
    // Store initial fit-to-screen scale for toggling
    private var fitToScreenScale = 1f
    private var isZoomedToHeight = false
    
    // Zoom levels for tap-to-zoom functionality
    private val zoomLevels = arrayOf(1f, 2f, 4f) // fit-to-screen, 2x, 4x
    private var currentZoomLevelIndex = 0
    
    // Screenshot loading progress indicator
    private lateinit var screenshotProgressIndicator: ProgressBar
    
    // Recording state within the dialog
    private var isRecordingLocal = false
    private lateinit var recordButton: MaterialButton
    
    // Receiver to sync recording state
    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                AudioService.ACTION_RECORDING_STARTED -> {
                    isRecordingLocal = true
                    updateRecordButtonState()
                }
                AudioService.ACTION_RECORDING_STOPPED -> {
                    isRecordingLocal = false
                    updateRecordButtonState()
                }
            }
        }
    }
    
    init {
        // Create the container layout
        val container = FrameLayout(context)
        
        // Create the ImageView with zoom and drag support
        imageView = object : ImageView(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                scaleGestureDetector.onTouchEvent(event)
                
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        touchStartX = event.x
                        touchStartY = event.y
                        touchStartTime = System.currentTimeMillis()
                        isDragging = false
                        isInPinchGesture = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!scaleGestureDetector.isInProgress && !isInPinchGesture) {
                            val deltaX = event.x - lastTouchX
                            val deltaY = event.y - lastTouchY
                            val totalMoveDistance = sqrt(
                                (event.x - touchStartX) * (event.x - touchStartX) + 
                                (event.y - touchStartY) * (event.y - touchStartY)
                            )
                            
                            // Only start dragging if we've moved enough and we're zoomed in
                            if (totalMoveDistance > moveThreshold && scaleFactor > fitToScreenScale) {
                                isDragging = true
                            }
                            
                            if (isDragging && scaleFactor > fitToScreenScale) {
                                // Calculate new translation
                                val newTranslateX = currentTranslateX + deltaX
                                val newTranslateY = currentTranslateY + deltaY
                                
                                // Calculate bounds
                                val scaledWidth = bitmap.width * scaleFactor
                                val scaledHeight = bitmap.height * scaleFactor
                                
                                // Calculate the minimum and maximum allowed translations
                                val minTranslateX = -(scaledWidth - screenWidth)
                                val minTranslateY = -(scaledHeight - screenHeight)
                                
                                // Update translation with bounds checking
                                if (scaledWidth > screenWidth) {
                                    currentTranslateX = newTranslateX.coerceIn(minTranslateX, 0f)
                                } else {
                                    currentTranslateX = (screenWidth - scaledWidth) / 2
                                }
                                
                                if (scaledHeight > screenHeight) {
                                    currentTranslateY = newTranslateY.coerceIn(minTranslateY, 0f)
                                } else {
                                    currentTranslateY = (screenHeight - scaledHeight) / 2
                                }
                                
                                // Update matrix
                                updateImageMatrix()
                                
                                lastTouchX = event.x
                                lastTouchY = event.y
                            }
                        } else if (scaleGestureDetector.isInProgress) {
                            isInPinchGesture = true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val touchDuration = System.currentTimeMillis() - touchStartTime
                        val totalMoveDistance = sqrt(
                            (event.x - touchStartX) * (event.x - touchStartX) + 
                            (event.y - touchStartY) * (event.y - touchStartY)
                        )
                        
                        // Detect tap: short duration, minimal movement, and not after pinch gesture
                        if (touchDuration < tapThreshold && 
                            totalMoveDistance < moveThreshold && 
                            !isInPinchGesture && 
                            !isDragging) {
                            handleTap(touchStartX, touchStartY)
                        }
                        
                        isDragging = false
                        isInPinchGesture = false
                    }
                }
                
                return true
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.MATRIX
            setImageBitmap(bitmap)
        }
        
        // Create close button
        val closeButton = MaterialButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(16, 16, 16, 16)
            }
            setIconResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            iconTint = ColorStateList.valueOf(Color.WHITE)
            setOnClickListener { dismiss() }
        }
        
        // Screenshot loading progress indicator - positioned in center
        screenshotProgressIndicator = ProgressBar(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            alpha = 0.9f
            visibility = View.GONE
        }
        
        // Floating semi-transparent recording button (similar to landscape)
        recordButton = MaterialButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                144, // approx 72dp on mdpi; good enough for our use here
                144
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(24, 24, 24, 36)
            }
            alpha = 0.7f
            setIconResource(android.R.drawable.ic_btn_speak_now)
            iconTint = ColorStateList.valueOf(Color.WHITE)
            rippleColor = ColorStateList.valueOf(Color.WHITE)
            setBackgroundColor(Color.parseColor("#006400"))
            contentDescription = context.getString(R.string.start_recording)
            setOnClickListener {
                // Toggle start/stop by sending the same service intents MainActivity uses
                val action = if (!isRecordingLocal) "START_RECORDING" else "STOP_RECORDING"
                val svcIntent = Intent(context, AudioService::class.java).apply { this.action = action }
                try {
                    context.startService(svcIntent)
                } catch (_: Exception) { /* ignore */ }
            }
        }
        
        // Add views to container
        container.addView(imageView)
        container.addView(closeButton)
        container.addView(screenshotProgressIndicator)
        container.addView(recordButton)
        
        setContentView(container)
        
        // Make sure the dialog takes up the full screen
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        
        // Center the image initially
        centerImage()
        
        // Set dismiss callback
        setOnDismissListener { 
            try {
                context.unregisterReceiver(recordingStateReceiver)
            } catch (_: Exception) { /* already unregistered */ }
            onDismissCallback?.invoke() 
        }
        
        // Register receiver for recording state
        try {
            val filter = IntentFilter().apply {
                addAction(AudioService.ACTION_RECORDING_STARTED)
                addAction(AudioService.ACTION_RECORDING_STOPPED)
            }
            context.registerReceiver(recordingStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) { /* ignore */ }
        
        // Initialize progress indicator state based on main activity
        initializeProgressState()
        
        // Initialize button state
        updateRecordButtonState()
    }
    
    // Method to update the bitmap when a new screenshot is available
    fun updateBitmap(newBitmap: Bitmap) {
        bitmap = newBitmap
        imageView.setImageBitmap(bitmap)
        // Preserve current zoom and position state instead of re-centering
        updateImageMatrix()
    }
    
    // Method to show/hide screenshot loading progress
    fun setScreenshotProgressVisible(visible: Boolean) {
        if (::screenshotProgressIndicator.isInitialized) {
            screenshotProgressIndicator.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }
    
    // Initialize progress state based on main activity
    private fun initializeProgressState() {
        // Try to get the main activity's progress indicator state
        try {
            val mainActivity = context as? MainActivity
            if (mainActivity != null) {
                val isMainProgressVisible = mainActivity.screenshotLoadingProgress.visibility == View.VISIBLE
                setScreenshotProgressVisible(isMainProgressVisible)
            }
        } catch (e: Exception) {
            // If we can't access the main activity, default to hidden
            setScreenshotProgressVisible(false)
        }
    }
    
    private fun updateRecordButtonState() {
        if (::recordButton.isInitialized) {
            if (isRecordingLocal) {
                // Recording state - red background, pause icon
                recordButton.setBackgroundColor(Color.parseColor("#D32F2F")) // Material red
                recordButton.setIconResource(android.R.drawable.ic_media_pause)
                recordButton.contentDescription = context.getString(R.string.stop_recording)
            } else {
                // Idle state - green background, mic icon
                recordButton.setBackgroundColor(Color.parseColor("#006400")) // Dark green
                recordButton.setIconResource(android.R.drawable.ic_btn_speak_now)
                recordButton.contentDescription = context.getString(R.string.start_recording)
            }
        }
    }
    
    private fun centerImage() {
        // Calculate scale to fit screen while maintaining aspect ratio
        val scaleX = screenWidth / bitmap.width.toFloat()
        val scaleY = screenHeight / bitmap.height.toFloat()
        scaleFactor = minOf(scaleX, scaleY)
        fitToScreenScale = scaleFactor // Store the fit-to-screen scale
        isZoomedToHeight = false
        currentZoomLevelIndex = 0 // Reset to first zoom level
        
        // Calculate translation to center the image
        currentTranslateX = (screenWidth - bitmap.width * scaleFactor) / 2f
        currentTranslateY = (screenHeight - bitmap.height * scaleFactor) / 2f
        
        updateImageMatrix()
    }
    
    private fun updateImageMatrix() {
        matrix.reset()
        matrix.postScale(scaleFactor, scaleFactor)
        matrix.postTranslate(currentTranslateX, currentTranslateY)
        imageView.imageMatrix = matrix
    }
    
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val previousScale = scaleFactor
            scaleFactor *= detector.scaleFactor
            
            // Limit the scale factor
            scaleFactor = scaleFactor.coerceIn(0.1f, 10f)
            
            // Reset zoom-to-height state when manually scaling
            isZoomedToHeight = false
            
            // Adjust translation to keep the image centered at the focal point
            if (scaleFactor != previousScale) {
                val focusX = detector.focusX
                val focusY = detector.focusY
                
                // Calculate the change in scale
                val scaleChange = scaleFactor / previousScale
                
                // Calculate new dimensions
                val scaledWidth = bitmap.width * scaleFactor
                val scaledHeight = bitmap.height * scaleFactor
                
                // Calculate new translation
                val newTranslateX = focusX - (focusX - currentTranslateX) * scaleChange
                val newTranslateY = focusY - (focusY - currentTranslateY) * scaleChange
                
                // Apply bounds checking for the new translation
                if (scaledWidth > screenWidth) {
                    val minTranslateX = -(scaledWidth - screenWidth)
                    currentTranslateX = newTranslateX.coerceIn(minTranslateX, 0f)
                } else {
                    currentTranslateX = (screenWidth - scaledWidth) / 2
                }
                
                if (scaledHeight > screenHeight) {
                    val minTranslateY = -(scaledHeight - screenHeight)
                    currentTranslateY = newTranslateY.coerceIn(minTranslateY, 0f)
                } else {
                    currentTranslateY = (screenHeight - scaledHeight) / 2
                }
                
                updateImageMatrix()
            }
            
            return true
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide system UI for true fullscreen
        window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
    
    private fun handleTap(tapX: Float, tapY: Float) {
        // Cycle through zoom levels on tap
        currentZoomLevelIndex = (currentZoomLevelIndex + 1) % zoomLevels.size
        val targetZoomLevel = zoomLevels[currentZoomLevelIndex]
        
        if (currentZoomLevelIndex == 0) {
            // Return to fit-to-screen
            centerImage()
            isZoomedToHeight = false
        } else {
            // Apply zoom level and position based on tap location
            applyZoomLevel(targetZoomLevel, tapX, tapY)
            isZoomedToHeight = false // Reset this since we're using custom zoom
        }
    }
    
    private fun zoomToHeight(tapX: Float) {
        // Calculate scale to fill the viewport height
        val heightScale = screenHeight / bitmap.height.toFloat()
        scaleFactor = heightScale
        isZoomedToHeight = true
        
        // Calculate the scaled width
        val scaledWidth = bitmap.width * scaleFactor
        
        // Determine horizontal positioning based on tap location
        val tapRatio = tapX / screenWidth
        
        when {
            // Left third of screen - show left side of image
            tapRatio < 0.33f -> {
                currentTranslateX = 0f
            }
            // Right third of screen - show right side of image
            tapRatio > 0.67f -> {
                currentTranslateX = -(scaledWidth - screenWidth)
            }
            // Center third of screen - show center of image
            else -> {
                currentTranslateX = -(scaledWidth - screenWidth) / 2f
            }
        }
        
        // Center vertically (should be 0 since we're scaling to exact height)
        currentTranslateY = 0f
        
        // Ensure we don't go beyond bounds
        if (scaledWidth > screenWidth) {
            val minTranslateX = -(scaledWidth - screenWidth)
            currentTranslateX = currentTranslateX.coerceIn(minTranslateX, 0f)
        } else {
            // If image is narrower than screen, center it
            currentTranslateX = (screenWidth - scaledWidth) / 2f
        }
        
        updateImageMatrix()
    }
    
    private fun applyZoomLevel(zoomLevel: Float, tapX: Float, tapY: Float) {
        // Calculate the target scale (fit-to-screen scale * zoom level)
        val targetScale = fitToScreenScale * zoomLevel
        scaleFactor = targetScale
        
        // Calculate the scaled dimensions
        val scaledWidth = bitmap.width * scaleFactor
        val scaledHeight = bitmap.height * scaleFactor
        
        // Calculate the tap position relative to the current image bounds
        val imageLeft = currentTranslateX
        val imageTop = currentTranslateY
        val imageRight = imageLeft + (bitmap.width * fitToScreenScale)
        val imageBottom = imageTop + (bitmap.height * fitToScreenScale)
        
        // Convert tap coordinates to image coordinates
        val tapRatioX = if (imageRight > imageLeft) {
            ((tapX - imageLeft) / (imageRight - imageLeft)).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val tapRatioY = if (imageBottom > imageTop) {
            ((tapY - imageTop) / (imageBottom - imageTop)).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        
        // Calculate new translation to center the tapped point
        val targetX = tapX - (scaledWidth * tapRatioX)
        val targetY = tapY - (scaledHeight * tapRatioY)
        
        // Apply bounds checking
        if (scaledWidth > screenWidth) {
            val minTranslateX = -(scaledWidth - screenWidth)
            currentTranslateX = targetX.coerceIn(minTranslateX, 0f)
        } else {
            currentTranslateX = (screenWidth - scaledWidth) / 2f
        }
        
        if (scaledHeight > screenHeight) {
            val minTranslateY = -(scaledHeight - screenHeight)
            currentTranslateY = targetY.coerceIn(minTranslateY, 0f)
        } else {
            currentTranslateY = (screenHeight - scaledHeight) / 2f
        }
        
        updateImageMatrix()
    }
}
