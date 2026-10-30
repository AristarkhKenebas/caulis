package dev.caulis.render

import dev.caulis.render.gles.GlesRenderer
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlin.test.Test
import kotlin.test.assertEquals

/** Draws on the real GPU (off-screen) and checks the pixels. Skipped without a GPU. */
class RenderTest {

    @Test
    fun `rectangles and textures land on the right pixels`() {
        assumeGpu()
        val renderer: Renderer = GlesRenderer.create()
        renderer.use {
            renderer.createTarget(64, 32).use { target ->
                Arena.ofConfined().use { arena ->
                    // A 2x2 texture: green everywhere (BGRA bytes = 0xAARRGGBB little-endian ints).
                    val texturePixels = arena.allocateFrom(JAVA_INT, GREEN, GREEN, GREEN, GREEN)
                    renderer.createTexture(2, 2).use { texture ->
                        texture.upload(texturePixels, 0, 2)

                        renderer.begin(target, 0f, 0f, 1f)                             // blue background
                        renderer.drawColor(0, 0, 10, 5, 1f, 0f, 0f)                     // red, top left
                        renderer.drawTexture(texture, 40, 20, 8, 8, opaque = true)     // green, bottom right

                        val frame = arena.allocate(64L * 32 * 4)
                        target.readPixels(frame, 64 * 4)
                        fun pixel(x: Int, y: Int) = frame.get(JAVA_INT, (y * 64L + x) * 4)

                        assertEquals(RED, pixel(2, 1), "red rectangle at the top left (top row first)")
                        assertEquals(BLUE, pixel(30, 10), "background")
                        assertEquals(GREEN, pixel(44, 24), "texture")
                        assertEquals(BLUE, pixel(39, 24), "texture does not spill to the left")
                    }
                }
            }
            println("GPU: ${renderer.description}")
        }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
