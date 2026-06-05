package com.androidspect.server.routes

import android.content.Context
import com.androidspect.root.ComponentInspector
import com.androidspect.root.ManifestDecoder
import com.androidspect.root.SnapshotManager
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.io.File

/**
 * App version snapshot + diff.
 *
 *   GET    /api/snapshots                      → all snapshots (every package)
 *   GET    /api/snapshots/{pkg}                → snapshots for one package
 *   POST   /api/snapshots/{pkg}                → capture installed app now
 *                                                body: { includeDataDir: bool }
 *   POST   /api/snapshots/apk                  → capture from an APK path
 *                                                body: { apkPath, pkg }
 *   GET    /api/snapshots/{pkg}/{id}           → full snapshot JSON
 *   DELETE /api/snapshots/{pkg}/{id}           → delete a snapshot
 *   GET    /api/snapshots/{pkg}/diff?a=ID&b=ID → diff two snapshots
 */
fun Routing.snapshotRoutes(context: Context) {

    val mgr = SnapshotManager(context)
    val manifestDecoder = ManifestDecoder(context)
    val inspector = ComponentInspector(context)
    val stageDir = File(context.cacheDir, "snap-apk").also { it.mkdirs() }

    route("/api/snapshots") {

        get { call.respond(mgr.listAll()) }

        // Snapshot an APK uploaded from the computer (multipart/form-data).
        // ?pkg= labels which package dir to store it under.
        post("/apk") {
            val pkg = call.request.queryParameters["pkg"]?.takeIf { it.isNotBlank() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "pkg query param required"))

            var staged: File? = null
            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                if (part is PartData.FileItem && staged == null) {
                    val f = File(stageDir, "apk_${System.nanoTime()}.apk")
                    part.streamProvider().use { input -> f.outputStream().use { input.copyTo(it) } }
                    staged = f
                }
                part.dispose()
            }
            val apk = staged
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no APK file in upload"))
            try {
                // Decode manifest/components from the *installed* pkg if present;
                // for an arbitrary uploaded APK these resolve from the package name
                // the user supplied (best-effort — components/manifest reflect the
                // installed app, while versionCode/code/native come from the APK).
                val manifest = manifestDecoder.decode(pkg)
                val components = inspector.list(pkg)
                val snap = mgr.captureApk(apk.absolutePath, manifest, components, pkg)
                call.respond(HttpStatusCode.Created, mapOf("id" to snap.id))
            } finally {
                apk.delete()
            }
        }

        // Import a previously-exported snapshot JSON (multipart or raw body).
        post("/import") {
            val raw = if (call.request.headers[HttpHeaders.ContentType]?.contains("multipart") == true) {
                var text: String? = null
                call.receiveMultipart().forEachPart { part ->
                    if (part is PartData.FileItem && text == null) {
                        text = part.streamProvider().use { it.readBytes().toString(Charsets.UTF_8) }
                    }
                    part.dispose()
                }
                text
            } else {
                call.receive<String>()
            }
            if (raw.isNullOrBlank())
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no snapshot JSON provided"))
            try {
                val id = mgr.importJson(raw)
                call.respond(HttpStatusCode.Created, mapOf("id" to id))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid snapshot JSON: ${e.message}"))
            }
        }

        route("/{pkg}") {

            get {
                val pkg = call.parameters["pkg"].orEmpty()
                call.respond(mgr.list(pkg))
            }

            post {
                val pkg = call.parameters["pkg"].orEmpty()
                val req = runCatching { call.receive<CaptureRequest>() }.getOrDefault(CaptureRequest())
                val manifest = manifestDecoder.decode(pkg)
                val components = inspector.list(pkg)
                val snap = mgr.captureInstalled(pkg, manifest, components, req.includeDataDir)
                call.respond(HttpStatusCode.Created, mapOf("id" to snap.id))
            }

            get("/diff") {
                val pkg = call.parameters["pkg"].orEmpty()
                val aId = call.request.queryParameters["a"]
                val bId = call.request.queryParameters["b"]
                if (aId == null || bId == null)
                    return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "a and b required"))
                val a = mgr.load(pkg, aId) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "snapshot a not found"))
                val b = mgr.load(pkg, bId) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "snapshot b not found"))
                call.respond(mgr.diff(a, b))
            }

            route("/{id}") {
                get {
                    val pkg = call.parameters["pkg"].orEmpty()
                    val id = call.parameters["id"].orEmpty()
                    val snap = mgr.load(pkg, id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
                    call.respond(snap)
                }
                get("/export") {
                    val pkg = call.parameters["pkg"].orEmpty()
                    val id = call.parameters["id"].orEmpty()
                    val raw = mgr.exportJson(pkg, id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
                    val fname = "snapshot-${pkg}-${id}.json"
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, fname).toString()
                    )
                    call.respondText(raw, ContentType.Application.Json)
                }
                delete {
                    val pkg = call.parameters["pkg"].orEmpty()
                    val id = call.parameters["id"].orEmpty()
                    call.respond(mapOf("deleted" to mgr.delete(pkg, id)))
                }
            }
        }
    }
}

@Serializable private data class CaptureRequest(val includeDataDir: Boolean = true)
