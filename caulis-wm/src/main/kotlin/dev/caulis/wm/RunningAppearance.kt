package dev.caulis.wm

import dev.caulis.log.Logger
import dev.caulis.event.EventLoop

/**
 * Light and dark: the colour scheme now, telling programs through the portal and gsettings.
 */
internal class RunningAppearance(private val running: Running) {

    private val colorSchemes = ColorSchemes()

    internal var colorSchemeTimer: EventLoop.Timer? = null

    // Telling programs: the portal backend (on the session bus) and gsettings. Only while wanted.
    private var portalSettings: dev.caulis.core.appearance.PortalSettings? = null

    private var gsettings: dev.caulis.core.appearance.GSettingsAppearance? = null

    private val appearanceLog = Logger("appearance")

    /** The configuration's scheme, schedule, accent and GTK themes, applied (again). */
    fun applyAppearance() {
        val config = running.config
        val toPrograms = config.appearanceToPrograms ?: (running.switchConsole != null)
        if (toPrograms && gsettings == null) {
            appearanceLog.info { "Programs are told the colour scheme (gsettings and the portal)" }
            gsettings = dev.caulis.core.appearance.GSettingsAppearance(running.appearance).also { if (running.appearance.colorScheme != dev.caulis.core.appearance.ColorScheme.DEFAULT) it.write() }
            portalSettings = running.sessionBus?.let { dev.caulis.core.appearance.PortalSettings(it, running.appearance) }
        } else if (!toPrograms && gsettings != null) {
            stopTellingPrograms()
        }
        gsettings?.gtkThemes = config.gtkThemes
        running.appearance.accentColor = config.accentColor
        if (colorSchemes.base != config.colorScheme) {
            colorSchemes.base = config.colorScheme
            colorSchemes.switch(null, java.time.ZonedDateTime.now()) // (set anew: it wins over one by hand)
        }
        colorSchemes.schedule = config.colorSchedule
        updateColorScheme()
    }

    /** Light or dark by hand until the schedule's next change (null: back to the schedule). */
    fun switchColorScheme(scheme: dev.caulis.core.appearance.ColorScheme?) {
        appearanceLog.info { "Colour scheme by hand: ${scheme?.name?.lowercase() ?: "back to the schedule"}" }
        colorSchemes.switch(scheme, java.time.ZonedDateTime.now())
        updateColorScheme()
    }

    // The scheme of now, told; looked at again when the schedule changes it (at least hourly:
    // the clock may be set, or the laptop asleep).
    internal fun updateColorScheme() {
        colorSchemeTimer?.cancel()
        colorSchemeTimer = null
        val now = java.time.ZonedDateTime.now()
        val (scheme, next) = colorSchemes.at(now)
        if (running.appearance.colorScheme != scheme) {
            running.appearance.colorScheme = scheme
            running.ipc?.colorSchemeChanged(scheme)
            for (action in running.config.colorSchemeListeners.toList()) running.safely("onColorSchemeChanged") { action(running.config, scheme) }
        }
        if (next != null) {
            val millis = java.time.Duration.between(now, next).toMillis().coerceIn(1_000, 3_600_000)
            appearanceLog.trace { "The colour scheme is looked at again in ${millis / 1000} s" }
            colorSchemeTimer = running.loop.after(millis) { running.safely("the colour schedule") { updateColorScheme() } }
        }
    }

    internal fun stopTellingPrograms() {
        portalSettings?.close()
        portalSettings = null
        gsettings?.close()
        gsettings = null
    }
}
