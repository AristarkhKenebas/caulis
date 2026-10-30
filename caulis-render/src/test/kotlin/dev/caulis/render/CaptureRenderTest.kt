package dev.caulis.render

import dev.caulis.geometry.Rect
import dev.caulis.render.gles.GlesRenderer
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlin.test.Test
import kotlin.test.assertEquals

/** What screenshots need from the renderer: parts of a picture, copies, see-through backgrounds. */
class CaptureRenderTest {

    @Test
    fun `a part of the picture is read into rows of any length`() = withRenderer { renderer ->
        renderer.createTarget(64, 32).use { target ->
            renderer.begin(target, 0f, 0f, 1f)
            renderer.drawColor(20, 10, 4, 4, 1f, 0f, 0f)
            Arena.ofConfined().use { arena ->
                val stride = 10 * 4 + 8 // longer rows than the part, as clients may want
                val part = arena.allocate(stride.toLong() * 6)
                target.readPixels(part, stride, Rect(18, 8, 10, 6))
                fun at(x: Int, y: Int) = part.get(JAVA_INT, y.toLong() * stride + x * 4L)
                assertEquals(BLUE, at(0, 0))
                assertEquals(RED, at(2, 2), "the red square at 20, 10 is at 2, 2 of the part")
                assertEquals(RED, at(5, 5))
                assertEquals(BLUE, at(6, 5))
            }
        }
    }

    @Test
    fun `a part is copied on the GPU and the background can be see-through`() = withRenderer { renderer ->
        renderer.createTarget(64, 32).use { source ->
            renderer.createTarget(16, 16).use { copy ->
                renderer.begin(source, 0f, 0f, 0f, alpha = 0f)
                renderer.drawColor(40, 0, 16, 16, 0f, 1f, 0f)
                renderer.copy(source, Rect(32, 0, 16, 16), copy)
                renderer.finish()
                Arena.ofConfined().use { arena ->
                    val pixels = arena.allocate(16L * 16 * 4)
                    copy.readPixels(pixels, 16 * 4)
                    assertEquals(0, pixels.get(JAVA_INT, 0), "see-through where nothing was drawn")
                    assertEquals(GREEN, pixels.get(JAVA_INT, (5 * 16 + 10) * 4L), "the green part")
                }
            }
        }
    }

    private fun withRenderer(test: (Renderer) -> Unit) {
        assumeGpu()
        GlesRenderer.create().use(test)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
