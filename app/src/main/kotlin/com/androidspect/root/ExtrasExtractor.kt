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
 * Recovers the Intent *extras* a component actually reads, by statically
 * scanning its compiled DEX for calls to android.content.Intent.getXxxExtra(...)
 * and android.os.Bundle.getXxx(...).
 *
 * How it works:
 *   In Dalvik bytecode an extra read looks like:
 *       const-string v2, "auth_token"
 *       invoke-virtual {v1, v2}, Landroid/content/Intent;->getStringExtra(...)
 *   The string key is loaded into a register immediately before the call.
 *   We walk each method's instructions, remember the most recent const-string
 *   per register, and when we hit a get*Extra/Bundle getter we pair the
 *   key-register's last string with the getter's return type.
 *
 * This is a best-effort heuristic — keys built dynamically (concatenated,
 * obfuscated, read from a field) can't be recovered statically — but it
 * captures the common literal-key case that covers the large majority of
 * real apps, which is exactly what makes the adb command precise.
 */
class ExtrasExtractor(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun extract(packageName: String, componentClass: String): List<IntentExtra> =
        withContext(Dispatchers.IO) {
            val ai = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                ?: return@withContext emptyList()
            val apkPath = ai.sourceDir ?: return@withContext emptyList()

            // Normalise the class name to a Dalvik type descriptor:
            // com.example.MainActivity  ->  Lcom/example/MainActivity;
            val fq = if (componentClass.startsWith(".")) packageName + componentClass else componentClass
            val targetType = "L" + fq.replace('.', '/') + ";"

            val found = LinkedHashMap<String, IntentExtra>()  // key -> extra (dedup, keep first type)

            // Scan every .dex in the APK (multidex) for the target class.
            ZipFile(apkPath).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".dex") }
                    .forEach { entry ->
                        val tmp = File.createTempFile("scan_", ".dex", context.cacheDir)
                        try {
                            zip.getInputStream(entry).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                            val dex = DexFileFactory.loadDexFile(tmp, Opcodes.getDefault())
                            val cls = dex.classes.firstOrNull { it.type.toString() == targetType } ?: return@forEach
                            scanClass(cls, found)
                        } catch (_: Exception) {
                            // skip unreadable dex
                        } finally {
                            tmp.delete()
                        }
                    }
            }
            found.values.toList()
        }

    private fun scanClass(
        cls: com.android.tools.smali.dexlib2.iface.ClassDef,
        out: LinkedHashMap<String, IntentExtra>
    ) {
        for (method in cls.methods) {
            val impl = method.implementation ?: continue
            // Track the last const-string literal seen, in order. An extra read
            // pattern is:
            //     const-string vX, "key"
            //     invoke-virtual {…}, Intent;->getStringExtra(…)
            // so when we hit a getter we attribute the most recent string literal
            // as its key. We keep a tiny window of recent strings to stay robust
            // against one intervening instruction (e.g. a move).
            var lastString: String? = null
            for (insn in impl.instructions) {
                // const-string vX, "literal"
                if (insn is Instruction21c) {
                    val ref = insn.reference
                    if (ref is StringReference) {
                        lastString = ref.string.toString()
                        continue
                    }
                }
                // invoke-* … Intent/Bundle getter
                if (insn is ReferenceInstruction) {
                    val ref = insn.reference
                    if (ref is MethodReference) {
                        val type = extraType(ref) ?: continue
                        val key = lastString ?: continue
                        out.putIfAbsent(key, IntentExtra(name = key, type = type))
                        // Consume the key so a following unrelated getter doesn't
                        // reuse it.
                        lastString = null
                    }
                }
            }
        }
    }

    /**
     * Maps an Intent/Bundle getter to an adb-am extra type token, or null if
     * the method isn't an extra getter.
     */
    private fun extraType(m: MethodReference): String? {
        val cls = m.definingClass.toString()
        if (cls != "Landroid/content/Intent;" && cls != "Landroid/os/Bundle;") return null
        return when (m.name.toString()) {
            "getStringExtra", "getString"        -> "s"   // --es
            "getIntExtra", "getInt"              -> "i"   // --ei
            "getLongExtra", "getLong"            -> "l"   // --el
            "getBooleanExtra", "getBoolean"      -> "z"   // --ez
            "getFloatExtra", "getFloat"          -> "f"   // --ef
            "getDoubleExtra", "getDouble"        -> "f"   // am has no double flag; use float
            "getByteExtra"                       -> "i"
            "getCharSequenceExtra"               -> "s"
            else -> null
        }
    }
}

@Serializable
data class IntentExtra(
    val name: String,
    val type: String   // adb am extra type token: s|i|l|z|f|d
)
