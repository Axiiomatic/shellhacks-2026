package com.example.objectdetection

import com.google.mlkit.vision.objects.DetectedObject
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Data class holding normalized relative motion tracking results for a detected object.
 */
data class ObjectTrackInfo(
  val trackingId: Int?,
  val relativeSpeed: Float,
  val relativeScaleGrowth: Float = 0f,
  val isRapidApproaching: Boolean = false,
  val motionDirection: MotionDirection,
  val motionLabel: String,
  val displacementX: Float,
  val displacementY: Float,
  val isMoving: Boolean,
)

/**
 * Enum representing possible motion directions.
 */
enum class MotionDirection {
  STATIONARY,
  APPROACHING,
  RECEDING,
  LEFT,
  RIGHT,
  UP,
  DOWN,
  UP_LEFT,
  UP_RIGHT,
  DOWN_LEFT,
  DOWN_RIGHT,
}

/**
 * Tracker that calculates real-time normalized relative motion for detected objects
 * and measures sustained scale growth (imminent bump/collision) based on bounding box changes.
 */
class MotionTracker {

  private data class TrackedObjectState(
    val trackingId: Int?,
    var lastCenterX: Float,
    var lastCenterY: Float,
    var lastWidth: Float,
    var lastHeight: Float,
    var lastTimestampMs: Long,
    var smoothedRelativeSpeed: Float,
    var smoothedScaleGrowth: Float,
    var smoothedDx: Float,
    var smoothedDy: Float,
    var consecutiveApproachCount: Int = 0,
    var lastMotionDirection: MotionDirection = MotionDirection.STATIONARY,
  )

  private val trackedObjects = mutableMapOf<Int, TrackedObjectState>()
  private var fallbackIdCounter = -1

  // Exponential Moving Average (EMA) factors for smoothing motion & scale jitter
  private val motionAlpha = 0.25f
  private val scaleAlpha = 0.15f

  // Minimum normalized relative speed to classify as moving
  private val relativeMovementThreshold = 0.08f

  /**
   * Processes a frame of detected objects and computes relative motion tracking info.
   */
  fun processFrame(
    detectedObjects: List<DetectedObject>,
    frameWidth: Int,
    frameHeight: Int,
  ): Map<DetectedObject, ObjectTrackInfo> {
    val currentTimeMs = System.currentTimeMillis()
    val results = IdentityHashMap<DetectedObject, ObjectTrackInfo>()

    val safeWidth = max(frameWidth, 1).toFloat()
    val safeHeight = max(frameHeight, 1).toFloat()
    val frameDiagonalPx = sqrt(safeWidth * safeWidth + safeHeight * safeHeight)

    val currentFrameTrackingIds = mutableSetOf<Int>()

    for (obj in detectedObjects) {
      val box = obj.boundingBox
      val boxWidth = max((box.right - box.left).toFloat(), 1.0f)
      val boxHeight = max((box.bottom - box.top).toFloat(), 1.0f)
      val centerX = (box.left + box.right) / 2.0f
      val centerY = (box.top + box.bottom) / 2.0f

      // Identify object using trackingId or spatial match
      val id = obj.trackingId ?: findMatchingTrackedObjectId(centerX, centerY, boxWidth, boxHeight)
      currentFrameTrackingIds.add(id)

      val state = trackedObjects[id]

      if (state == null) {
        // First frame tracking this object
        val newState = TrackedObjectState(
          trackingId = obj.trackingId,
          lastCenterX = centerX,
          lastCenterY = centerY,
          lastWidth = boxWidth,
          lastHeight = boxHeight,
          lastTimestampMs = currentTimeMs,
          smoothedRelativeSpeed = 0f,
          smoothedScaleGrowth = 0f,
          smoothedDx = 0f,
          smoothedDy = 0f,
          consecutiveApproachCount = 0
        )
        trackedObjects[id] = newState

        results[obj] = ObjectTrackInfo(
          trackingId = obj.trackingId,
          relativeSpeed = 0f,
          relativeScaleGrowth = 0f,
          isRapidApproaching = false,
          motionDirection = MotionDirection.STATIONARY,
          motionLabel = "Stationary",
          displacementX = 0f,
          displacementY = 0f,
          isMoving = false
        )
      } else {
        val dtSec = (currentTimeMs - state.lastTimestampMs) / 1000.0f

        if (dtSec <= 0f || dtSec > 1.0f) {
          // Frame drop, re-anchor timestamp
          state.lastTimestampMs = currentTimeMs
          state.lastCenterX = centerX
          state.lastCenterY = centerY
          state.lastWidth = boxWidth
          state.lastHeight = boxHeight
          results[obj] = ObjectTrackInfo(
            trackingId = obj.trackingId,
            relativeSpeed = 0f,
            relativeScaleGrowth = 0f,
            isRapidApproaching = false,
            motionDirection = MotionDirection.STATIONARY,
            motionLabel = "Stationary",
            displacementX = 0f,
            displacementY = 0f,
            isMoving = false
          )
          continue
        }

        // Calculate Displacement in frame pixel space
        val dx = centerX - state.lastCenterX
        val dy = centerY - state.lastCenterY
        val distPx = sqrt(dx * dx + dy * dy)

        // Normalize displacement relative to frame diagonal size
        val normalizedDist = distPx / frameDiagonalPx
        val instRelativeSpeed = normalizedDist / dtSec

        // Calculate Bounding Box Diagonal Scale Growth Rate & Screen Fraction
        val currentDiagonal = sqrt(boxWidth * boxWidth + boxHeight * boxHeight)
        val prevDiagonal = max(sqrt(state.lastWidth * state.lastWidth + state.lastHeight * state.lastHeight), 1.0f)
        val instScaleGrowth = ((currentDiagonal - prevDiagonal) / prevDiagonal) / dtSec
        val screenFraction = currentDiagonal / frameDiagonalPx

        // Apply EMA filter
        val smoothedDx = motionAlpha * dx + (1f - motionAlpha) * state.smoothedDx
        val smoothedDy = motionAlpha * dy + (1f - motionAlpha) * state.smoothedDy
        val smoothedRelativeSpeed = motionAlpha * instRelativeSpeed + (1f - motionAlpha) * state.smoothedRelativeSpeed
        val smoothedScaleGrowth = scaleAlpha * instScaleGrowth + (1f - scaleAlpha) * state.smoothedScaleGrowth

        state.smoothedDx = smoothedDx
        state.smoothedDy = smoothedDy
        state.smoothedRelativeSpeed = smoothedRelativeSpeed
        state.smoothedScaleGrowth = smoothedScaleGrowth

        // Sustained approach accumulator:
        // Object must be expanding rapidly (>= 30%/s), occupy a major portion of the frame (>= 22% frame diagonal),
        // and not be a fast lateral pan across the screen.
        val isExpandingRapidly = smoothedScaleGrowth >= RAPID_APPROACH_THRESHOLD &&
            screenFraction >= MIN_SCREEN_FRACTION_FOR_COLLISION &&
            smoothedRelativeSpeed < MAX_LATERAL_PAN_SPEED

        if (isExpandingRapidly) {
          state.consecutiveApproachCount = minOf(15, state.consecutiveApproachCount + 1)
        } else {
          // Rapid decay on non-expanding jitter frames
          state.consecutiveApproachCount = maxOf(0, state.consecutiveApproachCount - 2)
        }

        // Require sustained continuous expansion over multiple frames (~350-450ms)
        val isRapidApproaching = state.consecutiveApproachCount >= SUSTAINED_FRAMES_REQUIRED

        val isMoving = smoothedRelativeSpeed >= relativeMovementThreshold || abs(smoothedScaleGrowth) > 0.10f

        val (motionDirection, motionLabel) = determineMotionDirection(
          isMoving = isMoving,
          isRapidApproaching = isRapidApproaching,
          dx = smoothedDx,
          dy = smoothedDy,
          relativeSpeed = smoothedRelativeSpeed,
          relativeScaleGrowth = smoothedScaleGrowth
        )

        // Update object state
        state.lastCenterX = centerX
        state.lastCenterY = centerY
        state.lastWidth = boxWidth
        state.lastHeight = boxHeight
        state.lastTimestampMs = currentTimeMs
        state.lastMotionDirection = motionDirection

        results[obj] = ObjectTrackInfo(
          trackingId = obj.trackingId,
          relativeSpeed = smoothedRelativeSpeed,
          relativeScaleGrowth = smoothedScaleGrowth,
          isRapidApproaching = isRapidApproaching,
          motionDirection = motionDirection,
          motionLabel = motionLabel,
          displacementX = smoothedDx,
          displacementY = smoothedDy,
          isMoving = isMoving
        )
      }
    }

    cleanStaleTrackedObjects(currentFrameTrackingIds, currentTimeMs)
    return results
  }

  private fun findMatchingTrackedObjectId(centerX: Float, centerY: Float, width: Float, height: Float): Int {
    var minDistanceSq = Float.MAX_VALUE
    var matchedId: Int? = null

    for ((id, state) in trackedObjects) {
      if (state.trackingId == null) {
        val dx = centerX - state.lastCenterX
        val dy = centerY - state.lastCenterY
        val distSq = dx * dx + dy * dy
        val maxAllowedDistSq = (max(width, height) * 1.5f).let { it * it }

        if (distSq < minDistanceSq && distSq < maxAllowedDistSq) {
          minDistanceSq = distSq
          matchedId = id
        }
      }
    }

    return matchedId ?: run {
      fallbackIdCounter--
      fallbackIdCounter
    }
  }

  private fun determineMotionDirection(
    isMoving: Boolean,
    isRapidApproaching: Boolean,
    dx: Float,
    dy: Float,
    relativeSpeed: Float,
    relativeScaleGrowth: Float
  ): Pair<MotionDirection, String> {
    if (!isMoving && !isRapidApproaching) {
      return Pair(MotionDirection.STATIONARY, "Stationary")
    }

    val relStr = "Rel: %.2f".format(relativeSpeed)

    if (isRapidApproaching) {
      val pctStr = "+%.0f%%/s".format(relativeScaleGrowth * 100f)
      return Pair(MotionDirection.APPROACHING, "⚠️ BUMP WARNING ($pctStr)")
    } else if (relativeScaleGrowth > 0.10f) {
      val pctStr = "+%.0f%%/s".format(relativeScaleGrowth * 100f)
      return Pair(MotionDirection.APPROACHING, "Getting Closer ($pctStr)")
    } else if (relativeScaleGrowth < -0.10f) {
      val pctStr = "-%.0f%%/s".format(abs(relativeScaleGrowth) * 100f)
      return Pair(MotionDirection.RECEDING, "Getting Farther ($pctStr)")
    }

    // Direction angle in degrees (screen space: x right, y down)
    val angleRad = atan2(-dy.toDouble(), dx.toDouble()) // negate dy so y points up
    var angleDeg = Math.toDegrees(angleRad)
    if (angleDeg < 0) angleDeg += 360.0

    val (dir, labelStr) = when {
      angleDeg in 22.5..67.5 -> Pair(MotionDirection.UP_RIGHT, "Up-Right")
      angleDeg in 67.5..112.5 -> Pair(MotionDirection.UP, "Moving Up")
      angleDeg in 112.5..157.5 -> Pair(MotionDirection.UP_LEFT, "Up-Left")
      angleDeg in 157.5..202.5 -> Pair(MotionDirection.LEFT, "Moving Left")
      angleDeg in 202.5..247.5 -> Pair(MotionDirection.DOWN_LEFT, "Down-Left")
      angleDeg in 247.5..292.5 -> Pair(MotionDirection.DOWN, "Moving Down")
      angleDeg in 292.5..337.5 -> Pair(MotionDirection.DOWN_RIGHT, "Down-Right")
      else -> Pair(MotionDirection.RIGHT, "Moving Right")
    }

    return Pair(dir, "%s ($relStr)".format(labelStr))
  }

  private fun cleanStaleTrackedObjects(currentFrameTrackingIds: Set<Int>, currentTimeMs: Long) {
    val staleIds = trackedObjects.filter { (id, state) ->
      id !in currentFrameTrackingIds && (currentTimeMs - state.lastTimestampMs > STALE_THRESHOLD_MS)
    }.keys

    for (id in staleIds) {
      trackedObjects.remove(id)
    }
  }

  companion object {
    private const val STALE_THRESHOLD_MS = 1200L
    const val RAPID_APPROACH_THRESHOLD = 0.30f // 30%/s scale growth rate
    const val MIN_SCREEN_FRACTION_FOR_COLLISION = 0.22f // Object must occupy >= 22% of frame diagonal
    const val MAX_LATERAL_PAN_SPEED = 0.70f // Filters out fast lateral camera sweeps
    const val SUSTAINED_FRAMES_REQUIRED = 5 // Requires at least 5 consecutive expanding frames
  }
}
