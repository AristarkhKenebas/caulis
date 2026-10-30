package dev.caulis.core

import dev.caulis.render.assumeGpu
import dev.caulis.render.gles.GlesRenderer
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals

/** A mirror: another monitor's picture made to fit a screen of another shape, bars at the sides. */
class MirrorTest {

    @Test
    fun `a square picture on a wide screen is as tall as the screen, centred`() {
        assumeGpu()
        GlesRenderer.create().use { renderer ->
            val screen = renderer.createTarget(200, 100)
            val source = Monitor("eDP-1", null, 100, 100, 1.0)
            Mirror(renderer).use { mirror ->
                mirror.wrap(source) { picture, _ ->
                    assertEquals(100, picture.width)
                    renderer.begin(picture, 0f, 1f, 0f)
                    renderer.drawColor(0, 0, picture.width / 2, picture.height / 2, 1f, 0f, 0f)
                    renderer.end()
                }(screen, 0)
            }
            renderer.finish()
            Arena.ofConfined().use { arena ->
                val pixels = arena.allocate(200L * 100 * 4)
                screen.readPixels(pixels, 200 * 4)
                fun pixel(x: Int, y: Int) = pixels.getAtIndex(ValueLayout.JAVA_INT, y * 200L + x) or 0xFF000000.toInt()
                assertEquals(BLACK, pixel(20, 50), "a bar on the left")
                assertEquals(BLACK, pixel(180, 50), "a bar on the right")
                assertEquals(RED, pixel(70, 20), "the picture's top left quarter")
                assertEquals(GREEN, pixel(130, 80), "the rest of it")
            }
            screen.close()
        }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
