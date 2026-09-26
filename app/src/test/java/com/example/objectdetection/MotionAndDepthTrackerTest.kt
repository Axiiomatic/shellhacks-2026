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

  @Test
  fun testSustainedCollisionApproach() {
    val tracker = MotionTracker()

    // Single frame expansion should NOT trigger collision alert immediately (prevents false positives)
    val objFrame1 = mock(DetectedObject::class.java)
    `when`(objFrame1.boundingBox).thenReturn(createRect(210, 490, 510, 790))
    `when`(objFrame1.trackingId).thenReturn(303)
    val res1 = tracker.processFrame(listOf(objFrame1), 720, 1280)
    assertFalse("Single frame expansion should not trigger collision alert", res1[objFrame1]!!.isRapidApproaching)

    // Symmetrical expansion around center over multiple frames triggers collision warning
    var lastRes: ObjectTrackInfo? = null
    var size = 300
    for (step in 1..6) {
      Thread.sleep(50)
      size += 80
      val halfSize = size / 2
      val objNext = mock(DetectedObject::class.java)
      `when`(objNext.boundingBox).thenReturn(createRect(360 - halfSize, 640 - halfSize, 360 + halfSize, 640 + halfSize))
      `when`(objNext.trackingId).thenReturn(303)
      val res = tracker.processFrame(listOf(objNext), 720, 1280)
      lastRes = res[objNext]
    }

    assertNotNull(lastRes)
    assertTrue("Sustained expansion should trigger collision alert", lastRes!!.isRapidApproaching)
    assertTrue("Motion label should indicate BUMP WARNING", lastRes.motionLabel.contains("BUMP WARNING"))
  }

  @Test
  fun testSideObjectIgnored() {
    val tracker = MotionTracker()

    // Object located far to the right side (normCenterX ~ 0.90) expanding rapidly
    var lastRes: ObjectTrackInfo? = null
    var size = 100
    for (step in 1..6) {
      Thread.sleep(50)
      size += 50
      val halfSize = size / 2
      val sideObj = mock(DetectedObject::class.java)
      // Centered at X = 650 (far right on 720 wide screen)
      `when`(sideObj.boundingBox).thenReturn(createRect(650 - halfSize, 640 - halfSize, minOf(720, 650 + halfSize), 640 + halfSize))
      `when`(sideObj.trackingId).thenReturn(404)
      val res = tracker.processFrame(listOf(sideObj), 720, 1280)
      lastRes = res[sideObj]
    }

    assertNotNull(lastRes)
    assertFalse("Expanding side object should NOT trigger bump warning", lastRes!!.isRapidApproaching)
  }

  @Test
  fun testDistantObjectIgnored() {
    val tracker = MotionTracker()

    // Centered object expanding while still small/distant (screen fraction < 0.34)
    var lastRes: ObjectTrackInfo? = null
    var size = 80
    for (step in 1..6) {
      Thread.sleep(50)
      size += 30 // Grows to 230px -> diagonal 325px / 1468px ~ 0.22 < 0.34
      val halfSize = size / 2
      val distantObj = mock(DetectedObject::class.java)
      `when`(distantObj.boundingBox).thenReturn(createRect(360 - halfSize, 640 - halfSize, 360 + halfSize, 640 + halfSize))
      `when`(distantObj.trackingId).thenReturn(505)
      val res = tracker.processFrame(listOf(distantObj), 720, 1280)
      lastRes = res[distantObj]
    }

    assertNotNull(lastRes)
    assertFalse("Distant expanding object should NOT trigger bump warning until close", lastRes!!.isRapidApproaching)
  }

  @Test
  fun testAngledApproachToDoorOrWall() {
    val tracker = MotionTracker()

    // Approaching a door or wall at an angle (expanding rapidly while shifting laterally)
    var lastRes: ObjectTrackInfo? = null
    var width = 280
    var height = 500
    var centerX = 380

    for (step in 1..8) {
      Thread.sleep(50)
      width += 30  // Grows to 520px (520/720 = 0.72 >= 0.45 width fraction)
      height += 45 // Grows to 860px (860/1280 = 0.67 >= 0.50 height fraction)
      centerX += 8 // Shifts laterally as camera approaches at an angle

      val halfW = width / 2
      val halfH = height / 2
      val doorObj = mock(DetectedObject::class.java)
      `when`(doorObj.boundingBox).thenReturn(createRect(centerX - halfW, 640 - halfH, centerX + halfW, 640 + halfH))
      `when`(doorObj.trackingId).thenReturn(606)

      val res = tracker.processFrame(listOf(doorObj), 720, 1280)
      lastRes = res[doorObj]
    }

    assertNotNull(lastRes)
    assertTrue("Angled approach to door/wall should trigger bump warning", lastRes!!.isRapidApproaching)
    assertTrue("Motion label should indicate BUMP WARNING", lastRes.motionLabel.contains("BUMP WARNING"))
  }
}
