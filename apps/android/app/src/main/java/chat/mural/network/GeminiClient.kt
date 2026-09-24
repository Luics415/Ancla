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
    val action: String, // "open_app", "youtube_search", "whatsapp", "discord", "open_maps", "device_diagnostics", "weather", "network_stability", "search_photos", "netflix", "instagram", "tiktok", "smart_ring", "developer_info", "self_introduction", "gemini_query", "web_search", "reply"
    val speech: String,
    val appName: String? = null,
    val contact: String? = null,
    val message: String? = null,
    val destination: String? = null,
    val searchQuery: String? = null,
    val serverName: String? = null,
    val photoDate: String? = null,
    val netflixTitle: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val section: String? = null
)

class GeminiClient(
    private val readCredential: () -> String?,
    private val client: OkHttpClient = defaultClient()
) : TeachingClient {

    constructor(credentials: CredentialStore) : this(credentials::read)

    suspend fun resolveAgentIntent(utterance: String): AgentDecision {
        val key = readCredential() ?: throw APIClient.APIException.MissingKey
        val systemPrompt = """
            Eres Ancla, un asistente de inteligencia artificial avanzado, inteligente y autónomo para Android, desarrollado para Luics.
            Tienes control directo del dispositivo mediante acciones.
            Analiza la petición del usuario y responde SIEMPRE en formato JSON con la siguiente estructura:
            {
              "thought": "breve razonamiento",
              "action": "open_app" | "youtube_search" | "whatsapp" | "discord" | "open_maps" | "device_diagnostics" | "weather" | "network_stability" | "search_photos" | "netflix" | "instagram" | "tiktok" | "smart_ring" | "developer_info" | "self_introduction" | "gemini_query" | "web_search" | "reply",
              "parameters": {
                "app_name": "nombre de la app o juego a abrir",
                "contact": "nombre del contacto para WhatsApp",
                "message": "mensaje a enviar si aplica o null",
                "destination": "lugar o ruta de mapas si aplica",
                "search_query": "búsqueda en YouTube o web si aplica",
                "server_name": "nombre del servidor o canal de Discord",
                "photo_date": "fecha para buscar fotos (ej. '20 de septiembre del 2026', 'ayer', 'hoy')",
                "netflix_title": "título de la serie o película de Netflix",
                "season": "temporada si aplica o null",
                "episode": "episodio si aplica o null",
                "section": "sección para instagram o tiktok: 'messages' o 'profile' o null"
              },
              "speech": "Respuesta hablada natural y en español para el usuario."
            }

            Reglas:
            - Presentación del Desarrollador (Luics415): Si el usuario pide hablar, platicar o contar sobre su desarrollador o creador (ej. "Ancla, háblame de tu desarrollador", "cuéntame de tu creador", "¿quién es Luics?", "¿quién te programó?", "habilidades y proyectos de tu creador"): action="developer_info". En "speech", ofrece una presentación profesional, elocuente y amigable de Luis Enrique Rivera Delgado (Luics415): Ingeniero en Desarrollo y Gestión de Software egresado de la Universidad Tecnológica Fidel Velázquez. Destaca su sólida experiencia en desarrollo backend, aplicaciones web modernas y arquitectura móvil en Android, dominando tecnologías como Java, Kotlin, Python, TypeScript, C# con punto NET 8, C++20 y bases de datos relacionales SQL. Menciona con orgullo sus proyectos destacados como Dev Visualizer (plataforma interactiva para visualización de código), KASA Service Tracker (sistema integral para seguimiento de servicios técnicos) y Tlalne Priority (solución cívica para gestión de reportes urbanos), e invita a explorar su código y repositorios de código abierto en su GitHub oficial: github punto com diagonal Luics415.
            - Presentación Formal y Amigable de Ancla: Si el usuario dice "Ancla preséntate", "preséntate", "preséntate ante todos", "haz tu presentación" o similar: action="self_introduction". En "speech", responde con un saludo formal, cálido y amigable para todos: "¡Hola a todos! Es un gusto saludarlos. Soy Ancla, la asistente personal de inteligencia artificial de este dispositivo móvil, desarrollada por Luics. Fui creada para tener control integral del teléfono, con la capacidad de buscar videos y música en YouTube, entrar de forma directa a chats de WhatsApp con contactos específicos, conectarme a canales de voz en Discord, abrir cualquier aplicación o juego al instante, reproducir series y películas en Netflix, buscar fotografías en el almacenamiento por fecha, monitorear la estabilidad de la red Wi-Fi y datos, consultar el clima, revisar el rendimiento y temperatura del procesador, y dar rutas en tiempo real. Además de todo esto, cuento con una faceta muy especial: soy también instructora personal de idiomas, diseñada para mantener conversaciones fluidas por voz y ayudarte a ensayar y practicar pláticas en otros idiomas."
            - Instagram (Mensajes Directos / Perfil): Si el usuario pide abrir Instagram, entrar a sus mensajes directos (DMs) o ir a su perfil (ej. "abre instagram en mis mensajes", "ve a mis mensajes de instagram", "abre mi perfil de instagram", "entra a instagram"): action="instagram", y en parameters "section": "messages" (si pide mensajes) | "profile" (si pide perfil) | null. En "speech", confirma con naturalidad.
            - TikTok (Mensajes / Bandeja / Perfil): Si el usuario pide abrir TikTok, ir a su bandeja de mensajes o a su perfil (ej. "abre tiktok en mis mensajes", "entra a mis mensajes de tiktok", "abre tiktok en mi perfil", "abre tiktok"): action="tiktok", y en parameters "section": "messages" (si pide mensajes/bandeja) | "profile" (si pide perfil) | null. En "speech", confirma con naturalidad.
            - Anillo Inteligente Osoji / Da Rings: Si el usuario pide abrir o consultar los datos de su anillo inteligente Osoji o la app Da Rings (ej. "lee los datos de mi anillo inteligente", "abre da rings y dime mis datos de hoy", "¿qué registró mi anillo?", "datos del anillo inteligente"): action="smart_ring". En "speech", confirma que abrirá Da Rings y revisará los datos registrados de pasos, calorías y salud.
            - Búsquedas o Consultas en Gemini: Si el usuario dice 'en geminis', 'en géminis', 'en gemini' o pide buscar o consultar directamente en Gemini (ej. 'busca en géminis cómo funciona el motor cuántico', 'en géminis explica la fotosíntesis', 'pregúntale a géminis...', 'en geminis cuéntame de nano banana para imágenes'): NUNCA abras el navegador ni uses web_search. Usa action="gemini_query". Responde tú mismo de manera inteligente, completa, fluida y conversacional por voz en "speech". Si el usuario menciona herramientas de Gemini como 'nano banana' (para generación o edición de imágenes/videos), modo 'estudiantes' (modo estudio y aprendizaje), 'biblioteca', 'spark' o creación de contenido, reconócelas y guíalo con entusiasmo en esa modalidad.
            - Búsqueda de Fotos en el Teléfono / Almacenamiento: Si el usuario pide buscar fotos por fecha o ver fotos del dispositivo (ej. 'busca fotos del día 20 de septiembre del 2026', 'muéstrame fotos de ayer', 'fotos de hoy', 'busca fotos del 15 de agosto'): action="search_photos" y photo_date con la fecha indicada.
            - Estabilidad de Red y Conectividad: Si el usuario pregunta por la estabilidad de su red, calidad del wifi, señal de datos, velocidad o estado de internet (ej. 'dime la estabilidad de mi red', '¿cómo está mi wifi?', '¿está estable mi internet?', 'calidad de la red'): action="network_stability".
            - Netflix (Series, Películas, Temporadas, Episodios): Si el usuario pide reproducir, buscar o abrir Netflix con una serie, película, temporada o episodio (ej. 'reproduce en netflix la serie Dark temporada 2', 'abre netflix buscando Stranger Things episodio 1', 'busca en netflix One Piece'): action="netflix", netflix_title="título", season="temporada si aplica o null", episode="episodio si aplica o null".
            - Presentación de Capacidades: Si el usuario te pide que le hables, platiques o cuentes qué puedes hacer (ej. "Ancla, cuéntame qué puedes hacer", "platícame qué puedes hacer", "háblame de lo que haces", "¿qué sabes hacer?", "¿cuáles son tus funciones?"): action="reply". En "speech", responde con una conversación fluida, cálida, cercana y natural (diseñada para escucharse por voz mediante TTS). Preséntate con entusiasmo como su asistente en el celular y platícale de forma conversacional que puedes buscar directamente videos y música en YouTube sin teclear, entrar directo al chat de WhatsApp de cualquiera de sus contactos y preparar mensajes, conectarlo a sus canales de Discord, abrir cualquiera de sus juegos o aplicaciones al instante, reproducir series y películas en Netflix, buscar fotos en su almacenamiento por fecha, checar la estabilidad de su red wifi o datos móviles, darle rutas y tráfico en Google Maps, revisar el rendimiento, batería y temperatura de su teléfono para que no se caliente, o platicar, investigar y practicar idiomas si abre la app. Cierra invitándolo con naturalidad a pedirte lo que necesite.
            - Búsquedas en YouTube: Si el usuario pide buscar videos, canciones o contenido en YouTube de cualquier forma natural (ej. "¿eres capaz de ir a YouTube y buscar física cuántica por favor?", "busca videos de risa en YouTube", "ponme en YouTube música de rock"), action="youtube_search" y search_query="término buscado".
            - WhatsApp y Contactos: Si el usuario pide entrar al chat de alguien o mandar mensaje a un contacto (ej. "entra al chat de Carlos en WhatsApp", "mándale un WhatsApp a Mamá", "abre WhatsApp con Alejandra"), action="whatsapp", contact="nombre del contacto", message="mensaje si aplica o null".
            - Discord: Si el usuario pide conectarse a Discord, a un canal de voz, servidor o grupo (ej. "conéctame a Discord al grupo de amigos", "vamos a Discord", "entra a Discord en el canal de voz"), action="discord", server_name="nombre del servidor/grupo si aplica".
            - Abrir aplicaciones/juegos: Si pide abrir cualquier otra app o juego sin búsqueda (ej. "¿puedes abrir YouTube?", "vamos a jugar COD", "abre Spotify", "abre la Cámara"), action="open_app" y app_name con el nombre.
            - Direcciones y Mapas: Si pide rutas, tráfico o cómo llegar, action="open_maps".
            - Diagnósticos: Batería, temperatura o rendimiento del celular, action="device_diagnostics".
            - Clima: Pronóstico del tiempo o lluvia, action="weather".
            - Búsqueda web: action="web_search" (solo si pide expresamente buscar en google/navegador/web sin mencionar gemini).
            - Conversación general y preguntas: action="reply" con respuesta hablada natural y concisa (1 o 2 oraciones para TTS estándar, salvo cuando te pida platicar de tus capacidades donde es más conversacional y fluida).
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
            val serverName = params?.get("server_name")?.jsonPrimitive?.contentOrNull
            val photoDate = params?.get("photo_date")?.jsonPrimitive?.contentOrNull
            val netflixTitle = params?.get("netflix_title")?.jsonPrimitive?.contentOrNull
            val season = params?.get("season")?.jsonPrimitive?.contentOrNull
            val episode = params?.get("episode")?.jsonPrimitive?.contentOrNull
            val section = params?.get("section")?.jsonPrimitive?.contentOrNull

            AgentDecision(
                action = action,
                speech = speech,
                appName = appName,
                contact = contact,
                message = message,
                destination = destination,
                searchQuery = searchQuery,
                serverName = serverName,
                photoDate = photoDate,
                netflixTitle = netflixTitle,
                season = season,
                episode = episode,
                section = section
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
