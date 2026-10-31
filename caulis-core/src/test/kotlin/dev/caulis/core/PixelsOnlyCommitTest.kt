package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wayland.WlSubcompositor
import dev.caulis.protocol.client.wayland.WlShm
import dev.caulis.protocol.client.viewporter.WpViewporter
import dev.caulis.render.Color
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import java.lang.foreign.ValueLayout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A commit of new pixels leaves the scene as it is (the desktop is not worked out again for a
 * blinking cursor). What reaches the screen, drawing only what changed, must stay to the pixel
 * what a whole redraw shows: anything else would be a stale picture.
 */
class PixelsOnlyCommitTest {

    private val width = ScreenTestServer.WIDTH

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { client ->
                server.onServer { d ->
                    d.backgroundChangeMillis = 0
                    d.background = Background.gradient(Color.hex("#102030"), Color.hex("#f0e0d0"), degrees = 30.0)
                    d.layout = Dwindle(Gaps(16, 24))
                    d.titleBars = TitleBars.NONE
                    d.effects = Effects()
                }
                test(server, client)
            }
        }
    }

    // What an honest program has drawn so far, per window: only what it says changed differs.
    private val drawnSoFar = HashMap<TestClient.TestWindow, IntArray>()

    // A new buffer of the window's size: as before, with [damage] painted [argb] (null: all of it).
    private fun commit(client: TestClient, window: TestClient.TestWindow, argb: Int, damage: List<IntArray>? = null, format: Int = WlShm.Format.XRGB8888, commit: Boolean = true) {
        val w = window.width.takeIf { it > 0 } ?: 100
        val h = window.height.takeIf { it > 0 } ?: 100
        val picture = drawnSoFar[window]?.takeIf { it.size == w * h } ?: IntArray(w * h)
        drawnSoFar[window] = picture
        for (d in damage ?: listOf(intArrayOf(0, 0, w, h))) {
            for (y in d[1].coerceAtLeast(0) until (d[1] + d[3]).coerceAtMost(h)) for (x in d[0].coerceAtLeast(0) until (d[0] + d[2]).coerceAtMost(w)) picture[y * w + x] = argb
        }
        val (buffer, memory) = client.buffer(w, h, format)
        memory.write { segment -> for (i in 0 until w * h) segment.setAtIndex(ValueLayout.JAVA_INT, i.toLong(), picture[i]) }
        window.surface.attach(buffer, 0, 0)
        if (damage == null) window.surface.damageBuffer(0, 0, w, h) else for (d in damage) window.surface.damageBuffer(d[0], d[1], d[2], d[3])
        if (commit) window.surface.commit()
    }

    // Two windows side by side, in their final sizes, on screen.
    private fun twoWindows(server: ScreenTestServer, client: TestClient): Pair<TestClient.TestWindow, TestClient.TestWindow> {
        val a = client.window()
        val b = client.window()
        commit(client, a, RED)
        commit(client, b, BLUE)
        client.waitFor { server.onServer { it.windows.size } == 2 }
        client.connection.roundtrip()
        commit(client, a, RED)
        commit(client, b, BLUE)
        settled(server, client)
        return a to b
    }

    // Until the screen has drawn all that came (no new frames for a while).
    private fun settled(server: ScreenTestServer, client: TestClient) {
        client.connection.roundtrip()
        var frames: Int
        var version: Long
        do {
            frames = server.framesDrawn
            version = server.version()
            Thread.sleep(80)
            client.connection.roundtrip()
        } while (server.framesDrawn != frames || server.version() != version)
    }

    private fun ScreenTestServer.version() = onServer { it.version }

    // The last frame shown (drawn in parts) against a whole redraw of the same.
    private fun assertScreenRight(server: ScreenTestServer, last: () -> IntArray?, what: String) {
        val shown = last() ?: error("$what: no frame shown")
        assertContentEquals(server.screenshot(), shown, "$what: the screen shows what a whole redraw shows")
    }

    @Test
    fun `new pixels - the scene stays and the screen is right`() = withServer { server, client ->
        var last: IntArray? = null
        val (a, _) = twoWindows(server, client)
        server.onFrameShown = { _, pixels -> last = pixels }
        val version = server.version()
        val frames = server.framesDrawn
        commit(client, a, GREEN, listOf(intArrayOf(10, 10, 40, 30)))
        settled(server, client)
        assertEquals(version, server.version(), "new pixels only: no new scene")
        assertTrue(server.framesDrawn > frames, "but a new frame")
        val shown = last!!
        assertTrue(GREEN in shown.asList(), "the new pixels are on screen")
        assertTrue(RED in shown.asList(), "the rest of the window stays")
        assertScreenRight(server, { last }, "part of a window")
    }

    @Test
    fun `a commit with no damage draws nothing`() = withServer { server, client ->
        val (a, _) = twoWindows(server, client)
        val version = server.version()
        val frames = server.framesDrawn
        a.surface.commit()
        settled(server, client)
        assertEquals(version, server.version())
        assertEquals(frames, server.framesDrawn)
    }

    @Test
    fun `everything but pixels makes a new scene and stays right`() = withServer { server, client ->
        var last: IntArray? = null
        val (a, b) = twoWindows(server, client)
        server.onFrameShown = { _, pixels -> last = pixels }
        fun step(what: String, change: () -> Unit) {
            val version = server.version()
            change()
            settled(server, client)
            assertTrue(server.version() != version, "$what: a new scene")
            assertScreenRight(server, { last }, what)
        }
        // Another format: see-through instead of opaque.
        step("see-through now") { commit(client, a, HALF_GREEN, format = WlShm.Format.ARGB8888) }
        step("opaque region") {
            val region = client.compositor.createRegion()
            region.add(0, 0, 50, 50)
            a.surface.setOpaqueRegion(region)
            region.destroy()
            commit(client, a, HALF_GREEN, format = WlShm.Format.ARGB8888)
        }
        step("input region") {
            val region = client.compositor.createRegion()
            region.add(0, 0, 10, 10)
            a.surface.setInputRegion(region)
            region.destroy()
            a.surface.commit()
        }
        step("window geometry") {
            a.xdgSurface.setWindowGeometry(4, 4, 90, 90)
            a.surface.commit()
        }
        step("another buffer turn") {
            b.surface.setBufferTransform(dev.caulis.protocol.client.wayland.WlOutput.Transform._180)
            commit(client, b, GREEN)
        }
        step("another size") {
            val (buffer, memory) = client.buffer(50, 60)
            memory.write { segment -> for (i in 0 until 50 * 60) segment.setAtIndex(ValueLayout.JAVA_INT, i.toLong(), BLUE) }
            b.surface.attach(buffer, 0, 0)
            b.surface.damageBuffer(0, 0, 50, 60)
            b.surface.commit()
        }
    }

    @Test
    fun `a window moved without telling, then new pixels, is shown where it is`() = withServer { server, client ->
        var last: IntArray? = null
        val (a, _) = twoWindows(server, client)
        server.onFrameShown = { _, pixels -> last = pixels }
        val version = server.version()
        // (As an X11 program moves its window: set, and the next picture shows it.)
        server.onServer { d ->
            val window = d.windows.first()
            d.setFloating(window, true)
        }
        settled(server, client)
        val moved = server.version()
        assertTrue(moved != version)
        server.onServer { d -> d.windows.first().let { it.x += 40; it.y += 30 } }
        commit(client, a, GREEN, listOf(intArrayOf(0, 0, 20, 20)))
        settled(server, client)
        assertTrue(server.version() != moved, "moved: a new scene")
        assertScreenRight(server, { last }, "moved and drawn")
    }

    @Test
    fun `a viewport changed or kept`() = withServer { server, client ->
        var last: IntArray? = null
        val (a, _) = twoWindows(server, client)
        server.onFrameShown = { _, pixels -> last = pixels }
        val viewport = client.bind("wp_viewporter", WpViewporter, 1).getViewport(a.surface)
        viewport.setSource(0.0, 0.0, 40.0, 40.0)
        viewport.setDestination(120, 120)
        commit(client, a, RED)
        settled(server, client)
        assertScreenRight(server, { last }, "a viewport")
        val version = server.version()
        // The same again, as video players do with each picture.
        viewport.setSource(0.0, 0.0, 40.0, 40.0)
        viewport.setDestination(120, 120)
        commit(client, a, GREEN)
        settled(server, client)
        assertEquals(version, server.version(), "the same viewport: pixels only")
        assertTrue(GREEN in last!!.asList())
        assertScreenRight(server, { last }, "pixels under a viewport")
    }

    @Test
    fun `a subsurface moved by its parent's commit alone`() = withServer { server, client ->
        var last: IntArray? = null
        val (a, _) = twoWindows(server, client)
        server.onFrameShown = { _, pixels -> last = pixels }
        val childSurface = client.compositor.createSurface()
        val sub = client.bind("wl_subcompositor", WlSubcompositor, 1).getSubsurface(childSurface, a.surface)
        sub.setDesync()
        sub.setPosition(10, 10)
        client.draw(childSurface, 30, 30)
        a.surface.commit()
        settled(server, client)
        assertScreenRight(server, { last }, "a new subsurface")
        // New pixels of the subsurface alone: its own commit, no new scene.
        val version = server.version()
        val (buffer, memory) = client.buffer(30, 30)
        memory.write { segment -> for (i in 0 until 900) segment.setAtIndex(ValueLayout.JAVA_INT, i.toLong(), GREEN) }
        childSurface.attach(buffer, 0, 0)
        childSurface.damageBuffer(0, 0, 30, 30)
        childSurface.commit()
        settled(server, client)
        assertEquals(version, server.version())
        assertTrue(GREEN in last!!.asList())
        assertScreenRight(server, { last }, "new pixels in a subsurface")
        // Moved: said on the subsurface, happening with the parent's commit, which draws nothing new.
        sub.setPosition(40, 50)
        a.surface.commit()
        settled(server, client)
        assertTrue(server.version() != version, "moved: a new scene")
        assertScreenRight(server, { last }, "a subsurface moved")
    }

    @Test
    fun `a new title reaches a title bar without any commit`() = withServer { server, client ->
        val (a, _) = twoWindows(server, client)
        val version = server.version()
        a.toplevel.setTitle("Another title")
        client.waitFor { server.version() != version }
    }

    @Test
    fun `whatever programs do, the screen stays what a whole redraw shows`() = withServer { server, client ->
        var last: IntArray? = null
        val (a, b) = twoWindows(server, client)
        server.onFrameShown = { _, pixels -> last = pixels }
        val seed = 20261031
        val random = Random(seed)
        val colors = intArrayOf(RED, GREEN, BLUE, WHITE)
        repeat(60) { step ->
            val window = if (random.nextBoolean()) a else b
            val w = window.width.takeIf { it > 0 } ?: 100
            val h = window.height.takeIf { it > 0 } ?: 100
            fun rect(): IntArray {
                val x = random.nextInt(w)
                val y = random.nextInt(h)
                return intArrayOf(x, y, 1 + random.nextInt(w - x), 1 + random.nextInt(h - y))
            }
            when (random.nextInt(6)) {
                // The usual: new pixels here and there.
                0, 1, 2 -> commit(client, window, colors.random(random), List(1 + random.nextInt(4)) { rect() })
                // All of it, see-through and back.
                3 -> commit(client, window, colors.random(random) and 0x7fffffff, format = if (random.nextBoolean()) WlShm.Format.ARGB8888 else WlShm.Format.XRGB8888)
                // Damage outside of the picture too (allowed).
                4 -> commit(client, window, colors.random(random), listOf(intArrayOf(w - 5, h - 5, 500, 500), intArrayOf(-10, -10, 20, 20)))
                // Told nothing changed: the buffer is new, the screen stays.
                else -> commit(client, window, colors.random(random), emptyList())
            }
            settled(server, client)
            if (last != null) assertScreenRight(server, { last }, "step $step (seed $seed)")
        }
    }

    @Test
    fun `a window under a floating one, drawn in parts, stays right`() = withServer { server, client ->
        var last: IntArray? = null
        val (a, b) = twoWindows(server, client)
        server.onFrameShown = { _, pixels -> last = pixels }
        // The other window over this one, opaque, then the lower one drawing where it is hidden and where not.
        server.onServer { d ->
            val top = d.windows.last()
            val lower = d.windows.first()
            d.setFloating(top, true)
            top.x = lower.x + 10
            top.y = lower.y + 10
            d.requestRedraw()
        }
        settled(server, client)
        for ((step, color) in listOf(GREEN, WHITE, RED).withIndex()) {
            commit(client, a, color, listOf(intArrayOf(0, 0, a.width, 40), intArrayOf(20, 20, 30, 30)))
            commit(client, b, BLUE, listOf(intArrayOf(5, 5, 10, 10)))
            settled(server, client)
            assertScreenRight(server, { last }, "step $step")
        }
    }

    @Test
    fun `only the parts said to have changed are copied, and no pixel twice`() {
        // Far apart: each on its own, not the band of rows between them.
        assertEquals(listOf(Rect(0, 0, 10, 10), Rect(90, 90, 10, 10)), partsToCopy(listOf(Rect(0, 0, 10, 10), Rect(90, 90, 10, 10)), 100, 100))
        // Overlapping: as one.
        assertEquals(listOf(Rect(0, 0, 20, 20)), partsToCopy(listOf(Rect(0, 0, 15, 15), Rect(5, 5, 15, 15)), 100, 100))
        // Outside the picture: cut, or nothing at all.
        assertEquals(listOf(Rect(95, 95, 5, 5)), partsToCopy(listOf(Rect(95, 95, 500, 500), Rect(200, 200, 5, 5)), 100, 100))
        assertEquals(emptyList(), partsToCopy(listOf(Rect(-20, -20, 5, 5)), 100, 100))
        // Many: one box around them.
        val many = List(20) { Rect(it * 5, it * 5, 2, 2) }
        assertEquals(listOf(Rect(0, 0, 97, 97)), partsToCopy(many, 100, 100))
    }

    @Test
    fun `a window drawn in parts far apart has all of them right, nothing else`() = withServer { server, client ->
        var last: IntArray? = null
        val (a, _) = twoWindows(server, client)
        server.onFrameShown = { _, pixels -> last = pixels }
        commit(client, a, GREEN, listOf(intArrayOf(5, 5, 20, 10), intArrayOf(40, 200, 30, 10), intArrayOf(100, 400, 10, 30)))
        settled(server, client)
        val shot = last!!
        assertEquals((20 * 10 + 30 * 10 + 10 * 30), shot.count { it == GREEN }, "exactly the parts")
        assertScreenRight(server, { last }, "parts far apart")
    }

    @Test
    fun `a program whose new buffer differs outside of what it says stays right too`() = withServer { server, client ->
        var last: IntArray? = null
        val (a, _) = twoWindows(server, client)
        server.onFrameShown = { _, pixels -> last = pixels }
        // All of the buffer is new, only a part is said to be: the rest is not shown.
        val w = a.width
        val h = a.height
        val (buffer, memory) = client.buffer(w, h)
        memory.write { segment -> for (i in 0 until w * h) segment.setAtIndex(ValueLayout.JAVA_INT, i.toLong(), SIGNAL) }
        a.surface.attach(buffer, 0, 0)
        a.surface.damageBuffer(10, 10, 20, 20)
        a.surface.commit()
        settled(server, client)
        assertEquals(20 * 20, last!!.count { it == SIGNAL })
        assertScreenRight(server, { last }, "more drawn than said")
    }

    private companion object {
        const val RED = 0xffff0000.toInt()
        const val GREEN = 0xff00ff00.toInt()
        const val BLUE = 0xff0000ff.toInt()
        const val SIGNAL = 0xff123456.toInt()
        const val WHITE = 0xffffffff.toInt()
        const val HALF_GREEN = 0x8000ff00.toInt()
    }
}
