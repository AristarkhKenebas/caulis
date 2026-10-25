package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.tiling.Columns
import dev.caulis.tiling.Gaps
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A widget of the compositor's own: room kept for it, the pointer in its units, the keyboard when clicked. */
class WidgetTest {

    private class Bar : Widget {
        val moves = ArrayList<Pair<Double, Double>>()
        val buttons = ArrayList<Boolean>()
        val typed = StringBuilder()
        var focused = false

        override fun area(monitor: Monitor) = Rect(monitor.area.x, monitor.area.y, monitor.area.width, 30)
        override fun reserve(monitor: Monitor) = Reserve(Edge.TOP, 30)
        override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: Rect) = canvas.fill(area, 1f, 0f, 0f)
        override fun pointerMoved(monitor: Monitor, x: Double, y: Double) { moves += x to y }
        override fun pointerButton(button: Int, pressed: Boolean) { buttons += pressed }
        override val takesKeyboard get() = true
        override val cursor get() = "pointer"
        override fun key(key: WidgetKey) { if (key.pressed) key.text?.let(typed::append) }
        override fun keyboardFocus(has: Boolean) { focused = has }
    }

    @Test
    fun `a bar widget keeps room, gets the pointer and the keyboard, a window takes it back`() = withServer { server, client ->
        val bar = Bar()
        server.onServer { d ->
            d.layout = Columns(Gaps(0, 0))
            d.animationMillis = 0
            d.addWidget(bar)
            assertEquals(30, d.monitors.first().usableArea.y, "room kept at the top")
        }
        client.window().draw()
        client.waitFor { server.onServer { d -> d.windows.singleOrNull()?.y } == 30 }
        assertEquals(0xFF0000, server.screenshot()[10 * ScreenTestServer.WIDTH + 10] and 0xFFFFFF, "drawn (red)")
        server.onServer { d ->
            d.pointerMoved(0, 100.0, 10.0)
            assertEquals(100.0 to 10.0, bar.moves.last(), "in its own units")
            assertEquals("pointer", d.cursorShape)
            assertNull(d.pointerWindow, "no program has the pointer over it")
            d.pointerButton(0, BTN_LEFT, true)
            d.pointerButton(0, BTN_LEFT, false)
            assertEquals(listOf(true, false), bar.buttons)
            assertSame(bar, d.keyboardWidget)
            assertTrue(bar.focused)
            d.key(0, KEY_A, true)
            d.key(0, KEY_A, false)
            assertEquals("a", bar.typed.toString())
            // A click on the window gives it the keyboard back.
            d.pointerMoved(0, 100.0, 200.0)
            d.pointerButton(0, BTN_LEFT, true)
            d.pointerButton(0, BTN_LEFT, false)
            assertNull(d.keyboardWidget)
            assertTrue(!bar.focused)
        }
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val BTN_LEFT = 0x110
        const val KEY_A = 30
    }
}
