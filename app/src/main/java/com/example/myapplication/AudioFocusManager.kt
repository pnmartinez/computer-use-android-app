package com.example.myapplication

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

class AudioFocusManager(
    private val context: Context,
    private val onFocusGained: () -> Unit,
    private val onFocusLost: () -> Unit,
    private val onFocusLogs: (String) -> Unit
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                onFocusLogs("🎵 AUDIO FOCUS: Ganado - Ahora podemos capturar botones de headset")
                Log.d("AudioFocusManager", "Audio focus gained")
                onFocusGained()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                onFocusLogs("❌ AUDIO FOCUS: Perdido permanentemente - Otra app domina")
                Log.d("AudioFocusManager", "Audio focus lost permanently")
                onFocusLost()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                onFocusLogs("⚠️ AUDIO FOCUS: Perdido temporalmente")
                Log.d("AudioFocusManager", "Audio focus lost transiently")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasFocus = false
                onFocusLogs("🔉 AUDIO FOCUS: Perdido temporalmente (puede duck)")
                Log.d("AudioFocusManager", "Audio focus lost transiently can duck")
            }
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                hasFocus = true
                onFocusLogs("🎵 AUDIO FOCUS: Ganado temporalmente")
                Log.d("AudioFocusManager", "Audio focus gained transiently")
                onFocusGained()
            }
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasFocus = true
                onFocusLogs("🎵 AUDIO FOCUS: Ganado temporalmente (may duck)")
                Log.d("AudioFocusManager", "Audio focus gained transiently may duck")
                onFocusGained()
            }
        }
    }

    fun requestAudioFocus(): Boolean {
        onFocusLogs("🎯 Solicitando audio focus para capturar botones de headset...")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .setAcceptsDelayedFocusGain(true)
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            val success = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            onFocusLogs("🎯 Audio focus request result: ${if (success) "GRANTED" else "DENIED"}")
            Log.d("AudioFocusManager", "Audio focus request result: $result")
            success
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            val success = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            onFocusLogs("🎯 Audio focus request result: ${if (success) "GRANTED" else "DENIED"}")
            Log.d("AudioFocusManager", "Audio focus request result: $result")
            success
        }
    }

    fun abandonAudioFocus() {
        onFocusLogs("🏁 Abandonando audio focus")
        Log.d("AudioFocusManager", "Abandoning audio focus")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }

        hasFocus = false
        audioFocusRequest = null
    }

    fun hasAudioFocus(): Boolean = hasFocus

    fun getCurrentAudioFocusState(): String {
        // getCurrentAudioFocus() is only available from API 26+ and we can't use it reliably
        // Return a simple status based on our internal state
        return if (hasFocus) "GAIN (internal)" else "LOSS (internal)"
    }
}
