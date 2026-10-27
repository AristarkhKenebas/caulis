package dev.caulis.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Code built against Caulis 0.1.1 scrolls as it did: its fourth number is in 1/120 of a notch. */
class ScrollCompatTest {

    @Test
    fun `the old pointerAxis takes the wheel in 1·120 of a notch`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            val heard = ArrayList<Double>()
            server.onServer { d ->
                d.openOverlay(object : Overlay {
                    override fun draw(monitor: Monitor, canvas: OverlayCanvas) {}
                    override fun scroll(vertical: Boolean, steps: Double) {
                        heard += steps
                    }
                })
                @Suppress("DEPRECATION")
                d.pointerAxis(0, true, 15.0, 120)
                d.closeOverlay()
            }
            assertEquals(listOf(1.0), heard, "one notch")
        }
    }
}
