package com.example.objectdetection

import android.graphics.Rect
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect as CvRect
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Result of corner/landmark anchor point tracking for a specific detected object.
 */
data class AnchorTrackingResult(
  val anchorScaleGrowth: Float,
  val validPointCount: Int,
  val confidence: Float,
  val trackedCorners: List<Point> = emptyList()
)

/**
 * Robust corner & landmark anchor point tracker using OpenCV's Shi-Tomasi feature detection
 * and Lucas-Kanade pyramidal optical flow.
 *
 * Measures geometric inter-anchor distance expansion across frames to provide an extremely
 * accurate, jitter-resistant scale growth rate for bump/collision warnings.
 */
class CornerAnchorTracker {

  private data class ObjectAnchorState(
    var prevCorners: List<Point>,
    var prevCentroid: Point,
    var prevMat: Mat?,
    var frameCountSinceRefresh: Int = 0
  )

  private val trackedObjectAnchors = mutableMapOf<Int, ObjectAnchorState>()

  /**
   * Processes a detected object bounding box against the current grayscale frame matrix
   * and computes relative anchor scale expansion rate.
   */
  fun trackObjectAnchors(
    objectId: Int,
    box: Rect,
    currGrayMat: Mat?,
    dtSec: Float
  ): AnchorTrackingResult {
    if (currGrayMat == null || currGrayMat.empty() || dtSec <= 0f) {
      return AnchorTrackingResult(0f, 0, 0f)
    }

    val imgWidth = currGrayMat.cols()
    val imgHeight = currGrayMat.rows()

    // Ensure ROI is valid within image boundaries
    val left = max(0, min(box.left, imgWidth - 1))
    val top = max(0, min(box.top, imgHeight - 1))
    val right = max(left + 1, min(box.right, imgWidth))
    val bottom = max(top + 1, min(box.bottom, imgHeight))
    val roiWidth = right - left
    val roiHeight = bottom - top

    if (roiWidth < 10 || roiHeight < 10) {
      return AnchorTrackingResult(0f, 0, 0f)
    }

    val state = trackedObjectAnchors[objectId]

    if (state == null || state.prevMat == null || state.prevCorners.size < MIN_POINTS_TO_TRACK || state.frameCountSinceRefresh >= REFRESH_INTERVAL_FRAMES) {
      // Detect new Shi-Tomasi corner anchor points in the object ROI
      val newCorners = detectCornersInRoi(currGrayMat, left, top, roiWidth, roiHeight)
      if (newCorners.size >= MIN_POINTS_TO_TRACK) {
        val centroid = calculateCentroid(newCorners)
        trackedObjectAnchors[objectId] = ObjectAnchorState(
          prevCorners = newCorners,
          prevCentroid = centroid,
          prevMat = currGrayMat.clone(),
          frameCountSinceRefresh = 0
        )
      } else {
        trackedObjectAnchors.remove(objectId)
      }
      return AnchorTrackingResult(0f, newCorners.size, if (newCorners.size >= MIN_POINTS_TO_TRACK) 0.5f else 0f, newCorners)
    }

    // Perform Pyramidal Lucas-Kanade Optical Flow from prevMat to currGrayMat
    val prevMat = state.prevMat!!
    val (trackedPrevPts, trackedCurrPts) = trackOpticalFlow(
      prevMat = prevMat,
      currMat = currGrayMat,
      prevCorners = state.prevCorners,
      roiBounds = CvRect(left, top, roiWidth, roiHeight)
    )

    val validCount = trackedCurrPts.size
    if (validCount < MIN_POINTS_TO_TRACK) {
      // Re-initialize features if points were lost
      val refreshedCorners = detectCornersInRoi(currGrayMat, left, top, roiWidth, roiHeight)
      if (refreshedCorners.size >= MIN_POINTS_TO_TRACK) {
        state.prevCorners = refreshedCorners
        state.prevCentroid = calculateCentroid(refreshedCorners)
        state.prevMat?.release()
        state.prevMat = currGrayMat.clone()
        state.frameCountSinceRefresh = 0
      } else {
        state.prevMat?.release()
        trackedObjectAnchors.remove(objectId)
      }
      return AnchorTrackingResult(0f, validCount, 0f)
    }

    // Calculate Median Inter-Anchor Distance Expansion
    val pairwiseScaleGrowthRatios = mutableListOf<Float>()
    val ptCount = trackedCurrPts.size

    for (i in 0 until ptCount) {
      for (j in i + 1 until ptCount) {
        val prevDist = distance(trackedPrevPts[i], trackedPrevPts[j])
        val currDist = distance(trackedCurrPts[i], trackedCurrPts[j])
        if (prevDist > 5.0) { // Ignore micro-distances
          val pairExpansionRate = ((currDist - prevDist) / prevDist) / dtSec
          pairwiseScaleGrowthRatios.add(pairExpansionRate.toFloat())
        }
      }
    }

    // Calculate Radial Expansion relative to centroid
    val currCentroid = calculateCentroid(trackedCurrPts)
    val radialExpansionRatios = mutableListOf<Float>()
    for (k in 0 until ptCount) {
      val prevRad = distance(trackedPrevPts[k], state.prevCentroid)
      val currRad = distance(trackedCurrPts[k], currCentroid)
      if (prevRad > 5.0) {
        val radialRate = ((currRad - prevRad) / prevRad) / dtSec
        radialExpansionRatios.add(radialRate.toFloat())
      }
    }

    val combinedRatios = pairwiseScaleGrowthRatios + radialExpansionRatios
    val medianScaleGrowth = if (combinedRatios.isNotEmpty()) {
      combinedRatios.sorted()[combinedRatios.size / 2]
    } else {
      0f
    }

    // Update object anchor state for next frame
    state.prevCorners = trackedCurrPts
    state.prevCentroid = currCentroid
    state.prevMat?.release()
    state.prevMat = currGrayMat.clone()
    state.frameCountSinceRefresh++

    val confidence = min(1.0f, validCount / 10.0f)
    return AnchorTrackingResult(
      anchorScaleGrowth = medianScaleGrowth,
      validPointCount = validCount,
      confidence = confidence,
      trackedCorners = trackedCurrPts
    )
  }

  /**
   * Cleans up stale tracked objects and OpenCV matrices.
   */
  fun cleanStaleAnchors(activeIds: Set<Int>) {
    val staleIds = trackedObjectAnchors.keys.filter { it !in activeIds }
    for (id in staleIds) {
      trackedObjectAnchors[id]?.prevMat?.release()
      trackedObjectAnchors.remove(id)
    }
  }

  /**
   * Releases all stored OpenCV frame matrices.
   */
  fun clear() {
    for ((_, state) in trackedObjectAnchors) {
      state.prevMat?.release()
    }
    trackedObjectAnchors.clear()
  }

  private fun detectCornersInRoi(
    grayMat: Mat,
    left: Int,
    top: Int,
    width: Int,
    height: Int
  ): List<Point> {
    return try {
      val roiMat = Mat(grayMat, CvRect(left, top, width, height))
      val cornersMatOfPoint = MatOfPoint()
      Imgproc.goodFeaturesToTrack(
        roiMat,
        cornersMatOfPoint,
        MAX_CORNERS_PER_OBJECT,
        CORNER_QUALITY_LEVEL,
        MIN_CORNER_DISTANCE
      )

      val points = cornersMatOfPoint.toList()
      cornersMatOfPoint.release()
      roiMat.release()

      // Translate corner coordinates back to frame space
      points.map { Point(it.x + left, it.y + top) }
    } catch (e: Exception) {
      Log.e(TAG, "Error running goodFeaturesToTrack", e)
      emptyList()
    }
  }

  private fun trackOpticalFlow(
    prevMat: Mat,
    currMat: Mat,
    prevCorners: List<Point>,
    roiBounds: CvRect
  ): Pair<List<Point>, List<Point>> {
    val prevPtsMat = MatOfPoint2f(*prevCorners.toTypedArray())
    val currPtsMat = MatOfPoint2f()
    val statusMat = MatOfByte()
    val errMat = MatOfFloat()

    try {
      Video.calcOpticalFlowPyrLK(
        prevMat,
        currMat,
        prevPtsMat,
        currPtsMat,
        statusMat,
        errMat
      )

      val statusList = statusMat.toList()
      val prevList = prevPtsMat.toList()
      val currList = currPtsMat.toList()

      val trackedPrev = mutableListOf<Point>()
      val trackedCurr = mutableListOf<Point>()

      for (i in statusList.indices) {
        if (statusList[i].toInt() == 1) {
          val pCurr = currList[i]
          // Keep points inside padded object ROI
          val margin = 20
          if (pCurr.x >= roiBounds.x - margin && pCurr.x <= roiBounds.x + roiBounds.width + margin &&
            pCurr.y >= roiBounds.y - margin && pCurr.y <= roiBounds.y + roiBounds.height + margin
          ) {
            trackedPrev.add(prevList[i])
            trackedCurr.add(pCurr)
          }
        }
      }

      return Pair(trackedPrev, trackedCurr)
    } catch (e: Exception) {
      Log.e(TAG, "Error running calcOpticalFlowPyrLK", e)
      return Pair(emptyList(), emptyList())
    } finally {
      prevPtsMat.release()
      currPtsMat.release()
      statusMat.release()
      errMat.release()
    }
  }

  private fun calculateCentroid(points: List<Point>): Point {
    if (points.isEmpty()) return Point(0.0, 0.0)
    var sumX = 0.0
    var sumY = 0.0
    for (p in points) {
      sumX += p.x
      sumY += p.y
    }
    return Point(sumX / points.size, sumY / points.size)
  }

  private fun distance(p1: Point, p2: Point): Double {
    val dx = p1.x - p2.x
    val dy = p1.y - p2.y
    return sqrt(dx * dx + dy * dy)
  }

  companion object {
    private const val TAG = "CornerAnchorTracker"
    private const val MAX_CORNERS_PER_OBJECT = 16
    private const val CORNER_QUALITY_LEVEL = 0.05
    private const val MIN_CORNER_DISTANCE = 10.0
    private const val MIN_POINTS_TO_TRACK = 3
    private const val REFRESH_INTERVAL_FRAMES = 12
  }
}
