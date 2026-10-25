package dev.caulis.core.appearance

import dev.caulis.core.rethrowIfFatal
import dev.caulis.log.Logger
import dev.caulis.render.Color

/**
 * Light or dark, as programs are told (xdg-desktop-portal's `color-scheme`: GTK 4, libadwaita,
 * Qt 6, Firefox, Chromium and Electron programs follow it). [DEFAULT]: no wish — each program
 * looks as it does by itself.
 */
enum class ColorScheme(
    /** The number the portal says it with (0 no preference, 1 dark, 2 light). */
    val portalValue: Int,
) {
    DEFAULT(0),
    DARK(1),
    LIGHT(2),
    ;

    /** The other one: dark for light, light for dark and default. */
    fun toggled(): ColorScheme = if (this == DARK) LIGHT else DARK
}

/**
 * How the desktop wants programs to look: light or dark ([colorScheme]) and the colour
 * buttons and selections take ([accentColor]). Changing it tells whoever listens ([onChange]):
 * [PortalSettings] tells programs over D-Bus, [GSettingsAppearance] writes it where GTK and
 * the GTK portal read it. All of it on the compositor's thread.
 */
class Appearance {

    private val log = Logger("appearance")
    private val listeners = ArrayList<(Appearance) -> Unit>()

    var colorScheme: ColorScheme = ColorScheme.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            log.info { "Programs are told: ${value.name.lowercase()}" }
            changed()
        }

    /** The accent colour programs are told (null: none, they keep their own). */
    var accentColor: Color? = null
        set(value) {
            if (field == value) return
            field = value
            log.info { "Accent colour: ${value ?: "none"}" }
            changed()
        }

    /** Calls [listener] after every change; close the result to stop. */
    fun onChange(listener: (Appearance) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun changed() {
        for (listener in listeners.toList()) {
            try {
                listener(this)
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                log.error("Telling about the appearance failed", e)
            }
        }
    }
}
