package com.example.objectdetection;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceUtils {

  private static final String PREF_NAME = "object_detection_prefs";
  private static final String KEY_VIBRATION_ENABLED = "key_vibration_enabled";
  private static final String KEY_AUDIO_ENABLED = "key_audio_enabled";
  private static final String KEY_TORCH_ENABLED = "key_torch_enabled";
  private static final String KEY_INFERENCE_ENABLED = "key_inference_enabled";

  public static boolean isCameraLiveViewportEnabled(Context context) {
    return true;
  }

  public static boolean shouldHideDetectionInfo(Context context) {
    return false;
  }

  public static boolean isVibrationEnabled(Context context) {
    SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    return sp.getBoolean(KEY_VIBRATION_ENABLED, true);
  }

  public static void setVibrationEnabled(Context context, boolean enabled) {
    SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    sp.edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply();
  }

  public static boolean isAudioEnabled(Context context) {
    SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    return sp.getBoolean(KEY_AUDIO_ENABLED, true);
  }

  public static void setAudioEnabled(Context context, boolean enabled) {
    SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    sp.edit().putBoolean(KEY_AUDIO_ENABLED, enabled).apply();
  }

  public static boolean isTorchEnabled(Context context) {
    SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    return sp.getBoolean(KEY_TORCH_ENABLED, false);
  }

  public static void setTorchEnabled(Context context, boolean enabled) {
    SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    sp.edit().putBoolean(KEY_TORCH_ENABLED, enabled).apply();
  }

  public static boolean isInferenceModeEnabled(Context context) {
    SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    return sp.getBoolean(KEY_INFERENCE_ENABLED, false);
  }

  public static void setInferenceModeEnabled(Context context, boolean enabled) {
    SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    sp.edit().putBoolean(KEY_INFERENCE_ENABLED, enabled).apply();
  }

  private PreferenceUtils() {}
}
