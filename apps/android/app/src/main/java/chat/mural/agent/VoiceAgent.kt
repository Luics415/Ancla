package chat.mural.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class VoiceAgent(
    private val context: Context,
    private val onSpeechRecognized: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {}
) : RecognitionListener, TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var activeLanguage: String = "es-MX"

    var isListening: Boolean = false
        private set
    var shouldListen: Boolean = false
        private set
    var isSpeaking: Boolean = false
        private set

    init {
        mainHandler.post {
            ensureSpeechRecognizer()
            try {
                tts = TextToSpeech(context.applicationContext, this)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing TextToSpeech", e)
            }
        }
    }

    private fun ensureSpeechRecognizer(): SpeechRecognizer? {
        if (speechRecognizer == null) {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(this@VoiceAgent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing SpeechRecognizer", e)
            }
        }
        return speechRecognizer
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (shouldListen) {
                        scheduleRestart(250)
                    }
                }

                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    if (shouldListen) {
                        scheduleRestart(250)
                    }
                }
            })
            Log.d(TAG, "TextToSpeech initialized successfully with listener")
        } else {
            Log.e(TAG, "TextToSpeech initialization failed: $status")
        }
    }

    fun startListening(languageCode: String = "es-MX") {
        activeLanguage = languageCode
        shouldListen = true
        mainHandler.post {
            startListeningInternal()
        }
    }

    private fun startListeningInternal() {
        if (!shouldListen || isSpeaking) return
        try {
            val recognizer = ensureSpeechRecognizer() ?: return
            try { recognizer.cancel() } catch (_: Exception) {}
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeLanguage)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            recognizer.startListening(intent)
            isListening = true
            onListeningStateChanged(true)
            Log.d(TAG, "SpeechRecognizer listening for 'ancla' in $activeLanguage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
            onListeningStateChanged(false)
            if (shouldListen) {
                scheduleRestart(800)
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.postDelayed({
            if (shouldListen && !isSpeaking) {
                startListeningInternal()
            }
        }, delayMs)
    }

    fun stopListening() {
        shouldListen = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            isListening = false
            onListeningStateChanged(false)
        }
    }

    fun speak(text: String, languageCode: String? = null) {
        mainHandler.post {
            if (!ttsReady || tts == null) return@post
            try {
                // Pause listening while speaking to avoid self-feedback
                try {
                    speechRecognizer?.stopListening()
                } catch (_: Exception) {}
                isListening = false
                onListeningStateChanged(false)
                isSpeaking = true

                if (languageCode != null) {
                    val locale = Locale.forLanguageTag(languageCode)
                    tts?.language = locale
                }
                // Strip markdown formatting symbols for natural speech
                val cleanText = text
                    .replace(Regex("[*#`_~>]"), "")
                    .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
                    .trim()

                tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "ANCLA_UTTERANCE")
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak failed", e)
                isSpeaking = false
                if (shouldListen) scheduleRestart(250)
            }
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            isSpeaking = false
            try { tts?.stop() } catch (_: Exception) {}
        }
    }

    fun destroy() {
        shouldListen = false
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Exception) {}
            try {
                tts?.stop()
                tts?.shutdown()
                tts = null
            } catch (_: Exception) {}
            isListening = false
            onListeningStateChanged(false)
        }
    }

    // RecognitionListener implementation
    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "onReadyForSpeech")
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "onBeginningOfSpeech")
    }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d(TAG, "onEndOfSpeech")
        isListening = false
        onListeningStateChanged(false)
    }

    override fun onError(error: Int) {
        isListening = false
        onListeningStateChanged(false)
        val errorMsg = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No se detectó ninguna palabra."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiempo de espera agotado."
            SpeechRecognizer.ERROR_AUDIO -> "Error de grabación de audio."
            SpeechRecognizer.ERROR_CLIENT -> "Reconocimiento cancelado."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permiso de micrófono no concedido."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sin conexión para reconocimiento."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado."
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Servidor desconectado."
            else -> "Error de voz ($error)"
        }
        Log.w(TAG, "SpeechRecognizer error: $errorMsg ($error)")

        // Reconnect only on server disconnection or busy
        if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            try { speechRecognizer?.destroy() } catch (_: Exception) {}
            speechRecognizer = null
            if (shouldListen && !isSpeaking) scheduleRestart(600)
        } else if (shouldListen && !isSpeaking) {
            scheduleRestart(250)
        }
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        onListeningStateChanged(false)
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val bestText = matches?.firstOrNull()?.trim()

        if (!bestText.isNullOrBlank()) {
            Log.d(TAG, "Recognized text: $bestText")
            // Requirement: Only trigger if user says "ancla"
            val hasKeyword = bestText.contains("ancla", ignoreCase = true)
            if (hasKeyword) {
                // Extract clean command without keyword prefix
                val command = bestText.replace(Regex("^(?:oye\\s+)?ancla\\s*,?\\s*", RegexOption.IGNORE_CASE), "").trim()
                val finalPrompt = if (command.isBlank()) "ancla" else command
                Log.d(TAG, "Keyword 'ancla' detected! Executing command: $finalPrompt")
                onSpeechRecognized(finalPrompt)
            } else {
                Log.d(TAG, "Ignored speech without 'ancla' keyword. Continuing listening...")
                if (shouldListen && !isSpeaking) {
                    scheduleRestart(150)
                }
            }
        } else {
            if (shouldListen && !isSpeaking) {
                scheduleRestart(150)
            }
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {}

    override fun onEvent(eventType: Int, params: Bundle?) {}

    companion object {
        private const val TAG = "VoiceAgent"
    }
}
