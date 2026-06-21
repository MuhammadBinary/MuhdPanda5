package com.blurr.voice.utilities

import android.util.Log

class FreemiumManager {

    companion object {
        const val DAILY_TASK_LIMIT = 999
        private const val PRO_SKU = "pro"
    }

    suspend fun canPerformTask(): Boolean = true  // No Firebase auth needed — always allow

    suspend fun isUserSubscribed(): Boolean = true

    suspend fun getDeveloperMessage(): String = ""

    suspend fun getTasksRemaining(): Long = Long.MAX_VALUE

    suspend fun decrementTaskCount() { /* no-op */ }

    suspend fun provisionUserIfNeeded() { /* no-op */ }
}
