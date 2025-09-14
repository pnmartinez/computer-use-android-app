package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
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
import android.app.NotificationManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.constraintlayout.widget.ConstraintLayout
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
import com.example.myapplication.AudioService.Companion.KEY_WHISPER_MODEL
import kotlin.math.sqrt
import android.widget.ProgressBar

class MainActivity : AppCompatActivity() {
    
    // Main controls
    private lateinit var btnStartRecording: MaterialButton
    private lateinit var btnProcessingRecording: MaterialButton
    private lateinit var progressIndicator: LinearProgressIndicator
    
    // Logs - Find ScrollView directly by ID
    private lateinit var logsTextView: TextView
    private lateinit var btnClearLogs: MaterialButton
    private lateinit var logsScrollView: ScrollView
    private lateinit var btnToggleLogs: MaterialButton
    private lateinit var logsContent: LinearLayout
    private var isLogsExpanded = false
    
    // Advanced settings
    private lateinit var advancedSettingsContent: LinearLayout
    private lateinit var btnExpandSettings: MaterialButton
    private lateinit var serverIpInput: TextInputEditText
    private lateinit var serverPortInput: TextInputEditText
    private lateinit var unlockPasswordInput: TextInputEditText
    private lateinit var btnTestConnection: MaterialButton
    private lateinit var btnSaveSettings: MaterialButton
    private lateinit var connectionStatusText: TextView
    private lateinit var whisperModelDropdown: AutoCompleteTextView
    
    // Screenshot section
    private lateinit var screenshotImageView: ImageView
    private lateinit var screenshotStatusText: TextView
    private lateinit var btnCaptureScreenshot: MaterialButton
    private lateinit var btnUnlockScreen: MaterialButton
    private lateinit var btnRefreshPeriod: MaterialButton
    private lateinit var screenshotLoadingProgress: ProgressBar
    
    // Keep track of app state
    private var isRecording = false
    private var currentAudioFile: File? = null
    private val logBuffer = SpannableStringBuilder()
    private var isAdvancedSettingsExpanded = false
    
    // Track active fullscreen dialog
    private var activeFullscreenDialog: FullscreenImageDialog? = null
    
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
    
    // Broadcast receiver to listen for service state changes
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioService.ACTION_RECORDING_STARTED -> {
                    isRecording = true
                    Log.d("MainActivity", "Recording started, isRecording = $isRecording")
                    updateButtonStates()
                }
                AudioService.ACTION_RECORDING_STOPPED -> {
                    Log.d("MainActivity", "Recording stopped, showing processing state")
                    // Show the processing state when recording is stopped and we're waiting for the server response
                    showProcessingState()
                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.recording_finished_processing)}")
                }
                AudioService.ACTION_RESPONSE_RECEIVED -> {
                    // When a response is received, return to the ready state
                    Log.d("MainActivity", "Response received, returning to ready state")
                    showReadyState()
                    
                    // Show toast message for user feedback
                    val message = intent.getStringExtra(AudioService.EXTRA_RESPONSE_MESSAGE) ?: getString(R.string.command_processed_successfully)
                    val success = intent.getBooleanExtra(AudioService.EXTRA_RESPONSE_SUCCESS, true)
                    
                    // Call our handler with the response details
                    handleVoiceCommandResponse(success, message)
                    
                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.response_received, message)}")
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
                    
                    // Check for connection test completion in log messages (backup method)
                    if (message.contains("TEST COMPLETADO:", ignoreCase = true)) {
                        val isSuccess = message.contains("exitosa", ignoreCase = true)
                        
                        // Extract the message content
                        val connectionMessage = if (isSuccess) {
                            // Extract the connection time if possible
                            val timePattern = "\\((\\d+)ms\\)".toRegex()
                            val matchResult = timePattern.find(message)
                            if (matchResult != null) {
                                getString(R.string.connected_with_time, matchResult.groupValues[1].toInt())
                            } else {
                                getString(R.string.connected)
                            }
                        } else {
                            // Extract error message if possible
                            val errorPattern = "Error de conexión \\((.+)\\)".toRegex()
                            val matchResult = errorPattern.find(message)
                            if (matchResult != null) {
                                getString(R.string.connection_error_with_message, matchResult.groupValues[1])
                            } else {
                                getString(R.string.connection_error)
                            }
                        }
                        
                        // Update connection status using our helper method
                        updateConnectionStatus(isSuccess, connectionMessage)
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
                    // Ensure we return to the ready state when processing completes
                    showReadyState()
                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.processing_completed)}")
                }
                AudioService.ACTION_CONNECTION_TESTED -> {
                    val success = intent.getBooleanExtra(AudioService.EXTRA_CONNECTION_SUCCESS, false)
                    val message = intent.getStringExtra(AudioService.EXTRA_CONNECTION_MESSAGE) ?: getString(R.string.unknown_error)
                    
                    Log.d("MainActivity", getString(R.string.connection_test_received, success, message))
                    addLogMessage("[${getCurrentTime()}] ${getString(R.string.connection_test_received, success, message)}")
                    
                    // Ensure UI updates happen on the main thread
                    Handler(mainLooper).post {
                        // Update UI with test results
                        updateConnectionStatus(success, message)
                    }
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

    private lateinit var btnToggleTheme: MaterialButton
    private var isDarkTheme = false
    
    private var connectionTestTimeoutHandler: Handler? = null
    
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
        private const val KEY_IS_ADVANCED_SETTINGS_EXPANDED = "isAdvancedSettingsExpanded"
        private const val KEY_SCREENSHOT_REFRESH_PERIOD = "screenshotRefreshPeriod"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 100
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        
        // Configure initial button states
        updateButtonStates()
        
        // Setup click listeners
        setupButtonListeners()

        // Setup screenshot section
        setupScreenshotSection()
        
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
    }
    
    override fun onResume() {
        super.onResume()
        try {
            registerReceiver()
        } catch (e: Exception) {
            // Already registered
        }
        
        // Restore logs state
        loadLogsState()
        
        // If auto-refresh was enabled, restart it
        if (autoRefreshEnabled) {
            startAutoRefresh()
        }
    }
    
    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        
        // Save logs state
        saveLogsState()
        
        // Save preferences
        saveAppPreferences()
        
        // Stop any scheduled timers
        connectionTestTimeoutHandler?.removeCallbacksAndMessages(null)
        stopAutoRefresh()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Cancel any ongoing screenshot jobs
        screenshotJob.cancel()
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
        }
        registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }
    
    private fun initViews() {
        // Main controls
        btnStartRecording = findViewById(R.id.btnStartRecording)
        btnProcessingRecording = findViewById(R.id.btnProcessingRecording)
        progressIndicator = findViewById(R.id.progressIndicator)
        
        // Theme toggle
        btnToggleTheme = findViewById(R.id.btnToggleTheme)
        updateThemeButtonText()
        
        // Logs - Find ScrollView directly by ID
        logsTextView = findViewById(R.id.logsTextView)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        logsScrollView = findViewById(R.id.logsScrollView)
        
        // Make the logs content area capture focus for scrolling
        setupLogsScrollingBehavior()
        
        btnToggleLogs = findViewById(R.id.btnToggleLogs)
        logsContent = findViewById(R.id.logsContent)
        
        // Command History section
        setupCommandHistory()
        
        // Advanced settings
        advancedSettingsContent = findViewById(R.id.advancedSettingsContent)
        btnExpandSettings = findViewById(R.id.btnExpandSettings)
        serverIpInput = findViewById(R.id.serverIpInput)
        serverPortInput = findViewById(R.id.serverPortInput)
        unlockPasswordInput = findViewById(R.id.unlockPasswordInput)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        whisperModelDropdown = findViewById(R.id.whisperModelDropdown)
        
        // Initialize the whisper model dropdown
        val adapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.whisper_model_options)
        )
        whisperModelDropdown.setAdapter(adapter)
        
        // Screenshot section
        screenshotImageView = findViewById(R.id.screenshotImageView)
        screenshotStatusText = findViewById(R.id.screenshotStatusText)
        btnCaptureScreenshot = findViewById(R.id.btnCaptureScreenshot)
        btnUnlockScreen = findViewById(R.id.btnUnlockScreen)
        btnRefreshPeriod = findViewById(R.id.btnRefreshPeriod)
        screenshotLoadingProgress = findViewById(R.id.screenshotLoadingProgress)
        
        // Setup log clear button
        btnClearLogs.setOnClickListener {
            logBuffer.clear()
            logsTextView.text = ""
            addLogMessage("[${getCurrentTime()}] ${getString(R.string.logs_cleared)}")
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
        
        // Setup advanced settings
        setupAdvancedSettings()
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
    
    private fun setupAdvancedSettings() {
        // Load saved settings from SharedPreferences
        loadServerSettings()
        
        // Setup expand/collapse button
        btnExpandSettings.setOnClickListener {
            isAdvancedSettingsExpanded = !isAdvancedSettingsExpanded
            toggleAdvancedSettings()
        }
        
        // Setup advanced settings header to toggle expansion when clicked
        findViewById<LinearLayout>(R.id.advancedSettingsHeader).setOnClickListener {
            isAdvancedSettingsExpanded = !isAdvancedSettingsExpanded
            toggleAdvancedSettings()
        }
        
        // Setup theme toggle
        btnToggleTheme.setOnClickListener {
            toggleTheme()
        }
        
        // Setup server setup button
        findViewById<MaterialButton>(R.id.btnServerSetup).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/pnmartinez/simple-computer-use"))
            startActivity(intent)
        }
        
        // Setup save button
        btnSaveSettings.setOnClickListener {
            saveServerSettings()
        }
        
        // Setup test connection button
        btnTestConnection.setOnClickListener {
            testServerConnection()
        }
    }
    
    private fun toggleAdvancedSettings() {
        // Always run UI updates on the main thread
        runOnUiThread {
            // Update button icon and visibility
            val iconResId = if (isAdvancedSettingsExpanded) 
                R.drawable.ic_collapse else R.drawable.ic_expand
            btnExpandSettings.setIconResource(iconResId)
            
            // Update content visibility with animation
            if (isAdvancedSettingsExpanded) {
                advancedSettingsContent.visibility = View.VISIBLE
                advancedSettingsContent.alpha = 0f
                advancedSettingsContent.animate().alpha(1f).setDuration(500).start()
                
                // Find the parent NestedScrollView
                val nestedScrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.nestedScrollView)
                
                // We need to wait until the content is laid out to calculate proper scroll position
                advancedSettingsContent.post {
                    // Get the coordinates of the card that contains this section
                    val advancedSettingsCard = findViewById<View>(R.id.advancedSettingsCard)
                    
                    // Calculate the position to scroll to - we want to show the entire card
                    // by scrolling to a position that makes the card visible
                    if (advancedSettingsCard != null) {
                        val scrollViewHeight = nestedScrollView.height
                        val cardTop = advancedSettingsCard.top
                        val cardBottom = advancedSettingsCard.bottom
                        val cardHeight = advancedSettingsCard.height
                        
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
                advancedSettingsContent.animate().alpha(0f).setDuration(500)
                    .withEndAction { advancedSettingsContent.visibility = View.GONE }.start()
            }
        }
    }
    
    private fun loadServerSettings() {
        // Load from SharedPreferences
        val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        val serverIp = prefs.getString(KEY_SERVER_IP, AudioService.DEFAULT_SERVER_IP) 
            ?: AudioService.DEFAULT_SERVER_IP
        val serverPort = prefs.getInt(KEY_SERVER_PORT, AudioService.DEFAULT_SERVER_PORT)
        val whisperModel = prefs.getString(KEY_WHISPER_MODEL, AudioService.DEFAULT_WHISPER_MODEL)
            ?: AudioService.DEFAULT_WHISPER_MODEL
        val unlockPassword = prefs.getString(KEY_UNLOCK_PASSWORD, "your_password") ?: "your_password"
        
        // Update UI
        serverIpInput.setText(serverIp)
        serverPortInput.setText(serverPort.toString())
        whisperModelDropdown.setText(whisperModel, false)
        unlockPasswordInput.setText(unlockPassword)
    }
    
    private fun saveServerSettings() {
        val ip = serverIpInput.text.toString().trim()
        val portText = serverPortInput.text.toString().trim()
        val whisperModel = whisperModelDropdown.text.toString().trim()
        val unlockPassword = unlockPasswordInput.text.toString()
        
        // Validate input
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
        
        // Save to SharedPreferences
        val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_SERVER_IP, ip)
            putInt(KEY_SERVER_PORT, port)
            putString(KEY_WHISPER_MODEL, whisperModel)
            putString(KEY_UNLOCK_PASSWORD, unlockPassword)  // Save unlock password
            apply()
        }
        
        // Also save to app preferences to ensure consistency
        saveAppPreferences()
        
        // Update service
        val intent = Intent(this, AudioService::class.java).apply {
            action = "UPDATE_SETTINGS"
            putExtra(KEY_SERVER_IP, ip)
            putExtra(KEY_SERVER_PORT, port as Int)
            putExtra(KEY_WHISPER_MODEL, whisperModel)
        }
        startService(intent)
        
        // Show confirmation
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        addLogMessage("[${getCurrentTime()}] ${getString(R.string.server_settings_updated, ip, port, whisperModel)}")
    }
    
    private fun testServerConnection() {
        try {
            // First, save current settings
            saveServerSettings()
            
            // Clear previous status and set indication that test is in progress
            connectionStatusText.text = getString(R.string.checking_connection)
            connectionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            
            // Log the connection attempt
            val ipPort = "${serverIpInput.text}:${serverPortInput.text}"
            Log.d("MainActivity", "Iniciando prueba de conexión a $ipPort")
            addLogMessage("[${getCurrentTime()}] ${getString(R.string.testing_connection, ipPort)}")
            
            // Cancel any existing timeout handler
            connectionTestTimeoutHandler?.removeCallbacksAndMessages(null)
            
            // Create new timeout handler
            connectionTestTimeoutHandler = Handler(mainLooper)
            connectionTestTimeoutHandler?.postDelayed({
                if (connectionStatusText.text == getString(R.string.checking_connection)) {
                    connectionStatusText.text = getString(R.string.connection_timeout)
                    connectionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                    addLogMessage("[${getCurrentTime()}] ❌ ${getString(R.string.timeout_connecting)}")
                }
            }, 10000) // 10 second timeout
            
            // Send test connection request to service
            val intent = Intent(this, AudioService::class.java).apply {
                action = "TEST_CONNECTION"
            }
            startService(intent)
        } catch (e: Exception) {
            // Handle any exceptions that might occur
            Log.e("MainActivity", "Error al iniciar prueba de conexión", e)
            connectionStatusText.text = getString(R.string.error_generic, e.message)
            connectionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            addLogMessage("[${getCurrentTime()}] ${getString(R.string.error_starting_test, e.message)}")
        }
    }
    
    private fun updateButtonStates() {
        if (isRecording) {
            btnStartRecording.text = getString(R.string.stop_recording)
            btnStartRecording.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_media_pause)
            // Set the background color to red when recording
            btnStartRecording.setBackgroundColor(ContextCompat.getColor(this, R.color.md_theme_error))
        } else {
            btnStartRecording.text = getString(R.string.start_recording_button)
            btnStartRecording.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_btn_speak_now)
            // Set the default background color to Android's default dark green
            btnStartRecording.setBackgroundColor(Color.parseColor("#006400"));
        }
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
        
        // Add a timeout to reset the UI if no response is received
        Handler(mainLooper).postDelayed({
            if (progressIndicator.visibility == View.VISIBLE || btnProcessingRecording.visibility == View.VISIBLE) {
                Log.d("MainActivity", "Response timeout - resetting UI to ready state")
                showReadyState()
                addLogMessage("[${getCurrentTime()}] ${getString(R.string.waiting_for_response)}")
                Toast.makeText(this, "No se recibió respuesta del servidor", Toast.LENGTH_SHORT).show()
            }
        }, 35000) // 35 seconds timeout
        
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
            // Use AnimationUtils instead of AnimatorInflater
            val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out)
            val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in)
            
            // Set animation listener
            fadeOut.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                
                override fun onAnimationEnd(animation: Animation?) {
                    // After fade out, hide the processing button and show the main button
                    btnProcessingRecording.visibility = View.GONE
                    btnStartRecording.visibility = View.VISIBLE
                    progressIndicator.visibility = View.GONE
                    isRecording = false
                    updateButtonStates()
                    
                    // Start fade in animation
                    btnStartRecording.startAnimation(fadeIn)
                }
                
                override fun onAnimationRepeat(animation: Animation?) {}
            })
            
            // Start fade out animation
            btnProcessingRecording.startAnimation(fadeOut)
        }
    }
    
    private fun updateAudioFileInfo(filePath: String, fileSize: Long, duration: Long, type: String) {
        // Update UI with file info
        val file = File(filePath)
        
        // Verify file exists and has content
        if (!file.exists()) {
            addLogMessage("[${getCurrentTime()}] ${getString(R.string.file_not_found, filePath)}")
            currentAudioFile = null
            return
        }
        
        if (fileSize <= 0) {
            addLogMessage("[${getCurrentTime()}] ${getString(R.string.file_empty_corrupt, filePath)}")
            currentAudioFile = null
            return
        }
        
        // File seems valid, update reference
        currentAudioFile = file
        
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
        context.startService(intent)
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

    private fun logMessage(message: String) {
        runOnUiThread {
            logsTextView.append("$message\n")
        }
    }

    private fun testConnection() {
        val serverUrl = serverIpInput.text.toString()
        val model = whisperModelDropdown.text.toString()
        
        Log.d("MainActivity", getString(R.string.testing_connection, serverUrl))
        
        try {
            // Simulate connection test
            Thread.sleep(5000)
            
            if (serverUrl.isNotEmpty()) {
                Log.d("MainActivity", getString(R.string.connection_successful, model))
            } else {
                Log.e("MainActivity", getString(R.string.connection_error_with_message, "Invalid server URL"))
            }
        } catch (e: Exception) {
            Log.e("MainActivity", getString(R.string.error_starting_test, e.message))
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
            captureNewScreenshot()
        }, 1000)
        
        // Setup auto-refresh
        if (autoRefreshEnabled) {
            startAutoRefresh()
        }
        
        btnCaptureScreenshot.setOnClickListener {
            captureNewScreenshot()
        }
    }
    
    private fun fetchLatestScreenshot() {
        screenshotScope.launch {
            try {
                updateScreenshotState(ScreenshotState.Loading)
                
                val serverUrl = buildServerUrl()
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
    
    private fun buildServerUrl() = "https://${serverIpInput.text}:${serverPortInput.text}"

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
                    // Enable the refresh and capture buttons
                    btnCaptureScreenshot.isEnabled = true
                }
                is ScreenshotState.Error -> {
                    screenshotStatusText.text = state.message
                    screenshotLoadingProgress.visibility = View.GONE
                    disableScreenshotControls()
                    // Still enable the capture button on error to allow retry
                    btnCaptureScreenshot.isEnabled = true
                }
                is ScreenshotState.NoScreenshots -> {
                    screenshotStatusText.text = getString(R.string.no_screenshots)
                    screenshotLoadingProgress.visibility = View.GONE
                    disableScreenshotControls()
                    // Still enable the capture button when no screenshots
                    btnCaptureScreenshot.isEnabled = true
                    addLogMessage("[${getCurrentTime()}] ⚠️ ${getString(R.string.no_screenshots_available)}")
                }
                is ScreenshotState.Loading -> {
                    screenshotStatusText.text = getString(R.string.loading_screenshots)
                    screenshotLoadingProgress.visibility = View.VISIBLE
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
            
            // Update status and log error
            screenshotStatusText.text = errorMessage
            addLogMessage("[${getCurrentTime()}] ❌ $errorMessage")
        }
        
        Log.e("Screenshot", "Error fetching screenshot", error)
    }

    private fun fetchScreenshot(filename: String) {
        screenshotScope.launch {
            try {
                val serverUrl = "https://${serverIpInput.text}:${serverPortInput.text}"
                // Add a timestamp to prevent caching
                val timestamp = System.currentTimeMillis()
                val imageUrl = "$serverUrl/screenshots/$filename?t=$timestamp"
                
                Log.d("Screenshot", "Fetching screenshot from URL: $imageUrl")
                addLogMessage("[${getCurrentTime()}] 🖼️ Descargando captura: $filename")
                
                // Show loading progress bar and hide image view while loading
                withContext(Dispatchers.Main) {
                    screenshotLoadingProgress.visibility = View.VISIBLE
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
                        
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            ?: throw IOException("Error al decodificar la imagen")
                        
                        withContext(Dispatchers.Main) {
                            // Hide loading progress bar
                            screenshotLoadingProgress.visibility = View.GONE
                            
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
                    
                    // Handle error
                    handleScreenshotError(e)
                }
            }
        }
    }
    
    private fun startAutoRefresh() {
        // Cancel any existing auto-refresh
        stopAutoRefresh()
        
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

    private fun showFullscreenImage() {
        // Get the current bitmap from the ImageView
        val bitmap = (screenshotImageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        
        // Create and show the fullscreen dialog
        activeFullscreenDialog = FullscreenImageDialog(this, bitmap) { 
            // Clear reference when dialog is dismissed
            activeFullscreenDialog = null
        }
        activeFullscreenDialog?.show()
    }

    private fun loadAppPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isLogsExpanded = prefs.getBoolean(KEY_IS_LOGS_EXPANDED, false)
        isDarkTheme = prefs.getBoolean(KEY_IS_DARK_THEME, false)
        refreshPeriodMs = prefs.getLong(KEY_REFRESH_PERIOD, 30000)
        autoRefreshEnabled = prefs.getBoolean(KEY_AUTO_REFRESH_ENABLED, true)
        
        // Load unlock password or use default if not set
        val savedPassword = prefs.getString(KEY_UNLOCK_PASSWORD, "your_password")
        if (::unlockPasswordInput.isInitialized) {
            unlockPasswordInput.setText(savedPassword)
        }
    }
    
    private fun saveAppPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SERVER_IP, serverIpInput.text.toString())
            .putString(KEY_SERVER_PORT, serverPortInput.text.toString())
            .putString(KEY_WHISPER_MODEL, whisperModelDropdown.text.toString())
            .putString(KEY_UNLOCK_PASSWORD, unlockPasswordInput.text.toString())
            .putBoolean(KEY_IS_LOGS_EXPANDED, isLogsExpanded)
            .putBoolean(KEY_IS_COMMAND_HISTORY_EXPANDED, isCommandHistoryExpanded)
            .putBoolean(KEY_IS_FAVORITES_EXPANDED, isFavoritesExpanded)
            .putBoolean(KEY_IS_ADVANCED_SETTINGS_EXPANDED, isAdvancedSettingsExpanded)
            .putString(KEY_SCREENSHOT_REFRESH_PERIOD, btnRefreshPeriod.text.toString())
            .apply()
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
        btnToggleTheme.text = if (isDarkTheme) getString(R.string.toggle_theme_light) else getString(R.string.toggle_theme_dark)
        btnToggleTheme.setIconResource(
            if (isDarkTheme) android.R.drawable.ic_menu_day
            else android.R.drawable.ic_dialog_dialer  // Using a darker icon for night mode
        )
    }
    
    private fun toggleTheme() {
        isDarkTheme = !isDarkTheme
        saveAppPreferences()
        updateThemeButtonText()
        updateTheme()
    }

    private fun captureNewScreenshot() {
        screenshotScope.launch {
            try {
                // Show loading progress bar instead of just text
                withContext(Dispatchers.Main) {
                    screenshotLoadingProgress.visibility = View.VISIBLE
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
                                            // Convert base64 string to bitmap
                                            val imageBytes = android.util.Base64.decode(base64ImageData, android.util.Base64.DEFAULT)
                                            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                            
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
                    btnCaptureScreenshot.isEnabled = true
                    addLogMessage("[${getCurrentTime()}] ❌ ${getString(R.string.error_generic, e.message)}")
                }
                Log.e("Screenshot", "Error capturing screenshot", e)
            }
        }
    }



    private fun getServerUrl(): String {
        // Safety check: if serverIpInput isn't initialized yet, use default values
        if (!::serverIpInput.isInitialized || !::serverPortInput.isInitialized) {
            return "https://${AudioService.DEFAULT_SERVER_IP}:${AudioService.DEFAULT_SERVER_PORT}"
        }
        
        val serverIp = serverIpInput.text.toString().trim()
        val serverPort = serverPortInput.text.toString().trim()
        return "https://$serverIp:$serverPort"
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
                
                // Get the password from the input field
                val password = unlockPasswordInput.text.toString().trim()
                
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
        
        // Add views to container
        container.addView(imageView)
        container.addView(closeButton)
        
        setContentView(container)
        
        // Make sure the dialog takes up the full screen
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        
        // Center the image initially
        centerImage()
        
        // Set dismiss callback
        setOnDismissListener { onDismissCallback?.invoke() }
    }
    
    // Method to update the bitmap when a new screenshot is available
    fun updateBitmap(newBitmap: Bitmap) {
        bitmap = newBitmap
        imageView.setImageBitmap(bitmap)
        // Preserve current zoom and position state instead of re-centering
        updateImageMatrix()
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