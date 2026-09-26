package com.example.objectdetection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
  private var isFrontFacing = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    initOpenCV()
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    binding.facingSwitch.setOnClickListener {
      isFrontFacing = !isFrontFacing
      val newFacing = if (isFrontFacing) {
        CameraSource.CAMERA_FACING_FRONT
      } else {
        CameraSource.CAMERA_FACING_BACK
      }

      binding.facingSwitch.text = if (isFrontFacing) {
        getString(R.string.switch_to_back_camera)
      } else {
        getString(R.string.switch_to_front_camera)
      }

      cameraSource?.setFacing(newFacing)
      binding.previewView.stop()
      startCameraSource()
    }

    if (allRuntimePermissionsGranted()) {
      createCameraSource()
    } else {
      getRuntimePermissions()
    }
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
