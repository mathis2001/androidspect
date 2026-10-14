package com.androidspect.server.routes

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.androidspect.root.AppActions
import com.androidspect.root.RootBridge
import com.androidspect.root.Sanitize
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * YesWeHack integration — lists bug bounty programs that declare a Mobile
 * (Android) scope. Hunters aren't issued long-lived API keys by YesWeHack,
 * so auth here is a session JWT the user copies from their own browser
 * after logging in to yeswehack.com (pasted in the Env Setup tab). It is
 * encrypted at rest the same way AI provider keys are (Android Keystore
 * AES-GCM via [KeystoreCrypto]), under its own key alias.
 *
 * The public /programs list endpoint doesn't carry scope info, so finding
 * "which programs have an Android scope" requires one detail request per
 * program (GET /programs/{slug}). That's a lot of requests for a few
 * hundred programs, so results are cached to disk and only re-fetched when
 * the user asks for a refresh.
 *
 *   GET    /api/ywh/token/status         → { present, savedAt }
 *   PUT    /api/ywh/token                → { token } - store (encrypted)
 *   DELETE /api/ywh/token                → clear stored token + cached programs
 *   GET    /api/ywh/programs?refresh=1   → cached or freshly-fetched program list
 *   GET    /api/ywh/programs/{slug}/scope.md → current scope policy as Markdown
 *   POST   /api/ywh/apps/install         → open the app in Play Store on this device
 *   GET    /api/ywh/apps/{pkg}/status    → report whether the package is installed
 */
private const val YWH_BASE = "https://api.yeswehack.com"
private const val YWH_KEY_ALIAS = "androidspect_ywh_key"
private const val YWH_DETAIL_CONCURRENCY = 6

fun Routing.yesWeHackRoutes(context: Context) {

    val tokenFile = File(context.filesDir, "ywh_token.key")
    val metaFile  = File(context.filesDir, "ywh_token_meta.json")
    val cacheFile = File(context.filesDir, "ywh_programs_cache.json")
    val json = Json { ignoreUnknownKeys = true }

    fun loadToken(): String? {
        if (!tokenFile.exists()) return null
        return runCatching { KeystoreCrypto.decrypt(tokenFile.readBytes(), YWH_KEY_ALIAS) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    route("/api/ywh") {

        get("/token/status") {
            val savedAt = runCatching {
                if (!metaFile.exists()) return@runCatching null
                json.parseToJsonElement(metaFile.readText())
                    .jsonObject["savedAt"]?.jsonPrimitive?.content?.toLongOrNull()
            }.getOrNull()
            call.respond(YwhTokenStatus(present = loadToken() != null, savedAt = savedAt))
        }

        put("/token") {
            val req = call.receive<YwhTokenRequest>()
            val token = req.token.trim()
            if (token.isBlank())
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "token is required"))
            // Loose sanity check - a JWT is three dot-separated base64url segments.
            if (token.count { it == '.' } != 2)
                return@put call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "doesn't look like a JWT (expected header.payload.signature)"))
            tokenFile.writeBytes(KeystoreCrypto.encrypt(token, YWH_KEY_ALIAS))
            metaFile.writeText("""{"savedAt":${System.currentTimeMillis()}}""")
            call.respond(mapOf("ok" to "saved"))
        }

        delete("/token") {
            tokenFile.delete()
            metaFile.delete()
            cacheFile.delete()
            call.respond(mapOf("ok" to "cleared"))
        }

        get("/programs") {
            val token = loadToken()
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "No YesWeHack token configured. Set one in the Env Setup tab."))

            val refresh = call.request.queryParameters["refresh"] == "true"
            if (!refresh && cacheFile.exists()) {
                return@get call.respondText(cacheFile.readText(), ContentType.Application.Json)
            }

            val result = withContext(Dispatchers.IO) { fetchMobileAndroidPrograms(token) }
            if (result.authError) {
                return@get call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "YesWeHack token expired or invalid. Update it in Env Setup."))
            }

            val payload = buildJsonObject {
                put("fetchedAt", System.currentTimeMillis())
                put("total", result.programs.size)
                put("skipped", result.skipped)
                putJsonArray("programs") { result.programs.forEach { add(it) } }
            }
            cacheFile.writeText(payload.toString())
            call.respondText(payload.toString(), ContentType.Application.Json)
        }

        // Live re-fetch of a single program's detail - used by the "More info"
        // button so its in-scope targets, reward grid, and vuln types reflect
        // what's on YesWeHack right now rather than whatever was cached the
        // last time "Fetch programs" ran.
        get("/programs/{slug}") {
            val token = loadToken()
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "No YesWeHack token configured. Set one in the Env Setup tab."))
            val slug = call.parameters["slug"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "slug required"))

            val (code, body) = withContext(Dispatchers.IO) { ywhGet("$YWH_BASE/programs/$slug", token) }
            when {
                code == 401 -> call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "YesWeHack token expired or invalid. Update it in Env Setup."))
                code !in 200..299 -> call.respond(HttpStatusCode.BadGateway,
                    mapOf("error" to "YesWeHack returned HTTP $code for '$slug'."))
                else -> call.respondText(body, ContentType.Application.Json)
            }
        }

        get("/programs/{slug}/scope.md") {
            val token = loadToken()
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "No YesWeHack token configured. Set one in the Env Setup tab."))
            val slug = call.parameters["slug"]?.takeIf { YWH_SLUG.matches(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid program slug"))

            val (code, body) = withContext(Dispatchers.IO) { ywhGet("$YWH_BASE/programs/$slug", token) }
            when {
                code == 401 -> call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "YesWeHack token expired or invalid. Update it in Env Setup."))
                code !in 200..299 -> call.respond(HttpStatusCode.BadGateway,
                    mapOf("error" to "YesWeHack returned HTTP $code for '$slug'."))
                else -> {
                    val detail = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadGateway,
                            mapOf("error" to "YesWeHack returned invalid program data for '$slug'."))
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, "scope.md").toString()
                    )
                    call.respondText(buildScopeMarkdown(detail, slug), ContentType.parse("text/markdown"))
                }
            }
        }

        /**
         * Google Play does not expose an APK at the public listing URL. Hand the
         * package to the device's authenticated Play Store. The Play Store owns
         * the actual install so account, entitlement, region and compatibility
         * checks remain identical to a normal on-device installation.
         */
        post("/apps/install") {
            val request = call.receive<YwhPlayInstallRequest>()
            val pkg = playStorePackageName(request.url)
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "A valid Google Play app URL with an id parameter is required.")
                )

            if (AppActions.apkPaths(pkg).isNotEmpty()) {
                return@post call.respond(YwhPlayInstallStatus.installed(pkg))
            }

            val marketUri = "market://details?id=$pkg"
            val shellLaunch = RootBridge.exec(
                "am start -a android.intent.action.VIEW -d ${Sanitize.shellQuote(marketUri)} -p com.android.vending"
            )
            val launched = shellLaunch.ok || runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                    setPackage("com.android.vending")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.recoverCatching {
                context.startActivity(Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }.isSuccess

            if (!launched) {
                return@post call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "Could not open a Play Store client on the Android device.")
                )
            }
            call.respond(YwhPlayInstallStatus.opened(pkg))
        }

        get("/apps/{pkg}/status") {
            val pkg = validPackageName(call.parameters["pkg"])
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid package name"))
            val paths = AppActions.apkPaths(pkg)
            call.respond(if (paths.isEmpty()) YwhPlayInstallStatus.pending(pkg)
                else YwhPlayInstallStatus.installed(pkg))
        }
    }
}

private val ANDROID_PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
private val YWH_SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")

private fun validPackageName(value: String?): String? =
    value?.trim()?.takeIf { ANDROID_PACKAGE.matches(it) }

/** Accept only genuine Play listing URLs; never turn an arbitrary URL into an intent. */
private fun playStorePackageName(value: String): String? = runCatching {
    val uri = Uri.parse(value.trim())
    val scheme = uri.scheme?.lowercase()
    val isMarket = scheme == "market" && uri.host.equals("details", ignoreCase = true)
    val isWeb = scheme in setOf("http", "https") &&
        uri.host?.lowercase() in setOf("play.google.com", "market.android.com") &&
        uri.path?.trimEnd('/')?.endsWith("/store/apps/details") == true
    if (!isMarket && !isWeb) return@runCatching null
    validPackageName(uri.getQueryParameter("id"))
}.getOrNull()

private fun buildScopeMarkdown(program: JsonObject, fallbackSlug: String): String {
    fun string(key: String): String? = program[key]?.jsonPrimitive?.contentOrNull
        ?.trim()?.takeIf(String::isNotEmpty)
    fun strings(key: String): List<String> = program[key]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        .orEmpty()
    fun bullet(value: String): String = value.lines().map(String::trimEnd)
        .joinToString("\n  ", prefix = "- ")
    fun section(items: List<String>): String = if (items.isEmpty()) "_None specified._"
        else items.joinToString("\n") { bullet(it) }

    val slug = string("slug") ?: fallbackSlug
    val title = string("title") ?: slug
    val scopes = program["scopes"]?.jsonArray.orEmpty().mapNotNull { element ->
        val scope = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val target = scope["scope"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (target.isEmpty()) return@mapNotNull null
        val type = (scope["scope_type_name"] ?: scope["scope_type"])
            ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (type.isEmpty()) target else "$target — $type"
    }
    val outOfScope = strings("out_of_scope")
    val outOfScopeVulnerabilities = strings("non_qualifying_vulnerability")
    val userAgent = string("user_agent") ?: string("ywh_triager_user_agent")

    return buildString {
        appendLine("# $title — Scope")
        appendLine()
        appendLine("- Program: https://yeswehack.com/programs/$slug")
        string("last_update_at")?.let { appendLine("- Last updated by YesWeHack: $it") }
        appendLine()
        appendLine("## In-scope URLs and targets")
        appendLine()
        appendLine(section(scopes))
        appendLine()
        appendLine("## Out-of-scope parts")
        appendLine()
        appendLine(section(outOfScope))
        appendLine()
        appendLine("## Out-of-scope vulnerabilities")
        appendLine()
        appendLine(section(outOfScopeVulnerabilities))
        appendLine()
        appendLine("## User-Agent to use")
        appendLine()
        if (userAgent == null) appendLine("_None specified._")
        else userAgent.lines().forEach { appendLine("    $it") }
    }
}

// ── Fetch + filter ───────────────────────────────────────────────────────────

private data class YwhFetchResult(val programs: List<JsonObject>, val skipped: Int, val authError: Boolean)

/**
 * Walks every page of /programs (skipping disabled ones), then fetches each
 * program's detail endpoint (bounded concurrency) to inspect its scopes.
 * Keeps only programs with at least one scope whose scope_type mentions
 * "android" - covers YesWeHack's "Mobile Application - Android" category
 * without hard-depending on its exact slug string.
 */
private suspend fun fetchMobileAndroidPrograms(token: String): YwhFetchResult = coroutineScope {
    val json = Json { ignoreUnknownKeys = true }

    val items = mutableListOf<JsonObject>()
    var page = 1
    while (true) {
        val (code, body) = ywhGet("$YWH_BASE/programs?page=$page", token)
        if (code == 401) return@coroutineScope YwhFetchResult(emptyList(), 0, true)
        if (code !in 200..299) break
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: break
        val pageItems = obj["items"]?.jsonArray ?: break
        pageItems.forEach { el ->
            val o = el.jsonObject
            if (o["disabled"]?.jsonPrimitive?.content != "true") items.add(o)
        }
        val nbPages = obj["pagination"]?.jsonObject?.get("nb_pages")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        if (page >= nbPages || page > 200) break // sanity cap against a runaway pagination loop
        page++
    }

    val semaphore = Semaphore(YWH_DETAIL_CONCURRENCY)
    // Dispatchers.IO spans real threads, not just interleaved coroutines, so a
    // plain `var` here would race under concurrent detail fetches.
    val skipped = java.util.concurrent.atomic.AtomicInteger(0)
    val merged = items.map { item ->
        async {
            semaphore.withPermit {
                val slug = item["slug"]?.jsonPrimitive?.content ?: return@withPermit null
                val (code, body) = ywhGet("$YWH_BASE/programs/$slug", token)
                if (code != 200) { skipped.incrementAndGet(); return@withPermit null }
                val detail = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                if (detail == null) { skipped.incrementAndGet(); return@withPermit null }
                val scopes = detail["scopes"]?.jsonArray
                val hasAndroidScope = scopes?.any {
                    it.jsonObject["scope_type"]?.jsonPrimitive?.content
                        ?.contains("android", ignoreCase = true) == true
                } == true
                if (!hasAndroidScope) return@withPermit null
                // Detail fields (scopes, reward_policy, dates, ...) win over the
                // leaner list-item fields; list fields fill in anything detail omits.
                JsonObject(item.toMutableMap().apply { putAll(detail) })
            }
        }
    }.awaitAll().filterNotNull()

    YwhFetchResult(merged, skipped.get(), false)
}

private fun ywhGet(url: String, token: String): Pair<Int, String> {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Accept", "application/json")
    }
    return try {
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
    } finally {
        conn.disconnect()
    }
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable data class YwhTokenRequest(val token: String = "")
@Serializable data class YwhTokenStatus(val present: Boolean, val savedAt: Long? = null)
@Serializable data class YwhPlayInstallRequest(val url: String = "")
@Serializable data class YwhPlayInstallStatus(
    val packageName: String,
    val status: String
) {
    companion object {
        fun opened(pkg: String) = YwhPlayInstallStatus(pkg, "play_store_opened")
        fun pending(pkg: String) = YwhPlayInstallStatus(pkg, "pending")
        fun installed(pkg: String) = YwhPlayInstallStatus(pkg, "installed")
    }
}
