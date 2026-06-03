package com.androidspect.root

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.XmlResourceParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser

/**
 * Enumerates activities, services, receivers and providers of a target package,
 * with the exported flag AND structured intent-filter data (actions, categories,
 * data scheme/host/port/path/mimeType).
 *
 * PackageManager.getPackageInfo only carries name+exported — it does NOT expose
 * intent filters. So we parse the real binary AndroidManifest.xml directly with
 * Android's native AXML parser (the same technique as ManifestDecoder) to recover
 * the full filter spec. This is what enables the UI to forge accurate adb
 * commands, including deeplinks with the actual scheme://host/path.
 */
class ComponentInspector(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun list(packageName: String): ComponentReport = withContext(Dispatchers.IO) {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS

        val pkg = runCatching { pm.getPackageInfo(packageName, flags) }.getOrNull()
            ?: return@withContext ComponentReport(packageName, emptyList(), emptyList(), emptyList(), emptyList())

        // exported flags + provider authorities come from PackageManager (reliable)
        val exportedMap = HashMap<String, Boolean>()
        val authorityMap = HashMap<String, String?>()
        val readPermMap  = HashMap<String, String?>()
        val writePermMap = HashMap<String, String?>()
        pkg.activities?.forEach { exportedMap[it.name] = it.exported }
        pkg.services?.forEach   { exportedMap[it.name] = it.exported }
        pkg.receivers?.forEach  { exportedMap[it.name] = it.exported }
        pkg.providers?.forEach  {
            exportedMap[it.name] = it.exported
            authorityMap[it.name] = it.authority
            readPermMap[it.name]  = it.readPermission
            writePermMap[it.name] = it.writePermission
        }

        // intent-filters parsed from the real manifest
        val filters = runCatching {
            parseIntentFilters(pkg.applicationInfo?.sourceDir, packageName)
        }.getOrDefault(emptyMap())

        fun build(names: List<String>, type: String): List<Component> = names.map { rawName ->
            val fqName = if (rawName.startsWith(".")) packageName + rawName else rawName
            Component(
                name = rawName,
                exported = exportedMap[rawName] ?: false,
                type = type,
                authority = authorityMap[rawName],
                readPermission = readPermMap[rawName],
                writePermission = writePermMap[rawName],
                filters = filters[rawName] ?: filters[fqName] ?: emptyList()
            )
        }

        ComponentReport(
            packageName = packageName,
            activities = build(pkg.activities?.map { it.name } ?: emptyList(), "activity"),
            services   = build(pkg.services?.map   { it.name } ?: emptyList(), "service"),
            receivers  = build(pkg.receivers?.map  { it.name } ?: emptyList(), "receiver"),
            providers  = build(pkg.providers?.map  { it.name } ?: emptyList(), "provider")
        )
    }

    /**
     * Parse <intent-filter> blocks from the binary AndroidManifest.xml, keyed by
     * the enclosing component's android:name.
     */
    private fun parseIntentFilters(apkPath: String?, pkgName: String): Map<String, List<IntentFilter>> {
        if (apkPath == null) return emptyMap()

        val assets = AssetManager::class.java.newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assets, apkPath) as Int
        if (cookie == 0) return emptyMap()

        val parser = assets.openXmlResourceParser(cookie, "AndroidManifest.xml")
        val androidNs = "http://schemas.android.com/apk/res/android"
        val result = HashMap<String, MutableList<IntentFilter>>()

        parser.use { p ->
            var currentComponent: String? = null
            var inComponent = false
            var curFilter: MutableFilter? = null

            fun attr(name: String): String? {
                for (i in 0 until p.attributeCount) {
                    if (p.getAttributeName(i) == name &&
                        (p.getAttributeNamespace(i) == androidNs || p.getAttributeNamespace(i).isEmpty())) {
                        return p.getAttributeValue(i)
                    }
                }
                return null
            }

            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "activity", "activity-alias", "service", "receiver", "provider" -> {
                            currentComponent = attr("name")
                            inComponent = true
                        }
                        "intent-filter" -> if (inComponent) {
                            curFilter = MutableFilter()
                            // android:autoVerify="true" marks this as an App Link
                            // filter that Android will verify against the domain's
                            // assetlinks.json at install time.
                            curFilter!!.autoVerify = attr("autoVerify") == "true"
                        }
                        "action"   -> attr("name")?.let { curFilter?.actions?.add(it) }
                        "category" -> attr("name")?.let { curFilter?.categories?.add(it) }
                        "data"     -> if (curFilter != null) {
                            // Android merges attributes across ALL <data> tags in
                            // a filter and matches the cross-product. So we pool
                            // each attribute separately rather than treating each
                            // tag as a self-contained URI. A single tag may also
                            // carry several attributes at once — both forms work.
                            attr("scheme")?.let { curFilter!!.schemes.add(it) }
                            attr("host")?.let { curFilter!!.hosts.add(it) }
                            attr("port")?.let { curFilter!!.ports.add(it) }
                            attr("path")?.let { curFilter!!.paths.add(it) }
                            attr("pathPrefix")?.let { curFilter!!.paths.add(it) }
                            attr("pathPattern")?.let { curFilter!!.paths.add(it) }
                            attr("pathSuffix")?.let { curFilter!!.paths.add(it) }
                            attr("pathAdvancedPattern")?.let { curFilter!!.paths.add(it) }
                            attr("mimeType")?.let { curFilter!!.mimeTypes.add(it) }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "intent-filter" -> {
                            val comp = currentComponent
                            val f = curFilter
                            if (comp != null && f != null) {
                                result.getOrPut(comp) { mutableListOf() }.add(f.toFilter())
                            }
                            curFilter = null
                        }
                        "activity", "activity-alias", "service", "receiver", "provider" -> {
                            inComponent = false
                            currentComponent = null
                        }
                    }
                }
                event = p.next()
            }
        }
        return result
    }

    private class MutableFilter {
        val actions = mutableListOf<String>()
        val categories = mutableListOf<String>()
        // Pooled <data> attributes — Android merges them across all tags.
        val schemes = mutableListOf<String>()
        val hosts = mutableListOf<String>()
        val ports = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val mimeTypes = mutableListOf<String>()
        var autoVerify = false

        /**
         * Reconstruct concrete URI shapes from the pooled attributes the way
         * Android resolves them: the effective set is the cross-product of
         * schemes × hosts × paths (mimeType handled separately). Missing pools
         * fall back to a single null so e.g. a scheme-only filter still yields
         * one entry. This recovers deeplinks that span multiple <data> tags,
         * which the previous one-entry-per-tag approach dropped.
         */
        fun toFilter(): IntentFilter {
            val schemeList = schemes.distinct().ifEmpty { listOf<String?>(null) }
            val hostList   = hosts.distinct().ifEmpty { listOf<String?>(null) }
            val pathList   = paths.distinct().ifEmpty { listOf<String?>(null) }
            val portOne    = ports.distinct().firstOrNull()
            val mimeOne    = mimeTypes.distinct().firstOrNull()

            val combined = mutableListOf<IntentData>()
            for (sc in schemeList) for (h in hostList) for (pa in pathList) {
                // Skip the fully-empty combination unless there's a mimeType
                if (sc == null && h == null && pa == null && mimeOne == null) continue
                combined.add(IntentData(
                    scheme = sc,
                    host = h,
                    port = portOne,
                    path = pa,
                    pathPrefix = null,
                    pathPattern = null,
                    mimeType = mimeOne
                ))
            }
            return IntentFilter(
                actions = actions,
                categories = categories,
                data = combined,
                autoVerify = autoVerify
            )
        }
    }
}

@Serializable
data class IntentData(
    val scheme: String? = null,
    val host: String? = null,
    val port: String? = null,
    val path: String? = null,
    val pathPrefix: String? = null,
    val pathPattern: String? = null,
    val mimeType: String? = null
)

@Serializable
data class IntentFilter(
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val data: List<IntentData> = emptyList(),
    val autoVerify: Boolean = false
)

@Serializable
data class Component(
    val name: String,
    val exported: Boolean,
    val type: String = "activity",            // activity | service | receiver | provider
    val authority: String? = null,            // providers only
    val readPermission: String? = null,       // providers only
    val writePermission: String? = null,      // providers only
    val filters: List<IntentFilter> = emptyList()
)

@Serializable
data class ComponentReport(
    val packageName: String,
    val activities: List<Component>,
    val services: List<Component>,
    val receivers: List<Component>,
    val providers: List<Component>
)
