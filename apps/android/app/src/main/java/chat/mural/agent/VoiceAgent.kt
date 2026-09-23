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

    var isListening: Boolean = false
        private set

    init {
        mainHandler.post {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(this@VoiceAgent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing SpeechRecognizer", e)
            }
            try {
                tts = TextToSpeech(context.applicationContext, this)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing TextToSpeech", e)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.getDefault()
            Log.d(TAG, "TextToSpeech initialized successfully")
        } else {
            Log.e(TAG, "TextToSpeech initialization failed: $status")
        }
    }

    fun startListening(languageCode: String = "es-MX") {
        mainHandler.post {
            if (isListening) return@post
            try {
                tts?.stop()
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(this@VoiceAgent)
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                speechRecognizer?.startListening(intent)
                isListening = true
                onListeningStateChanged(true)
                Log.d(TAG, "SpeechRecognizer started listening in $languageCode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                isListening = false
                onListeningStateChanged(false)
                onError("No se pudo iniciar el reconocimiento de voz.")
            }
        }
    }

    fun stopListening() {
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
            }
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            try { tts?.stop() } catch (_: Exception) {}
        }
    }

    fun destroy() {
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
            else -> "Error de voz ($error)"
        }
        Log.w(TAG, "SpeechRecognizer error: $errorMsg ($error)")
        // Only notify if it's an actionable failure, not silent timeout
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_CLIENT) {
            onError(errorMsg)
        }
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        onListeningStateChanged(false)
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val bestText = matches?.firstOrNull()?.trim()
        if (!bestText.isNullOrBlank()) {
            Log.d(TAG, "Recognized text: $bestText")
            onSpeechRecognized(bestText)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        // Can be used for live preview if needed
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    companion object {
        private const val TAG = "VoiceAgent"
    }
}
