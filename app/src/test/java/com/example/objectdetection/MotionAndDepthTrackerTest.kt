package com.example.objectdetection

import android.graphics.Rect
import com.google.mlkit.vision.objects.DetectedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class MotionTrackerTest {

  private fun createRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
    val rect = Rect()
    rect.left = left
    rect.top = top
    rect.right = right
    rect.bottom = bottom
    return rect
  }

  @Test
  fun testStationaryTracking() {
    val tracker = MotionTracker()

    val mockObj = mock(DetectedObject::class.java)
    `when`(mockObj.boundingBox).thenReturn(createRect(310, 590, 410, 690))
    `when`(mockObj.trackingId).thenReturn(101)

    val results = tracker.processFrame(
      detectedObjects = listOf(mockObj),
      frameWidth = 720,
      frameHeight = 1280
    )

    val trackInfo = results[mockObj]
    assertNotNull(trackInfo)
    assertEquals(101, trackInfo?.trackingId)
    assertFalse(trackInfo!!.isMoving)
    assertEquals("Stationary", trackInfo.motionLabel)
    assertEquals(0.0f, trackInfo.relativeSpeed, 0.001f)
  }

  @Test
  fun testMotionTrackingMovement() {
    val tracker = MotionTracker()

    // Frame 1: Object at (100, 100, 200, 200)
    val mockObj1 = mock(DetectedObject::class.java)
    `when`(mockObj1.boundingBox).thenReturn(createRect(100, 100, 200, 200))
    `when`(mockObj1.trackingId).thenReturn(202)

    tracker.processFrame(listOf(mockObj1), 720, 1280)

    Thread.sleep(100) // 100 ms interval

    // Frame 2: Object moved right to (250, 100, 350, 200)
    val mockObj2 = mock(DetectedObject::class.java)
    `when`(mockObj2.boundingBox).thenReturn(createRect(250, 100, 350, 200))
    `when`(mockObj2.trackingId).thenReturn(202)

    val results2 = tracker.processFrame(listOf(mockObj2), 720, 1280)

    val trackInfo2 = results2[mockObj2]
    assertNotNull(trackInfo2)
    assertTrue("Object should be detected as moving, speed: ${trackInfo2?.relativeSpeed}", trackInfo2!!.isMoving)
    assertTrue("Motion label should indicate rightward movement, got: ${trackInfo2.motionLabel}", trackInfo2.motionLabel.contains("Right"))
    assertTrue("Relative speed should be positive", trackInfo2.relativeSpeed > 0.0f)
  }
}
