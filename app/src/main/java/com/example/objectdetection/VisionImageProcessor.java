package com.example.objectdetection;

import android.graphics.Bitmap;
import com.google.mlkit.common.MlKitException;
import java.nio.ByteBuffer;

public interface VisionImageProcessor {

  void processBitmap(Bitmap bitmap, GraphicOverlay graphicOverlay);

  void processByteBuffer(
      ByteBuffer data, FrameMetadata frameMetadata, GraphicOverlay graphicOverlay)
      throws MlKitException;

  void stop();
}
