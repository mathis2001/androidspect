package com.androidspect.server.routes

import com.androidspect.root.RootBridge
import com.androidspect.root.Sanitize
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Device-wide file explorer (absolute paths, root shell).
 *
 * Unlike fileRoutes (scoped to /data/data/<pkg>), these browse the entire
 * filesystem from / using RootBridge, for inspecting world files, other apps'
 * dirs, /sdcard, /system, etc.
 *
 *   GET /api/device/files?path=/abs/dir      → directory listing
 *   GET /api/device/files/raw?path=/abs/file[&download=1]  → file bytes
 *   GET /api/device/files/text?path=/abs/file              → first N KB as text
 *   GET /api/device/files/zip?path=/abs/dir                → dir as ZIP
 */
fun Routing.deviceFilesRoutes() {

    route("/api/device/files") {

        get {
            val path = normalize(call.request.queryParameters["path"] ?: "/")
            // Guard: must be an absolute path, no shell metacharacters.
            if (!isSafeAbs(path)) return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "invalid path"))

            // Verify it's a directory via the root shell (don't rely on stat()'s
            // field shape — listDir + an explicit test are robust).
            val kind = RootBridge.exec(
                "if [ -d '$path' ]; then echo dir; elif [ -e '$path' ]; then echo file; else echo none; fi"
            ).stdout.trim()
            when (kind) {
                "none" -> return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found: $path"))
                "file" -> return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "not a directory: $path"))
            }

            val entries = RootBridge.listDir(path)
            call.respond(DeviceListing(
                path = path,
                parent = parentOf(path),
                entries = entries
                    .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                    .map {
                        DeviceFileItem(
                            name = it.name,
                            path = it.path,
                            isDir = it.isDir,
                            size = it.size,
                            modifiedMs = it.modifiedMs
                        )
                    }
            ))
        }

        get("/raw") {
            val path = normalize(call.request.queryParameters["path"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required")))
            if (!isSafeAbs(path)) return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid path"))
            val download = call.request.queryParameters["download"] == "1"
            val bytes = RootBridge.readBytes(path)
            val name = path.substringAfterLast('/').ifEmpty { "file" }
            val disp = if (download) ContentDisposition.Attachment else ContentDisposition.Inline
            call.response.header(
                HttpHeaders.ContentDisposition,
                disp.withParameter(ContentDisposition.Parameters.FileName, name).toString()
            )
            call.respondBytes(bytes, ContentType.parse(mimeFor(name)))
        }

        get("/text") {
            val path = normalize(call.request.queryParameters["path"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required")))
            if (!isSafeAbs(path)) return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid path"))
            val maxBytes = (call.request.queryParameters["max"]?.toLongOrNull() ?: 512L * 1024)
                .coerceAtMost(5L * 1024 * 1024)
            val bytes = RootBridge.readBytes(path, maxBytes)
            call.respond(DeviceFileText(
                path = path,
                truncated = bytes.size.toLong() >= maxBytes,
                content = bytes.toString(Charsets.UTF_8)
            ))
        }

        get("/zip") {
            val path = normalize(call.request.queryParameters["path"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required")))
            if (!isSafeAbs(path)) return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid path"))
            val isDir = RootBridge.exec("[ -d '$path' ] && echo y || echo n").stdout.trim() == "y"
            if (!isDir) return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "not a directory"))
            val maxBytes = (call.request.queryParameters["max"]?.toLongOrNull()
                ?: 256L * 1024 * 1024).coerceAtMost(2L * 1024 * 1024 * 1024)
            val zipName = (path.trim('/').replace('/', '_').ifEmpty { "root" }) + ".zip"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, zipName).toString()
            )
            call.respondOutputStream(ContentType.Application.Zip) {
                withContext(Dispatchers.IO) {
                    ZipOutputStream(this@respondOutputStream).use { zip ->
                        zipDir(path, path.substringAfterLast('/').ifEmpty { "root" }, zip, maxBytes)
                    }
                }
            }
        }
    }
}

// ── helpers ─────────────────────────────────────────────────────────────────

/** Collapse `..`/`.` and duplicate slashes; always returns an absolute path. */
private fun normalize(raw: String): String {
    val p = if (raw.startsWith("/")) raw else "/$raw"
    val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
    val stack = ArrayDeque<String>()
    for (seg in parts) {
        if (seg == "..") { if (stack.isNotEmpty()) stack.removeLast() }
        else stack.addLast(seg)
    }
    return "/" + stack.joinToString("/")
}

/** Absolute path with no shell metacharacters or quotes. */
private fun isSafeAbs(path: String): Boolean =
    path.startsWith("/") && path.none { it == '\'' || it == '"' || it == '`' || it == '$' ||
                                        it == ';' || it == '|' || it == '&' || it == '\n' }

private fun parentOf(path: String): String? {
    if (path == "/") return null
    val idx = path.trimEnd('/').lastIndexOf('/')
    return if (idx <= 0) "/" else path.trimEnd('/').substring(0, idx)
}

private suspend fun zipDir(absPath: String, prefix: String, zip: ZipOutputStream, maxBytes: Long) {
    for (entry in RootBridge.listDir(absPath)) {
        val childPrefix = "$prefix/${entry.name}"
        if (entry.isDir) {
            zipDir(entry.path, childPrefix, zip, maxBytes)
        } else {
            if (entry.size > maxBytes) continue
            runCatching {
                zip.putNextEntry(ZipEntry(childPrefix))
                zip.write(RootBridge.readBytes(entry.path, maxBytes))
                zip.closeEntry()
            }
        }
    }
}

private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "log", "md", "ini", "conf", "prop", "rc" -> "text/plain"
    "xml" -> "text/xml"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "zip", "apk", "jar" -> "application/zip"
    "so" -> "application/octet-stream"
    "db", "sqlite", "sqlite3" -> "application/octet-stream"
    else -> "application/octet-stream"
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable
private data class DeviceListing(
    val path: String,
    val parent: String?,
    val entries: List<DeviceFileItem>
)

@Serializable
private data class DeviceFileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedMs: Long
)

@Serializable
private data class DeviceFileText(
    val path: String,
    val truncated: Boolean,
    val content: String
)
