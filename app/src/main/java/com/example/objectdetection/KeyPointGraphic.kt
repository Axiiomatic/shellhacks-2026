package com.example.objectdetection

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.opencv.core.Point

class KeyPointGraphic(
  overlay: GraphicOverlay,
  private val keyPoints: List<Point>,
  private val alertPoints: List<Point>
) : GraphicOverlay.Graphic(overlay) {

  private val keyPointPaint = Paint().apply {
    color = Color.CYAN
    alpha = 110
    style = Paint.Style.FILL
    isAntiAlias = true
  }

  private val alertPointPaint = Paint().apply {
    color = Color.RED
    style = Paint.Style.FILL
    isAntiAlias = true
  }

  override fun draw(canvas: Canvas) {
    for (point in keyPoints) {
      canvas.drawCircle(translateX(point.x.toFloat()), translateY(point.y.toFloat()), KEY_POINT_RADIUS, keyPointPaint)
    }

    for (point in alertPoints) {
      canvas.drawCircle(translateX(point.x.toFloat()), translateY(point.y.toFloat()), ALERT_POINT_RADIUS, alertPointPaint)
    }
  }

  companion object {
    private const val KEY_POINT_RADIUS = 7.0f
    private const val ALERT_POINT_RADIUS = 11.0f
  }
}
