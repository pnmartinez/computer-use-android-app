package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver for MainActivity that handles service state changes.
 * Extracted from MainActivity to reduce complexity.
 */
class MainActivityBroadcastReceiver(
    private val callbacks: Callbacks
) : BroadcastReceiver() {

    interface Callbacks {
        // Recording state
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onProcessingCompleted()

        // Response handling
        fun onResponseReceived(message: String, screenSummary: String, success: Boolean)
        fun onConnectionTested(success: Boolean, message: String)

        // TTS status
        fun onTtsStatusChanged(status: String)

        // Headset control
        fun onHeadsetControlStatusChanged(enabled: Boolean)
        fun onMicrophoneChanged(micName: String?)

        // Logging
        fun onLogMessage(message: String)
        fun onAudioFileInfo(filePath: String, fileSize: Long, duration: Long, type: String)

        // Long polling
        fun onServerUpdateReceived(updateId: String, updateType: String, summary: String, changes: List<String>)
        fun onPollingStatusChanged(connected: Boolean)
        fun onPollingError(errorMessage: String)
        fun onPollingDebug(debugMessage: String)

        // Utilities
        fun getString(resId: Int): String
        fun getString(resId: Int, vararg formatArgs: Any): String
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AudioService.ACTION_RECORDING_STARTED -> {
                Log.d(TAG, "Recording started")
                callbacks.onRecordingStarted()
            }

            AudioService.ACTION_RECORDING_STOPPED -> {
                Log.d(TAG, "Recording stopped")
                callbacks.onRecordingStopped()
            }

            AudioService.ACTION_RESPONSE_RECEIVED -> {
                Log.d(TAG, "Response received")
                val message = intent.getStringExtra(AudioService.EXTRA_RESPONSE_MESSAGE)
                    ?: callbacks.getString(R.string.command_processed_successfully)
                val screenSummary = intent.getStringExtra(AudioService.EXTRA_SCREEN_SUMMARY).orEmpty()
                val success = intent.getBooleanExtra(AudioService.EXTRA_RESPONSE_SUCCESS, true)
                callbacks.onResponseReceived(message, screenSummary, success)
            }

            AudioService.ACTION_TTS_STATUS -> {
                val status = intent.getStringExtra(AudioService.EXTRA_TTS_STATUS).orEmpty()
                callbacks.onTtsStatusChanged(status)
            }

            AudioService.ACTION_HEADSET_CONTROL_STATUS -> {
                val enabled = intent.getBooleanExtra(AudioService.EXTRA_HEADSET_CONTROL_ENABLED, false)
                callbacks.onHeadsetControlStatusChanged(enabled)
            }

            AudioService.ACTION_MICROPHONE_CHANGED -> {
                val micName = intent.getStringExtra(AudioService.EXTRA_MICROPHONE_NAME)
                callbacks.onMicrophoneChanged(micName)
            }

            AudioService.ACTION_LOG_MESSAGE -> {
                val message = intent.getStringExtra(AudioService.EXTRA_LOG_MESSAGE) ?: return
                callbacks.onLogMessage(message)
            }

            AudioService.ACTION_AUDIO_FILE_INFO -> {
                val filePath = intent.getStringExtra(AudioService.EXTRA_AUDIO_FILE_PATH) ?: return
                val fileSize = intent.getLongExtra(AudioService.EXTRA_AUDIO_FILE_SIZE, 0)
                val duration = intent.getLongExtra(AudioService.EXTRA_AUDIO_DURATION, 0)
                val type = intent.getStringExtra(AudioService.EXTRA_AUDIO_TYPE) ?: "unknown"
                callbacks.onAudioFileInfo(filePath, fileSize, duration, type)
            }

            AudioService.ACTION_PROCESSING_COMPLETED -> {
                Log.d(TAG, "Processing completed")
                callbacks.onProcessingCompleted()
            }

            AudioService.ACTION_CONNECTION_TESTED -> {
                val success = intent.getBooleanExtra(AudioService.EXTRA_CONNECTION_SUCCESS, false)
                val message = intent.getStringExtra(AudioService.EXTRA_CONNECTION_MESSAGE)
                    ?: callbacks.getString(R.string.unknown_error)
                callbacks.onConnectionTested(success, message)
            }

            // Long Polling broadcasts
            LongPollingService.ACTION_UPDATE_RECEIVED -> {
                val updateId = intent.getStringExtra(LongPollingService.EXTRA_UPDATE_ID) ?: ""
                val updateType = intent.getStringExtra(LongPollingService.EXTRA_UPDATE_TYPE) ?: ""
                val updateSummary = intent.getStringExtra(LongPollingService.EXTRA_UPDATE_SUMMARY) ?: ""
                val updateChanges = intent.getStringArrayListExtra(LongPollingService.EXTRA_UPDATE_CHANGES) ?: arrayListOf()
                Log.d(TAG, "Server update received: $updateId - $updateSummary")
                callbacks.onServerUpdateReceived(updateId, updateType, updateSummary, updateChanges)
            }

            LongPollingService.ACTION_POLLING_STATUS -> {
                val connected = intent.getBooleanExtra(LongPollingService.EXTRA_POLLING_CONNECTED, false)
                callbacks.onPollingStatusChanged(connected)
            }

            LongPollingService.ACTION_POLLING_ERROR -> {
                val errorMessage = intent.getStringExtra(LongPollingService.EXTRA_ERROR_MESSAGE) ?: ""
                Log.e(TAG, "Polling error: $errorMessage")
                callbacks.onPollingError(errorMessage)
            }

            LongPollingService.ACTION_POLLING_DEBUG -> {
                val debugMessage = intent.getStringExtra(LongPollingService.EXTRA_DEBUG_MESSAGE) ?: ""
                callbacks.onPollingDebug(debugMessage)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivityReceiver"

        /**
         * Creates the IntentFilter with all required actions
         */
        fun createIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                // AudioService actions
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

                // LongPollingService actions
                addAction(LongPollingService.ACTION_UPDATE_RECEIVED)
                addAction(LongPollingService.ACTION_POLLING_STATUS)
                addAction(LongPollingService.ACTION_POLLING_ERROR)
                addAction(LongPollingService.ACTION_POLLING_DEBUG)
            }
        }

        /**
         * Registers the receiver with appropriate flags based on API level
         */
        fun register(context: Context, receiver: BroadcastReceiver) {
            val filter = createIntentFilter()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
    }
}
