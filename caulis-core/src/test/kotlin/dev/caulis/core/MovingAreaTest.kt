package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.render.Color
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A wallpaper shader that says which part of it moves: kept whole, only that part drawn anew. */
class MovingAreaTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    // Time moves it only in its area, fading to nothing at the edge; a stripe pattern so that a shift or a wrong row shows.
    private val code = """
        vec4 wallpaper(vec2 at) {
            float k = movingFade(at, 0.08);
            float v = 0.5 + 0.4 * sin(at.x * 40.0 + 3.0 * time * k);
            return vec4(v, at.y, 0.5 + 0.4 * k * sin(time), 1.0);
        }
    """.trimIndent()

    private val area = MovingArea(0.25, 0.25, 0.5, 0.5)
    private val shader = WallpaperShader(code, framesPerSecond = 60).with(movingArea = area)

    @Test
    fun `the kept picture with its part drawn anew is what a whole drawing gives, at any time and along the edge`() = withServer { server ->
        server.onServer {
            it.backgroundChangeMillis = 0
            it.wallpaperShadersPaused = true
            it.wallpaperShadersClock = 1.0
            it.background = Background.shader(shader)
        }
        val first = settled(server)
        assertSame(whole(server, 1.0), first, "at 1 second")
        server.onServer { it.wallpaperShadersClock = 7.3 }
        waitFor { server.screenshot().contentEquals(first).not() }
        val second = settled(server)
        assertSame(whole(server, 7.3), second, "at 7.3 seconds, the part drawn anew over the same kept picture")
        var inside = 0
        for (y in 0 until height) for (x in 0 until width) {
            val p = second[y * width + x]
            val q = first[y * width + x]
            if (x < width / 4 - 1 || x > width * 3 / 4 + 1 || y < height / 4 - 1 || y > height * 3 / 4 + 1) {
                assertEquals(q, p, "outside the area, nothing changes with time ($x, $y)")
            } else if (p != q) {
                inside++
            }
        }
        assertTrue(inside > width * height / 16, "inside it does: $inside pixels")
        assertEquals(width * 4L * height, server.onServer { it.wallpaperShaders.bakedBytes }, "one picture of the screen's size")
    }

    @Test
    fun `no moving area, nothing kept`() = withServer { server ->
        server.onServer {
            it.backgroundChangeMillis = 0
            it.background = Background.shader(WallpaperShader(code, framesPerSecond = 60))
        }
        settled(server)
        assertEquals(0L, server.onServer { it.wallpaperShaders.bakedBytes })
    }

    @Test
    fun `a frame of its own draws only the part anew, and it goes slower at rest`() = withServer { server ->
        val parts = java.util.concurrent.CopyOnWriteArrayList<String>()
        dev.caulis.log.Log.listener = { if ("redraw" in it) parts += it }
        try {
            dev.caulis.log.Log.setLevel("render", 3)
            server.onServer {
                it.backgroundChangeMillis = 0
                it.background = Background.shader(WallpaperShader(code, framesPerSecond = 60).with(movingArea = area.copy(restFps = 5)))
            }
            Thread.sleep(600)
            parts.clear()
            val frames = server.framesDrawn
            Thread.sleep(1000)
            val drawn = server.framesDrawn - frames
            assertTrue(drawn in 3..8, "about 5 frames in a second at rest: $drawn")
            // (Each of these frames redraws only the area: a quarter of the screen.)
            val wholeScreen = "redraw ${Rect(0, 0, width, height)}"
            assertTrue(parts.isNotEmpty() && parts.none { wholeScreen in it }, parts.take(3).joinToString("\n"))
        } finally {
            dev.caulis.log.Log.listener = null
            dev.caulis.log.Log.clearLevel("render")
        }
    }

    @Test
    fun `its colour changed, drawn whole again, and drawn smaller it still fits`() = withServer { server ->
        val coloured = WallpaperShader("vec4 wallpaper(vec2 at) { return vec4(colors[0].rgb * (0.999 + 0.001 * at.x), 1.0); }").with(movingArea = area, colors = listOf(Color.hex("#ff0000")))
        server.onServer {
            it.backgroundChangeMillis = 0
            it.background = Background.shader(coloured)
        }
        waitFor { (server.screenshot()[10] shr 16 and 0xff) >= 0xfe }
        server.onServer { it.background = Background.shader(coloured.with(colors = listOf(Color.hex("#0000ff")))) }
        waitFor { blueish(server.screenshot()[10]) }
        assertTrue(blueish(server.screenshot()[height / 2 * width + width / 4 + 10]), "and in the part")
        server.onServer { it.background = Background.shader(shader.with(resolution = 0.5f)) }
        waitFor {
            server.screenshot()
            server.onServer { d -> d.wallpaperShaders.bakedBytes } == width / 2 * 4L * (height / 2)
        }
    }

    @Test
    fun `the part damaged only, or all of it if more changed`() {
        fun item(look: Any, changing: Any, at: Rect? = Rect(10, 10, 20, 20), rect: Rect = Rect(0, 0, 100, 100)) =
            DrawItem.Custom("key", rect, look, changing = changing, changingArea = at) { }
        val before = item("a", 1)
        assertEquals(listOf(Rect(10, 10, 20, 20)), changedAreas(item("a", 2), listOf(before), 0, before, listOf(before), 0))
        assertFalse(item("a", 1).differsFrom(before))
        assertTrue(item("a", 2).differsFrom(before))
        assertEquals(setOf(Rect(0, 0, 100, 100)), changedAreas(item("b", 2), listOf(before), 0, before, listOf(before), 0).toSet())
        // Under a window: no damage at all.
        val cover = DrawItem.Color("over", Rect(0, 0, 50, 50), 0f, 0f, 0f, listOf(Rect(0, 0, 50, 50)))
        val after = item("a", 2)
        assertEquals(emptyList(), changedAreas(after, listOf(after, cover), 0, before, listOf(before, cover), 0))
    }

    @Test
    fun `an area outside the picture is refused`() {
        assertTrue(runCatching { MovingArea(0.5, 0.5, 0.6, 0.1) }.isFailure)
        assertTrue(runCatching { MovingArea(0.1, 0.1, 0.0, 0.1) }.isFailure)
        val taken = runCatching { WallpaperShader(code).with(values = mapOf("movingFade" to 1f)) }
        assertTrue(taken.isFailure, "its names are Caulis's")
    }

    // The screen once two looks in a row are the same.
    private fun settled(server: ScreenTestServer): IntArray {
        var last = server.screenshot()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            Thread.sleep(80)
            val now = server.screenshot()
            if (now.contentEquals(last)) return now
            last = now
        }
        return last
    }

    // What the shader gives drawn whole, every pixel, at [seconds].
    private fun whole(server: ScreenTestServer, seconds: Double): IntArray = server.onServer { desktop ->
        val target = desktop.renderer.createTarget(width, height)
        try {
            val a = floatArrayOf(area.x.toFloat(), area.y.toFloat(), area.width.toFloat(), area.height.toFloat())
            val floats = mapOf(
                "time" to floatArrayOf(seconds.toFloat()), "size" to floatArrayOf(width.toFloat(), height.toFloat()), "alpha" to floatArrayOf(1f), "movingArea" to a, "movingSince" to floatArrayOf(0f),
            )
            val ok = desktop.renderer.drawShadedInto(target, shader.source(), floats, mapOf("movingDeclared" to 1, "movingWhole" to 1))
            assertTrue(ok, "it compiles")
            desktop.renderer.finish()
            Arena.ofConfined().use { arena ->
                val pixels = arena.allocate(width * 4L * height)
                target.readPixels(pixels, width * 4)
                IntArray(width * height) { pixels.get(JAVA_INT, it * 4L) }
            }
        } finally {
            target.close()
        }
    }

    private fun assertSame(expected: IntArray, actual: IntArray, what: String) {
        for (i in expected.indices) for (s in 0..2) {
            // (The pointer is drawn above it, in the middle.)
            if (abs(i % width - width / 2) < 40 && abs(i / width - height / 2) < 40) continue
            val e = expected[i] shr (s * 8) and 0xff
            val a = actual[i] shr (s * 8) and 0xff
            assertTrue(abs(e - a) <= 1, "$what: pixel (${i % width}, ${i / width}) is ${Integer.toHexString(actual[i])}, drawn whole ${Integer.toHexString(expected[i])}")
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(30)
        }
    }

    private fun withServer(test: (ScreenTestServer) -> Unit) {
        ScreenTestServer().use { test(it) }
    }

    private fun blueish(p: Int) = (p and 0xff) >= 0xfe && (p shr 16 and 0xff) == 0
}
