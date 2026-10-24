// SPDX-License-Identifier: GPL-3.0-or-later
package dev.caulis.cursor.bibata

import dev.caulis.core.cursor.CursorThemeProvider
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * The Bibata cursor themes, found by [dev.caulis.core.cursor.CursorTheme.load] on their own
 * once this module is in the project. Bibata-Modern-Classic (black) is the default;
 * Bibata-Modern-Ice (white) and Bibata-Modern-Amber are there too.
 *
 * Each theme is one zip in the resources (the theme's files, and its links as a list of
 * aliases); it is opened only when a cursor of it is first needed.
 */
class BibataCursors : CursorThemeProvider {

    override val themes: List<String> = THEMES

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
        val real = if (path.startsWith("cursors/")) {
            val name = path.removePrefix("cursors/")
            "cursors/" + opened.resolve(name)
        } else {
            path
        }
        val entry = opened.zip.getEntry(real) ?: return null
        return opened.zip.getInputStream(entry).use { it.readAllBytes() }
    }

    override fun files(theme: String): List<String> {
        val opened = open(theme) ?: return emptyList()
        return opened.zip.entries().asSequence().map { it.name }.filter { it != ALIASES && !it.endsWith("/") }.toList()
    }

    override fun aliases(theme: String): Map<String, String> = open(theme)?.aliases ?: emptyMap()

    // The theme's zip, copied out of the jar once so single files can be read directly.
    private fun open(theme: String): Theme? = synchronized(opened) { opened.getOrPut(theme) { load(theme) } }

    private fun load(theme: String): Theme? {
        if (theme !in THEMES) return null
        val resource = BibataCursors::class.java.getResourceAsStream("$theme.zip") ?: return null
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

    companion object {
        /** The themes in this module, the default first. */
        @JvmField
        val THEMES = listOf("Bibata-Modern-Classic", "Bibata-Modern-Ice", "Bibata-Modern-Amber")

        const val CLASSIC = "Bibata-Modern-Classic"
        const val ICE = "Bibata-Modern-Ice"
        const val AMBER = "Bibata-Modern-Amber"

        private const val ALIASES = "aliases"

        // Shared by all instances (ServiceLoader makes a new one each time themes are looked up).
        private val opened = HashMap<String, Theme?>()
    }
}
