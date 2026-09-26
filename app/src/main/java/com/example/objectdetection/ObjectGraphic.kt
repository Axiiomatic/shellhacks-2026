package com.example.objectdetection

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.example.objectdetection.GraphicOverlay.Graphic
import com.google.mlkit.vision.objects.DetectedObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Draw the detected object bounding box and normalized relative motion info in preview. */
class ObjectGraphic(
  overlay: GraphicOverlay,
  private val detectedObject: DetectedObject,
  private val trackInfo: ObjectTrackInfo? = null
) : Graphic(overlay) {

  private val numColors = COLORS.size

  private val boxPaints = Array(numColors) { Paint() }
  private val textPaints = Array(numColors) { Paint() }
  private val labelPaints = Array(numColors) { Paint() }

  private val warningBoxPaint = Paint().apply {
    color = Color.RED
    style = Paint.Style.STROKE
    strokeWidth = STROKE_WIDTH * 1.5f
    isAntiAlias = true
  }

  private val warningLabelPaint = Paint().apply {
    color = Color.RED
    style = Paint.Style.FILL
    isAntiAlias = true
  }

  private val warningTextPaint = Paint().apply {
    color = Color.WHITE
    textSize = TEXT_SIZE
    isAntiAlias = true
  }

  private val vectorPaint = Paint().apply {
    color = Color.CYAN
    style = Paint.Style.STROKE
    strokeWidth = 8.0f
    strokeCap = Paint.Cap.ROUND
    isAntiAlias = true
  }

  private val arrowheadPaint = Paint().apply {
    color = Color.CYAN
    style = Paint.Style.FILL
    isAntiAlias = true
  }

  init {
    for (i in 0 until numColors) {
      textPaints[i] = Paint().apply {
        color = COLORS[i][0]
        textSize = TEXT_SIZE
        isAntiAlias = true
      }
      boxPaints[i] = Paint().apply {
        color = COLORS[i][1]
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        isAntiAlias = true
      }
      labelPaints[i] = Paint().apply {
        color = COLORS[i][1]
        style = Paint.Style.FILL
        isAntiAlias = true
      }
    }
  }

  override fun draw(canvas: Canvas) {
    val colorID = if (detectedObject.trackingId == null) 0
      else abs(detectedObject.trackingId!! % numColors)

    val isWarning = trackInfo?.isRapidApproaching == true

    val boxPaintToUse = if (isWarning) warningBoxPaint else boxPaints[colorID]
    val labelPaintToUse = if (isWarning) warningLabelPaint else labelPaints[colorID]
    val textPaintToUse = if (isWarning) warningTextPaint else textPaints[colorID]

    // Draws the bounding box.
    val rect = RectF(detectedObject.boundingBox)
    val x0 = translateX(rect.left)
    val x1 = translateX(rect.right)
    rect.left = min(x0, x1)
    rect.right = max(x0, x1)
    rect.top = translateY(rect.top)
    rect.bottom = translateY(rect.bottom)
    canvas.drawRect(rect, boxPaintToUse)

    // Build text lines
    val lines = mutableListOf<String>()

    val idStr = if (detectedObject.trackingId != null) "ID: ${detectedObject.trackingId}" else "ID: N/A"
    lines.add(idStr)

    if (trackInfo != null) {
      lines.add("Motion: ${trackInfo.motionLabel}")
    }

    // Draw multi-line text header above bounding box
    val lineHeight = TEXT_SIZE + 6.0f
    val totalHeight = lines.size * lineHeight
    var maxTextWidth = 0.0f
    for (line in lines) {
      val w = textPaintToUse.measureText(line)
      if (w > maxTextWidth) {
        maxTextWidth = w
      }
    }

    val backgroundRect = RectF(
      rect.left - STROKE_WIDTH,
      rect.top - totalHeight - 2 * STROKE_WIDTH,
      rect.left + maxTextWidth + 3 * STROKE_WIDTH,
      rect.top
    )
    canvas.drawRect(backgroundRect, labelPaintToUse)

    var currentY = rect.top - totalHeight + TEXT_SIZE - 2.0f
    for (line in lines) {
      canvas.drawText(
        line,
        rect.left + STROKE_WIDTH,
        currentY,
        textPaintToUse
      )
      currentY += lineHeight
    }

    // Draw Motion Vector Arrow if object is moving
    if (trackInfo != null && trackInfo.isMoving) {
      drawMotionVectorArrow(canvas, rect)
    }
  }

  private fun drawMotionVectorArrow(canvas: Canvas, rect: RectF) {
    if (trackInfo == null) return

    val centerX = rect.centerX()
    val centerY = rect.centerY()

    // Convert displacement from frame space to screen space
    val rawDx = trackInfo.displacementX
    val rawDy = trackInfo.displacementY

    val screenDx = if (isImageFlipped()) -scale(rawDx) else scale(rawDx)
    val screenDy = scale(rawDy)

    val mag = sqrt(screenDx * screenDx + screenDy * screenDy)
    if (mag < 1.0f) return

    // Scale vector length based on relative speed for visual clarity
    val targetLength = max(30.0f, min(mag * 2.5f, 130.0f))
    val dirX = screenDx / mag
    val dirY = screenDy / mag

    val endX = centerX + dirX * targetLength
    val endY = centerY + dirY * targetLength

    // Draw vector shaft line
    canvas.drawLine(centerX, centerY, endX, endY, vectorPaint)

    // Draw arrowhead at (endX, endY)
    val angle = atan2(dirY.toDouble(), dirX.toDouble())
    val arrowHeadSize = 20.0f

    val path = Path().apply {
      moveTo(endX, endY)
      lineTo(
        (endX - arrowHeadSize * cos(angle - Math.PI / 6)).toFloat(),
        (endY - arrowHeadSize * sin(angle - Math.PI / 6)).toFloat()
      )
      lineTo(
        (endX - arrowHeadSize * cos(angle + Math.PI / 6)).toFloat(),
        (endY - arrowHeadSize * sin(angle + Math.PI / 6)).toFloat()
      )
      close()
    }
    canvas.drawPath(path, arrowheadPaint)
  }

  companion object {
    private const val TEXT_SIZE = 36.0f
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
