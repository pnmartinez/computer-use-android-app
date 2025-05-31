Documento de Implementación y Configuración
1. Arquitectura General

La solución combina WireGuard (VPN a nivel de red) con TLS (cifrado a nivel de aplicación) para exponer un servidor WebSocket seguro (wss://). La aplicación Android:

    Graba audio con MediaRecorder.
    Envía el audio al servidor vía WebSocket seguro.
    Recibe audio de respuesta (en formato OGG) y lo reproduce con ExoPlayer.

En el servidor, se configura:

    WireGuard para crear una VPN cifrada (opcional, pero recomendado).
    Un reverse proxy o un servidor configurado para TLS, permitiendo wss:// (por ejemplo, con Nginx y Let's Encrypt).

2. Código de la Aplicación Android

A continuación se muestran ejemplos relevantes para una clase de servicio AudioService que maneja la grabación, envío y reproducción de audio. Además, se provee un ejemplo simplificado de la interfaz Compose para iniciar/detener la grabación y reproducir la respuesta.
2.1. AndroidManifest.xml
Permisos

<!-- AndroidManifest.xml -->
<manifest
    xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.voiceapp">

    <!-- Permiso para grabar audio -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:networkSecurityConfig="@xml/network_security_config"
        android:allowBackup="true"
        android:supportsRtl="true">

        <!-- Configuración adicional de la app -->

    </application>
</manifest>

Network Security Config (opcional)

Si quieres restringir completamente el tráfico http:// y forzar TLS, añade un archivo res/xml/network_security_config.xml con:

<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">miservidor.com</domain>
    </domain-config>
</network-security-config>

y en tu <application> del Manifest:

android:networkSecurityConfig="@xml/network_security_config"

2.2. build.gradle (App-Level)

En tu archivo app/build.gradle, añade las dependencias necesarias para OkHttp, Coroutines y ExoPlayer:

plugins {
    id 'com.android.application'
    id 'kotlin-android'
}

android {
    compileSdk 33

    defaultConfig {
        applicationId "com.example.voiceapp"
        minSdk 21
        targetSdk 33
        versionCode 1
        versionName "1.0"
        // ...
    }

    buildTypes {
        release {
            // ...
        }
    }

    kotlinOptions {
        jvmTarget = '1.8'
    }
}

dependencies {
    // OkHttp
    implementation 'com.squareup.okhttp3:okhttp:4.10.0'

    // Coroutines
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4"

    // ExoPlayer
    implementation 'com.google.android.exoplayer:exoplayer:2.18.7'

    // Otros (Compose, etc.) según tu proyecto
    // ...
}

2.3. Servicio de Audio: AudioService.kt

El código completo del servicio quedaría así (ejemplo con mejoras de seguridad, manejo de errores y uso de TLS en wss://):

package com.example.voiceapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import java.io.File
import java.util.concurrent.TimeUnit

class AudioService : Service() {

    private var recorder: MediaRecorder? = null
    private var player: ExoPlayer? = null

    // Para coroutines con un Job que cancelaremos en onDestroy()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)

    // WebSocket seguro (TLS)
    private val webSocketUrl = "wss://miservidor.com/ws"

    // Configuración OkHttp con timeouts
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var webSocket: WebSocket

    // Flag para controlar el estado de grabación
    private var isRecording = false

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_RECORDING" -> startRecording()
            "STOP_RECORDING" -> stopRecordingAndSend()
            "PLAY_RESPONSE" -> playLastResponse()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        // Cancelar coroutines en curso
        serviceJob.cancel()

        // Cerrar el WebSocket
        webSocket.close(1000, "Service destroyed")
        httpClient.dispatcher.executorService.shutdown()

        // Liberar ExoPlayer
        player?.release()
        player = null
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
        val channel = NotificationChannel(
            channelId,
            "Audio Service",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Escuchando comandos...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(webSocketUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Reproducir la respuesta en el hilo principal
                serviceScope.launch(Dispatchers.Main) {
                    playAudioResponse(bytes)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "Error en WebSocket: ${t.message}")
                // Se podría reintentar la conexión o notificar al usuario
            }
        })
    }

    private fun startRecording() {
        if (isRecording) {
            Log.w("AudioService", "Ya se está grabando. Ignorando segunda solicitud.")
            return
        }
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
        } catch (e: Exception) {
            Log.e("AudioService", "Error al iniciar grabación: ${e.message}")
            recorder?.release()
            recorder = null
            isRecording = false
        }
    }

    private fun stopRecordingAndSend() {
        if (!isRecording) {
            Log.w("AudioService", "No estaba grabando. Ignorando...")
            return
        }
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e("AudioService", "Error al detener la grabación: ${e.message}")
        } finally {
            recorder?.release()
            recorder = null
            isRecording = false
        }

        // Enviar archivo al servidor
        val audioFile = getAudioFile()
        serviceScope.launch(Dispatchers.IO) {
            sendAudioOverWebSocket(audioFile)
        }
    }

    private fun sendAudioOverWebSocket(file: File) {
        // Verificar tamaño del archivo antes de leerlo
        val maxFileSize = 5 * 1024 * 1024 // 5 MB, por ejemplo
        if (file.length() > maxFileSize) {
            Log.e("AudioService", "Archivo demasiado grande para enviar completo.")
            return
        }

        // Leer bytes y enviar
        val audioBytes = file.readBytes()
        webSocket.send(ByteString.of(*audioBytes))
        Log.d("WebSocket", "Audio enviado correctamente.")
    }

    private fun playAudioResponse(audioBytes: ByteString) {
        // Validar cabecera OGG ("OggS") como mínimo
        if (!isOggFormat(audioBytes)) {
            Log.e("AudioService", "Los bytes recibidos no parecen ser OGG.")
            return
        }
        val tempFile = getResponseFile()
        tempFile.writeBytes(audioBytes.toByteArray())

        player?.release() // Liberar instancia anterior si la hubiera
        player = ExoPlayer.Builder(this).build().apply {
            val mediaItem = MediaItem.fromUri(tempFile.absolutePath)
            setMediaItem(mediaItem)
            prepare()
            play()
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

    private fun playLastResponse() {
        // Por si el usuario quiere reproducir la última respuesta almacenada
        val responseFile = getResponseFile()
        if (!responseFile.exists()) {
            Log.w("AudioService", "No hay respuesta previa guardada.")
            return
        }

        player?.release()
        player = ExoPlayer.Builder(this).build().apply {
            val mediaItem = MediaItem.fromUri(responseFile.absolutePath)
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }
}

2.4. Funciones para Iniciar el Servicio

En algún utils.kt o directamente en la UI (Compose), se incluye un helper:

fun startAudioService(context: Context, action: String) {
    val serviceIntent = Intent(context, AudioService::class.java).apply {
        this.action = action
    }
    context.startService(serviceIntent)
}

2.5. Interfaz de Usuario (Ejemplo Compose)

Ejemplo muy simplificado para iniciar/stop grabación y reproducir respuesta:

@Composable
fun VoiceAppUI() {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = {
            // Aquí deberías verificar el permiso RECORD_AUDIO antes de iniciar
            startAudioService(context, "START_RECORDING")
        }) {
            Text("Iniciar Grabación")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = {
            startAudioService(context, "STOP_RECORDING")
        }) {
            Text("Detener y Enviar")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = {
            startAudioService(context, "PLAY_RESPONSE")
        }) {
            Text("Reproducir Respuesta")
        }
    }
}

    Recordatorio: manejar permisos en la Activity si no están otorgados (usando requestPermissions() en Android 6+).

3. Configuración de Servidor con WireGuard
3.1. Instalación en Ubuntu

sudo apt update
sudo apt install wireguard

3.2. Generación de Claves

cd /etc/wireguard
umask 077
wg genkey | tee server_privatekey | wg pubkey > server_publickey

3.3. Configuración /etc/wireguard/wg0.conf

Ejemplo:

[Interface]
Address = 10.8.0.1/24
PrivateKey = <contenido de server_privatekey>
ListenPort = 51820

PostUp = iptables -A FORWARD -i wg0 -j ACCEPT
PostUp = iptables -A FORWARD -o wg0 -j ACCEPT
PostUp = iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT
PostDown = iptables -D FORWARD -o wg0 -j ACCEPT
PostDown = iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE

[Peer]
PublicKey = <public_key_del_cliente>
AllowedIPs = 10.8.0.2/32

Iniciar interfaz:

sudo wg-quick up wg0

Para ver el estado:

sudo wg

Cada cliente (por ejemplo, tu móvil con la app oficial de WireGuard o tu portátil) tendrá su propio par de claves y se añadirá con su sección [Peer] en el servidor y viceversa.
4. Configuración de TLS (wss://)
4.1. Instalar Nginx y certbot

sudo apt update
sudo apt install nginx certbot python3-certbot-nginx

4.2. Obtener un Certificado con Let's Encrypt

Si tienes un dominio (ej. miservidor.com) apuntando a tu servidor:

sudo certbot --nginx -d miservidor.com

Certbot configurará automáticamente Nginx para HTTPS.
4.3. Configurar WebSocket Proxy

En el archivo /etc/nginx/sites-available/miservidor.conf (o similar), añade algo como:

server {
    listen 443 ssl;
    server_name miservidor.com;

    ssl_certificate /etc/letsencrypt/live/miservidor.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/miservidor.com/privkey.pem;

    location /ws {
        proxy_pass http://127.0.0.1:5000;  # Puerto donde corre tu backend WebSocket
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600;
    }
}

Si tu backend en Python/Node corre en 127.0.0.1:5000, Nginx se encargará de la capa TLS y proveerá una conexión wss://miservidor.com/ws.
5. Conclusión y Buenas Prácticas

    Permisos de Grabación: Asegurar que la app solicite RECORD_AUDIO en tiempo de ejecución antes de iniciar la grabación.
    Almacenamiento: Usar filesDir (almacenamiento interno), en lugar de externalCacheDir, para mayor seguridad.
    TLS + WireGuard:
        WireGuard protege la comunicación a nivel de red (VPN).
        TLS (wss://) agrega cifrado a nivel de aplicación y facilita las conexiones desde clientes externos sin exponer servicios en claro.
    Liberación de Recursos: Liberar MediaRecorder, cerrar WebSockets y ExoPlayer en los momentos adecuados.
    Asegurarse de la Persistencia: Ver si se necesitan reintentos en caso de fallos de conexión.
    Logs: Evitar loguear datos sensibles.
    Certificate Pinning (opcional) para mayor seguridad en OkHttp, validando la huella de tu certificado.

De esta forma, tu servicio Android podrá grabar, enviar y reproducir audio de manera segura y eficiente. Igualmente, tu servidor Ubuntu, con WireGuard y TLS, estará debidamente preparado para atender peticiones wss:// desde tu aplicación.