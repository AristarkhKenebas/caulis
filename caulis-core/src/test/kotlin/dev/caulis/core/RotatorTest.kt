package dev.caulis.core

import dev.caulis.render.assumeGpu
import dev.caulis.render.gles.GlesRenderer
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals

/** A turned monitor: the desktop's picture lands on the screen turned the right way. */
class RotatorTest {

    // The screen (200x100) after drawing a picture whose top left quarter is red, turned by [transform].
    private fun screen(transform: Transform, check: (pixel: (Int, Int) -> Int) -> Unit) {
        assumeGpu()
        GlesRenderer.create().use { renderer ->
            val screen = renderer.createTarget(200, 100)
            Rotator(renderer).use { rotator ->
                rotator.wrap(transform) { picture, _ ->
                    renderer.begin(picture, 0f, 0f, 0f)
                    renderer.drawColor(0, 0, picture.width / 2, picture.height / 2, 1f, 0f, 0f)
                    renderer.end()
                }(screen, 0)
            }
            renderer.finish()
            Arena.ofConfined().use { arena ->
                val pixels = arena.allocate(200L * 100 * 4)
                screen.readPixels(pixels, 200 * 4)
                check { x, y -> pixels.getAtIndex(ValueLayout.JAVA_INT, y * 200L + x) or 0xFF000000.toInt() }
            }
            screen.close()
        }
    }

    @Test
    fun `turned 90 degrees clockwise, the top left goes to the top right`() = screen(Transform.ROTATE_90) { pixel ->
        assertEquals(RED, pixel(175, 25), "top right")
        assertEquals(BLACK, pixel(25, 25), "not top left")
        assertEquals(BLACK, pixel(175, 75), "not bottom right")
    }

    @Test
    fun `turned 180 degrees, to the bottom right`() = screen(Transform.ROTATE_180) { pixel ->
        assertEquals(RED, pixel(150, 75))
        assertEquals(BLACK, pixel(50, 25))
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
