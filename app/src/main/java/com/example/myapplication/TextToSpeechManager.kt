package com.example.myapplication

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import java.util.UUID

class TextToSpeechManager(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit = {},
    private val onSpeakStart: () -> Unit = {},
    private val onSpeakDone: () -> Unit = {},
    private val onSpeakError: (String?) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val textToSpeech: TextToSpeech = TextToSpeech(context.applicationContext, this)
    private var isReady: Boolean = false
    private var ttsLocale: Locale = Locale("es", "ES")
    private var ttsRate: Float = 1.0f
    private var ttsPitch: Float = 1.0f
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onInit(status: Int) {
        isReady = status == TextToSpeech.SUCCESS
        if (isReady) {
            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    mainHandler.post { onSpeakStart() }
                }

                override fun onDone(utteranceId: String) {
                    mainHandler.post { onSpeakDone() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    mainHandler.post { onSpeakError(null) }
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    mainHandler.post { onSpeakError(errorCode.toString()) }
                }
            })
            val languageResult = textToSpeech.setLanguage(ttsLocale)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w("TextToSpeech", "Default language not supported")
                isReady = false
            }
        } else {
            Log.e("TextToSpeech", "Failed to initialize TextToSpeech")
        }
        onReadyChanged(isReady)
    }

    fun updateConfig(languageTag: String, rate: Float, pitch: Float): Boolean {
        ttsLocale = parseLocale(languageTag)
        ttsRate = rate
        ttsPitch = pitch
        if (!isReady) {
            return false
        }
        val languageResult = textToSpeech.setLanguage(ttsLocale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w("TextToSpeech", "Configured language not supported: $languageTag")
            return false
        }
        textToSpeech.setSpeechRate(ttsRate)
        textToSpeech.setPitch(ttsPitch)
        return true
    }

    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()): Boolean {
        if (!isReady || text.isBlank()) {
            return false
        }
        textToSpeech.setSpeechRate(ttsRate)
        textToSpeech.setPitch(ttsPitch)
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return true
    }

    fun shutdown() {
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    private fun parseLocale(languageTag: String): Locale {
        val trimmed = languageTag.trim()
        if (trimmed.isEmpty()) {
            return Locale("es", "ES")
        }
        val normalized = trimmed.replace('_', '-')
        val parts = normalized.split('-')
        return when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            3 -> Locale(parts[0], parts[1], parts[2])
            else -> Locale.forLanguageTag(normalized)
        }
    }
}
