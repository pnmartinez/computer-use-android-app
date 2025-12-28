package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast

class HeadsetMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
            return
        }
        val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        Log.d("HeadsetReceiver", "Media button event: ${keyEvent.keyCode}")
        Toast.makeText(
            context,
            "MEDIA_BUTTON ${keyEvent.keyCode}",
            Toast.LENGTH_SHORT
        ).show()
        context.sendBroadcast(Intent(AudioService.ACTION_LOG_MESSAGE).apply {
            setPackage(context.packageName)
            putExtra(
                AudioService.EXTRA_LOG_MESSAGE,
                "Media button event recibido: ${keyEvent.keyCode}"
            )
        })
        val serviceIntent = Intent(context, AudioService::class.java).apply {
            action = AudioService.ACTION_MEDIA_BUTTON_EVENT
            putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
