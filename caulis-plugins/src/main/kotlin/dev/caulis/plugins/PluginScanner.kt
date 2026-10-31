package dev.caulis.plugins

import java.nio.file.Files
import java.nio.file.Path

/** Reads the plugin folders, away from the compositor's thread. */
internal class PluginScanner<H : Any>(private val formats: List<PluginFormat<H>>, private val apiName: String) {

    /** One file or folder in a plugin folder: a plugin, or why it is not one. */
    class Found<H : Any>(val path: Path, val source: PluginSource<H>?, val format: String, val fingerprint: String, val problem: String?)

    /** The folders' plugins, once their files stopped changing (a copy may be going on). */
    fun settled(options: PluginOptions): List<Found<H>> {
        var last = fingerprints(options)
        repeat(SETTLE_TRIES) {
            Thread.sleep(options.settleMillis)
            val now = fingerprints(options)
            if (now == last) return read(options)
            last = now
        }
        return read(options)
    }

    private fun fingerprints(options: PluginOptions): Map<Path, String> =
        candidates(options).associateWith { runCatching { PluginFiles.fingerprint(it) }.getOrDefault("") }

    private fun candidates(options: PluginOptions): List<Path> = options.directories.flatMap { directory ->
        if (!Files.isDirectory(directory)) emptyList() else Files.list(directory).use { all ->
            all.filter { !it.fileName.toString().startsWith(".") }.sorted().toList()
        }
    }

    private fun read(options: PluginOptions): List<Found<H>> = candidates(options).mapNotNull { path ->
        val fingerprint = runCatching { PluginFiles.fingerprint(path) }.getOrDefault("")
        try {
            for (format in formats) {
                val source = format.recognize(path, apiName) ?: continue
                val unsafe = if (options.allowUnsafePermissions) null else PluginFiles.unsafeBecause(path)
                if (unsafe != null) return@mapNotNull Found<H>(path, null, format.name, fingerprint, unsafe)
                return@mapNotNull Found(path, source, format.name, fingerprint, null)
            }
            null
        } catch (e: PluginManifestException) {
            Found<H>(path, null, "?", fingerprint, e.message)
        } catch (e: java.io.IOException) {
            Found<H>(path, null, "?", fingerprint, "$path: $e")
        }
    }

    private companion object {
        const val SETTLE_TRIES = 50
    }
}
