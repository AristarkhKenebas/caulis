package dev.caulis.render

import dev.caulis.render.gles.GlesRenderer
import org.junit.jupiter.api.Assumptions

/**
 * Skips the calling test (a JUnit assumption) unless this machine has a GPU Caulis can
 * draw on. Every test that needs the GPU calls this first, as do the test servers that
 * draw (TestServer with gpu, ScreenTestServer, the compositor harnesses).
 *
 * The check really makes a renderer, the way Caulis does (render node, GBM device, EGL
 * display and context) and asks it for its render node, once per test run: a /dev/dri
 * directory alone says little (CI machines can have one with only a virtual display card
 * and no render node). Where the renderer can be made, GPU tests run and a later failure
 * is a real one.
 */
fun assumeGpu() {
    GpuCheck.problem?.let { Assumptions.abort<Unit>("no usable GPU: $it") }
}

private object GpuCheck {
    /** Why no renderer could be made, or null if one could. */
    val problem: String? = try {
        GlesRenderer.create().use { renderer ->
            // The test servers share GPU buffers with clients (linux-dmabuf): that needs the
            // driver's render node (a software driver on some other device file has none).
            checkNotNull(renderer.renderNode) { "the GPU driver (${renderer.description}) has no render node" }
            println("GPU tests run on: ${renderer.description}")
        }
        null
    } catch (e: Throwable) {
        // (Throwable: a missing C library shows up as a linkage error.)
        // (With the exception's kind: a missing /dev/dri says only "/dev/dri" by itself.)
        val reason = "${e.javaClass.simpleName}: ${e.message}"
        println("GPU tests are skipped, no usable GPU: $reason")
        reason
    }
}
