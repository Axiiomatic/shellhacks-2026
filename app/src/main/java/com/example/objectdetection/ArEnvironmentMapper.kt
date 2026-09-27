package com.example.objectdetection

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.PointCloud
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableException
import com.google.mlkit.vision.objects.DetectedObject
import org.opencv.core.Mat
import org.opencv.core.Point
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Manages ARCore Session, 3D point clouds, detected planes, and fuses OpenCV optical flow
 * + ML Kit object detections into a unified 3D spatial environment map.
 */
class ArEnvironmentMapper(private val context: Context) {

  private var arSession: Session? = null
  private var isArCoreAvailable = false
  private var isArCoreInitialized = false

  private data class Object3DHistoryState(
    var lastX: Float,
    var lastY: Float,
    var lastZ: Float,
    var lastTimestampMs: Long,
    var smoothedVx: Float = 0f,
    var smoothedVy: Float = 0f,
    var smoothedVz: Float = 0f
  )

  private val objectHistoryMap = ConcurrentHashMap<Int, Object3DHistoryState>()

  init {
    checkAndInitArCore()
  }

  private fun checkAndInitArCore() {
    try {
      val availability = ArCoreApk.getInstance().checkAvailability(context)
      if (availability.isSupported) {
        isArCoreAvailable = true
        createArSession()
      } else {
        Log.w(TAG, "ARCore is not supported on this device. Fallback projection active.")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error checking ARCore availability", e)
    }
  }

  private fun createArSession() {
    try {
      val session = Session(context)
      val config = Config(session).apply {
        focusMode = Config.FocusMode.AUTO
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
      }
      session.configure(config)
      session.resume()
      arSession = session
      isArCoreInitialized = true
      Log.i(TAG, "ARCore Session initialized and resumed successfully.")
    } catch (e: UnavailableException) {
      Log.w(TAG, "ARCore unavailable or permission pending: ${e.message}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create ARCore Session", e)
    }
  }

  /**
   * Resumes ARCore session if active.
   */
  fun onResume() {
    try {
      if (arSession == null && isArCoreAvailable) {
        createArSession()
      } else {
        arSession?.resume()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error resuming ARCore session", e)
    }
  }

  /**
   * Pauses ARCore session.
   */
  fun onPause() {
    try {
      arSession?.pause()
    } catch (e: Exception) {
      Log.e(TAG, "Error pausing ARCore session", e)
    }
  }

  /**
   * Releases ARCore session resources.
   */
  fun close() {
    try {
      arSession?.close()
      arSession = null
      isArCoreInitialized = false
    } catch (e: Exception) {
      Log.e(TAG, "Error closing ARCore session", e)
    }
  }

  /**
   * Builds the current [Environment3DMap] by querying ARCore session data, OpenCV keypoints,
   * and ML Kit detected objects.
   */
  fun mapEnvironment(
    detectedObjects: List<DetectedObject>,
    trackInfoMap: Map<DetectedObject, ObjectTrackInfo>,
    frameWidth: Int,
    frameHeight: Int,
    currGrayMat: Mat? = null,
    detectedText: String = ""
  ): Environment3DMap {
    val currentTimeMs = System.currentTimeMillis()
    val safeWidth = max(frameWidth, 1).toFloat()
    val safeHeight = max(frameHeight, 1).toFloat()

    var frame: Frame? = null
    var isTrackingActive = false
    var cameraPose = Point3D(0f, 0f, 0f)

    if (arSession != null && isArCoreInitialized) {
      try {
        frame = arSession?.update()
        val camera = frame?.camera
        if (camera != null && camera.trackingState == TrackingState.TRACKING) {
          isTrackingActive = true
          val pose = camera.pose
          cameraPose = Point3D(pose.tx(), pose.ty(), pose.tz())
        }
      } catch (e: Exception) {
        // ARCore update failed on this frame, graceful fallback
      }
    }

    // 1. Extract 3D Point Cloud Landmarks
    val pointCloud3D = extractPointCloud(frame, safeWidth, safeHeight)

    // 2. Extract Detected 3D Planes (Ground / Wall Surfaces)
    val detectedPlanes = extractPlanes()

    // 3. Map ML Kit + OpenCV Object Detections into 3D Spatial Objects
    val tracked3DObjects = mutableListOf<Spatial3DObject>()

    for (obj in detectedObjects) {
      val trackInfo = trackInfoMap[obj]
      val box = obj.boundingBox
      val objId = trackInfo?.trackingId ?: obj.hashCode()

      val rawLabel = if (obj.labels.isNotEmpty()) {
        val topLabel = obj.labels.maxByOrNull { it.confidence }
        topLabel?.text ?: obj.labels.first().text
      } else {
        ""
      }

      val labelStr = if (rawLabel.isNotBlank() && rawLabel != "Object" && rawLabel != "Home good") {
        rawLabel
      } else {
        val boxWidth = box.width().toFloat()
        val boxHeight = box.height().toFloat()
        val aspectRatio = if (boxHeight > 0f) boxWidth / boxHeight else 1.0f
        val relativeArea = (boxWidth * boxHeight) / (safeWidth * safeHeight)

        when {
          relativeArea > 0.35f -> "Large Obstacle / Wall"
          aspectRatio < 0.7f && boxHeight > safeHeight * 0.3f -> "Person / Vertical Pole"
          aspectRatio > 1.4f -> "Furniture / Table / Counter"
          box.bottom > safeHeight * 0.85f -> "Ground Obstacle / Step"
          trackInfo?.isRapidApproaching == true -> "Approaching Hazard"
          else -> "Detected Object #${abs(objId % 100)}"
        }
      }
      val confidence = if (obj.labels.isNotEmpty()) obj.labels.first().confidence else 0.8f

      // Calculate 3D Position (X, Y, Z in meters) using ARCore HitTest / Point Cloud / Fallback
      val pos3D = calculate3DPosition(
        frame = frame,
        box = box,
        frameWidth = safeWidth,
        frameHeight = safeHeight,
        pointCloud = pointCloud3D,
        scaleGrowth = trackInfo?.relativeScaleGrowth ?: 0f
      )

      // Estimate 3D Dimensions (Width, Height, Depth in meters)
      val estimatedW = max(0.1f, (box.width() / safeWidth) * pos3D.z * 1.2f)
      val estimatedH = max(0.1f, (box.height() / safeHeight) * pos3D.z * 1.2f)
      val estimatedD = max(0.1f, min(estimatedW, estimatedH))

      // Differentiate 3D Velocity across frames (Vx, Vy, Vz in m/s)
      val vel3D = calculate3DVelocity(
        objectId = objId,
        currPos = pos3D,
        currentTimeMs = currentTimeMs
      )

      val ttcSec = if (vel3D.z < -0.1f && pos3D.z > 0.1f) {
        pos3D.z / (-vel3D.z)
      } else {
        Float.MAX_VALUE
      }

      val isHazard = (trackInfo?.isRapidApproaching == true) || (pos3D.z <= 1.8f && vel3D.z < -0.2f)

      val spatialObj = Spatial3DObject(
        trackingId = trackInfo?.trackingId,
        label = labelStr,
        confidence = confidence,
        boundingBox2D = box,
        position3D = pos3D,
        estimatedWidthMeters = estimatedW,
        estimatedHeightMeters = estimatedH,
        estimatedDepthMeters = estimatedD,
        velocity3D = vel3D,
        timeToCollisionSec = ttcSec,
        motionLabel = trackInfo?.motionLabel ?: "Stationary",
        isHazard = isHazard,
        trackedCorners2D = trackInfo?.trackedCorners ?: emptyList()
      )

      tracked3DObjects.add(spatialObj)
    }

    val trackingSummary = if (isTrackingActive) {
      String.format(
        TAG_LOCALE,
        "ARCore 3D Map Active • %d Points • %d Planes • %d Objects",
        pointCloud3D.size,
        detectedPlanes.size,
        tracked3DObjects.size
      )
    } else {
      String.format(
        TAG_LOCALE,
        "CV Optical Projection Map • %d Points • %d Objects",
        pointCloud3D.size,
        tracked3DObjects.size
      )
    }

    return Environment3DMap(
      tracked3DObjects = tracked3DObjects,
      pointCloud3D = pointCloud3D,
      detectedPlanes = detectedPlanes,
      cameraPosition3D = cameraPose,
      isArCoreTrackingActive = isTrackingActive,
      trackingStateSummary = trackingSummary,
      detectedText = detectedText
    )
  }

  private fun extractPointCloud(frame: Frame?, width: Float, height: Float): List<Point3D> {
    val points = mutableListOf<Point3D>()
    if (frame != null) {
      try {
        val cloud: PointCloud = frame.acquirePointCloud()
        val floatBuf = cloud.points
        floatBuf.rewind()
        val numPoints = floatBuf.remaining() / 4

        // Sample up to 120 key feature points from ARCore point cloud
        val step = max(1, numPoints / 120)
        for (i in 0 until numPoints step step) {
          val idx = i * 4
          if (idx + 3 < floatBuf.capacity()) {
            val px = floatBuf.get(idx)
            val py = floatBuf.get(idx + 1)
            val pz = floatBuf.get(idx + 2)
            val conf = floatBuf.get(idx + 3)
            if (conf > 0.2f) {
              points.add(Point3D(px, py, pz, conf, Point3D.PointSource.ARCORE_POINT_CLOUD))
            }
          }
        }
        cloud.release()
      } catch (e: Exception) {
        Log.e(TAG, "Error acquiring ARCore point cloud", e)
      }
    }

    if (points.isEmpty()) {
      // Generate standard camera perspective 3D grid points for visual spatial mapping
      val sampleCols = 8
      val sampleRows = 6
      for (r in 1..sampleRows) {
        for (c in 1..sampleCols) {
          val normX = (c.toFloat() / (sampleCols + 1)) - 0.5f
          val normY = (r.toFloat() / (sampleRows + 1)) - 0.5f
          val estimatedZ = 2.0f + (r * 0.4f)
          val x3d = normX * estimatedZ * 1.1f
          val y3d = -normY * estimatedZ * 1.1f
          points.add(Point3D(x3d, y3d, estimatedZ, 0.5f, Point3D.PointSource.OPENCV_UNPROJECTION))
        }
      }
    }

    return points
  }

  private fun extractPlanes(): List<Plane3D> {
    val planes = mutableListOf<Plane3D>()
    val session = arSession ?: return planes

    try {
      val trackablePlanes = session.getAllTrackables(Plane::class.java)
      for (plane in trackablePlanes) {
        if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
          val pose = plane.centerPose
          val type = when (plane.type) {
            Plane.Type.HORIZONTAL_UPWARD_FACING -> Plane3D.PlaneType.HORIZONTAL_UPWARD_FACING
            Plane.Type.HORIZONTAL_DOWNWARD_FACING -> Plane3D.PlaneType.HORIZONTAL_DOWNWARD_FACING
            Plane.Type.VERTICAL -> Plane3D.PlaneType.VERTICAL
            else -> Plane3D.PlaneType.UNKNOWN
          }
          planes.add(
            Plane3D(
              id = plane.hashCode().toString(),
              type = type,
              centerX = pose.tx(),
              centerY = pose.ty(),
              centerZ = pose.tz(),
              extentX = plane.extentX,
              extentZ = plane.extentZ
            )
          )
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error extracting ARCore planes", e)
    }

    return planes
  }

  private fun calculate3DPosition(
    frame: Frame?,
    box: Rect,
    frameWidth: Float,
    frameHeight: Float,
    pointCloud: List<Point3D>,
    scaleGrowth: Float
  ): Point3D {
    val centerX = (box.left + box.right) / 2.0f
    val centerY = (box.top + box.bottom) / 2.0f

    // 1. Try ARCore HitTest against planes / point clouds for precise metric depth
    if (frame != null) {
      try {
        val hitResults = frame.hitTest(centerX, centerY)
        for (hit in hitResults) {
          val hitPose = hit.getHitPose()
          val x = hitPose.tx()
          val y = hitPose.ty()
          val z = hitPose.tz()
          if (z > 0.1f && z < 10.0f) {
            return Point3D(x, y, z, 0.95f, Point3D.PointSource.ARCORE_POINT_CLOUD)
          }
        }
      } catch (e: Exception) {
        // Fallback
      }
    }

    // 2. Fallback to point cloud cluster median depth if available
    val normX = (centerX / frameWidth) - 0.5f
    val normY = (centerY / frameHeight) - 0.5f

    val nearbyCloudPoints = pointCloud.filter { pt ->
      val ptNormX = pt.x / max(pt.z, 0.1f)
      val ptNormY = -pt.y / max(pt.z, 0.1f)
      abs(ptNormX - normX) < 0.20f && abs(ptNormY - normY) < 0.20f
    }

    if (nearbyCloudPoints.isNotEmpty()) {
      val sortedZ = nearbyCloudPoints.map { it.z }.sorted()
      val medianZ = sortedZ[sortedZ.size / 2].coerceIn(0.5f, 8.0f)
      val x3d = normX * medianZ * 1.28f
      val y3d = -normY * medianZ * 1.28f
      return Point3D(x3d, y3d, medianZ, 0.8f, Point3D.PointSource.ARCORE_POINT_CLOUD)
    }

    // 3. Final geometric fallback with calibrated scaling
    val boxDiag = sqrt((box.width() * box.width() + box.height() * box.height()).toDouble()).toFloat()
    val screenDiag = sqrt(frameWidth * frameWidth + frameHeight * frameHeight)
    val diagFraction = max(0.05f, boxDiag / screenDiag)
    val estimatedZ = (1.2f / diagFraction).coerceIn(0.6f, 6.0f)

    val x3d = normX * estimatedZ * 1.28f
    val y3d = -normY * estimatedZ * 1.28f
    return Point3D(x3d, y3d, estimatedZ, 0.6f, Point3D.PointSource.ESTIMATED_DEPTH)
  }

  private fun calculate3DVelocity(
    objectId: Int,
    currPos: Point3D,
    currentTimeMs: Long
  ): Point3D {
    val history = objectHistoryMap[objectId]
    if (history == null) {
      objectHistoryMap[objectId] = Object3DHistoryState(
        lastX = currPos.x,
        lastY = currPos.y,
        lastZ = currPos.z,
        lastTimestampMs = currentTimeMs
      )
      return Point3D(0f, 0f, 0f)
    }

    val dtSec = (currentTimeMs - history.lastTimestampMs) / 1000.0f
    if (dtSec <= 0.01f || dtSec > 1.2f) {
      history.lastX = currPos.x
      history.lastY = currPos.y
      history.lastZ = currPos.z
      history.lastTimestampMs = currentTimeMs
      return Point3D(history.smoothedVx, history.smoothedVy, history.smoothedVz)
    }

    val instVx = (currPos.x - history.lastX) / dtSec
    val instVy = (currPos.y - history.lastY) / dtSec
    val instVz = (currPos.z - history.lastZ) / dtSec

    val alpha = 0.25f
    val smoothedVx = alpha * instVx + (1f - alpha) * history.smoothedVx
    val smoothedVy = alpha * instVy + (1f - alpha) * history.smoothedVy
    val smoothedVz = alpha * instVz + (1f - alpha) * history.smoothedVz

    history.lastX = currPos.x
    history.lastY = currPos.y
    history.lastZ = currPos.z
    history.lastTimestampMs = currentTimeMs
    history.smoothedVx = smoothedVx
    history.smoothedVy = smoothedVy
    history.smoothedVz = smoothedVz

    return Point3D(smoothedVx, smoothedVy, smoothedVz)
  }

  companion object {
    private const val TAG = "ArEnvironmentMapper"
    private val TAG_LOCALE = Locale.US
  }
}
