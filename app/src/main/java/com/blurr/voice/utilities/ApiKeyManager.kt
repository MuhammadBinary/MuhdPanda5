package com.blurr.voice.utilities

import android.content.Context

/**
 * Manages the OpenRouter API key and selected model.
 * Keys are stored in SharedPreferences so the user can update them from Settings.
 *
 * The old BuildConfig.GEMINI_API_KEYS rotation logic is kept as a stub for compatibility,
 * but real calls now go through getOpenRouterKey().
 */
object ApiKeyManager {

    private const val PREFS_NAME = "openrouter_prefs"
    private const val KEY_API_KEY = "openrouter_api_key"
    private const val KEY_MODEL = "openrouter_model"

    const val DEFAULT_MODEL = "openrouter/auto"

    /** Free models available on OpenRouter — shown in Settings dropdown */

    val FREE_MODELS = listOf(
        "openrouter/auto",
        "google/gemma-3-27b-it:free",
        "meta-llama/llama-3.1-8b-instruct:free",
        "mistralai/mistral-7b-instruct:free",
        "qwen/qwen3-30b-a3b:free",
        "deepseek/deepseek-r1:free",
        "microsoft/phi-3-mini-128k-instruct:free"
    )

    fun getOpenRouterKey(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "") ?: ""
    }

    fun saveOpenRouterKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun getSelectedModel(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun saveSelectedModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, model).apply()
    }

    /**
     * Legacy compatibility — the old code called getNextKey() in some places.
     * Returns empty string; actual key retrieval now requires a Context.
     */
    fun getNextKey(): String = ""
}
