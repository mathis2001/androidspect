package com.androidspect.server.routes

import android.content.Context
import android.content.pm.PackageManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Firebase Remote Config detector and fetcher.
 *
 * Detection strategy (in order, stops at first success):
 *   1. assets/google-services.json  (rare, some builds bundle it)
 *   2. DEX string pool — scan all .dex for known Firebase string patterns:
 *        - API key:      AIza[0-9A-Za-z\-_]{35}
 *        - Project ID:   [a-z0-9\-]+ near ".firebaseapp.com" or "firebaseio.com"
 *        - App ID:       1:\d+:android:[0-9a-f]+
 *        - Project num:  \d{12,} near firebase context
 *   3. resources.arsc binary scan — scan raw bytes for UTF-16LE Firebase strings
 *   4. AndroidManifest.xml meta-data (google_app_id etc.)
 */
fun Routing.remoteConfigRoutes(context: Context) {

    route("/api/remoteconfig") {
        get {
            val pkg = call.request.queryParameters["pkg"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "pkg required"))

            val result = withContext(Dispatchers.IO) {
                runCatching { detectAndFetch(pkg, context) }
                    .getOrElse { e ->
                        RemoteConfigResult(pkg = pkg, firebaseDetected = false,
                            remoteConfigDetected = false,
                            error = "${e::class.java.simpleName}: ${e.message?.take(400)}")
                    }
            }
            call.respond(result)
        }
    }
}

// ── Main logic ─────────────────────────────────────────────────────────────────

internal fun detectAndFetch(pkg: String, context: Context): RemoteConfigResult {
    val apkPath = try {
        context.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA).sourceDir
    } catch (e: Exception) {
        return RemoteConfigResult(pkg = pkg, firebaseDetected = false,
            remoteConfigDetected = false, error = "Package not found: $pkg")
    }

    val config = extractFirebaseConfig(File(apkPath), pkg)
        ?: return RemoteConfigResult(
            pkg = pkg, firebaseDetected = false, remoteConfigDetected = false,
            error = "No Firebase configuration found in APK.\n" +
                    "Checked: assets/google-services.json, DEX string pool " +
                    "(AIza API keys, app IDs 1:N:android:H, *.firebaseapp.com domains), " +
                    "resources.arsc binary string pool.\n" +
                    "This app does not appear to use Firebase, or values are heavily obfuscated."
        )

    val usesRC = checkRemoteConfigInDex(File(apkPath))
    if (!usesRC) {
        return RemoteConfigResult(
            pkg = pkg, firebaseDetected = true, remoteConfigDetected = false,
            firebaseConfig = config,
            note = "Firebase SDK detected but FirebaseRemoteConfig class not found in DEX. " +
                   "The app may not use Remote Config."
        )
    }

    val fetch = fetchRemoteConfig(config, pkg)
    return RemoteConfigResult(
        pkg                  = pkg,
        firebaseDetected     = true,
        remoteConfigDetected = true,
        firebaseConfig       = config,
        fetchStatus          = fetch.status,
        fetchError           = fetch.error,
        parameters           = fetch.parameters,
        rawResponse          = fetch.rawResponse
    )
}

// ── Firebase config extraction ─────────────────────────────────────────────────

private val RE_API_KEY    = Regex("""AIza[0-9A-Za-z\-_]{35}""")
private val RE_APP_ID     = Regex("""1:\d{7,20}:android:[0-9a-f]{16,32}""")
private val RE_PROJECT_ID = Regex("""([a-z0-9][a-z0-9\-]{2,40})\.firebaseapp\.com""")
private val RE_PROJECT_ID2= Regex("""([a-z0-9][a-z0-9\-]{2,40})\.firebaseio\.com""")

private fun extractFirebaseConfig(apk: File, pkg: String): FirebaseConfig? {
    ZipFile(apk).use { zip ->

        // 1. assets/google-services.json
        zip.getEntry("assets/google-services.json")?.let { entry ->
            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            parseGoogleServicesJson(text, pkg)?.let { return it }
        }

        // 2. Scan all .dex string pools (ISO-8859-1 so byte values pass through).
        var apiKey: String? = null
        var appId: String?  = null
        var projectId: String? = null

        zip.entries().asSequence().filter { it.name.endsWith(".dex") }.forEach { entry ->
            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
            if (apiKey    == null) apiKey    = RE_API_KEY.find(text)?.value
            if (appId     == null) appId     = RE_APP_ID.find(text)?.value
            if (projectId == null) projectId =
                RE_PROJECT_ID.find(text)?.groupValues?.get(1)
                    ?: RE_PROJECT_ID2.find(text)?.groupValues?.get(1)
        }

        // 3. Scan resources.arsc for UTF-16LE Firebase strings.
        if (apiKey == null || projectId == null) {
            zip.getEntry("resources.arsc")?.let { entry ->
                // resources.arsc encodes strings as UTF-16LE in a string pool.
                // We convert to a searchable string by reading as ISO-8859-1
                // then also reading as UTF-16LE pairs.
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val iso   = bytes.toString(Charsets.ISO_8859_1)
                val utf16 = try {
                    // Build a string from every other byte pair (little-endian UTF-16).
                    val sb = StringBuilder(bytes.size / 2)
                    var i = 0
                    while (i + 1 < bytes.size) {
                        val lo = bytes[i].toInt() and 0xFF
                        val hi = bytes[i + 1].toInt() and 0xFF
                        val ch = (hi shl 8) or lo
                        if (ch in 0x20..0x7E || ch == 0) sb.append(ch.toChar())
                        else sb.append(' ')
                        i += 2
                    }
                    sb.toString()
                } catch (_: Exception) { "" }

                for (text in listOf(iso, utf16)) {
                    if (apiKey    == null) apiKey    = RE_API_KEY.find(text)?.value
                    if (appId     == null) appId     = RE_APP_ID.find(text)?.value
                    if (projectId == null) projectId =
                        RE_PROJECT_ID.find(text)?.groupValues?.get(1)
                            ?: RE_PROJECT_ID2.find(text)?.groupValues?.get(1)
                }
            }
        }

        if (apiKey != null || appId != null || projectId != null) {
            return FirebaseConfig(
                projectId     = projectId ?: "unknown",
                projectNumber = extractNumberFromAppId(appId),
                apiKey        = apiKey,
                appId         = appId,
                source        = "APK string scan (DEX + resources.arsc)"
            )
        }

        return null
    }
}

private fun extractProjectFromAppId(appId: String?): String? {
    // App ID format: 1:PROJECT_NUMBER:android:HASH
    // Project ID is not directly encoded in the app ID, but the project number is.
    return null
}

private fun extractNumberFromAppId(appId: String?): String? {
    // 1:PROJECT_NUMBER:android:HASH
    return appId?.split(":")?.getOrNull(1)
}

private fun parseGoogleServicesJson(text: String, pkg: String): FirebaseConfig? {
    return try {
        val raw = text.replace(Regex("//[^\n]*"), "") // strip comments
        val apiKey    = Regex(""""current_key"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1)
        val projectId = Regex(""""project_id"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1)
        val projectNo = Regex(""""project_number"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1)
        val appIdRe   = Regex(""""mobilesdk_app_id"\s*:\s*"([^"]+)"""")
        // Find app_id near our package name.
        val pkgIdx = raw.indexOf(pkg)
        val appId = if (pkgIdx >= 0) {
            val window = raw.substring((pkgIdx - 300).coerceAtLeast(0), (pkgIdx + 300).coerceAtMost(raw.length))
            appIdRe.find(window)?.groupValues?.get(1)
        } else appIdRe.find(raw)?.groupValues?.get(1)

        if (projectId == null && apiKey == null) return null
        FirebaseConfig(
            projectId     = projectId ?: "unknown",
            projectNumber = projectNo,
            apiKey        = apiKey,
            appId         = appId,
            source        = "assets/google-services.json"
        )
    } catch (_: Exception) { null }
}

// ── Remote Config class check ──────────────────────────────────────────────────

private fun checkRemoteConfigInDex(apk: File): Boolean {
    return try {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".dex") }.any { entry ->
                val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                "FirebaseRemoteConfig" in text ||
                "firebase/remoteconfig" in text ||
                "com/google/firebase/remoteconfig" in text
            }
        }
    } catch (_: Exception) { false }
}

// ── Remote Config REST API ─────────────────────────────────────────────────────

private data class FetchResult(
    val status: String,
    val parameters: Map<String, RemoteConfigEntry>?,
    val rawResponse: String?,
    val error: String? = null
)

private fun fetchRemoteConfig(config: FirebaseConfig, pkg: String): FetchResult {
    val apiKey = config.apiKey
        ?: return FetchResult("NO_API_KEY", null, null,
            "No API key found in the APK — cannot call the Remote Config API.\n" +
            "The key is usually an AIza... string in google-services.json or the DEX.")

    // Project identifier for the endpoint URL — prefer project ID string, fall back
    // to the project number extracted from the app ID (1:PROJECT_NUMBER:android:HASH).
    val projectRef = when {
        config.projectId.isNotBlank() && config.projectId != "unknown" -> config.projectId
        config.projectNumber != null -> config.projectNumber
        config.appId != null -> config.appId.split(":").getOrNull(1) // extract project number
        else -> null
    } ?: return FetchResult("NO_PROJECT_REF", null, null,
            "Could not determine project ID or project number.\n" +
            "Found API key but no *.firebaseapp.com, project_id, or app ID string.")

    val appId    = config.appId ?: "1:${config.projectNumber ?: "000000000000"}:android:0000000000000000"
    val body     = """{"appId":"$appId","appInstanceId":"AAAAAAAAAAAAAAAAAAAAAAAA","packageName":"$pkg"}"""
    val endpoint = "https://firebaseremoteconfig.googleapis.com/v1/projects/$projectRef/namespaces/firebase:fetch?key=$apiKey"

    return try {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type",  "application/json; charset=UTF-8")
            setRequestProperty("X-Goog-Api-Key", apiKey)
            connectTimeout = 15_000; readTimeout = 20_000; doOutput = true
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

        val code = conn.responseCode
        val raw  = (if (code < 400) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

        when (code) {
            200  -> parseRemoteConfigResponse(raw)
            400  -> FetchResult("BAD_REQUEST", null, raw,
                    "HTTP 400 — request malformed. App ID or project ID may be wrong.")
            401, 403 -> FetchResult("AUTH_ERROR", null, raw,
                    "HTTP $code — API key is invalid or restricted for this API.\n" +
                    "Check Firebase Console → Project Settings → API key restrictions.")
            404  -> FetchResult("NOT_FOUND", null, raw,
                    "HTTP 404 — project '$projectRef' not found or Remote Config not enabled.")
            else -> FetchResult("HTTP_$code", null, raw, "HTTP $code from Firebase API.")
        }
    } catch (e: Exception) {
        FetchResult("NETWORK_ERROR", null, null, "${e::class.java.simpleName}: ${e.message}")
    }
}

private fun parseRemoteConfigResponse(raw: String): FetchResult {
    return try {
        val j      = Json { ignoreUnknownKeys = true }.parseToJsonElement(raw).jsonObject
        val state  = j["state"]?.jsonPrimitive?.content ?: "UNKNOWN"
        val params = j["entries"]?.jsonObject?.entries?.associate { (k, v) ->
            k to RemoteConfigEntry(value = try { v.jsonPrimitive.content } catch (_: Exception) { v.toString() })
        }
        FetchResult(status = state, parameters = params, rawResponse = raw)
    } catch (e: Exception) {
        FetchResult("PARSE_ERROR", null, raw, "Could not parse response: ${e.message}")
    }
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable data class FirebaseConfig(
    val projectId: String, val projectNumber: String?,
    val apiKey: String?, val appId: String?, val source: String
)

@Serializable data class RemoteConfigEntry(val value: String)

@Serializable data class RemoteConfigResult(
    val pkg: String,
    val firebaseDetected: Boolean,
    val remoteConfigDetected: Boolean,
    val firebaseConfig: FirebaseConfig? = null,
    val fetchStatus: String? = null,
    val fetchError: String? = null,
    val parameters: Map<String, RemoteConfigEntry>? = null,
    val rawResponse: String? = null,
    val note: String? = null,
    val error: String? = null
)
