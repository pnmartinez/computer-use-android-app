# Guía de Implementación: Long Polling para Cliente Android

Esta guía describe cómo implementar la recepción de actualizaciones desde el servidor LLM Control en una aplicación Android usando Long Polling.

## Arquitectura

```
┌─────────────────┐                               ┌─────────────────┐
│  Cliente Android│ ── GET /pending-updates ───► │  Flask Server   │
│  (long polling) │ ◄── JSON con updates ──────── │  (llm-control)  │
└─────────────────┘                               └─────────────────┘
                                                           ▲
                                                  POST /push-update
                                                           │
                                                  ┌─────────────────┐
                                                  │ Cursor / MCP    │
                                                  │ (fuente externa)│
                                                  └─────────────────┘
```

## Endpoints del Servidor

### 1. `GET /pending-updates` - Long Polling

Este es el endpoint principal que el cliente Android debe consultar.

**Comportamiento:**
- El servidor **espera** hasta que haya actualizaciones disponibles o se alcance el timeout
- Reduce el número de requests vacíos comparado con polling tradicional
- Las actualizaciones se **consumen** al entregarlas (no se repiten)

**Parámetros Query:**
| Parámetro | Tipo | Default | Descripción |
|-----------|------|---------|-------------|
| `timeout` | int | 30 | Segundos máximos de espera (máx: 60) |
| `since` | string | null | Timestamp ISO para filtrar updates más recientes |

**Request:**
```
GET http://servidor:5000/pending-updates?timeout=30
```

**Response (con updates):**
```json
{
  "status": "success",
  "updates": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "timestamp": "2026-01-08T15:30:00.000000",
      "type": "cursor_update",
      "summary": "Modificado login.py: añadida validación de email",
      "changes": ["login.py", "validators.py"],
      "metadata": {}
    }
  ],
  "has_more": false
}
```

**Response (sin updates - timeout):**
```json
{
  "status": "success",
  "updates": [],
  "has_more": false,
  "timeout": true
}
```

### 2. `GET /pending-updates/peek` - Debug

Ver la cola sin consumir (para debugging).

```
GET http://servidor:5000/pending-updates/peek
```

```json
{
  "status": "success",
  "count": 2,
  "updates": [...]
}
```

---

## Implementación en Android (Kotlin)

### Dependencias (build.gradle)

```kotlin
dependencies {
    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### Modelos de Datos

```kotlin
// UpdateModels.kt

data class PendingUpdatesResponse(
    val status: String,
    val updates: List<ServerUpdate>,
    val has_more: Boolean,
    val timeout: Boolean? = null
)

data class ServerUpdate(
    val id: String,
    val timestamp: String,
    val type: String,
    val summary: String,
    val changes: List<String>,
    val metadata: Map<String, Any>?
)
```

### Servicio de Long Polling

```kotlin
// LongPollingService.kt

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import java.util.concurrent.TimeUnit

class LongPollingService(
    private val serverUrl: String,
    private val onUpdateReceived: (ServerUpdate) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)  // Mayor que el timeout del servidor
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private var isRunning = false
    private var pollingJob: Job? = null
    
    /**
     * Inicia el loop de long polling
     */
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isRunning && isActive) {
                try {
                    pollForUpdates()
                } catch (e: Exception) {
                    if (isRunning) {
                        onError(e)
                        // Esperar antes de reintentar en caso de error
                        delay(5000)
                    }
                }
            }
        }
    }
    
    /**
     * Detiene el long polling
     */
    fun stop() {
        isRunning = false
        pollingJob?.cancel()
    }
    
    private suspend fun pollForUpdates() {
        val request = Request.Builder()
            .url("$serverUrl/pending-updates?timeout=30")
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("Server error: ${response.code}")
        }
        
        val body = response.body?.string() ?: return
        val result = gson.fromJson(body, PendingUpdatesResponse::class.java)
        
        if (result.status == "success" && result.updates.isNotEmpty()) {
            // Procesar cada update en el main thread
            withContext(Dispatchers.Main) {
                result.updates.forEach { update ->
                    onUpdateReceived(update)
                }
            }
        }
        
        // Si hubo timeout, inmediatamente hacer otra request
        // (el servidor ya esperó, así que esto no genera overhead)
    }
}
```

### Uso en Activity/Fragment

```kotlin
// MainActivity.kt

class MainActivity : AppCompatActivity() {
    
    private lateinit var pollingService: LongPollingService
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Configurar el servidor (obtener de SharedPreferences o configuración)
        val serverUrl = "http://192.168.1.100:5000"
        
        pollingService = LongPollingService(
            serverUrl = serverUrl,
            onUpdateReceived = { update ->
                // Mostrar el update al usuario
                showUpdateNotification(update)
            },
            onError = { error ->
                Log.e("LongPolling", "Error: ${error.message}")
                // Opcionalmente mostrar error al usuario
            }
        )
        
        // Iniciar polling
        pollingService.start(scope)
    }
    
    private fun showUpdateNotification(update: ServerUpdate) {
        // Mostrar en UI
        runOnUiThread {
            Toast.makeText(
                this,
                "📥 ${update.summary}",
                Toast.LENGTH_LONG
            ).show()
        }
        
        // O mostrar una notificación del sistema
        // showSystemNotification(update)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        pollingService.stop()
        scope.cancel()
    }
}
```

### Servicio en Background (opcional)

Para recibir updates incluso cuando la app está en segundo plano:

```kotlin
// UpdateForegroundService.kt

class UpdateForegroundService : Service() {
    
    private lateinit var pollingService: LongPollingService
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        
        val serverUrl = getServerUrl() // Obtener de SharedPreferences
        
        pollingService = LongPollingService(
            serverUrl = serverUrl,
            onUpdateReceived = { update ->
                showNotification(update)
            },
            onError = { error ->
                Log.e("UpdateService", "Error: ${error.message}")
            }
        )
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        pollingService.start(scope)
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        pollingService.stop()
        scope.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun showNotification(update: ServerUpdate) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("Actualización de Cursor")
            .setContentText(update.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(update.summary))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(update.id.hashCode(), notification)
    }
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "updates_channel"
    }
}
```

---

## Manejo de Errores y Reconexión

### Estrategia de Reconexión Exponencial

```kotlin
class LongPollingServiceWithRetry(
    private val serverUrl: String,
    private val onUpdateReceived: (ServerUpdate) -> Unit,
    private val onError: (Exception) -> Unit,
    private val onConnectionStatusChanged: (Boolean) -> Unit
) {
    private var retryDelayMs = 1000L
    private val maxRetryDelayMs = 60000L
    
    private suspend fun pollWithRetry() {
        while (isRunning) {
            try {
                pollForUpdates()
                // Reset delay on success
                retryDelayMs = 1000L
                onConnectionStatusChanged(true)
            } catch (e: Exception) {
                onConnectionStatusChanged(false)
                onError(e)
                
                // Espera exponencial
                delay(retryDelayMs)
                retryDelayMs = minOf(retryDelayMs * 2, maxRetryDelayMs)
            }
        }
    }
}
```

---

## Testing

### Enviar un Update de Prueba

```bash
curl -X POST http://servidor:5000/push-update \
  -H "Content-Type: application/json" \
  -d '{
    "summary": "Test: Modificado archivo de prueba",
    "changes": ["test.py"],
    "type": "cursor_update"
  }'
```

### Verificar Cola (sin consumir)

```bash
curl http://servidor:5000/pending-updates/peek
```

### Consumir Updates

```bash
curl "http://servidor:5000/pending-updates?timeout=5"
```

---

## Consideraciones de Batería

El long polling es más eficiente que el polling tradicional porque:
1. Menos requests HTTP (el servidor espera en lugar del cliente)
2. Menos procesamiento de respuestas vacías
3. El timeout de 30 segundos es un buen balance

Para optimizar aún más en Android:
- Usar `WorkManager` con constraints de red para polling en background
- Aumentar el timeout cuando la app está en segundo plano
- Detener el polling completamente cuando no hay conectividad

---

## Próximos Pasos

Si necesitas menor latencia o notificaciones push reales en el futuro, considera migrar a:
- **WebSockets** (Socket.IO) - Bidireccionalidad real
- **Firebase Cloud Messaging (FCM)** - Push notifications nativas de Android
