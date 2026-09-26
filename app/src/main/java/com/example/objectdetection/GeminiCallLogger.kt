package com.example.objectdetection

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GeminiCallLog(
  val id: Long,
  val timestampMs: Long,
  val formattedTime: String,
  val triggerType: String,
  val sceneContext: String,
  val responseText: String?,
  val durationMs: Long?,
  val intervalMs: Long?,
  val isSuccess: Boolean,
  val errorMessage: String? = null
)

object GeminiCallLogger {
  private val logs = mutableListOf<GeminiCallLog>()
  private var idCounter = 1L
  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

  @Synchronized
  fun logCall(
    triggerType: String,
    sceneContext: String,
    responseText: String?,
    durationMs: Long?,
    intervalMs: Long?,
    isSuccess: Boolean,
    errorMessage: String? = null
  ) {
    val timestamp = System.currentTimeMillis()
    val formattedTime = dateFormat.format(Date(timestamp))
    val log = GeminiCallLog(
      id = idCounter++,
      timestampMs = timestamp,
      formattedTime = formattedTime,
      triggerType = triggerType,
      sceneContext = sceneContext,
      responseText = responseText,
      durationMs = durationMs,
      intervalMs = intervalMs,
      isSuccess = isSuccess,
      errorMessage = errorMessage
    )
    logs.add(0, log)
  }

  @Synchronized
  fun getLogs(): List<GeminiCallLog> {
    return ArrayList(logs)
  }

  @Synchronized
  fun clearLogs() {
    logs.clear()
  }
}
