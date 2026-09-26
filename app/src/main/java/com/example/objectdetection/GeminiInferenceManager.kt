package com.example.objectdetection

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.objects.DetectedObject
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class GeminiInferenceManager(
  private val context: Context,
  private val ttsHelper: TtsHelper,
  private val geminiHelper: GeminiInferenceHelper
) {

  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var lastGeminiCallTimeMs = 0L
  private var latestFrameData: ByteBuffer? = null
  private var latestFrameMetadata: FrameMetadata? = null

  @Synchronized
  fun updateLatestFrame(frameData: ByteBuffer?, frameMetadata: FrameMetadata?) {
    latestFrameData = frameData
    latestFrameMetadata = frameMetadata
  }

  fun onFrameProcessed(
    _results: List<DetectedObject>,
    frameData: ByteBuffer?,
    frameMetadata: FrameMetadata?,
    _isBumpAlert: Boolean
  ) {
    updateLatestFrame(frameData, frameMetadata)

    if (!PreferenceUtils.isInferenceModeEnabled(context)) {
      return
    }

    val currentTime = System.currentTimeMillis()

    if (currentTime - lastGeminiCallTimeMs >= MIN_CALL_INTERVAL_MS) {
      Log.d(TAG, "Triggering Gemini periodically (every ${MIN_CALL_INTERVAL_MS / 1000} seconds)")
      triggerGemini(latestFrameData, latestFrameMetadata)
    }
  }

  fun triggerGemini(frameData: ByteBuffer?, frameMetadata: FrameMetadata?) {
    if (frameData == null || frameMetadata == null) return
    val currentTime = System.currentTimeMillis()
    if (currentTime - lastGeminiCallTimeMs < MIN_CALL_INTERVAL_MS) {
      return
    }

    lastGeminiCallTimeMs = currentTime

    scope.launch {
      val bitmap = withContext(Dispatchers.Default) {
        BitmapUtils.getBitmap(frameData, frameMetadata)
      }

      if (bitmap != null) {
        val description = geminiHelper.analyzeFrame(bitmap, false)
        if (!bitmap.isRecycled) {
          bitmap.recycle()
        }

        if (!description.isNullOrBlank()) {
          Log.d(TAG, "Gemini response: $description")
          ttsHelper.speak(description, override = false)
        }
      }
    }
  }

  fun release() {
    scope.cancel()
  }

  companion object {
    private const val TAG = "GeminiInferenceManager"
    private const val MIN_CALL_INTERVAL_MS = 10000L // 10 seconds minimum between periodic calls
  }
}
