package com.example.objectdetection

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Graphic overlay layer that renders 3D mapped point clouds, spatial object distances (in meters),
 * velocity vectors, surface planes, and ARCore tracking status.
 */
class Environment3DMapGraphic(
  private val graphicOverlay: GraphicOverlay,
  private val environment3DMap: Environment3DMap
) : GraphicOverlay.Graphic(graphicOverlay) {

  private val pointPaint = Paint().apply {
    color = Color.CYAN
    style = Paint.Style.FILL
    isAntiAlias = true
  }

  private val hazardPaint = Paint().apply {
    color = Color.RED
    style = Paint.Style.STROKE
    strokeWidth = 6.0f
    isAntiAlias = true
  }

  private val normalBoxPaint = Paint().apply {
    color = Color.rgb(100, 220, 255)
    style = Paint.Style.STROKE
    strokeWidth = 4.0f
    isAntiAlias = true
  }

  private val textBgPaint = Paint().apply {
    color = Color.argb(200, 15, 23, 42) // Dark translucent background slate
    style = Paint.Style.FILL
    isAntiAlias = true
  }

  private val textPaint = Paint().apply {
    color = Color.WHITE
    textSize = 32.0f
    isAntiAlias = true
  }

  private val statusPaint = Paint().apply {
    color = Color.rgb(168, 224, 255)
    textSize = 28.0f
    isAntiAlias = true
  }

  override fun draw(canvas: Canvas) {
    val imgW = graphicOverlay.imageWidth.toFloat()
    val imgH = graphicOverlay.imageHeight.toFloat()
    if (imgW <= 0f || imgH <= 0f) return

    // 1. Render 3D Point Cloud Landmarks
    for (pt in environment3DMap.pointCloud3D) {
      val normX = (pt.x / max(pt.z, 0.1f)) / 1.28f + 0.5f
      val normY = (-pt.y / max(pt.z, 0.1f)) / 1.28f + 0.5f

      val screenX = translateX(normX * imgW)
      val screenY = translateY(normY * imgH)

      // Radius scales inversely with depth Z
      val radius = max(3.0f, min(14.0f, 12.0f / max(0.5f, pt.z)))
      val alpha = max(80, min(255, (255 * (1.0f - (pt.z / 6.0f))).toInt()))

      pointPaint.color = ColorUtils.setAlphaComponent(
        if (pt.source == Point3D.PointSource.ARCORE_POINT_CLOUD) Color.CYAN else Color.rgb(120, 180, 255),
        alpha
      )
      canvas.drawCircle(screenX, screenY, radius, pointPaint)
    }

    // 2. Render 3D Spatial Objects with Meters Depth Callouts
    for (obj in environment3DMap.tracked3DObjects) {
      val box = obj.boundingBox2D
      val left = translateX(box.left.toFloat())
      val top = translateY(box.top.toFloat())
      val right = translateX(box.right.toFloat())
      val bottom = translateY(box.bottom.toFloat())

      val rectF = RectF(
        min(left, right),
        min(top, bottom),
        max(left, right),
        max(top, bottom)
      )

      val boxPaintToUse = if (obj.isHazard) hazardPaint else normalBoxPaint
      canvas.drawRoundRect(rectF, 12f, 12f, boxPaintToUse)

      // Format directional spatial label without actual lengths
      val proximityStr = when {
        obj.position3D.z < 1.5f -> "Very Close"
        obj.position3D.z < 3.0f -> "Nearby"
        else -> "Distant"
      }

      val sideStr = when {
        obj.position3D.x < -0.35f -> "To Your Left"
        obj.position3D.x > 0.35f -> "To Your Right"
        else -> "Straight Ahead"
      }

      val labelText = String.format(
        Locale.US,
        "%s • %s %s",
        obj.label,
        sideStr,
        if (obj.isHazard) "⚠️ HAZARD" else ""
      )
      val subText = String.format(
        Locale.US,
        "%s • %s",
        proximityStr,
        obj.motionLabel
      )

      val textWidth = max(textPaint.measureText(labelText), textPaint.measureText(subText))
      val bgRect = RectF(
        rectF.left,
        max(0f, rectF.top - 70f),
        min(graphicOverlay.width.toFloat(), rectF.left + textWidth + 24f),
        rectF.top
      )

      canvas.drawRoundRect(bgRect, 8f, 8f, textBgPaint)
      canvas.drawText(labelText, bgRect.left + 12f, bgRect.top + 32f, textPaint)
      canvas.drawText(subText, bgRect.left + 12f, bgRect.top + 60f, statusPaint)
    }

    // 3. Render Top ARCore 3D Mapping Status Banner
    val statusText = environment3DMap.trackingStateSummary
    val statusWidth = statusPaint.measureText(statusText)
    val bannerRect = RectF(
      16f,
      16f,
      16f + statusWidth + 32f,
      64f
    )
    canvas.drawRoundRect(bannerRect, 16f, 16f, textBgPaint)
    canvas.drawText(statusText, 32f, 48f, statusPaint)

    // 4. Render ARCore 3D Environment Debug HUD Panel
    val debugLines = listOf(
      "=== ARCore 3D Debug HUD ===",
      "SLAM Tracking: ${if (environment3DMap.isArCoreTrackingActive) "Active (Tracking)" else "Fallback (Uncalibrated)"}",
      String.format(
        Locale.US,
        "Camera Pos (X,Y,Z): X:%.2fm, Y:%.2fm, Z:%.2fm",
        environment3DMap.cameraPosition3D.x,
        environment3DMap.cameraPosition3D.y,
        environment3DMap.cameraPosition3D.z
      ),
      "Point Cloud Landmarks: ${environment3DMap.pointCloud3D.size} points",
      "Surface Planes: ${environment3DMap.detectedPlanes.size} planes",
      "Tracked Objects: ${environment3DMap.tracked3DObjects.size} objects"
    )

    val debugPaint = Paint().apply {
      color = Color.rgb(0, 255, 200)
      textSize = 24.0f
      isAntiAlias = true
    }

    val hudLeft = 16f
    val hudTop = 80f
    val hudWidth = 480f
    val hudHeight = (debugLines.size * 32f) + 24f
    val hudRect = RectF(hudLeft, hudTop, hudLeft + hudWidth, hudTop + hudHeight)
    canvas.drawRoundRect(hudRect, 12f, 12f, textBgPaint)

    var lineY = hudTop + 28f
    for (line in debugLines) {
      canvas.drawText(line, hudLeft + 16f, lineY, debugPaint)
      lineY += 32f
    }
  }
}
