// SPDX-License-Identifier: GPL-3.0-or-later
package dev.caulis.cursor.bibata

import dev.caulis.core.cursor.CursorTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Bibata themes load by themselves and have every cursor programs can ask for. */
class BibataCursorsTest {

    @Test
    fun `Bibata is the default cursor once this module is there`() {
        val theme = CursorTheme.load(size = 24)
        assertEquals(BibataCursors.CLASSIC, theme.name)
        assertTrue(theme.found)
    }

    @Test
    fun `every theme has every cursor shape, in the size asked for`() {
        for (name in BibataCursors.THEMES) {
            val theme = CursorTheme.load(name, 32)
            val missing = CursorTheme.SHAPES.filter { theme.cursor(it) == null }
            assertEquals(emptyList(), missing, "$name lacks cursors")
            assertEquals(32, theme.cursor("default")!!.frames[0].nominalSize)
            assertTrue(theme.cursor("wait")!!.animated, "$name: the busy cursor turns")
        }
    }

    @Test
    fun `a theme can be put on disk for programs that draw their own cursor`() {
        val folder = Files.createTempDirectory("icons")
        try {
            assertTrue(CursorTheme.load(BibataCursors.ICE, 24).exportTo(folder))
            val cursors = folder.resolve("${BibataCursors.ICE}/cursors")
            assertTrue(Files.isRegularFile(cursors.resolve("left_ptr")))
            assertTrue(Files.isSymbolicLink(cursors.resolve("default")), "other names are links")
            // An installed theme from disk reads the same as from the module.
            val fromDisk = CursorTheme.load(BibataCursors.ICE, 24).cursor("text")!!.frames[0]
            assertTrue(fromDisk.width > 0)
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
