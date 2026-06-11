package com.androidspect.server.routes

import android.content.Context
import com.androidspect.root.RootBridge
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Environment Setup — proxy, CA cert hash helper, Frida server manager.
 *
 *   POST   /api/env/cert              → parse cert, compute hash, return adb commands
 *   GET    /api/env/proxy             → current global proxy
 *   PUT    /api/env/proxy             → set proxy { host, port }
 *   DELETE /api/env/proxy             → clear proxy (sets :0)
 *   GET    /api/env/frida/status      → installed version, ABI, running/stopped
 *   GET    /api/env/frida/releases    → latest frida-server versions from GitHub
 *   POST   /api/env/frida/install?version=X.Y.Z  → download + install frida-server
 *   POST   /api/env/frida/start       → start frida-server in background
 *   POST   /api/env/frida/stop        → kill frida-server
 */
fun Routing.envSetupRoutes(context: Context) {

    route("/api/env") {

        // ── Certificate ───────────────────────────────────────────────────────

        post("/cert") {
            var certBytes: ByteArray? = null
            var fileName = ""
            call.receiveMultipart().forEachPart { part ->
                if (part is PartData.FileItem && certBytes == null) {
                    fileName = part.originalFileName ?: "cert"
                    certBytes = part.streamProvider().use { it.readBytes() }
                }
                part.dispose()
            }
            val bytes = certBytes
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no cert file"))
            val result = withContext(Dispatchers.IO) { computeCertHash(bytes, context) }
            call.respond(HttpStatusCode.OK, result)
        }

        // ── Proxy ─────────────────────────────────────────────────────────────

        get("/proxy") {
            val r = RootBridge.exec("settings get global http_proxy")
            val raw = r.stdout.trim()
            val current = if (raw == "null" || raw.isBlank() || raw == ":0") null else raw
            val (host, port) = if (current != null && current.contains(':')) {
                current.substringBeforeLast(':') to current.substringAfterLast(':').toIntOrNull()
            } else null to null
            call.respond(ProxyStatus(current = current, host = host, port = port))
        }

        put("/proxy") {
            val req = call.receive<ProxyRequest>()
            val host = req.host.trim()
            val port = req.port
            if (host.isBlank() || port == null || port !in 1..65535)
                return@put call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "host and port (1-65535) required"))
            if (host.any { it in ";|&`\$'\"\\\n" })
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid host"))
            val r = RootBridge.exec("settings put global http_proxy $host:$port")
            call.respond(ProxySetResult(ok = r.code == 0, value = "$host:$port", stderr = r.stderr.trim()))
        }

        delete("/proxy") {
            // Setting :0 is the reliable Android trick to disable the proxy.
            RootBridge.exec("settings put global http_proxy :0")
            val verify = RootBridge.exec("settings get global http_proxy").stdout.trim()
            val cleared = verify == ":0" || verify == "null" || verify.isBlank()
            call.respond(ProxySetResult(ok = cleared, value = ":0",
                stderr = if (!cleared) "unexpected value: $verify" else ""))
        }

        // ── Frida ─────────────────────────────────────────────────────────────

        get("/frida/status") {
            val abi = RootBridge.exec("getprop ro.product.cpu.abi").stdout.trim()
            val pid = RootBridge.exec("pidof frida-server 2>/dev/null").stdout.trim()
            val ls  = RootBridge.exec("ls /data/local/tmp/frida-server 2>/dev/null").stdout.trim()
            val ver = if (ls.isNotBlank())
                RootBridge.exec("/data/local/tmp/frida-server --version 2>/dev/null").stdout.trim()
            else ""
            call.respond(FridaStatus(
                abi              = abi,
                running          = pid.isNotBlank(),
                pid              = pid.toIntOrNull(),
                installed        = ls.isNotBlank(),
                installedVersion = ver.ifBlank { null }
            ))
        }

        get("/frida/releases") {
            val releases = withContext(Dispatchers.IO) { fetchFridaReleases() }
            call.respond(releases)
        }

        post("/frida/install") {
            val version = call.request.queryParameters["version"]
                ?.trim()?.removePrefix("v")
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "version required"))
            if (!version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid version"))
            val abi = RootBridge.exec("getprop ro.product.cpu.abi").stdout.trim()
            val result = withContext(Dispatchers.IO) {
                runCatching { downloadFridaServer(version, abi, context) }
                    .getOrElse { FridaActionResult(false, "Exception: ${it.message ?: it::class.java.simpleName}") }
            }
            call.respond(HttpStatusCode.OK, result)
        }

        post("/frida/start") {
            val result = runCatching {
                val alreadyRunning = RootBridge.exec("pidof frida-server 2>/dev/null").stdout.trim().isNotBlank()
                if (alreadyRunning) return@runCatching FridaActionResult(true, "frida-server already running")
                RootBridge.exec("chmod +x /data/local/tmp/frida-server")
                RootBridge.exec("nohup /data/local/tmp/frida-server > /data/local/tmp/frida-server.log 2>&1 &")
                delay(1000)
                val pid = RootBridge.exec("pidof frida-server 2>/dev/null").stdout.trim()
                FridaActionResult(
                    success = pid.isNotBlank(),
                    message = if (pid.isNotBlank()) "frida-server started (PID $pid)"
                              else "Failed to start — check /data/local/tmp/frida-server exists and is executable"
                )
            }.getOrElse { FridaActionResult(false, "Exception: ${it.message}") }
            call.respond(HttpStatusCode.OK, result)
        }

        post("/frida/stop") {
            val result = runCatching {
                RootBridge.exec("kill \$(pidof frida-server 2>/dev/null) 2>/dev/null; true")
                delay(500)
                val pid = RootBridge.exec("pidof frida-server 2>/dev/null").stdout.trim()
                FridaActionResult(
                    success = pid.isBlank(),
                    message = if (pid.isBlank()) "frida-server stopped" else "Still running (PID $pid)"
                )
            }.getOrElse { FridaActionResult(false, "Exception: ${it.message}") }
            call.respond(HttpStatusCode.OK, result)
        }
    }
}

// ── Frida helpers ─────────────────────────────────────────────────────────────

private fun fetchFridaReleases(): FridaReleases {
    return try {
        val versions = mutableListOf<String>()
        var page = 1
        val json = Json { ignoreUnknownKeys = true }

        outer@ while (page <= 20) {
            val url = URL("https://api.github.com/repos/frida/frida/releases?per_page=30&page=$page")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "AndroidSpect/1.0")

            val body = try {
                if (conn.responseCode != 200) break@outer
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }

            val arr = runCatching {
                json.parseToJsonElement(body).jsonArray
            }.getOrNull() ?: break@outer

            if (arr.isEmpty()) break@outer

            var foundOld = false
            for (el in arr) {
                val tag = runCatching {
                    el.jsonObject["tag_name"]?.jsonPrimitive?.content?.removePrefix("v")
                }.getOrNull() ?: continue

                val major = tag.split(".").firstOrNull()?.toIntOrNull() ?: continue
                if (major < 16) { foundOld = true; break }
                versions.add(tag)
            }
            if (foundOld) break@outer
            page++
        }

        FridaReleases(versions = versions, error = if (versions.isEmpty()) "No versions found" else null)
    } catch (e: Exception) {
        FridaReleases(versions = emptyList(), error = "${e::class.java.simpleName}: ${e.message}")
    }
}

/** Maps Android ABI string to the frida-server asset name suffix. */
private fun fridaAbiSuffix(abi: String): String = when {
    abi.startsWith("arm64") -> "android-arm64"
    abi.startsWith("armeabi") -> "android-arm"
    abi.startsWith("x86_64") -> "android-x86_64"
    abi.startsWith("x86") -> "android-x86"
    else -> "android-arm64"
}

/** Returns the full path of a binary, checking PATH then Termux prefix. */
private suspend fun which(bin: String): String? {
    val r = RootBridge.exec("which $bin 2>/dev/null || echo ''")
    val found = r.stdout.trim()
    if (found.isNotBlank()) return found
    // Termux fallback
    val termux = "/data/data/com.termux/files/usr/bin/$bin"
    val exists = RootBridge.exec("[ -f '$termux' ] && echo y || echo n").stdout.trim()
    return if (exists == "y") termux else null
}

private fun termuxEnv(): String =
    "TERMUX_PREFIX=/data/data/com.termux/files/usr " +
    "PATH=/data/data/com.termux/files/usr/bin:\$PATH " +
    "LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib "

private suspend fun downloadFridaServer(version: String, abi: String, context: Context): FridaActionResult {
    val suffix    = fridaAbiSuffix(abi)
    val assetName = "frida-server-$version-$suffix.xz"
    val url       = "https://github.com/frida/frida/releases/download/$version/$assetName"
    val tmpXz     = "/data/local/tmp/frida-server.xz"
    val dest      = "/data/local/tmp/frida-server"

    // Find available downloaders (system PATH first, then Termux).
    val curlPath = which("curl")
    val wgetPath = which("wget")
    val env      = termuxEnv()

    // Try curl, fall back to wget if curl fails or isn't available.
    var dlTool   = ""
    var dlOk     = false
    var dlError  = ""

    if (curlPath != null) {
        dlTool = "curl (${curlPath})"
        val r = RootBridge.exec("${env}$curlPath -sL -o $tmpXz '$url' 2>&1")
        if (r.code == 0 && RootBridge.exec("[ -s $tmpXz ] && echo ok || echo empty").stdout.trim() == "ok") {
            dlOk = true
        } else {
            dlError = "curl failed (exit ${r.code}): ${(r.stdout + r.stderr).trim().take(200)}"
            RootBridge.exec("rm -f $tmpXz")
        }
    }

    if (!dlOk && wgetPath != null) {
        dlTool = if (dlTool.isNotBlank()) "$dlTool → wget fallback (${wgetPath})" else "wget (${wgetPath})"
        val r = RootBridge.exec("${env}$wgetPath -q -O $tmpXz '$url' 2>&1")
        if (r.code == 0 && RootBridge.exec("[ -s $tmpXz ] && echo ok || echo empty").stdout.trim() == "ok") {
            dlOk = true
            dlError = ""
        } else {
            dlError += "\nwget failed (exit ${r.code}): ${(r.stdout + r.stderr).trim().take(200)}"
            RootBridge.exec("rm -f $tmpXz")
        }
    }

    if (!dlOk) {
        val missing = buildString {
            if (curlPath == null && wgetPath == null) {
                appendLine("Neither curl nor wget is available.")
                appendLine("  • Install via Termux: pkg install curl")
                appendLine("  • Or install Magisk module: BusyBox for Android NDK")
            } else {
                appendLine("Download failed with available tools:")
                if (dlError.isNotBlank()) appendLine(dlError.trim())
            }
            appendLine()
            appendLine("Manual alternative from your PC:")
            appendLine("  1. Download $assetName")
            appendLine("     github.com/frida/frida/releases/tag/$version")
            appendLine("  2. unxz $assetName")
            appendLine("  3. adb push frida-server-$version-$suffix /data/local/tmp/frida-server")
            appendLine("  4. adb shell chmod +x /data/local/tmp/frida-server")
        }
        return FridaActionResult(false, missing.trim())
    }

    // Decompress — check system PATH then Termux.
    val xzPath = which("xz")
    if (xzPath == null) {
        RootBridge.exec("rm -f $tmpXz")
        return FridaActionResult(false,
            "xz not found (checked PATH and Termux).\n\n" +
            "Install Termux from F-Droid and run: pkg install xz-utils\n\n" +
            "Or decompress on your PC:\n" +
            "  unxz $assetName\n" +
            "  adb push frida-server-$version-$suffix /data/local/tmp/frida-server\n" +
            "  adb shell chmod +x /data/local/tmp/frida-server")
    }
    val xzR = RootBridge.exec("${env}$xzPath -d -c $tmpXz > $dest 2>&1 ; echo exit:\$?")
    RootBridge.exec("rm -f $tmpXz")

    val xzExit = Regex("exit:(\\d+)").find(xzR.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    if (xzExit != 0) {
        return FridaActionResult(false,
            "xz decompression failed: ${xzR.stdout.trim().take(200)}\n\n" +
            "Install BusyBox via Magisk for xz support, or decompress on your PC:\n" +
            "  unxz $assetName\n" +
            "  adb push frida-server-$version-$suffix /data/local/tmp/frida-server\n" +
            "  adb shell chmod +x /data/local/tmp/frida-server")
    }

    RootBridge.exec("chmod +x $dest")
    val verify = RootBridge.exec("[ -x $dest ] && echo ok || echo fail").stdout.trim()

    return if (verify == "ok")
        FridaActionResult(true, "frida-server $version ($suffix) installed at $dest")
    else
        FridaActionResult(false, "Install failed — file not executable at $dest")
}

// ── Certificate hash helper ───────────────────────────────────────────────────

private suspend fun computeCertHash(raw: ByteArray, context: Context): CertResult {
    val cert: X509Certificate = runCatching {
        val bytes = if (raw.decodeToString().contains("BEGIN CERTIFICATE")) raw else toPem(raw)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }.getOrElse { return CertResult(false, "Cannot parse certificate: ${it.message}") }

    val appPem = File(context.filesDir, "ca_hash_tmp.pem")
    appPem.writeText(certToPem(cert))
    val tmpPem = "/data/local/tmp/androidspect_ca_hash.pem"
    RootBridge.exec("cp '${appPem.absolutePath}' $tmpPem && chmod 644 $tmpPem 2>/dev/null || true")
    appPem.delete()

    val opensslHash = RootBridge.exec(
        "openssl x509 -inform PEM -subject_hash_old -in $tmpPem 2>/dev/null | head -1"
    ).stdout.trim()
    RootBridge.exec("rm -f $tmpPem")

    val hash = if (opensslHash.matches(Regex("[0-9a-f]{8}"))) {
        opensslHash
    } else {
        val der = javax.security.auth.x500.X500Principal(
            cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.CANONICAL)
        ).encoded
        val md5 = MessageDigest.getInstance("MD5").digest(der)
        val n = (md5[0].toLong() and 0xFF) or ((md5[1].toLong() and 0xFF) shl 8) or
                ((md5[2].toLong() and 0xFF) shl 16) or ((md5[3].toLong() and 0xFF) shl 24)
        "%08x".format(n and 0xFFFFFFFFL)
    }
    val hashSource = if (opensslHash.matches(Regex("[0-9a-f]{8}"))) "openssl" else "java"
    val certFile = "$hash.0"

    return CertResult(
        success = true,
        message = "Certificate hash ($hashSource): $certFile\n\n" +
            "Run from your PC:\n\n" +
            "# Convert and rename\n" +
            "openssl x509 -inform DER -in cacert.der -out cacert.pem\n" +
            "CERT=\$(openssl x509 -inform PEM -subject_hash_old -in cacert.pem | head -1).0\n" +
            "mv cacert.pem \$CERT\n\n" +
            "# Push and install\n" +
            "adb push \$CERT /sdcard/\n" +
            "adb shell su -c \"cp /sdcard/\$CERT /system/etc/security/cacerts/ && chmod 644 /system/etc/security/cacerts/\$CERT\"\n\n" +
            "Expected filename: $certFile"
    )
}

private fun certToPem(cert: X509Certificate): String {
    val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
    return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
}

private fun toPem(der: ByteArray): ByteArray {
    val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
    return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n".toByteArray()
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable data class CertResult(val success: Boolean, val message: String)

@Serializable data class ProxySetResult(val ok: Boolean, val value: String, val stderr: String = "")

@Serializable data class ProxyStatus(val current: String?, val host: String?, val port: Int?)

@Serializable data class ProxyRequest(val host: String = "", val port: Int? = null)

@Serializable
data class FridaStatus(
    val abi: String,
    val running: Boolean,
    val pid: Int?,
    val installed: Boolean,
    val installedVersion: String?
)

@Serializable data class FridaReleases(val versions: List<String>, val error: String? = null)

@Serializable data class FridaActionResult(val success: Boolean, val message: String)
