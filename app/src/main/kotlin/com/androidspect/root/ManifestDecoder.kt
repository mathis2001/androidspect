package com.androidspect.root

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.XmlResourceParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser

/**
 * Decodes an installed app's real AndroidManifest.xml.
 *
 * Strategy: open the APK with a private AssetManager and use Android's own
 * native binary-XML parser (openXmlResourceParser) to walk the *actual*
 * compiled AndroidManifest.xml. This yields the complete manifest — every
 * element, attribute, intent-filter, meta-data, uses-feature, provider
 * path-permission, etc. — not a synthesized subset.
 *
 * The previous implementation reconstructed a partial manifest from
 * PackageManager's parsed PackageInfo, which silently dropped intent-filters,
 * meta-data, application attributes (usesCleartextTraffic, networkSecurityConfig),
 * permission protection levels, queries, and more. This reads the source of
 * truth instead.
 *
 * Attribute *values* that are resource references (e.g. android:theme,
 * android:label pointing at @string/...) appear as resource IDs (e.g.
 * "@7f0f0001") because resolving them requires the app's resources.arsc.
 * Structural attributes a pentester cares about — names, exported flags,
 * permissions, authorities, schemes, hosts — are all literal strings and
 * decode fully.
 */
class ManifestDecoder(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun decode(packageName: String): ManifestDump? = withContext(Dispatchers.IO) {
        val pkg: PackageInfo = runCatching {
            pm.getPackageInfo(
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
            )
        }.getOrNull() ?: return@withContext null

        val ai = pkg.applicationInfo
        val apkPath = ai?.sourceDir

        val xml = if (apkPath != null) {
            runCatching { decodeRealManifest(apkPath) }.getOrNull()
                ?: buildFallbackXml(pkg)   // if AXML parse fails, degrade gracefully
        } else {
            buildFallbackXml(pkg)
        }

        ManifestDump(
            packageName = pkg.packageName,
            versionName = pkg.versionName.orEmpty(),
            versionCode = pkg.longVersionCode,
            minSdk = ai?.minSdkVersion ?: 0,
            targetSdk = ai?.targetSdkVersion ?: 0,
            permissions = pkg.requestedPermissions?.toList().orEmpty(),
            xml = xml
        )
    }

    /**
     * Opens the APK as an asset source and parses the compiled
     * AndroidManifest.xml with Android's native binary-XML parser,
     * re-serialising it to readable text.
     */
    private fun decodeRealManifest(apkPath: String): String {
        // Build a private AssetManager pointed at this APK. addAssetPath is
        // hidden API but stable and whitelisted (greylist) — it's the standard
        // technique used by every on-device manifest reader.
        val assets = AssetManager::class.java.newInstance()
        val addAssetPath = AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assets, apkPath) as Int
        if (cookie == 0) error("addAssetPath failed for $apkPath")

        // Resources bound to this AssetManager so we can resolve @string/... etc.
        val res: android.content.res.Resources? = runCatching {
            android.content.res.Resources(
                assets,
                context.resources.displayMetrics,
                context.resources.configuration
            )
        }.getOrNull()

        val parser: XmlResourceParser = assets.openXmlResourceParser(cookie, "AndroidManifest.xml")
        parser.use { p ->
            return serialize(p, res)
        }
    }

    /**
     * Walks the XmlResourceParser event stream and pretty-prints it as XML.
     * Resolves the android: namespace prefix and indents by depth.
     */
    private fun serialize(p: XmlResourceParser, res: android.content.res.Resources?): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        val androidNs = "http://schemas.android.com/apk/res/android"
        var depth = 0

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val indent = "    ".repeat(depth)
                    sb.append("$indent<${p.name}")
                    // Attributes
                    val n = p.attributeCount
                    if (n > 0) {
                        for (i in 0 until n) {
                            val ns   = p.getAttributeNamespace(i)
                            val name = p.getAttributeName(i)
                            val prefix = if (ns == androidNs) "android:" else ""
                            val value = readAttr(p, i, res)
                            if (n == 1) {
                                sb.append(" $prefix$name=\"${esc(value)}\"")
                            } else {
                                sb.append("\n$indent    $prefix$name=\"${esc(value)}\"")
                            }
                        }
                    }
                    sb.appendLine(">")
                    depth++
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    sb.appendLine("${"    ".repeat(depth)}</${p.name}>")
                }
                XmlPullParser.TEXT -> {
                    val t = p.text?.trim()
                    if (!t.isNullOrEmpty()) sb.appendLine("${"    ".repeat(depth)}${esc(t)}")
                }
            }
            event = p.next()
        }
        return sb.toString()
    }

    /**
     * Reads an attribute value, resolving resource references (@string/foo) to
     * their literal value when possible. Unresolved numeric refs ("@2131820742")
     * are shown as-is in the manifest XML (display only) but note that
     * ComponentInspector drops them for analysis.
     */
    private fun readAttr(p: XmlResourceParser, i: Int, res: android.content.res.Resources?): String {
        // Resolve @reference values to their real string via Resources.
        val resId = p.getAttributeResourceValue(i, 0)
        if (resId != 0 && res != null) {
            val resolved = runCatching { res.getString(resId) }.getOrNull()
            if (!resolved.isNullOrEmpty()) return resolved
        }
        val raw = p.getAttributeValue(i)
        if (raw != null) return raw
        return runCatching { p.getAttributeIntValue(i, -1).toString() }.getOrDefault("")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * Last-resort synthesized manifest if the AXML parse fails (corrupt APK,
     * unusual packaging, hidden-API restriction on a future Android version).
     * This is the old behaviour — partial but better than nothing.
     */
    private fun buildFallbackXml(pkg: PackageInfo): String {
        val ai = pkg.applicationInfo
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("""<!-- NOTE: binary AXML parse failed; this is a partial reconstruction -->""")
        sb.append("""<manifest package="${pkg.packageName}"""")
        sb.append(""" android:versionCode="${pkg.longVersionCode}"""")
        if (!pkg.versionName.isNullOrBlank()) sb.append(""" android:versionName="${pkg.versionName}"""")
        sb.appendLine(">")
        sb.appendLine("""    <uses-sdk android:minSdkVersion="${ai?.minSdkVersion ?: 0}" android:targetSdkVersion="${ai?.targetSdkVersion ?: 0}"/>""")
        pkg.requestedPermissions?.forEach { sb.appendLine("""    <uses-permission android:name="$it"/>""") }
        sb.appendLine("""    <application android:label="${ai?.loadLabel(pm) ?: pkg.packageName}">""")
        sb.appendLine("""    </application>""")
        sb.appendLine("""</manifest>""")
        return sb.toString()
    }
}

@Serializable
data class ManifestDump(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val xml: String
)
