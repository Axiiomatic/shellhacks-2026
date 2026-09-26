package com.example.objectdetection

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.io.IOException

/** A processor to run object detector with normalized relative motion tracking. */
class ObjectDetectorProcessor(context: Context, options: ObjectDetectorOptions) :
  VisionProcessorBase<List<DetectedObject>>(context) {

  private val detector: ObjectDetector = ObjectDetection.getClient(options)
  private val motionTracker = MotionTracker()

  override fun stop() {
    super.stop()
    try {
      detector.close()
    } catch (e: IOException) {
      Log.e(TAG, "Exception thrown while trying to close object detector!", e)
    }
  }

  override fun detectInImage(image: InputImage): Task<List<DetectedObject>> {
    return detector.process(image)
  }

  override fun onSuccess(results: List<DetectedObject>, graphicOverlay: GraphicOverlay) {
    val trackInfoMap = motionTracker.processFrame(
      detectedObjects = results,
      frameWidth = graphicOverlay.imageWidth,
      frameHeight = graphicOverlay.imageHeight
    )

    for (result in results) {
      val trackInfo = trackInfoMap[result]
      graphicOverlay.add(ObjectGraphic(graphicOverlay, result, trackInfo))
    }
  }

  override fun onFailure(e: Exception) {
    Log.e(TAG, "Object detection failed!", e)
  }

  companion object {
    private const val TAG = "ObjectDetectorProcessor"
  }
}
