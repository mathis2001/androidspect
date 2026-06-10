package com.androidspect.server.routes

import android.content.Context
import com.androidspect.root.OverlayManager
import com.androidspect.root.OverlayParams
import com.androidspect.root.RootBridge
import com.androidspect.root.Sanitize
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * TapJacking PoC — overlay management + activity launcher.
 *
 *   GET  /api/overlay/status          → { canDraw, shown }
 *   POST /api/overlay/show            → body: OverlayParams → show/update overlay
 *   DELETE /api/overlay               → dismiss overlay
 *   POST /api/overlay/launch          → body: { pkg, activity } → am start the target
 */
fun Routing.overlayRoutes(context: Context) {

    val mgr = OverlayManager(context)

    route("/api/overlay") {

        get("/status") {
            call.respond(mapOf(
                "canDraw" to mgr.canDraw(),
                "shown"   to mgr.isShown()
            ))
        }

        post("/show") {
            if (!mgr.canDraw())
                return@post call.respond(HttpStatusCode.Forbidden, mapOf(
                    "error" to "Missing 'Display over other apps' permission.",
                    "detail" to "Grant it in Settings → Apps → AndroidSpect → Display over other apps (or Settings.ACTION_MANAGE_OVERLAY_PERMISSION)."
                ))
            val params = call.receive<OverlayParams>()
            mgr.show(params)
            call.respond(mapOf("ok" to true))
        }

        delete {
            mgr.dismiss()
            call.respond(mapOf("ok" to true))
        }

        /**
         * Launch a specific exported activity via `am start`.
         * The component is validated to be alphanumeric + dots/$ so it's safe
         * to pass to the root shell.
         */
        post("/launch") {
            val req = call.receive<LaunchRequest>()
            val pkg = Sanitize.pkg(req.pkg)
            val activity = req.activity.trim()
            if (!activity.matches(Regex("^[A-Za-z0-9._\$/-]+")))
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid activity name"))

            // Build the component string for `am start -n`:
            //   FQCN   "com.example.MainActivity"  → "com.example/.MainActivity" is wrong;
            //                                       → "com.example/com.example.MainActivity" is correct
            //   short  ".MainActivity"             → "com.example/.MainActivity"
            //   already pkg/class                  → use as-is
            val target = when {
                activity.contains('/') -> activity          // already fully-qualified pkg/class
                activity.startsWith(".") -> "$pkg/$activity"  // short name
                activity.startsWith(pkg) -> "$pkg/$activity"  // FQCN within same package
                else -> "$pkg/$activity"                    // FQCN in different package or partial
            }
            val r = RootBridge.exec("am start -n ${Sanitize.shellQuote(target)}")
            call.respond(mapOf(
                "ok"     to (r.code == 0),
                "code"   to r.code,
                "stdout" to r.stdout.trim(),
                "stderr" to r.stderr.trim(),
                "target" to target
            ))
        }
    }
}

@kotlinx.serialization.Serializable
private data class LaunchRequest(val pkg: String = "", val activity: String = "")
