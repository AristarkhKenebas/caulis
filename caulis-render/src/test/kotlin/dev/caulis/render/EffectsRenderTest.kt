package dev.caulis.render

import dev.caulis.geometry.Rect
import dev.caulis.render.gles.GlesRenderer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rounded corners, borders, shadows and blur on the real GPU. Skipped without a GPU. */
class EffectsRenderTest {

    @Test
    fun `rounded corners cut the picture's corners`() = draw({ renderer, texture ->
        renderer.drawTexture(texture, 10, 10, 40, 40, opaque = true, clip = Rect(10, 10, 40, 40), cornerRadius = 12f)
    }) {
        assertEquals(BLUE, pixel(10, 10), "the very corner is cut away")
        assertEquals(BLUE, pixel(49, 49), "all corners")
        assertEquals(GREEN, pixel(30, 30), "the middle is drawn")
        assertEquals(GREEN, pixel(30, 10), "the edges between the corners are drawn")
    }

    @Test
    fun `a border is only a frame`() = draw({ renderer, _ ->
        renderer.drawRoundedRect(Rect(10, 10, 40, 40), 8f, Color(1f, 0f, 0f), borderWidth = 3f)
    }) {
        assertEquals(RED, pixel(30, 11), "on the frame")
        assertEquals(BLUE, pixel(30, 30), "inside the frame")
        assertEquals(BLUE, pixel(10, 10), "rounded")
    }

    @Test
    fun `a shadow fades out around the box and not inside the cut out`() = draw({ renderer, _ ->
        val box = Rect(20, 20, 24, 24)
        renderer.drawShadow(box, 4f, 10f, Color.BLACK, cutOut = box)
    }) {
        val near = blue(pixel(32, 46))
        val far = blue(pixel(32, 53))
        assertTrue(near < 200, "darker right next to the box ($near)")
        assertTrue(near < far, "lighter further away ($near, $far)")
        assertEquals(255, blue(pixel(32, 63)), "gone beyond the softness")
        assertEquals(BLUE, pixel(32, 32), "nothing under the window casting it")
    }

    @Test
    fun `blur mixes what is below and stays inside its area`() = draw({ renderer, _ ->
        // Hard stripes: red left, blue right of x = 32.
        renderer.drawColor(0, 0, 32, 64, 1f, 0f, 0f)
        renderer.drawBlur(Rect(16, 16, 32, 32), 6f, size = 3, passes = 2)
    }) {
        val left = pixel(30, 32)
        val right = pixel(34, 32)
        assertTrue(red(left) in 30..250 && blue(left) in 5..230, "mixed left of the edge: ${hex(left)}")
        assertTrue(red(right) in 5..230 && blue(right) in 30..250, "mixed right of the edge: ${hex(right)}")
        assertEquals(RED, pixel(10, 32), "outside the area nothing changes")
        assertEquals(BLUE, pixel(54, 32), "outside the area nothing changes")
    }

    @Test
    fun `the blurred glass is toned`() = draw({ renderer, _ ->
        renderer.drawColor(0, 0, 64, 64, 1f, 0f, 0f)
        renderer.drawBlur(Rect(16, 16, 32, 32), 0f, size = 3, passes = 2, tone = BlurTone(brightness = 0.5f))
    }) {
        assertTrue(red(pixel(32, 32)) in 120..135, "half as bright: ${hex(pixel(32, 32))}")
        assertEquals(RED, pixel(8, 8), "outside the area nothing changes")
    }

    private lateinit var frame: MemorySegment

    private fun pixel(x: Int, y: Int) = frame.get(JAVA_INT, (y * SIZE.toLong() + x) * 4)
    private fun red(pixel: Int) = (pixel shr 16) and 0xff
    private fun blue(pixel: Int) = pixel and 0xff
    private fun hex(pixel: Int) = Integer.toHexString(pixel)

    // Draws [scene] on a blue 64x64 target (a green texture at hand), reads it back, runs [checks].
    private fun draw(scene: (Renderer, GpuTexture) -> Unit, checks: () -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        GlesRenderer.create().use { renderer ->
            renderer.createTarget(SIZE, SIZE).use { target ->
                Arena.ofConfined().use { arena ->
                    renderer.createTexture(2, 2).use { texture ->
                        texture.upload(arena.allocateFrom(JAVA_INT, GREEN, GREEN, GREEN, GREEN), 0, 2)
                        renderer.begin(target, 0f, 0f, 1f)
                        frame = arena.allocate(SIZE.toLong() * SIZE * 4)
                        scene(renderer, texture)
                        target.readPixels(frame, SIZE * 4)
                        checks()
                    }
                }
            }
        }
    }

    private companion object {
        const val SIZE = 64
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
