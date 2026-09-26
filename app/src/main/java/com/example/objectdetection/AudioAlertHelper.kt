package com.example.objectdetection

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/**
 * Helper class for triggering audio sound alerts when an object is rapidly approaching.
 */
class AudioAlertHelper(context: Context) {

  private var soundPool: SoundPool? = null
  private var soundId: Int = 0
  private var isLoaded = false
  private var lastPlayTimeMs = 0L

  init {
    try {
      val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

      soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(attributes)
        .build()

      soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
        if (status == 0 && sampleId == soundId) {
          isLoaded = true
        }
      }

      soundId = soundPool?.load(context.applicationContext, R.raw.alert, 1) ?: 0
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize SoundPool for audio alerts", e)
    }
  }

  /**
   * Plays the alert sound effect if cooldown has elapsed and resource is loaded.
   */
  fun playAlertSound() {
    val currentTime = System.currentTimeMillis()
    if (currentTime - lastPlayTimeMs < SOUND_COOLDOWN_MS) return
    if (!isLoaded || soundId == 0) return

    lastPlayTimeMs = currentTime
    try {
      soundPool?.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to play alert sound", e)
    }
  }

  /**
   * Releases SoundPool native resources when processor stops.
   */
  fun release() {
    try {
      soundPool?.release()
      soundPool = null
      isLoaded = false
    } catch (e: Exception) {
      Log.e(TAG, "Failed to release SoundPool", e)
    }
  }

  companion object {
    private const val TAG = "AudioAlertHelper"
    private const val SOUND_COOLDOWN_MS = 1200L
  }
}
