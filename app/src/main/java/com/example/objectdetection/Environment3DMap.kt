package com.example.objectdetection

import android.graphics.Rect
import org.opencv.core.Point
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Represents a single point in 3D spatial world coordinates (in meters).
 */
data class Point3D(
  val x: Float, // X-axis in meters (right +, left -)
  val y: Float, // Y-axis in meters (up +, down -)
  val z: Float, // Z-axis in meters (depth forward +, behind -)
  val confidence: Float = 1.0f,
  val source: PointSource = PointSource.ARCORE_POINT_CLOUD
) {
  val distanceMeters: Float = sqrt(x * x + y * y + z * z)

  enum class PointSource {
    ARCORE_POINT_CLOUD,
    OPENCV_UNPROJECTION,
    ESTIMATED_DEPTH
  }
}

/**
 * Represents a detected 3D plane surface in the environment (floor, table, wall).
 */
data class Plane3D(
  val id: String,
  val type: PlaneType,
  val centerX: Float,
  val centerY: Float,
  val centerZ: Float,
  val extentX: Float,
  val extentZ: Float
) {
  val distanceMeters: Float = sqrt(centerX * centerX + centerY * centerY + centerZ * centerZ)

  enum class PlaneType {
    HORIZONTAL_UPWARD_FACING,   // e.g. Floor
    HORIZONTAL_DOWNWARD_FACING, // e.g. Ceiling
    VERTICAL,                   // e.g. Wall
    UNKNOWN
  }
}

/**
 * Fused 3D spatial object anchor combining ML Kit detection, OpenCV optical flow tracking,
 * and ARCore 3D spatial depth coordinates.
 */
data class Spatial3DObject(
  val trackingId: Int?,
  val label: String,
  val confidence: Float,
  val boundingBox2D: Rect,
  val position3D: Point3D, // Centroid position (X, Y, Z in meters relative to camera)
  val estimatedWidthMeters: Float,
  val estimatedHeightMeters: Float,
  val estimatedDepthMeters: Float,
  val velocity3D: Point3D, // Velocity vector (Vx, Vy, Vz in meters/second)
  val timeToCollisionSec: Float, // Estimated Time To Collision in seconds
  val motionLabel: String,
  val isHazard: Boolean,
  val trackedCorners2D: List<Point> = emptyList()
) {
  val distanceMeters: Float = position3D.distanceMeters

  /**
   * Returns a short, natural, safety-focused description of the obstacle.
   */
  fun toSpatialPromptString(): String {
    val proximityStr = when {
      position3D.z < 1.5f -> "very close"
      position3D.z < 3.0f -> "nearby"
      else -> "ahead"
    }

    val sideStr = when {
      position3D.x < -0.35f -> "on your left"
      position3D.x > 0.35f -> "on your right"
      else -> "straight ahead"
    }

    val hazardNotice = if (isHazard) " - Hazard!" else ""

    return String.format(
      Locale.US,
      "%s %s %s%s",
      label,
      sideStr,
      proximityStr,
      hazardNotice
    )
  }
}

/**
 * Aggregated 3D spatial environment map built from ARCore, OpenCV, and Google ML data fusion.
 */
data class Environment3DMap(
  val tracked3DObjects: List<Spatial3DObject> = emptyList(),
  val pointCloud3D: List<Point3D> = emptyList(),
  val detectedPlanes: List<Plane3D> = emptyList(),
  val cameraPosition3D: Point3D = Point3D(0f, 0f, 0f),
  val isArCoreTrackingActive: Boolean = false,
  val trackingStateSummary: String = "Initializing 3D Spatial Map...",
  val detectedText: String = ""
) {
  /**
   * Generates a detailed directional scene description and pathfinding analysis formatted specifically for Gemini AI inference.
   */
  fun buildGemini3DSceneContext(): String {
    val sb = StringBuilder()
    sb.append("Spatial Environment Map & Pathfinding Analysis:\n")
    sb.append("- Spatial Tracking Mode: ").append(if (isArCoreTrackingActive) "Active 3D Spatial SLAM" else "Camera Projection Fallback").append("\n")
    if (detectedText.isNotBlank()) {
      sb.append("- Visible Text / Signs Read: \"").append(detectedText).append("\"\n")
    }

    val objectsOnLeft = tracked3DObjects.count { it.position3D.x < -0.2f }
    val objectsOnRight = tracked3DObjects.count { it.position3D.x > 0.2f }
    val objectsAhead = tracked3DObjects.count { abs(it.position3D.x) <= 0.2f && it.position3D.z < 2.5f }

    val safePathSuggestion = when {
      objectsAhead == 0 -> "Path ahead is clear. Continue straight."
      objectsOnLeft < objectsOnRight -> "Obstacle ahead. Safe detour available to your LEFT."
      objectsOnRight < objectsOnLeft -> "Obstacle ahead. Safe detour available to your RIGHT."
      else -> "Obstacles ahead. Proceed with caution or pause."
    }
    sb.append("- Pathfinding Guidance: ").append(safePathSuggestion).append("\n")

    if (tracked3DObjects.isEmpty()) {
      sb.append("- Tracked Obstacles: None currently in view.\n")
    } else {
      sb.append("- Tracked Obstacles & Details:\n")
      tracked3DObjects.forEachIndexed { idx, obj ->
        val desc = String.format(
          Locale.US,
          "  %d. %s (Details: %s size, motion: %s)",
          idx + 1,
          obj.toSpatialPromptString(),
          if (obj.estimatedWidthMeters > 0.8f) "large" else "compact",
          obj.motionLabel
        )
        sb.append(desc).append("\n")
      }
    }

    return sb.toString()
  }
}

/**
 * Global holder for live 3D environment map state accessed by visualizers and UI.
 */
object MappedEnvironmentHolder {
  @Volatile var latestMap: Environment3DMap = Environment3DMap()
}
