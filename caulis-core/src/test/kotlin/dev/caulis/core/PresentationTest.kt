package dev.caulis.core

import dev.caulis.ffi.SharedMemory
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.presentationtime.WpPresentation
import dev.caulis.protocol.client.presentationtime.WpPresentationFeedback
import dev.caulis.protocol.client.wayland.WlCallback
import dev.caulis.protocol.client.wayland.WlCompositor
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.protocol.client.wayland.WlShm
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.protocol.client.xdgshell.XdgSurface
import dev.caulis.protocol.client.xdgshell.XdgWmBase
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** presentation-time: programs learn when their pictures reached the screen. */
class PresentationTest {

    @Test
    fun `a shown window hears when its picture reached the screen`() = withClient { client ->
        val surface = client.window()
        val answer = client.feedbackFor(surface)
        client.draw(surface)
        client.waitFor { answer.result != null }
        assertEquals("presented", answer.result)
        assertEquals(ScreenTestServer.REFRESH_NANOS.toInt(), answer.refresh)
        assertTrue(answer.flags and WpPresentationFeedback.Kind.VSYNC != 0)
    }

    @Test
    fun `a picture replaced before it was shown is reported as discarded`() = withClient { client ->
        val surface = client.window()
        val first = client.feedbackFor(surface)
        client.draw(surface, commit = false)
        surface.commit()
        val second = client.feedbackFor(surface)
        client.draw(surface) // replaces the first picture in the same moment
        client.waitFor { first.result != null && second.result != null }
        assertEquals("discarded", first.result)
        assertEquals("presented", second.result)
    }

    @Test
    fun `a surface nobody sees still gets its frames, slowly`() = withClient { client ->
        // A surface without a role is never on screen.
        val hidden = client.compositor.createSurface()
        var frames = 0
        val start = System.nanoTime()
        repeat(2) {
            hidden.frame().events = object : WlCallback.Events {
                override fun done(proxy: WlCallback, callbackData: Int) {
                    frames++
                }
            }
            hidden.commit()
            client.waitFor { frames == it + 1 }
        }
        val seconds = (System.nanoTime() - start) / 1e9
        assertTrue(seconds > 1.5, "hidden surfaces are slowed down (2 frames took $seconds s)")
    }

    // --- a tiny client ---------------------------------------------------------------------

    private class Answer {
        var result: String? = null
        var refresh = 0
        var flags = 0
    }

    private class Client(val connection: Connection) {
        private val names = HashMap<String, Int>()
        private val registry = connection.display.getRegistry().also { registry ->
            registry.events = object : WlRegistry.Events {
                override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                    names[`interface`] = name
                }
            }
            connection.roundtrip()
        }
        val compositor = registry.bind(names.getValue("wl_compositor"), WlCompositor, 6)
        private val shm = registry.bind(names.getValue("wl_shm"), WlShm, 1)
        private val wmBase = registry.bind(names.getValue("xdg_wm_base"), XdgWmBase, 6)
        private val presentation = registry.bind(names.getValue("wp_presentation"), WpPresentation, 1)
        private val memory = SharedMemory.create("picture", 64L * 64 * 4)

        fun window(): WlSurface {
            val surface = compositor.createSurface()
            val xdgSurface = wmBase.getXdgSurface(surface)
            xdgSurface.getToplevel()
            xdgSurface.events = object : XdgSurface.Events {
                override fun configure(proxy: XdgSurface, serial: Int) = proxy.ackConfigure(serial)
            }
            surface.commit()
            connection.roundtrip()
            return surface
        }

        fun feedbackFor(surface: WlSurface): Answer {
            val answer = Answer()
            presentation.feedback(surface).events = object : WpPresentationFeedback.Events {
                override fun presented(proxy: WpPresentationFeedback, tvSecHi: Int, tvSecLo: Int, tvNsec: Int, refresh: Int, seqHi: Int, seqLo: Int, flags: Int) {
                    answer.result = "presented"
                    answer.refresh = refresh
                    answer.flags = flags
                }

                override fun discarded(proxy: WpPresentationFeedback) {
                    answer.result = "discarded"
                }
            }
            return answer
        }

        fun draw(surface: WlSurface, commit: Boolean = true) {
            val pool = shm.createPool(memory.fd, 64 * 64 * 4)
            surface.attach(pool.createBuffer(0, 64, 64, 256, WlShm.Format.XRGB8888), 0, 0)
            surface.damageBuffer(0, 0, 64, 64)
            pool.destroy()
            if (commit) surface.commit()
        }

        fun waitFor(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000
            while (!condition()) {
                check(System.nanoTime() < deadline) { "timed out" }
                connection.roundtrip()
                Thread.sleep(10)
            }
        }
    }

    private fun withClient(test: (Client) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            Connection.connect(server.socketPath).use { connection -> test(Client(connection)) }
        }
    }
}
