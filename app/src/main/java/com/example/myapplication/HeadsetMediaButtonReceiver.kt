package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat

class HeadsetMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
            return
        }
        val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        Log.d("HeadsetReceiver", "Media button event: ${keyEvent.keyCode}")
        val serviceIntent = Intent(context, AudioService::class.java).apply {
            action = AudioService.ACTION_MEDIA_BUTTON_EVENT
            putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
        abortBroadcast()
    }
}
