package com.androidspect.server.routes

import android.content.Context
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Frida Script Manager.
 *
 * CodeShare scripts are referenced by slug only — Frida CLI fetches them
 * automatically via --codeshare author/slug. No downloading required.
 *
 *   GET  /api/frida-scripts/catalog     → curated catalog
 *   POST /api/frida-scripts/custom      → save custom script { name, category, content }
 *   GET  /api/frida-scripts/custom      → list custom scripts
 *   GET  /api/frida-scripts/custom/{id} → get custom script content
 *   DELETE /api/frida-scripts/custom/{id} → delete custom script
 */
fun Routing.fridaScriptRoutes(context: Context) {

    val customDir = File(context.filesDir, "frida_custom_scripts").also { it.mkdirs() }

    route("/api/frida-scripts") {

        get("/catalog") { call.respond(CATALOG) }

        post("/custom") {
            val req = call.receive<CustomScriptRequest>()
            val id  = req.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(64)
            File(customDir, "$id.js").writeText(req.content)
            File(customDir, "$id.meta").writeText("${req.name}\t${req.category}\t${System.currentTimeMillis()}")
            call.respond(mapOf("ok" to "saved", "id" to id))
        }

        get("/custom") {
            val scripts = customDir.listFiles { f -> f.extension == "js" }
                ?.mapNotNull { f ->
                    val meta  = File(customDir, "${f.nameWithoutExtension}.meta")
                    val parts = if (meta.exists()) meta.readText().split("\t") else null
                    FridaScript(
                        id          = f.nameWithoutExtension,
                        name        = parts?.getOrNull(0) ?: f.nameWithoutExtension,
                        category    = parts?.getOrNull(1) ?: "Custom",
                        description = "User-defined script",
                        codeshare   = null,
                        tags        = listOf("custom")
                    )
                }
                ?.sortedBy { it.name }
                ?: emptyList()
            call.respond(scripts)
        }

        get("/custom/{id}") {
            val id = call.parameters["id"]?.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val f  = File(customDir, "$id.js")
            if (!f.exists()) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(mapOf("content" to f.readText()))
        }

        delete("/custom/{id}") {
            val id = call.parameters["id"]?.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            File(customDir, "$id.js").delete()
            File(customDir, "$id.meta").delete()
            call.respond(mapOf("ok" to "deleted"))
        }
    }
}

// ── Curated CodeShare catalog ─────────────────────────────────────────────────

private val CATALOG = FridaCatalog(
    categories = listOf(
        FridaCategory("ssl", "SSL/TLS Bypass", "🔓", listOf(
            FridaScript("ssl-multiple-unpinning", "Multiple Unpinning",
                "SSL/TLS Bypass",
                "Bypasses multiple SSL pinning implementations (OkHttp3, Trustmanager, Appcelerator, etc.)",
                "akabe1/frida-multiple-unpinning",
                listOf("ssl", "pinning", "okhttp", "universal")),
            FridaScript("ssl-universal-android", "Universal Android SSL Pinning Bypass",
                "SSL/TLS Bypass",
                "The classic universal Android SSL pinning bypass script",
                "pcipolloni/universal-android-ssl-pinning-bypass-with-frida",
                listOf("ssl", "pinning", "universal")),
            FridaScript("ssl-android-unpinning", "Android Unpinning (httptoolkit)",
                "SSL/TLS Bypass",
                "Comprehensive SSL unpinning covering Conscrypt, OkHttp, Netty, Cronet, Xamarin and Flutter",
                "httptoolkit/android-ssl-bypass",
                listOf("ssl", "pinning", "conscrypt", "flutter")),
            FridaScript("ssl-flutter", "Disable Flutter TLS",
                "SSL/TLS Bypass",
                "Disables Flutter TLS verification by hooking ssl_crypto_x509_session_verify_cert_chain",
                "TheDauntless/disable-flutter-tls-v1",
                listOf("ssl", "flutter", "tls")),
            FridaScript("ssl-xamarin", "Xamarin SSL Bypass",
                "SSL/TLS Bypass",
                "Bypasses SSL pinning in Xamarin Android apps",
                "jame-sh/xamarin-ssl-pinning-bypass",
                listOf("ssl", "xamarin")),
        )),
        FridaCategory("root", "Root Detection Bypass", "🛡", listOf(
            FridaScript("root-universal", "Universal Root Bypass",
                "Root Detection Bypass",
                "Bypasses common root detection (RootBeer, su binary, test-keys, Magisk props, SafetyNet)",
                "dzonerzy/fridantiroot",
                listOf("root", "magisk", "universal", "safetynet")),
            FridaScript("root-multiple", "Multiple Root Checks Bypass",
                "Root Detection Bypass",
                "Hooks isRooted(), checkForSuperUser(), checkForSuBinary() and many more",
                "milkhe/multifrida",
                listOf("root", "su", "hooks")),
        )),
        FridaCategory("anti-debug", "Anti-Debug / Anti-Tamper", "🪲", listOf(
            FridaScript("anti-debug", "Anti-Debug Bypass",
                "Anti-Debug / Anti-Tamper",
                "Bypasses ptrace, TracerPid, and isDebuggerConnected checks",
                "apkunpacker/anti-debug-bypass",
                listOf("debug", "ptrace", "tracerpid")),
            FridaScript("frida-detection", "Frida Detection Bypass",
                "Anti-Debug / Anti-Tamper",
                "Bypasses apps that detect Frida via port scan or library enumeration",
                "dzonerzy/fridantifrida",
                listOf("frida", "detection", "stealth")),
        )),
        FridaCategory("crypto", "Cryptography", "🔑", listOf(
            FridaScript("crypto-logger", "Android Crypto Logger",
                "Cryptography",
                "Logs all javax.crypto Cipher operations: algorithm, key, IV, input and output",
                "fadeevab/android-frida-scripts/cryptologger",
                listOf("crypto", "aes", "logger", "keys")),
            FridaScript("jni-trace", "JNI Trace",
                "Cryptography",
                "Traces all JNI calls between Java and native libraries — useful for native crypto",
                "chame1eon/jnitrace",
                listOf("jni", "native", "trace")),
        )),
        FridaCategory("logging", "Data Logging", "📋", listOf(
            FridaScript("log-http", "OkHttp3 Logger",
                "Data Logging",
                "Intercepts and logs all OkHttp3 requests and responses",
                "Tiff4nY/okhttp3-logger",
                listOf("http", "okhttp", "network", "logger")),
            FridaScript("log-dex", "In-Memory DEX Dump",
                "Data Logging",
                "Dumps dynamically loaded DEX classes from memory",
                "cryptax/inmemorydexclassloader-dump",
                listOf("dex", "classloader", "dynamic")),
            FridaScript("log-intent", "Intent Monitor",
                "Data Logging",
                "Monitors startActivity and sendBroadcast calls, logging all Intent data",
                "n0nop/android-intent-monitor",
                listOf("intent", "activity", "broadcast", "logger")),
        )),
        FridaCategory("webview", "WebView", "🌐", listOf(
            FridaScript("webview-hook", "WebView Hooks",
                "WebView",
                "Hooks loadUrl, evaluateJavascript, addJavascriptInterface and shouldOverrideUrlLoading",
                "nicowillis/webview-hooks",
                listOf("webview", "xss", "hooks")),
        )),
        FridaCategory("mstg", "OWASP MSTG", "📖", listOf(
            FridaScript("mstg-biometric", "Biometric Bypass",
                "OWASP MSTG",
                "Bypasses FingerprintManager and BiometricPrompt authentication",
                "Ralali/biometric-bypass",
                listOf("biometric", "auth", "mstg")),
            FridaScript("mstg-objc-observer", "ObjC Method Observer",
                "OWASP MSTG",
                "Observe any Objective-C method call (iOS/macOS)",
                "mrmacete/objc-method-observer",
                listOf("objc", "ios", "observer")),
        )),
    )
)

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable data class FridaScript(
    val id: String, val name: String, val category: String,
    val description: String,
    val codeshare: String?,   // "author/slug" for --codeshare, null for custom
    val tags: List<String>
)

@Serializable data class FridaCategory(
    val id: String, val name: String, val icon: String,
    val scripts: List<FridaScript>
)

@Serializable data class FridaCatalog(val categories: List<FridaCategory>)

@Serializable data class CustomScriptRequest(
    val name: String, val category: String = "Custom", val content: String
)
