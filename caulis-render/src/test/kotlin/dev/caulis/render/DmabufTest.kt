package dev.caulis.render

import dev.caulis.render.gles.GlesRenderer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** GPU buffers shared as dma-bufs: draw into one, export it, import it back. */
class DmabufTest {

    @Test
    fun `a buffer drawn on the GPU can be exported and shown again`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        GlesRenderer.create().use { renderer ->
            assertTrue(DmabufFormat(Dmabuf.XRGB8888, Dmabuf.MODIFIER_LINEAR) in renderer.dmabufFormats,
                "every GPU can read XRGB8888 in plain rows")

            renderer.createBuffer(32, 16, Dmabuf.XRGB8888, listOf(Dmabuf.MODIFIER_LINEAR), scanout = false).use { buffer ->
                renderer.begin(buffer, 1f, 0f, 0f) // fill with red

                buffer.export().use { dmabuf ->
                    assertEquals(32, dmabuf.width)
                    val texture = assertNotNull(renderer.importDmabuf(dmabuf), "import failed")
                    texture.use {
                        renderer.createTarget(32, 16).use { target ->
                            renderer.begin(target, 0f, 0f, 1f)
                            renderer.drawTexture(texture, 0, 0, 32, 16, opaque = true)
                            Arena.ofConfined().use { arena ->
                                val pixels = arena.allocate(32L * 16 * 4)
                                target.readPixels(pixels, 32 * 4)
                                assertEquals(0xFFFF0000.toInt(), pixels.get(JAVA_INT, (8L * 32 + 16) * 4))
                            }
                        }
                    }
                }
            }
        }
    }
}
