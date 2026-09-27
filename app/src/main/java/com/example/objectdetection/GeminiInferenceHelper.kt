package com.example.objectdetection

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

data class GeminiInferenceResult(
  val responseText: String?,
  val errorMessage: String? = null,
  val isSuccess: Boolean
)

class GeminiInferenceHelper(private val context: Context) {

  private val token = BuildConfig.GEMINI_API_KEY
  private val modelName = "gemini-3.1-flash-lite"
  private val isAnalyzing = AtomicBoolean(false)

  suspend fun analyzeFrame(
    bitmap: Bitmap,
    isBumpAlert: Boolean,
    sceneContext: String = "",
    recentOutputs: List<String> = emptyList(),
    cameraMotion: CameraMotionState = CameraMotionState.UNKNOWN
  ): GeminiInferenceResult = withContext(Dispatchers.IO) {
    if (!isAnalyzing.compareAndSet(false, true)) {
      Log.d(TAG, "Analysis already in flight, skipping frame.")
      return@withContext GeminiInferenceResult(null, "Analysis already in flight", false)
    }

    try {
      val downscaled = downscaleBitmap(bitmap, 768)
      val outputStream = ByteArrayOutputStream()
      downscaled.compress(Bitmap.CompressFormat.WEBP, 90, outputStream)
      val imageBytes = outputStream.toByteArray()
      val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
      val mimeType = "image/webp"

      if (downscaled != bitmap && !downscaled.isRecycled) {
        downscaled.recycle()
      }

      val historyText = if (recentOutputs.isEmpty()) {
        "Recent spoken guidance: none."
      } else {
        "Recent spoken guidance:\n" + recentOutputs.joinToString("\n") { "- $it" }
      }
      val bumpInstruction = if (isBumpAlert) {
        "An immediate collision risk is detected; give the urgent action first."
      } else {
        "Do not use urgent wording unless there is an immediate collision risk."
      }
      val modeInstruction = if (cameraMotion.isMoving) {
        "The user is moving: focus on obstacles likely to matter within 5 seconds, tripping hazards, curbs, stairs, and clear directional navigation. Skip general scenery unless it affects route safety."
      } else {
        "The user is stationary: focus on understanding the environment, such as whether it is open or enclosed, the setting type, surfaces, stable landmarks, and useful spatial context. Add a new environmental detail instead of repeating recent guidance."
      }
      val responseLengthInstruction = when {
        isBumpAlert -> "Urgent updates may use up to 24 words."
        cameraMotion.isMoving -> "Keep moving-mode updates to 12 words or fewer."
        else -> "Stationary-mode environmental updates may use up to 18 words."
      }
      val promptText = """
        You are a concise navigation assistant for a person using a camera.
        The request and response have about 5 seconds of latency. Treat this image and reference data as the starting point of a 5-second forecast.
        Predict what will be relevant within the next 5 seconds, not only what is relevant now.
        Prioritize hazards likely to enter the travel path, objects approaching quickly, narrowing safe paths, and actions needed before they become immediate.
        Use object motion, approach rate, time-to-collision, distance, path position, and camera motion from the reference data as evidence.
        Do not warn about a static, distant, or off-path object unless its predicted motion makes it relevant within 5 seconds.
        State uncertainty conservatively; never invent motion or future events not supported by the image or reference data.
        $modeInstruction
        Return one useful spoken update. $responseLengthInstruction Never return SILENT.
        Use recent spoken guidance to avoid repeating unchanged details. When the scene repeats, provide a different useful environmental or contextual detail rather than restating the same answer.
        Do not say STOP IMMEDIATELY unless there is an immediate collision risk.
        $bumpInstruction
        Camera motion: ${cameraMotion.promptDescription}
        $sceneContext
        $historyText
      """.trimIndent()

      val jsonBody = JSONObject().apply {
        put("contents", JSONArray().put(
          JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", promptText))
            .put(JSONObject().put("inline_data", JSONObject()
              .put("mime_type", mimeType)
              .put("data", base64Image)
            ))
          )
        ))
      }

      val endpointUrl = "https://generativelanguage.googleapis.com/v1/models/$modelName:generateContent?key=$token"
      Log.d(TAG, "Connecting to Generative Language API (v1) with model $modelName...")

      val url = URL(endpointUrl)
      val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("x-goog-api-key", token)
        doOutput = true
        connectTimeout = 20000
        readTimeout = 20000
      }

      connection.outputStream.use { os ->
        val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
        os.write(input, 0, input.size)
      }

      val responseCode = connection.responseCode
      Log.d(TAG, "Generative Language API v1 response code: $responseCode")

      if (responseCode == HttpURLConnection.HTTP_OK) {
        val responseString = connection.inputStream.bufferedReader().use { it.readText() }
        val jsonResponse = JSONObject(responseString)
        val candidates = jsonResponse.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
          val candidate = candidates.getJSONObject(0)
          val content = candidate.optJSONObject("content")
          val parts = content?.optJSONArray("parts")
          if (parts != null && parts.length() > 0) {
            val part = parts.getJSONObject(0)
            val text = part.optString("text", "").trim()
            val maxWords = when {
              isBumpAlert -> 24
              cameraMotion.isMoving -> 12
              else -> 18
            }
            val spokenResponse = ensureSpokenResponse(truncateToWords(text, maxWords), isBumpAlert, cameraMotion)
            Log.d(TAG, "Gemini API connected successfully! Response: $text")
            if (spokenResponse.isNotBlank()) {
              showToast("Gemini: $spokenResponse")
            }
            return@withContext GeminiInferenceResult(
              spokenResponse,
              null,
              true
            )
          }
        }
        return@withContext GeminiInferenceResult(null, "No text candidates returned", false)
      } else {
        val errorString = try {
          connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
        } catch (ex: Exception) {
          "Could not read error stream: ${ex.message}"
        }
        Log.e(TAG, "Generative Language API v1 error response ($responseCode): $errorString")
        showErrorDialog("Gemini Error $responseCode", errorString)
        return@withContext GeminiInferenceResult(null, "HTTP $responseCode: $errorString", false)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Gemini API connection failed: ${e.message}", e)
      showErrorDialog("Gemini Exception", e.localizedMessage ?: e.javaClass.simpleName)
      return@withContext GeminiInferenceResult(null, e.localizedMessage ?: e.javaClass.simpleName, false)
    } finally {
      isAnalyzing.set(false)
    }
  }

  private fun downscaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= maxDim && height <= maxDim) return bitmap
    val ratio = width.toFloat() / height.toFloat()
    val newWidth: Int
    val newHeight: Int
    if (width > height) {
      newWidth = maxDim
      newHeight = (maxDim / ratio).toInt()
    } else {
      newHeight = maxDim
      newWidth = (maxDim * ratio).toInt()
    }
    return Bitmap.createScaledBitmap(bitmap, max(newWidth, 1), max(newHeight, 1), true)
  }

  private fun truncateToWords(text: String?, maxWords: Int): String? {
    if (text.isNullOrEmpty()) return null
    val cleanText = text.replace("\n", " ")
    val words = cleanText.split("\\s+".toRegex())
    if (words.size <= maxWords) return cleanText
    return words.take(maxWords).joinToString(" ")
  }

  private fun ensureSpokenResponse(text: String?, isBumpAlert: Boolean, cameraMotion: CameraMotionState): String {
    if (!text.isNullOrBlank() && !text.trim().trimEnd('.', '!', ' ').equals("SILENT", ignoreCase = true)) {
      return text
    }
    return when {
      isBumpAlert -> "Obstacle ahead; move carefully."
      cameraMotion.isMoving -> "Camera moving; reassessing nearby obstacles."
      else -> "No new hazards detected; continue carefully."
    }
  }

  private fun showToast(message: String) {
    try {
      Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error showing toast", e)
    }
  }

  private fun showErrorDialog(title: String, message: String) {
    try {
      Handler(Looper.getMainLooper()).post {
        AlertDialog.Builder(context)
          .setTitle(title)
          .setMessage(message)
          .setPositiveButton("OK", null)
          .show()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error showing error dialog", e)
    }
  }

  companion object {
    private const val TAG = "GeminiInferenceHelper"
  }
}
