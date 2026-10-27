package dev.caulis.wm

import dev.caulis.core.SpecialWorkspaces
import dev.caulis.render.Color
import dev.caulis.render.Paint
import dev.caulis.wm.config.Actions
import dev.caulis.wm.config.ConfigFile
import dev.caulis.wm.config.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Special workspaces and border paints from the configuration: actions, rules, config.json, a reload. */
class SpecialWorkspacesTest {

    private val harness = CaulisTest()

    @Test
    fun `the actions move a window in and out and toggle it, and a reload keeps it`() = with(harness) {
        withCaulis({ specialWorkspaces = SpecialWorkspaces(gap = 0) }) { caulis, client ->
            client.window { it.setTitle("w") }.draw()
            client.waitFor { caulis.onLoop { it.desktop.windows.size } == 1 }
            caulis.onLoop { Actions.parse("move-to-workspace special:music silent")(caulis) }
            assertEquals(1, caulis.onLoop { it.desktop.specialWorkspace("music").windows.size })
            assertFalse(caulis.onLoop { it.desktop.specialWorkspace("music").shown })
            caulis.onLoop { Actions.parse("special-workspace toggle music")(caulis) }
            assertTrue(caulis.onLoop { it.desktop.specialWorkspace("music").shown })

            // A reload: still there, still shown.
            val next = caulis.reloaded { specialWorkspaces = SpecialWorkspaces(dim = 0.6f) }
            caulis.onLoop { it.reconfigure(next) }
            assertTrue(next.onLoop { it.desktop.specialWorkspace("music").shown })
            assertEquals(0.6f, next.onLoop { it.desktop.specialWorkspaces.dim })

            next.onLoop { Actions.parse("move-out-of-special")(next) }
            assertEquals(1, next.onLoop { it.desktop.windows.size })
            assertFalse(next.onLoop { it.desktop.specialWorkspace("music").shown }, "empty: hidden")
            next.onLoop { Actions.parse("move-to-special")(next) }
            assertEquals(1, next.onLoop { it.desktop.specialWorkspace().windows.size }, "the one without a name")
            assertTrue(next.onLoop { it.desktop.specialWorkspace().shown })
        }
    }

    @Test
    fun `a rule sends an app there`() = with(harness) {
        withCaulis({ rule { appId("player"); specialWorkspace = "music" } }) { caulis, client ->
            client.window { it.setAppId("player") }.draw()
            client.waitFor { caulis.onLoop { it.desktop.specialWorkspace("music").windows.size } == 1 }
            assertTrue(caulis.onLoop { it.desktop.windows.isEmpty() })
            assertFalse(caulis.onLoop { it.desktop.specialWorkspace("music").shown }, "waiting there")
        }
    }

    @Test
    fun `wrong actions say why`() {
        assertFailsWith<IllegalArgumentException> { Actions.parse("special-workspace flip") }
        assertFailsWith<IllegalArgumentException> { Actions.parse("move-to-special a b c") }
        assertFailsWith<IllegalArgumentException> { Actions.parse("move-to-workspace special:x loud") }
    }

    @Test
    fun `config json - Hyprland's border colours, urgent, animate, and special workspaces`() {
        val text = """
            {
              "effects": { "border": { "width": 3, "focused": "rgba(33ccffee) rgba(00ff99ee) 45deg", "unfocused": "#595959aa", "urgent": "rgb(ff5555)", "animate": false } },
              "specialWorkspaces": { "dim": 0.5, "gap": 20, "size": 0.9, "hideWhenEmpty": false, "blur": { "size": 6 } },
              "rules": [ { "appId": "spotify", "specialWorkspace": "music", "borderPaint": { "focused": "rgba(ff0000ff) rgba(0000ffff) 90deg" } } ]
            }
        """.trimIndent()
        val read = ConfigFile.read(text)
        assertEquals(emptyList(), read.problems)
        val caulis = Caulis()
        Settings.apply(caulis, read.values)
        val border = caulis.effects.border!!
        assertEquals(Paint.Gradient(listOf(Color.hex("#33ccffee"), Color.hex("#00ff99ee")), 45.0), border.focused)
        assertEquals(Paint.solid(Color.hex("#ff5555")), border.urgent)
        assertFalse(border.animate)
        assertEquals(SpecialWorkspaces(dim = 0.5f, gap = 20, size = 0.9, hideWhenEmpty = false, blur = dev.caulis.core.Blur(6)), caulis.specialWorkspaces)
        assertEquals("music", caulis.rules.single().specialWorkspace)
    }
}
