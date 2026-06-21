package com.blurr.voice.v2.llm

import android.content.Context
import android.util.Log
import com.blurr.voice.utilities.ApiKeyManager
import com.blurr.voice.v2.AgentOutput
import com.blurr.voice.v2.logging.TaskLogger
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiApi(
    private val modelName: String,
    private val apiKeyManager: ApiKeyManager,
    private val context: Context,
    private val maxRetry: Int = 3
) {
    companion object {
        private const val TAG = "OpenRouterV2Api"
        private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun generateAgentOutput(messages: List<GeminiMessage>): AgentOutput? {
        val rawContent = retryWithBackoff(times = maxRetry) {
            performApiCall(messages)
        } ?: run {
            Log.e(TAG, "All retries failed, giving up.")
            return null
        }

        // Extract clean JSON — models sometimes wrap in ```json ... ``` blocks
        val jsonString = extractJson(rawContent)
        Log.d(TAG, "Extracted JSON: ${jsonString.take(300)}")

        try {
            val input = jsonParser.encodeToString(messages)
            TaskLogger.log(context, input, jsonString)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log task: ${e.message}")
        }

        return try {
            jsonParser.decodeFromString<AgentOutput>(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse AgentOutput from: $jsonString", e)
            null
        }
    }

    private suspend fun performApiCall(messages: List<GeminiMessage>): String {
        val apiKey = ApiKeyManager.getOpenRouterKey(context)
        if (apiKey.isBlank()) throw IOException("No OpenRouter API key set in Settings.")

        val model = ApiKeyManager.getSelectedModel(context).ifBlank { modelName }

        val messagesArray = JSONArray()
        messages.forEachIndexed { index, msg ->
            val text = msg.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            if (text.isBlank()) return@forEachIndexed

            // First message is always the system prompt
            val role = when {
                index == 0 -> "system"
                msg.role == MessageRole.MODEL -> "assistant"
                else -> "user"
            }
            messagesArray.put(JSONObject().apply {
                put("role", role)
                put("content", text)
            })
        }

        // Do NOT send response_format — most free models don't support it and return errors
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
        }

        Log.d(TAG, "Calling OpenRouter model=$model with ${messagesArray.length()} messages")

        val request = Request.Builder()
            .url(OPENROUTER_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "com.blurr.voice")
            .addHeader("X-Title", "Blurr AI v2")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            Log.d(TAG, "HTTP ${response.code}: ${body?.take(200)}")
            if (!response.isSuccessful || body.isNullOrBlank()) {
                throw IOException("HTTP ${response.code}: $body")
            }
            val json = JSONObject(body)
            if (json.has("error")) {
                throw IOException("API error: ${json.getJSONObject("error").optString("message")}")
            }
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    /**
     * Strips markdown code fences that some models add around JSON.
     * e.g. ```json { ... } ``` → { ... }
     */
    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        // Remove ```json ... ``` or ``` ... ``` wrappers
        val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```")
        val match = fenceRegex.find(trimmed)
        if (match != null) return match.groupValues[1].trim()
        // Find first { and last } as fallback
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) trimmed.substring(start, end + 1)
        else trimmed
    }

    suspend fun generateGroundedContent(prompt: String): String? {
        val messages = listOf(GeminiMessage(prompt))
        return retryWithBackoff(times = maxRetry) { performApiCall(messages) }
    }
}

class ContentBlockedException(message: String) : Exception(message)

private suspend fun <T> retryWithBackoff(
    times: Int,
    initialDelay: Long = 1500L,
    maxDelay: Long = 16000L,
    factor: Double = 2.0,
    block: suspend () -> T
): T? {
    var currentDelay = initialDelay
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            Log.e("RetryUtil", "Attempt ${attempt + 1}/$times failed: ${e.message}")
            if (attempt == times - 1) return null
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
    return null
}
