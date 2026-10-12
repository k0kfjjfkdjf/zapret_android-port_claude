package io.github.zapretdroid.core

import java.io.File

/**
 * Placeholders that users can put into ByeDPI arguments instead of file paths:
 * {fake:name} is a zapret2 fake payload (".bin" may be omitted), {list:name} a host list.
 */
object ArgTemplates {
    private val PLACEHOLDER = Regex("""\{(fake|list):([^{}\s]+)}""")

    class UnknownReference(val kind: String, val name: String) :
        IllegalArgumentException("unknown $kind '$name'")

    /** Replaces placeholders with absolute paths; throws [UnknownReference] for missing files. */
    fun resolve(args: List<String>, fakeDir: File, listDir: File): List<String> = args.map { arg ->
        PLACEHOLDER.replace(arg) { match ->
            val (kind, name) = match.destructured
            val file = when (kind) {
                "fake" -> File(fakeDir, name).takeIf { it.isFile } ?: File(fakeDir, "$name.bin")
                else -> File(listDir, name).takeIf { it.isFile } ?: File(listDir, "$name.txt")
            }
            if (!file.isFile) throw UnknownReference(kind, name)
            file.absolutePath
        }
    }

    fun fake(name: String) = "{fake:$name}"
}
