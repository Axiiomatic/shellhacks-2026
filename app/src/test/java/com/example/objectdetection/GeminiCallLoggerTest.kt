package com.example.objectdetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiCallLoggerTest {

  @Before
  fun setUp() {
    GeminiCallLogger.clearLogs()
  }

  @Test
  fun testLogCallAndRetrieval() {
    assertTrue(GeminiCallLogger.getLogs().isEmpty())

    GeminiCallLogger.logCall(
      triggerType = "Periodic",
      sceneContext = "Scene: trash can",
      responseText = "Watch out for trash can",
      durationMs = 1200L,
      intervalMs = 10000L,
      isSuccess = true
    )

    val logs = GeminiCallLogger.getLogs()
    assertEquals(1, logs.size)

    val entry = logs[0]
    assertEquals("Periodic", entry.triggerType)
    assertEquals("Watch out for trash can", entry.responseText)
    assertEquals(1200L, entry.durationMs)
    assertEquals(10000L, entry.intervalMs)
    assertTrue(entry.isSuccess)
    assertNull(entry.errorMessage)
  }

  @Test
  fun testNewestLogsFirst() {
    GeminiCallLogger.logCall(
      triggerType = "Periodic",
      sceneContext = "First call",
      responseText = "Call 1",
      durationMs = 1000L,
      intervalMs = null,
      isSuccess = true
    )

    GeminiCallLogger.logCall(
      triggerType = "Bump Alert",
      sceneContext = "Second call",
      responseText = "Call 2",
      durationMs = 800L,
      intervalMs = 5000L,
      isSuccess = true
    )

    val logs = GeminiCallLogger.getLogs()
    assertEquals(2, logs.size)
    assertEquals("Call 2", logs[0].responseText)
    assertEquals("Call 1", logs[1].responseText)
  }

  @Test
  fun testClearLogs() {
    GeminiCallLogger.logCall(
      triggerType = "Periodic",
      sceneContext = "Test",
      responseText = "Response",
      durationMs = 500L,
      intervalMs = 10000L,
      isSuccess = true
    )

    assertEquals(1, GeminiCallLogger.getLogs().size)
    GeminiCallLogger.clearLogs()
    assertTrue(GeminiCallLogger.getLogs().isEmpty())
  }
}
