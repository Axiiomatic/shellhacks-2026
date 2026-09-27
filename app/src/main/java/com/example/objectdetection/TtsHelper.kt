package com.example.objectdetection

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsHelper(
  context: Context,
  speechRateMultiplier: Float = DEFAULT_SPEECH_RATE_MULTIPLIER,
) : TextToSpeech.OnInitListener {

  private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
  private var isInitialized = false
  var speechRateMultiplier: Float = speechRateMultiplier
    set(value) {
      require(value.isFinite() && value > 0f && value <= MAX_SPEECH_RATE_MULTIPLIER) {
        "Speech rate multiplier must be finite, greater than zero, and at most 2x"
      }
      field = value
      if (isInitialized) {
        tts?.setSpeechRate(field)
      }
    }

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      val result = tts?.setLanguage(Locale.US)
      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        Log.e(TAG, "TTS Language not supported")
      } else {
        tts?.setSpeechRate(speechRateMultiplier)
        isInitialized = true
      }
    } else {
      Log.e(TAG, "TTS Initialization failed with status: $status")
    }
  }

  fun speak(text: String, override: Boolean = false, critical: Boolean = false) {
    if (!isInitialized || text.isBlank()) return
    val queueMode = if (override) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
    tts?.setSpeechRate(if (critical) MAX_SPEECH_RATE_MULTIPLIER else speechRateMultiplier)
    tts?.speak(text, queueMode, null, null)
  }

  fun stop() {
    try {
      tts?.stop()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping TTS", e)
    }
  }

  fun release() {
    try {
      tts?.stop()
      tts?.shutdown()
      tts = null
      isInitialized = false
    } catch (e: Exception) {
      Log.e(TAG, "Error releasing TTS", e)
    }
  }

  companion object {
    private const val TAG = "TtsHelper"
    const val DEFAULT_SPEECH_RATE_MULTIPLIER = 1.5f
    private const val MAX_SPEECH_RATE_MULTIPLIER = 2.0f
  }
}
