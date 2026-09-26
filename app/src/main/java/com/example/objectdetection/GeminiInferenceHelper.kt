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
  private val modelName = "gemini-3.5-flash-lite"
  private val isAnalyzing = AtomicBoolean(false)

  suspend fun analyzeFrame(
    bitmap: Bitmap,
    isBumpAlert: Boolean,
    sceneContext: String = "",
    recentOutputs: List<String> = emptyList()
  ): GeminiInferenceResult = withContext(Dispatchers.IO) {
    if (!isAnalyzing.compareAndSet(false, true)) {
      Log.d(TAG, "Analysis already in flight, skipping frame.")
      return@withContext GeminiInferenceResult(null, "Analysis already in flight", false)
    }

    try {
      val downscaled = downscaleBitmap(bitmap, 512)
      val outputStream = ByteArrayOutputStream()
      downscaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
      val imageBytes = outputStream.toByteArray()
      val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

      if (downscaled != bitmap && !downscaled.isRecycled) {
        downscaled.recycle()
      }

      val historyText = if (recentOutputs.isEmpty()) {
        "Recent Gemini guidance: none."
      } else {
        "Recent Gemini guidance:\n" + recentOutputs.joinToString("\n") { "- $it" }
      }
      val promptText = "You are a navigational assistant, helping a blind user walking through the environment. This is a photo of the camera view that the user sees. Analyze the most relevant objects in the image in terms of walking navigation, then give the user verbal directions with the most relevant information. Keep the description of the world short, limiting the response to a sentence or two of landmarks and navigational info. For example, if the photo depicts a trash can approaching the left side of the camera view, warn the user \"Watch out for a trash can slightly on your left.\" \n$sceneContext\n$historyText"


      val jsonBody = JSONObject().apply {
        put("contents", JSONArray().put(
          JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", promptText))
            .put(JSONObject().put("inline_data", JSONObject()
              .put("mime_type", "image/jpeg")
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
            Log.d(TAG, "Gemini API connected successfully! Response: $text")
            if (text.isNotBlank()) {
              showToast("Gemini: $text")
            }
            return@withContext GeminiInferenceResult(truncateTo20Words(text), null, true)
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

  private fun truncateTo20Words(text: String?): String? {
    if (text.isNullOrEmpty()) return null
    val cleanText = text.replace("\n", " ")
    val words = cleanText.split("\\s+".toRegex())
    if (words.size <= 20) return cleanText
    return words.take(20).joinToString(" ")
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
