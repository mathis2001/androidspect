package com.androidspect.server.routes

import android.content.Context
import com.androidspect.root.WebExtractor
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Web attack-surface extraction.
 *   GET /api/apps/{pkg}/web → { urls, endpoints[{path,params}], params }
 */
fun Routing.webRoutes(context: Context) {
    val extractor = WebExtractor(context)
    route("/api/apps/{pkg}/web") {
        get {
            val pkg = call.parameters["pkg"].orEmpty()
            call.respond(extractor.extract(pkg))
        }
    }
}
