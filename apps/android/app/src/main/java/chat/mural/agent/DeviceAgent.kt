package chat.mural.agent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import java.net.URLEncoder
import java.text.Normalizer

import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.TimeUnit

data class AppLaunchResult(
    val success: Boolean,
    val appName: String,
    val packageName: String? = null,
    val message: String
)

data class DeviceDiagnostics(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val temperatureCelsius: Float,
    val thermalStatusDescription: String,
    val freeRamGb: Float,
    val totalRamGb: Float,
    val performanceSummary: String
)

object DeviceAgent {

    /**
     * Normalizes text by removing accents, punctuation, and lowercase conversion.
     */
    private fun normalize(text: String): String {
        val normalized = Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
    }

    /**
     * Resolves and launches an application (or game) installed on the phone.
     * Searches through all installed launcher activities to find games even inside home screen folders.
     */
    fun openApp(context: Context, query: String): AppLaunchResult {
        val cleanQuery = normalize(query)
        if (cleanQuery.isBlank()) {
            return AppLaunchResult(false, query, message = "No se especificó ninguna aplicación.")
        }

        val pm = context.packageManager

        // Known direct shortcuts
        when (cleanQuery) {
            "whatsapp", "wasap", "guasap" -> {
                val intent = pm.getLaunchIntentForPackage("com.whatsapp")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return AppLaunchResult(true, "WhatsApp", "com.whatsapp", "Abriendo WhatsApp...")
                }
            }
            "youtube", "yutub" -> {
                val intent = pm.getLaunchIntentForPackage("com.google.android.youtube")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return AppLaunchResult(true, "YouTube", "com.google.android.youtube", "Abriendo YouTube...")
                }
            }
            "maps", "mapas", "google maps" -> {
                val intent = pm.getLaunchIntentForPackage("com.google.android.apps.maps")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return AppLaunchResult(true, "Google Maps", "com.google.android.apps.maps", "Abriendo Google Maps...")
                }
            }
            "spotify" -> {
                val intent = pm.getLaunchIntentForPackage("com.spotify.music")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return AppLaunchResult(true, "Spotify", "com.spotify.music", "Abriendo Spotify...")
                }
            }
            "camara", "camera", "fotos" -> {
                val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (cameraIntent.resolveActivity(pm) != null) {
                    context.startActivity(cameraIntent)
                    return AppLaunchResult(true, "Cámara", message = "Abriendo la cámara...")
                }
            }
            "ajustes", "configuracion", "settings" -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return AppLaunchResult(true, "Ajustes", message = "Abriendo ajustes del sistema...")
            }
        }

        // Generic search through all launchable activities (includes games inside folders)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveList: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        var exactMatch: ResolveInfo? = null
        var startsWithMatch: ResolveInfo? = null
        var containsMatch: ResolveInfo? = null

        for (info in resolveList) {
            val label = normalize(info.loadLabel(pm).toString())
            if (label == cleanQuery) {
                exactMatch = info
                break
            } else if (label.startsWith(cleanQuery) && startsWithMatch == null) {
                startsWithMatch = info
            } else if (label.contains(cleanQuery) && containsMatch == null) {
                containsMatch = info
            }
        }

        val bestMatch = exactMatch ?: startsWithMatch ?: containsMatch
        if (bestMatch != null) {
            val pkg = bestMatch.activityInfo.packageName
            val realName = bestMatch.loadLabel(pm).toString()
            val launchIntent = pm.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                return AppLaunchResult(true, realName, pkg, "Abriendo $realName...")
            }
        }

        return AppLaunchResult(false, query, message = "No encontré ninguna aplicación o juego llamado \"$query\".")
    }

    /**
     * Opens WhatsApp with optional pre-filled message or target number.
     */
    fun openWhatsApp(context: Context, message: String? = null, phone: String? = null): Boolean {
        return try {
            val uriBuilder = StringBuilder("https://api.whatsapp.com/send?")
            if (!phone.isNullOrBlank()) {
                val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
                uriBuilder.append("phone=").append(cleanPhone).append("&")
            }
            if (!message.isNullOrBlank()) {
                uriBuilder.append("text=").append(URLEncoder.encode(message.trim(), "UTF-8"))
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriBuilder.toString())).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                openApp(context, "whatsapp").success
            }
        } catch (_: Exception) {
            openApp(context, "whatsapp").success
        }
    }

    /**
     * Finds a contact's phone number in device address book using normalized matching.
     */
    fun findContactPhoneNumber(context: Context, targetName: String): Pair<String, String>? {
        if (targetName.isBlank()) return null
        val cleanTarget = normalize(targetName)
        val cr = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var bestMatchNumber: String? = null
        var bestMatchName: String? = null
        var bestScore = 0

        try {
            val cursor = cr.query(uri, projection, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: continue
                    val number = it.getString(numberIndex) ?: continue
                    val cleanName = normalize(name)

                    if (cleanName == cleanTarget) {
                        return Pair(name, cleanPhoneNumber(number))
                    } else if (cleanName.startsWith(cleanTarget) && bestScore < 3) {
                        bestMatchName = name
                        bestMatchNumber = cleanPhoneNumber(number)
                        bestScore = 3
                    } else if (cleanName.contains(cleanTarget) && bestScore < 2) {
                        bestMatchName = name
                        bestMatchNumber = cleanPhoneNumber(number)
                        bestScore = 2
                    } else if (cleanTarget.contains(cleanName) && bestScore < 1) {
                        bestMatchName = name
                        bestMatchNumber = cleanPhoneNumber(number)
                        bestScore = 1
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DeviceAgent", "Error querying contacts: ${e.message}")
        }

        return if (bestMatchNumber != null && bestMatchName != null) {
            Pair(bestMatchName, bestMatchNumber)
        } else null
    }

    private fun cleanPhoneNumber(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }

    /**
     * Opens WhatsApp directly targeting a registered contact by name or generic.
     */
    fun openWhatsAppContact(context: Context, contactQuery: String?, message: String? = null): AppLaunchResult {
        if (contactQuery.isNullOrBlank()) {
            val opened = openWhatsApp(context, message)
            return AppLaunchResult(opened, "WhatsApp", message = if (opened) "Abriendo WhatsApp..." else "No pude abrir WhatsApp.")
        }

        val found = findContactPhoneNumber(context, contactQuery)
        return if (found != null) {
            val (realName, phone) = found
            val opened = openWhatsApp(context, message, phone)
            val msg = if (!message.isNullOrBlank()) {
                "Abriendo chat de $realName en WhatsApp con tu mensaje..."
            } else {
                "Abriendo chat de $realName en WhatsApp..."
            }
            AppLaunchResult(opened, "WhatsApp", "com.whatsapp", msg)
        } else {
            openWhatsApp(context, message)
            AppLaunchResult(true, "WhatsApp", "com.whatsapp", "No encontré a \"$contactQuery\" en tus contactos, abriendo WhatsApp...")
        }
    }

    /**
     * Executes direct search in YouTube or opens relevant query.
     */
    fun searchYouTube(context: Context, query: String): AppLaunchResult {
        return try {
            val encoded = Uri.encode(query.trim())
            val appIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query.trim())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (appIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(appIntent)
                AppLaunchResult(true, "YouTube", "com.google.android.youtube", "Buscando \"$query\" en YouTube...")
            } else {
                val uri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
                val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (webIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(webIntent)
                    AppLaunchResult(true, "YouTube", "com.google.android.youtube", "Buscando \"$query\" en YouTube...")
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    AppLaunchResult(true, "YouTube Web", message = "Buscando \"$query\" en YouTube...")
                }
            }
        } catch (e: Exception) {
            AppLaunchResult(false, "YouTube", message = "No pude realizar la búsqueda en YouTube.")
        }
    }

    /**
     * Opens Discord or connects to voice / server.
     */
    fun openDiscord(context: Context, serverOrChannel: String? = null): AppLaunchResult {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage("com.discord")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val msg = if (!serverOrChannel.isNullOrBlank()) {
                    "Abriendo Discord para conectarte a $serverOrChannel..."
                } else {
                    "Abriendo Discord..."
                }
                AppLaunchResult(true, "Discord", "com.discord", msg)
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/channels/@me")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                AppLaunchResult(true, "Discord Web", message = "Abriendo Discord en el navegador...")
            }
        } catch (e: Exception) {
            AppLaunchResult(false, "Discord", message = "No pude abrir Discord.")
        }
    }

    /**
     * Opens Google Maps for search, routing, or live navigation.
     */
    fun openMaps(context: Context, query: String, navigation: Boolean = true): Boolean {
        return try {
            val encoded = Uri.encode(query.trim())
            val uri = if (navigation) {
                Uri.parse("google.navigation:q=$encoded")
            } else {
                Uri.parse("geo:0,0?q=$encoded")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens web search or specific URL in default browser.
     */
    fun searchWeb(context: Context, query: String): Boolean {
        return try {
            val url = if (query.startsWith("http://") || query.startsWith("https://")) {
                query
            } else {
                "https://www.google.com/search?q=" + Uri.encode(query.trim())
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Inspects device performance, RAM, battery health, and thermal status.
     */
    fun getDiagnostics(context: Context): DeviceDiagnostics {
        val batteryStatus: Intent? = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val rawTemp = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = rawTemp / 10.0f

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalStatusDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Fresco / Óptimo"
                PowerManager.THERMAL_STATUS_LIGHT -> "Ligero (Temperatura normal)"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderado (Tibio)"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severo (Caliente - Celular reduciendo consumo)"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Crítico (Muy caliente)"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergencia por calor"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Apagado por calor inminente"
                else -> "Normal"
            }
        } else {
            if (tempCelsius < 36.0f) "Fresco" else if (tempCelsius < 41.0f) "Normal" else "Caliente"
        }

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val freeGb = memInfo.availMem / (1024f * 1024f * 1024f)
        val totalGb = memInfo.totalMem / (1024f * 1024f * 1024f)

        val summary = buildString {
            append("🔋 Batería: $batteryPct% ${if (isCharging) "(Cargando)" else "(Descargando)"}.\n")
            append("🌡️ Temperatura: ${String.format("%.1f", tempCelsius)}°C ($thermalStatusDesc).\n")
            append("🧠 Memoria RAM: ${String.format("%.1f", freeGb)} GB libres de ${String.format("%.1f", totalGb)} GB.\n")
            if (tempCelsius < 38.0f && !memInfo.lowMemory) {
                append("✅ El rendimiento es excelente y el celular está frío y fluido.")
            } else if (tempCelsius >= 40.0f) {
                append("⚠️ Temperatura algo elevada. Te recomiendo cerrar juegos pesados o apps en segundo plano para enfriar el procesador.")
            } else {
                append("ℹ️ El estado del sistema es estable.")
            }
        }

        return DeviceDiagnostics(
            batteryPercent = batteryPct,
            isCharging = isCharging,
            temperatureCelsius = tempCelsius,
            thermalStatusDescription = thermalStatusDesc,
            freeRamGb = freeGb,
            totalRamGb = totalGb,
            performanceSummary = summary
        )
    }

    /**
     * Retrieves current real-time weather using device coordinates or defaults via Open-Meteo.
     */
    fun getWeather(context: Context): String {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            var lat = 19.4326
            var lon = -99.1332
            if (lm != null) {
                try {
                    val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        ?: lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
                    if (loc != null) {
                        lat = loc.latitude
                        lon = loc.longitude
                    }
                } catch (_: SecurityException) {}
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,weather_code"
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return "No pude obtener los datos del clima en este momento."

            val body = resp.body?.string() ?: return "No se recibió respuesta del servicio de clima."
            val json = Json.parseToJsonElement(body).jsonObject
            val current = json["current"]?.jsonObject ?: return "Información meteorológica no disponible."
            val temp = current["temperature_2m"]?.jsonPrimitive?.floatOrNull ?: 20f
            val humidity = current["relative_humidity_2m"]?.jsonPrimitive?.intOrNull ?: 50
            val code = current["weather_code"]?.jsonPrimitive?.intOrNull ?: 0

            val condition = when (code) {
                0 -> "cielo despejado y soleado"
                1, 2 -> "cielo con algunas nubes"
                3 -> "cielo nublado"
                45, 48 -> "niebla matutina"
                51, 53, 55 -> "llovizna ligera"
                61, 63, 65 -> "lluvia"
                71, 73, 75 -> "nieve"
                80, 81, 82 -> "chubascos dispersos"
                95, 96, 99 -> "tormenta eléctrica"
                else -> "condiciones estables"
            }

            "Actualmente tenemos ${String.format("%.1f", temp)}°C con $condition y una humedad del $humidity%."
        } catch (e: Exception) {
            android.util.Log.e("DeviceAgent", "Error fetching weather", e)
            "No pude conectar con el servicio del clima. Por favor revisa tu conexión a internet."
        }
    }

    /**
     * Inspects text for actionable assistant device commands.
     * Returns a user-facing response string if executed, or null if it's general conversation/question for Gemini.
     */
    fun executeCommandIfMatched(context: Context, rawText: String): String? {
        val trimmed = rawText.trim()
        val lower = normalize(trimmed)

        // 0. Greeting / Wake word response
        if (lower == "ancla" || lower == "oye ancla" || lower == "hola ancla" || lower == "hola") {
            return "¡Hola! Estoy aquí escuchándote. ¿En qué te puedo ayudar?"
        }

        // 1. Device Diagnostics / Performance / Temperature
        if (lower.contains("diagnostico") ||
            (lower.contains("bateria") && (lower.contains("como") || lower.contains("cuanta") || lower.contains("nivel") || lower.contains("estado"))) ||
            (lower.contains("temperatura") && (lower.contains("como") || lower.contains("cuanta") || lower.contains("celular") || lower.contains("telefono"))) ||
            (lower.contains("rendimiento") && (lower.contains("celular") || lower.contains("telefono") || lower.contains("como"))) ||
            (lower.contains("calienta") || lower.contains("sobrecalienta") || lower.contains("caliente")) && (lower.contains("celular") || lower.contains("telefono") || lower.contains("esta"))
        ) {
            return getDiagnostics(context).performanceSummary
        }

        // 2. WhatsApp with custom message or chat
        val waRegex = Regex("^(?:ancla\\s*,?\\s*)?(?:abre|abrir|manda|enviar|envia)?\\s*(?:un\\s+)?(?:mensaje\\s+(?:por|de|en)\\s+)?whatsapp(?:\\s+al\\s+chat\\s+(?:de\\s+)?([^y]+?))?(?:\\s+y\\s+(?:manda|que\\s+diga|con\\s+el\\s+mensaje)\\s+(.+))?$", RegexOption.IGNORE_CASE)
        val waMatch = waRegex.find(trimmed)
        if (waMatch != null && (trimmed.contains("whatsapp", ignoreCase = true) || trimmed.contains("wasap", ignoreCase = true))) {
            val contact = waMatch.groupValues.getOrNull(1)?.trim()
            val msg = waMatch.groupValues.getOrNull(2)?.trim()
            if (!msg.isNullOrBlank() || !contact.isNullOrBlank()) {
                openWhatsApp(context, message = msg, phone = contact)
                return if (!contact.isNullOrBlank() && !msg.isNullOrBlank()) {
                    "Abriendo WhatsApp para el chat \"$contact\" con tu mensaje: \"$msg\"."
                } else if (!msg.isNullOrBlank()) {
                    "Abriendo WhatsApp con tu mensaje: \"$msg\" listo para enviar."
                } else {
                    "Abriendo WhatsApp para contactar a \"$contact\"."
                }
            }
        }

        // 3. Google Maps / Navigation / Traffic
        val mapsRegex = Regex("^(?:ancla\\s*,?\\s*)?(?:abre|abrir|busca\\s+en|como\\s+llegar\\s+a|ruta\\s+a|trafico\\s+a|navega\\s+a)\\s+(?:maps|mapas|google\\s+maps)?\\s*(?:hacia|a|para)?\\s*(.+)$", RegexOption.IGNORE_CASE)
        if (lower.startsWith("como llegar a") || lower.startsWith("ruta a") || lower.startsWith("trafico a") ||
            (lower.contains("maps") && (lower.contains("abre") || lower.contains("busca") || lower.contains("ir a")))) {
            val dest = trimmed.replace(Regex("^(?:ancla\\s*,?\\s*)?(?:abre|abrir|busca\\s+en|como\\s+llegar\\s+a|ruta\\s+a|trafico\\s+a|navega\\s+a)\\s*(?:maps|mapas|google\\s+maps)?\\s*(?:hacia|a|para)?\\s*", RegexOption.IGNORE_CASE), "").trim()
            if (dest.isNotBlank()) {
                openMaps(context, dest, navigation = true)
                return "Abriendo Google Maps con la ruta e información de tráfico hacia \"$dest\"."
            }
        }

        // 4. Web Search
        if (lower.startsWith("busca en la web") || lower.startsWith("busca en google") || lower.startsWith("busca en internet")) {
            val query = trimmed.replace(Regex("^(?:ancla\\s*,?\\s*)?busca\\s+en\\s+(?:la\\s+web|google|internet)\\s*", RegexOption.IGNORE_CASE), "").trim()
            if (query.isNotBlank()) {
                searchWeb(context, query)
                return "Abriendo el navegador y buscando: \"$query\"..."
            }
        }

        // 5. Generic App or Game launcher (including games inside folders)
        val appLaunchRegex = Regex("^(?:ancla\\s*,?\\s*)?(?:abre|abrir|ejecuta|ejecutar|inicia|iniciar|lanza|lanzar|juega|jugar)\\s+(?:el\\s+|la\\s+|el\\s+juego\\s+(?:de\\s+)?|la\\s+app\\s+(?:de\\s+)?|la\\s+aplicacion\\s+(?:de\\s+)?)?(.+)$", RegexOption.IGNORE_CASE)
        val launchMatch = appLaunchRegex.find(trimmed)
        if (launchMatch != null) {
            val candidateApp = launchMatch.groupValues[1].trim()
            // Avoid triggering on non-app phrases like "abre la puerta", "abre los ojos"
            if (candidateApp.isNotBlank() && candidateApp.length in 2..40 &&
                !candidateApp.equals("la puerta", ignoreCase = true) &&
                !candidateApp.equals("los ojos", ignoreCase = true)
            ) {
                val result = openApp(context, candidateApp)
                if (result.success) {
                    return result.message
                }
            }
        }

        return null
    }
}

