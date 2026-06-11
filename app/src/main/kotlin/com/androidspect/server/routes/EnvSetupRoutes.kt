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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Environment Setup — proxy configuration and CA cert hash helper.
 *
 *   POST /api/env/cert          → parse cert, compute hash, return adb install commands
 *   GET  /api/env/proxy         → current global proxy
 *   PUT  /api/env/proxy         → set global proxy { host, port }
 *   DELETE /api/env/proxy       → clear global proxy
 *
 * Note: direct installation of system CA certs is not attempted — SELinux on
 * physical devices blocks all writes to the trust store even from root, and
 * Magisk module overlays risk disabling root on reboot. Instead we compute
 * the subject_hash_old and return the exact adb commands to run from a PC.
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
            val current = if (raw == "null" || raw.isBlank()) null else raw
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
                return@put call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "invalid host"))
            val r = RootBridge.exec("settings put global http_proxy $host:$port")
            call.respond(ProxySetResult(ok = r.code == 0, value = "$host:$port", stderr = r.stderr.trim()))
        }

        delete("/proxy") {
            // On some ROMs "settings delete" doesn't reliably clear the proxy.
            // Setting it to ":0" is the standard Android trick to disable it.
            RootBridge.exec("settings put global http_proxy :0")
            val verify = RootBridge.exec("settings get global http_proxy").stdout.trim()
            val cleared = verify == ":0" || verify == "null" || verify.isBlank()
            call.respond(ProxySetResult(ok = cleared, value = ":0",
                stderr = if (!cleared) "unexpected value: $verify" else ""))
        }
    }
}

// ── Certificate hash helper ───────────────────────────────────────────────────

/**
 * Parses the cert, computes subject_hash_old (via openssl if available, else
 * Java fallback), and returns the exact adb commands to install it from a PC.
 * No writes to system partitions.
 */
private suspend fun computeCertHash(raw: ByteArray, context: android.content.Context): CertResult {
    val cert: X509Certificate = runCatching {
        val bytes = if (raw.decodeToString().contains("BEGIN CERTIFICATE")) raw else toPem(raw)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }.getOrElse { return CertResult(false, "Cannot parse certificate: ${it.message}") }

    // Write PEM to app filesDir, copy to /data/local/tmp so openssl can read it.
    val appPem = java.io.File(context.filesDir, "ca_hash_tmp.pem")
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
            "# Convert and rename (if you have a .der file)\n" +
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

@Serializable
data class CertResult(val success: Boolean, val message: String)

@Serializable
data class ProxySetResult(val ok: Boolean, val value: String, val stderr: String = "")

@Serializable
data class ProxyStatus(val current: String?, val host: String?, val port: Int?)

@Serializable
data class ProxyRequest(val host: String = "", val port: Int? = null)
