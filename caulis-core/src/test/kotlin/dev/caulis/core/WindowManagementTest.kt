package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Maximize, fullscreen, minimize, moving and resizing — as apps and users trigger them. */
class WindowManagementTest {

    private val screenWidth = ScreenTestServer.WIDTH
    private val screenHeight = ScreenTestServer.HEIGHT

    @Test
    fun `maximize fills the screen, restore brings the old size back`() = withServer { server, client ->
        val window = client.window()
        window.draw(200, 100)
        client.waitFor { server.onServer { it.windows.size } == 1 }

        window.toplevel.setMaximized()
        client.waitFor { XdgToplevel.State.MAXIMIZED in window.states }
        assertEquals(screenWidth to screenHeight, window.width to window.height)
        window.draw()
        client.waitFor { server.onServer { d -> d.windows.single().let { it.x to it.y } } == (0 to 0) }

        window.toplevel.unsetMaximized()
        client.waitFor { XdgToplevel.State.MAXIMIZED !in window.states }
        assertEquals(200 to 100, window.width to window.height)
    }

    @Test
    fun `a window pressed in keeps the pointer outside it until the button is let go`() = withServer { server, client ->
        server.onServer { d ->
            d.layout = dev.caulis.tiling.Columns(dev.caulis.tiling.Gaps(0, 0))
            d.animationMillis = 0
        }
        val left = client.window()
        left.draw()
        val right = client.window()
        right.draw()
        client.waitFor { server.onServer { d -> d.windows.size == 2 && d.windows.all { it.x >= 0 } } }
        client.waitFor { server.onServer { d -> d.windows.map { it.x }.toSet().size } == 2 }
        // Each drawn at the size of its column (the first was drawn before the screen was shared).
        client.waitFor { left.width in 1 until 640 && right.width in 1 until 640 }
        left.draw()
        right.draw()
        client.waitFor { server.onServer { d -> d.windows.all { it.visibleRect.width < 640 } } }
        server.onServer { d ->
            val (a, b) = d.windows.sortedBy { it.x }
            d.pointerMoved(0, a.x + 20.0, a.y + 20.0)
            assertEquals(a, d.pointerWindow)
            d.pointerButton(0, BTN_LEFT, true)
            d.pointerMoved(0, b.x + 20.0, b.y + 20.0) // selecting past the edge
            assertEquals(a, d.pointerWindow, "the window the press was in keeps it")
            d.pointerButton(0, BTN_LEFT, false)
            assertEquals(b, d.pointerWindow, "let go: the one under the pointer gets it")
        }
    }

    @Test
    fun `an app can start maximized`() = withServer { _, client ->
        val window = client.window { toplevel -> toplevel.setMaximized() }
        assertTrue(XdgToplevel.State.MAXIMIZED in window.states, "the first configure already says so")
        assertEquals(screenWidth to screenHeight, window.width to window.height)
    }

    @Test
    fun `fullscreen and back`() = withServer { _, client ->
        val window = client.window()
        window.draw(120, 80)
        window.toplevel.setFullscreen(null)
        client.waitFor { XdgToplevel.State.FULLSCREEN in window.states }
        assertEquals(screenWidth to screenHeight, window.width to window.height)
        window.toplevel.unsetFullscreen()
        client.waitFor { XdgToplevel.State.FULLSCREEN !in window.states }
        assertEquals(120 to 80, window.width to window.height)
    }

    @Test
    fun `minimize hides the window, cycling brings it back`() = withServer { server, client ->
        val window = client.window()
        window.draw()
        client.waitFor { server.onServer { it.windows.size } == 1 }
        window.toplevel.setMinimized()
        client.waitFor { server.onServer { it.minimized.size } == 1 }
        assertTrue(server.onServer { it.windows.isEmpty() })
        server.onServer { it.cycleFocus() }
        assertEquals(1, server.onServer { it.windows.size })
        assertFalse(server.onServer { it.minimized.isNotEmpty() })
    }

    @Test
    fun `dragging the title bar moves the window`() = withServer { server, client ->
        val window = client.window()
        window.draw(100, 100)
        client.waitFor { server.onServer { it.windows.size } == 1 }
        val (startX, startY) = server.onServer { d -> d.windows.single().let { it.x to it.y } }

        // The user presses the button on the window; the app answers with "move".
        server.onServer { d ->
            d.pointerMoved(0, startX + 10.0, startY + 10.0)
            d.pointerButton(0, BTN_LEFT, true)
        }
        window.toplevel.move(client.seat, 0)
        client.connection.roundtrip()
        server.onServer { d ->
            d.pointerMoved(0, startX + 110.0, startY + 60.0)
            d.pointerButton(0, BTN_LEFT, false)
        }
        assertEquals(startX + 100 to startY + 50, server.onServer { d -> d.windows.single().let { it.x to it.y } })
    }

    @Test
    fun `dragging an edge resizes the window`() = withServer { server, client ->
        val window = client.window()
        window.draw(100, 100)
        client.waitFor { server.onServer { it.windows.size } == 1 }
        val (startX, startY) = server.onServer { d -> d.windows.single().let { it.x to it.y } }
        server.onServer { d ->
            d.pointerMoved(0, startX + 99.0, startY + 99.0)
            d.pointerButton(0, BTN_LEFT, true)
        }
        window.toplevel.resize(client.seat, 0, XdgToplevel.ResizeEdge.BOTTOM_RIGHT)
        client.connection.roundtrip()
        server.onServer { d -> d.pointerMoved(0, startX + 149.0, startY + 129.0) }
        client.waitFor { window.width == 150 && window.height == 130 }
        assertTrue(XdgToplevel.State.RESIZING in window.states)
        server.onServer { d -> d.pointerButton(0, BTN_LEFT, false) }
        client.waitFor { XdgToplevel.State.RESIZING !in window.states }
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val BTN_LEFT = 0x110
    }
}
