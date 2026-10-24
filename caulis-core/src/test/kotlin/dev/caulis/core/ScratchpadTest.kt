package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import dev.caulis.tiling.Side
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Special workspaces: shown and hidden with one key, above any workspace. */
class ScratchpadTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    @Test
    fun `a drop-down window slides in at its edge with the right size from the start`() = withServer { server, client ->
        server.onServer { it.addScratchpad("term", Scratchpad.Edge(Side.TOP, depth = 0.4, length = 0.6)) { w -> w.appId == "dropdown" } }
        val normal = client.window { it.setTitle("normal") }.also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 1 }

        val drop = client.window { it.setAppId("dropdown") }
        assertEquals((width * 0.6).toInt() to (height * 0.4).toInt(), drop.width to drop.height, "its size before the first frame")
        drop.draw()
        client.waitFor { server.onServer { d -> d.scratchpad("term")!!.shown } }
        assertEquals(Rect((width - 384) / 2, 0, 384, 192), server.onServer { d -> d.scratchpad("term")!!.windows.single().let { Rect(it.x, it.y, 384, 192) } })
        assertEquals("dropdown", server.onServer { d -> d.focusedWindow?.appId }, "it has the keyboard")
        assertEquals(1, server.onServer { d -> d.windows.size }, "it is not on the workspace")

        // Hide: it slides out; the keyboard goes back.
        server.onServer { d -> d.toggleScratchpad("term") }
        assertEquals("normal", server.onServer { d -> d.focusedWindow?.title })
        client.waitFor { !server.onServer { d -> d.scratchpad("term")!!.shown } }

        // On another workspace it comes back the same way.
        server.onServer { d -> d.switchTo(3) }
        assertTrue(server.onServer { d -> d.toggleScratchpad("term") })
        assertTrue(server.onServer { d -> d.scratchpad("term")!!.shown })
        normal.draw()
    }

    @Test
    fun `an overlay is a workspace of its own above the current one, a click outside hides it`() = withServer { server, client ->
        server.onServer { it.addScratchpad("special", Scratchpad.Overlay(Dwindle(Gaps(inner = 0, outer = 40)))) { w -> w.title.startsWith("special") } }
        val a = client.window { it.setTitle("special a") }
        assertEquals(width - 80 to height - 80, a.width to a.height)
        a.draw()
        client.waitFor { server.onServer { d -> d.scratchpad("special")!!.shown } }
        val b = client.window { it.setTitle("special b") }
        assertEquals((width - 80) / 2 to height - 80, b.width to b.height, "its own layout (Dwindle)")
        assertTrue(XdgToplevel.State.TILED_LEFT in b.states)
        b.draw()
        client.waitFor { a.width == (width - 80) / 2 }

        // A click on the dimmed edge around it hides it.
        server.onServer { d ->
            d.pointerMoved(0, 10.0, 10.0)
            d.pointerButton(0, 0x110, true)
            d.pointerButton(0, 0x110, false)
        }
        client.waitFor { !server.onServer { d -> d.scratchpad("special")!!.shown } }
        assertTrue(server.onServer { d -> d.windows.isEmpty() }, "its windows were never on the workspace")
    }

    @Test
    fun `a window can be moved into a scratchpad`() = withServer { server, client ->
        server.onServer { it.addScratchpad("parked", Scratchpad.Center(0.5, 0.5)) { false } }
        val w = client.window().also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 1 }
        server.onServer { d -> d.moveToScratchpad(d.windows.single(), "parked") }
        client.waitFor { w.width == width / 2 && w.height == height / 2 }
        assertTrue(server.onServer { d -> d.windows.isEmpty() })
        assertFalse(server.onServer { d -> d.scratchpad("parked")!!.windows.isEmpty() })
        w.draw()
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }
}
