package dev.caulis.core.cursor

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Cursor themes kept as one zip each among a library's resources, next to [owner]
 * (`<theme>.zip` in its package): the theme's files (`index.theme`, `cursors/...`), and an
 * `aliases` file of "other-name name" lines instead of links. Caulis's own themes are kept
 * this way, and so can a library's.
 */
open class ZipCursorThemes(private val owner: Class<*>, final override val themes: List<String>) : CursorThemeProvider {

    private class Theme(val zip: ZipFile, val aliases: Map<String, String>) {
        // A name for another cursor, which may itself be another name.
        fun resolve(name: String): String {
            var current = name
            repeat(8) { current = aliases[current] ?: return current }
            return current
        }
    }

    override fun file(theme: String, path: String): ByteArray? {
        val opened = open(theme) ?: return null
        val real = if (path.startsWith("cursors/")) "cursors/" + opened.resolve(path.removePrefix("cursors/")) else path
        val entry = opened.zip.getEntry(real) ?: return null
        return opened.zip.getInputStream(entry).use { it.readAllBytes() }
    }

    override fun files(theme: String): List<String> {
        val opened = open(theme) ?: return emptyList()
        return opened.zip.entries().asSequence().map { it.name }.filter { it != ALIASES && !it.endsWith("/") }.toList()
    }

    override fun aliases(theme: String): Map<String, String> = open(theme)?.aliases ?: emptyMap()

    // The theme's zip, copied out of the jar once so single files can be read directly.
    private fun open(theme: String): Theme? = synchronized(opened) {
        opened.getOrPut("${owner.name}/$theme") { load(theme) }
    }

    private fun load(theme: String): Theme? {
        if (theme !in themes) return null
        val resource = owner.getResourceAsStream("$theme.zip") ?: return null
        val copy = Files.createTempFile("caulis-$theme", ".zip")
        copy.toFile().deleteOnExit()
        resource.use { Files.copy(it, copy, StandardCopyOption.REPLACE_EXISTING) }
        val zip = ZipFile(copy.toFile())
        val aliases = zip.getEntry(ALIASES)?.let { entry ->
            zip.getInputStream(entry).use { it.readAllBytes() }.decodeToString().lines()
                .mapNotNull { line -> line.split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
        } ?: emptyMap()
        return Theme(zip, aliases)
    }

    private companion object {
        const val ALIASES = "aliases"

        // Shared by all instances (ServiceLoader makes a new one each time themes are looked up).
        val opened = HashMap<String, Theme?>()
    }
}
