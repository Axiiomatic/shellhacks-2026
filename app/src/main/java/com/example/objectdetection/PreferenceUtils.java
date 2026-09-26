package com.example.objectdetection;

import android.content.Context;

public class PreferenceUtils {

  public static boolean isCameraLiveViewportEnabled(Context context) {
    return true;
  }

  public static boolean shouldHideDetectionInfo(Context context) {
    return false;
  }

  private PreferenceUtils() {}
}
