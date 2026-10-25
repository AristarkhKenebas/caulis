package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.tiling.Layout
import dev.caulis.tiling.LayoutWindow
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Your effects, title bar rule, curve and layout throwing: logged, and the desktop keeps drawing. */
class BrokenUserCodeTest {

    @Test
    fun `every frame still comes when your code throws while drawing`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { client ->
                server.onServer { d ->
                    d.windowEffects = { error("effects") }
                    d.layerEffects = { throw StackOverflowError() }
                    d.titleBarsFor = { error("title bars") }
                    d.floatRule = { error("float rule") }
                    d.animations = Animations(curve = Curve { error("curve") })
                    d.layout = object : Layout {
                        override val name = "broken"
                        override fun arrange(windows: List<LayoutWindow>, area: dev.caulis.geometry.Rect, focused: Int): List<dev.caulis.geometry.Rect> = error("layout")
                    }
                }
                val window = client.window()
                window.draw()
                client.waitFor { server.onServer { it.windows.size } == 1 }
                val drawn = server.framesDrawn
                window.draw(100, 80)
                client.waitFor { server.framesDrawn > drawn }
                val area = server.onServer { d -> d.monitors[0].usableArea }
                assertEquals(area.width to area.height, window.width to window.height, "the broken layout: the whole area meanwhile")
                server.onServer { d -> assertTrue(d.windows.single().mapped) }
            }
        }
    }
}
