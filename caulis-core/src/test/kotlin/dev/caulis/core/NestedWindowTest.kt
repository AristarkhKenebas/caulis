package dev.caulis.core

import dev.caulis.core.nested.NestedWindow
import dev.caulis.event.EventLoop
import dev.caulis.render.assumeGpu
import dev.caulis.render.gles.GlesRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The nested window, with a Caulis test server playing the outer session. */
class NestedWindowTest {

    @Test
    fun `frames go to an outer session with linux-dmabuf as GPU buffers`() {
        val (sharesGpu, shown) = showFrames(outerHasGpu = true)
        assertTrue(sharesGpu)
        assertFramesShown(shown)
    }

    @Test
    fun `without linux-dmabuf frames are copied through shared memory`() {
        val (sharesGpu, shown) = showFrames(outerHasGpu = false)
        assertTrue(!sharesGpu)
        assertFramesShown(shown)
    }

    private fun assertFramesShown(shown: List<String>) {
        assertTrue(shown.size >= FRAMES, "expected $FRAMES frames, the outer session showed ${shown.size}")
        assertEquals("${NestedWindow.DEFAULT_WIDTH}x${NestedWindow.DEFAULT_HEIGHT}", shown.last())
    }

    // Opens a nested window inside a test server and draws a few frames into it.
    private fun showFrames(outerHasGpu: Boolean): Pair<Boolean, List<String>> {
        assumeGpu()
        TestServer(gpu = outerHasGpu).use { outer ->
            EventLoop().use { loop ->
                GlesRenderer.create().use { renderer ->
                    NestedWindow(loop, renderer, outerSocket = outer.socketPath).use { window ->
                        var frames = 0
                        window.onFrame = {
                            window.present { target, _ -> renderer.begin(target, 0.2f, 0.4f, 0.6f); renderer.end() }
                            if (++frames == FRAMES) loop.stop()
                        }
                        loop.run()
                        // The last commit reaches the server at the latest with a roundtrip.
                        window.connection.roundtrip()
                        return window.sharesGpuBuffers to outer.shownPictures.toList()
                    }
                }
            }
        }
    }

    private companion object {
        const val FRAMES = 3
    }
}
