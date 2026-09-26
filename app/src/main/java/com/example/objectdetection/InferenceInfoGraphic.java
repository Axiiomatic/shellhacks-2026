package com.example.objectdetection;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import androidx.annotation.Nullable;

public class InferenceInfoGraphic extends GraphicOverlay.Graphic {

  private static final int TEXT_COLOR = Color.WHITE;
  private static final float TEXT_SIZE = 48.0f;

  private final Paint textPaint;
  private final GraphicOverlay overlay;
  private final long frameLatency;
  private final long detectorLatency;
  @Nullable private final Integer framesPerSecond;

  public InferenceInfoGraphic(
      GraphicOverlay overlay,
      long frameLatency,
      long detectorLatency,
      @Nullable Integer framesPerSecond) {
    super(overlay);
    this.overlay = overlay;
    this.frameLatency = frameLatency;
    this.detectorLatency = detectorLatency;
    this.framesPerSecond = framesPerSecond;
    textPaint = new Paint();
    textPaint.setColor(TEXT_COLOR);
    textPaint.setTextSize(TEXT_SIZE);
    textPaint.setShadowLayer(5.0f, 0f, 0f, Color.BLACK);
    postInvalidate();
  }

  @Override
  public synchronized void draw(Canvas canvas) {
    float x = TEXT_SIZE * 0.5f;
    float y = TEXT_SIZE * 1.5f;

    canvas.drawText(
        "Input size: " + overlay.getImageHeight() + "x" + overlay.getImageWidth(),
        x,
        y,
        textPaint);

    if (framesPerSecond != null) {
      canvas.drawText(
          "FPS: " + framesPerSecond + ", Frame latency: " + frameLatency + " ms",
          x,
          y + TEXT_SIZE,
          textPaint);
    } else {
      canvas.drawText("Frame latency: " + frameLatency + " ms", x, y + TEXT_SIZE, textPaint);
    }
    canvas.drawText(
        "Detector latency: " + detectorLatency + " ms", x, y + TEXT_SIZE * 2, textPaint);
  }
}
