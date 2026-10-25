package dev.caulis.wm

import dev.caulis.tiling.MasterLayout
import dev.caulis.wm.config.ConfigFile
import dev.caulis.wm.config.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

/** Layout options from config.json, and messages to the layout as actions. */
class LayoutOptionsTest {

    private val harness = CaulisTest()

    @Test
    fun `options of each layout in config_json`() {
        val read = ConfigFile.read(
            """
            { "layout": { "name": "master-stack", "ratio": 0.6, "side": "right", "masters": 2, "newWindow": "stack-top" },
              "workspaces": { "rules": [
                { "workspace": 2, "layout": { "name": "tree", "splitRatio": 0.6 } },
                { "workspace": 3, "layout": { "name": "scrolling-columns", "columnWidths": [0.5, 1.0], "newColumnWidth": 1.0, "centerFocused": true } }
              ] },
              "bind": { "Super+Equal": "layout-message ratio +0.05", "Super+M": "layout-message focus-master" } }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        val caulis = Caulis()
        Settings.apply(caulis, read.values)
        assertEquals("Master (right)", caulis.layout.name)
    }

    @Test
    fun `a message changes the arrangement, and can move the focus`() = with(harness) {
        withCaulis({ layout = MasterLayout(dev.caulis.tiling.Gaps(0, 0), ratio = 0.5) }) { caulis, client ->
            val (master) = listOf("a", "b", "c").map { id -> client.window { it.setAppId(id) }.also { it.draw() } }
            client.waitFor { caulis.onLoop { r -> r.desktop.windows.size == 3 && r.desktop.focusedWindow?.appId == "c" } }
            client.connection.roundtrip()
            val width = master.width // (as the compositor asks it to be)
            caulis.onLoop { r -> r.config.run("layout-message ratio +0.1") }
            client.waitFor { master.width > width }
            caulis.onLoop { r ->
                r.config.run("layout-message focus-master")
                assertEquals("a", r.desktop.focusedWindow?.appId)
            }
        }
    }
}
