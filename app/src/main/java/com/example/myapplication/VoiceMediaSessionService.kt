package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class VoiceMediaSessionService : MediaSessionService() {

    private lateinit var mediaSession: MediaSession
    private lateinit var player: ExoPlayer
    private lateinit var audioFocusManager: AudioFocusManager
    private var isHandsFreeModeActive = false
    private var isServiceReady = false
    private val pendingIntents = mutableListOf<Intent>()

    companion object {
        private const val TAG = "VoiceMediaSessionService"
        const val ACTION_HANDSFREE_MODE_CHANGED = "com.example.myapplication.HANDSFREE_MODE_CHANGED"
        const val ACTION_MEDIA_BUTTON_TAP = "com.example.myapplication.MEDIA_BUTTON_TAP"
        const val EXTRA_HANDSFREE_ACTIVE = "handsfree_active"
        const val EXTRA_KEYCODE = "keycode"
        const val EXTRA_KEYCODE_NAME = "keycode_name"

        // Notification
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "handsfree_media"
        private const val CHANNEL_NAME = "Hands-Free Media Control"
    }

    override fun onCreate() {
        super.onCreate()

        logToUI("🎧 Iniciando VoiceMediaSessionService para captura de botones headset")

        // Crear canal de notificación
        createNotificationChannel()

        // Nota: MediaSessionService maneja automáticamente las notificaciones
        // No necesitamos configurar explícitamente el notification provider

        // Crear AudioFocusManager
        audioFocusManager = AudioFocusManager(
            context = this,
            onFocusGained = { onAudioFocusGained() },
            onFocusLost = { onAudioFocusLost() },
            onFocusLogs = { logToUI(it) }
        )

        // Crear ExoPlayer con silencio
        setupSilentPlayer()

        // Crear MediaSession con callback
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(VoiceMediaSessionCallback())
            .build()

        // Marcar servicio como listo y procesar intents pendientes
        isServiceReady = true
        logToUI("✅ VoiceMediaSessionService inicializado y listo")
        Log.d(TAG, "VoiceMediaSessionService created successfully")

        // Procesar intents pendientes que llegaron antes de que estuviéramos listos
        processPendingIntents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Si el servicio no está listo, guardar el intent para procesarlo después
        if (!isServiceReady) {
            intent?.let { pendingIntents.add(it) }
            logToUI("📦 Intent guardado para procesamiento posterior: ${intent?.action}")
            return Service.START_NOT_STICKY
        }

        // Procesar el intent inmediatamente si estamos listos
        when (intent?.action) {
            "ACTION_ENABLE_HANDSFREE" -> {
                logToUI("📡 Recibido comando: ACTIVAR modo hands-free")
                setHandsFreeMode(true)
            }
            "ACTION_DISABLE_HANDSFREE" -> {
                logToUI("📡 Recibido comando: DESACTIVAR modo hands-free")
                setHandsFreeMode(false)
            }
        }
        return Service.START_STICKY
    }

    private fun processPendingIntents() {
        if (pendingIntents.isEmpty()) return

        logToUI("🔄 Procesando ${pendingIntents.size} intents pendientes...")

        pendingIntents.forEach { intent ->
            when (intent.action) {
                "ACTION_ENABLE_HANDSFREE" -> {
                    logToUI("📡 Procesando intent pendiente: ACTIVAR modo hands-free")
                    setHandsFreeMode(true)
                }
                "ACTION_DISABLE_HANDSFREE" -> {
                    logToUI("📡 Procesando intent pendiente: DESACTIVAR modo hands-free")
                    setHandsFreeMode(false)
                }
            }
        }

        pendingIntents.clear()
        logToUI("✅ Intents pendientes procesados")
    }

    private fun setupSilentPlayer() {
        logToUI("🔊 Configurando ExoPlayer con reproducción de silencio continua")

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // Respect audio focus
            .build()

        // Configurar MediaItem con archivo de silencio
        try {
            val silenceUri = Uri.parse("android.resource://${packageName}/${R.raw.silence_1s}")
            val mediaItem = MediaItem.fromUri(silenceUri)
            player.setMediaItem(mediaItem)
        } catch (e: Exception) {
            logToUI("❌ Error configurando archivo de silencio: ${e.message}")
            // Crear un MediaItem vacío como fallback
            player.setMediaItem(MediaItem.EMPTY)
        }

        player.repeatMode = Player.REPEAT_MODE_ONE // Loop infinito
        player.volume = 0.01f // Muy bajo pero no cero para que cuente como "reproduciendo"

        // Listeners para debugging
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                logToUI("🎵 Player state: $stateName")
                Log.d(TAG, "Player state changed: $stateName")
            }

            override fun onPlayerError(error: PlaybackException) {
                logToUI("❌ Error en ExoPlayer: ${error.message}")
                Log.e(TAG, "Player error", error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                logToUI("▶️ Player isPlaying: $isPlaying")
                Log.d(TAG, "Player isPlaying: $isPlaying")
            }
        })

        logToUI("🎵 ExoPlayer configurado con silencio continuo")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activa la captura de botones de headset Bluetooth"
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun setHandsFreeMode(active: Boolean) {
        try {
            if (isHandsFreeModeActive == active) return

            logToUI("🎧 Solicitud modo manos libres: ${if (active) "ACTIVAR" else "DESACTIVAR"}")

            // Broadcast para UI inmediatamente
            sendBroadcast(Intent(ACTION_HANDSFREE_MODE_CHANGED).apply {
                putExtra(EXTRA_HANDSFREE_ACTIVE, active)
            })

            // Solo procesar si hay cambios
            isHandsFreeModeActive = active
            logToUI("🎧 Modo manos libres: ${if (active) "ACTIVADO" else "DESACTIVADO"}")

            if (active) {
                startHandsFreeMode()
            } else {
                stopHandsFreeMode()
            }

        } catch (e: Exception) {
            logToUI("❌ Error cambiando modo hands-free: ${e.message}")
            Log.e(TAG, "Error in setHandsFreeMode", e)

            // Reset state on error
            isHandsFreeModeActive = false
            sendBroadcast(Intent(ACTION_HANDSFREE_MODE_CHANGED).apply {
                putExtra(EXTRA_HANDSFREE_ACTIVE, false)
            })
        }
    }

    private fun startHandsFreeMode() {
        try {
            logToUI("🚀 Iniciando modo manos libres - Paso 1: Solicitando audio focus")

            // Solicitar audio focus
            val focusGranted = audioFocusManager.requestAudioFocus()
            if (!focusGranted) {
                logToUI("⚠️ No se pudo obtener audio focus - Otra app lo tiene")
                return
            }

            logToUI("🚀 Paso 2: Audio focus concedido, preparando player")

            // Preparar y reproducir silencio
            player.prepare()
            logToUI("🚀 Paso 3: Player preparado, iniciando reproducción")
            player.play()

            logToUI("🚀 Paso 4: Iniciando foreground service")
            // Iniciar foreground service
            startForeground(NOTIFICATION_ID, createHandsFreeNotification())

            logToUI("✅ Modo manos libres activo - Reproduciendo silencio continuo")
        } catch (e: Exception) {
            logToUI("❌ Error en startHandsFreeMode: ${e.message}")
            Log.e(TAG, "Error in startHandsFreeMode", e)

            // Intentar limpiar estado
            try {
                audioFocusManager.abandonAudioFocus()
                player.pause()
            } catch (cleanupError: Exception) {
                Log.e(TAG, "Error during cleanup", cleanupError)
            }
        }
    }

    private fun stopHandsFreeMode() {
        logToUI("🛑 Deteniendo modo manos libres")

        // Detener reproducción
        player.pause()
        player.seekTo(0)

        // Abandonar audio focus
        audioFocusManager.abandonAudioFocus()

        // Detener foreground
        stopForeground(true)

        logToUI("✅ Modo manos libres detenido")
    }

    private fun onAudioFocusGained() {
        logToUI("🎯 Audio focus ganado - Ahora podemos capturar botones!")
        // Reanudar reproducción si estaba pausada
        if (player.playbackState == Player.STATE_READY && isHandsFreeModeActive) {
            player.play()
        }
    }

    private fun onAudioFocusLost() {
        logToUI("💔 Audio focus perdido - Los botones irán a otra app")
        // Pausar reproducción cuando perdamos focus
        player.pause()
    }

    private fun createHandsFreeNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Modo Manos Libres")
            .setContentText("Capturando botones de headset Bluetooth")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Los botones de tus cascos Bluetooth ahora controlan la app. Toca para abrir."))
            .build()
    }

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    override fun onDestroy() {
        logToUI("🧹 Destruyendo VoiceMediaSessionService")

        // Limpiar recursos
        setHandsFreeMode(false)
        mediaSession.release()
        player.release()

        super.onDestroy()
    }

    private fun logToUI(message: String) {
        // Log to LogCat
        Log.d(TAG, message)

        // Send broadcast to MainActivity for UI logs
        val intent = Intent(AudioService.ACTION_LOG_MESSAGE).apply {
            putExtra(AudioService.EXTRA_LOG_MESSAGE, "[$TAG] $message")
        }
        sendBroadcast(intent)
    }

    // Callback para manejar botones de media
    inner class VoiceMediaSessionCallback : MediaSession.Callback {

        override fun onPostConnect(
            session: MediaSession,
            controller: androidx.media3.session.MediaSession.ControllerInfo
        ) {
            logToUI("🔗 MediaSession conectada - Podemos recibir comandos media")
            Log.d(TAG, "MediaSession connected")
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controller: androidx.media3.session.MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val keyEvent = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (keyEvent == null) {
                logToUI("⚠️ Media button event sin KeyEvent")
                return false
            }

            // Solo procesar ACTION_DOWN para evitar duplicados
            if (keyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                return false
            }

            val keyCode = keyEvent.keyCode
            val keyCodeName = getKeyCodeName(keyCode)

            logToUI("🎧 ¡BOTÓN DE HEADSET RECIBIDO! KeyCode: $keyCode ($keyCodeName)")
            Log.d(TAG, "Media button received: $keyCode ($keyCodeName)")

            // Verificar si tenemos audio focus
            if (!audioFocusManager.hasAudioFocus()) {
                logToUI("⚠️ Recibido botón pero NO tenemos audio focus - ignorando")
                return false
            }

            // Verificar si modo handsfree está activo
            if (!isHandsFreeModeActive) {
                logToUI("⚠️ Recibido botón pero modo handsfree NO activo - ignorando")
                return false
            }

            // Broadcast del botón pulsado para MainActivity
            sendBroadcast(Intent(ACTION_MEDIA_BUTTON_TAP).apply {
                putExtra(EXTRA_KEYCODE, keyCode)
                putExtra(EXTRA_KEYCODE_NAME, keyCodeName)
            })

            // Procesar el botón según lógica de la app
            handleMediaButton(keyCode, keyCodeName)

            return true // Consumido
        }

        private fun getKeyCodeName(keyCode: Int): String {
            return when (keyCode) {
                android.view.KeyEvent.KEYCODE_HEADSETHOOK -> "HEADSETHOOK"
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> "MEDIA_PLAY"
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> "MEDIA_PAUSE"
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "MEDIA_PLAY_PAUSE"
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "MEDIA_NEXT"
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MEDIA_PREVIOUS"
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> "MEDIA_STOP"
                else -> "UNKNOWN_$keyCode"
            }
        }

        private fun handleMediaButton(keyCode: Int, keyCodeName: String) {
            // Obtener estado de grabación desde SharedPreferences
            val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
            val isRecording = prefs.getBoolean("is_recording", false)

            when (keyCode) {
                android.view.KeyEvent.KEYCODE_HEADSETHOOK,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (isRecording) {
                        logToUI("🎙️ Botón $keyCodeName → DETENIENDO GRABACIÓN")
                        // Enviar intent para detener grabación
                        ContextCompat.startForegroundService(this@VoiceMediaSessionService, Intent(this@VoiceMediaSessionService, AudioService::class.java).apply {
                            action = "STOP_RECORDING"
                        })
                    } else {
                        logToUI("🎙️ Botón $keyCodeName → INICIANDO GRABACIÓN")
                        // Enviar intent para iniciar grabación
                        ContextCompat.startForegroundService(this@VoiceMediaSessionService, Intent(this@VoiceMediaSessionService, AudioService::class.java).apply {
                            action = "START_RECORDING"
                        })
                    }
                }

                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (isRecording) {
                        logToUI("🎙️ Botón $keyCodeName → CANCELANDO GRABACIÓN (estaba grabando)")
                        ContextCompat.startForegroundService(this@VoiceMediaSessionService, Intent(this@VoiceMediaSessionService, AudioService::class.java).apply {
                            action = "CANCEL_RECORDING"
                        })
                    } else {
                        logToUI("🎙️ Botón $keyCodeName → RELEYENDO ÚLTIMA RESPUESTA")
                        ContextCompat.startForegroundService(this@VoiceMediaSessionService, Intent(this@VoiceMediaSessionService, AudioService::class.java).apply {
                            action = "SPEAK_LAST_RESPONSE"
                        })
                    }
                }

                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    logToUI("🎙️ Botón $keyCodeName → CANCELANDO (si estaba grabando)")
                    ContextCompat.startForegroundService(this@VoiceMediaSessionService, Intent(this@VoiceMediaSessionService, AudioService::class.java).apply {
                        action = "CANCEL_RECORDING"
                    })
                }

                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> {
                    logToUI("🎙️ Botón $keyCodeName → CANCELANDO GRABACIÓN")
                    ContextCompat.startForegroundService(this@VoiceMediaSessionService, Intent(this@VoiceMediaSessionService, AudioService::class.java).apply {
                        action = "CANCEL_RECORDING"
                    })
                }
            }
        }
    }
}
