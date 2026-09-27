package com.example.objectdetection

import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.video.Video
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Estimates camera movement from the dominant motion of features in the surroundings. */
class CameraMotionTracker {

  private var previousGrayMat: Mat? = null
  private var smoothedMotion = 0f

  fun processFrame(grayMat: Mat?): CameraMotionState {
    if (grayMat == null || grayMat.empty()) {
      return CameraMotionState.UNKNOWN
    }

    val previous = previousGrayMat
    if (previous == null || previous.empty() || previous.size() != grayMat.size()) {
      replacePrevious(grayMat)
      return CameraMotionState.UNKNOWN
    }

    return try {
      val previousPoints = detectFeatures(previous)
      if (previousPoints.size < MIN_FEATURES) {
        replacePrevious(grayMat)
        return CameraMotionState.UNKNOWN
      }

      val currentPoints = MatOfPoint2f()
      val status = MatOfByte()
      val errors = MatOfFloat()
      Video.calcOpticalFlowPyrLK(
        previous,
        grayMat,
        MatOfPoint2f(*previousPoints.toTypedArray()),
        currentPoints,
        status,
        errors
      )

      val previousList = previousPoints
      val currentList = currentPoints.toList()
      val statusList = status.toList()
      val displacements = mutableListOf<Pair<Float, Float>>()
      for (index in currentList.indices) {
        if (index >= statusList.size || statusList[index].toInt() == 0) continue
        val previousPoint = previousList[index]
        val currentPoint = currentList[index]
        val dx = (currentPoint.x - previousPoint.x).toFloat()
        val dy = (currentPoint.y - previousPoint.y).toFloat()
        if (sqrt(dx * dx + dy * dy) <= MAX_TRACK_DISTANCE_PX) {
          displacements.add(dx to dy)
        }
      }

      val state = if (displacements.size < MIN_FEATURES) {
        CameraMotionState.UNKNOWN
      } else {
        val medianX = median(displacements.map { it.first })
        val medianY = median(displacements.map { it.second })
        val diagonal = max(1.0, sqrt(
          grayMat.cols().toDouble() * grayMat.cols() +
            grayMat.rows().toDouble() * grayMat.rows()
        )).toFloat()
        val normalizedMotion = sqrt(medianX * medianX + medianY * medianY) / diagonal
        smoothedMotion = MOTION_ALPHA * normalizedMotion + (1f - MOTION_ALPHA) * smoothedMotion
        CameraMotionState.from(smoothedMotion, medianX, medianY)
      }

      replacePrevious(grayMat)
      currentPoints.release()
      status.release()
      errors.release()
      state
    } catch (e: Exception) {
      Log.e(TAG, "Error estimating camera motion", e)
      replacePrevious(grayMat)
      CameraMotionState.UNKNOWN
    }
  }

  fun clear() {
    previousGrayMat?.release()
    previousGrayMat = null
    smoothedMotion = 0f
  }

  private fun detectFeatures(grayMat: Mat): List<org.opencv.core.Point> {
    val corners = MatOfPoint()
    Imgproc.goodFeaturesToTrack(
      grayMat,
      corners,
      MAX_FEATURES,
      FEATURE_QUALITY,
      MIN_FEATURE_DISTANCE_PX
    )
    val points = corners.toList()
    corners.release()
    return points
  }

  private fun replacePrevious(grayMat: Mat) {
    previousGrayMat?.release()
    previousGrayMat = grayMat.clone()
  }

  private fun median(values: List<Float>): Float {
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
  }

  companion object {
    private const val TAG = "CameraMotionTracker"
    private const val MAX_FEATURES = 120
    private const val MIN_FEATURES = 8
    private const val FEATURE_QUALITY = 0.01
    private const val MIN_FEATURE_DISTANCE_PX = 8.0
    private const val MAX_TRACK_DISTANCE_PX = 150.0
    private const val MOTION_ALPHA = 0.25f
  }
}

data class CameraMotionState(
  val isMoving: Boolean,
  val label: String,
  val normalizedMagnitude: Float,
  val displacementX: Float,
  val displacementY: Float
) {
  val promptDescription: String
    get() = if (isMoving) "Camera is moving relative to surroundings ($label)." else "Camera is stationary relative to surroundings."

  companion object {
    val UNKNOWN = CameraMotionState(false, "unknown", 0f, 0f, 0f)
    private const val MOVEMENT_THRESHOLD = 0.012f

    fun from(normalizedMagnitude: Float, displacementX: Float, displacementY: Float): CameraMotionState {
      val isMoving = normalizedMagnitude >= MOVEMENT_THRESHOLD
      val label = when {
        !isMoving -> "stationary"
        abs(displacementX) >= abs(displacementY) && displacementX > 0f -> "moving right"
        abs(displacementX) >= abs(displacementY) -> "moving left"
        displacementY > 0f -> "moving down"
        else -> "moving up"
      }
      return CameraMotionState(isMoving, label, normalizedMagnitude, displacementX, displacementY)
    }
  }
}
