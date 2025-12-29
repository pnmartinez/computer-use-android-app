package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.media.session.MediaButtonReceiver

/**
 * Custom MediaButtonReceiver with logging to debug media button events.
 */
class MyMediaButtonReceiver : MediaButtonReceiver() {
    
    companion object {
        private const val TAG = "MyMediaButtonReceiver"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "=== MEDIA BUTTON INTENT RECEIVED ===")
        Log.d(TAG, "Action: ${intent?.action}")
        Log.d(TAG, "Extras: ${intent?.extras?.keySet()}")
        
        // Try to extract the KeyEvent for logging
        val keyEvent = intent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        if (keyEvent != null) {
            Log.d(TAG, "KeyEvent: keyCode=${keyEvent.keyCode}, action=${keyEvent.action}")
            Log.d(TAG, "KeyEvent keyCode name: ${KeyEvent.keyCodeToString(keyEvent.keyCode)}")
        } else {
            Log.d(TAG, "No KeyEvent in intent")
        }
        
        // Let the parent handle it (forwards to MediaSession)
        try {
            super.onReceive(context, intent)
            Log.d(TAG, "Intent forwarded to MediaSession successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding to MediaSession: ${e.message}", e)
        }
    }
}

