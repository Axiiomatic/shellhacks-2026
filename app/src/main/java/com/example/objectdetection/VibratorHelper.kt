package com.example.objectdetection

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Helper class for triggering distinct haptic vibration when an object is rapidly approaching.
 */
class VibratorHelper(context: Context) {

  @Suppress("DEPRECATION")
  private val vibrator: Vibrator? = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

  private var lastVibrateTimeMs = 0L

  /**
   * Triggers a strong vibration alert when an object approaches rapidly.
   */
  @RequiresPermission(Manifest.permission.VIBRATE)
  fun vibrateRapidApproach() {
    val currentTime = System.currentTimeMillis()
    if (currentTime - lastVibrateTimeMs < VIBRATE_COOLDOWN_MS) return
    lastVibrateTimeMs = currentTime

    val v = vibrator ?: return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val audioAttributes = AudioAttributes.Builder()
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .setUsage(AudioAttributes.USAGE_ALARM)
          .build()

        // Double pulse pattern: 0ms wait, 180ms vibrate, 80ms pause, 180ms vibrate
        val timings = longArrayOf(0, 180, 80, 180)
        val amplitudes = intArrayOf(0, 255, 0, 255) // Max amplitude

        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        v.vibrate(effect, audioAttributes)
      } else {
        @Suppress("DEPRECATION")
        v.vibrate(longArrayOf(0, 180, 80, 180), -1)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to execute vibration", e)
      // Fallback one-shot
      try {
        @Suppress("DEPRECATION")
        v.vibrate(250L)
      } catch (ignored: Exception) {}
    }
  }

  companion object {
    private const val TAG = "VibratorHelper"
    private const val VIBRATE_COOLDOWN_MS = 1200L
  }
}
