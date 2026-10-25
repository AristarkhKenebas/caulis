package dev.caulis.wm

import dev.caulis.core.appearance.ColorSchedule
import dev.caulis.core.appearance.ColorScheme
import dev.caulis.ipc.CaulisIpc
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Light and dark: from the configuration, by hand (an action), by a schedule, and seen from outside. */
class AppearanceTest {

    private val harness = CaulisTest()

    @Test
    fun `by hand until the schedule's next change, then the schedule again`() {
        val schemes = ColorSchemes()
        schemes.schedule = ColorSchedule.Times(dark = LocalTime.of(20, 0), light = LocalTime.of(7, 0))
        val zone = ZoneId.of("Europe/Berlin")
        fun at(hour: Int, day: Int = 1) = ZonedDateTime.of(LocalDate.of(2026, 11, day), LocalTime.of(hour, 0), zone)
        assertEquals(ColorScheme.LIGHT to at(20), schemes.at(at(12)))
        schemes.switch(ColorScheme.DARK, at(12))
        assertEquals(ColorScheme.DARK, schemes.at(at(15)).first, "by hand")
        assertEquals(ColorScheme.DARK, schemes.at(at(21)).first, "the schedule's dark came")
        assertEquals(ColorScheme.LIGHT, schemes.at(at(8, day = 2)).first, "and its light: by hand is over")
        // Without a schedule: by hand stays; auto (null) goes back to the configuration's.
        val plain = ColorSchemes().apply { base = ColorScheme.LIGHT }
        plain.switch(ColorScheme.DARK, at(12))
        assertEquals(ColorScheme.DARK to null, plain.at(at(12, day = 20)))
        plain.switch(null, at(12))
        assertEquals(ColorScheme.LIGHT, plain.at(at(12)).first)
    }

    @Test
    fun `the configuration's scheme, an action, a listener and the IPC state agree`() = with(harness) {
        val heard = ArrayList<ColorScheme>()
        withCaulis({
            configName = "appearancewm"
            colorScheme = ColorScheme.DARK
            onColorSchemeChanged { heard += it }
        }, headless = true) { caulis, client ->
            assertEquals(ColorScheme.DARK, caulis.onLoop { it.config.colorSchemeNow })
            caulis.onLoop { it.config.run("color-scheme toggle") }
            assertEquals(ColorScheme.LIGHT, caulis.onLoop { it.config.colorSchemeNow })
            assertEquals(listOf(ColorScheme.DARK, ColorScheme.LIGHT), heard, "at the start too")
            val socket = caulis.socketDirectory!!.resolve("appearancewm-${caulis.onLoop { it.socket }}.sock")
            client.waitFor { Files.exists(socket) }
            CaulisIpc.connect(socket).use { ipc ->
                assertEquals("light", ipc.state().colorScheme)
                ipc.run("color-scheme auto")
            }
            assertEquals(ColorScheme.DARK, caulis.onLoop { it.config.colorSchemeNow }, "auto: the configuration's again")
        }
    }

    @Test
    fun `config json sets the scheme and the schedule`() = with(harness) {
        val folder = Files.createTempDirectory("caulis-config")
        Files.writeString(folder.resolve("config.json"), """{ "appearance": { "colorScheme": "light", "darkFrom": "21:30", "lightFrom": "06:45" } }""")
        try {
            withCaulis({
                useConfigFile = true
                configDir = folder
            }, headless = true) { caulis, client ->
                client.waitFor { caulis.onLoop { it.config.colorSchedule } != null }
                assertEquals(ColorSchedule.Times(LocalTime.of(21, 30), LocalTime.of(6, 45)), caulis.onLoop { it.config.colorSchedule })
                assertEquals(ColorScheme.LIGHT, caulis.onLoop { it.config.colorScheme })
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
