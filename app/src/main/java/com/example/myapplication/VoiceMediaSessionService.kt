package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class VoiceMediaSessionService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notificationProvider = DefaultMediaNotificationProvider(this)
        setMediaNotificationProvider(notificationProvider)

        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            prepare()
            playWhenReady = true
        }

        mediaSession = MediaSession.Builder(this, player!!)
            .setCallback(VoiceMediaSessionCallback(this))
            .build()

        Log.d("VoiceMediaSession", "Media session created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channelId = HANDSFREE_CHANNEL_ID
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            getString(R.string.handsfree_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.handsfree_service_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    class VoiceMediaSessionCallback(private val context: Context) : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val keyEvent = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                ?: return false
            if (keyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                return true
            }
            handleKeycode(keyEvent.keyCode)
            return true
        }

        private fun handleKeycode(keyCode: Int) {
            sendLogMessage(context.getString(R.string.media_button_keycode_detected, keyCode))
            sendMediaButtonBroadcast(keyCode)
            val isRecording = isRecording(context)
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (isRecording) {
                        startAudioService("STOP_RECORDING")
                    } else {
                        startAudioService("START_RECORDING")
                    }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (isRecording) {
                        startAudioService("CANCEL_RECORDING")
                    } else {
                        startAudioService("SPEAK_LAST_RESPONSE")
                    }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (isRecording) {
                        startAudioService("CANCEL_RECORDING")
                    } else {
                        startAudioService("SPEAK_LAST_RESPONSE")
                    }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> {
                    if (isRecording) {
                        startAudioService("CANCEL_RECORDING")
                    }
                }
            }
        }

        private fun startAudioService(action: String) {
            val intent = Intent(context, AudioService::class.java).apply {
                this.action = action
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun sendMediaButtonBroadcast(keyCode: Int) {
            context.sendBroadcast(Intent(AudioService.ACTION_MEDIA_BUTTON_TAP).apply {
                setPackage(context.packageName)
                putExtra(AudioService.EXTRA_MEDIA_BUTTON_KEYCODE, keyCode)
            })
        }

        private fun sendLogMessage(message: String) {
            context.sendBroadcast(Intent(AudioService.ACTION_LOG_MESSAGE).apply {
                setPackage(context.packageName)
                putExtra(AudioService.EXTRA_LOG_MESSAGE, message)
            })
        }

        private fun isRecording(context: Context): Boolean {
            val prefs = context.getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(AudioService.KEY_IS_RECORDING, false)
        }
    }

    companion object {
        private const val HANDSFREE_CHANNEL_ID = "handsfree_media"
    }
}
