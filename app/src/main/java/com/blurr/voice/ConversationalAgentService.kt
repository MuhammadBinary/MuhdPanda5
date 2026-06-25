package com.blurr.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blurr.voice.api.Eyes
//import com.blurr.voice.services.AgentTaskService
import com.blurr.voice.utilities.SpeechCoordinator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.graphics.toColorInt
import com.blurr.voice.agents.ClarificationAgent
import com.blurr.voice.utilities.TTSManager
import com.blurr.voice.utilities.addResponse
import com.blurr.voice.utilities.getReasoningModelApiResponse
import com.blurr.voice.utilities.FreemiumManager
import com.blurr.voice.overlay.OverlayManager
import com.blurr.voice.overlay.OverlayDispatcher
import com.blurr.voice.utilities.PandaState
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.VisualFeedbackManager
import com.blurr.voice.v2.AgentService
import com.blurr.voice.data.UserMemory
import com.google.ai.client.generativeai.type.TextPart
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.blurr.voice.utilities.ServicePermissionManager
import com.blurr.voice.utilities.PandaStateManager
import com.blurr.voice.v2.perception.Perception
import com.blurr.voice.v2.perception.SemanticParser
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

data class ModelDecision(
    val type: String = "Reply",
    val reply: String,
    val instruction: String = "",
    val shouldEnd: Boolean = false
)

class ConversationalAgentService : Service() {

    private val speechCoordinator by lazy { SpeechCoordinator.getInstance(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var conversationHistory = listOf<Pair<String, List<Any>>>()
    private val ttsManager by lazy { TTSManager.getInstance(this) }
    private val overlayManager by lazy { OverlayManager.getInstance(this) }
    private val clarificationQuestionViews = mutableListOf<View>()
    private var transcriptionView: TextView? = null
    private val visualFeedbackManager by lazy { VisualFeedbackManager.getInstance(this) }
    private val pandaStateManager by lazy { PandaStateManager.getInstance(this) }
    private var isTextModeActive = false
    private val freemiumManager by lazy { FreemiumManager() }
    private val servicePermissionManager by lazy { ServicePermissionManager(this) }

    private var clarificationAttempts = 0
    private val maxClarificationAttempts = 1
    private var sttErrorAttempts = 0
    private val maxSttErrorAttempts = 2

    private val clarificationAgent = ClarificationAgent()
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var cachedMemories = listOf<UserMemory>()
    private var hasHeardFirstUtterance = false
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private lateinit var perception: Perception
    private val client = OkHttpClient()

    
    // Firebase instances for conversation tracking
    private val db by lazy { try { Firebase.firestore } catch (e: Exception) { null } }
    private val auth by lazy { try { Firebase.auth } catch (e: Exception) { null } }
    private var conversationId: String? = null // Track current conversation session


    companion object {
        const val NOTIFICATION_ID = 3
        const val CHANNEL_ID = "ConversationalAgentChannel"
        const val ACTION_STOP_SERVICE = "com.blurr.voice.ACTION_STOP_SERVICE"
        var isRunning = false
        const val MEMORY_ENABLED = true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate() {
        super.onCreate()
        Log.d("ConvAgent", "Service onCreate")
        
        // Initialize Firebase Analytics
        try { firebaseAnalytics = Firebase.analytics } catch (e: Exception) { android.util.Log.w("ConvAgent", "Firebase Analytics unavailable: ${e.message}") }
        
        // Track service creation
        try { firebaseAnalytics.logEvent("conversational_agent_started", null) } catch (_: Exception) {}
        
        isRunning = true
        createNotificationChannel()
        initializeConversation()
        clarificationAttempts = 0 // Reset clarification attempts counter
        sttErrorAttempts = 0 // Reset STT error attempts counter
        // usedMemories.clear() // Removed
        hasHeardFirstUtterance = false // Reset first utterance flag

        fetchMemories() // Start async memory fetch

        OverlayDispatcher.clearAll()
        overlayManager.startObserving()
        visualFeedbackManager.showSpeakingOverlay() // <-- ADD THIS LINE
        visualFeedbackManager.showTtsWave()

        showInputBoxIfNeeded()
        visualFeedbackManager.showSmallDeltaGlow()

        // Start state monitoring and set initial state
        pandaStateManager.startMonitoring()
        pandaStateManager.setState(PandaState.IDLE)


    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun showInputBoxIfNeeded() {
        visualFeedbackManager.showInputBox(
            onActivated = {
                // This is called when the user taps the EditText
                enterTextMode()
            },
            onSubmit = { submittedText ->
                // This is the existing callback for when text is submitted
                processUserInput(submittedText)
            },
            onOutsideTap = {
                serviceScope.launch {
                    instantShutdown()
                }
            }
        )
    }

    /**
     * Call this when the user starts interacting with the text input.
     * It stops any ongoing voice interaction.
     */
    private fun enterTextMode() {
        if (isTextModeActive) return
        Log.d("ConvAgent", "Entering Text Mode. Stopping STT/TTS.")
        
        // Track text mode activation
        try { firebaseAnalytics.logEvent("text_mode_activated", null) } catch (_: Exception) {}
        
        isTextModeActive = true
        pandaStateManager.setState(PandaState.IDLE)
        speechCoordinator.stopListening()
        speechCoordinator.stopSpeaking()
        // Optionally hide the transcription view since user is typing
        visualFeedbackManager.hideTranscription()
    }


    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ConvAgent", "Service onStartCommand")

        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.i("ConvAgent", "Received stop action. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Check if we have the required RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("ConvAgent", "RECORD_AUDIO permission not granted. Cannot start foreground service.")
            Toast.makeText(this, "Microphone permission required for voice assistant", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: SecurityException) {
            serviceScope.launch {
                speechCoordinator.speakText("Hello, please give microphone permission or some other type of permission you have not given me! My code is open source, so you can check that out if you want!")
                delay(2000) // Wait for TTS to complete before closing service
                stopSelf()
            }
            Log.e("ConvAgent", "Failed to start foreground service: ${e.message}")
            Toast.makeText(this, "Cannot start voice assistant - permission missing", Toast.LENGTH_LONG).show()
            return START_NOT_STICKY
        }

        if (!servicePermissionManager.isMicrophonePermissionGranted()) {
            Log.e("ConvAgent", "RECORD_AUDIO permission not granted. Shutting down.")
            serviceScope.launch {
                ttsManager.speakText(getString(R.string.microphone_permission_not_granted))
                delay(2000)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        // Track conversation initiation
        try { firebaseAnalytics.logEvent("conversation_initiated", null) } catch (_: Exception) {}
        trackConversationStart()

        // Skip greeting and start listening immediately
        serviceScope.launch {
            Log.d("ConvAgent", "Starting immediate listening (no greeting)")
            pandaStateManager.setState(PandaState.LISTENING)
            startImmediateListening()
        }
        return START_STICKY
    }

    /**
     * Gets a personalized greeting using the user's name from memories if available
     * NOTE: This method is kept for potential future use but no longer called on startup
     */
    private fun getPersonalizedGreeting(): String {
        try {
            val userProfile = UserProfileManager(this@ConversationalAgentService)
            Log.d("ConvAgent", "No name found in memories, using generic greeting")
            return "Hey ${userProfile.getName()}!"
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error getting personalized greeting", e)
            return "Hey!"
        }
    }

    /**
     * Starts listening immediately without speaking any greeting or performing memory extraction
     * Memory extraction will be deferred until after the first user utterance
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun startImmediateListening() {
        Log.d("ConvAgent", "Starting immediate listening without greeting")
        
        // Check if we are in text mode before starting to listen
        if (isTextModeActive) {
            Log.d("ConvAgent", "In text mode, ensuring input box is visible and skipping voice listening.")
            mainHandler.post {
                showInputBoxIfNeeded() // Re-show the input box for the next turn.
            }
            return // Skip starting the voice listener entirely.
        }



        speechCoordinator.startListening(
            onResult = { recognizedText ->
                if (isTextModeActive) return@startListening // Ignore results in text mode
                Log.d("ConvAgent", "Final user transcription: $recognizedText")
                pandaStateManager.setState(PandaState.PROCESSING)
                visualFeedbackManager.updateTranscription(recognizedText)
                mainHandler.postDelayed({
                    visualFeedbackManager.hideTranscription()
                }, 500)

                
                processUserInput(recognizedText)
            },
            onError = { error ->
                Log.e("ConvAgent", "STT Error: $error")
                if (isTextModeActive) return@startListening // Ignore errors in text mode

                if (error == "No speech match") {
                    Log.d("ConvAgent", "No speech match detected. Silently resetting to IDLE.")
                    visualFeedbackManager.hideTranscription()
                    pandaStateManager.setState(PandaState.IDLE)
                    // We return early so we don't trigger the "I didn't catch that" logic
                    return@startListening
                }
                // Trigger error state in state manager
                pandaStateManager.triggerErrorState()

                // Track STT errors
                val sttErrorBundle = android.os.Bundle().apply {
                    putString("error_message", error.take(100))
                    putInt("error_attempt", sttErrorAttempts + 1)
                    putInt("max_attempts", maxSttErrorAttempts)
                }
                try { firebaseAnalytics.logEvent("stt_error", sttErrorBundle) } catch (_: Exception) {}
                
                visualFeedbackManager.hideTranscription()
                sttErrorAttempts++
                serviceScope.launch {
                    if (sttErrorAttempts >= maxSttErrorAttempts) {
                        try { firebaseAnalytics.logEvent("conversation_ended_stt_errors", null) } catch (_: Exception) {}
                        val exitMessage = "I'm having trouble understanding you clearly. Please try calling later!"
                        trackMessage("model", exitMessage, "error_message")
                        gracefulShutdown(exitMessage, "stt_errors")
                    } else {
                        val retryMessage = "I'm sorry, I didn't catch that. Could you please repeat?"
                        speakAndThenListen(retryMessage)
                    }
                }
            },
            onPartialResult = { partialText ->
                if (isTextModeActive) return@startListening // Ignore partial results in text mode
                visualFeedbackManager.updateTranscription(partialText)
            },
            onListeningStateChange = { listening ->
                Log.d("ConvAgent", "Listening state: $listening")
                if (listening) {
                    if (isTextModeActive) return@startListening // Ignore state changes in text mode
                    pandaStateManager.setState(PandaState.LISTENING)
                    visualFeedbackManager.showTranscription()
                } else {
                    if (!isTextModeActive) {
                        pandaStateManager.setState(PandaState.IDLE)
                    }
                }
            }
        )
    }


    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun speakAndThenListen(text: String, draw: Boolean = true) {
        // Only update system prompt with memories if we've heard the first utterance
//        if (hasHeardFirstUtterance) {
//            updateSystemPromptWithMemories()
//        }
        updateSystemPromptWithTime()
        pandaStateManager.setState(PandaState.SPEAKING)
        speechCoordinator.speakText(text)
        Log.d("ConvAgent", "Panda said: $text")
        // --- CHANGE 4: Check if we are in text mode before starting to listen ---
        if (isTextModeActive) {
            Log.d("ConvAgent", "In text mode, ensuring input box is visible and skipping voice listening.")
            // Post to main handler to ensure UI operations are on the main thread.
            mainHandler.post {
                showInputBoxIfNeeded() // Re-show the input box for the next turn.
            }
            return // IMPORTANT: Skip starting the voice listener entirely.
        }
        speechCoordinator.startListening(
            onResult = { recognizedText ->
                if (isTextModeActive) return@startListening // Ignore errors in text mode
                Log.d("ConvAgent", "Final user transcription: $recognizedText")
                pandaStateManager.setState(PandaState.PROCESSING)
                visualFeedbackManager.updateTranscription(recognizedText)
                mainHandler.postDelayed({
                    visualFeedbackManager.hideTranscription()
                }, 500)
                
                // Mark that we've heard the first utterance and trigger memory extraction if not already done
                if (!hasHeardFirstUtterance) {
                    hasHeardFirstUtterance = true
                    Log.d("ConvAgent", "First utterance received, triggering memory extraction")
                    serviceScope.launch {
                        try {
                            updateSystemPromptWithScreenContext()
                        } catch (e: Exception) {
                            Log.e("ConvAgent", "Error during first utterance memory extraction", e)
                            // Continue execution even if memory extraction fails
                        }
                    }
                }
                
                processUserInput(recognizedText)

            },
            onError = { error ->
                Log.e("ConvAgent", "STT Error: $error")
                if (isTextModeActive) return@startListening // Ignore errors in text mode
                
                // Trigger error state in state manager
                pandaStateManager.triggerErrorState()
                
                // Track STT errors
                val sttErrorBundle = android.os.Bundle().apply {
                    putString("error_message", error.take(100))
                    putInt("error_attempt", sttErrorAttempts + 1)
                    putInt("max_attempts", maxSttErrorAttempts)
                }
                try { firebaseAnalytics.logEvent("stt_error", sttErrorBundle) } catch (_: Exception) {}
                
                visualFeedbackManager.hideTranscription()
                sttErrorAttempts++
                serviceScope.launch {
                    if (sttErrorAttempts >= maxSttErrorAttempts) {
                        try { firebaseAnalytics.logEvent("conversation_ended_stt_errors", null) } catch (_: Exception) {}
                        val exitMessage = "I'm having trouble understanding you clearly. Please try calling later!"
                        trackMessage("model", exitMessage, "error_message")
                        gracefulShutdown(exitMessage, "stt_errors")
                    } else {
                        speakAndThenListen("I'm sorry, I didn't catch that. Could you please repeat?")
                    }
                }
            },
            onPartialResult = { partialText ->
                if (isTextModeActive) return@startListening // Ignore errors in text mode
                visualFeedbackManager.updateTranscription(partialText)
            },
            onListeningStateChange = { listening ->
                Log.d("ConvAgent", "Listening state: $listening")
                if (listening) {
                    if (isTextModeActive) return@startListening // Ignore errors in text mode
                    pandaStateManager.setState(PandaState.LISTENING)
                    visualFeedbackManager.showTranscription()
                } else {
                    if (!isTextModeActive) {
                        pandaStateManager.setState(PandaState.IDLE)
                    }
                }
            }
        )
    }

    // START: ADD THESE NEW METHODS AT THE END OF THE CLASS, before onDestroy()
    private fun showTranscriptionView() {
        if (transcriptionView != null) return // Already showing

        mainHandler.post {
            transcriptionView = TextView(this).apply {
                text = "Listening..."
                val glassBackground = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xDD0D0D2E.toInt(), 0xDD2A0D45.toInt())
                ).apply {
                    cornerRadius = 28f
                    setStroke(1, 0x80FFFFFF.toInt())
                }
                background = glassBackground
                setTextColor(0xFFE0E0E0.toInt())
                textSize = 16f
                setPadding(40, 24, 40, 24)
                typeface = Typeface.MONOSPACE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 250 // Position it 250px above the bottom edge
            }

            try {
                windowManager.addView(transcriptionView, params)
            } catch (e: Exception) {
                Log.e("ConvAgent", "Failed to add transcription view.", e)
                transcriptionView = null
            }
        }
    }

    private fun updateTranscriptionView(text: String) {
        transcriptionView?.text = text
    }

    private fun hideTranscriptionView() {
        mainHandler.post {
            transcriptionView?.let {
                if (it.isAttachedToWindow) {
                    try {
                        windowManager.removeView(it)
                    } catch (e: Exception) {
                        Log.e("ConvAgent", "Error removing transcription view.", e)
                    }
                }
            }
            transcriptionView = null
        }
    }


    // --- CHANGED: Rewritten to process the new custom text format ---
    @RequiresApi(Build.VERSION_CODES.R)
    private fun processUserInput(userInput: String) {
        serviceScope.launch {
            removeClarificationQuestions()
            updateSystemPromptWithAgentStatus()
            updateSystemPromptWithScreenContext()
            updateSystemPromptWithTime()
            // Mark that we've heard the first utterance and trigger memory extraction if not already done
            if (!hasHeardFirstUtterance) {
                hasHeardFirstUtterance = true
                Log.d("ConvAgent", "First utterance received via processUserInput, triggering memory extraction")
                try {
                    updateSystemPromptWithScreenContext()
                } catch (e: Exception) {
                    Log.e("ConvAgent", "Error during first utterance memory extraction", e)
                    // Continue execution even if memory extraction fails
                }
            }

            conversationHistory = addResponse("user", userInput, conversationHistory)
            
            // Track user message in Firebase
            trackMessage("user", userInput, "input")

            // Track user input
            val inputBundle = android.os.Bundle().apply {
                putString("input_type", if (isTextModeActive) "text" else "voice")
                putInt("input_length", userInput.length)
                putBoolean("is_command", userInput.equals("stop", ignoreCase = true) || userInput.equals("exit", ignoreCase = true))
            }
            try { firebaseAnalytics.logEvent("user_input_processed", inputBundle) } catch (_: Exception) {}

            try {
                if (userInput.equals("stop", ignoreCase = true) || userInput.equals("exit", ignoreCase = true)) {
                    try { firebaseAnalytics.logEvent("conversation_ended_by_command", null) } catch (_: Exception) {}
                    trackMessage("model", "Goodbye!", "farewell")
                    gracefulShutdown("Goodbye!", "command")
                    return@launch
                }
                pandaStateManager.setState(PandaState.PROCESSING)
                visualFeedbackManager.showThinkingIndicator()
                val defaultJsonResponse = """{"Type": "Reply", "Reply": "I'm sorry, I had an issue.", "Instruction": "", "Should End": "Continue"}"""
                val rawModelResponse = getReasoningModelApiResponse(conversationHistory) ?: defaultJsonResponse
                visualFeedbackManager.hideThinkingIndicator()
                val decision = parseModelResponse(rawModelResponse)
                Log.d("TTS_DEBUG", "Reply received from GeminiApi: -->${rawModelResponse}<--")
                when (decision.type) {
                    "Task" -> {
                        // Track task request
                        val taskBundle = android.os.Bundle().apply {
                            putString("task_instruction", decision.instruction.take(100)) // Limit length for analytics
                            putBoolean("agent_already_running", AgentService.isRunning)
                        }
                        try { firebaseAnalytics.logEvent("task_requested", taskBundle) } catch (_: Exception) {}
                        
                        if (AgentService.isRunning) {
                            try { firebaseAnalytics.logEvent("task_rejected_agent_busy", null) } catch (_: Exception) {}
                            val busyMessage = "I'm already working on '${AgentService.currentTask}'. Please let me finish that first, or you can ask me to stop it."
                            speakAndThenListen(busyMessage)
                            conversationHistory = addResponse("model", busyMessage, conversationHistory)
                            return@launch
                        }

                        if (!servicePermissionManager.isAccessibilityServiceEnabled()) {
                            speakAndThenListen(getString(R.string.accessibility_permission_needed_for_task))
                            conversationHistory = addResponse("model", R.string.accessibility_permission_needed_for_task.toString(), conversationHistory)
                            return@launch
                        }

                        Log.d("ConvAgent", "Model identified a task. Checking for clarification...")
                        // --- NEW: Check if the task instruction needs clarification ---
                        removeClarificationQuestions()
                        if(freemiumManager.canPerformTask()){
                            Log.d("ConvAgent", "Allowance check passed. Proceeding with task.")

                            if (clarificationAttempts < maxClarificationAttempts) {
                                val (needsClarification, questions) = checkIfClarificationNeeded(
                                    decision.instruction
                                )
                                Log.d("ConcAgent", needsClarification.toString())
                                Log.d("ConcAgent", questions.toString())

                                if (needsClarification) {
                                    // Track clarification needed
                                    val clarificationBundle = android.os.Bundle().apply {
                                        putInt("clarification_attempt", clarificationAttempts + 1)
                                        putInt("questions_count", questions.size)
                                    }
                                    try { firebaseAnalytics.logEvent("task_clarification_needed", clarificationBundle) } catch (_: Exception) {}
                                    
                                    clarificationAttempts++
                                    displayClarificationQuestions(questions)
                                    val questionToAsk =
                                        "I can help with that, but first: ${questions.joinToString(" and ")}"
                                    Log.d(
                                        "ConvAgent",
                                        "Task needs clarification. Asking: '$questionToAsk' (Attempt $clarificationAttempts/$maxClarificationAttempts)"
                                    )
                                    conversationHistory = addResponse(
                                        "model",
                                        "Clarification needed for task: ${decision.instruction}",
                                        conversationHistory
                                    )
                                    trackMessage("model", questionToAsk, "clarification")
                                    speakAndThenListen(questionToAsk, false)
                                } else {
                                    Log.d(
                                        "ConvAgent",
                                        "Task is clear. Executing: ${decision.instruction}"
                                    )
                                    
                                    // Track task execution
                                    try { firebaseAnalytics.logEvent("task_executed", taskBundle) } catch (_: Exception) {}
                                    
                                    val originalInstruction = decision.instruction
                                    AgentService.start(applicationContext, originalInstruction)
                                    trackMessage("model", decision.reply, "task_confirmation")
                                    gracefulShutdown(decision.reply, "task_executed")
                                }
                            } else {
                                Log.d(
                                    "ConvAgent",
                                    "Max clarification attempts reached ($maxClarificationAttempts). Proceeding with task execution."
                                )
                                
                                // Track max clarification attempts reached
                                try { firebaseAnalytics.logEvent("task_executed_max_clarification", taskBundle) } catch (_: Exception) {}
                                
                                AgentService.start(applicationContext, decision.instruction)
                                trackMessage("model", decision.reply, "task_confirmation")
                                gracefulShutdown(decision.reply, "task_executed")
                            }
                        }else{
                            Log.w("ConvAgent", "User has no tasks remaining. Denying request.")
                            
                            // Track freemium limit reached
                            try { firebaseAnalytics.logEvent("task_rejected_freemium_limit", null) } catch (_: Exception) {}
                            
                            val upgradeMessage = "Hey! You've used all your free tasks for the month. Please upgrade in the app to unlock more. We can still talk in voice mode."
                            conversationHistory = addResponse("model", upgradeMessage, conversationHistory)
                            trackMessage("model", upgradeMessage, "freemium_limit")
                            speakAndThenListen(upgradeMessage)
                        }
                    }
                    "KillTask" -> {
                        Log.d("ConvAgent", "Model requested to kill the running agent service.")
                        
                        // Track kill task request
                        val killTaskBundle = android.os.Bundle().apply {
                            putBoolean("task_was_running", AgentService.isRunning)
                        }
                        try { firebaseAnalytics.logEvent("kill_task_requested", killTaskBundle) } catch (_: Exception) {}
                        
                        if (AgentService.isRunning) {
                            AgentService.stop(applicationContext)
                            trackMessage("model", decision.reply, "kill_task_response")
                            gracefulShutdown(decision.reply, "task_killed")
                        } else {
                            val noTaskMessage = "There was no automation running, but I can help with something else."
                            trackMessage("model", noTaskMessage, "kill_task_response")
                            speakAndThenListen(noTaskMessage)
                        }
                    }
                    else -> { // Default to "Reply"
                        // Track conversational reply
                        val replyBundle = android.os.Bundle().apply {
                            putBoolean("conversation_ended", decision.shouldEnd)
                            putInt("reply_length", decision.reply.length)
                        }
                        try { firebaseAnalytics.logEvent("conversational_reply", replyBundle) } catch (_: Exception) {}
                        
                        if (decision.shouldEnd) {
                            Log.d("ConvAgent", "Model decided to end the conversation.")
                            try { firebaseAnalytics.logEvent("conversation_ended_by_model", null) } catch (_: Exception) {}
                            trackMessage("model", decision.reply, "farewell")
                            gracefulShutdown(decision.reply, "model_ended")
                        } else {
                            conversationHistory = addResponse("model", rawModelResponse, conversationHistory)
                            trackMessage("model", decision.reply, "reply")
                            speakAndThenListen(decision.reply)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("ConvAgent", "Error processing user input: ${e.message}", e)
                
                // Trigger error state in state manager
                pandaStateManager.triggerErrorState()
                
                // Track processing errors
                val errorBundle = android.os.Bundle().apply {
                    putString("error_message", e.message?.take(100) ?: "Unknown error")
                    putString("error_type", e.javaClass.simpleName)
                }
                try { firebaseAnalytics.logEvent("input_processing_error", errorBundle) } catch (_: Exception) {}
                
                speakAndThenListen("closing voice mode")
            }
        }
    }

    private suspend fun checkIfClarificationNeeded(instruction: String): Pair<Boolean, List<String>> {
        Log.d("ConvAgent", "Checking for clarification on instruction: '$instruction'")
        return Pair(false, listOf())
    }

    private fun initializeConversation() {
        val memoryContextSection = if (MEMORY_ENABLED) {
            """
            Use these memories to answer the user's question with his personal data
            ### Memory Context Start ###
            {memory_context}
            ### Memory Context Ends ###
            """
        } else {
            """
            ### Memory Status ###
            Memory system is temporarily disabled. Panda cannot remember or learn from previous conversations at this time.
            some memories added by developers
            {memory_context}
            ### End Memory Status ###
            """
        }

        val systemPrompt = """
            You are a helpful voice assistant called Panda that can either have a conversation or ask an executor to execute tasks on the user's phone.
            The executor can speak, listen, see the screen, tap the screen, and basically use the phone as a normal human would.

            {agent_status_context}

            ### Current Screen Context ###
            {screen_context}
            ### End Screen Context ###

            Some Guideline:
            1. If the user ask you to do something creative, you do this task and be the most creative person in the world.
            2. If you know the user's name from the memories, refer to them by their name to make the conversation more personal and friendly as often as possible.
            3. Use the current screen context to better understand what the user is looking at and provide more relevant responses.
            4. If the user asks about something on the screen, you can reference the screen content directly.
            5. Always ask for clarification if the user's request is ambiguous or unclear.
            6. When the user ask to sing, shout or produce any sound, just generate text, we will sing it for you.
            7. Your code is opensource so you can tell tell that to user. repo is ayush0chaudhary/blurr
            8. Give a warning for the tasks related to banking, games, shopping and app with Canvas (no a11y tree) that you wont be able to do them properly but you will try your best.
            
            Use these memories to answer the user's question with his personal data
            ### Memory Context Start ###
            {memory_context}
            ### Memory Context Ends ###
            
            Analyze the user's request and respond ONLY with a single, valid JSON object.
            Do not include any text, notes, or explanations outside of the JSON object.
            The JSON object must have the following structure:
            
            {
              "Type": "String",
              "Reply": "String",
              "Instruction": "String",
              "Should End": "String"
            }

            Here are the rules for the JSON values:
            - "Type": Must be one of "Task", "Reply", or "KillTask".
              - Use "Task" if the user is asking you to DO something on the device (e.g., "open settings", "send a text to Mom").
              - Use "Reply" for conversational questions (e.g., "what's the weather?", "tell me a joke").
              - Use "KillTask" ONLY if an automation task is running and the user wants to stop it.
            - "Reply": The text to speak to the user. This is a confirmation for a "Task", or the direct answer for a "Reply".
            - "Instruction": The precise, literal instruction for the task agent. This field should be an empty string "" if the "Type" is not "Task".
            - "Should End": Must be either "Continue" or "Finished". Use "Finished" only when the conversation is naturally over.
        
            Current Time : {time_context}
        """.trimIndent()

        conversationHistory = addResponse("user", systemPrompt, emptyList())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateSystemPromptWithTime() {
        val currentPromptText = conversationHistory.firstOrNull()?.second
            ?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return

        val currentTime = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        val formattedTime = currentTime.format(formatter)

        // Matches "Current Time : {time_context}" OR "Current Time : 2025-12-11..."
        // This ensures we can update it even if the placeholder is already gone.
        val timeRegex = Regex("Current Time : (\\{time_context\\}|.*)")
        val newTimeLine = "Current Time : $formattedTime"

        val updatedPromptText = timeRegex.replace(currentPromptText, newTimeLine)

        // Replace the first system message with the updated prompt
        conversationHistory = conversationHistory.toMutableList().apply {
            set(0, "user" to listOf(TextPart(updatedPromptText)))
        }
        Log.d("ConvAgent", "System prompt updated with time: $formattedTime")
    }

    private fun updateSystemPromptWithAgentStatus() {
        val currentPromptText = conversationHistory.firstOrNull()?.second
            ?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return

        val agentStatusContext = if (AgentService.isRunning) {
            """
            IMPORTANT CONTEXT: An automation task is currently running in the background.
            Task Description: "${AgentService.currentTask}".
            If the user asks to stop, cancel, or kill this task, you MUST use the "KillTask" type.
            """.trimIndent()
        } else {
            "CONTEXT: No automation task is currently running."
        }

        val updatedPromptText = currentPromptText.replace("{agent_status_context}", agentStatusContext)

        // Replace the first system message with the updated prompt
        conversationHistory = conversationHistory.toMutableList().apply {
            set(0, "user" to listOf(TextPart(updatedPromptText)))
        }
        Log.d("ConvAgent", "System prompt updated with agent status: ${AgentService.isRunning}")
    }

    /**
     * Updates the system prompt with relevant memories and current screen context
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun updateSystemPromptWithScreenContext() {
        try {

            perception = Perception(Eyes(this), SemanticParser())
            val analysis = perception.analyze(all = true)
            Log.d("ConvAgent", "Screen analysis: ${analysis.uiRepresentation}")
            val currentPrompt = conversationHistory.firstOrNull()?.second
                ?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return

            // Update screen context first
            var updatedPrompt = currentPrompt.replace("{screen_context}", analysis.uiRepresentation)

            // Check if memory is enabled before processing memories
            if (!MEMORY_ENABLED) {
                var userProfile = UserProfileManager(this@ConversationalAgentService)

                Log.d("ConvAgent", "Memory is disabled, skipping memory operations")
                Log.d("ConvAgent", "User name is ${userProfile.getName()}")
                // Replace memory context with disabled message

                updatedPrompt = updatedPrompt.replace("{memory_context}", "User name is ${userProfile.getName()}")
            } else {
                // Use cached memories from Firestore
                if (cachedMemories.isNotEmpty()) {
                    Log.d("ConvAgent", "Injecting ${cachedMemories.size} cached memories into context")
                    
                    // Take top 100 memories (already sorted by date desc in fetch logic if needed, or just take latest)
                    // For now, we just take the list as is, assuming it's not huge, or take top 100.
                    val topMemories = cachedMemories.take(100)
                    
                    val memoryContext = topMemories.joinToString("\n") { memory -> 
                        "- ${memory.text} (Source: ${memory.source})" 
                    }
                    updatedPrompt = updatedPrompt.replace("{memory_context}", memoryContext)
                } else {
                     Log.d("ConvAgent", "No cached memories available yet")
                     updatedPrompt = updatedPrompt.replace("{memory_context}", "No memories available yet.")
                }
            }

            if (updatedPrompt.isNotEmpty()) {
                // Replace the first system message with updated prompt
                conversationHistory = conversationHistory.toMutableList().apply {
                    set(0, "user" to listOf(TextPart(updatedPrompt)))
                }
                Log.d("ConvAgent", "Updated system prompt with screen context and memories")
            }
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error updating system prompt with memories and screen context", e)
        }
    }

    /**
     * Extracts current memory context from the system prompt
     */
    private fun extractCurrentMemoryContext(prompt: String): List<String> {
        return try {
            val memorySection = prompt.substringAfter("##### MEMORY CONTEXT #####")
                .substringBefore("##### END MEMORY CONTEXT #####")
                .trim()

            if (memorySection.isNotEmpty() && !memorySection.contains("{memory_context}")) {
                memorySection.lines()
                    .filter { it.trim().startsWith("- ") }
                    .map { it.trim().substring(2) } // Remove "- " prefix
                    .filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error extracting current memory context", e)
            emptyList()
        }
    }

    private fun parseModelResponse(response: String): ModelDecision {
        try {
            val json = JSONObject(response)
            Log.d("justchecking", json.toString())
            // Use optString for safety, providing a default value if the key doesn't exist.
            val type = json.optString("Type", "Reply")
            val reply = json.optString("Reply", "")
            val instruction = json.optString("Instruction", "")
            val shouldEndStr = json.optString("Should End", "Continue")
            val shouldEnd = shouldEndStr.equals("Finished", ignoreCase = true)
            
            // Add a fallback reply if the model provides an empty one for a conversational turn.
            val finalReply = if (reply.isEmpty() && type.equals("Reply", ignoreCase = true)) {
                "I'm not sure how to respond to that."
            } else {
                reply
            }

            return ModelDecision(type, finalReply, instruction, shouldEnd)
        } catch (e: org.json.JSONException) {
            Log.e("ConvAgent", "Error parsing JSON response, falling back. Response: $response", e)
            // Fallback for malformed JSON
            return ModelDecision(reply = "I seem to have gotten my thoughts tangled. Could you repeat that?")
        } catch (e: Exception) {
            Log.e("ConvAgent", "Generic error parsing model response, falling back. Response: $response", e)
            return ModelDecision(reply = "I had a minor issue processing that. Could you try again?")
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ConversationalAgentService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Conversational Agent")
            .setContentText("Listening for your commands...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause, // Using built-in pause icon as stop button
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Conversational Agent Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun displayClarificationQuestions(questions: List<String>) {
        mainHandler.post {
            clarificationQuestionViews.clear()
            questions.forEachIndexed { index, question ->
                try {
                    val textView = TextView(this).apply {
                        text = question
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 14f
                        setPadding(20, 15, 20, 15)
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            intArrayOf(0xFF6A0572.toInt(), 0xFF2A0845.toInt())
                        ).apply {
                            cornerRadius = 12f
                            setStroke(1, 0xFFB366FF.toInt())
                        }
                    }

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                        y = 150 + (index * 80) // Offset each question vertically
                    }

                    windowManager.addView(textView, params)
                    clarificationQuestionViews.add(textView)

                    // Animate in
                    val animator = ValueAnimator.ofFloat(0f, 1f)
                    animator.duration = 300
                    animator.addUpdateListener { animation ->
                        val progress = animation.animatedValue as Float
                        if (index < clarificationQuestionViews.size) {
                            val viewHeight = textView.height
                            val finalYPosition = 150 + (index * 80)
                            params.y = (finalYPosition * progress - viewHeight * (1 - progress)).toInt()
                            params.alpha = progress
                            windowManager.updateViewLayout(textView, params)
                        }
                    }
                    animator.start()

                } catch (e: Exception) {
                    Log.e("ConvAgent", "Failed to display futuristic clarification question.", e)
                }
            }
        }
    }

    /**
     * Removes all currently displayed clarification questions from the screen.
     */
    private fun removeClarificationQuestions() {
        mainHandler.post {
            clarificationQuestionViews.forEach { view ->
                if (view.isAttachedToWindow) {
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                        Log.e("ConvAgent", "Error removing clarification view.", e)
                    }
                }
            }
            clarificationQuestionViews.clear()
        }
    }

    private suspend fun gracefulShutdown(exitMessage: String? = null, endReason: String = "graceful") {
        // Track graceful shutdown
        val shutdownBundle = android.os.Bundle().apply {
            putBoolean("had_exit_message", exitMessage != null)
            putInt("conversation_length", conversationHistory.size)
            putBoolean("text_mode_used", isTextModeActive)
            putInt("clarification_attempts", clarificationAttempts)
            putInt("stt_error_attempts", sttErrorAttempts)
        }
        try { firebaseAnalytics.logEvent("conversation_ended_gracefully", shutdownBundle) } catch (_: Exception) {}
        
        // Track conversation end in Firebase

        trackConversationEnd(endReason)
        
        visualFeedbackManager.hideTtsWave()
        visualFeedbackManager.hideTranscription()
        visualFeedbackManager.hideSpeakingOverlay()
        visualFeedbackManager.hideInputBox()

        if (exitMessage != null) {
                speechCoordinator.speakText(exitMessage)
                delay(2000) // Give TTS time to finish
            }
            // 1. Extract memories from the conversation before ending
            // Removed old memory extraction logic
            triggerMemoryGeneration()
            
            // 3. Stop the service
            stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ConvAgent", "Service onDestroy")

        try { firebaseAnalytics.logEvent("conversation_service_destroyed", null) } catch (_: Exception) {}

        removeClarificationQuestions()
        serviceScope.cancel()
        isRunning = false
        
        // Stop state monitoring and set final state
        pandaStateManager.setState(PandaState.IDLE)
        pandaStateManager.stopMonitoring()
        visualFeedbackManager.hideSmallDeltaGlow()
        visualFeedbackManager.hideSpeakingOverlay()
        // USE the new manager to hide the wave and transcription view
        visualFeedbackManager.hideTtsWave()
        visualFeedbackManager.hideTranscription()
        visualFeedbackManager.hideInputBox()

    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun fetchMemories() {
        val currentUser = auth?.currentUser
        if (currentUser == null) {
            Log.w("ConvAgent", "User not logged in, cannot fetch memories")
            return
        }

        Log.d("ConvAgent", "Starting async memory fetch for user: ${currentUser.uid}")
        db?.let { fs ->
            fs.collection("users").document(currentUser.uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("ConvAgent", "Listen failed for memories.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val memoriesList = snapshot.get("memories") as? List<Map<String, Any>> ?: emptyList()
                        
                        // Convert to UserMemory objects
                        cachedMemories = memoriesList.mapNotNull { memoryMap ->
                            try {
                                UserMemory(
                                    id = memoryMap["id"] as? String ?: return@mapNotNull null,
                                    text = memoryMap["text"] as? String ?: "",
                                    source = memoryMap["source"] as? String ?: "user",
                                    timestamp = memoryMap["timestamp"] as? Long ?: System.currentTimeMillis()
                                )
                            } catch (ex: Exception) {
                                Log.e("ConvAgent", "Error converting memory map", ex)
                                null
                            }
                        }
                        
                        Log.d("ConvAgent", "Fetched ${cachedMemories.size} memories")
                    } catch (ex: Exception) {
                        Log.e("ConvAgent", "Error processing memories snapshot", ex)
                    }
                } else {
                    Log.d("ConvAgent", "No memories document found")
                    cachedMemories = emptyList()
                }
            }
        }
    }

    private fun trackMessage(sender: String, message: String, messageType: String) {
        val currentUser = auth?.currentUser
        if (currentUser == null || conversationId == null) {
            Log.w("ConvAgent", "Cannot track message, user not logged in or no conversation ID")
            return
        }

        serviceScope.launch {
            try {
                val messageEntry = hashMapOf(
                    "sender" to sender,
                    "message" to message,
                    "type" to messageType,
                    "timestamp" to Timestamp.now()
                )

                db?.collection("users")?.document(currentUser.uid)
                    ?.collection("conversations")?.document(conversationId!!)
                    ?.collection("messages")?.add(messageEntry)
                    ?.addOnFailureListener { e ->
                        Log.e("ConvAgent", "Error tracking message", e)
                    }
            } catch (e: Exception) {
                Log.e("ConvAgent", "Error in trackMessage", e)
            }
        }
    }

    private fun triggerMemoryGeneration() {
        // This would trigger the memory generation from conversation
        Log.d("ConvAgent", "Memory generation triggered for conversation")
    }

    private fun trackConversationStart() {
        val currentUser = auth?.currentUser
        if (currentUser == null) {
            Log.w("ConvAgent", "Cannot track conversation, user is not logged in.")
            return
        }

        // Generate a unique conversation ID
        conversationId = "${System.currentTimeMillis()}_${currentUser.uid.take(8)}"

        serviceScope.launch {
            try {
                val conversationEntry = hashMapOf(
                    "conversationId" to conversationId,
                    "startedAt" to Timestamp.now(),
                    "endedAt" to null,
                    "status" to "active"
                )

                db?.collection("users")?.document(currentUser.uid)
                    ?.collection("conversations")?.document(conversationId!!)
                    ?.set(conversationEntry)
                    ?.addOnFailureListener { e ->
                        Log.e("ConvAgent", "Error tracking conversation start", e)
                    }
            } catch (e: Exception) {
                Log.e("ConvAgent", "Error in trackConversationStart", e)
            }
        }
    }

    private fun trackConversationEnd(endReason: String) {
        val currentUser = auth?.currentUser
        if (currentUser == null || conversationId == null) {
            Log.w("ConvAgent", "Cannot track conversation end, user not logged in or no conversation ID")
            return
        }

        serviceScope.launch {
            try {
                db?.collection("users")?.document(currentUser.uid)
                    ?.collection("conversations")?.document(conversationId!!)
                    ?.update(
                        "endedAt" to Timestamp.now(),
                        "status" to "ended",
                        "endReason" to endReason
                    )
                    ?.addOnFailureListener { e ->
                        Log.e("ConvAgent", "Error tracking conversation end", e)
                    }
            } catch (e: Exception) {
                Log.e("ConvAgent", "Error in trackConversationEnd", e)
            }
        }
    }

    /**
     * Immediately stops all TTS, STT, and background tasks, hides all UI, and stops the service.
     * This is used for forceful termination, like an outside tap.
     */
    private suspend fun instantShutdown() {
        // Track instant shutdown
        val instantShutdownBundle = android.os.Bundle().apply {
            putInt("conversation_length", conversationHistory.size)
            putBoolean("text_mode_used", isTextModeActive)
            putInt("clarification_attempts", clarificationAttempts)
            putInt("stt_error_attempts", sttErrorAttempts)
        }
        try { firebaseAnalytics.logEvent("conversation_ended_instantly", instantShutdownBundle) } catch (_: Exception) {}
        
        // Track conversation end in Firebase
        trackConversationEnd("instant")
        
        Log.d("ConvAgent", "Instant shutdown triggered by user.")
        withContext(Dispatchers.Main) {
            speechCoordinator.stopSpeaking()
            speechCoordinator.stopListening()
            visualFeedbackManager.hideTtsWave()
            visualFeedbackManager.hideTranscription()
            visualFeedbackManager.hideSpeakingOverlay()
            visualFeedbackManager.hideInputBox()
            removeClarificationQuestions()
        }

        removeClarificationQuestions()
        // Make a thread-safe copy of the conversation history.
        // Removed old memory extraction logic
        triggerMemoryGeneration()
        
        serviceScope.cancel("User tapped outside, forcing instant shutdown.")

        stopSelf()
    }
}
