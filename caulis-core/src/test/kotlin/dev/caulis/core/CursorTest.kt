package dev.caulis.core

import dev.caulis.core.cursor.CursorTheme
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.ConnectionException
import dev.caulis.protocol.client.cursorshapev1.WpCursorShapeDeviceV1
import dev.caulis.protocol.client.cursorshapev1.WpCursorShapeManagerV1
import dev.caulis.protocol.client.wayland.WlPointer
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.protocol.client.wayland.WlSurface
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The cursor: drawn from the theme, chosen by the program under it (cursor-shape-v1), hidden while typing. */
class CursorTest {

    private val width = ScreenTestServer.WIDTH

    // A theme with a red arrow ("left_ptr") and a blue text cursor ("xterm"), 24 px, hotspot at 0, 0.
    private fun testTheme(root: Path): CursorTheme {
        fun cursor(name: String, color: Int) {
            val side = 24
            val data = ByteBuffer.allocate(16 + 12 + 36 + side * side * 4).order(ByteOrder.LITTLE_ENDIAN)
            data.putInt(0x72756358).putInt(16).putInt(0x10000).putInt(1)
            data.putInt(0xfffd0002.toInt()).putInt(side).putInt(28)
            data.putInt(36).putInt(0xfffd0002.toInt()).putInt(side).putInt(1).putInt(side).putInt(side).putInt(0).putInt(0).putInt(0)
            repeat(side * side) { data.putInt(color) }
            Files.createDirectories(root.resolve("Test/cursors"))
            Files.write(root.resolve("Test/cursors/$name"), data.array())
        }
        cursor("left_ptr", RED)
        cursor("xterm", BLUE)
        return CursorTheme.load("Test", 24, listOf(root))
    }

    // The client's pointer, with the serial of the last enter.
    private class Pointer(val pointer: WlPointer) {
        @Volatile var enteredSerial = -1
        @Volatile var entered: WlSurface? = null
    }

    private fun TestClient.pointer(): Pointer {
        val pointer = Pointer(seat.getPointer())
        pointer.pointer.events = object : WlPointer.Events {
            override fun enter(proxy: WlPointer, serial: Int, surface: WlSurface, surfaceX: Double, surfaceY: Double) {
                pointer.enteredSerial = serial
                pointer.entered = surface
            }
        }
        return pointer
    }

    private fun TestClient.cursorShapes(): WpCursorShapeManagerV1 {
        var found = -1
        val registry = connection.display.getRegistry()
        registry.events = object : WlRegistry.Events {
            override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                if (`interface` == "wp_cursor_shape_manager_v1") found = name
            }
        }
        connection.roundtrip()
        check(found >= 0) { "no cursor-shape global" }
        return registry.bind(found, WpCursorShapeManagerV1, 2)
    }

    @Test
    fun `the program under the pointer chooses the cursor, by name`() = withServer { server, client, root ->
        server.onServer { it.cursorTheme = testTheme(root) }
        val pointer = client.pointer()
        val device = client.cursorShapes().getPointer(pointer.pointer)
        val window = client.window()
        window.draw(200, 200)
        client.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        val rect = server.onServer { d -> d.screenRect(d.windows.first())!! }

        // Over the window, which asks for the text cursor.
        server.onServer { it.pointerMoved(0, rect.x + 50.0, rect.y + 50.0) }
        client.waitFor { pointer.entered != null }
        device.setShape(pointer.enteredSerial, WpCursorShapeDeviceV1.Shape.TEXT)
        client.waitFor { server.onServer { it.cursorShape } == "text" }
        assertEquals(BLUE, server.screenshot().at(rect.x + 52, rect.y + 52), "the theme's text cursor")

        // Over nothing: the usual arrow.
        server.onServer { it.pointerMoved(0, 5.0, 5.0) }
        assertEquals(RED, server.screenshot().at(7, 7), "the theme's arrow")
    }

    @Test
    fun `the cursor makes way while typing and comes back with the mouse`() = withServer { server, client, root ->
        server.onServer {
            it.cursorTheme = testTheme(root)
            it.hideCursorWhileTyping = true
        }
        client.window().draw(200, 200)
        client.waitFor { server.onServer { d -> d.keyboardFocus != null } }
        server.onServer { it.pointerMoved(0, 5.0, 5.0) }
        assertEquals(RED, server.screenshot().at(7, 7))

        server.onServer { it.key(0, 30, true) } // "a"
        assertTrue(server.screenshot().at(7, 7) != RED, "hidden while typing")
        server.onServer { it.key(0, 30, false) }

        server.onServer { it.pointerMoved(0, 6.0, 6.0) }
        assertEquals(RED, server.screenshot().at(8, 8), "back when the mouse moves")
    }

    @Test
    fun `an X11 move request needs the button held in a window of its own client`() = withServer { server, client, _ ->
        client.window().draw(200, 200)
        client.waitFor { server.onServer { d -> d.windows.isNotEmpty() } }
        TestClient(Connection.connect(server.socketPath)).use { other ->
            other.window().draw(200, 200)
            other.waitFor { server.onServer { d -> d.windows.size == 2 } }
            val (mine, theirs) = server.onServer { d -> d.windows[0] to d.windows[1] }
            // The button held in the other program's window: the drag is not taken over.
            server.onServer { d ->
                val r = d.screenRect(theirs)!!
                d.pointerMoved(0, r.x + 10.0, r.y + 10.0)
                d.pointerButton(0, BTN_LEFT, true)
                d.x11DragRequested(mine, null)
            }
            assertTrue(server.onServer { it.cursorShape } != "grabbing", "refused")
            server.onServer { d -> d.pointerButton(0, BTN_LEFT, false) }
            // Held in its own window: it moves.
            server.onServer { d ->
                val r = d.screenRect(mine)!!
                d.pointerMoved(0, r.x + 10.0, r.y + 10.0)
                d.pointerButton(0, BTN_LEFT, true)
                d.x11DragRequested(mine, null)
            }
            assertEquals("grabbing", server.onServer { it.cursorShape })
            server.onServer { d -> d.pointerButton(0, BTN_LEFT, false) }
        }
    }

    @Test
    fun `moving and resizing a window show the compositor's own cursors`() = withServer { server, client, _ ->
        client.window().draw(200, 200)
        client.waitFor { server.onServer { d -> d.windows.isNotEmpty() } }
        server.onServer { d ->
            d.pointerButton(0, BTN_LEFT, true, passToApps = false)
            d.beginMove(d.windows.first())
        }
        assertEquals("grabbing", server.onServer { it.cursorShape })
        server.onServer { d ->
            d.pointerButton(0, BTN_LEFT, false)
            d.pointerButton(0, BTN_RIGHT, true, passToApps = false)
            d.beginResize(d.windows.first(), XdgToplevel.ResizeEdge.BOTTOM_RIGHT)
        }
        assertEquals("se-resize", server.onServer { it.cursorShape })
        server.onServer { it.pointerButton(0, BTN_RIGHT, false) }
        assertTrue(server.onServer { it.cursorShape } != "se-resize", "back to normal after letting go")
    }

    @Test
    fun `an unknown cursor shape is a protocol error`() = withServer { _, client, _ ->
        val pointer = client.pointer()
        val device = client.cursorShapes().getPointer(pointer.pointer)
        device.setShape(0, 999)
        val error = assertFailsWith<ConnectionException> { client.connection.roundtrip() }
        assertTrue(error.message!!.contains("999"), error.message)
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * width + x]

    private fun withServer(test: (ScreenTestServer, TestClient, Path) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        val root = Files.createTempDirectory("cursor-test")
        try {
            ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it, root) } }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
    }
}
