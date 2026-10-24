package dev.caulis.core

import dev.caulis.core.layout.Dwindle
import dev.caulis.core.layout.Floating
import dev.caulis.core.layout.Gaps
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real windows in tiling layouts and workspaces. */
class TilingTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    @Test
    fun `a new window gets its tile size in the very first configure`() = withServer { server, client ->
        server.onServer { it.layout = Dwindle(Gaps(0, 0)) }
        val first = client.window()
        assertEquals(width to height, first.width to first.height, "alone: the whole screen")
        assertTrue(XdgToplevel.State.TILED_LEFT in first.states)
        first.draw()
        client.waitFor { server.onServer { it.windows.size } == 1 }

        val second = client.window()
        assertEquals(width / 2 to height, second.width to second.height, "second: half, before it ever drew")
        second.draw()
        client.waitFor { first.width == width / 2 }
        first.draw()
        // Both drew in their new sizes: the positions are applied together.
        client.waitFor { server.onServer { d -> d.windows.map { it.x }.sorted() } == listOf(0, width / 2) }
    }

    @Test
    fun `switching to floating gives windows their own places back`() = withServer { server, client ->
        server.onServer { it.layout = Dwindle(Gaps(0, 0)) }
        val window = client.window()
        window.draw()
        client.waitFor { server.onServer { it.windows.size } == 1 }
        server.onServer { it.layout = Floating }
        client.waitFor { XdgToplevel.State.TILED_LEFT !in window.states }
        assertTrue(window.width < width, "back to a floating size, not the whole screen")
    }

    @Test
    fun `workspaces - only the active one is on screen`() = withServer { server, client ->
        val one = client.window()
        one.draw()
        client.waitFor { server.onServer { it.windows.size } == 1 }
        server.onServer { it.switchTo(2) }
        assertTrue(server.onServer { it.windows.isEmpty() }, "workspace 2 starts empty")
        val two = client.window()
        two.draw()
        client.waitFor { server.onServer { it.windows.size } == 1 }

        server.onServer { d -> d.moveToWorkspace(d.windows.single(), 1) }
        assertTrue(server.onServer { it.windows.isEmpty() })
        server.onServer { it.switchTo(1) }
        assertEquals(2, server.onServer { it.windows.size })
        assertEquals(listOf(1), server.onServer { d -> d.allWorkspaces.map { it.number } }, "empty workspace 2 is gone")
    }

    @Test
    fun `dialogs float`() = withServer { server, client ->
        server.onServer { it.layout = Dwindle(Gaps(0, 0)) }
        val main = client.window()
        main.draw()
        client.waitFor { server.onServer { it.windows.size } == 1 }
        val dialog = client.window { toplevel -> toplevel.setParent(main.toplevel) }
        assertTrue(XdgToplevel.State.TILED_LEFT !in dialog.states)
        dialog.draw(200, 100)
        client.waitFor { server.onServer { it.windows.size } == 2 }
        assertEquals(width to height, main.width to main.height, "the main window keeps the whole screen")
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }
}
