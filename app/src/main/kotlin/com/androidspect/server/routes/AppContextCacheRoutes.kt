package com.androidspect.server.routes

import android.content.Context
import android.content.pm.PackageManager
import com.androidspect.root.ComponentInspector
import com.androidspect.root.ManifestDecoder
import com.androidspect.root.NativeLibScanner
import com.androidspect.root.WebExtractor
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File

/**
 * App Context Cache — calls analysis classes directly (no HTTP round-trip)
 * and writes a structured JSON file to filesDir/ai_context/<pkg>.json
 * so the AI assistant can read the full app profile in a single read_file call.
 *
 *   POST /api/ai/context/build?pkg=…  → build/refresh the cache
 *   GET  /api/ai/context/status?pkg=… → existence check + age + size
 */
fun Routing.appContextCacheRoutes(context: Context) {

    val cacheDir   = File(context.filesDir, "ai_context").also { it.mkdirs() }
    val decoder    = ManifestDecoder(context)
    val inspector  = ComponentInspector(context)
    val nativeScanner = NativeLibScanner(context)
    val webExtractor  = WebExtractor(context)
    val json       = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun cacheFile(pkg: String) =
        File(cacheDir, pkg.replace(Regex("[^a-zA-Z0-9._\\-]"), "_") + ".json")

    route("/api/ai/context") {

        get("/status") {
            val pkg  = call.request.queryParameters["pkg"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "pkg required"))
            val file = cacheFile(pkg)
            call.respond(mapOf(
                "exists"    to file.exists().toString(),
                "sizeBytes" to (if (file.exists()) file.length() else 0L).toString(),
                "updatedAt" to (if (file.exists()) file.lastModified() else 0L).toString()
            ))
        }

        post("/build") {
            val pkg = call.request.queryParameters["pkg"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "pkg required"))

            val result = withContext(Dispatchers.IO) {
                buildContextCache(pkg, context, cacheFile(pkg),
                    decoder, inspector, nativeScanner, webExtractor, json)
            }
            call.respond(result)
        }
    }
}

// ── Direct Kotlin build — no HTTP ─────────────────────────────────────────────

private suspend fun buildContextCache(
    pkg: String,
    context: Context,
    outFile: File,
    decoder: ManifestDecoder,
    inspector: ComponentInspector,
    nativeScanner: NativeLibScanner,
    webExtractor: WebExtractor,
    json: Json
): ContextBuildResult {
    val errors = mutableListOf<String>()
    val fmt    = Json { ignoreUnknownKeys = true; prettyPrint = false }

    suspend fun <T> runSafe(label: String, block: suspend () -> T): T? =
        runCatching { block() }.getOrElse {
            errors.add("$label: ${it.message?.take(120)}")
            null
        }

    // Run all analysis in parallel — each class is thread-safe (read-only APK access).
    val results = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope {
            val manifest   = async { runSafe("manifest")   { decoder.decode(pkg) } }
            val components = async { runSafe("components") { inspector.list(pkg) } }
            val native     = async { runSafe("native")     { nativeScanner.scan(pkg) } }
            val web        = async { runSafe("web")        { webExtractor.extract(pkg) } }
            val deeplinks  = async { runSafe("deeplinks")  { buildDeeplinks(pkg, inspector, context) } }
            val appInfo    = async { runSafe("appInfo")    { buildAppInfo(pkg, context) } }
            listOf(manifest.await(), components.await(), native.await(),
                   web.await(),      deeplinks.await(),  appInfo.await())
        }
    }

    @Suppress("UNCHECKED_CAST")
    val manifest   = results[0]
    val components = results[1]
    val native     = results[2]
    val web        = results[3]
    val deeplinks  = results[4]
    val appInfo    = results[5]

    val ctx = buildJsonObject {
        put("_meta", buildJsonObject {
            put("pkg",         pkg)
            put("generatedAt", System.currentTimeMillis().toString())
            put("description", "AndroidSpect aggregated app context — use for AI-assisted security analysis")
        })
        put("appInfo",     if (appInfo != null) fmt.encodeToJsonElement(AppInfoSnapshot.serializer(), appInfo as AppInfoSnapshot) else JsonNull)
        put("manifest",    if (manifest != null) fmt.encodeToJsonElement(com.androidspect.root.ManifestDump.serializer(), manifest as com.androidspect.root.ManifestDump) else JsonNull)
        put("components",  if (components != null) fmt.encodeToJsonElement(com.androidspect.root.ComponentReport.serializer(), components as com.androidspect.root.ComponentReport) else JsonNull)
        put("native",      if (native != null) {
            buildJsonObject { put("libs", fmt.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(com.androidspect.root.NativeLib.serializer()),
                native as List<com.androidspect.root.NativeLib>)) }
        } else JsonNull)
        put("webSecurity", if (web != null) fmt.encodeToJsonElement(com.androidspect.root.WebReport.serializer(), web as com.androidspect.root.WebReport) else JsonNull)
        put("deeplinks",   if (deeplinks != null) fmt.encodeToJsonElement(DeeplinkSnapshot.serializer(), deeplinks as DeeplinkSnapshot) else JsonNull)
        if (errors.isNotEmpty())
            put("_errors", JsonPrimitive(errors.joinToString("; ")))
    }

    val pretty = json.encodeToString(JsonObject.serializer(), ctx)
    outFile.writeText(pretty)

    return ContextBuildResult(
        pkg       = pkg,
        path      = outFile.absolutePath,
        sizeBytes = outFile.length(),
        updatedAt = outFile.lastModified(),
        errors    = errors
    )
}

// ── Deeplink snapshot ─────────────────────────────────────────────────────────

private suspend fun buildDeeplinks(
    pkg: String,
    inspector: ComponentInspector,
    context: Context
): DeeplinkSnapshot {
    val report = inspector.list(pkg)
    val all    = report.activities + report.services + report.receivers + report.providers
    val domains = mutableListOf<String>()
    val customSchemes = mutableListOf<String>()

    for (c in all) {
        for (f in c.filters) {
            val isViewBrowsable = f.actions.contains("android.intent.action.VIEW") &&
                f.categories.contains("android.intent.category.BROWSABLE")
            for (d in f.data) {
                val scheme = d.scheme ?: continue
                if (scheme == "http" || scheme == "https") {
                    d.host?.let { if (it !in domains) domains.add(it) }
                } else if (isViewBrowsable) {
                    val host = d.host
                    val path = d.path ?: d.pathPrefix ?: d.pathPattern
                    val example = buildString {
                        append(scheme).append("://")
                        append(host ?: "host")
                        if (path != null) append(if (path.startsWith("/")) path else "/$path")
                    }
                    if (example !in customSchemes) customSchemes.add(example)
                }
            }
        }
    }

    return DeeplinkSnapshot(
        signingSha256  = ctxSigningSha256(context, pkg),
        appLinkDomains = domains,
        customSchemes  = customSchemes
    )
}

/** Duplicate of the private signingSha256 in DeeplinkRoutes — cannot access it cross-file. */
private fun localSigningSha256(context: Context, pkg: String): String? = try {
    val pm   = context.packageManager
    val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
    val cert = info.signingInfo?.apkContentsSigners?.lastOrNull()?.toByteArray() ?: return null
    java.security.MessageDigest.getInstance("SHA-256").digest(cert)
        .joinToString(":") { "%02X".format(it) }
} catch (_: Exception) { null }

// ── App info snapshot ─────────────────────────────────────────────────────────

private fun buildAppInfo(pkg: String, context: Context): AppInfoSnapshot {
    val pm = context.packageManager
    val ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
    val pi = pm.getPackageInfo(pkg, 0)
    return AppInfoSnapshot(
        packageName = pkg,
        label       = pm.getApplicationLabel(ai).toString(),
        versionName = pi.versionName ?: "",
        versionCode = pi.longVersionCode,
        targetSdk   = ai.targetSdkVersion,
        minSdk      = ai.minSdkVersion,
        debuggable  = ai.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
        allowBackup = ai.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
        installedOn = pi.firstInstallTime,
        updatedOn   = pi.lastUpdateTime,
        sourceDir   = ai.sourceDir ?: "",
        dataDir     = ai.dataDir ?: ""
    )
}

// ── Signing certificate SHA-256 (local copy — original is private in DeeplinkRoutes) ──
private fun ctxSigningSha256(context: Context, pkg: String): String? {
    return try {
        val pm = context.packageManager
        val info = runCatching {
            pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: run {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
        }
        val signers = info.signingInfo?.apkContentsSigners
            ?: @Suppress("DEPRECATION") info.signatures
            ?: return null
        val cert = signers.lastOrNull()?.toByteArray() ?: return null
        java.security.MessageDigest.getInstance("SHA-256").digest(cert)
            .joinToString(":") { "%02X".format(it) }
    } catch (_: Exception) { null }
}


// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable
data class AppInfoSnapshot(
    val packageName: String, val label: String,
    val versionName: String, val versionCode: Long,
    val targetSdk: Int, val minSdk: Int,
    val debuggable: Boolean, val allowBackup: Boolean,
    val installedOn: Long, val updatedOn: Long,
    val sourceDir: String, val dataDir: String
)

@Serializable
data class DeeplinkSnapshot(
    val signingSha256: String?,
    val appLinkDomains: List<String>,
    val customSchemes: List<String>
)

@Serializable
data class ContextBuildResult(
    val pkg: String, val path: String,
    val sizeBytes: Long, val updatedAt: Long,
    val errors: List<String>
)
