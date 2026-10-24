package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Gaps
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

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

/** Windows glide to their new places instead of jumping. */
class GlideTest {

    @Test
    fun `switching the layout moves windows smoothly`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { client ->
                server.onServer {
                    it.animationMillis = 600
                    it.layout = dev.caulis.tiling.Columns(Gaps(0, 0))
                }
                val first = client.window().also { it.draw() }
                client.waitFor { server.onServer { d -> d.windows.size } == 1 }
                val second = client.window().also { it.draw() }
                client.waitFor { first.width == ScreenTestServer.WIDTH / 2 }
                first.draw()
                client.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::shownRect)?.width } == ScreenTestServer.WIDTH / 2 }
                Thread.sleep(700) // let the glides of the second window finish

                // Rows instead of columns: the first window goes from the left half to the top half.
                server.onServer { it.layout = dev.caulis.tiling.Rows(Gaps(0, 0)) }
                client.waitFor { first.height == ScreenTestServer.HEIGHT / 2 }
                first.draw()
                second.draw()
                client.connection.roundtrip() // send the new pictures
                Thread.sleep(250)
                val midway = server.onServer { d -> d.windows.first { it.surface.width == ScreenTestServer.WIDTH }.let(d::shownRect)!! }
                assertTrue(midway.width in (ScreenTestServer.WIDTH / 2 + 1) until ScreenTestServer.WIDTH, "between half and full width: $midway")
                Thread.sleep(600)
                val arrived = server.onServer { d -> d.windows.first { it.surface.width == ScreenTestServer.WIDTH }.let(d::shownRect)!! }
                assertEquals(ScreenTestServer.WIDTH, arrived.width)
            }
        }
    }
}

/** Layouts that remember things, with real windows. */
class RememberingDesktopTest {

    @Test
    fun `on a canvas, focusing a far window brings it into view - like activating it from a dock`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { client ->
                server.onServer {
                    it.animationMillis = 0
                    it.layout = dev.caulis.tiling.Canvas(Gaps(0, 0), columns = 2, rows = 1, windowColumns = 1, windowRows = 1)
                }
                val windows = (1..3).map { n ->
                    client.window { it.setTitle("w$n") }.also { w ->
                        w.draw()
                        client.waitFor { server.onServer { d -> d.windows.size } == n }
                    }
                }
                // Three windows of half the screen each: the third pushed the view right.
                client.waitFor { server.onServer { d -> d.windows.first { it.title == "w1" }.x } < 0 }
                server.onServer { d -> d.focus(d.windows.first { it.title == "w1" }) }
                client.waitFor { server.onServer { d -> d.windows.first { it.title == "w1" }.x } == 0 }
                // Panning away and the window stays out of view until it is focused again.
                server.onServer { d -> d.tilingCommand(dev.caulis.tiling.TilingCommand.Pan(dev.caulis.tiling.Direction.RIGHT)) }
                assertTrue(server.onServer { d -> d.windows.first { it.title == "w1" }.x } < 0)
                windows.forEach { it.draw() }
            }
        }
    }
}

/** The overview: the whole workspace zoomed out. */
class OverviewTest {

    @Test
    fun `the overview shows windows beyond the edges, a click there goes to them`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { client ->
                server.onServer {
                    it.animationMillis = 0
                    it.layout = dev.caulis.tiling.Canvas(Gaps(0, 0), columns = 2, rows = 1, windowColumns = 1, windowRows = 1)
                }
                (1..3).map { n ->
                    client.window { it.setTitle("w$n") }.also { w ->
                        w.draw()
                        client.waitFor { server.onServer { d -> d.windows.size } == n }
                    }
                }
                server.onServer { d -> d.focus(d.windows.first { it.title == "w3" }) }
                client.waitFor { server.onServer { d -> d.windows.first { it.title == "w1" }.x } < 0 }

                server.onServer { it.setOverview(true) }
                Thread.sleep(100) // a few frames
                val inOverview = server.onServer { d -> d.windows.associate { it.title to d.screenRect(it)!! } }
                val screen = Rect(0, 0, ScreenTestServer.WIDTH, ScreenTestServer.HEIGHT)
                for ((title, rect) in inOverview) assertTrue(screen.contains(rect), "$title is on screen in the overview: $rect")
                assertTrue(inOverview.getValue("w1").width < ScreenTestServer.WIDTH / 2, "zoomed out")

                // Click on the far-left window: it is focused and brought into view.
                val target = inOverview.getValue("w1")
                server.onServer { d ->
                    d.pointerMoved(0, target.x + target.width / 2.0, target.y + target.height / 2.0)
                    d.pointerButton(0, 0x110, true)
                    d.pointerButton(0, 0x110, false)
                }
                assertTrue(!server.onServer { it.inOverview })
                assertEquals("w1", server.onServer { d -> d.focusedWindow?.title })
                client.waitFor { server.onServer { d -> d.windows.first { it.title == "w1" }.x } == 0 }
            }
        }
    }
}
