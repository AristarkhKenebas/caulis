package dev.caulis.xwayland

import dev.caulis.core.ScreenTestServer
import dev.caulis.core.TestClient
import dev.caulis.core.TestClipboard
import dev.caulis.ffi.UnixSocket
import dev.caulis.ffi.c.xcb.Xcb
import dev.caulis.ffi.c.xcb.xcb_property_notify_event_t
import dev.caulis.ffi.c.xcb.xcb_selection_notify_event_t
import dev.caulis.ffi.c.xcb.xcb_selection_request_event_t
import dev.caulis.protocol.client.Connection
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** X11 programs in a real Xwayland: their windows, scaled screens, the clipboard both ways. */
class XwaylandTest {

    @Test
    fun `an X11 window is a desktop window with its picture`() = withX { server, xwayland, _ ->
        XProgram(xwayland.displayName).use { program ->
            val window = program.window("caulis test", RED)
            val shown = waitFor { server.onServer { d -> d.windows.firstOrNull { it.title == "caulis test" } } }
            assertTrue(shown is XWindow, "an X window: $shown")
            assertEquals("caulis test", shown.title)
            // It is drawn where the desktop has it, in the colour it painted itself.
            val place = waitFor { server.onServer { d -> d.screenRect(shown)?.let(d::toPixels) } }
            waitFor { server.screenshot()[(place.y + place.height / 2) * ScreenTestServer.WIDTH + place.x + place.width / 2].takeIf { it == RED } }
            assertTrue(program.x.geometry(window) != null)
        }
    }

    @Test
    fun `a menu shown again and again under the pointer`() = withX { server, xwayland, _ ->
        XProgram(xwayland.displayName).use { program ->
            // Xwayland makes a new surface each time; the old one must be forgotten (or Xwayland quits).
            val menu = program.window("menu", RED, x = 100, y = 100, width = 80, height = 80, menu = true)
            // (looked at up and left of the pointer: the cursor is drawn below and right of it)
            fun shownRed() = server.screenshot()[110 * ScreenTestServer.WIDTH + 110] == RED
            repeat(3) {
                program.x.map(menu)
                program.x.flush()
                waitFor { shownRed().takeIf { it } }
                server.onServer { it.pointerMoved(0, 140.0, 140.0) }
                program.x.unmap(menu)
                program.x.flush()
                waitFor { (!shownRed()).takeIf { it } }
                server.onServer { it.pointerMoved(0, 141.0, 141.0) }
            }
            assertTrue(server.onServer { xwayland.running }, "Xwayland still runs")
            assertTrue(!program.x.broken)
        }
    }

    @Test
    fun `each compositor has its own X display, even before Xwayland runs`() = withX { server, first, _ ->
        // The first one has not started Xwayland (no X11 program yet), but its number stays its own.
        val second = server.onServer { Xwayland(server.display, it, server.loop) }
        try {
            assertTrue(first.displayName != second.displayName, "${first.displayName} and ${second.displayName}")
            assertTrue(Files.readString(Path.of("/tmp/.X${first.displayName.removePrefix(":")}-lock")).trim() == ProcessHandle.current().pid().toString())
        } finally {
            server.onServer { second.close() }
        }
        assertTrue(!Files.exists(Path.of("/tmp/.X${second.displayName.removePrefix(":")}-lock")), "its lock is gone with it")
    }

    @Test
    fun `a program that starts fullscreen is fullscreen`() = withX { server, xwayland, _ ->
        XProgram(xwayland.displayName).use { program ->
            program.window("video", RED) { id ->
                program.x.setProperty(id, program.x.atom("_NET_WM_STATE"), Xcb.XCB_ATOM_ATOM(), intArrayOf(program.x.atom("_NET_WM_STATE_FULLSCREEN")))
            }
            val shown = waitFor { server.onServer { d -> d.windows.firstOrNull { it.title == "video" } } }
            waitFor { server.onServer { d -> d.screenRect(shown)?.takeIf { shown.fullscreen && it.width == d.width && it.height == d.height } } }
            Unit
        }
    }

    @Test
    fun `dialogs float, menus of any kind place themselves`() = withX { server, xwayland, _ ->
        XProgram(xwayland.displayName).use { program ->
            fun typed(type: String): (Int) -> Unit = { id ->
                program.x.setProperty(id, program.x.atom("_NET_WM_WINDOW_TYPE"), Xcb.XCB_ATOM_ATOM(), intArrayOf(program.x.atom("_NET_WM_WINDOW_TYPE_$type")))
            }
            program.window("a dialog", RED, before = typed("DIALOG"))
            val dialog = waitFor { server.onServer { d -> d.windows.firstOrNull { it.title == "a dialog" } } }
            assertTrue(!server.onServer { dialog.tiled }, "a dialog floats")
            // A menu that does not place itself by X's means but says it is one (Java, Wine do).
            program.window("a menu", RED, x = 400, y = 300, width = 60, height = 60, before = typed("POPUP_MENU"))
            waitFor { (server.screenshot()[310 * ScreenTestServer.WIDTH + 410] == RED).takeIf { it } }
            assertTrue(server.onServer { d -> d.windows.none { it.title == "a menu" } }, "not a window of the desktop")
        }
    }

    @Test
    fun `Xwayland stops when the last X11 program left and starts again with the next`() = withX { server, _, _ ->
        val xwayland = server.onServer { Xwayland(server.display, it, server.loop, idleSeconds = 1) }
        try {
            XProgram(xwayland.displayName).use { it.window("first", RED) }
            waitFor { server.onServer { (!xwayland.running).takeIf { it } } }
            XProgram(xwayland.displayName).use { program ->
                program.window("second", RED)
                waitFor { server.onServer { d -> d.windows.firstOrNull { it.title == "second" } } }
            }
        } finally {
            server.onServer { xwayland.close() }
        }
    }

    @Test
    fun `on a scaled screen X11 windows are sized in real pixels`() = withX(scale = 2.0) { server, xwayland, _ ->
        XProgram(xwayland.displayName).use { program ->
            val window = program.window("scaled", RED)
            val shown = waitFor { server.onServer { d -> d.windows.firstOrNull { it.title == "scaled" } } }
            val pixels = waitFor { server.onServer { d -> d.screenRect(shown)?.let(d::toPixels) } }
            // X has the window as big as it is on the screen (not half as big: no tiny programs).
            val size = waitFor {
                program.pump()
                program.x.geometry(window)?.takeIf { it[2] == pixels.width && it[3] == pixels.height }
            }
            assertEquals(pixels.width to pixels.height, size[2] to size[3])
            val units = server.onServer { d -> d.screenRect(shown)!! }
            assertEquals(units.width * 2, size[2], "twice as many pixels as desktop units")
        }
    }

    @Test
    fun `text copied in a Wayland program pastes in an X11 program`() = withX { _, xwayland, client ->
        val wayland = TestClipboard(client)
        XProgram(xwayland.displayName).use { program ->
            for (text in listOf("Hello from Wayland ✓".toByteArray(), bigText())) {
                wayland.copy(text)
                // (X may hear the paste before the copy: a person pastes later, the test retries)
                val pasted = waitFor { program.paste(client)?.takeIf { it.size == text.size } }
                assertContentEquals(text, pasted, "${text.size} bytes")
            }
        }
    }

    @Test
    fun `text copied in an X11 program pastes in a Wayland program`() = withX { _, xwayland, client ->
        val wayland = TestClipboard(client)
        XProgram(xwayland.displayName).use { program ->
            for (text in listOf("Hello from X11 ✓".toByteArray(), bigText())) {
                program.copy(text)
                client.waitFor { program.pump(); TestClipboard.TEXT in wayland.offered }
                val pasted = wayland.paste { program.pump() }
                assertContentEquals(text, pasted, "${text.size} bytes")
            }
        }
    }

    // More than X takes in one request: it must go in pieces (INCR).
    private fun bigText() = ByteArray(1_000_000) { ('a' + it % 26).code.toByte() }

    // ==== the test setup =======================================================================

    private fun withX(scale: Double = 1.0, test: (ScreenTestServer, Xwayland, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        assumeTrue(System.getenv("PATH").orEmpty().split(':').any { Files.isExecutable(Path.of(it, "Xwayland")) }, "Xwayland is not installed")
        ScreenTestServer().use { server ->
            if (scale != 1.0) server.setScale(scale)
            server.onServer { it.animationMillis = 0 }
            val xwayland = server.onServer { Xwayland(server.display, it, server.loop, server.clipboard) }
            try {
                TestClient(Connection.connect(server.socketPath)).use { client ->
                    // A focused Wayland window: the clipboard is offered to the focused program.
                    client.window().draw()
                    client.waitFor { server.onServer { d -> d.windows.isNotEmpty() } }
                    test(server, xwayland, client)
                }
            } finally {
                server.onServer { xwayland.close() }
            }
        }
    }

    private fun <T : Any> waitFor(value: () -> T?): T {
        val deadline = System.nanoTime() + 10_000_000_000
        while (true) {
            value()?.let { return it }
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    /** A tiny X11 program on our own XCB bindings: a window, and copy and paste. */
    private class XProgram(display: String) : AutoCloseable {
        val x = XConnection(UnixSocket.connect(Path.of("/tmp/.X11-unix/X${display.removePrefix(":")}")).fd.release())
        private val clipboard = x.atom("CLIPBOARD")
        private val targets = x.atom("TARGETS")
        private val utf8 = x.atom("UTF8_STRING")
        private val incr = x.atom("INCR")
        private val property = x.atom("TEST_PASTE")
        private val helper = x.createHelperWindow().also {
            x.changeAttributes(it, Xcb.XCB_CW_EVENT_MASK(), intArrayOf(Xcb.XCB_EVENT_MASK_PROPERTY_CHANGE()))
        }

        // A window painted [color]; a [menu] places itself and is not mapped yet; [before] runs before it is mapped.
        fun window(
            title: String, color: Int, x: Int = 0, y: Int = 0, width: Int = 300, height: Int = 200, menu: Boolean = false,
            before: (Int) -> Unit = {},
        ): Int =
            Arena.ofConfined().use { arena ->
                val id = this.x.newId()
                val values = if (menu) intArrayOf(color and 0xffffff, 1) else intArrayOf(color and 0xffffff)
                val mask = if (menu) CW_BACK_PIXEL or Xcb.XCB_CW_OVERRIDE_REDIRECT() else CW_BACK_PIXEL
                Xcb.xcb_create_window(arena, this.x.c, 0, id, this.x.root, x.toShort(), y.toShort(), width.toShort(), height.toShort(), 0,
                    Xcb.XCB_WINDOW_CLASS_INPUT_OUTPUT().toShort(), Xcb.XCB_COPY_FROM_PARENT().toInt(), mask, arena.allocateFrom(JAVA_INT, *values))
                this.x.setText(id, Xcb.XCB_ATOM_WM_NAME(), Xcb.XCB_ATOM_STRING(), title)
                before(id)
                if (!menu) this.x.map(id)
                this.x.flush()
                id
            }

        // --- copy: we own CLIPBOARD and answer requests (in pieces when big) ---------------

        private var copied: ByteArray? = null
        private class Piecewise(val requestor: Int, val property: Int, val data: ByteArray) {
            var offset = 0
            var done = false
        }
        private var piecewise: Piecewise? = null

        fun copy(data: ByteArray) {
            copied = data
            x.setSelectionOwner(helper, clipboard)
            x.flush()
        }

        // --- paste: ask the owner of CLIPBOARD, take its answer (in pieces when big) --------

        private var answered = false
        private var refused = false
        private var receivingPieces = false
        private var pasted = ByteArrayOutputStream()
        private var complete = false

        fun paste(client: TestClient): ByteArray? {
            answered = false; refused = false; receivingPieces = false; complete = false
            pasted = ByteArrayOutputStream()
            x.convertSelection(helper, clipboard, utf8, property)
            x.flush()
            client.waitFor { pump(); refused || complete }
            return if (refused) null else pasted.toByteArray()
        }

        /** Handles what X sent us. */
        fun pump() {
            while (true) {
                val event = x.poll() ?: break
                try {
                    handle(event)
                } finally {
                    x.free(event)
                }
            }
            x.flush()
        }

        private fun handle(event: MemorySegment) {
            when (event.get(JAVA_BYTE, 0).toInt() and 0x7f) {
                Xcb.XCB_SELECTION_REQUEST() -> {
                    val requestor = xcb_selection_request_event_t.requestor(event)
                    val target = xcb_selection_request_event_t.target(event)
                    val into = xcb_selection_request_event_t.property(event)
                    val data = copied
                    var ok = true
                    when {
                        data == null -> ok = false
                        target == targets -> x.setProperty(requestor, into, Xcb.XCB_ATOM_ATOM(), intArrayOf(targets, utf8))
                        target != utf8 -> ok = false
                        data.size < 65536 -> x.setBytes(requestor, into, utf8, data)
                        else -> {
                            x.changeAttributes(requestor, Xcb.XCB_CW_EVENT_MASK(), intArrayOf(Xcb.XCB_EVENT_MASK_PROPERTY_CHANGE()))
                            piecewise = Piecewise(requestor, into, data)
                            x.setProperty(requestor, into, incr, intArrayOf(data.size))
                        }
                    }
                    x.sendSelectionNotify(requestor, clipboard, target, if (ok) into else XConnection.NONE, xcb_selection_request_event_t.time(event))
                }
                Xcb.XCB_SELECTION_NOTIFY() -> {
                    if (xcb_selection_notify_event_t.property(event) == XConnection.NONE) {
                        refused = true
                        return
                    }
                    val value = x.property(helper, property, maxBytes = 1 shl 24, delete = true)
                    if (value == null) refused = true
                    else if (value.type == incr) receivingPieces = true
                    else { pasted.write(value.bytes); complete = true }
                }
                Xcb.XCB_PROPERTY_NOTIFY() -> {
                    val window = xcb_property_notify_event_t.window(event)
                    val atom = xcb_property_notify_event_t.atom(event)
                    val state = xcb_property_notify_event_t.state(event).toInt()
                    if (window == helper && atom == property && state == Xcb.XCB_PROPERTY_NEW_VALUE() && receivingPieces) {
                        val value = x.property(helper, property, maxBytes = 1 shl 24, delete = true) ?: return
                        if (value.bytes.isEmpty()) complete = true else pasted.write(value.bytes)
                    }
                    val sending = piecewise
                    if (sending != null && window == sending.requestor && atom == sending.property && state == Xcb.XCB_PROPERTY_DELETE()) {
                        if (sending.done) {
                            piecewise = null
                            return
                        }
                        val length = minOf(65536, sending.data.size - sending.offset)
                        x.setBytes(sending.requestor, sending.property, utf8, sending.data, sending.offset, length)
                        sending.offset += length
                        sending.done = length == 0
                    }
                }
            }
        }

        override fun close() = x.close()
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val CW_BACK_PIXEL = 2 // XCB_CW_BACK_PIXEL
    }
}
