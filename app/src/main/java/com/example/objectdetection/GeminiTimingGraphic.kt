package com.example.objectdetection

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

class GeminiTimingGraphic(
  overlay: GraphicOverlay,
  private val intervalMs: Long?,
  private val inProgress: Boolean,
  private val isInferenceEnabled: Boolean
) : GraphicOverlay.Graphic(overlay) {

  private val textPaint = Paint().apply {
    color = Color.WHITE
    textSize = TEXT_SIZE
    isAntiAlias = true
    setShadowLayer(6.0f, 0f, 0f, Color.BLACK)
  }

  private val backgroundPaint = Paint().apply {
    color = Color.argb(180, 0, 0, 0)
    style = Paint.Style.FILL
  }

  override fun draw(canvas: Canvas) {
    val label = when {
      !isInferenceEnabled -> "Gemini: OFF"
      inProgress -> "Gemini: running..."
      intervalMs != null -> "Gemini interval: ${intervalMs} ms"
      else -> "Gemini: waiting..."
    }
    val right = canvas.width - EDGE_PADDING
    val left = right - textPaint.measureText(label) - HORIZONTAL_PADDING * 2
    val top = TOP_MARGIN
    val bottom = top + TEXT_SIZE + VERTICAL_PADDING * 2
    canvas.drawRect(left, top, right, bottom, backgroundPaint)
    canvas.drawText(label, left + HORIZONTAL_PADDING, top + TEXT_SIZE + VERTICAL_PADDING, textPaint)
  }

  companion object {
    private const val TEXT_SIZE = 34.0f
    private const val EDGE_PADDING = 20.0f
    private const val TOP_MARGIN = 220.0f
    private const val HORIZONTAL_PADDING = 14.0f
    private const val VERTICAL_PADDING = 8.0f
  }
}