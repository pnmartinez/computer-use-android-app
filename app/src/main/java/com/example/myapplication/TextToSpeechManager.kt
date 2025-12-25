package com.example.myapplication

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.UUID

class TextToSpeechManager(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val textToSpeech: TextToSpeech = TextToSpeech(context.applicationContext, this)
    private var isReady: Boolean = false

    override fun onInit(status: Int) {
        isReady = status == TextToSpeech.SUCCESS
        if (isReady) {
            val languageResult = textToSpeech.setLanguage(Locale("es", "ES"))
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

    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()): Boolean {
        if (!isReady || text.isBlank()) {
            return false
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return true
    }

    fun shutdown() {
        textToSpeech.stop()
        textToSpeech.shutdown()
    }
}
