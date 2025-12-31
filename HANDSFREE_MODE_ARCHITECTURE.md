# Arquitectura del Modo Manos Libres

## Índice
1. [Visión General](#visión-general)
2. [Componentes Principales](#componentes-principales)
3. [Flujo de Activación](#flujo-de-activación)
4. [Flujo de Grabación](#flujo-de-grabación)
5. [Gestión de Audio Bluetooth](#gestión-de-audio-bluetooth)
6. [Feedback Sonoro](#feedback-sonoro)
7. [Comunicación entre Componentes](#comunicación-entre-componentes)
8. [Persistencia de Datos](#persistencia-de-datos)
9. [Gestión del Ciclo de Vida](#gestión-del-ciclo-de-vida)
10. [Limitaciones Conocidas](#limitaciones-conocidas)
11. [Diagrama de Secuencia](#diagrama-de-secuencia)

---

## Visión General

El **Modo Manos Libres** permite al usuario controlar la grabación de comandos de voz usando auriculares Bluetooth, incluso con la pantalla bloqueada. El audio se graba a través del micrófono Bluetooth (SCO) y se envía al servidor para transcripción y ejecución.

### Características principales:
- Control mediante tap en auriculares Bluetooth
- Grabación usando micrófono Bluetooth (no el del dispositivo)
- Funciona con pantalla bloqueada
- Timeout automático de 15 segundos
- Feedback sonoro para indicar estados

---

## Componentes Principales

### 1. `MainActivity.kt`
- **Responsabilidad**: UI, control del switch de manos libres, mostrar estado del micrófono
- **Elementos UI**:
  - `drawerHandsfreeSwitch`: Switch para activar/desactivar el modo
  - `drawerMicrophoneStatus`: TextView que muestra el micrófono activo
  - `btnStartRecording`: Botón que muestra countdown durante grabación
  - `summaryCard`: Tarjeta con resumen y botón de reproducción

### 2. `AudioService.kt`
- **Responsabilidad**: Servicio en primer plano que gestiona grabación, Bluetooth SCO, MediaSession
- **Variables clave**:
  ```kotlin
  headsetControlEnabled: Boolean     // Estado del modo manos libres
  isBluetoothScoOn: Boolean          // Estado de conexión SCO
  isRecording: Boolean               // Estado de grabación
  pendingRecordingAfterSco: Boolean  // Espera SCO para grabar
  ```

### 3. `MediaSessionCompat`
- **Responsabilidad**: Capturar eventos de botones de media (tap en auriculares)
- **Ubicación**: Dentro de `AudioService`
- **Callback**: `mediaSessionCallback` maneja `onMediaButtonEvent()`

---

## Flujo de Activación

```
Usuario activa switch en drawer
        │
        ▼
MainActivity.setupHandsfreeSwitchListener()
        │
        ├─► showHandsfreeBetaDialog() (primera vez)
        │
        ▼
Intent → AudioService.ACTION_TOGGLE_HEADSET_CONTROL
        │
        ▼
AudioService.enableHeadsetControlMode()
        │
        ├─► startForegroundService() (notificación persistente)
        ├─► setupMediaSession() (captura botones)
        ├─► requestAudioFocus()
        └─► sendHeadsetControlStatus(true) → Broadcast a MainActivity
```

### Desactivación:
```
Usuario desactiva switch
        │
        ▼
AudioService.disableHeadsetControlMode()
        │
        ├─► mediaSession?.release()
        ├─► stopBluetoothSco()
        ├─► audioManager.mode = MODE_NORMAL
        ├─► stopForeground(STOP_FOREGROUND_REMOVE)
        └─► sendHeadsetControlStatus(false) → Broadcast
```

---

## Flujo de Grabación

### Secuencia completa (tap → respuesta):

```
1. TAP EN AURICULARES
   │
   ▼
2. MediaSessionCompat.Callback.onMediaButtonEvent()
   │
   ├─► Detecta KEYCODE_MEDIA_PLAY/PAUSE/NEXT/HEADSETHOOK
   │
   ▼
3. handleHeadsetButtonPress()
   │
   ├─► Si NO grabando: startRecording()
   └─► Si grabando: stopRecordingAndSend()

4. startRecording() [modo manos libres]
   │
   ├─► playPreparingAndReadyFeedback() (~1.5s)
   │
   ├─► Thread.sleep(1300ms) // Esperar feedback
   │
   ├─► activateBluetoothMicForRecording()
   │   ├─► audioManager.mode = MODE_IN_COMMUNICATION
   │   ├─► audioManager.startBluetoothSco()
   │   └─► Esperar hasta 2s por conexión SCO
   │
   ├─► setupRecordingTimeout() // 15 segundos
   │
   └─► startRecordingInternal()
       ├─► MediaRecorder.setAudioSource(MIC)
       ├─► MediaRecorder.start()
       └─► sendRecordingStarted() → Broadcast

5. [GRABACIÓN EN CURSO - máx 15 segundos]
   │
   ▼
6. stopRecordingAndSend() [automático o por tap]
   │
   ├─► recorder.stop()
   ├─► playRecordingStopFeedback()
   ├─► stopBluetoothSco()
   ├─► audioManager.mode = MODE_NORMAL
   ├─► sendAudioFileInfo() // Guarda copia en caché
   │   └─► file.copyTo(cacheDir/last_sent_recording.ogg)
   │
   └─► sendAudioOverWebSocket()
       │
       ▼
7. SERVIDOR PROCESA
   │
   ├─► Transcripción (Whisper)
   ├─► Ejecución de comando
   └─► Respuesta JSON
       │
       ▼
8. handleServerResponse()
   │
   ├─► Parsear JSON
   ├─► sendAppBroadcast(ACTION_RESPONSE_RECEIVED)
   │   └─► MainActivity actualiza summaryCard
   │
   └─► createTextToSpeechResponse() [si hay audio]
```

---

## Gestión de Audio Bluetooth

### Conceptos clave:

| Concepto | Descripción |
|----------|-------------|
| **SCO** | Synchronous Connection-Oriented - Enlace para audio bidireccional |
| **A2DP** | Advanced Audio Distribution Profile - Solo salida (música) |
| **MODE_IN_COMMUNICATION** | Modo de audio para llamadas, habilita mic BT |
| **MODE_NORMAL** | Modo estándar, permite detectar botones de media |

### Activación del micrófono Bluetooth:

```kotlin
fun activateBluetoothMicForRecording() {
    // 1. Cambiar a modo comunicación (CRÍTICO)
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    
    // 2. Activar SCO
    audioManager.startBluetoothSco()
    
    // 3. Esperar conexión (hasta 2s)
    for (i in 1..10) {
        Thread.sleep(200)
        if (audioManager.isBluetoothScoOn) break
    }
    
    // 4. Opcionalmente: setCommunicationDevice() en API 31+
}
```

### Limitación importante:
> ⚠️ En `MODE_IN_COMMUNICATION`, los botones de media NO funcionan.
> Por eso usamos **timeout automático de 15 segundos**.

---

## Feedback Sonoro

### Inicio de grabación (`playPreparingAndReadyFeedback`):
```
Tiempo    Acción                      Tono
─────────────────────────────────────────────────
0ms       Confirmación tap            TONE_PROP_BEEP (200ms)
250ms     Preparando                  TONE_CDMA_ALERT_INCALL_LITE (400ms)
700ms     Listo 1                     TONE_PROP_ACK (150ms)
900ms     Listo 2                     TONE_PROP_BEEP2 (200ms)
1200ms    Release ToneGenerator
```

### Fin de grabación (`playRecordingStopFeedback`):
```
Tiempo    Acción                      Tono
─────────────────────────────────────────────────
0ms       Confirmación               TONE_PROP_BEEP2 (300ms)
350ms     Tono descendente           TONE_CDMA_CALLDROP_LITE (300ms)
750ms     Release ToneGenerator
```

### Stream de audio:
- **STREAM_ALARM**: Para inicio (funciona con pantalla bloqueada)
- **STREAM_MUSIC**: Para fin (después de volver a MODE_NORMAL)

---

## Comunicación entre Componentes

### Broadcasts (AudioService → MainActivity):

| Action | Datos | Propósito |
|--------|-------|-----------|
| `ACTION_HEADSET_CONTROL_STATUS` | `EXTRA_HEADSET_CONTROL_ENABLED` | Estado del modo |
| `ACTION_MICROPHONE_CHANGED` | `EXTRA_MICROPHONE_NAME` | Micrófono activo |
| `ACTION_RECORDING_STARTED` | - | Iniciar countdown UI |
| `ACTION_RECORDING_STOPPED` | - | Detener countdown UI |
| `ACTION_AUDIO_FILE_INFO` | `EXTRA_AUDIO_FILE_PATH`, `EXTRA_AUDIO_TYPE` | Info del archivo |
| `ACTION_RESPONSE_RECEIVED` | `EXTRA_RESPONSE_MESSAGE`, `EXTRA_SCREEN_SUMMARY` | Respuesta servidor |

### Intents (MainActivity → AudioService):

| Action | Propósito |
|--------|-----------|
| `ACTION_TOGGLE_HEADSET_CONTROL` | Activar/desactivar modo |
| `ACTION_HEADSET_BUTTON_PRESS` | Simular tap (desde UI) |

### Registro de receivers:
```kotlin
// MainActivity.kt
ContextCompat.registerReceiver(
    this,
    serviceReceiver,
    intentFilter,
    ContextCompat.RECEIVER_NOT_EXPORTED  // Seguridad Android 13+
)
```

> ⚠️ Los broadcasts requieren `intent.setPackage(packageName)` para llegar
> cuando se usa `RECEIVER_NOT_EXPORTED`.

---

## Persistencia de Datos

### Archivos:

| Archivo | Ubicación | Propósito |
|---------|-----------|-----------|
| `recorded_audio.ogg` | `filesDir` | Grabación actual |
| `last_sent_recording.ogg` | `cacheDir` | Copia para reproducción |
| `response_audio.ogg` | `filesDir` | Respuesta TTS del servidor |

### SharedPreferences (`AppPreferences`):

| Key | Tipo | Propósito |
|-----|------|-----------|
| `lastScreenSummary` | String | Último resumen recibido |
| `tutorialShown` | Boolean | Si se mostró el tutorial |

### Flujo de persistencia del audio:

```
AudioService.sendAudioFileInfo(file, "recording")
        │
        ├─► Copiar a cacheDir/last_sent_recording.ogg
        │   (independiente del broadcast)
        │
        └─► sendAppBroadcast(ACTION_AUDIO_FILE_INFO)
            (puede perderse si Activity en background)

MainActivity.onResume()
        │
        └─► restoreLastAudioFile()
            └─► Buscar en cacheDir o filesDir
```

---

## Gestión del Ciclo de Vida

### Problema: Pantalla bloqueada
Cuando la pantalla se bloquea, `MainActivity` entra en `onPause()` y:
- Se desregistra el `BroadcastReceiver`
- Los broadcasts se pierden
- La UI no se actualiza

### Solución implementada:

```kotlin
// AudioService: Guardar datos directamente
fun sendAudioFileInfo(file, type) {
    if (type == "recording") {
        file.copyTo(cacheDir/last_sent_recording.ogg)  // Persistir
    }
    sendAppBroadcast(...)  // Puede perderse
}

fun updateScreenSummary(summary) {
    sharedPrefs.putString(KEY_LAST_SUMMARY, summary)  // Persistir
}

// MainActivity: Restaurar en onResume
override fun onResume() {
    restoreLastAudioFile()   // Desde cacheDir
    restoreLastSummary()     // Desde SharedPreferences
}
```

### Diagrama de estados de Activity:

```
[FOREGROUND] ←─── onResume() ←─── [BACKGROUND]
     │                                  ▲
     │ onPause()                        │
     ▼                                  │
[BACKGROUND] ───── (pantalla bloqueada) ┘
     │
     │ Broadcasts perdidos
     │ AudioService sigue funcionando
     │
     └─► Datos guardados en caché/prefs
```

---

## Limitaciones Conocidas

### 1. Botones no funcionan durante grabación
- **Causa**: `MODE_IN_COMMUNICATION` bloquea eventos de media
- **Solución**: Timeout automático de 15 segundos
- **Alternativa futura**: Implementar wake word detection

### 2. Delay de ~3-4 segundos al iniciar
- **Causa**: Feedback sonoro (1.3s) + activación SCO (hasta 2s)
- **Trade-off**: Necesario para feedback audible y conexión estable

### 3. Algunos auriculares envían códigos diferentes
- **Observado**: Redmi Buds envían `KEYCODE_MEDIA_NEXT` en vez de `PLAY_PAUSE`
- **Solución**: Manejar múltiples keycodes como "toggle"

### 4. SCO puede desconectarse espontáneamente
- **Causa**: Comportamiento del stack Bluetooth de Android
- **Mitigación**: Reintentos automáticos en el flujo de grabación

---

## Diagrama de Secuencia

```
┌──────────┐     ┌─────────────┐     ┌──────────────┐     ┌────────┐
│  Usuario │     │ MainActivity│     │ AudioService │     │Servidor│
└────┬─────┘     └──────┬──────┘     └──────┬───────┘     └───┬────┘
     │                  │                   │                 │
     │ Tap auricular    │                   │                 │
     │─────────────────────────────────────>│                 │
     │                  │                   │                 │
     │                  │   ACTION_RECORDING_STARTED          │
     │                  │<──────────────────│                 │
     │                  │                   │                 │
     │                  │ [Countdown 15s]   │ [Grabando]      │
     │                  │                   │                 │
     │                  │                   │ [Timeout/Tap]   │
     │                  │                   │                 │
     │                  │   ACTION_RECORDING_STOPPED          │
     │                  │<──────────────────│                 │
     │                  │                   │                 │
     │                  │                   │ POST /voice-cmd │
     │                  │                   │────────────────>│
     │                  │                   │                 │
     │                  │                   │  JSON Response  │
     │                  │                   │<────────────────│
     │                  │                   │                 │
     │                  │ ACTION_RESPONSE_RECEIVED            │
     │                  │<──────────────────│                 │
     │                  │                   │                 │
     │  [UI actualizada]│                   │                 │
     │<─────────────────│                   │                 │
```

---

## Archivos Relevantes

| Archivo | Responsabilidad |
|---------|-----------------|
| `AudioService.kt` | Lógica principal del modo manos libres |
| `MainActivity.kt` | UI y control del switch |
| `drawer_footer.xml` | Layout del switch y estado del mic |
| `dialog_handsfree_beta.xml` | Modal de advertencia beta |
| `strings.xml` | Textos localizados |

---

## Configuración

### Constantes en AudioService:
```kotlin
RECORDING_TIMEOUT_MS = 15000L  // Timeout automático
```

### Constantes en MainActivity:
```kotlin
HANDSFREE_RECORDING_TIMEOUT_MS = 15000L  // Para countdown UI
```

---

## Futuras Mejoras Potenciales

1. **Wake word detection**: Permitir iniciar grabación por voz ("Hey app")
2. **Gestos personalizables**: Mapear diferentes acciones a tap simple/doble/triple
3. **Feedback háptico**: Vibración además del sonido
4. **Reducción de latencia**: Optimizar activación SCO
5. **Soporte multi-dispositivo**: Selección de auriculares específicos

---

*Última actualización: 31 de diciembre de 2025*

