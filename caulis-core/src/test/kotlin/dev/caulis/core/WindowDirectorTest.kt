package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import dev.caulis.tiling.Columns
import dev.caulis.tiling.Gaps
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A compositor placing windows itself: stretched, found there by the pointer, hidden, its requests its own. */
class WindowDirectorTest {

    private val width = ScreenTestServer.WIDTH

    // Places every window at (100, 50), twice its size, unless hidden; takes fullscreen requests.
    private class Director : WindowDirector {
        var hidden = false
        val requests = ArrayList<WindowRequest>()

        override fun initialSize(window: Window) = 80 to 60

        override fun place(window: Window, now: Long): WindowPlace? {
            if (hidden) return null
            val visible = window.visibleRect
            return WindowPlace(100.0, 50.0, visible.width * 2.0, visible.height * 2.0)
        }

        override fun requested(window: Window, request: WindowRequest, on: Boolean): Boolean {
            requests += request
            window.configure()
            return true
        }
    }

    @Test
    fun `the director's windows are drawn stretched where it says and found there`() = withServer { server, client ->
        val director = Director()
        server.onServer { d ->
            d.layout = Columns(Gaps(0, 0)) // (a layout would place it elsewhere)
            d.animationMillis = 0
            d.director = director
        }
        val window = client.window()
        assertEquals(80 to 60, window.width to window.height, "the director chose the first size")
        window.draw()
        client.waitFor { server.onServer { d -> d.windowAt(100.0 + 150, 50.0 + 110) } != null }
        server.onServer { d ->
            val shown = d.windows.single()
            assertEquals(shown, d.windowAt(105.0, 55.0), "at its top left")
            assertEquals(shown, d.windowAt(100.0 + 150, 50.0 + 110), "in the stretched part (outside its own size)")
            assertNull(d.windowAt(100.0 + 170, 50.0 + 130), "past it")
            assertEquals(100 to 50, shown.x to shown.y, "the layout did not move it")
        }
        // Drawn there too: black (the test window) inside the stretched part, not outside.
        val picture = server.screenshot()
        assertEquals(0, picture[(50 + 110) * width + 100 + 150] and 0xFFFFFF, "stretched picture")
        assertTrue(picture[(50 + 130) * width + 100 + 170] and 0xFFFFFF != 0, "not drawn past it")

        // Its fullscreen request is the director's.
        window.toplevel.setFullscreen(null)
        client.waitFor { server.onServer { director.requests.isNotEmpty() } }
        assertEquals(listOf(WindowRequest.FULLSCREEN), server.onServer { director.requests.toList() })
        assertFalse(server.onServer { d -> d.windows.single().fullscreen })
        assertTrue(XdgToplevel.State.FULLSCREEN !in window.states)

        // Hidden: not drawn and not found.
        server.onServer { d ->
            director.hidden = true
            d.requestRedraw()
        }
        client.waitFor { server.onServer { d -> d.windowAt(105.0, 55.0) } == null }
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }
}
