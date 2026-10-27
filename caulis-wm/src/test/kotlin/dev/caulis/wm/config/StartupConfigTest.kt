package dev.caulis.wm.config

import dev.caulis.render.Color
import dev.caulis.wm.Autostart
import dev.caulis.wm.Caulis
import dev.caulis.wm.SoundMoment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** autostart, reveal and startupSound in config.json (and so Lua and Nix). */
class StartupConfigTest {

    @Test
    fun `the start's sections apply, and applied again replace the entries`() {
        val read = ConfigFile.read(
            """
            {
              "autostart": {
                "staggerMillis": 250, "desktopEntries": true,
                "entries": [
                  { "app": ["nm-applet", "--indicator"], "delayMillis": 2000, "ifNotRunning": true,
                    "restart": { "when": "on-crash", "maxTimes": 3, "withinMillis": 60000 }, "after": ["waybar"], "env": { "X": "y" }, "dir": "~" },
                  { "app": "waybar" },
                  { "shell": "sleep 1; notify-send hi", "restart": "always" },
                  { "script": "~/start.sh", "arguments": ["--now"] }
                ]
              },
              "reveal": { "enabled": true, "fadeMillis": 800, "color": "#102030" },
              "startupSound": { "enabled": true, "preset": "classic", "volume": 0.5, "at": "start", "player": ["mpv", "{file}"] }
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        val caulis = Caulis()
        Settings.applyEach(caulis, read.values)
        val a = caulis.autostart
        assertEquals(250, a.staggerMillis)
        assertTrue(a.desktopEntries)
        assertEquals(listOf("nm-applet", "waybar", "sleep", "start.sh"), a.entries.map { it.name })
        val applet = a.entries[0]
        assertEquals(listOf("nm-applet", "--indicator"), applet.command)
        assertEquals(2000, applet.delayMillis)
        assertTrue(applet.ifNotRunning)
        assertTrue(applet.restart.wants(139) && !applet.restart.wants(0))
        assertEquals(listOf("waybar"), applet.after)
        assertEquals(mapOf("X" to "y"), applet.env)
        assertEquals("~", applet.dir)
        assertEquals(Autostart.Kind.SHELL, a.entries[2].kind)
        assertTrue(a.entries[2].restart.wants(0), "always")
        assertEquals(listOf("~/start.sh", "--now"), a.entries[3].command)

        assertTrue(caulis.reveal.enabled)
        assertEquals(800, caulis.reveal.fadeMillis)
        assertEquals(Color.hex("#102030"), caulis.reveal.color)

        val sound = caulis.startupSound
        assertTrue(sound.enabled)
        assertEquals("classic", sound.preset)
        assertEquals(0.5, sound.volume)
        assertEquals(SoundMoment.START, sound.at)
        assertEquals(listOf("mpv", "{file}"), sound.player)

        val problems = ConfigFile.read("""{ "autostart": { "entries": [ { "app": [] }, { "shel": "x" } ] }, "startupSound": { "preset": "loud" } }""").problems
        assertEquals(3, problems.size, "$problems")
    }
}
