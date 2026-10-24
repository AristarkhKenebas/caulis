package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wayland.WlPointer
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.render.Dmabuf
import dev.caulis.render.GpuBuffer
import dev.caulis.render.gles.GlesRenderer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** When may a program's own picture go to the screen as it is? */
class DirectScanoutTest {

    @Test
    fun `a fullscreen GPU window with a hidden cursor is shown directly`() = withFullscreenGame(hideCursor = true) { server, _ ->
        assertTrue(server.framesShownDirectly > 0, "the game's picture should go to the screen directly")
    }

    @Test
    fun `with our cursor on top it is drawn as usual`() = withFullscreenGame(hideCursor = false) { server, _ ->
        assertEquals(0, server.framesShownDirectly)
    }

    // A "game": a fullscreen window drawing GPU buffers of the screen's size, a few frames.
    private fun withFullscreenGame(hideCursor: Boolean, check: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { client ->
                GlesRenderer.create().use { renderer ->
                    val window = client.window { it.setFullscreen(null) }
                    if (hideCursor) hideCursorOver(server, client, window.surface)
                    val buffers = (0 until 2).map {
                        renderer.createBuffer(window.width, window.height, Dmabuf.XRGB8888, listOf(Dmabuf.MODIFIER_LINEAR), scanout = false)
                    }
                    try {
                        repeat(4) { frame -> showFrame(client, window.surface, buffers[frame % 2]) }
                        if (hideCursor) client.waitFor { server.framesShownDirectly > 0 }
                        check(server, client)
                    } finally {
                        buffers.forEach(GpuBuffer::close)
                    }
                }
            }
        }
    }

    private fun showFrame(client: TestClient, surface: WlSurface, buffer: GpuBuffer) {
        val params = client.dmabuf!!.createParams()
        buffer.export().use { exported ->
            exported.planes.forEachIndexed { index, plane ->
                params.add(plane.fd, index, plane.offset, plane.stride, (exported.modifier ushr 32).toInt(), exported.modifier.toInt())
            }
        }
        val wlBuffer = params.createImmed(buffer.width, buffer.height, Dmabuf.XRGB8888, 0)
        params.destroy()
        surface.attach(wlBuffer, 0, 0)
        surface.damageBuffer(0, 0, buffer.width, buffer.height)
        surface.commit()
        Thread.sleep(40) // a couple of the pretend screen's refreshes
        client.connection.roundtrip()
    }

    // Like a game: when the pointer enters, set no cursor picture.
    private fun hideCursorOver(server: ScreenTestServer, client: TestClient, surface: WlSurface) {
        var entered = false
        client.seat.getPointer().events = object : WlPointer.Events {
            override fun enter(proxy: WlPointer, serial: Int, surface: WlSurface, surfaceX: Double, surfaceY: Double) {
                proxy.setCursor(serial, null, 0, 0)
                entered = true
            }
        }
        client.connection.roundtrip()
        client.draw(surface, 64, 64) // something to point at
        client.waitFor { server.onServer { it.windows.isNotEmpty() } }
        server.onServer { it.pointerMoved(0, 10.0, 10.0) }
        client.waitFor { entered }
    }
}
