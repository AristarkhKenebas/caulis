package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.presentationtime.WpPresentationFeedback
import dev.caulis.protocol.client.wayland.WlCallback
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.render.assumeGpu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** presentation-time: programs learn when their pictures reached the screen. */
class PresentationTest {

    @Test
    fun `a shown window hears when its picture reached the screen`() = withClient { client ->
        val surface = client.windowSurface()
        val answer = client.feedbackFor(surface)
        client.draw(surface)
        client.waitFor { answer.result != null }
        assertEquals("presented", answer.result)
        assertEquals(ScreenTestServer.REFRESH_NANOS.toInt(), answer.refresh)
        assertTrue(answer.flags and WpPresentationFeedback.Kind.VSYNC != 0)
    }

    @Test
    fun `a picture replaced before it was shown is reported as discarded`() = withClient { client ->
        val surface = client.windowSurface()
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

    private class Answer {
        var result: String? = null
        var refresh = 0
        var flags = 0
    }

    private fun TestClient.feedbackFor(surface: WlSurface): Answer {
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

    private fun TestClient.draw(surface: WlSurface, commit: Boolean = true) = draw(surface, 64, 64, commit)

    private fun TestClient.windowSurface(): WlSurface = window().surface

    private fun withClient(test: (TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use(test) }
    }
}
