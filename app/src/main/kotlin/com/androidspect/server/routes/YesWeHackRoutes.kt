package com.androidspect.server.routes

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
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
