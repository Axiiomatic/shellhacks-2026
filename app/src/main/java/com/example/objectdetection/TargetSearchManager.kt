package com.example.objectdetection

import java.util.Locale

object TargetSearchManager {
  @Volatile var targetObject: String? = null
  @Volatile var lastAlertTimeMs: Long = 0L
  const val ALERT_THROTTLE_MS = 4000L

  fun matchesTarget(label: String): Boolean {
    val target = targetObject?.trim()?.lowercase(Locale.US) ?: return false
    if (target.isBlank()) return false
    val cleanLabel = label.lowercase(Locale.US)

    // Direct match (e.g. searching "chair" matches "Chair" or "Office Chair")
    if (cleanLabel.contains(target) || target.contains(cleanLabel)) return true

    // Specific synonym / category groupings
    val synonyms = mapOf(
      "chair" to listOf("chair", "seat", "couch", "sofa", "stool", "furniture"),
      "table" to listOf("table", "desk", "counter", "furniture"),
      "bottle" to listOf("bottle", "cup", "mug", "glass", "container"),
      "keys" to listOf("keys", "key", "ring"),
      "phone" to listOf("phone", "device", "mobile"),
      "person" to listOf("person", "people", "human", "man", "woman"),
      "door" to listOf("door", "gateway", "entrance", "wall")
    )

    for ((key, values) in synonyms) {
      if (target.contains(key) && values.any { cleanLabel.contains(it) }) {
        return true
      }
    }

    return false
  }

  fun clear() {
    targetObject = null
    lastAlertTimeMs = 0L
  }
}
