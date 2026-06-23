package com.androidspect.server.routes

import android.content.ClipboardManager
import android.content.Context
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Clipboard reader — uses ClipboardManager directly, no shell parsing.
 *
 *   GET /api/clipboard → ClipboardEntry { content, itemCount, mimeTypes, timestamp }
 *
 * Note: Android 10+ restricts background clipboard access to apps that are
 * the default IME or have accessibility service. AndroidSpect runs as a
 * foreground service so access is granted in most cases.
 */
fun Routing.clipboardRoutes(context: Context) {
    route("/api/clipboard") {
        get {
            val entry = readClipboard(context)
            call.respond(entry)
        }
    }
}

private fun readClipboard(context: Context): ClipboardEntry {
    val ts = System.currentTimeMillis()
    return try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        if (!cm.hasPrimaryClip()) {
            return ClipboardEntry(
                content    = "",
                itemCount  = 0,
                mimeTypes  = emptyList(),
                timestamp  = ts,
                error      = "Clipboard is empty."
            )
        }

        val clip = cm.primaryClip
            ?: return ClipboardEntry(
                content    = "",
                itemCount  = 0,
                mimeTypes  = emptyList(),
                timestamp  = ts,
                error      = "Could not read primary clip."
            )

        // Collect all text items.
        val items = (0 until clip.itemCount).mapNotNull { i ->
            clip.getItemAt(i)?.coerceToText(context)?.toString()
        }

        // Collect MIME types from the description.
        val desc = clip.description
        val mimes = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }

        ClipboardEntry(
            content    = items.joinToString("\n---\n"),
            itemCount  = clip.itemCount,
            mimeTypes  = mimes,
            timestamp  = ts,
            error      = if (items.isEmpty()) "Clip has no text items (may be image or URI)." else null
        )
    } catch (e: SecurityException) {
        ClipboardEntry(
            content    = "",
            itemCount  = 0,
            mimeTypes  = emptyList(),
            timestamp  = ts,
            error      = "Access denied: ${e.message}\nOn Android 10+, clipboard access from background requires the app to have been in the foreground recently."
        )
    } catch (e: Exception) {
        ClipboardEntry(
            content    = "",
            itemCount  = 0,
            mimeTypes  = emptyList(),
            timestamp  = ts,
            error      = "${e::class.java.simpleName}: ${e.message}"
        )
    }
}

@Serializable
data class ClipboardEntry(
    val content: String,
    val itemCount: Int,
    val mimeTypes: List<String>,
    val timestamp: Long,
    val error: String? = null
)
