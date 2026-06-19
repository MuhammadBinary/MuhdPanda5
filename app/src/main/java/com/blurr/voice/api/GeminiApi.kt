package com.blurr.voice.api

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.blurr.voice.MyApplication
import com.blurr.voice.utilities.ApiKeyManager
import com.google.ai.client.generativeai.type.ImagePart
import com.google.ai.client.generativeai.type.TextPart
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * OpenRouter-backed AI API.
 * Drop-in replacement for the original GeminiApi.
 * - Reads the user's OpenRouter API key from SharedPreferences (set in Settings).
 * - Uses OpenAI-compatible /v1/chat/completions endpoint.
 * - Model is also user-configurable from Settings.
 */
object GeminiApi {

    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val TAG = "OpenRouterApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Main entry point — same signature as before so everything else compiles unchanged.
     * modelName is ignored when user has set a model in Settings (Settings model takes priority).
     */
    suspend fun generateContent(
        chat: List<Pair<String, List<Any>>>,
        images: List<Bitmap> = emptyList(),
        modelName: String = "google/gemma-3-27b-it:free",
        maxRetry: Int = 3,
        context: Context? = null
    ): String? {
        val ctx = context ?: MyApplication.appContext

        val apiKey = ApiKeyManager.getOpenRouterKey(ctx)
        if (apiKey.isBlank()) {
            Log.e(TAG, "No OpenRouter API key set. Open Settings and paste your key.")
            return null
        }

        // Use the model saved in settings, falling back to the passed-in modelName
        val model = ApiKeyManager.getSelectedModel(ctx).ifBlank { modelName }

        val messagesArray = buildOpenAiMessages(chat)

        var attempts = 0
        while (attempts < maxRetry) {
            attempts++
            Log.d(TAG, "Request attempt $attempts — model: $model")

            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messagesArray)
            }

            try {
                val request = Request.Builder()
                    .url(OPENROUTER_URL)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "com.blurr.voice")
                    .addHeader("X-Title", "Blurr AI")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    Log.d(TAG, "HTTP ${response.code}")

                    if (!response.isSuccessful || body.isNullOrBlank()) {
                        Log.e(TAG, "API error ${response.code}: $body")
                        throw Exception("API Error ${response.code}: $body")
                    }

                    val result = parseOpenAiResponse(body)
                    saveLogToFile(ctx, "Attempt $attempts | model=$model | response=${result?.take(200)}")
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Attempt $attempts failed: ${e.message}")
                saveLogToFile(ctx, "Attempt $attempts ERROR: ${e.message}")
                if (attempts < maxRetry) {
                    delay(1000L * attempts)
                } else {
                    Log.e(TAG, "All $maxRetry retries failed.")
                    return null
                }
            }
        }
        return null
    }

    /** Convert Gemini-style chat history to OpenAI messages array */
    private fun buildOpenAiMessages(chat: List<Pair<String, List<Any>>>): JSONArray {
        val array = JSONArray()
        chat.forEach { (role, parts) ->
            // Map "model" role (Gemini) → "assistant" (OpenAI)
            val openAiRole = if (role.lowercase() == "model") "assistant" else role.lowercase()
            val textContent = parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            if (textContent.isNotBlank()) {
                array.put(JSONObject().apply {
                    put("role", openAiRole)
                    put("content", textContent)
                })
            }
        }
        return array
    }

    /** Parse OpenAI-compatible response */
    private fun parseOpenAiResponse(body: String): String? {
        return try {
            val json = JSONObject(body)
            // Handle error field
            if (json.has("error")) {
                val errorMsg = json.getJSONObject("error").optString("message", "Unknown error")
                Log.e(TAG, "API returned error: $errorMsg")
                return null
            }
            val choices = json.getJSONArray("choices")
            if (choices.length() == 0) return null
            choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: $body", e)
            // Return raw body as fallback (some models may return plain text)
            body
        }
    }

    private fun saveLogToFile(context: Context, logEntry: String) {
        try {
            val logDir = File(context.filesDir, "openrouter_logs")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "api_log.txt")
            FileWriter(logFile, true).use { writer ->
                writer.append("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())} | $logEntry\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save log", e)
        }
    }
}
