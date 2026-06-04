package com.androidspect.server.routes

import android.content.Context
import com.androidspect.root.ComponentInspector
import com.androidspect.root.ManifestDecoder
import com.androidspect.root.SnapshotManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

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

    route("/api/snapshots") {

        get { call.respond(mgr.listAll()) }

        post("/apk") {
            val req = call.receive<ApkSnapshotRequest>()
            if (req.apkPath.isBlank() || req.pkg.isBlank())
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "apkPath and pkg required"))
            // ManifestDecoder/ComponentInspector resolve from an installed pkg;
            // for an arbitrary APK we still decode the manifest from its path.
            val manifest = manifestDecoder.decode(req.pkg)
            val components = inspector.list(req.pkg)
            val snap = mgr.captureApk(req.apkPath, manifest, components, req.pkg)
            call.respond(HttpStatusCode.Created, mapOf("id" to snap.id))
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
@Serializable private data class ApkSnapshotRequest(val apkPath: String = "", val pkg: String = "")
