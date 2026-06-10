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
 * Statically recovers the deeplink parameters a component reads from the
 * incoming Uri — query parameters (named) and path-segment accesses.
 *
 * Deeplink data is read via android.net.Uri, not Intent extras, e.g.:
 *     const-string v2, "id"
 *     invoke-virtual {v1, v2}, Landroid/net/Uri;->getQueryParameter(...)
 *     invoke-virtual {v1}, Landroid/net/Uri;->getPathSegments()
 *     invoke-virtual {v1}, Landroid/net/Uri;->getLastPathSegment()
 *
 * Same heuristic as ExtrasExtractor: track the most recent const-string and
 * attribute it to the next Uri getter that takes a key. Path-segment reads
 * don't carry a literal name, so they're surfaced generically.
 *
 * Best-effort: literal keys are recovered; dynamically-built keys are not.
 */
class DeeplinkParamExtractor(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun extract(packageName: String, componentClass: String): List<DeeplinkParam> =
        withContext(Dispatchers.IO) {
            val ai = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                ?: return@withContext emptyList()
            val apkPath = ai.sourceDir ?: return@withContext emptyList()

            val fq = if (componentClass.startsWith(".")) packageName + componentClass else componentClass
            val targetType = "L" + fq.replace('.', '/') + ";"

            val found = LinkedHashMap<String, DeeplinkParam>()

            ZipFile(apkPath).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".dex") }
                    .forEach { entry ->
                        val tmp = File.createTempFile("dlp_", ".dex", context.cacheDir)
                        try {
                            zip.getInputStream(entry).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                            val dex = DexFileFactory.loadDexFile(tmp, Opcodes.getDefault())
                            val cls = dex.classes.firstOrNull { it.type.toString() == targetType } ?: return@forEach
                            scanClass(cls, found)
                        } catch (_: Exception) {
                        } finally { tmp.delete() }
                    }
            }
            found.values.toList()
        }

    private fun scanClass(
        cls: com.android.tools.smali.dexlib2.iface.ClassDef,
        out: LinkedHashMap<String, DeeplinkParam>
    ) {
        for (method in cls.methods) {
            val impl = method.implementation ?: continue
            var lastString: String? = null
            for (insn in impl.instructions) {
                if (insn is Instruction21c) {
                    val ref = insn.reference
                    if (ref is StringReference) { lastString = ref.string.toString(); continue }
                }
                if (insn is ReferenceInstruction) {
                    val ref = insn.reference
                    if (ref is MethodReference) {
                        val cls2 = ref.definingClass.toString()
                        if (cls2 != "Landroid/net/Uri;") continue
                        when (ref.name.toString()) {
                            "getQueryParameter", "getQueryParameters" -> {
                                val key = lastString ?: continue
                                out.putIfAbsent("q:$key", DeeplinkParam(name = key, kind = "query"))
                                lastString = null
                            }
                            "getQueryParameterNames" -> {
                                out.putIfAbsent("q:*", DeeplinkParam(
                                    name = "(enumerates all query params)", kind = "query-all"))
                            }
                            "getPathSegments" -> {
                                out.putIfAbsent("path:segments", DeeplinkParam(
                                    name = "(reads path segments)", kind = "path"))
                            }
                            "getLastPathSegment" -> {
                                out.putIfAbsent("path:last", DeeplinkParam(
                                    name = "(reads last path segment)", kind = "path"))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Serializable
data class DeeplinkParam(
    val name: String,
    val kind: String   // query | query-all | path
)
