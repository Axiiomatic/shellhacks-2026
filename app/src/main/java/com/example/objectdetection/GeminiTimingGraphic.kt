package com.example.objectdetection

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

class GeminiTimingGraphic(
  overlay: GraphicOverlay,
  private val durationMs: Long?,
  private val inProgress: Boolean
) : GraphicOverlay.Graphic(overlay) {

  private val textPaint = Paint().apply {
    color = Color.WHITE
    textSize = TEXT_SIZE
    isAntiAlias = true
    setShadowLayer(5.0f, 0f, 0f, Color.BLACK)
  }

  private val backgroundPaint = Paint().apply {
    color = Color.argb(150, 0, 0, 0)
    style = Paint.Style.FILL
  }

  override fun draw(canvas: Canvas) {
    val label = when {
      inProgress -> "Gemini: running..."
      durationMs != null -> "Gemini: ${durationMs} ms"
      else -> "Gemini: --"
    }
    val right = canvas.width - EDGE_PADDING
    val left = right - textPaint.measureText(label) - HORIZONTAL_PADDING * 2
    val top = EDGE_PADDING
    val bottom = top + TEXT_SIZE + VERTICAL_PADDING * 2
    canvas.drawRect(left, top, right, bottom, backgroundPaint)
    canvas.drawText(label, left + HORIZONTAL_PADDING, top + TEXT_SIZE, textPaint)
  }

  companion object {
    private const val TEXT_SIZE = 32.0f
    private const val EDGE_PADDING = 16.0f
    private const val HORIZONTAL_PADDING = 10.0f
    private const val VERTICAL_PADDING = 6.0f
  }
}