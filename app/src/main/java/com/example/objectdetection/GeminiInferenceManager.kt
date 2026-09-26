package com.example.objectdetection

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
  private val recentOutputs = ArrayDeque<String>()
  @Volatile var lastInferenceDurationMs: Long? = null
    private set
  @Volatile var inferenceInProgress: Boolean = false
    private set

  @Synchronized
  fun updateLatestFrame(frameData: ByteBuffer?, frameMetadata: FrameMetadata?) {
    latestFrameData = frameData
    latestFrameMetadata = frameMetadata
  }

  fun onFrameProcessed(
    frameData: ByteBuffer?,
    frameMetadata: FrameMetadata?,
    isBumpAlert: Boolean,
    sceneContext: String
  ) {
    updateLatestFrame(frameData, frameMetadata)

    if (!PreferenceUtils.isInferenceModeEnabled(context)) {
      return
    }

    val currentTime = System.currentTimeMillis()

    if (currentTime - lastGeminiCallTimeMs >= MIN_CALL_INTERVAL_MS) {
      Log.d(TAG, "Triggering Gemini periodically (every ${MIN_CALL_INTERVAL_MS / 1000} seconds)")
      triggerGemini(latestFrameData, latestFrameMetadata, isBumpAlert, sceneContext)
    }
  }

  fun triggerGemini(
    frameData: ByteBuffer?,
    frameMetadata: FrameMetadata?,
    isBumpAlert: Boolean = false,
    sceneContext: String = ""
  ) {
    if (frameData == null || frameMetadata == null) return
    val currentTime = System.currentTimeMillis()
    if (currentTime - lastGeminiCallTimeMs < MIN_CALL_INTERVAL_MS) {
      return
    }

    lastGeminiCallTimeMs = currentTime

    scope.launch {
      val startTimeMs = SystemClock.elapsedRealtime()
      inferenceInProgress = true

      try {
        val bitmap = withContext(Dispatchers.Default) {
          BitmapUtils.getBitmap(frameData, frameMetadata)
        }

        if (bitmap != null) {
          val description = geminiHelper.analyzeFrame(
            bitmap,
            isBumpAlert,
            sceneContext,
            recentOutputs.toList()
          )
          if (!bitmap.isRecycled) {
            bitmap.recycle()
          }

          if (!description.isNullOrBlank()) {
            recentOutputs.addLast(description)
            while (recentOutputs.size > MAX_RECENT_OUTPUTS) {
              recentOutputs.removeFirst()
            }
            Log.d(TAG, "Gemini response: $description")
            ttsHelper.speak(description, override = false)
          }
        }
      } finally {
        lastInferenceDurationMs = SystemClock.elapsedRealtime() - startTimeMs
        inferenceInProgress = false
      }
    }
  }

  fun release() {
    scope.cancel()
  }

  companion object {
    private const val TAG = "GeminiInferenceManager"
    private const val MIN_CALL_INTERVAL_MS = 10000L // 10 seconds minimum between periodic calls
    private const val MAX_RECENT_OUTPUTS = 3
  }
}
