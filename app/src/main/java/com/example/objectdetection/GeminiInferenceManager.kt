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
  private var lastGeminiCallRealtimeMs = 0L
  private var latestFrameData: ByteBuffer? = null
  private var latestFrameMetadata: FrameMetadata? = null
  private val recentOutputs = ArrayDeque<String>()
  @Volatile var lastInferenceDurationMs: Long? = null
    private set
  @Volatile var lastCallIntervalMs: Long? = null
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
    sceneContext: String,
    cameraMotion: CameraMotionState = CameraMotionState.UNKNOWN
  ) {
    updateLatestFrame(frameData, frameMetadata)

    if (!PreferenceUtils.isInferenceModeEnabled(context)) {
      return
    }

    val currentTime = SystemClock.elapsedRealtime()

    if (lastGeminiCallRealtimeMs == 0L || currentTime - lastGeminiCallRealtimeMs >= MIN_CALL_INTERVAL_MS) {
      Log.d(TAG, "Triggering Gemini periodically (every ${MIN_CALL_INTERVAL_MS / 1000} seconds)")
      triggerGemini(latestFrameData, latestFrameMetadata, isBumpAlert, sceneContext, cameraMotion)
    }
  }

  fun triggerGemini(
    frameData: ByteBuffer?,
    frameMetadata: FrameMetadata?,
    isBumpAlert: Boolean = false,
    sceneContext: String = "",
    cameraMotion: CameraMotionState = CameraMotionState.UNKNOWN
  ) {
    if (frameData == null || frameMetadata == null) return
    val currentTime = SystemClock.elapsedRealtime()
    if (lastGeminiCallRealtimeMs > 0L && currentTime - lastGeminiCallRealtimeMs < MIN_CALL_INTERVAL_MS) {
      return
    }

    val intervalMs = if (lastGeminiCallRealtimeMs > 0L) currentTime - lastGeminiCallRealtimeMs else null
    lastCallIntervalMs = intervalMs
    lastGeminiCallRealtimeMs = currentTime

    val triggerType = if (isBumpAlert) "Bump Alert" else "Periodic"

    scope.launch {
      val startTimeMs = SystemClock.elapsedRealtime()
      inferenceInProgress = true

      try {
        val bitmap = withContext(Dispatchers.Default) {
          BitmapUtils.getBitmap(frameData, frameMetadata)
        }

        if (bitmap != null) {
          val result = geminiHelper.analyzeFrame(
            bitmap,
            isBumpAlert,
            sceneContext,
            recentOutputs.toList(),
            cameraMotion
          )
          if (!bitmap.isRecycled) {
            bitmap.recycle()
          }

          val durationMs = SystemClock.elapsedRealtime() - startTimeMs
          lastInferenceDurationMs = durationMs

          if (result.errorMessage != "Analysis already in flight") {
            GeminiCallLogger.logCall(
              triggerType = triggerType,
              sceneContext = sceneContext,
              responseText = result.responseText,
              durationMs = durationMs,
              intervalMs = intervalMs,
              isSuccess = result.isSuccess,
              errorMessage = result.errorMessage
            )
          }

          val description = result.responseText
          if (!description.isNullOrBlank()) {
            recentOutputs.addLast(description)
            while (recentOutputs.size > MAX_RECENT_OUTPUTS) {
              recentOutputs.removeFirst()
            }
            Log.d(TAG, "Gemini response: $description")
            ttsHelper.speak(
              description,
              override = false,
              critical = isCriticalResponse(description) || isBumpAlert
            )
          }
        }
      } finally {
        inferenceInProgress = false
      }
    }
  }

  fun release() {
    scope.cancel()
  }

  private fun isCriticalResponse(description: String): Boolean {
    val normalized = description.lowercase()
    return listOf("stop", "collision", "immediate", "danger", "bump", "hazard").any {
      normalized.contains(it)
    }
  }

  companion object {
    private const val TAG = "GeminiInferenceManager"
    private const val MIN_CALL_INTERVAL_MS = 5000L // 10 seconds minimum between periodic calls
    private const val MAX_RECENT_OUTPUTS = 3
  }
}
