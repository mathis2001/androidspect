package com.androidspect.server.routes

import android.content.Context
import com.androidspect.root.Sanitize
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Per-package Markdown notes, persisted locally in the app's files dir.
 *
 * Notes are stored as plain .md files under filesDir/notes/<pkg>.md so they
 * survive app restarts and are easy to back up or pull off the device.
 *
 * ── Endpoints ──────────────────────────────────────────────────────────────
 *   GET    /api/apps/{pkg}/notes          → { pkg, markdown, updatedAt }
 *   GET    /api/apps/{pkg}/notes/download  → .md file as attachment
 *   PUT    /api/apps/{pkg}/notes           body: { markdown } → { pkg, markdown, updatedAt }
 *   DELETE /api/apps/{pkg}/notes           → { pkg, deleted }
 */
fun Routing.notesRoutes(context: Context) {

    val notesDir = File(context.filesDir, "notes").also { it.mkdirs() }

    fun noteFile(pkg: String): File {
        // Sanitize.pkg validates the package name (rejects path traversal /
        // shell metacharacters), so the filename is always safe.
        val safe = Sanitize.pkg(pkg)
        return File(notesDir, "$safe.md")
    }

    route("/api/apps/{pkg}/notes") {

        get {
            val pkg = call.parameters["pkg"].orEmpty()
            val f = noteFile(pkg)
            val markdown  = if (f.isFile) f.readText(Charsets.UTF_8) else ""
            val updatedAt = if (f.isFile) f.lastModified() else 0L
            call.respond(NoteResponse(pkg = pkg, markdown = markdown, updatedAt = updatedAt))
        }

        get("/download") {
            val pkg = call.parameters["pkg"].orEmpty()
            val f = noteFile(pkg)
            if (!f.isFile) return@get call.respond(
                HttpStatusCode.NotFound, mapOf("error" to "no notes for $pkg")
            )
            val filename = "${Sanitize.pkg(pkg)}-notes.md"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, filename)
                    .toString()
            )
            call.respondText(f.readText(Charsets.UTF_8), ContentType.parse("text/markdown"))
        }

        put {
            val pkg = call.parameters["pkg"].orEmpty()
            val req = call.receive<NoteRequest>()
            val f = noteFile(pkg)
            if (req.markdown.isEmpty()) {
                // Empty content removes the note rather than leaving a blank file.
                if (f.exists()) f.delete()
                call.respond(NoteResponse(pkg = pkg, markdown = "", updatedAt = 0L))
            } else {
                f.writeText(req.markdown, Charsets.UTF_8)
                call.respond(NoteResponse(pkg = pkg, markdown = req.markdown, updatedAt = f.lastModified()))
            }
        }

        delete {
            val pkg = call.parameters["pkg"].orEmpty()
            val f = noteFile(pkg)
            val existed = f.exists()
            if (existed) f.delete()
            call.respond(mapOf("pkg" to pkg, "deleted" to existed))
        }
    }
}

@Serializable
private data class NoteRequest(val markdown: String = "")

@Serializable
private data class NoteResponse(val pkg: String, val markdown: String, val updatedAt: Long)
