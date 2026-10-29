package dev.caulis.core.appearance

import dev.caulis.log.Logger

/**
 * Writes the [appearance] where GTK programs and the GTK and GNOME portals read it
 * (`gsettings set org.gnome.desktop.interface color-scheme prefer-dark`): with
 * xdg-desktop-portal-gtk or -gnome installed, as on most desktops, that is all programs need.
 * GTK 3 programs follow a theme rather than the scheme: give [gtkThemes] (light, dark), e.g.
 * ("Adwaita", "Adwaita-dark"), and the theme changes too. Nothing is written until the
 * appearance changes. Close it to stop.
 */
class GSettingsAppearance(
    private val appearance: Appearance,
    /** The GTK theme for light and for dark, or null: the theme is left as it is. */
    var gtkThemes: Pair<String, String>? = null,
    // Runs a command (gsettings ...); its output is not needed.
    private val run: (List<String>) -> Unit = ::start,
) : AutoCloseable {

    private val listening = appearance.onChange { write() }

    /** Writes the appearance now. */
    fun write() {
        val scheme = appearance.colorScheme
        val value = when (scheme) {
            ColorScheme.DARK -> "prefer-dark"
            ColorScheme.LIGHT -> "prefer-light"
            ColorScheme.DEFAULT -> "default"
        }
        log.trace { "gsettings: color-scheme $value" }
        run(listOf("gsettings", "set", SCHEMA, "color-scheme", value))
        gtkThemes?.let { (light, dark) ->
            if (scheme != ColorScheme.DEFAULT) run(listOf("gsettings", "set", SCHEMA, "gtk-theme", if (scheme == ColorScheme.DARK) dark else light))
        }
    }

    override fun close() = listening.close()

    private companion object {
        const val SCHEMA = "org.gnome.desktop.interface"
        val log = Logger("appearance")

        fun start(command: List<String>) {
            try {
                dev.caulis.ffi.Processes.start(ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD))
            } catch (e: java.io.IOException) {
                dev.caulis.ffi.Processes.tellStartFailed(log, command.first(), e, "GTK programs are not told the appearance")
            }
        }
    }
}
