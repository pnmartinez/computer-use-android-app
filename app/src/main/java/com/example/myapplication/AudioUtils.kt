package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Inicia el servicio de audio con la acción especificada.
 */
fun startAudioService(context: Context, action: String) {
    val serviceIntent = Intent(context, AudioService::class.java).apply {
        this.action = action
    }
    context.startService(serviceIntent)
}

/**
 * Verifica si el permiso de grabación de audio está otorgado.
 */
fun hasRecordAudioPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * Solicita el permiso de grabación de audio.
 */
fun requestRecordAudioPermission(activity: androidx.appcompat.app.AppCompatActivity) {
    ActivityCompat.requestPermissions(
        activity,
        arrayOf(android.Manifest.permission.RECORD_AUDIO),
        RECORD_AUDIO_PERMISSION_REQUEST_CODE
    )
}

// Constante para el código de solicitud de permiso
const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 100 