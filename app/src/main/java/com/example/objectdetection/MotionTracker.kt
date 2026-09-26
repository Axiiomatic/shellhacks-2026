package com.example.objectdetection

import com.google.mlkit.vision.objects.DetectedObject
import org.opencv.core.Mat
import org.opencv.core.Point
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
  val trackedCorners: List<Point> = emptyList(),
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
 * Tracker that calculates real-time normalized relative motion for detected objects.
 * Prioritizes corner & landmark anchor point tracking via OpenCV optical flow for bump/collision warnings.
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
  private val cornerAnchorTracker = CornerAnchorTracker()
  private var fallbackIdCounter = -1

  fun detectFrameKeyPoints(currGrayMat: Mat?): List<Point> {
    return cornerAnchorTracker.detectFrameKeyPoints(currGrayMat)
  }

  // Exponential Moving Average (EMA) factors for smoothing motion & scale jitter
  private val motionAlpha = 0.25f
  private val scaleAlpha = 0.20f

  // Minimum normalized relative speed to classify as moving
  private val relativeMovementThreshold = 0.08f

  /**
   * Processes a frame of detected objects and computes relative motion tracking info.
   */
  fun processFrame(
    detectedObjects: List<DetectedObject>,
    frameWidth: Int,
    frameHeight: Int,
    currGrayMat: Mat? = null
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

        // Track object motion from optical-flow anchor points when available.
        val anchorResult = cornerAnchorTracker.trackObjectAnchors(
          objectId = id,
          box = box,
          currGrayMat = currGrayMat,
          dtSec = dtSec
        )
        val hasReliableAnchors = anchorResult.validPointCount >= 3 && anchorResult.confidence > 0.2f

        val dx = if (hasReliableAnchors) anchorResult.anchorDisplacementX else 0f
        val dy = if (hasReliableAnchors) anchorResult.anchorDisplacementY else 0f
        val distPx = sqrt(dx * dx + dy * dy)

        // Normalize displacement relative to frame diagonal size
        val normalizedDist = distPx / frameDiagonalPx
        val instRelativeSpeed = normalizedDist / dtSec

        // Bounding-box growth is intentionally not used as a depth signal.
        val effectiveScaleGrowth = if (hasReliableAnchors) anchorResult.anchorScaleGrowth else 0f

        // Apply EMA filter
        val smoothedDx = motionAlpha * dx + (1f - motionAlpha) * state.smoothedDx
        val smoothedDy = motionAlpha * dy + (1f - motionAlpha) * state.smoothedDy
        val smoothedRelativeSpeed = motionAlpha * instRelativeSpeed + (1f - motionAlpha) * state.smoothedRelativeSpeed
        val smoothedScaleGrowth = scaleAlpha * effectiveScaleGrowth + (1f - scaleAlpha) * state.smoothedScaleGrowth

        state.smoothedDx = smoothedDx
        state.smoothedDy = smoothedDy
        state.smoothedRelativeSpeed = smoothedRelativeSpeed
        state.smoothedScaleGrowth = smoothedScaleGrowth

        // 1. Scale Expansion Rate & Time-To-Collision (TTC)
        val isRapidScaleGrowth = smoothedScaleGrowth >= RAPID_APPROACH_THRESHOLD
        val estimatedTtc = if (smoothedScaleGrowth > 0.05f) 1.0f / smoothedScaleGrowth else Float.MAX_VALUE
        val isImminentTtc = estimatedTtc <= MAX_TTC_SECONDS

        // 2. Directional Collision Path (supports head-on AND angled approaches)
        // Object must extend into the forward path corridor [0.20, 0.80]
        val normLeft = box.left / safeWidth
        val normRight = box.right / safeWidth
        val isInCollisionPath = normLeft <= PATH_CORRIDOR_X_MAX && normRight >= PATH_CORRIDOR_X_MIN

        // 3. Expansion-to-Motion Ratio
        // Permits angled approaches (where both expansion and lateral movement occur),
        // but filters out pure camera sweeps / lateral pans across the room (where lateral speed is high, scale expansion is tiny)
        val expansionToMotionRatio = if (smoothedRelativeSpeed > 0.05f) {
          smoothedScaleGrowth / smoothedRelativeSpeed
        } else {
          10.0f
        }
        val isApproachMotion = expansionToMotionRatio >= MIN_EXPANSION_TO_MOTION_RATIO || smoothedRelativeSpeed < 0.35f

        val isExpandingRapidly = isRapidScaleGrowth &&
            isImminentTtc &&
            isInCollisionPath &&
            isApproachMotion

        if (isExpandingRapidly) {
          state.consecutiveApproachCount = minOf(15, state.consecutiveApproachCount + 1)
        } else {
          // Rapid decay on non-expanding jitter frames
          state.consecutiveApproachCount = maxOf(0, state.consecutiveApproachCount - 2)
        }

        // Require sustained continuous expansion over multiple frames
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
          isMoving = isMoving,
          trackedCorners = anchorResult.trackedCorners
        )
      }
    }

    cleanStaleTrackedObjects(currentFrameTrackingIds, currentTimeMs)
    cornerAnchorTracker.cleanStaleAnchors(currentFrameTrackingIds)
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
    const val RAPID_APPROACH_THRESHOLD = 0.42f // 42%/s scale growth rate required for imminent bump
    const val MAX_TTC_SECONDS = 2.0f // Estimated Time-To-Collision must be <= 2.0 seconds
    const val PATH_CORRIDOR_X_MIN = 0.20f // Collision path corridor starts at 20% width
    const val PATH_CORRIDOR_X_MAX = 0.80f // Collision path corridor ends at 80% width
    const val MIN_EXPANSION_TO_MOTION_RATIO = 0.35f // Minimum expansion-to-lateral-speed ratio
    const val SUSTAINED_FRAMES_REQUIRED = 5 // Requires at least 5 consecutive expanding frames
  }
}
