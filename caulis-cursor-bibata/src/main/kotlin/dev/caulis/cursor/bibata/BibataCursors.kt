// SPDX-License-Identifier: GPL-3.0-or-later
package dev.caulis.cursor.bibata

import dev.caulis.core.cursor.ZipCursorThemes

/**
 * The Bibata cursor themes, found by [dev.caulis.core.cursor.CursorTheme.load] on their own
 * once this module is in the project. Bibata-Modern-Classic (black) is the default;
 * Bibata-Modern-Ice (white) and Bibata-Modern-Amber are there too.
 */
class BibataCursors : ZipCursorThemes(BibataCursors::class.java, THEMES) {

    companion object {
        /** The themes in this module, the default first. */
        @JvmField
        val THEMES = listOf("Bibata-Modern-Classic", "Bibata-Modern-Ice", "Bibata-Modern-Amber")

        const val CLASSIC = "Bibata-Modern-Classic"
        const val ICE = "Bibata-Modern-Ice"
        const val AMBER = "Bibata-Modern-Amber"
    }
}
