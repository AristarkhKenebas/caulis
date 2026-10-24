package dev.caulis.core

import dev.caulis.ffi.Fd
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.ConnectionException
import dev.caulis.protocol.client.linuxdmabufv1.ZwpLinuxBufferParamsV1
import dev.caulis.protocol.client.linuxdmabufv1.ZwpLinuxDmabufFeedbackV1
import dev.caulis.protocol.client.linuxdmabufv1.ZwpLinuxDmabufV1
import dev.caulis.protocol.client.wayland.WlCompositor
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.render.Dmabuf
import dev.caulis.render.gles.GlesRenderer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Clients sharing GPU buffers (zwp_linux_dmabuf_v1) with a GPU-backed Caulis. */
class LinuxDmabufTest {

    @Test
    fun `feedback names the GPU and a format table`() = withGpuClient { _, connection, dmabuf, _ ->
        var tableSize = 0
        var mainDevice = ByteArray(0)
        var done = false
        dmabuf.getDefaultFeedback().events = object : ZwpLinuxDmabufFeedbackV1.Events {
            override fun formatTable(proxy: ZwpLinuxDmabufFeedbackV1, fd: Fd, size: Int) {
                tableSize = size
                fd.close()
            }

            override fun mainDevice(proxy: ZwpLinuxDmabufFeedbackV1, device: ByteArray) {
                mainDevice = device
            }

            override fun done(proxy: ZwpLinuxDmabufFeedbackV1) {
                done = true
            }
        }
        connection.roundtrip()

        assertTrue(done)
        assertTrue(tableSize > 0 && tableSize % 16 == 0, "16 bytes per format, got $tableSize")
        val device = ByteBuffer.wrap(mainDevice).order(ByteOrder.nativeOrder()).long
        GlesRenderer.create().use { renderer ->
            assertEquals(Files.getAttribute(renderer.renderNode!!, "unix:rdev") as Long, device)
        }
    }

    @Test
    fun `a GPU buffer is shown without copying`() = withGpuClient { server, connection, dmabuf, compositor ->
        GlesRenderer.create().use { renderer ->
            renderer.createBuffer(32, 16, Dmabuf.XRGB8888, listOf(Dmabuf.MODIFIER_LINEAR), scanout = false).use { gpuBuffer ->
                gpuBuffer.export().use { exported ->
                    val params = dmabuf.createParams()
                    addPlanes(params, exported)
                    val buffer = params.createImmed(32, 16, Dmabuf.XRGB8888, 0)
                    params.destroy()

                    val surface = compositor.createSurface()
                    surface.attach(buffer, 0, 0)
                    surface.commit()
                    connection.roundtrip()
                    assertEquals(listOf("32x16"), server.shownPictures)
                }
            }
        }
    }

    @Test
    fun `a plane outside its memory is refused`() {
        assertFailsWith<ConnectionException> {
            withGpuClient { _, connection, dmabuf, _ ->
                GlesRenderer.create().use { renderer ->
                    renderer.createBuffer(32, 16, Dmabuf.XRGB8888, listOf(Dmabuf.MODIFIER_LINEAR), scanout = false).use { gpuBuffer ->
                        gpuBuffer.export().use { exported ->
                            val params = dmabuf.createParams()
                            val plane = exported.planes[0]
                            // Claims 1 GiB per row: far more than the buffer has.
                            params.add(plane.fd, 0, 0, 1 shl 30, 0, 0)
                            params.createImmed(32, 16, Dmabuf.XRGB8888, 0)
                            connection.roundtrip()
                        }
                    }
                }
            }
        }
    }

    private fun addPlanes(params: ZwpLinuxBufferParamsV1, buffer: Dmabuf) {
        buffer.planes.forEachIndexed { index, plane ->
            params.add(plane.fd, index, plane.offset, plane.stride, (buffer.modifier ushr 32).toInt(), buffer.modifier.toInt())
        }
    }

    // Starts a GPU server, connects, and binds linux-dmabuf v4 and wl_compositor.
    private fun withGpuClient(test: (TestServer, Connection, ZwpLinuxDmabufV1, WlCompositor) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        TestServer(gpu = true).use { server ->
            Connection.connect(server.socketPath).use { connection ->
                val globals = HashMap<String, Int>()
                val registry = connection.display.getRegistry()
                registry.events = object : WlRegistry.Events {
                    override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                        globals[`interface`] = name
                    }
                }
                connection.roundtrip()
                val dmabuf = registry.bind(globals.getValue("zwp_linux_dmabuf_v1"), ZwpLinuxDmabufV1, 4)
                val compositor = registry.bind(globals.getValue("wl_compositor"), WlCompositor, 6)
                test(server, connection, dmabuf, compositor)
            }
        }
    }
}
