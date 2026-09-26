package com.example.objectdetection

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/**
 * Helper class for triggering audio sound alerts with directional stereo panning when an object is rapidly approaching.
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
   * Plays the alert sound effect with directional stereo panning based on screen position normX [0.0..1.0].
   */
  fun playAlertSound(normX: Float = 0.5f) {
    val currentTime = System.currentTimeMillis()
    if (currentTime - lastPlayTimeMs < SOUND_COOLDOWN_MS) return
    if (!isLoaded || soundId == 0) return

    lastPlayTimeMs = currentTime

    val (leftVol, rightVol) = calculateStereoVolume(normX)

    try {
      soundPool?.play(soundId, leftVol, rightVol, 1, 0, 1.0f)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to play alert sound", e)
    }
  }

  /**
   * Calculates stereo volume (leftVolume, rightVolume) for directional audio panning based on screen position.
   * - Left side (normX < 0.38): Left ear full volume, right ear muted/reduced.
   * - Right side (normX > 0.62): Right ear full volume, left ear muted/reduced.
   * - Center (0.38 <= normX <= 0.62): Both ears full volume.
   */
  fun calculateStereoVolume(normX: Float): Pair<Float, Float> {
    val clampedX = normX.coerceIn(0.0f, 1.0f)
    return when {
      clampedX < 0.38f -> {
        // Left ear full volume, right ear reduced (directional pan left)
        val rightVol = (clampedX / 0.38f) * 0.10f
        Pair(1.0f, rightVol)
      }
      clampedX > 0.62f -> {
        // Right ear full volume, left ear reduced (directional pan right)
        val leftVol = ((1.0f - clampedX) / 0.38f) * 0.10f
        Pair(leftVol, 1.0f)
      }
      else -> {
        // Center: Both ears full volume
        Pair(1.0f, 1.0f)
      }
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
