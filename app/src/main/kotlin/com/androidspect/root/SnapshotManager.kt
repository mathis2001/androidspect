package com.androidspect.root

import android.content.Context
import android.content.pm.PackageManager
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Captures point-in-time "snapshots" of an app and diffs them to track what an
 * update changed across:
 *   • metadata     — versionName/Code, min/target SDK, signing fingerprint
 *   • manifest     — full decoded AndroidManifest.xml
 *   • permissions  — requested permissions
 *   • components   — activities/services/receivers/providers + intent-filters,
 *                    exported flags
 *   • code         — per-class fingerprint (class name → method count) so we can
 *                    tell which classes were added/removed/changed without
 *                    storing the whole DEX
 *   • dataDir      — file tree of /data/data/<pkg> (path → size + sha256),
 *                    only when captured from the live install (not from an APK)
 *
 * Snapshots are JSON files under filesDir/snapshots/<pkg>/<id>.json.
 */
class SnapshotManager(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    private val root: File get() = File(context.filesDir, "snapshots").also { it.mkdirs() }
    private fun pkgDir(pkg: String): File =
        File(root, Sanitize.pkg(pkg)).also { it.mkdirs() }

    // ── Capture ────────────────────────────────────────────────────────────────

    /** Snapshot the currently-installed package (includes data dir). */
    suspend fun captureInstalled(
        pkg: String,
        manifest: ManifestDump?,
        components: ComponentReport,
        includeDataDir: Boolean = true
    ): Snapshot = withContext(Dispatchers.IO) {
        val pi = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        }.getOrNull()
        val ai = pi?.applicationInfo

        val apkPath = ai?.sourceDir
        val codeFp = apkPath?.let { codeFingerprint(it) } ?: emptyList()

        val dataTree = if (includeDataDir)
            runCatching { dataDirTree("/data/data/$pkg") }.getOrDefault(emptyList())
        else emptyList()

        val snap = Snapshot(
            id = System.currentTimeMillis().toString(),
            packageName = pkg,
            source = "installed",
            createdAt = System.currentTimeMillis(),
            versionName = pi?.versionName ?: "",
            versionCode = pi?.longVersionCode ?: 0L,
            minSdk = ai?.minSdkVersion ?: 0,
            targetSdk = ai?.targetSdkVersion ?: 0,
            allowBackup = ((ai?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0,
            cleartextTraffic = ((ai?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0,
            signingSha256 = signingSha256(pkg),
            permissions = pi?.requestedPermissions?.toList()?.sorted() ?: emptyList(),
            manifestXml = manifest?.xml ?: "",
            components = flattenComponents(components),
            codeClasses = codeFp,
            dataFiles = dataTree,
            deeplinks = extractDeeplinks(components),
            nativeLibs = apkPath?.let { nativeLibList(it) } ?: emptyList()
        )
        persist(snap)
        snap
    }

    /** Snapshot an APK file on disk (no data dir available). */
    suspend fun captureApk(
        apkPath: String,
        manifest: ManifestDump?,
        components: ComponentReport,
        labelPkg: String
    ): Snapshot = withContext(Dispatchers.IO) {
        val pi = runCatching {
            pm.getPackageArchiveInfo(apkPath, PackageManager.GET_PERMISSIONS)
        }.getOrNull()

        val snap = Snapshot(
            id = System.currentTimeMillis().toString(),
            packageName = labelPkg,
            source = "apk:$apkPath",
            createdAt = System.currentTimeMillis(),
            versionName = pi?.versionName ?: "",
            versionCode = pi?.longVersionCode ?: 0L,
            minSdk = pi?.applicationInfo?.minSdkVersion ?: 0,
            targetSdk = pi?.applicationInfo?.targetSdkVersion ?: 0,
            allowBackup = ((pi?.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0,
            cleartextTraffic = ((pi?.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0,
            signingSha256 = null,
            permissions = pi?.requestedPermissions?.toList()?.sorted() ?: emptyList(),
            manifestXml = manifest?.xml ?: "",
            components = flattenComponents(components),
            codeClasses = codeFingerprint(apkPath),
            dataFiles = emptyList(),
            deeplinks = extractDeeplinks(components),
            nativeLibs = nativeLibList(apkPath)
        )
        persist(snap)
        snap
    }

    // ── Storage ────────────────────────────────────────────────────────────────

    private fun persist(s: Snapshot) {
        File(pkgDir(s.packageName), "${s.id}.json").writeText(json.encodeToString(Snapshot.serializer(), s))
    }

    fun list(pkg: String): List<SnapshotMeta> =
        pkgDir(pkg).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching {
                    val s = json.decodeFromString(Snapshot.serializer(), f.readText())
                    SnapshotMeta(s.id, s.packageName, s.source, s.createdAt,
                        s.versionName, s.versionCode, s.components.size, s.codeClasses.size)
                }.getOrNull()
            }
            ?.sortedByDescending { it.createdAt } ?: emptyList()

    fun listAll(): List<SnapshotMeta> =
        root.listFiles()?.filter { it.isDirectory }?.flatMap { dir ->
            list(dir.name.let { decodePkgDir(it) })
        }?.sortedByDescending { it.createdAt } ?: emptyList()

    private fun decodePkgDir(name: String) = name  // Sanitize.pkg is reversible enough for our keys

    fun load(pkg: String, id: String): Snapshot? =
        runCatching {
            json.decodeFromString(Snapshot.serializer(),
                File(pkgDir(pkg), "$id.json").readText())
        }.getOrNull()

    fun delete(pkg: String, id: String): Boolean =
        File(pkgDir(pkg), "$id.json").delete()

    // ── Diff ─────────────────────────────────────────────────────────────────

    fun diff(a: Snapshot, b: Snapshot): SnapshotDiff {
        // Permissions
        val aPerms = a.permissions.toSet()
        val bPerms = b.permissions.toSet()
        val permsAdded = (bPerms - aPerms).sorted()
        val permsRemoved = (aPerms - bPerms).sorted()

        // Components keyed by "type:name"
        val aComp = a.components.associateBy { "${it.type}:${it.name}" }
        val bComp = b.components.associateBy { "${it.type}:${it.name}" }
        val compAdded = (bComp.keys - aComp.keys).map { bComp.getValue(it) }
        val compRemoved = (aComp.keys - bComp.keys).map { aComp.getValue(it) }
        val compChanged = (aComp.keys intersect bComp.keys).mapNotNull { k ->
            val x = aComp.getValue(k); val y = bComp.getValue(k)
            val changes = mutableListOf<String>()
            if (x.exported != y.exported)
                changes.add("exported ${x.exported} → ${y.exported}")
            if (x.filtersDigest != y.filtersDigest)
                changes.add("intent-filters changed")
            if (changes.isEmpty()) null
            else ComponentChange(y.type, y.name, changes)
        }

        // Code classes keyed by class name → method count
        val aCls = a.codeClasses.associate { it.name to it.methods }
        val bCls = b.codeClasses.associate { it.name to it.methods }
        val clsAdded = (bCls.keys - aCls.keys).sorted()
        val clsRemoved = (aCls.keys - bCls.keys).sorted()
        val clsChanged = (aCls.keys intersect bCls.keys)
            .filter { aCls[it] != bCls[it] }
            .map { ClassMethodChange(it, aCls.getValue(it), bCls.getValue(it)) }
            .sortedBy { it.name }

        // Data dir keyed by path → sha
        val aData = a.dataFiles.associate { it.path to it.sha256 }
        val bData = b.dataFiles.associate { it.path to it.sha256 }
        val dataAdded = (bData.keys - aData.keys).sorted()
        val dataRemoved = (aData.keys - bData.keys).sorted()
        val dataChanged = (aData.keys intersect bData.keys)
            .filter { aData[it] != bData[it] }
            .sorted()

        // Deeplinks (simple set diff)
        val aDl = a.deeplinks.toSet()
        val bDl = b.deeplinks.toSet()
        val dlAdded = (bDl - aDl).sorted()
        val dlRemoved = (aDl - bDl).sorted()

        // Native libs keyed by path → sha
        val aLib = a.nativeLibs.associate { it.path to it.sha256 }
        val bLib = b.nativeLibs.associate { it.path to it.sha256 }
        val libAdded = (bLib.keys - aLib.keys).sorted()
        val libRemoved = (aLib.keys - bLib.keys).sorted()
        val libChanged = (aLib.keys intersect bLib.keys)
            .filter { aLib[it] != bLib[it] }
            .sorted()

        // Manifest security-attribute changes, with directional/state descriptions.
        val manifestChanges = mutableListOf<ManifestChange>()
        if (a.minSdk != b.minSdk) {
            val dir = if (b.minSdk > a.minSdk) "raised" else "lowered"
            manifestChanges.add(ManifestChange(
                attribute = "minSdkVersion",
                from = a.minSdk.toString(),
                to = b.minSdk.toString(),
                // Lowering minSdk widens the attack surface to older, weaker
                // Android versions — flag it as the riskier direction.
                severity = if (b.minSdk < a.minSdk) "warn" else "info",
                note = "$dir from ${a.minSdk} to ${b.minSdk}"
            ))
        }
        if (a.targetSdk != b.targetSdk) {
            val dir = if (b.targetSdk > a.targetSdk) "raised" else "lowered"
            manifestChanges.add(ManifestChange(
                attribute = "targetSdkVersion",
                from = a.targetSdk.toString(),
                to = b.targetSdk.toString(),
                severity = if (b.targetSdk < a.targetSdk) "warn" else "info",
                note = "$dir from ${a.targetSdk} to ${b.targetSdk}"
            ))
        }
        if (a.allowBackup != b.allowBackup) {
            manifestChanges.add(ManifestChange(
                attribute = "allowBackup",
                from = a.allowBackup.toString(),
                to = b.allowBackup.toString(),
                // Enabling backup is the riskier direction (data extractable via adb).
                severity = if (b.allowBackup) "warn" else "good",
                note = if (b.allowBackup) "backup ENABLED — app data may be extractable via adb backup"
                       else "backup disabled"
            ))
        }
        if (a.cleartextTraffic != b.cleartextTraffic) {
            manifestChanges.add(ManifestChange(
                attribute = "usesCleartextTraffic",
                from = a.cleartextTraffic.toString(),
                to = b.cleartextTraffic.toString(),
                // Enabling cleartext is the riskier direction (HTTP allowed).
                severity = if (b.cleartextTraffic) "warn" else "good",
                note = if (b.cleartextTraffic) "cleartext ENABLED — plaintext HTTP now permitted"
                       else "cleartext disabled"
            ))
        }

        return SnapshotDiff(
            packageName = b.packageName,
            from = SnapshotMeta(a.id, a.packageName, a.source, a.createdAt, a.versionName, a.versionCode, a.components.size, a.codeClasses.size),
            to   = SnapshotMeta(b.id, b.packageName, b.source, b.createdAt, b.versionName, b.versionCode, b.components.size, b.codeClasses.size),
            versionChanged = a.versionName != b.versionName || a.versionCode != b.versionCode,
            minSdkChange = if (a.minSdk != b.minSdk) "${a.minSdk} → ${b.minSdk}" else null,
            targetSdkChange = if (a.targetSdk != b.targetSdk) "${a.targetSdk} → ${b.targetSdk}" else null,
            signingChanged = a.signingSha256 != null && b.signingSha256 != null && a.signingSha256 != b.signingSha256,
            manifestChanged = a.manifestXml != b.manifestXml,
            permsAdded = permsAdded,
            permsRemoved = permsRemoved,
            componentsAdded = compAdded,
            componentsRemoved = compRemoved,
            componentsChanged = compChanged,
            classesAdded = clsAdded,
            classesRemoved = clsRemoved,
            classesChanged = clsChanged,
            dataAdded = dataAdded,
            dataRemoved = dataRemoved,
            dataChanged = dataChanged,
            deeplinksAdded = dlAdded,
            deeplinksRemoved = dlRemoved,
            nativeAdded = libAdded,
            nativeRemoved = libRemoved,
            nativeChanged = libChanged,
            manifestSecurityChanges = manifestChanges
        )
    }

    // ── Builders ───────────────────────────────────────────────────────────────

    private fun flattenComponents(r: ComponentReport): List<SnapComponent> {
        fun map(list: List<Component>, type: String) = list.map { c ->
            // A stable digest of the intent-filter set so we can detect changes
            // without storing the whole structure.
            val fdigest = sha256(c.filters.joinToString("|") { f ->
                f.actions.sorted().joinToString(",") + "#" +
                f.categories.sorted().joinToString(",") + "#" +
                f.data.joinToString(",") { "${it.scheme}://${it.host}${it.path}" } + "#" +
                f.autoVerify
            })
            SnapComponent(type, c.name, c.exported, fdigest)
        }
        return map(r.activities, "activity") + map(r.services, "service") +
               map(r.receivers, "receiver") + map(r.providers, "provider")
    }

    /** All deeplink URIs (scheme://host/path) declared across VIEW+BROWSABLE filters. */
    private fun extractDeeplinks(r: ComponentReport): List<String> {
        val out = LinkedHashSet<String>()
        val all = r.activities + r.services + r.receivers + r.providers
        for (c in all) {
            for (f in c.filters) {
                val isViewBrowsable = f.actions.contains("android.intent.action.VIEW") &&
                    f.categories.contains("android.intent.category.BROWSABLE")
                if (!isViewBrowsable) continue
                for (d in f.data) {
                    val scheme = d.scheme ?: continue
                    val host = d.host ?: ""
                    val path = d.path ?: d.pathPrefix ?: d.pathPattern ?: ""
                    out.add("$scheme://$host$path")
                }
            }
        }
        return out.sorted()
    }

    /** Native libraries inside the APK's lib/<abi>/ entries: path → size + sha256. */
    private fun nativeLibList(apkPath: String): List<SnapFile> {
        val out = ArrayList<SnapFile>()
        runCatching {
            ZipFile(apkPath).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .forEach { e ->
                        val sha = runCatching {
                            zip.getInputStream(e).use { sha256Stream(it) }
                        }.getOrDefault("")
                        out.add(SnapFile(e.name, e.size, sha))
                    }
            }
        }
        return out.sortedBy { it.path }
    }

    private fun sha256Stream(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Per-class fingerprint: class name → number of methods. */
    private fun codeFingerprint(apkPath: String): List<SnapClass> {
        val out = ArrayList<SnapClass>()
        runCatching {
            ZipFile(apkPath).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".dex") }.forEach { entry ->
                    val tmp = File.createTempFile("snap_", ".dex", context.cacheDir)
                    try {
                        zip.getInputStream(entry).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        val dex = DexFileFactory.loadDexFile(tmp, Opcodes.getDefault())
                        for (cls in dex.classes) {
                            val name = cls.type.toString()
                                .removePrefix("L").removeSuffix(";").replace('/', '.')
                            val methodCount = cls.methods.count()
                            out.add(SnapClass(name, methodCount))
                        }
                    } catch (_: Exception) {
                    } finally { tmp.delete() }
                }
            }
        }
        // Dedup by name (multidex can split a class? rare — keep first)
        return out.distinctBy { it.name }.sortedBy { it.name }
    }

    /** File tree of a data dir: path → size + content sha256 (capped per file). */
    private suspend fun dataDirTree(base: String, maxHashBytes: Long = 2L * 1024 * 1024): List<SnapFile> {
        val out = ArrayList<SnapFile>()
        suspend fun walk(dir: String) {
            for (e in RootBridge.listDir(dir)) {
                if (e.isDir) walk(e.path)
                else {
                    val sha = runCatching {
                        sha256(RootBridge.readBytes(e.path, maxHashBytes))
                    }.getOrDefault("")
                    out.add(SnapFile(e.path.removePrefix(base).ifEmpty { e.path }, e.size, sha))
                }
            }
        }
        walk(base)
        return out.sortedBy { it.path }
    }

    private fun signingSha256(pkg: String): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    private fun sha256(s: String) = sha256(s.toByteArray(Charsets.UTF_8))
    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

// ── Serializable models ────────────────────────────────────────────────────────

@Serializable
data class Snapshot(
    val id: String,
    val packageName: String,
    val source: String,
    val createdAt: Long,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val allowBackup: Boolean = false,
    val cleartextTraffic: Boolean = false,
    val signingSha256: String?,
    val permissions: List<String>,
    val manifestXml: String,
    val components: List<SnapComponent>,
    val codeClasses: List<SnapClass>,
    val dataFiles: List<SnapFile>,
    val deeplinks: List<String> = emptyList(),
    val nativeLibs: List<SnapFile> = emptyList()
)

@Serializable
data class SnapComponent(
    val type: String,
    val name: String,
    val exported: Boolean,
    val filtersDigest: String
)

@Serializable data class SnapClass(val name: String, val methods: Int)
@Serializable data class SnapFile(val path: String, val size: Long, val sha256: String)

@Serializable
data class SnapshotMeta(
    val id: String,
    val packageName: String,
    val source: String,
    val createdAt: Long,
    val versionName: String,
    val versionCode: Long,
    val componentCount: Int,
    val classCount: Int
)

@Serializable
data class ComponentChange(val type: String, val name: String, val changes: List<String>)

@Serializable
data class ClassMethodChange(val name: String, val before: Int, val after: Int)

@Serializable
data class SnapshotDiff(
    val packageName: String,
    val from: SnapshotMeta,
    val to: SnapshotMeta,
    val versionChanged: Boolean,
    val minSdkChange: String?,
    val targetSdkChange: String?,
    val signingChanged: Boolean,
    val manifestChanged: Boolean,
    val permsAdded: List<String>,
    val permsRemoved: List<String>,
    val componentsAdded: List<SnapComponent>,
    val componentsRemoved: List<SnapComponent>,
    val componentsChanged: List<ComponentChange>,
    val classesAdded: List<String>,
    val classesRemoved: List<String>,
    val classesChanged: List<ClassMethodChange>,
    val dataAdded: List<String>,
    val dataRemoved: List<String>,
    val dataChanged: List<String>,
    val deeplinksAdded: List<String> = emptyList(),
    val deeplinksRemoved: List<String> = emptyList(),
    val nativeAdded: List<String> = emptyList(),
    val nativeRemoved: List<String> = emptyList(),
    val nativeChanged: List<String> = emptyList(),
    val manifestSecurityChanges: List<ManifestChange> = emptyList()
)

@Serializable
data class ManifestChange(
    val attribute: String,
    val from: String,
    val to: String,
    val severity: String,   // good | info | warn
    val note: String
)
