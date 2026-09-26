package com.example.objectdetection;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Log;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class BitmapUtils {

  @Nullable
  public static Bitmap getBitmap(ByteBuffer data, FrameMetadata metadata) {
    data.rewind();
    byte[] imageInBuffer = new byte[data.limit()];
    data.get(imageInBuffer, 0, imageInBuffer.length);
    try {
      YuvImage image =
          new YuvImage(
              imageInBuffer, ImageFormat.NV21, metadata.getWidth(), metadata.getHeight(), null);
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      image.compressToJpeg(new Rect(0, 0, metadata.getWidth(), metadata.getHeight()), 80, stream);

      Bitmap bmp = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size());

      stream.close();
      return rotateBitmap(bmp, metadata.getRotation(), false, false);
    } catch (Exception e) {
      Log.e("BitmapUtils", "Error: " + e.getMessage());
    }
    return null;
  }

  private static Bitmap rotateBitmap(
      Bitmap bitmap, int rotationDegrees, boolean flipX, boolean flipY) {
    Matrix matrix = new Matrix();

    matrix.postRotate(rotationDegrees);
    matrix.postScale(flipX ? -1.0f : 1.0f, flipY ? -1.0f : 1.0f);
    Bitmap rotatedBitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

    if (rotatedBitmap != bitmap) {
      bitmap.recycle();
    }
    return rotatedBitmap;
  }
}
