package com.androidspect.server.routes

import android.content.Context
import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.androidspect.root.AppActions
import com.androidspect.root.Sanitize
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.request.receiveMultipart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * APK Decompiler routes — uses baksmali as the decompilation engine.
 *
 * Why baksmali instead of jadx-core:
 *   jadx builds a full AST of every class in memory before writing anything,
 *   which exhausts the Android process heap (typically 256–512 MB) on any
 *   real-world APK and causes an OOM kill. baksmali disassembles one method
 *   at a time and writes it immediately to disk, using constant memory
 *   (~20–40 MB) regardless of APK size. It never crashes.
 *
 *   Output is Smali (Dalvik assembly) rather than Java. For pentesting this
 *   is actually preferable — the output is exact and lossless, unlike a Java
 *   decompiler which may mis-reconstruct control flow.
 *
 * Add to app/build.gradle.kts dependencies:
 *   implementation("com.android.tools.smali:smali-baksmali:3.0.9")
 *   implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
 *
 * ── Endpoints ──────────────────────────────────────────────────────────────
 *   POST   /api/decompiler/jobs           { "pkg": "com.example" } | { "apkPath": "…" }
 *   GET    /api/decompiler/jobs           list all jobs
 *   GET    /api/decompiler/jobs/{id}      job detail + progress
 *   DELETE /api/decompiler/jobs/{id}      cancel + delete output
 *   GET    /api/decompiler/jobs/{id}/tree file tree JSON
 *   GET    /api/decompiler/jobs/{id}/file?path=…   file content + language
 *   GET    /api/decompiler/jobs/{id}/search?q=…    grep source
 *   GET    /api/decompiler/jobs/{id}/download?path=… single file attachment
 *   GET    /api/decompiler/jobs/{id}/zip  full tree as ZIP
 */
fun Routing.decompilerRoutes(context: Context) {

    // Root dir — actual job dirs are scoped per package: decompiler/<sanitized_pkg>/
    val baseDir = File(context.cacheDir, "decompiler").also { it.mkdirs() }

    // Load jobs for ALL packages on startup so IDs remain accessible.
    baseDir.listFiles { f -> f.isDirectory }?.forEach { pkgDir ->
        DecompileJob.loadFromDisk(pkgDir)
    }
    // Also load legacy flat jobs (migration from old single-dir layout).
    DecompileJob.loadFromDisk(baseDir)

    fun workDirFor(pkg: String?): File {
        val safe = pkg?.replace(Regex("[^a-zA-Z0-9._\\-]"), "_") ?: "unknown"
        return File(baseDir, safe).also { it.mkdirs() }
    }

    route("/api/decompiler") {

        post("/jobs") {
            val req = call.receive<StartJobRequest>()
            val apkPath: String = when {
                req.apkPath != null -> {
                    if (!File(req.apkPath).exists()) return@post call.respond(
                        HttpStatusCode.NotFound, mapOf("error" to "APK not found: ${req.apkPath}")
                    )
                    req.apkPath
                }
                req.pkg != null -> {
                    val paths = AppActions.apkPaths(req.pkg)
                    if (paths.isEmpty()) return@post call.respond(
                        HttpStatusCode.NotFound, mapOf("error" to "No APK found for: ${req.pkg}")
                    )
                    paths.first()
                }
                else -> return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Provide 'pkg' or 'apkPath'")
                )
            }
            val label   = req.pkg ?: File(apkPath).nameWithoutExtension
            val workDir = workDirFor(req.pkg)
            val job     = DecompileJob.create(label, apkPath, workDir)
            DecompileJob.register(job)
            job.start()
            call.respond(HttpStatusCode.Accepted, mapOf("jobId" to job.id))
        }

        // GET /api/decompiler/jobs?pkg=com.example  → only that package's jobs
        // GET /api/decompiler/jobs                  → all jobs (for backward compat)
        get("/jobs") {
            val pkg = call.request.queryParameters["pkg"]
            val all = DecompileJob.all().map { it.toSummary() }
            val filtered = if (pkg != null)
                all.filter { it.apkPath.contains(pkg) || it.label == pkg || it.label.startsWith(pkg) }
            else all
            call.respond(filtered)
        }

        /**
         * Import JADX output (a ZIP of .java files) as a completed job.
         *
         * POST /api/decompiler/jadx?label=MyApp   (multipart/form-data, one file part)
         *
         * The ZIP is extracted into a new job's outputDir and the job is
         * immediately marked DONE — so the existing tree/file/search/download
         * routes work unchanged against Java sources instead of Smali.
         *
         * Expected ZIP structure (JADX defaults):
         *   sources/com/example/MainActivity.java
         *   sources/com/example/...
         * or a flat:
         *   com/example/MainActivity.java
         * Both are accepted; a top-level "sources/" prefix is stripped so the
         * tree root shows the package directories directly.
         */
        post("/jadx") {
            val label = call.request.queryParameters["label"]?.takeIf { it.isNotBlank() }
                ?: "JADX import"
            val pkg = call.request.queryParameters["pkg"]
            val workDir = workDirFor(pkg)
            var staged: File? = null
            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                if (part is PartData.FileItem && staged == null) {
                    val f = File(workDir, "jadx_upload_${System.nanoTime()}.zip")
                    part.streamProvider().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    staged = f
                }
                part.dispose()
            }
            val zip = staged
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "No ZIP file in upload"))

            val job = DecompileJob.createJadx(label, workDir)
            DecompileJob.register(job)
            job.status    = JobStatus.RUNNING
            job.startedAt = System.currentTimeMillis()
            job.outputDir.mkdirs()
            job.persist()

            try {
                withContext(Dispatchers.IO) { extractJadxZip(zip, job.outputDir) }
                job.status    = JobStatus.DONE
                job.progress  = 100
                job.message   = "Imported from JADX ZIP."
                job.finishedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                job.status  = JobStatus.ERROR
                job.message = e.message ?: "Extraction failed"
            } finally {
                zip.delete()
                job.persist()
            }
            call.respond(HttpStatusCode.Created, job.toSummary())
        }

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
                // Build tree from outputDir — children paths are relative to outputDir
                // so they can be passed directly to the /file endpoint as ?path=
                val tree = buildTree(job.outputDir, job.outputDir)
                call.respond(tree)
            }

            get("/file") {
                val job = jobOrNotFound(call, call.parameters["id"]) ?: return@get
                if (job.status != JobStatus.DONE) return@get call.respond(
                    HttpStatusCode.Conflict, mapOf("error" to "Job not complete (status=${job.status})")
                )
                val rel = call.request.queryParameters["path"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required"))
                val target = jailcheck(job.outputDir, rel)
                    ?: return@get call.respond(HttpStatusCode.Forbidden,
                        mapOf("error" to "path outside output — outputDir=${job.outputDir.absolutePath}, rel=$rel"))
                if (!target.exists()) return@get call.respond(HttpStatusCode.NotFound,
                    mapOf("error" to "file not found: ${target.absolutePath}"))
                if (!target.isFile) return@get call.respond(HttpStatusCode.NotFound,
                    mapOf("error" to "not a file: ${target.absolutePath}"))
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
                val target = jailcheck(job.outputDir, rel) ?: return@get call.respond(HttpStatusCode.Forbidden)
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
                val zipName = "${job.label}_smali.zip"
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

private suspend fun jobOrNotFound(call: ApplicationCall, id: String?): DecompileJob? {
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
    val source:    String = "baksmali",   // "baksmali" | "jadx"
) {
    var status:     JobStatus = JobStatus.PENDING; internal set
    var progress:   Int       = 0;                 internal set
    var message:    String    = "Waiting…";        internal set
    var startedAt:  Long      = 0L;                internal set
    var finishedAt: Long      = 0L;                internal set

    @Volatile private var cancelled = false

    private val metaFile: File get() = File(outputDir.parent, "$id.meta")

    internal fun persist() {
        runCatching {
            metaFile.writeText(listOf(id, label, apkPath, outputDir.absolutePath,
                status.name, progress, startedAt, finishedAt, source, message).joinToString("\t"))
        }
    }

    fun start() {
        status    = JobStatus.RUNNING
        startedAt = System.currentTimeMillis()
        outputDir.mkdirs()
        persist()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { disassemble() }
    }

    /**
     * Disassembles the APK to Smali using baksmali.
     *
     * Memory profile: baksmali reads one DEX class at a time, writes the
     * .smali file, then moves on. Peak heap is ~20-40 MB regardless of APK
     * size — orders of magnitude less than jadx's AST approach.
     *
     * For split APKs we extract all .dex files from the APK zip and
     * disassemble each one into a numbered subdirectory (classes/, classes2/,
     * classes3/, …) matching the on-disk layout Android uses.
     */
    private fun disassemble() {
        try {
            message = "Opening APK…"
            persist()

            // Extract all .dex entries from the APK (base.apk is just a zip).
            val dexFiles = mutableListOf<Pair<String, File>>() // (entryName, tempFile)
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries().toList()
                    .filter { it.name.endsWith(".dex") }
                    .sortedBy { it.name }
                for (entry in entries) {
                    val tmp = File(outputDir.parent, "${id}_${entry.name.replace('/', '_')}")
                    zip.getInputStream(entry).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    dexFiles += entry.name to tmp
                }
            }

            if (dexFiles.isEmpty()) {
                status  = JobStatus.ERROR
                message = "No .dex files found in APK"
                persist()
                return
            }

            val total = dexFiles.size
            message  = "Disassembling $total dex file(s)…"
            progress = 5
            persist()

            for ((idx, pair) in dexFiles.withIndex()) {
                if (cancelled) return
                val (entryName, tmpDex) = pair

                // Output subdir named after the dex entry: classes/ classes2/ etc.
                val subDirName = entryName.removeSuffix(".dex").replace('/', '_')
                val outSubDir  = File(outputDir, subDirName).also { it.mkdirs() }

                message  = "Disassembling $entryName…"
                progress = 5 + (idx.toFloat() / total * 90).toInt()
                persist()

                try {
                    val dex  = DexFileFactory.loadDexFile(tmpDex, Opcodes.getDefault())
                    val opts = BaksmaliOptions()
                    Baksmali.disassembleDexFile(dex, outSubDir, 1, opts)
                } finally {
                    tmpDex.delete()
                }
            }

            if (!cancelled) {
                status   = JobStatus.DONE
                progress = 100
                message  = "Disassembly complete"
                persist()
            }
        } catch (e: Exception) {
            if (!cancelled) {
                status  = JobStatus.ERROR
                message = e.message ?: e::class.java.simpleName
                persist()
            }
        } finally {
            finishedAt = System.currentTimeMillis()
            persist()
        }
    }

    fun cancel() {
        cancelled  = true
        status     = JobStatus.CANCELLED
        finishedAt = System.currentTimeMillis()
        persist()
        outputDir.deleteRecursively()
        metaFile.delete()
    }

    fun toSummary() = JobSummary(id, label, status.name, progress, apkPath, source)
    fun toDetail()  = JobDetail(id, label, status.name, progress, message, apkPath,
                                outputDir.absolutePath, startedAt, finishedAt, source)

    companion object {
        private val registry = ConcurrentHashMap<String, DecompileJob>()

        fun create(label: String, apkPath: String, workDir: File): DecompileJob {
            val id = UUID.randomUUID().toString()
            return DecompileJob(id, label, apkPath, File(workDir, id), "baksmali")
        }

        fun createJadx(label: String, workDir: File): DecompileJob {
            val id = UUID.randomUUID().toString()
            return DecompileJob(id, label, "", File(workDir, id), "jadx")
        }

        fun register(job: DecompileJob) { registry[job.id] = job }
        fun find(id: String): DecompileJob? = registry[id]
        fun all(): List<DecompileJob> = registry.values.sortedByDescending { it.startedAt }
        fun remove(id: String) { registry.remove(id) }

        fun loadFromDisk(workDir: File) {
            workDir.listFiles { f -> f.extension == "meta" }?.forEach { meta ->
                runCatching {
                    val p = meta.readText().split("\t")
                    if (p.size < 9) return@runCatching
                    // Old format: 9 fields (…finishedAt, message)
                    // New format: 10 fields (…finishedAt, source, message)
                    val hasSource = p.size >= 10
                    val src     = if (hasSource) p[8] else "baksmali"
                    val msg     = if (hasSource) p.drop(9).joinToString("\t") else p.drop(8).joinToString("\t")
                    val job = DecompileJob(p[0], p[1], p[2], File(p[3]), src).also {
                        it.status     = runCatching { JobStatus.valueOf(p[4]) }.getOrDefault(JobStatus.ERROR)
                        it.progress   = p[5].toIntOrNull() ?: 0
                        it.startedAt  = p[6].toLongOrNull() ?: 0L
                        it.finishedAt = p[7].toLongOrNull() ?: 0L
                        it.message    = msg
                    }
                    if (job.status == JobStatus.RUNNING || job.status == JobStatus.PENDING) {
                        job.status  = JobStatus.ERROR
                        job.message = "Process was killed mid-disassembly. Please retry."
                        job.persist()
                    }
                    registry[p[0]] = job
                }
            }
        }
    }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

private fun jailcheck(root: File, rel: String): File? {
    val resolved = File(root, rel).canonicalFile
    return if (resolved.absolutePath.startsWith(root.canonicalFile.absolutePath + File.separator) ||
               resolved == root.canonicalFile) resolved else null
}

private fun buildTree(node: File, base: File): TreeNode {
    // relativeTo returns "." for the root itself — normalize to "".
    val relPath = node.relativeTo(base).path.let { if (it == ".") "" else it }
    if (node.isFile) return TreeNode(node.name, relPath,
        "file", node.length(), languageFor(node.name), null)
    val children = (node.listFiles() ?: emptyArray())
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .map { buildTree(it, base) }
    return TreeNode(if (node == base) base.name else node.name,
        relPath, "dir", null, null, children)
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

/**
 * Extract a JADX output ZIP into [dest], stripping a top-level "sources/"
 * prefix that JADX adds by default, so the tree root is the package dir.
 * Skips directory entries and path-traversal attempts.
 */
private fun extractJadxZip(zip: File, dest: File) {
    ZipFile(zip).use { zf ->
        zf.entries().asSequence().forEach { entry ->
            if (entry.isDirectory) return@forEach
            // Strip leading "sources/" prefix if present.
            val rel = entry.name
                .trimStart('/')
                .removePrefix("sources/")
                .trimStart('/')
            // Skip path-traversal attempts.
            if (rel.contains("..")) return@forEach
            val out = File(dest, rel)
            out.parentFile?.mkdirs()
            zf.getInputStream(entry).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        }
    }
}

private fun grepDir(dir: File, pattern: String, ext: String?,
                    ignoreCase: Boolean, limit: Int): List<SearchHit> {
    val cmd = mutableListOf("grep", "-rnE")
    if (ignoreCase) cmd.add("-i")
    if (!ext.isNullOrBlank()) cmd.add("--include=*.$ext")
    cmd.add(pattern); cmd.add(dir.absolutePath)
    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val hits = mutableListOf<SearchHit>()
    proc.inputStream.bufferedReader().useLines { lines ->
        for (line in lines) {
            if (hits.size >= limit) break
            val c1 = line.indexOf(':'); if (c1 < 0) continue
            val c2 = line.indexOf(':', c1 + 1); if (c2 < 0) continue
            hits += SearchHit(
                line.substring(0, c1).removePrefix(dir.absolutePath).trimStart('/'),
                line.substring(c1 + 1, c2).toIntOrNull() ?: 0,
                line.substring(c2 + 1)
            )
        }
    }
    proc.waitFor()
    return hits
}

private val LANG_MAP = mapOf(
    "smali" to "smali", "java" to "java", "kt" to "kotlin",
    "xml"   to "xml",   "json" to "json", "txt" to "plaintext",
    "md"    to "markdown"
)
private fun languageFor(name: String) =
    LANG_MAP[name.substringAfterLast('.', "").lowercase()] ?: "plaintext"

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable private data class StartJobRequest(val pkg: String? = null, val apkPath: String? = null)
@Serializable data class JobSummary(val id: String, val label: String, val status: String, val progress: Int, val apkPath: String, val source: String = "baksmali")
@Serializable data class JobDetail(val id: String, val label: String, val status: String, val progress: Int, val message: String, val apkPath: String, val outputDir: String, val startedAt: Long, val finishedAt: Long, val source: String = "baksmali")
@Serializable data class TreeNode(val name: String, val path: String, val type: String, val size: Long?, val language: String?, val children: List<TreeNode>?)
@Serializable private data class FileContent(val path: String, val content: String, val language: String, val size: Long)
@Serializable private data class SearchHit(val path: String, val line: Int, val text: String)
@Serializable private data class SearchResult(val query: String, val total: Int, val hits: List<SearchHit>)
