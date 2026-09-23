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

class GeminiClient(
    private val readCredential: () -> String?,
    private val client: OkHttpClient = defaultClient()
) : TeachingClient {

    constructor(credentials: CredentialStore) : this(credentials::read)

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?
    ): APIResult {
        val key = readCredential() ?: throw APIClient.APIException.MissingKey

        // Build Gemini payload
        val body = buildJsonObject {
            if (instructions.isNotBlank()) {
                put("system_instruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", instructions) })
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

        // Use gemini-3.6-flash as primary (reliable, fast, high quota)
        // Fallback to gemini-3.5-flash or gemini-3.7-flash if needed
        return try {
            executeGeminiRequest("gemini-3.6-flash", key, body)
        } catch (e: Exception) {
            android.util.Log.w("GeminiClient", "gemini-3.6-flash failed: ${e.message}, falling back to gemini-3.5-flash", e)
            try {
                executeGeminiRequest("gemini-3.5-flash", key, body)
            } catch (e2: Exception) {
                android.util.Log.w("GeminiClient", "gemini-3.5-flash failed: ${e2.message}, falling back to gemini-3.7-flash", e2)
                executeGeminiRequest("gemini-3.7-flash", key, body)
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
