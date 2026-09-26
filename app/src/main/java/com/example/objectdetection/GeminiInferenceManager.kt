package com.example.objectdetection

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.objects.DetectedObject
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.math.abs

class GeminiInferenceManager(
  private val context: Context,
  private val ttsHelper: TtsHelper,
  private val geminiHelper: GeminiInferenceHelper
) {

  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var lastGeminiCallTimeMs = 0L
  private var lastDetectedObjectLabels = setOf<String>()
  private var latestFrameData: ByteBuffer? = null
  private var latestFrameMetadata: FrameMetadata? = null

  @Synchronized
  fun updateLatestFrame(frameData: ByteBuffer?, frameMetadata: FrameMetadata?) {
    latestFrameData = frameData
    latestFrameMetadata = frameMetadata
  }

  fun onFrameProcessed(
    results: List<DetectedObject>,
    frameData: ByteBuffer?,
    frameMetadata: FrameMetadata?,
    isBumpAlert: Boolean
  ) {
    updateLatestFrame(frameData, frameMetadata)

    if (!PreferenceUtils.isInferenceModeEnabled(context)) {
      return
    }

    val currentTime = System.currentTimeMillis()

    // Condition 2: Bump alert triggered
    if (isBumpAlert) {
      Log.d(TAG, "Triggering Gemini due to Bump Alert")
      triggerGemini(latestFrameData, latestFrameMetadata, isBumpAlert = true)
      return
    }

    // Condition 1: Scene of detected objects changed significantly
    val currentLabels = results.mapNotNull { obj ->
      obj.labels.maxByOrNull { it.confidence }?.text
    }.toSet()

    val isSceneChanged = hasSceneChangedSignificantly(lastDetectedObjectLabels, currentLabels)
    if (isSceneChanged && (currentTime - lastGeminiCallTimeMs >= MIN_CALL_INTERVAL_MS)) {
      Log.d(TAG, "Triggering Gemini due to Significant Scene Change")
      lastDetectedObjectLabels = currentLabels
      triggerGemini(latestFrameData, latestFrameMetadata, isBumpAlert = false)
      return
    }
  }

  private fun hasSceneChangedSignificantly(oldLabels: Set<String>, newLabels: Set<String>): Boolean {
    if (oldLabels.isEmpty() && newLabels.isNotEmpty()) return true
    if (oldLabels.isNotEmpty() && newLabels.isEmpty()) return true
    val union = oldLabels union newLabels
    if (union.isEmpty()) return false
    val intersection = oldLabels intersect newLabels
    val jaccard = intersection.size.toFloat() / union.size.toFloat()
    return jaccard < 0.6f || absSizeDiff(oldLabels, newLabels) >= 2
  }

  private fun absSizeDiff(a: Set<String>, b: Set<String>): Int {
    return abs(a.size - b.size)
  }

  fun triggerGemini(frameData: ByteBuffer?, frameMetadata: FrameMetadata?, isBumpAlert: Boolean) {
    if (frameData == null || frameMetadata == null) return
    val currentTime = System.currentTimeMillis()
    if (!isBumpAlert && currentTime - lastGeminiCallTimeMs < MIN_CALL_INTERVAL_MS) {
      return
    }

    lastGeminiCallTimeMs = currentTime

    scope.launch {
      val bitmap = withContext(Dispatchers.Default) {
        BitmapUtils.getBitmap(frameData, frameMetadata)
      }

      if (bitmap != null) {
        val description = geminiHelper.analyzeFrame(bitmap, isBumpAlert)
        if (!bitmap.isRecycled) {
          bitmap.recycle()
        }

        if (!description.isNullOrBlank()) {
          Log.d(TAG, "Gemini response: $description (bumpAlert: $isBumpAlert)")
          ttsHelper.speak(description, override = isBumpAlert)
        }
      }
    }
  }

  fun release() {
    scope.cancel()
  }

  companion object {
    private const val TAG = "GeminiInferenceManager"
    private const val MIN_CALL_INTERVAL_MS = 10000L // 10 seconds minimum between calls
  }
}
