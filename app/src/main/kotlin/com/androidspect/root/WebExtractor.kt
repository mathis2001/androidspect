package com.androidspect.root

import android.content.Context
import android.content.pm.PackageManager
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.zip.ZipFile

/**
 * Extracts the web attack surface from across the whole APK, tagging each
 * finding with its source:
 *   • dex          — string constants & query-builder calls in compiled code
 *   • assets       — text files under assets/ (.json/.xml/.txt/.properties…)
 *   • res/raw      — verbatim files under res/raw/
 *   • resources    — strings resolved from resources.arsc / compiled res XML
 *   • net-config   — <domain> entries in the network security config
 *
 * Captures literal values (the common case); not values assembled dynamically.
 */
class WebExtractor(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    // Accumulators shared across all source scanners. URLs and endpoints track
    // the set of sources each was seen in, so the same value found in code AND
    // a config file is tagged with both.
    private class Acc {
        val urls = HashMap<String, MutableSet<String>>()
        val endpoints = HashMap<String, EndpointAcc>()
        val params = sortedSetOf<String>()
        // WebView findings keyed by "ClassName#method" -> WebViewFinding
        val webviews = LinkedHashMap<String, WebViewFinding>()
        fun addUrl(u: String, src: String) { urls.getOrPut(u) { sortedSetOf() }.add(src) }
        fun addEndpoint(path: String, src: String, qs: List<String>) {
            val e = endpoints.getOrPut(path) { EndpointAcc() }
            e.sources.add(src); e.params.addAll(qs); params.addAll(qs)
        }
        fun addParam(p: String) { params.add(p) }
        fun addWebView(cls: String, method: String, arg: String?, definingClass: String) {
            val key = "$cls#$method"
            webviews.putIfAbsent(key, WebViewFinding(
                callerClass = cls,
                method      = method,
                argument    = arg,
                definingClass = definingClass,
                severity    = webViewSeverity(method, arg)
            ))
        }
    }
    private class EndpointAcc { val params = sortedSetOf<String>(); val sources = sortedSetOf<String>() }

    suspend fun extract(packageName: String): WebReport = withContext(Dispatchers.IO) {
        val ai = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return@withContext WebReport(packageName, emptyList(), emptyList(), emptyList(), emptyList())
        val apkPath = ai.sourceDir ?: return@withContext WebReport(packageName, emptyList(), emptyList(), emptyList(), emptyList())

        val acc = Acc()

        ZipFile(apkPath).use { zip ->
            val entries = zip.entries().toList()
            for (entry in entries) {
                if (entry.isDirectory) continue
                val name = entry.name
                when {
                    name.endsWith(".dex") -> {
                        val tmp = File.createTempFile("web_", ".dex", context.cacheDir)
                        try {
                            zip.getInputStream(entry).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                            val dex = DexFileFactory.loadDexFile(tmp, Opcodes.getDefault())
                            scanDex(dex, acc)
                        } catch (_: Exception) {} finally { tmp.delete() }
                    }
                    // Plain-text resource & asset files we can read verbatim.
                    name.startsWith("assets/") && isTextAsset(name) -> {
                        val src = "assets"
                        readZipText(zip, entry)?.let { scanText(it, acc, src) }
                    }
                    name.startsWith("res/raw/") && isTextAsset(name) -> {
                        readZipText(zip, entry)?.let { scanText(it, acc, "res/raw") }
                    }
                }
            }
        }

        // resources.arsc string values (base URLs in strings.xml, etc.)
        runCatching { scanResources(apkPath, acc) }
        // network_security_config domains (resolved via the manifest)
        runCatching { scanNetworkSecurityConfig(packageName, apkPath, acc) }

        // WebView security analysis
        val webviewAcc = LinkedHashMap<String, WebViewFinding>()
        ZipFile(apkPath).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".dex") }.forEach { entry ->
                val tmp = File.createTempFile("wv_", ".dex", context.cacheDir)
                try {
                    zip.getInputStream(entry).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    val dex = DexFileFactory.loadDexFile(tmp, Opcodes.getDefault())
                    scanWebViews(dex, webviewAcc)
                } catch (_: Exception) {} finally { tmp.delete() }
            }
        }

        WebReport(
            packageName = packageName,
            urls = acc.urls.entries
                .map { WebUrl(it.key, it.value.toList()) }
                .sortedBy { it.url },
            endpoints = acc.endpoints.entries
                .map { Endpoint(it.key, it.value.params.toList(), it.value.sources.toList()) }
                .sortedBy { it.path },
            params = acc.params.toList(),
            webviews = webviewAcc.values
                .sortedWith(compareBy({ severityOrder(it.severity) }, { it.callerClass }))
        )
    }

    /** Single pass over all methods: extracts URL/endpoint literals AND builder params. */
    private fun scanDex(dex: com.android.tools.smali.dexlib2.iface.DexFile, acc: Acc) {
        for (cls in dex.classes) {
            for (m in cls.methods) {
                val impl = m.implementation ?: continue
                var last: String? = null
                for (insn in impl.instructions) {
                    if (insn is Instruction21c) {
                        val r = insn.reference
                        if (r is StringReference) {
                            val s = r.string.toString()
                            last = s
                            harvest(s, acc, "dex")
                        }
                        continue
                    }
                    if (insn is ReferenceInstruction) {
                        val r = insn.reference
                        if (r is MethodReference) {
                            when (r.name.toString()) {
                                "appendQueryParameter",
                                "addQueryParameter",
                                "addEncodedQueryParameter" -> {
                                    last?.let { if (it.isNotBlank() && it.length < 64) acc.addParam(it) }
                                    last = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Pulls URLs/endpoints/params out of an arbitrary string and records them
     * against [source]. Used for a single DEX const-string and, via [scanText],
     * for whole resource/asset files.
     */
    private fun harvest(t0: String, acc: Acc, source: String) {
        val t = t0.trim()
        if (t.isEmpty() || t.length > 4000) return
        if (t.startsWith("http://") || t.startsWith("https://")) {
            val afterScheme = t.substringAfter("://", "")
            val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
            if (!isRealDomain(host)) return
            acc.addUrl(t, source)
            val pathPart = "/" + afterScheme.substringAfter('/', "")
            val (path, query) = splitPathQuery(pathPart)
            if (looksLikeApiPath(path) && !isBoilerplateHost(host)) {
                acc.addEndpoint(path, source, queryKeys(query))
            }
        } else if (t.startsWith("/") && looksLikeApiPath(t.substringBefore('?'))) {
            val (path, query) = splitPathQuery(t)
            acc.addEndpoint(path, source, queryKeys(query))
        }
    }

    // URL regex for free-text files (JSON/XML/properties), which pack many URLs
    // into one blob rather than one-per-string like the DEX pool.
    private val urlRegex = Regex("""https?://[^\s"'<>(){}\[\]\\^` ]+""")

    /** Scans an arbitrary text blob (asset/raw file) for URLs and endpoints. */
    private fun scanText(text: String, acc: Acc, source: String) {
        if (text.length > 5_000_000) return  // skip huge files
        for (m in urlRegex.findAll(text)) {
            // Trim common trailing punctuation captured from JSON/XML.
            val url = m.value.trimEnd('"', '\'', ',', ';', ')', '>', '.')
            harvest(url, acc, source)
        }
        // Also catch bare "/api/..." path literals inside quotes in config files.
        for (m in Regex("""["'](/[A-Za-z0-9_\-./{}]{2,})["']""").findAll(text)) {
            val p = m.groupValues[1]
            if (looksLikeApiPath(p.substringBefore('?'))) harvest(p, acc, source)
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun splitPathQuery(s: String): Pair<String, String> {
        val q = s.indexOf('?')
        return if (q < 0) s to "" else s.substring(0, q) to s.substring(q + 1)
    }

    private fun queryKeys(query: String): List<String> =
        if (query.isBlank()) emptyList()
        else query.split('&').mapNotNull { kv ->
            kv.substringBefore('=').takeIf { it.isNotBlank() && it.length < 64 && !it.contains('%') }
        }

    private fun isTextAsset(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".txt") ||
               n.endsWith(".properties") || n.endsWith(".cfg") || n.endsWith(".conf") ||
               n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".html") ||
               n.endsWith(".js") || n.endsWith(".graphql") || n.endsWith(".env") ||
               n.endsWith(".csv") || n.endsWith(".ini")
    }

    private fun readZipText(zip: ZipFile, entry: java.util.zip.ZipEntry): String? =
        runCatching {
            if (entry.size > 8L * 1024 * 1024) return null   // cap at 8MB
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()

    /**
     * Recover URLs from string resources (res/values/strings.xml is compiled
     * into resources.arsc, not readable as text). Android's R ids for app
     * resources are 0x7fTTNNNN (TT = type, NNNN = entry). We probe the id space
     * and resolve any that yield a string, harvesting URL-looking values.
     *
     * This is a bounded brute-force: we can't enumerate resource names on-device
     * without the R class, so we walk ids and stop after a long run of misses.
     * It reliably catches the common "base_url" string-resource case.
     */
    private fun scanResources(apkPath: String, acc: Acc) {
        val assets = android.content.res.AssetManager::class.java.newInstance()
        val addAssetPath = android.content.res.AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assets, apkPath) as Int
        if (cookie == 0) return
        val res = android.content.res.Resources(
            assets, context.resources.displayMetrics, context.resources.configuration
        )
        // Probe each resource "type" block (0x7f00..0x7f1f typically) and the
        // first ~8k entries within it; bail out of a type quickly once it's dry.
        for (type in 0x01..0x1f) {
            var consecutiveMisses = 0
            var entry = 0
            while (entry < 0x2000 && consecutiveMisses < 256) {
                val id = 0x7f000000.toInt() or (type shl 16) or entry
                val s = runCatching { res.getString(id) }.getOrNull()
                if (s != null) {
                    consecutiveMisses = 0
                    if (s.length in 5..4000 && (s.startsWith("http://") || s.startsWith("https://"))) {
                        harvest(s, acc, "resources")
                    }
                } else consecutiveMisses++
                entry++
            }
        }
    }

    /**
     * Reads the network security config (referenced from the manifest's
     * android:networkSecurityConfig) and harvests its <domain> entries — a
     * curated list of the app's real hosts.
     */
    private fun scanNetworkSecurityConfig(pkg: String, apkPath: String, acc: Acc) {
        val assets = android.content.res.AssetManager::class.java.newInstance()
        val addAssetPath = android.content.res.AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assets, apkPath) as Int
        if (cookie == 0) return

        // The NSC is a compiled XML resource. Its resource id is referenced from
        // the manifest; we resolve the manifest attribute to find which res/xml
        // file to open, then parse <domain> elements.
        val nscPath = findNscResourcePath(assets, cookie) ?: return
        val parser = runCatching { assets.openXmlResourceParser(cookie, nscPath) }.getOrNull() ?: return
        parser.use { p ->
            var event = p.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "domain") {
                    // domain text is the next TEXT event
                    val text = runCatching { p.nextText() }.getOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        val host = text.removePrefix("*.").lowercase()
                        if (isRealDomain(host)) {
                            acc.addUrl("https://$host", "net-config")
                        }
                    }
                }
                event = runCatching { p.next() }.getOrNull() ?: break
            }
        }
    }

    /** Find the res/xml path of the network security config from the manifest. */
    private fun findNscResourcePath(assets: android.content.res.AssetManager, cookie: Int): String? {
        val parser = runCatching { assets.openXmlResourceParser(cookie, "AndroidManifest.xml") }.getOrNull() ?: return null
        val androidNs = "http://schemas.android.com/apk/res/android"
        parser.use { p ->
            var event = p.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "application") {
                    for (i in 0 until p.attributeCount) {
                        if (p.getAttributeName(i) == "networkSecurityConfig" &&
                            p.getAttributeNamespace(i) == androidNs) {
                            // Value is a resource ref; getAttributeValue returns
                            // the resolved res path like "res/xml/nsc.xml".
                            val v = p.getAttributeValue(i)
                            if (v != null && v.startsWith("res/")) return v
                        }
                    }
                }
                event = runCatching { p.next() }.getOrNull() ?: break
            }
        }
        return null
    }

    /**
     * A real domain has at least one dot, a valid TLD-like last label, and no
     * spaces/placeholder characters. Rejects bare hostnames, IPs are allowed.
     */
    private fun isRealDomain(host: String): Boolean {
        if (host.isBlank() || host.contains(' ') || host.contains('%')) return false
        // Allow IPv4 literals
        if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) return true
        if (!host.contains('.')) return false
        val labels = host.split('.')
        if (labels.any { it.isEmpty() }) return false
        val tld = labels.last()
        // TLD must be alphabetic and at least 2 chars (com, io, xyz…)
        if (tld.length < 2 || !tld.all { it.isLetter() }) return false
        // Each label: alphanumeric + hyphen only
        return labels.all { it.all { c -> c.isLetterOrDigit() || c == '-' } }
    }

    /**
     * Hosts that are XML namespaces, schema/spec references, or doc placeholders
     * — never a real API endpoint, so we drop them to cut noise.
     */
    private fun isBoilerplateHost(host: String): Boolean {
        if (host in BOILERPLATE_HOSTS) return true
        // Namespace/schema/spec subdomains under common roots.
        if (host.startsWith("schemas.")) return true   // schemas.android.com, schemas.microsoft.com…
        if (host.startsWith("ns.")) return true         // ns.adobe.com…
        if (host.startsWith("xmlns.")) return true
        if (host.endsWith(".w3.org")) return true
        // Example/placeholder domains (RFC 2606 + common docs).
        if (host == "example.com" || host == "example.org" || host == "example.net" ||
            host.endsWith(".example.com") || host.endsWith(".example")) return true
        return false
    }

    /**
     * Heuristic: a path that looks like an API route — at least one segment,
     * mostly url-safe characters, not a file/asset path or a format string.
     */
    private fun looksLikeApiPath(path: String): Boolean {
        if (!path.startsWith("/") || path.length < 2) return false
        if (path.contains(' ') || path.contains('\n')) return false
        // Skip obvious non-endpoints: resource/asset/file paths.
        val lower = path.lowercase()
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp") ||
            lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".css") ||
            lower.endsWith(".js")  || lower.endsWith(".html")|| lower.endsWith(".xml") ||
            lower.endsWith(".json")|| lower.endsWith(".ttf") || lower.endsWith(".woff")) return false
        if (path.startsWith("/system/") || path.startsWith("/data/") ||
            path.startsWith("/sdcard/") || path.startsWith("/proc/") ||
            path.startsWith("/dev/")) return false
        // Require at least one alphanumeric segment.
        return path.drop(1).split('/').any { seg -> seg.any { it.isLetterOrDigit() } }
    }

    /**
     * Scans DEX for calls to WebSettings/WebView sensitive methods.
     *
     * Uses instanceof checks (same pattern as the working scanDex) rather than
     * opcode name string comparisons which are fragile across dexlib2 versions.
     *
     * Instruction types:
     *   Instruction35c  — invoke-virtual / invoke-static with up to 5 regs
     *   Instruction3rc  — invoke-virtual/range / invoke-static/range
     *   Instruction11n / Instruction21s / Instruction31i — const loads
     */
    private fun scanWebViews(
        dex: com.android.tools.smali.dexlib2.iface.DexFile,
        out: LinkedHashMap<String, WebViewFinding>
    ) {
        val targets = setOf(
            "setJavaScriptEnabled",
            "setAllowFileAccess",
            "setAllowFileAccessFromFileURLs",
            "setAllowUniversalAccessFromFileURLs",
            "setAllowContentAccess",
            "setWebContentsDebuggingEnabled",
            "addJavascriptInterface"
        )
        val webClasses = setOf(
            "Landroid/webkit/WebSettings;",
            "Landroid/webkit/WebView;"
        )
        val noArgMethods = setOf("addJavascriptInterface")

        for (cls in dex.classes) {
            val callerClass = cls.type.toString()
                .removePrefix("L").removeSuffix(";").replace('/', '.')
            for (method in cls.methods) {
                val impl = method.implementation ?: continue

                // Register → last const value, plus a sliding lastConst fallback.
                val regs = HashMap<Int, Int>()
                var lastConst: Int? = null

                for (insn in impl.instructions) {
                    // ── Const loads (track bool/int values loaded into regs) ──
                    when (insn) {
                        is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction11n -> {
                            // const/4 vA, #+B  (narrow literal)
                            regs[insn.registerA] = insn.narrowLiteral
                            lastConst = insn.narrowLiteral
                            continue
                        }
                        is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21s -> {
                            // const/16 vAA, #+BBBB
                            regs[insn.registerA] = insn.narrowLiteral
                            lastConst = insn.narrowLiteral
                            continue
                        }
                        is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31i -> {
                            // const vAA, #+BBBBBBBB
                            regs[insn.registerA] = insn.narrowLiteral
                            lastConst = insn.narrowLiteral
                            continue
                        }
                    }

                    // ── Invoke instructions (both 35c and 3rc forms) ──────────
                    if (insn !is ReferenceInstruction) continue
                    val ref = insn.reference as? MethodReference ?: continue
                    val methodName = ref.name.toString()
                    val defClass   = ref.definingClass.toString()
                    if (methodName !in targets || defClass !in webClasses) continue

                    // Resolve boolean argument.
                    val arg: String? = if (methodName in noArgMethods) null else {
                        // Instruction35c gives us direct register access.
                        val i35 = insn as? com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
                        val regVal: Int? = when {
                            i35 != null && i35.registerCount >= 2 ->
                                // invoke-virtual {vC=obj, vD=arg} → vD is the bool
                                regs[i35.registerD] ?: lastConst
                            i35 != null && i35.registerCount == 1 ->
                                // invoke-static {vC=arg}
                                regs[i35.registerC] ?: lastConst
                            else ->
                                // invoke-*/range or fallback
                                lastConst
                        }
                        when (regVal) { 0 -> "false"; 1 -> "true"; else -> null }
                    }

                    val key = "$callerClass#$methodName"
                    out.putIfAbsent(key, WebViewFinding(
                        callerClass   = callerClass,
                        method        = methodName,
                        argument      = arg,
                        definingClass = defClass,
                        severity      = webViewSeverity(methodName, arg)
                    ))
                    lastConst = null
                }
            }
        }
    }
}

private val BOILERPLATE_HOSTS = setOf(
    "www.w3.org", "w3.org",
    "schemas.android.com",
    "ns.adobe.com",
    "xmlpull.org", "www.xmlpull.org",
    "xml.org", "www.xml.org",
    "java.sun.com", "sun.com",
    "aomedia.org", "www.aomedia.org",
    "iptc.org", "www.iptc.org",
    "purl.org",
    "www.example.com", "example.com", "example.org", "example.net",
    "schema.org", "www.schema.org",
    "apache.org", "www.apache.org", "xml.apache.org",
    "relaxng.org",
    "docbook.org"
)

/** Sort order: HIGH first, then MEDIUM, LOW, INFO. */
private fun severityOrder(s: String) = when (s) { "HIGH" -> 0; "MEDIUM" -> 1; "LOW" -> 2; else -> 3 }

/** Severity for a WebView setting call, taking the boolean arg into account. */
private fun webViewSeverity(method: String, arg: String?): String {
    // Only flag "true" calls as dangerous; "false" calls are safe (but still reported as INFO).
    val enabled = arg == "true" || arg == null  // null = arg unknown, assume worst case
    return when (method) {
        "setAllowUniversalAccessFromFileURLs" -> if (enabled) "HIGH"   else "INFO"
        "setJavaScriptEnabled"               -> if (enabled) "HIGH"   else "INFO"
        "addJavascriptInterface"             -> "HIGH"   // always high — no bool arg
        "setAllowFileAccessFromFileURLs"     -> if (enabled) "MEDIUM" else "INFO"
        "setAllowFileAccess"                 -> if (enabled) "MEDIUM" else "INFO"
        "setWebContentsDebuggingEnabled"     -> if (enabled) "MEDIUM" else "INFO"
        "setAllowContentAccess"              -> if (enabled) "LOW"    else "INFO"
        else                                 -> "INFO"
    }
}

@Serializable
data class WebViewFinding(
    /** Fully-qualified class that makes the call. */
    val callerClass: String,
    /** Method name e.g. "setJavaScriptEnabled". */
    val method: String,
    /** Boolean argument as string ("true"/"false") or null if unknown / not applicable. */
    val argument: String?,
    /** The WebView/WebSettings class the method belongs to. */
    val definingClass: String,
    /** HIGH / MEDIUM / LOW / INFO */
    val severity: String
)

@Serializable
data class WebReport(
    val packageName: String,
    val urls: List<WebUrl>,
    val endpoints: List<Endpoint>,
    val params: List<String>,
    val webviews: List<WebViewFinding> = emptyList()
)

@Serializable
data class WebUrl(
    val url: String,
    val sources: List<String>
)

@Serializable
data class Endpoint(
    val path: String,
    val params: List<String>,
    val sources: List<String> = emptyList()
)
