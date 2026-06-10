package com.androidspect.server.routes

import android.content.Context
import com.androidspect.root.ComponentInspector
import com.androidspect.root.DeeplinkParamExtractor
import com.androidspect.root.Sanitize
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Android App Links (deeplink verification) helpers.
 *
 *   GET /api/apps/{pkg}/deeplinks
 *       → App Links analysis: which http(s) domains the app declares, whether
 *         each is autoVerify, and the SHA-256 signing cert fingerprint Android
 *         would expect to see in the domain's assetlinks.json.
 *
 *   GET /api/deeplinks/assetlinks?domain=example.com[&pkg=…][&fp=AA:BB:…]
 *       → Fetches https://<domain>/.well-known/assetlinks.json, validates its
 *         structure, and (if pkg/fp supplied) checks whether the app is actually
 *         authorised by a matching statement.
 */
fun Routing.deeplinkRoutes(context: Context) {

    val inspector = ComponentInspector(context)
    val paramExtractor = DeeplinkParamExtractor(context)

    route("/api/apps/{pkg}/deeplinks") {
        get {
            val pkg = call.parameters["pkg"].orEmpty()
            val report = inspector.list(pkg)

            // Gather http(s) hosts from every component's intent-filters,
            // tracking whether the enclosing filter was autoVerify.
            val domains = LinkedHashMap<String, AppLinkDomain>()
            val all = report.activities + report.services + report.receivers + report.providers
            for (c in all) {
                for (f in c.filters) {
                    val isWeb = f.data.any { it.scheme == "http" || it.scheme == "https" }
                    val isViewBrowsable = f.actions.contains("android.intent.action.VIEW") &&
                        f.categories.contains("android.intent.category.BROWSABLE")
                    if (!isWeb || !isViewBrowsable) continue
                    for (d in f.data) {
                        val host = d.host ?: continue
                        if (d.scheme != "http" && d.scheme != "https") continue
                        val existing = domains[host]
                        domains[host] = AppLinkDomain(
                            host = host,
                            autoVerify = (existing?.autoVerify ?: false) || f.autoVerify,
                            schemes = ((existing?.schemes ?: emptyList()) + d.scheme).distinct(),
                            component = c.name
                        )
                    }
                }
            }

            // The cert fingerprint Android expects in assetlinks.json
            val fp = signingSha256(context, pkg)

            // Custom-scheme deeplinks (anything that isn't http/https) — these
            // can't use App Links verification, so they're inherently hijackable
            // by any app that also registers the scheme. Collect scheme + the
            // example URI shape (scheme://host/path) for the PoC builder.
            val custom = LinkedHashMap<String, CustomScheme>()
            for (c in all) {
                for (f in c.filters) {
                    val isViewBrowsable = f.actions.contains("android.intent.action.VIEW") &&
                        f.categories.contains("android.intent.category.BROWSABLE")
                    for (d in f.data) {
                        val scheme = d.scheme ?: continue
                        if (scheme == "http" || scheme == "https") continue
                        val host = d.host
                        val path = d.path ?: d.pathPrefix ?: d.pathPattern
                        val example = buildString {
                            append(scheme).append("://")
                            append(host ?: "host")
                            if (path != null) append(if (path.startsWith("/")) path else "/$path")
                        }
                        // Key on the full shape (scheme|host|path) so distinct
                        // paths under the same scheme/host are kept as separate
                        // entries instead of collapsing to the first one.
                        val key = "$scheme|${host ?: ""}|${path ?: ""}"
                        if (!custom.containsKey(key)) {
                            custom[key] = CustomScheme(
                                scheme = scheme,
                                host = host,
                                path = path,
                                example = example,
                                browsable = isViewBrowsable,
                                component = c.name,
                                exported = c.exported
                            )
                        }
                    }
                }
            }

            call.respond(DeeplinkReport(
                packageName = pkg,
                signingSha256 = fp,
                domains = domains.values.toList(),
                customSchemes = custom.values.toList()
            ))
        }

        /**
         * Scan a component's code for the deeplink parameters it reads from the
         * Uri (query params + path-segment accesses).
         * GET /api/apps/{pkg}/deeplinks/params?class=<FQCN>
         */
        get("/params") {
            val pkg = call.parameters["pkg"].orEmpty()
            val cls = call.request.queryParameters["class"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "class required"))
            call.respond(mapOf("params" to paramExtractor.extract(pkg, cls)))
        }
    }

    route("/api/deeplinks") {
        get("/assetlinks") {
            val domain = call.request.queryParameters["domain"]
                ?.trim()
                ?.removePrefix("https://")?.removePrefix("http://")
                ?.substringBefore('/')
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "domain required"))
            val pkg = call.request.queryParameters["pkg"]
            val fp  = call.request.queryParameters["fp"]?.uppercase()

            val result = withContext(Dispatchers.IO) { fetchAndValidate(domain, pkg, fp) }
            call.respond(result)
        }
    }
}

/** Fetches and validates a domain's assetlinks.json. */
private fun fetchAndValidate(domain: String, pkg: String?, fp: String?): AssetlinksResult {
    val url = "https://$domain/.well-known/assetlinks.json"
    var conn: HttpURLConnection? = null
    try {
        conn = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AndroidSpect-AppLinks/1.0")
        }
        val code = conn.responseCode
        if (code != 200) {
            return AssetlinksResult(
                url = url, reachable = true, httpStatus = code, valid = false,
                error = "HTTP $code (expected 200). assetlinks.json must be served over HTTPS with a 200 response.",
            )
        }
        // Content-Type should be application/json (warn, not fail)
        val contentType = conn.contentType ?: ""
        val body = conn.inputStream.bufferedReader().use { it.readText() }

        val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: return AssetlinksResult(url = url, reachable = true, httpStatus = 200, valid = false,
                error = "Response is not valid JSON.", contentType = contentType)

        val arr = (json as? JsonArray)
            ?: return AssetlinksResult(url = url, reachable = true, httpStatus = 200, valid = false,
                error = "Top-level JSON must be an array of statements.", contentType = contentType)

        // Parse statements
        val statements = mutableListOf<AssetlinkStatement>()
        var grantsHandleAllUrls = false
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val relation = obj["relation"]?.let { rel ->
                (rel as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() } ?: emptyList()
            } ?: emptyList()
            val target = obj["target"]?.jsonObject
            val targetPkg = target?.get("package_name")?.jsonPrimitive?.contentOrNullSafe()
            val targetNamespace = target?.get("namespace")?.jsonPrimitive?.contentOrNullSafe()
            val fingerprints = target?.get("sha256_cert_fingerprints")?.let { fps ->
                (fps as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNullSafe()?.uppercase() } ?: emptyList()
            } ?: emptyList()

            if (relation.any { it.contains("handle_all_urls") }) grantsHandleAllUrls = true

            statements.add(AssetlinkStatement(
                relations = relation,
                namespace = targetNamespace,
                packageName = targetPkg,
                sha256Fingerprints = fingerprints
            ))
        }

        // If pkg/fp supplied, check whether the app is actually authorised.
        var authorizesApp: Boolean? = null
        var authDetail: String? = null
        if (pkg != null) {
            val match = statements.firstOrNull { st ->
                st.namespace == "android_app" &&
                st.packageName == pkg &&
                (fp == null || st.sha256Fingerprints.any { it.replace(":", "") == fp.replace(":", "") })
            }
            authorizesApp = match != null
            authDetail = when {
                match != null && fp != null -> "Statement found for $pkg with a matching SHA-256 fingerprint."
                match != null               -> "Statement found for $pkg (fingerprint not checked — none supplied)."
                statements.any { it.packageName == pkg } && fp != null ->
                    "A statement names $pkg but none list the supplied fingerprint — App Links verification would FAIL."
                else -> "No statement authorises $pkg — App Links verification would FAIL."
            }
        }

        return AssetlinksResult(
            url = url, reachable = true, httpStatus = 200, valid = true,
            contentType = contentType,
            statements = statements,
            authorizesApp = authorizesApp,
            authDetail = authDetail,
            note = if (!contentType.contains("json"))
                "Served with Content-Type '$contentType' — Google recommends application/json." else null
        )
    } catch (e: Exception) {
        return AssetlinksResult(
            url = url, reachable = false, valid = false,
            error = "Could not fetch: ${e.message ?: e::class.java.simpleName}"
        )
    } finally {
        conn?.disconnect()
    }
}

/** Returns the app's signing certificate SHA-256 fingerprint as AA:BB:… */
private fun signingSha256(context: Context, pkg: String): String? {
    return try {
        val pm = context.packageManager
        @Suppress("DEPRECATION", "PackageManagerGetSignatures")
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageInfo(pkg, flags)
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        val cert = signers.firstOrNull()?.toByteArray() ?: return null
        val md = MessageDigest.getInstance("SHA-256").digest(cert)
        md.joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) {
        null
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { this.content }.getOrNull()

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable
data class AppLinkDomain(
    val host: String,
    val autoVerify: Boolean,
    val schemes: List<String>,
    val component: String
)

@Serializable
data class DeeplinkReport(
    val packageName: String,
    val signingSha256: String?,
    val domains: List<AppLinkDomain>,
    val customSchemes: List<CustomScheme> = emptyList()
)

@Serializable
data class CustomScheme(
    val scheme: String,
    val host: String?,
    val path: String? = null,
    val example: String,
    val browsable: Boolean,
    val component: String,
    val exported: Boolean
)

@Serializable
data class AssetlinkStatement(
    val relations: List<String>,
    val namespace: String?,
    val packageName: String?,
    val sha256Fingerprints: List<String>
)

@Serializable
data class AssetlinksResult(
    val url: String,
    val reachable: Boolean,
    val httpStatus: Int? = null,
    val valid: Boolean,
    val contentType: String? = null,
    val statements: List<AssetlinkStatement> = emptyList(),
    val authorizesApp: Boolean? = null,
    val authDetail: String? = null,
    val error: String? = null,
    val note: String? = null
)
