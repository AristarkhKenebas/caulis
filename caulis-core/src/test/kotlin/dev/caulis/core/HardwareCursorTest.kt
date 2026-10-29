package dev.caulis.core

import dev.caulis.render.GpuTarget
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The cursor on a monitor's cursor plane: moving it needs no frames; when it cannot be there, frames have it. */
class HardwareCursorTest {

    // A pretend cursor plane: remembers what it was told, keeps its picture.
    private class Plane(override val width: Int, override val height: Int, val picture: GpuTarget) : HardwareCursor {
        var at: Pair<Int, Int>? = null
        var drawn = 0
        var hidden = 0

        override fun show(x: Int, y: Int, draw: ((GpuTarget) -> Unit)?): Boolean {
            draw?.let { it(picture); drawn++ }
            at = x to y
            return true
        }

        override fun hide() {
            at = null
            hidden++
        }
    }

    private fun withDesktop(test: (ScreenTestServer, Desktop, DesktopView, Plane, GpuTarget) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            val (view, plane, frame) = server.onServer { d ->
                val renderer = d.javaClass.getDeclaredField("renderer").apply { isAccessible = true }.get(d) as dev.caulis.render.Renderer
                val plane = Plane(64, 64, renderer.createTarget(64, 64))
                val view = d.createView(d.monitors[0]).also { it.hardwareCursor = plane }
                Triple(view, plane, renderer.createTarget(ScreenTestServer.WIDTH, ScreenTestServer.HEIGHT))
            }
            server.onServer { d -> test(server, d, view, plane, frame) }
        }
    }

    // A frame of [view] into [frame]; true if anything was drawn.
    private fun Desktop.frame(view: DesktopView, frame: GpuTarget): Boolean = refresh(view, { draw -> draw(frame, 1) })

    @Test
    fun `the pointer moves on the plane, without frames`() = withDesktop { _, d, view, plane, frame ->
        d.pointerMoved(0, 100.0, 100.0)
        d.frame(view, frame)
        assertTrue(plane.drawn == 1 && plane.at != null, "the cursor is on the plane")
        d.frame(view, frame)
        d.pointerMoved(0, 140.0, 120.0)
        assertFalse(d.frame(view, frame), "moving it draws no frame")
        assertEquals(1, plane.drawn, "nor a new cursor picture")
        val (x, y) = plane.at!!
        assertTrue(x in 120..140 && y in 100..120, "it is where the pointer is (its hotspot): $x, $y")
        // The plane has the cursor's picture.
        Arena.ofConfined().use { arena ->
            val pixels = arena.allocate(64L * 64 * 4)
            plane.picture.readPixels(pixels, 64 * 4)
            assertTrue((0 until 64 * 64).any { pixels.getAtIndex(ValueLayout.JAVA_INT, it.toLong()) ushr 24 != 0 }, "something drawn in it")
        }
    }

    @Test
    fun `the plane follows the pointer at once, where the next frame puts it too`() = withDesktop { _, d, view, plane, frame ->
        d.pointerMoved(0, 100.0, 100.0)
        d.frame(view, frame)
        val first = plane.at!!
        d.pointerMoved(0, 140.5, 120.0)
        val quick = plane.at!!
        assertEquals(first.first + 40 + 1, quick.first, "moved before any frame: $first -> $quick")
        assertEquals(first.second + 20, quick.second)
        d.frame(view, frame)
        assertEquals(quick, plane.at, "the frame puts it in the same place")
        // Off: it waits for the frame.
        d.quickCursorMoves = false
        d.pointerMoved(0, 160.0, 130.0)
        assertEquals(quick, plane.at)
        d.frame(view, frame)
        assertTrue(plane.at != quick)
    }

    @Test
    fun `a cursor too big for the plane, or a turned monitor, goes into the frames`() = withDesktop { _, d, view, plane, frame ->
        d.pointerMoved(0, 100.0, 100.0)
        d.frame(view, frame)
        assertTrue(plane.at != null)
        d.cursorTheme = dev.caulis.core.cursor.CursorTheme.load(size = 96)
        d.frame(view, frame)
        assertEquals(null, plane.at, "too big: off the plane")
        assertTrue(plane.hidden > 0)
        d.cursorTheme = dev.caulis.core.cursor.CursorTheme.load(size = 24)
        d.frame(view, frame)
        assertTrue(plane.at != null, "back on it")
        d.setTransform(d.monitors[0], Transform.ROTATE_90)
        d.frame(view, frame)
        assertEquals(null, plane.at, "turned monitor: in the frames")
    }
}
