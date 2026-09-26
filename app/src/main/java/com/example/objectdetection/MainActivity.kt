package com.example.objectdetection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.objectdetection.databinding.ActivityMainBinding
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.opencv.android.OpenCVLoader
import java.io.IOException

class MainActivity : AppCompatActivity() {

  private lateinit var binding: ActivityMainBinding
  private var cameraSource: CameraSource? = null
  private var isControlsExpanded = false
  private lateinit var ttsHelper: TtsHelper

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    initOpenCV()
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    ttsHelper = TtsHelper(this)
    setupAlertToggles()
    setupBottomCardToggle()
    collapseCard()

    if (allRuntimePermissionsGranted()) {
      createCameraSource()
    } else {
      getRuntimePermissions()
    }
  }

  private fun setupAlertToggles() {
    updateAudioToggleButton()
    updateVibrationToggleButton()
    updateTorchToggleButton()
    updateInferenceToggleButton()

    binding.audioToggle.setOnClickListener { view ->
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      val newAudioState = !PreferenceUtils.isAudioEnabled(this)
      PreferenceUtils.setAudioEnabled(this, newAudioState)
      updateAudioToggleButton()
      val msg = if (newAudioState) "Audio alerts enabled" else "Audio alerts disabled"
      ttsHelper.speak(msg, override = true)
      view.announceForAccessibility(msg)
    }

    binding.vibrationToggle.setOnClickListener { view ->
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      val newVibeState = !PreferenceUtils.isVibrationEnabled(this)
      PreferenceUtils.setVibrationEnabled(this, newVibeState)
      updateVibrationToggleButton()
      val msg = if (newVibeState) "Vibration alerts enabled" else "Vibration alerts disabled"
      ttsHelper.speak(msg, override = true)
      view.announceForAccessibility(msg)
    }

    binding.torchToggle.setOnClickListener { view ->
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      val newTorchState = !PreferenceUtils.isTorchEnabled(this)
      PreferenceUtils.setTorchEnabled(this, newTorchState)
      cameraSource?.setTorch(newTorchState)
      updateTorchToggleButton()
      val msg = if (newTorchState) "Flashlight turned on" else "Flashlight turned off"
      ttsHelper.speak(msg, override = true)
      view.announceForAccessibility(msg)
    }

    binding.inferenceToggle.setOnClickListener { view ->
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      val newInferenceState = !PreferenceUtils.isInferenceModeEnabled(this)
      PreferenceUtils.setInferenceModeEnabled(this, newInferenceState)
      updateInferenceToggleButton()
      val msg = if (newInferenceState) "Gemini AI inference mode enabled" else "Gemini AI inference mode disabled"
      ttsHelper.speak(msg, override = true)
      view.announceForAccessibility(msg)
    }
  }

  private fun setupBottomCardToggle() {
    binding.controlToggleBtn.setOnClickListener { view ->
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      if (isControlsExpanded) {
        collapseCard()
        ttsHelper.speak("Quick controls collapsed", override = true)
      } else {
        expandCard()
        ttsHelper.speak("Quick controls expanded", override = true)
      }
    }
    updateControlToggleButton()
  }

  private fun expandCard() {
    isControlsExpanded = true
    binding.scrollView.visibility = View.VISIBLE
    updateControlToggleButton()
    binding.controlToggleBtn.announceForAccessibility("Quick controls expanded")
  }

  private fun collapseCard() {
    isControlsExpanded = false
    binding.scrollView.visibility = View.GONE
    updateControlToggleButton()
    binding.controlToggleBtn.announceForAccessibility("Quick controls collapsed")
  }

  private fun updateControlToggleButton() {
    if (isControlsExpanded) {
      binding.controlToggleArrow.setImageResource(R.drawable.ic_arrow_down)
    } else {
      binding.controlToggleArrow.setImageResource(R.drawable.ic_arrow_up)
    }
  }

  private fun updateAudioToggleButton() {
    val isAudioOn = PreferenceUtils.isAudioEnabled(this)
    val iconRes = if (isAudioOn) R.drawable.ic_volume_up else R.drawable.ic_volume_off
    binding.audioIcon.setImageResource(iconRes)

    if (isAudioOn) {
      binding.audioToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.pastel_yellow)
      binding.audioIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.black)
    } else {
      binding.audioToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.dark_card)
      binding.audioIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.pastel_yellow)
    }
    binding.audioToggle.contentDescription = if (isAudioOn) getString(R.string.cd_audio_on) else getString(R.string.cd_audio_off)
  }

  private fun updateVibrationToggleButton() {
    val isVibeOn = PreferenceUtils.isVibrationEnabled(this)
    val iconRes = if (isVibeOn) R.drawable.ic_vibration else R.drawable.vibration_off
    binding.vibrationIcon.setImageResource(iconRes)

    if (isVibeOn) {
      binding.vibrationToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.pastel_green)
      binding.vibrationIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.black)
    } else {
      binding.vibrationToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.dark_card)
      binding.vibrationIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.pastel_green)
    }
    binding.vibrationToggle.contentDescription = if (isVibeOn) getString(R.string.cd_vibe_on) else getString(R.string.cd_vibe_off)
  }

  private fun updateTorchToggleButton() {
    val isTorchOn = PreferenceUtils.isTorchEnabled(this)
    val iconRes = if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
    binding.torchIcon.setImageResource(iconRes)

    if (isTorchOn) {
      binding.torchToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.pastel_peach)
      binding.torchIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.black)
    } else {
      binding.torchToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.dark_card)
      binding.torchIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.pastel_peach)
    }
    binding.torchToggle.contentDescription = if (isTorchOn) getString(R.string.cd_torch_on) else getString(R.string.cd_torch_off)
  }

  private fun updateInferenceToggleButton() {
    val isInferenceOn = PreferenceUtils.isInferenceModeEnabled(this)
    val iconRes = R.drawable.ic_ai
    binding.inferenceIcon.setImageResource(iconRes)

    if (isInferenceOn) {
      binding.inferenceToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.pastel_lavender)
      binding.inferenceIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.black)
    } else {
      binding.inferenceToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.dark_card)
      binding.inferenceIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.pastel_lavender)
    }
    binding.inferenceToggle.contentDescription = if (isInferenceOn) getString(R.string.cd_inference_on) else getString(R.string.cd_inference_off)
  }

  private fun initOpenCV() {
    try {
      if (OpenCVLoader.initLocal()) {
        Log.i(TAG, "OpenCV initialized successfully")
      } else {
        Log.w(TAG, "OpenCV initLocal returned false")
      }
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to initialize OpenCV", e)
    }
  }

  private fun createCameraSource() {
    if (cameraSource == null) {
      cameraSource = CameraSource(this, binding.graphicOverlay)
    }

    try {
      val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableMultipleObjects()
        .build()

      cameraSource?.setMachineLearningFrameProcessor(
        ObjectDetectorProcessor(this, options)
      )
    } catch (e: Exception) {
      Log.e(TAG, "Cannot create object detector processor", e)
      Toast.makeText(this, "Cannot create object detector processor: ${e.message}", Toast.LENGTH_LONG).show()
    }
  }

  private fun startCameraSource() {
    if (cameraSource != null) {
      try {
        binding.previewView.start(cameraSource, binding.graphicOverlay)
        cameraSource?.setTorch(PreferenceUtils.isTorchEnabled(this))
      } catch (e: IOException) {
        Log.e(TAG, "Unable to start camera source.", e)
        cameraSource?.release()
        cameraSource = null
      }
    }
  }

  override fun onResume() {
    super.onResume()
    if (allRuntimePermissionsGranted()) {
      createCameraSource()
      startCameraSource()
    }
  }

  override fun onPause() {
    super.onPause()
    binding.previewView.stop()
  }

  override fun onDestroy() {
    super.onDestroy()
    ttsHelper.release()
    cameraSource?.release()
    cameraSource = null
  }

  private fun allRuntimePermissionsGranted(): Boolean {
    for (permission in REQUIRED_PERMISSIONS) {
      if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
        return false
      }
    }
    return true
  }

  private fun getRuntimePermissions() {
    val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    if (permissionsToRequest.isNotEmpty()) {
      ActivityCompat.requestPermissions(
        this,
        permissionsToRequest.toTypedArray(),
        PERMISSION_REQUEST_CODE
      )
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == PERMISSION_REQUEST_CODE) {
      if (allRuntimePermissionsGranted()) {
        createCameraSource()
        startCameraSource()
      } else {
        Toast.makeText(this, R.string.permission_camera_rationale, Toast.LENGTH_LONG).show()
      }
    }
  }

  companion object {
    private const val TAG = "MainActivity"
    private const val PERMISSION_REQUEST_CODE = 1
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
  }
}
