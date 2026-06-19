package com.blurr.voice.v2.llm

import android.content.Context
import android.util.Log
import com.blurr.voice.utilities.ApiKeyManager
import com.blurr.voice.v2.AgentOutput
import com.blurr.voice.v2.logging.TaskLogger
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * OpenRouter-backed replacement for the original v2 GeminiApi.
 * Same public interface — generateAgentOutput() + generateGroundedContent().
 * Uses the OpenAI-compatible /v1/chat/completions endpoint.
 */
class GeminiApi(
    private val modelName: String,
    private val apiKeyManager: ApiKeyManager, // kept for constructor compat, not used directly
    private val context: Context,
    private val maxRetry: Int = 3
) {
    companion object {
        private const val TAG = "OpenRouterV2Api"
        private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Main method — converts GeminiMessage list to OpenRouter request and parses AgentOutput.
     */
    suspend fun generateAgentOutput(messages: List<GeminiMessage>): AgentOutput? {
        val jsonString = retryWithBackoff(times = maxRetry) {
            performApiCall(messages)
        } ?: return null

        try {
            val input = jsonParser.encodeToString(messages)
            TaskLogger.log(context, input, jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log task: ${e.message}")
        }

        return try {
            Log.d(TAG, "Parsing JSON response: $jsonString")
            jsonParser.decodeFromString<AgentOutput>(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON into AgentOutput: ${e.message}", e)
            null
        }
    }

    private suspend fun performApiCall(messages: List<GeminiMessage>): String {
        val apiKey = ApiKeyManager.getOpenRouterKey(context)
        if (apiKey.isBlank()) {
            throw IOException("No OpenRouter API key configured. Please set it in Settings.")
        }

        val model = ApiKeyManager.getSelectedModel(context).ifBlank { modelName }

        // Build OpenAI-compatible messages array
        val messagesArray = JSONArray()
        messages.forEach { msg ->
            val openAiRole = when (msg.role) {
                MessageRole.MODEL -> "assistant"
                MessageRole.TOOL -> "tool"
                else -> "user"
            }
            val text = msg.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            if (text.isNotBlank()) {
                messagesArray.put(JSONObject().apply {
                    put("role", openAiRole)
                    put("content", text)
                })
            }
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            // Ask for JSON output so AgentOutput parsing works
            put("response_format", JSONObject().put("type", "json_object"))
        }

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
            if (!response.isSuccessful || body.isNullOrBlank()) {
                throw IOException("OpenRouter error ${response.code}: $body")
            }
            // Parse OpenAI response format
            val json = JSONObject(body)
            if (json.has("error")) {
                throw IOException("OpenRouter error: ${json.getJSONObject("error").optString("message")}")
            }
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    /** Compatibility stub — grounded search not available via OpenRouter, falls back to normal call */
    suspend fun generateGroundedContent(prompt: String): String? {
        val messages = listOf(GeminiMessage(prompt))
        return retryWithBackoff(times = maxRetry) { performApiCall(messages) }
    }
}

class ContentBlockedException(message: String) : Exception(message)

private suspend fun <T> retryWithBackoff(
    times: Int,
    initialDelay: Long = 1000L,
    maxDelay: Long = 16000L,
    factor: Double = 2.0,
    block: suspend () -> T
): T? {
    var currentDelay = initialDelay
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            Log.e("RetryUtil", "Attempt ${attempt + 1}/$times failed: ${e.message}", e)
            if (attempt == times - 1) {
                Log.e("RetryUtil", "All $times retry attempts failed.")
                return null
            }
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
    return null
}
