package com.androidspect.server.routes

import android.content.Context
import com.androidspect.root.RootBridge
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen capture — screenshots and screen recordings.
 *
 *   POST   /api/capture/screenshot         → take screenshot, return CaptureMeta
 *   POST   /api/capture/record/start       → start screenrecord in background
 *   POST   /api/capture/record/stop        → send SIGINT to finalize MP4, pull file
 *   GET    /api/capture/record/status      → { recording, pid, elapsedSeconds }
 *   GET    /api/capture                    → list all captures (images + videos)
 *   GET    /api/capture/{name}             → serve raw file bytes
 *   DELETE /api/capture/{name}             → delete a capture
 */
fun Routing.screenshotRoutes(context: Context) {

    val dir = File(context.filesDir, "captures").also { it.mkdirs() }
    val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // Track recording state.
    var recPid: Int? = null
    var recStartMs: Long = 0L
    var recTmpPath: String? = null

    route("/api/capture") {

        // ── Screenshot ────────────────────────────────────────────────────────

        post("/screenshot") {
            val name = "screenshot_${sdf.format(Date())}.png"
            val tmp  = "/data/local/tmp/androidspect_sc.png"
            val dest = File(dir, name)

            val r = RootBridge.exec("screencap -p $tmp 2>&1")
            if (r.code != 0)
                return@post call.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to "screencap failed: ${(r.stdout + r.stderr).trim().take(200)}"))

            RootBridge.exec("cp $tmp '${dest.absolutePath}' && chmod 644 '${dest.absolutePath}'")
            RootBridge.exec("rm -f $tmp")

            if (!dest.exists() || dest.length() == 0L) {
                val bytes = RootBridge.readBytes(tmp)
                if (bytes == null || bytes.isEmpty())
                    return@post call.respond(HttpStatusCode.InternalServerError,
                        mapOf("error" to "Screenshot captured but file could not be retrieved"))
                dest.writeBytes(bytes)
            }
            call.respond(dest.toMeta())
        }

        // ── Screen recording ──────────────────────────────────────────────────

        post("/record/start") {
            if (recPid != null) {
                val alive = RootBridge.exec("kill -0 $recPid 2>/dev/null && echo y || echo n").stdout.trim() == "y"
                if (alive)
                    return@post call.respond(mapOf("error" to "Already recording (PID $recPid)"))
            }
            val name = "screenrecord_${sdf.format(Date())}.mp4"
            val tmp  = "/data/local/tmp/$name"
            recTmpPath = tmp

            // Start screenrecord in background. Max 3 min safety limit.
            // SIGINT (kill -2) is required to finalize the MP4 — SIGKILL corrupts it.
            val r = RootBridge.exec("screenrecord --time-limit 180 $tmp &")
            kotlinx.coroutines.delay(400)
            val pid = RootBridge.exec("pidof screenrecord 2>/dev/null").stdout.trim().toIntOrNull()
            if (pid == null || pid == 0) {
                return@post call.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to "screenrecord did not start: ${(r.stdout + r.stderr).trim().take(200)}"))
            }
            recPid = pid
            recStartMs = System.currentTimeMillis()
            call.respond(RecordStatus(recording = true, pid = pid, elapsedSeconds = 0))
        }

        post("/record/stop") {
            val pid = recPid
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Not recording"))
            val tmp = recTmpPath
                ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No temp path"))

            // SIGINT finalizes the MP4 cleanly.
            RootBridge.exec("kill -2 $pid 2>/dev/null || kill -SIGINT $pid 2>/dev/null || true")
            // Wait for screenrecord to finish writing (up to 5 seconds).
            var waited = 0
            while (waited < 5000) {
                val alive = RootBridge.exec("kill -0 $pid 2>/dev/null && echo y || echo n").stdout.trim() == "y"
                if (!alive) break
                kotlinx.coroutines.delay(300)
                waited += 300
            }
            recPid = null

            // Pull the file into the captures dir.
            val name = tmp.substringAfterLast('/')
            val dest = File(dir, name)
            RootBridge.exec("cp '$tmp' '${dest.absolutePath}' && chmod 644 '${dest.absolutePath}'")
            RootBridge.exec("rm -f '$tmp'")
            recTmpPath = null

            if (!dest.exists() || dest.length() == 0L)
                return@post call.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to "Recording stopped but output file is empty or missing"))

            call.respond(dest.toMeta())
        }

        get("/record/status") {
            val pid = recPid
            if (pid == null) {
                call.respond(RecordStatus(recording = false, pid = null, elapsedSeconds = 0))
                return@get
            }
            val alive = RootBridge.exec("kill -0 $pid 2>/dev/null && echo y || echo n").stdout.trim() == "y"
            if (!alive) recPid = null
            val elapsed = if (alive) ((System.currentTimeMillis() - recStartMs) / 1000).toInt() else 0
            call.respond(RecordStatus(recording = alive, pid = if (alive) pid else null, elapsedSeconds = elapsed))
        }

        // ── Gallery ───────────────────────────────────────────────────────────

        get {
            val captures = dir.listFiles { f -> f.extension == "png" || f.extension == "mp4" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.toMeta() }
                ?: emptyList()
            call.respond(captures)
        }

        get("/{name}") {
            val name = call.parameters["name"]?.filter { it.isLetterOrDigit() || it == '_' || it == '.' }
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val file = File(dir, name)
            if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound)
            val ct = if (file.extension == "mp4") ContentType.Video.MP4 else ContentType.Image.PNG
            call.respondBytes(file.readBytes(), ct)
        }

        delete("/{name}") {
            val name = call.parameters["name"]?.filter { it.isLetterOrDigit() || it == '_' || it == '.' }
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val file = File(dir, name)
            call.respond(mapOf("ok" to file.delete().toString()))
        }
    }
}

private fun File.toMeta() = CaptureMeta(
    name      = name,
    type      = if (extension == "mp4") "video" else "image",
    url       = "/api/capture/$name",
    sizeBytes = length(),
    timestamp = lastModified()
)

@Serializable
data class CaptureMeta(
    val name: String,
    val type: String,       // "image" | "video"
    val url: String,
    val sizeBytes: Long,
    val timestamp: Long
)

@Serializable
data class RecordStatus(
    val recording: Boolean,
    val pid: Int?,
    val elapsedSeconds: Int
)
