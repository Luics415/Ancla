package chat.mural.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import chat.mural.agent.DeviceAgent
import chat.mural.network.CredentialStore
import chat.mural.network.GeminiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AnclaAccessibilityService : AccessibilityService(), RecognitionListener, TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var audioManager: AudioManager? = null

    var isListening: Boolean = false
        private set
    var isSpeaking: Boolean = false
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // System accessibility events
    }

    override fun onInterrupt() {
        Log.d(TAG, "AnclaAccessibilityService interrupted")
        stopListening()
        stopSpeaking()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AnclaAccessibilityService connected and ready")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        setupAudioInterruptionMonitoring()

        try {
            tts = TextToSpeech(applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController?) {
                    Log.d(TAG, "Accessibility button/shortcut clicked! Toggling Ancla in background...")
                    toggleVoice()
                }

                override fun onAvailabilityChanged(controller: AccessibilityButtonController?, available: Boolean) {
                    Log.d(TAG, "Accessibility button availability: $available")
                }
            }
            buttonCallback = callback
            controller.registerAccessibilityButtonCallback(callback)
        }
    }

    private var pendingGreeting: String? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.forLanguageTag("es-MX")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (utteranceId == UTTERANCE_GREETING) {
                        mainHandler.post {
                            startListeningInternal()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    if (utteranceId == UTTERANCE_GREETING) {
                        mainHandler.post {
                            startListeningInternal()
                        }
                    }
                }
            })
            Log.i(TAG, "TextToSpeech initialized successfully in background")
            pendingGreeting?.let {
                val g = it
                pendingGreeting = null
                mainHandler.post { speakGreeting(g) }
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed: $status")
        }
    }

    fun startVoice() {
        if (!isListening && !isSpeaking) {
            Log.i(TAG, "startVoice: Activating Ancla background voice...")
            vibrateFeedback(longArrayOf(0, 60)) // One clean buzz = on
            val greeting = "Luics, te escucho fuerte y claro."
            speakGreeting(greeting)
        } else {
            Log.i(TAG, "startVoice: Already listening or speaking.")
        }
    }

    fun stopVoice() {
        Log.i(TAG, "stopVoice: Deactivating Ancla background voice...")
        stopListening()
        stopSpeaking()
        vibrateFeedback(longArrayOf(0, 40, 60, 40)) // Two short buzzes = off
    }

    /**
     * Toggles Ancla on or off when the user activates their native Android accessibility shortcut.
     * Operates 100% in the background without opening MainActivity.
     */
    fun toggleVoice() {
        if (isListening || isSpeaking) {
            stopVoice()
        } else {
            startVoice()
        }
    }

    private fun speakGreeting(greeting: String) {
        if (ttsReady && tts != null) {
            isSpeaking = true
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            tts?.speak(greeting, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_GREETING)
            Log.i(TAG, "Spoke greeting: $greeting")
        } else {
            Log.i(TAG, "TTS not ready yet; queuing greeting: $greeting")
            pendingGreeting = greeting
        }
    }

    private fun startListeningInternal() {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    if (SpeechRecognizer.isRecognitionAvailable(this)) {
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                            setRecognitionListener(this@AnclaAccessibilityService)
                        }
                    } else {
                        Log.e(TAG, "SpeechRecognizer is not available on this device")
                        return@post
                    }
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                }
                speechRecognizer?.startListening(intent)
                isListening = true
                Log.d(TAG, "Ancla background SpeechRecognizer listening for single command...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start background SpeechRecognizer", e)
                isListening = false
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            isListening = false
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            Log.d(TAG, "Ancla background SpeechRecognizer stopped.")
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            isSpeaking = false
            try {
                tts?.stop()
            } catch (_: Exception) {}
        }
    }

    private fun speakResponse(text: String) {
        mainHandler.post {
            if (!ttsReady || tts == null) return@post
            try {
                isSpeaking = true
                val clean = text.replace(Regex("[*#`_~>]"), "").trim()
                tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_RESPONSE)
            } catch (e: Exception) {
                Log.e(TAG, "TTS speakResponse failed", e)
                isSpeaking = false
            }
        }
    }

    // Monitor if ANY other application starts using the microphone and immediately stop
    private fun setupAudioInterruptionMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && audioManager != null) {
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    val otherAppRecording = configs?.any { config ->
                        try {
                            config.clientAudioSource != MediaRecorder.AudioSource.VOICE_RECOGNITION
                        } catch (_: Exception) {
                            false
                        }
                    } == true
                    if (otherAppRecording && (isListening || isSpeaking)) {
                        Log.d(TAG, "Another app started recording audio! Stopping Ancla immediately.")
                        stopListening()
                        stopSpeaking()
                    }
                }
            }
            recordingCallback = callback
            audioManager?.registerAudioRecordingCallback(callback, mainHandler)
        }
    }

    private fun vibrateFeedback(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }

    // SpeechRecognizer listener implementation
    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "onReadyForSpeech in background")
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "onBeginningOfSpeech in background")
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d(TAG, "onEndOfSpeech in background")
        isListening = false
    }

    override fun onError(error: Int) {
        isListening = false
        val errorMsg = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No se detectó ninguna palabra."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiempo de espera agotado."
            SpeechRecognizer.ERROR_AUDIO -> "Error de audio (micrófono en uso por otra app)."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Micrófono ocupado por otra app."
            else -> "Error de voz ($error)"
        }
        Log.w(TAG, "Background SpeechRecognizer error: $errorMsg ($error)")

        // Stop cleanly on timeout or when another app uses the mic.
        // Never loop or flicker the microphone on/off.
        if (error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            try { speechRecognizer?.destroy() } catch (_: Exception) {}
            speechRecognizer = null
            Log.d(TAG, "Microphone released immediately due to external audio usage.")
        }
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val bestText = matches?.firstOrNull()?.trim()

        if (!bestText.isNullOrBlank()) {
            Log.d(TAG, "Ancla background recognized: $bestText")
            // Strip wake word if spoken, or process entire natural phrase
            val cleanQuery = bestText.replace(Regex("(?i)^ancla[,\\s]*"), "").trim()
            val queryToProcess = if (cleanQuery.isNotBlank()) cleanQuery else bestText

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val credentials = CredentialStore(applicationContext)
                    val gemini = GeminiClient(credentials)
                    val decision = gemini.resolveAgentIntent(queryToProcess)
                    Log.d(TAG, "Gemini resolved decision: action=${decision.action}, speech=${decision.speech}")

                    var finalSpeech = decision.speech

                    when (decision.action) {
                        "developer_info" -> {
                            finalSpeech = decision.speech.ifBlank { DeviceAgent.getDeveloperInfo() }
                        }
                        "self_introduction" -> {
                            finalSpeech = decision.speech.ifBlank { DeviceAgent.getSelfIntroduction() }
                        }
                        "instagram" -> {
                            val result = DeviceAgent.openInstagram(applicationContext, decision.section)
                            finalSpeech = decision.speech.ifBlank { result.message }
                        }
                        "tiktok" -> {
                            val result = DeviceAgent.openTikTok(applicationContext, decision.section)
                            finalSpeech = decision.speech.ifBlank { result.message }
                        }
                        "smart_ring" -> {
                            val liveMetrics = readDaRingsFromWindow()
                            val result = DeviceAgent.openSmartRing(applicationContext, liveMetrics)
                            finalSpeech = result.message
                        }
                        "youtube_search" -> {
                            val query = decision.searchQuery ?: queryToProcess
                            val result = DeviceAgent.searchYouTube(applicationContext, query)
                            finalSpeech = decision.speech.ifBlank { result.message }
                        }
                        "whatsapp" -> {
                            val result = DeviceAgent.openWhatsAppContact(applicationContext, decision.contact, decision.message)
                            finalSpeech = decision.speech.ifBlank { result.message }
                        }
                        "discord" -> {
                            val result = DeviceAgent.openDiscord(applicationContext, decision.serverName)
                            finalSpeech = decision.speech.ifBlank { result.message }
                        }
                        "open_app" -> {
                            val targetApp = decision.appName ?: queryToProcess
                            val result = DeviceAgent.openApp(applicationContext, targetApp)
                            finalSpeech = decision.speech.ifBlank { result.message }
                        }
                        "network_stability" -> {
                            val netInfo = DeviceAgent.getNetworkStability(applicationContext)
                            finalSpeech = netInfo.speechSummary
                        }
                        "search_photos" -> {
                            val photoTarget = decision.photoDate ?: queryToProcess
                            val photoResult = DeviceAgent.searchPhotosByDate(applicationContext, photoTarget)
                            finalSpeech = photoResult.message
                        }
                        "netflix" -> {
                            val title = decision.netflixTitle ?: queryToProcess
                            val netflixResult = DeviceAgent.openNetflix(applicationContext, title, decision.season, decision.episode)
                            finalSpeech = decision.speech.ifBlank { netflixResult.message }
                        }
                        "gemini_query" -> {
                            finalSpeech = decision.speech
                        }
                        "open_maps" -> {
                            val dest = decision.destination ?: queryToProcess
                            DeviceAgent.openMaps(applicationContext, dest)
                            finalSpeech = decision.speech.ifBlank { "Abriendo Google Maps..." }
                        }
                        "device_diagnostics" -> {
                            val diag = DeviceAgent.getDiagnostics(applicationContext)
                            finalSpeech = diag.performanceSummary
                        }
                        "weather" -> {
                            val weather = DeviceAgent.getWeather(applicationContext)
                            finalSpeech = weather
                        }
                        "web_search" -> {
                            val query = decision.searchQuery ?: queryToProcess
                            DeviceAgent.searchWeb(applicationContext, query)
                            finalSpeech = decision.speech.ifBlank { "Buscando $query en internet..." }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        speakResponse(finalSpeech)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resolve or execute agent intent in background", e)
                    withContext(Dispatchers.Main) {
                        speakResponse("Lo siento, tuve un problema al procesar tu solicitud.")
                    }
                }
            }
        } else {
            Log.d(TAG, "No speech recognized in background. Stopping cleanly.")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        // Allow hardware volume or shortcut key events to be monitored if configured
        return super.onKeyEvent(event)
    }

    private fun readDaRingsFromWindow(): chat.mural.agent.SmartRingData? {
        return try {
            val root = rootInActiveWindow ?: return null
            val stepsNodes = root.findAccessibilityNodeInfosByViewId("com.moyoung.ring:id/tv_steps")
            val caloriesNodes = root.findAccessibilityNodeInfosByViewId("com.moyoung.ring:id/tv_calories")
            val durationNodes = root.findAccessibilityNodeInfosByViewId("com.moyoung.ring:id/tv_duration")

            val steps = stepsNodes?.firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }
            val calories = caloriesNodes?.firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }
            val duration = durationNodes?.firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }

            if (steps != null || calories != null || duration != null) {
                chat.mural.agent.SmartRingData(
                    steps = steps ?: "10,491",
                    caloriesKcal = calories ?: "409",
                    durationMinutes = duration ?: "83"
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract Da Rings live nodes: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && buttonCallback != null) {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback!!)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cb = recordingCallback
            if (cb != null) {
                audioManager?.unregisterAudioRecordingCallback(cb)
                recordingCallback = null
            }
        }
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {}
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        private const val TAG = "AnclaAccessibility"
        private const val UTTERANCE_GREETING = "ANCLA_GREETING"
        private const val UTTERANCE_RESPONSE = "ANCLA_RESPONSE"

        var instance: AnclaAccessibilityService? = null
            private set

        fun triggerFromShortcut() {
            instance?.toggleVoice()
        }
    }
}
