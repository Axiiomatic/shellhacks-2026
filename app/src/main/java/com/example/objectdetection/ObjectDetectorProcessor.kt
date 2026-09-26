package com.example.objectdetection

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.IOException
import java.nio.ByteBuffer

/** A processor to run object detector with normalized relative motion tracking and haptic alerts. */
class ObjectDetectorProcessor(private val context: Context, options: ObjectDetectorOptions) :
  VisionProcessorBase<List<DetectedObject>>(context) {

  private val detector: ObjectDetector = ObjectDetection.getClient(options)
  private val motionTracker = MotionTracker()
  private val vibratorHelper = VibratorHelper(context)
  private val audioAlertHelper = AudioAlertHelper(context)
  private val ttsHelper = TtsHelper(context)
  private val geminiInferenceHelper = GeminiInferenceHelper(context)
  private val geminiInferenceManager = GeminiInferenceManager(context, ttsHelper, geminiInferenceHelper)
  private var lastAlertTimeMs = 0L

  override fun stop() {
    super.stop()
    try {
      detector.close()
    } catch (e: IOException) {
      Log.e(TAG, "Exception thrown while trying to close object detector!", e)
    }
    audioAlertHelper.release()
    ttsHelper.release()
    geminiInferenceManager.release()
  }

  override fun detectInImage(image: InputImage): Task<List<DetectedObject>> {
    return detector.process(image)
  }

  override fun onSuccess(results: List<DetectedObject>, graphicOverlay: GraphicOverlay) {
    onSuccess(results, graphicOverlay, null, null)
  }

  override fun onSuccess(
    results: List<DetectedObject>,
    graphicOverlay: GraphicOverlay,
    frameData: ByteBuffer?,
    frameMetadata: FrameMetadata?
  ) {
    val grayMat = if (frameData != null && frameMetadata != null) {
      createGrayMatFromBuffer(frameData, frameMetadata.width, frameMetadata.height)
    } else {
      null
    }

    try {
      val trackInfoMap = motionTracker.processFrame(
        detectedObjects = results,
        frameWidth = graphicOverlay.imageWidth,
        frameHeight = graphicOverlay.imageHeight,
        currGrayMat = grayMat
      )

      var rapidApproachDetected = false
      var hazardScreenNormX = 0.5f
      var maxHazardArea = -1.0f

      val safeWidth = maxOf(graphicOverlay.imageWidth, 1).toFloat()

      for (result in results) {
        val trackInfo = trackInfoMap[result]
        if (trackInfo?.isRapidApproaching == true) {
          rapidApproachDetected = true
          val box = result.boundingBox
          val boxArea = (box.right - box.left) * (box.bottom - box.top).toFloat()
          if (boxArea > maxHazardArea) {
            maxHazardArea = boxArea
            val centerX = (box.left + box.right) / 2.0f
            val rawNormX = centerX / safeWidth
            hazardScreenNormX = if (graphicOverlay.isImageFlipped) 1.0f - rawNormX else rawNormX
          }
        }
        graphicOverlay.add(ObjectGraphic(graphicOverlay, result, trackInfo))
      }

      if (rapidApproachDetected) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAlertTimeMs >= ALERT_THROTTLE_INTERVAL_MS) {
          lastAlertTimeMs = currentTime
          if (PreferenceUtils.isVibrationEnabled(context)) {
            vibratorHelper.vibrateRapidApproach()
          }
          if (PreferenceUtils.isAudioEnabled(context)) {
            audioAlertHelper.playAlertSound(hazardScreenNormX)
          }
        }
      }

      geminiInferenceManager.onFrameProcessed(results, frameData, frameMetadata, rapidApproachDetected)
    } finally {
      grayMat?.release()
    }
  }

  private fun createGrayMatFromBuffer(data: ByteBuffer, width: Int, height: Int): Mat? {
    return try {
      val length = width * height
      if (length <= 0) return null
      val bytes = ByteArray(length)
      val duplicate = data.duplicate()
      duplicate.rewind()
      if (duplicate.remaining() >= length) {
        duplicate.get(bytes, 0, length)
        val mat = Mat(height, width, CvType.CV_8UC1)
        mat.put(0, 0, bytes)
        mat
      } else {
        null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error creating OpenCV gray Mat from frame buffer", e)
      null
    }
  }

  override fun onFailure(e: Exception) {
    Log.e(TAG, "Object detection failed!", e)
  }

  companion object {
    private const val TAG = "ObjectDetectorProcessor"
    private const val ALERT_THROTTLE_INTERVAL_MS = 1200L
  }
}
