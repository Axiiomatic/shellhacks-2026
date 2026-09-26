package com.example.objectdetection

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.objectdetection.GraphicOverlay.Graphic
import com.google.mlkit.vision.objects.DetectedObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Draw the detected object bounding box in preview. */
class ObjectGraphic(
  overlay: GraphicOverlay,
  private val detectedObject: DetectedObject
) : Graphic(overlay) {

  private val numColors = COLORS.size

  private val boxPaints = Array(numColors) { Paint() }
  private val textPaints = Array(numColors) { Paint() }
  private val labelPaints = Array(numColors) { Paint() }

  init {
    for (i in 0 until numColors) {
      textPaints[i] = Paint().apply {
        color = COLORS[i][0]
        textSize = TEXT_SIZE
      }
      boxPaints[i] = Paint().apply {
        color = COLORS[i][1]
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
      }
      labelPaints[i] = Paint().apply {
        color = COLORS[i][1]
        style = Paint.Style.FILL
      }
    }
  }

  override fun draw(canvas: Canvas) {
    val colorID = if (detectedObject.trackingId == null) 0
      else abs(detectedObject.trackingId!! % numColors)

    // Draws the bounding box.
    val rect = RectF(detectedObject.boundingBox)
    val x0 = translateX(rect.left)
    val x1 = translateX(rect.right)
    rect.left = min(x0, x1)
    rect.right = max(x0, x1)
    rect.top = translateY(rect.top)
    rect.bottom = translateY(rect.bottom)
    canvas.drawRect(rect, boxPaints[colorID])

    // Draws object tracking ID if present
    if (detectedObject.trackingId != null) {
      val text = "ID: ${detectedObject.trackingId}"
      val textWidth = textPaints[colorID].measureText(text)
      val lineHeight = TEXT_SIZE + STROKE_WIDTH
      val yLabelOffset = -lineHeight

      canvas.drawRect(
        rect.left - STROKE_WIDTH,
        rect.top + yLabelOffset,
        rect.left + textWidth + 2 * STROKE_WIDTH,
        rect.top,
        labelPaints[colorID]
      )
      canvas.drawText(
        text,
        rect.left,
        rect.top - STROKE_WIDTH,
        textPaints[colorID]
      )
    }
  }

  companion object {
    private const val TEXT_SIZE = 40.0f
    private const val STROKE_WIDTH = 6.0f
    private val COLORS =
      arrayOf(
        intArrayOf(Color.BLACK, Color.WHITE),
        intArrayOf(Color.WHITE, Color.MAGENTA),
        intArrayOf(Color.BLACK, Color.LTGRAY),
        intArrayOf(Color.WHITE, Color.RED),
        intArrayOf(Color.WHITE, Color.BLUE),
        intArrayOf(Color.WHITE, Color.DKGRAY),
        intArrayOf(Color.BLACK, Color.CYAN),
        intArrayOf(Color.BLACK, Color.YELLOW),
        intArrayOf(Color.WHITE, Color.BLACK),
        intArrayOf(Color.BLACK, Color.GREEN)
      )
  }
}
