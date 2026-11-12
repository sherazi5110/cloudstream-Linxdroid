package android.content.res

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Android's AssetManager for extensions: the files under assets/ inside an extension's .cs3 (or .jar).
 * Extensions use it for data they ship with them (JSON, JavaScript, keys…), through
 * `resources.assets.open("name")` or `context.resources.assets`.
 *
 * An extension's own Resources reads its own file; the app-wide one ([global]) looks in every installed
 * extension, because extensions also reach their assets through the shared Context.
 */
open class AssetManager internal constructor(private val sources: () -> List<File>) {
    constructor(file: File) : this({ listOf(file) })

    private fun entries(): Sequence<Pair<File, String>> = sources().asSequence().filter { it.isFile }.flatMap { f ->
        runCatching { ZipFile(f).use { z -> z.entries().toList().map { f to it.name } } }.getOrDefault(emptyList())
    }

    /** Opens assets/[fileName] from the first extension that has it. */
    open fun open(fileName: String): InputStream {
        val name = "assets/" + fileName.trimStart('/')
        for (f in sources()) {
            if (!f.isFile) continue
            val zip = runCatching { ZipFile(f) }.getOrNull() ?: continue
            val entry = zip.getEntry(name)
            if (entry == null || entry.isDirectory) { zip.close(); continue }
            // The stream closes the zip file with it.
            val stream = zip.getInputStream(entry)
            return object : java.io.FilterInputStream(stream) {
                override fun close() { try { super.close() } finally { zip.close() } }
            }
        }
        throw FileNotFoundException(fileName)
    }

    open fun open(fileName: String, accessMode: Int): InputStream = open(fileName)

    /** Names directly inside assets/[path] ("" for the top level), like Android. */
    open fun list(path: String): Array<String> {
        val prefix = "assets/" + path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return entries().map { it.second }.filter { it.startsWith(prefix) && it.length > prefix.length }
            .map { it.removePrefix(prefix).substringBefore('/') }.distinct().toList().toTypedArray()
    }

    open fun close() = Unit

    companion object {
        const val ACCESS_UNKNOWN = 0
        const val ACCESS_RANDOM = 1
        const val ACCESS_STREAMING = 2
        const val ACCESS_BUFFER = 3

        private val files = java.util.concurrent.CopyOnWriteArrayList<File>()

        /** Every installed extension's assets (the shared Context's Resources). */
        @JvmField val global = AssetManager { files.toList() }

        /** Called by the plugin loader for each extension file it loads. */
        @JvmStatic fun register(file: File) { if (file !in files) files += file }
        @JvmStatic fun unregister(file: File) { files -= file }
    }
}
