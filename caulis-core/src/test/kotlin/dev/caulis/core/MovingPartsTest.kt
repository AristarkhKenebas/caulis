package dev.caulis.core

import dev.caulis.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Small things over a wallpaper shader's picture drawn once and kept, and the seed. */
class MovingPartsTest {
    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    // The kept picture blue, one part a red square whose strength is its data, a kept one green.
    private val code = """
        vec4 wallpaper(vec2 at) {
            if (movingPart < 0) return vec4(0.0, 0.0, 0.5 + 0.4 * seed, 1.0);
            if (movingPart == 0) return vec4(movingData.x, 0.0, 0.0, movingData.x);
            return movingKept ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0);
        }
    """.trimIndent()

    private data class Parts(val strength: Float, val keptX: Double = 0.6, override val framesPerSecond: Int = 30) : MovingParts {
        override fun at(seconds: Double, aspect: Double) = listOf(
            MovingPart(1, 0.1, 0.1, 0.1, 0.1, 0, floatArrayOf(strength)),
            MovingPart(2, keptX, 0.5, 0.2, 0.2, 1, kept = true, keptScale = 0.5f),
        )
    }

    private fun show(server: ScreenTestServer, parts: MovingParts, seed: Long = 0) = server.onServer {
        it.backgroundChangeMillis = 0
        it.background = Background.shader(WallpaperShader(code).with(movingParts = parts, seed = seed))
    }

    private fun at(pixels: IntArray, x: Double, y: Double) = pixels[(y * height).toInt() * width + (x * width).toInt()]
    private fun red(p: Int) = p shr 16 and 0xff
    private fun green(p: Int) = p shr 8 and 0xff
    private fun blue(p: Int) = p and 0xff

    private fun settled(server: ScreenTestServer): IntArray {
        var last = server.screenshot()
        for (i in 0 until 100) {
            Thread.sleep(50)
            val now = server.screenshot()
            if (now.contentEquals(last)) return now
            last = now
        }
        error("it never settled")
    }

    @Test
    fun `parts are blended over the kept picture, each where it is, and moving leaves nothing behind`() = ScreenTestServer().use { server ->
        show(server, Parts(1f))
        val first = settled(server)
        assertEquals(255, red(at(first, 0.15, 0.15)), "the shaded part")
        assertEquals(255, green(at(first, 0.7, 0.6)), "the kept part")
        assertEquals(0, red(at(first, 0.4, 0.2)))
        assertTrue(blue(at(first, 0.4, 0.2)) > 100, "the kept picture around")
        val bytes = server.onServer { it.wallpaperShaders.bakedBytes }
        assertEquals(width * 4L * height, bytes)
        show(server, Parts(0.5f, keptX = 0.2))
        val second = settled(server)
        assertEquals(255, green(at(second, 0.3, 0.6)), "the kept part moved")
        assertEquals(0, green(at(second, 0.75, 0.6)), "and left nothing where it was")
    }

    @Test
    fun `only what changed is drawn anew`() {
        val state = WallpaperParts()
        val rect = Rect(0, 0, 1000, 1000)
        val one = MovingPart(1, 0.1, 0.1, 0.01, 0.01)
        val two = MovingPart(2, 0.5, 0.5, 0.01, 0.01)
        val s = state.update("k", listOf(one, two), rect)
        assertEquals(0L, s.version, "new: all of it is drawn anyway")
        state.update("k", listOf(one, two), rect)
        assertEquals(0L, s.version, "the same: nothing at all")
        val moved = MovingPart(2, 0.6, 0.5, 0.01, 0.01)
        state.update("k", listOf(one, moved), rect)
        assertEquals(1L, s.version)
        val places = s.changedSince(0L)!!
        assertEquals(2, places.size, "where it is and where it was: $places")
        assertTrue(places.all { it.width < 20 && it.height < 20 })
        assertNull(s.changedSince("something else"))
        // Two frames at once (a scene built twice before one was drawn): both.
        state.update("k", listOf(one, two), rect)
        assertEquals(4, s.changedSince(0L)!!.size)
        // One gone: its place too.
        state.update("k", listOf(two), rect)
        assertEquals(1, s.changedSince(2L)!!.size)
    }

    @Test
    fun `places that stay take turns`() {
        val parts = FixedMovingParts(listOf(listOf(0.0, 0.0, 0.1, 0.1), listOf(0.5, 0.0, 0.1, 0.1)), framesPerSecond = 10, every = 2)
        val a = parts.at(1.0, 1.6)
        val b = parts.at(1.1, 1.6)
        val c = parts.at(1.2, 1.6)
        assertEquals(a[0], b[0], "frame 11 leaves the first alone")
        assertNotEquals(a[1], b[1])
        assertNotEquals(b[0], c[0])
        assertEquals(b[1], c[1])
    }

    @Test
    fun `the seed gives another picture`() = ScreenTestServer().use { server ->
        show(server, Parts(0f), seed = 1)
        val one = settled(server)
        show(server, Parts(0f), seed = 2)
        val two = settled(server)
        assertNotEquals(blue(at(one, 0.4, 0.2)), blue(at(two, 0.4, 0.2)))
    }

    @Test
    fun `a shader of before with a seed of its own still compiles`() {
        val own = WallpaperShader("float seed = 3.0; vec4 wallpaper(vec2 at) { return vec4(seed); }").source()
        assertTrue("uniform float seed" !in own)
        assertTrue("uniform float seed" in WallpaperShader(code).source())
    }
}
