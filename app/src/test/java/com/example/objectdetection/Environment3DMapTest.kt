package com.example.objectdetection

import android.content.Context
import android.graphics.Rect
import com.google.mlkit.vision.objects.DetectedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class Environment3DMapTest {

  @Test
  fun testPoint3DDistanceCalculation() {
    val point = Point3D(x = 3.0f, y = 4.0f, z = 12.0f)
    // sqrt(3^2 + 4^2 + 12^2) = sqrt(9 + 16 + 144) = sqrt(169) = 13.0
    assertEquals(13.0f, point.distanceMeters, 0.001f)
  }

  @Test
  fun testSpatial3DObjectPromptFormatting() {
    val spatialObj = Spatial3DObject(
      trackingId = 1,
      label = "Chair",
      confidence = 0.9f,
      boundingBox2D = Rect(100, 100, 300, 300),
      position3D = Point3D(x = -0.4f, y = -0.2f, z = 1.8f),
      estimatedWidthMeters = 0.5f,
      estimatedHeightMeters = 0.8f,
      estimatedDepthMeters = 0.5f,
      velocity3D = Point3D(x = 0.0f, y = 0.0f, z = -0.3f),
      timeToCollisionSec = 6.0f,
      motionLabel = "Approaching",
      isHazard = true
    )

    assertEquals(1.8547f, spatialObj.distanceMeters, 0.01f)
    val promptStr = spatialObj.toSpatialPromptString()

    assertTrue("Prompt should contain object label 'Chair'", promptStr.contains("Chair"))
    assertTrue("Prompt should specify left direction", promptStr.contains("on your left"))
    assertTrue("Prompt should highlight collision hazard", promptStr.contains("Hazard!"))
  }

  @Test
  fun testEnvironment3DMapGeminiPromptBuilder() {
    val pointCloud = listOf(
      Point3D(0.1f, -0.2f, 1.5f),
      Point3D(-0.3f, 0.4f, 2.1f)
    )

    val plane = Plane3D(
      id = "floor_1",
      type = Plane3D.PlaneType.HORIZONTAL_UPWARD_FACING,
      centerX = 0.0f,
      centerY = -1.2f,
      centerZ = 2.0f,
      extentX = 3.0f,
      extentZ = 4.0f
    )

    val spatialObj = Spatial3DObject(
      trackingId = 10,
      label = "Table",
      confidence = 0.85f,
      boundingBox2D = Rect(200, 200, 500, 500),
      position3D = Point3D(x = 0.5f, y = 0.0f, z = 2.2f),
      estimatedWidthMeters = 1.0f,
      estimatedHeightMeters = 0.8f,
      estimatedDepthMeters = 0.8f,
      velocity3D = Point3D(0f, 0f, 0f),
      timeToCollisionSec = Float.MAX_VALUE,
      motionLabel = "Stationary",
      isHazard = false
    )

    val map = Environment3DMap(
      tracked3DObjects = listOf(spatialObj),
      pointCloud3D = pointCloud,
      detectedPlanes = listOf(plane),
      isArCoreTrackingActive = true,
      trackingStateSummary = "ARCore Active"
    )

    val contextText = map.buildGemini3DSceneContext()

    assertTrue("Context should indicate AR Tracking mode", contextText.contains("Active 3D Spatial SLAM"))
    assertTrue("Context should describe tracked 3D object 'Table'", contextText.contains("Table"))
    assertTrue("Context should describe directional proximity", contextText.contains("nearby"))
    assertTrue("Context should describe side direction", contextText.contains("on your right"))
  }

  @Test
  fun testArEnvironmentMapperFallbackMapping() {
    val mockContext = mock(Context::class.java)
    val mapper = ArEnvironmentMapper(mockContext)

    val mockObj = mock(DetectedObject::class.java)
    val mockLabel = mock(DetectedObject.Label::class.java)
    `when`(mockLabel.text).thenReturn("Person")
    `when`(mockLabel.confidence).thenReturn(0.92f)
    `when`(mockObj.labels).thenReturn(listOf(mockLabel))
    `when`(mockObj.boundingBox).thenReturn(Rect(200, 300, 500, 900))

    val trackInfoMap = mapOf(
      mockObj to ObjectTrackInfo(
        trackingId = 42,
        relativeSpeed = 0.15f,
        relativeScaleGrowth = 0.2f,
        isRapidApproaching = false,
        motionDirection = MotionDirection.APPROACHING,
        motionLabel = "Getting Closer",
        displacementX = 5f,
        displacementY = 0f,
        isMoving = true
      )
    )

    val map = mapper.mapEnvironment(
      detectedObjects = listOf(mockObj),
      trackInfoMap = trackInfoMap,
      frameWidth = 720,
      frameHeight = 1280
    )

    assertNotNull(map)
    assertEquals(1, map.tracked3DObjects.size)

    val obj3D = map.tracked3DObjects.first()
    assertEquals("Person", obj3D.label)
    assertTrue("Object depth Z should be positive", obj3D.position3D.z > 0.1f)
    assertTrue("3D Map point cloud fallback should be generated", map.pointCloud3D.isNotEmpty())
  }
}
