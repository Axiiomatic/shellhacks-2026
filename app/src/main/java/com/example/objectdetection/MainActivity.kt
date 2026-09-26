package com.example.objectdetection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.objectdetection.databinding.ActivityMainBinding
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.opencv.android.OpenCVLoader
import java.io.IOException
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

  private lateinit var binding: ActivityMainBinding
  private var cameraSource: CameraSource? = null
  private var isControlsExpanded = true

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    initOpenCV()
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setupAlertToggles()
    setupBottomCardToggle()

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
      val cd = if (newAudioState) getString(R.string.cd_audio_on) else getString(R.string.cd_audio_off)
      view.announceForAccessibility(cd)
    }

    binding.vibrationToggle.setOnClickListener { view ->
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      val newVibeState = !PreferenceUtils.isVibrationEnabled(this)
      PreferenceUtils.setVibrationEnabled(this, newVibeState)
      updateVibrationToggleButton()
      val cd = if (newVibeState) getString(R.string.cd_vibe_on) else getString(R.string.cd_vibe_off)
      view.announceForAccessibility(cd)
    }

    binding.torchToggle.setOnClickListener { view ->
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      val newTorchState = !PreferenceUtils.isTorchEnabled(this)
      PreferenceUtils.setTorchEnabled(this, newTorchState)
      cameraSource?.setTorch(newTorchState)
      updateTorchToggleButton()
      val cd = if (newTorchState) getString(R.string.cd_torch_on) else getString(R.string.cd_torch_off)
      view.announceForAccessibility(cd)
    }

    binding.inferenceToggle.setOnClickListener { view ->
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      val newInferenceState = !PreferenceUtils.isInferenceModeEnabled(this)
      PreferenceUtils.setInferenceModeEnabled(this, newInferenceState)
      updateInferenceToggleButton()
      val cd = if (newInferenceState) getString(R.string.cd_inference_on) else getString(R.string.cd_inference_off)
      view.announceForAccessibility(cd)
    }
  }

  private fun setupBottomCardToggle() {
    var startY = 0f
    val touchListener = View.OnTouchListener { view, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          startY = event.rawY
          true
        }
        MotionEvent.ACTION_UP -> {
          val deltaY = event.rawY - startY
          val swipeThreshold = 35f
          view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
          view.performClick()
          if (abs(deltaY) > swipeThreshold) {
            if (deltaY > 0) {
              // Swiped down -> collapse fully down
              collapseCard()
            } else {
              // Swiped up -> expand fully up
              expandCard()
            }
          } else {
            // Tap -> toggle fully up / fully down
            if (isControlsExpanded) collapseCard() else expandCard()
          }
          true
        }
        else -> false
      }
    }

    binding.headerLayout.setOnTouchListener(touchListener)
    binding.dragHandle.setOnTouchListener(touchListener)
  }

  private fun expandCard() {
    isControlsExpanded = true
    binding.scrollView.visibility = View.VISIBLE
    binding.controlSheet.animate().translationY(0f).setDuration(200).start()
    binding.headerLayout.announceForAccessibility("Quick controls expanded")
  }

  private fun collapseCard() {
    isControlsExpanded = false
    binding.scrollView.visibility = View.GONE
    binding.headerLayout.announceForAccessibility("Quick controls collapsed")
  }

  private fun updateAudioToggleButton() {
    val isAudioOn = PreferenceUtils.isAudioEnabled(this)
    binding.audioToggle.text = if (isAudioOn) getString(R.string.audio_alert_on) else getString(R.string.audio_alert_off)
    val iconRes = if (isAudioOn) R.drawable.ic_volume_up else R.drawable.ic_volume_off
    binding.audioToggle.setIconResource(iconRes)

    if (isAudioOn) {
      binding.audioToggle.setStrokeColorResource(R.color.accessible_gold)
      binding.audioToggle.strokeWidth = dpToPx(2)
      binding.audioToggle.alpha = 1.0f
    } else {
      binding.audioToggle.setStrokeColorResource(R.color.accessible_outline)
      binding.audioToggle.strokeWidth = dpToPx(1)
      binding.audioToggle.alpha = 0.55f
    }
    binding.audioToggle.contentDescription = if (isAudioOn) getString(R.string.cd_audio_on) else getString(R.string.cd_audio_off)
  }

  private fun updateVibrationToggleButton() {
    val isVibeOn = PreferenceUtils.isVibrationEnabled(this)
    binding.vibrationToggle.text = if (isVibeOn) getString(R.string.vibe_alert_on) else getString(R.string.vibe_alert_off)
    val iconRes = if (isVibeOn) R.drawable.ic_vibration else R.drawable.ic_vibration_off
    binding.vibrationToggle.setIconResource(iconRes)

    if (isVibeOn) {
      binding.vibrationToggle.setStrokeColorResource(R.color.accessible_gold)
      binding.vibrationToggle.strokeWidth = dpToPx(2)
      binding.vibrationToggle.alpha = 1.0f
    } else {
      binding.vibrationToggle.setStrokeColorResource(R.color.accessible_outline)
      binding.vibrationToggle.strokeWidth = dpToPx(1)
      binding.vibrationToggle.alpha = 0.55f
    }
    binding.vibrationToggle.contentDescription = if (isVibeOn) getString(R.string.cd_vibe_on) else getString(R.string.cd_vibe_off)
  }

  private fun updateTorchToggleButton() {
    val isTorchOn = PreferenceUtils.isTorchEnabled(this)
    binding.torchToggle.text = if (isTorchOn) getString(R.string.torch_on) else getString(R.string.torch_off)
    val iconRes = if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
    binding.torchToggle.setIconResource(iconRes)

    if (isTorchOn) {
      binding.torchToggle.setStrokeColorResource(R.color.accessible_gold)
      binding.torchToggle.strokeWidth = dpToPx(2)
      binding.torchToggle.alpha = 1.0f
    } else {
      binding.torchToggle.setStrokeColorResource(R.color.accessible_outline)
      binding.torchToggle.strokeWidth = dpToPx(1)
      binding.torchToggle.alpha = 0.55f
    }
    binding.torchToggle.contentDescription = if (isTorchOn) getString(R.string.cd_torch_on) else getString(R.string.cd_torch_off)
  }

  private fun updateInferenceToggleButton() {
    val isInferenceOn = PreferenceUtils.isInferenceModeEnabled(this)
    binding.inferenceToggle.text = if (isInferenceOn) getString(R.string.inference_on) else getString(R.string.inference_off)
    val iconRes = R.drawable.ic_ai
    binding.inferenceToggle.setIconResource(iconRes)

    if (isInferenceOn) {
      binding.inferenceToggle.setStrokeColorResource(R.color.accessible_gold)
      binding.inferenceToggle.strokeWidth = dpToPx(2)
      binding.inferenceToggle.alpha = 1.0f
    } else {
      binding.inferenceToggle.setStrokeColorResource(R.color.accessible_outline)
      binding.inferenceToggle.strokeWidth = dpToPx(1)
      binding.inferenceToggle.alpha = 0.55f
    }
    binding.inferenceToggle.contentDescription = if (isInferenceOn) getString(R.string.cd_inference_on) else getString(R.string.cd_inference_off)
  }

  private fun dpToPx(dp: Int): Int {
    return (dp * resources.displayMetrics.density).toInt()
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
