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
import java.util.concurrent.TimeUnit

object GeminiApi {

    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val TAG = "OpenRouterApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateContent(
        chat: List<Pair<String, List<Any>>>,
        images: List<Bitmap> = emptyList(),
        modelName: String = "openrouter/auto",
        maxRetry: Int = 3,
        context: Context? = null
    ): String? {
        val ctx = context ?: MyApplication.appContext

        val apiKey = ApiKeyManager.getOpenRouterKey(ctx)
        if (apiKey.isBlank()) {
            Log.e(TAG, "No OpenRouter API key set. Open Settings and paste your key.")
            return null
        }

        val model = ApiKeyManager.getSelectedModel(ctx).ifBlank { modelName }
        val messagesArray = buildMessages(chat)

        Log.d(TAG, "Sending ${messagesArray.length()} messages to model: $model")

        var attempts = 0
        while (attempts < maxRetry) {
            attempts++
            try {
                val payload = JSONObject().apply {
                    put("model", model)
                    put("messages", messagesArray)
                }

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
                    Log.d(TAG, "HTTP ${response.code} — body: ${body?.take(300)}")

                    if (!response.isSuccessful || body.isNullOrBlank()) {
                        throw Exception("HTTP ${response.code}: $body")
                    }

                    val result = parseResponse(body)
                    Log.d(TAG, "Parsed result: ${result?.take(200)}")
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Attempt $attempts failed: ${e.message}")
                if (attempts < maxRetry) delay(1500L * attempts)
                else return null
            }
        }
        return null
    }

    /**
     * KEY FIX: The first message is the system prompt — send it as role="system".
     * All subsequent user/model messages map normally.
     */
    private fun buildMessages(chat: List<Pair<String, List<Any>>>): JSONArray {
        val array = JSONArray()
        chat.forEachIndexed { index, (role, parts) ->
            val text = parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            if (text.isBlank()) return@forEachIndexed

            // First message is always the system prompt — force it to "system" role
            val openAiRole = when {
                index == 0 -> "system"
                role.lowercase() == "model" -> "assistant"
                else -> "user"
            }

            array.put(JSONObject().apply {
                put("role", openAiRole)
                put("content", text)
            })
        }
        return array
    }

    private fun parseResponse(body: String): String? {
        return try {
            val json = JSONObject(body)
            if (json.has("error")) {
                Log.e(TAG, "API error: ${json.getJSONObject("error").optString("message")}")
                return null
            }
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            content
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed on: $body", e)
            null
        }
    }
}
