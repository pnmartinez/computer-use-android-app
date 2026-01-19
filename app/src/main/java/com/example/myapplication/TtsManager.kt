package com.example.myapplication

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Manages Text-to-Speech functionality.
 * Provides a simplified interface for TTS operations.
 *
 * Usage:
 *   val ttsManager = TtsManager(context) { status ->
 *       when (status) {
 *           TtsManager.Status.READY -> // TTS is ready
 *           TtsManager.Status.SPEAKING -> // TTS is speaking
 *           TtsManager.Status.DONE -> // TTS finished speaking
 *           TtsManager.Status.ERROR -> // TTS error occurred
 *       }
 *   }
 *   ttsManager.speak("Hello world")
 */
class TtsManager(
    private val context: Context,
    private val onStatusChanged: (Status) -> Unit = {}
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    var language: String = DEFAULT_LANGUAGE
        set(value) {
            field = value
            applyLanguage()
        }

    var speechRate: Float = DEFAULT_RATE
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setSpeechRate(field)
        }

    var pitch: Float = DEFAULT_PITCH
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }

    val isSpeaking: Boolean
        get() = tts?.isSpeaking == true

    init {
        initializeTts()
    }

    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                applyLanguage()
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(pitch)
                setupProgressListener()
                onStatusChanged(Status.READY)
                Log.d(TAG, "TTS initialized successfully")
            } else {
                isInitialized = false
                onStatusChanged(Status.ERROR)
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    private fun applyLanguage() {
        if (!isInitialized) return

        val locale = when (language) {
            "es" -> Locale("es", "ES")
            "en" -> Locale.US
            "fr" -> Locale.FRANCE
            "de" -> Locale.GERMANY
            "it" -> Locale.ITALY
            "pt" -> Locale("pt", "BR")
            else -> Locale.getDefault()
        }

        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language $language not supported, falling back to default")
            tts?.setLanguage(Locale.getDefault())
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onStatusChanged(Status.SPEAKING)
            }

            override fun onDone(utteranceId: String?) {
                onStatusChanged(Status.DONE)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onStatusChanged(Status.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error: $errorCode for utterance: $utteranceId")
                onStatusChanged(Status.ERROR)
            }
        })
    }

    /**
     * Speaks the given text
     * @param text The text to speak
     * @param utteranceId Optional ID for tracking this utterance
     * @return true if speech was queued successfully
     */
    fun speak(text: String, utteranceId: String = "tts_${System.currentTimeMillis()}"): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak")
            return false
        }

        if (text.isBlank()) {
            Log.w(TAG, "Empty text, nothing to speak")
            return false
        }

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return result == TextToSpeech.SUCCESS
    }

    /**
     * Adds text to the speech queue without interrupting current speech
     */
    fun queue(text: String, utteranceId: String = "tts_${System.currentTimeMillis()}"): Boolean {
        if (!isInitialized || text.isBlank()) return false
        return tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
    }

    /**
     * Stops any current speech
     */
    fun stop() {
        tts?.stop()
        onStatusChanged(Status.DONE)
    }

    /**
     * Releases TTS resources. Call when done with TTS.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS shutdown")
    }

    /**
     * Reinitializes TTS (useful after errors or configuration changes)
     */
    fun reinitialize() {
        shutdown()
        initializeTts()
    }

    enum class Status {
        READY,      // TTS is initialized and ready
        SPEAKING,   // TTS is currently speaking
        DONE,       // TTS finished speaking
        ERROR       // An error occurred
    }

    companion object {
        private const val TAG = "TtsManager"
        const val DEFAULT_LANGUAGE = "es"
        const val DEFAULT_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
    }
}
