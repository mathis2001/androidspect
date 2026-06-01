package com.androidspect.server.routes

import android.content.Context
import com.androidspect.root.AppActions
import com.androidspect.root.Sanitize
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * APK Decompiler routes — uses jadx-core as an in-process library.
 * No subprocess, no JVM conflict, no Termux dependency.
 *
 * Add to app/build.gradle.kts:
 *
 *   dependencies {
 *       implementation("io.github.skylot:jadx-core:1.5.5")
 *       implementation("io.github.skylot:jadx-dex-input:1.5.5")
 *       implementation("io.github.skylot:jadx-java-input:1.5.5")
 *   }
 *
 *   // jadx uses google() for its aapt dependency
 *   repositories { google() }
 *
 *   // Exclude duplicate slf4j bindings if you hit a conflict:
 *   configurations.all {
 *       exclude(group = "org.slf4j", module = "slf4j-simple")
 *   }
 *
 * Wire up in AndroidSpectServer.kt routing block:
 *   decompilerRoutes(context)
 *
 * ── Endpoints ──────────────────────────────────────────────────────────────
 *
 *   POST   /api/decompiler/jobs           { "pkg": "com.example" } | { "apkPath": "…" }
 *   GET    /api/decompiler/jobs           list all jobs
 *   GET    /api/decompiler/jobs/{id}      job detail + progress
 *   DELETE /api/decompiler/jobs/{id}      cancel + delete output
 *   GET    /api/decompiler/jobs/{id}/tree file tree JSON
 *   GET    /api/decompiler/jobs/{id}/file?path=… file content + language
 *   GET    /api/decompiler/jobs/{id}/search?q=… grep source
 *   GET    /api/decompiler/jobs/{id}/download?path=… single file attachment
 *   GET    /api/decompiler/jobs/{id}/zip  full tree as ZIP
 */
fun Routing.decompilerRoutes(context: Context) {

    val workDir = File(context.cacheDir, "decompiler").also { it.mkdirs() }

    route("/api/decompiler") {

        // ── POST /api/decompiler/jobs ─────────────────────────────────────────
        post("/jobs") {
            val req = call.receive<StartJobRequest>()

            val apkPath: String = when {
                req.apkPath != null -> {
                    if (!File(req.apkPath).exists()) return@post call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "APK not found: ${req.apkPath}")
                    )
                    req.apkPath
                }
                req.pkg != null -> {
                    val paths = AppActions.apkPaths(req.pkg)
                    if (paths.isEmpty()) return@post call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "No APK found for package: ${req.pkg}")
                    )
                    paths.first()
                }
                else -> return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Provide either 'pkg' or 'apkPath'")
                )
            }

            val label = req.pkg ?: File(apkPath).nameWithoutExtension
            val job   = DecompileJob.create(label, apkPath, workDir)
            DecompileJob.register(job)
            job.start()

            call.respond(HttpStatusCode.Accepted, mapOf("jobId" to job.id))
        }

        // ── GET /api/decompiler/jobs ──────────────────────────────────────────
        get("/jobs") {
            call.respond(DecompileJob.all().map { it.toSummary() })
        }

        // ── Routes scoped to a specific job ───────────────────────────────────
        route("/jobs/{id}") {

            get {
                val job = jobOrNotFound(call, call.parameters["id"]) ?: return@get
                call.respond(job.toDetail())
            }

            delete {
                val job = jobOrNotFound(call, call.parameters["id"]) ?: return@delete
                job.cancel()
                DecompileJob.remove(job.id)
                call.respond(mapOf("deleted" to job.id))
            }

            get("/tree") {
                val job = jobOrNotFound(call, call.parameters["id"]) ?: return@get
                if (job.status != JobStatus.DONE) return@get call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "Job not complete", "status" to job.status.name)
                )
                call.respond(buildTree(job.outputDir, job.outputDir))
            }

            get("/file") {
                val job = jobOrNotFound(call, call.parameters["id"]) ?: return@get
                if (job.status != JobStatus.DONE) return@get call.respond(
                    HttpStatusCode.Conflict, mapOf("error" to "Job not complete")
                )
                val rel = call.request.queryParameters["path"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required"))
                val target = jailcheck(job.outputDir, rel)
                    ?: return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "path outside output dir"))
                if (!target.isFile) return@get call.respond(HttpStatusCode.NotFound)
                call.respond(FileContent(
                    path     = rel,
                    content  = target.readText(Charsets.UTF_8),
                    language = languageFor(target.name),
                    size     = target.length()
                ))
            }

            get("/search") {
                val job = jobOrNotFound(call, call.parameters["id"]) ?: return@get
                if (job.status != JobStatus.DONE) return@get call.respond(
                    HttpStatusCode.Conflict, mapOf("error" to "Job not complete")
                )
                val q = call.request.queryParameters["q"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "q required"))
                val ext        = call.request.queryParameters["ext"]
                val ignoreCase = call.request.queryParameters["i"] != "0"
                val limit      = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
                val hits = withContext(Dispatchers.IO) {
                    grepDir(job.outputDir, Sanitize.safeGrepPattern(q), ext, ignoreCase, limit)
                }
                call.respond(SearchResult(query = q, total = hits.size, hits = hits))
            }

            get("/download") {
                val job = jobOrNotFound(call, call.parameters["id"]) ?: return@get
                if (job.status != JobStatus.DONE) return@get call.respond(
                    HttpStatusCode.Conflict, mapOf("error" to "Job not complete")
                )
                val rel = call.request.queryParameters["path"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required"))
                val target = jailcheck(job.outputDir, rel)
                    ?: return@get call.respond(HttpStatusCode.Forbidden)
                if (!target.isFile) return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, target.name).toString()
                )
                call.respondBytes(target.readBytes(), ContentType.Application.OctetStream)
            }

            get("/zip") {
                val job = jobOrNotFound(call, call.parameters["id"]) ?: return@get
                if (job.status != JobStatus.DONE) return@get call.respond(
                    HttpStatusCode.Conflict, mapOf("error" to "Job not complete")
                )
                val zipName = "${job.label}_decompiled.zip"
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, zipName).toString()
                )
                call.respondOutputStream(ContentType.Application.Zip) {
                    withContext(Dispatchers.IO) {
                        java.util.zip.ZipOutputStream(this@respondOutputStream).use { zip ->
                            zipDir(job.outputDir, "", zip)
                        }
                    }
                }
            }
        }
    }
}

// ── Job helper ────────────────────────────────────────────────────────────────

private suspend fun jobOrNotFound(
    call: io.ktor.server.application.ApplicationCall,
    id: String?
): DecompileJob? {
    if (id.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest); return null }
    val job = DecompileJob.find(id)
    if (job == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "job not found"))
    return job
}

// ── DecompileJob ──────────────────────────────────────────────────────────────

enum class JobStatus { PENDING, RUNNING, DONE, ERROR, CANCELLED }

class DecompileJob private constructor(
    val id:        String,
    val label:     String,
    val apkPath:   String,
    val outputDir: File,
) {
    var status:     JobStatus = JobStatus.PENDING
        private set
    var progress:   Int    = 0
        private set
    var message:    String = "Waiting…"
        private set
    var startedAt:  Long   = 0L
        private set
    var finishedAt: Long   = 0L
        private set

    @Volatile private var cancelled = false

    fun start() {
        status    = JobStatus.RUNNING
        startedAt = System.currentTimeMillis()
        outputDir.mkdirs()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runJadx() }
    }

    private fun runJadx() {
        try {
            message = "Configuring jadx…"

            val args = JadxArgs().apply {
                setInputFile(File(apkPath))
                outDir = outputDir
                isDeobfuscationOn  = true
                isShowInconsistentCode = true
                threadsCount       = 2
            }

            JadxDecompiler(args).use { jadx ->
                message  = "Loading APK…"
                progress = 5
                jadx.load()

                if (cancelled) return

                val classes = jadx.classes
                val total   = classes.size.coerceAtLeast(1)
                message  = "Decompiling $total classes…"
                progress = 10

                // Save everything — jadx writes sources + resources to outputDir
                jadx.save()

                if (!cancelled) {
                    status   = JobStatus.DONE
                    progress = 100
                    message  = "Decompilation complete ($total classes)"
                }
            }
        } catch (e: Exception) {
            if (!cancelled) {
                status  = JobStatus.ERROR
                message = e.message ?: e::class.java.simpleName
            }
        } finally {
            finishedAt = System.currentTimeMillis()
        }
    }

    fun cancel() {
        cancelled = true
        status    = JobStatus.CANCELLED
        outputDir.deleteRecursively()
    }

    fun toSummary() = JobSummary(id, label, status.name, progress, apkPath)
    fun toDetail()  = JobDetail(id, label, status.name, progress, message, apkPath,
                                outputDir.absolutePath, startedAt, finishedAt)

    companion object {
        private val registry = ConcurrentHashMap<String, DecompileJob>()

        fun create(label: String, apkPath: String, workDir: File): DecompileJob {
            val id = UUID.randomUUID().toString()
            return DecompileJob(id, label, apkPath, File(workDir, id))
        }

        fun register(job: DecompileJob) { registry[job.id] = job }
        fun find(id: String): DecompileJob? = registry[id]
        fun all(): List<DecompileJob> = registry.values.sortedByDescending { it.startedAt }
        fun remove(id: String) { registry.remove(id) }
    }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

private fun jailcheck(root: File, rel: String): File? {
    val resolved = File(root, rel).canonicalFile
    return if (resolved.absolutePath.startsWith(root.canonicalFile.absolutePath + File.separator) ||
               resolved == root.canonicalFile) resolved else null
}

private fun buildTree(node: File, base: File): TreeNode {
    if (node.isFile) return TreeNode(
        name     = node.name,
        path     = node.relativeTo(base).path,
        type     = "file",
        size     = node.length(),
        language = languageFor(node.name),
        children = null
    )
    val children = (node.listFiles() ?: emptyArray())
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .map { buildTree(it, base) }
    return TreeNode(
        name     = if (node == base) base.name else node.name,
        path     = node.relativeTo(base).path,
        type     = "dir",
        size     = null,
        language = null,
        children = children
    )
}

private fun zipDir(dir: File, prefix: String, zip: java.util.zip.ZipOutputStream) {
    dir.listFiles()?.forEach { f ->
        val entry = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
        if (f.isDirectory) {
            zip.putNextEntry(java.util.zip.ZipEntry("$entry/")); zip.closeEntry()
            zipDir(f, entry, zip)
        } else {
            zip.putNextEntry(java.util.zip.ZipEntry(entry))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

private fun grepDir(
    dir: File, pattern: String, ext: String?,
    ignoreCase: Boolean, limit: Int
): List<SearchHit> {
    val cmd = mutableListOf("grep", "-rnE")
    if (ignoreCase) cmd.add("-i")
    if (!ext.isNullOrBlank()) cmd.add("--include=*.$ext")
    cmd.add(pattern)
    cmd.add(dir.absolutePath)
    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val hits = mutableListOf<SearchHit>()
    proc.inputStream.bufferedReader().useLines { lines ->
        for (line in lines) {
            if (hits.size >= limit) break
            val c1 = line.indexOf(':'); if (c1 < 0) continue
            val c2 = line.indexOf(':', c1 + 1); if (c2 < 0) continue
            val abs   = line.substring(0, c1)
            val lineNo = line.substring(c1 + 1, c2).toIntOrNull() ?: 0
            val text  = line.substring(c2 + 1)
            hits += SearchHit(abs.removePrefix(dir.absolutePath).trimStart('/'), lineNo, text)
        }
    }
    proc.waitFor()
    return hits
}

private val LANG_MAP = mapOf(
    "java" to "java",  "kt"   to "kotlin",  "kts"  to "kotlin",
    "xml"  to "xml",   "json" to "json",     "smali" to "smali",
    "gradle" to "groovy", "properties" to "ini", "pro" to "ini",
    "txt"  to "plaintext", "html" to "html", "htm"  to "html",
    "js"   to "javascript", "ts"  to "typescript",
    "py"   to "python", "sh"   to "bash",
    "yaml" to "yaml",  "yml"  to "yaml",
    "md"   to "markdown", "cpp" to "cpp", "c" to "c", "h" to "cpp",
)

private fun languageFor(name: String) =
    LANG_MAP[name.substringAfterLast('.', "").lowercase()] ?: "plaintext"

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable private data class StartJobRequest(val pkg: String? = null, val apkPath: String? = null)

@Serializable data class JobSummary(val id: String, val label: String, val status: String, val progress: Int, val apkPath: String)

@Serializable data class JobDetail(val id: String, val label: String, val status: String, val progress: Int, val message: String, val apkPath: String, val outputDir: String, val startedAt: Long, val finishedAt: Long)

@Serializable data class TreeNode(val name: String, val path: String, val type: String, val size: Long?, val language: String?, val children: List<TreeNode>?)

@Serializable private data class FileContent(val path: String, val content: String, val language: String, val size: Long)

@Serializable private data class SearchHit(val path: String, val line: Int, val text: String)

@Serializable private data class SearchResult(val query: String, val total: Int, val hits: List<SearchHit>)
