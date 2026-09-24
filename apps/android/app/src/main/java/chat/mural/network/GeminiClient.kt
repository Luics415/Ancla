package chat.mural.network

import chat.mural.core.SourceLink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class AgentDecision(
    val action: String, // "open_app", "whatsapp", "open_maps", "device_diagnostics", "weather", "web_search", "reply"
    val speech: String,
    val appName: String? = null,
    val contact: String? = null,
    val message: String? = null,
    val destination: String? = null,
    val searchQuery: String? = null
)

class GeminiClient(
    private val readCredential: () -> String?,
    private val client: OkHttpClient = defaultClient()
) : TeachingClient {

    constructor(credentials: CredentialStore) : this(credentials::read)

    suspend fun resolveAgentIntent(utterance: String): AgentDecision {
        val key = readCredential() ?: throw APIClient.APIException.MissingKey
        val systemPrompt = """
            Eres Ancla, un asistente de inteligencia artificial avanzado, inteligente y autónomo para Android, desarrollado por Luics415.
            Tienes control directo del dispositivo mediante acciones.
            Analiza la petición del usuario y responde SIEMPRE en formato JSON con la siguiente estructura:
            {
              "thought": "breve razonamiento",
              "action": "open_app" | "whatsapp" | "open_maps" | "device_diagnostics" | "weather" | "web_search" | "reply",
              "parameters": {
                "app_name": "nombre de la app o juego a abrir (ej. YouTube, WhatsApp, Free Fire, Ajustes, Cámara, Spotify)",
                "contact": "contacto de whatsapp si aplica",
                "message": "mensaje de whatsapp si aplica",
                "destination": "lugar o ruta de mapas si aplica",
                "search_query": "búsqueda web si aplica"
              },
              "speech": "Respuesta hablada natural, concisa y en español para el usuario (máximo 1 o 2 oraciones breves para TTS)."
            }

            Reglas:
            - Si el usuario pide abrir, poner, ver, jugar o ejecutar cualquier aplicación o juego (ej. "¿puedes abrir YouTube?", "quiero ver videos", "pon Spotify", "vamos a jugar COD", "abre WhatsApp"), action="open_app" y app_name con el nombre.
            - Si pide enviar un mensaje por WhatsApp, action="whatsapp".
            - Si pide direcciones, rutas, tráfico o mapas, action="open_maps".
            - Si pregunta por el estado del celular, batería, temperatura o si está lento/caliente, action="device_diagnostics".
            - Si pregunta por el clima o si va a llover, action="weather".
            - Si pide buscar en internet o google, action="web_search".
            - Si es una pregunta de conocimiento general, conversación, ayuda o explicación, action="reply" con la respuesta en "speech".
            - La clave única para activarte es 'ancla'. Todo el lenguaje es natural, flexible e inteligente.
        """.trimIndent()

        val body = buildJsonObject {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", utterance) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
            })
        }

        val result = try {
            executeGeminiRequest("gemini-3.5-flash-lite", key, body)
        } catch (e: Exception) {
            android.util.Log.w("GeminiClient", "gemini-3.5-flash-lite failed: ${e.message}, falling back to gemini-3.1-flash-lite", e)
            executeGeminiRequest("gemini-3.1-flash-lite", key, body)
        }

        return try {
            val json = JSON.parseToJsonElement(result.text).jsonObject
            val action = json["action"]?.jsonPrimitive?.contentOrNull ?: "reply"
            val speech = json["speech"]?.jsonPrimitive?.contentOrNull ?: result.text
            val params = json["parameters"]?.jsonObject
            val appName = params?.get("app_name")?.jsonPrimitive?.contentOrNull
            val contact = params?.get("contact")?.jsonPrimitive?.contentOrNull
            val message = params?.get("message")?.jsonPrimitive?.contentOrNull
            val destination = params?.get("destination")?.jsonPrimitive?.contentOrNull
            val searchQuery = params?.get("search_query")?.jsonPrimitive?.contentOrNull

            AgentDecision(
                action = action,
                speech = speech,
                appName = appName,
                contact = contact,
                message = message,
                destination = destination,
                searchQuery = searchQuery
            )
        } catch (e: Exception) {
            android.util.Log.e("GeminiClient", "Failed to parse agent JSON: ${result.text}", e)
            AgentDecision(action = "reply", speech = result.text)
        }
    }

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?
    ): APIResult {
        val key = readCredential() ?: throw APIClient.APIException.MissingKey

        val effectiveInstructions = if (instructions.contains("English") || instructions.contains("conversation partner") || instructions.contains("practise")) {
            "Eres Ancla, un asistente personal de inteligencia artificial autónomo y resolutivo para Android, desarrollado por Luics415. Respondes siempre en español de forma natural, concisa y directa (máximo 1 o 2 oraciones para ser escuchadas por voz)."
        } else {
            instructions
        }

        // Build Gemini payload
        val body = buildJsonObject {
            if (effectiveInstructions.isNotBlank()) {
                put("system_instruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", effectiveInstructions) })
                    })
                })
            }
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", input) })
                    })
                })
            })
            val genConfig = buildJsonObject {
                if (schema != null) {
                    put("responseMimeType", "application/json")
                    put("responseSchema", sanitizeSchemaForGemini(schema))
                }
            }
            if (genConfig.isNotEmpty()) {
                put("generationConfig", genConfig)
            }
            if (search) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("googleSearch", buildJsonObject {})
                    })
                })
            }
        }

        // Use gemini-3.5-flash-lite as primary (supported by Google API for this key with instant response)
        // Fallback to gemini-3.1-flash-lite or gemini-3.6-flash
        return try {
            executeGeminiRequest("gemini-3.5-flash-lite", key, body)
        } catch (e: Exception) {
            android.util.Log.w("GeminiClient", "gemini-3.5-flash-lite failed: ${e.message}, falling back to gemini-3.1-flash-lite", e)
            try {
                executeGeminiRequest("gemini-3.1-flash-lite", key, body)
            } catch (e2: Exception) {
                android.util.Log.w("GeminiClient", "gemini-3.1-flash-lite failed: ${e2.message}, falling back to gemini-3.6-flash", e2)
                executeGeminiRequest("gemini-3.6-flash", key, body)
            }
        }
    }

    override suspend fun streamMeaning(
        instructions: String,
        input: String,
        onText: (String) -> Unit
    ): APIResult {
        val result = respond(instructions, input, schema = null, search = false, purpose = HelperPurpose.MEANING)
        onText(result.text)
        return result
    }

    private suspend fun executeGeminiRequest(
        model: String,
        apiKey: String,
        body: JsonObject
    ): APIResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    android.util.Log.e("GeminiClient", "Request failed with IOException: ${error.message}", error)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val payload = response.use { resp ->
                            val respBody = resp.body?.string() ?: ""
                            android.util.Log.d("GeminiClient", "Response code: ${resp.code} for $model")
                            if (!resp.isSuccessful) {
                                android.util.Log.e("GeminiClient", "Error response: $respBody")
                                val status = resp.code
                                val errorObj = runCatching {
                                    JSON.parseToJsonElement(respBody).jsonObject["error"]?.jsonObject
                                }.getOrNull()
                                val message = errorObj?.get("message")?.jsonPrimitive?.contentOrNull
                                val code = errorObj?.get("status")?.jsonPrimitive?.contentOrNull
                                throw APIClient.APIException.Http(status, code ?: message, null)
                            }
                            respBody
                        }

                        val parsed = JSON.parseToJsonElement(payload).jsonObject
                        val candidates = parsed["candidates"]?.jsonArray
                        val firstCandidate = candidates?.firstOrNull()?.jsonObject
                        val parts = firstCandidate?.get("content")?.jsonObject?.get("parts")?.jsonArray

                        val fullText = StringBuilder()
                        parts?.forEach { partElement ->
                            val part = partElement.jsonObject
                            // Skip thoughts if marked as thought
                            val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                            val text = part["text"]?.jsonPrimitive?.contentOrNull
                            if (!isThought && !text.isNullOrBlank()) {
                                fullText.append(text)
                            }
                        }

                        val usageMeta = parsed["usageMetadata"]?.jsonObject
                        val inputTokens = usageMeta?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
                        val outputTokens = usageMeta?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0

                        val finalResult = APIResult(
                            text = fullText.toString().trim(),
                            sources = emptyList(),
                            usage = APIUsage(input = inputTokens, output = outputTokens, searches = 0)
                        )

                        if (continuation.isActive) continuation.resume(finalResult)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun sanitizeSchemaForGemini(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            val allowedKeys = setOf(
                "type", "format", "description", "nullable", "enum",
                "properties", "required", "items"
            )
            buildJsonObject {
                element.forEach { (key, value) ->
                    if (key in allowedKeys) {
                        when (key) {
                            "properties" -> if (value is JsonObject) {
                                put("properties", buildJsonObject {
                                    value.forEach { (propKey, propVal) ->
                                        put(propKey, sanitizeSchemaForGemini(propVal))
                                    }
                                })
                            } else {
                                put(key, sanitizeSchemaForGemini(value))
                            }
                            "items" -> put(key, sanitizeSchemaForGemini(value))
                            else -> put(key, value)
                        }
                    }
                }
            }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(sanitizeSchemaForGemini(it)) }
        }
        else -> element
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
