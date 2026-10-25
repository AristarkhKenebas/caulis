package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wayland.WlOutput
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A program that draws its buffer turned (as for a monitor on its side) and says so with
 * wl_surface.set_buffer_transform: its window is upright and as big as the turned picture,
 * not stretched or sideways.
 */
class BufferTransformTest {

    @Test
    fun `a buffer turned 90 degrees is shown upright, its sides traded`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { client ->
                val window = client.window()
                client.waitFor { window.states.isNotEmpty() || window.width >= 0 }
                // A buffer 100 wide, 200 high: turned by 90 it is a picture 200 wide, 100 high.
                // Its top left quarter is where the turned buffer's right half, top half is.
                val (buffer, memory) = client.buffer(100, 200)
                memory.write { pixels ->
                    for (y in 0 until 200) for (x in 0 until 100) {
                        pixels.setAtIndex(ValueLayout.JAVA_INT, y * 100L + x, if (x >= 50 && y < 100) RED else BLUE)
                    }
                }
                window.surface.setBufferTransform(WlOutput.Transform._90)
                window.surface.attach(buffer, 0, 0)
                window.surface.damageBuffer(0, 0, 100, 200)
                window.surface.commit()
                client.waitFor { server.onServer { d -> d.allWindows.any { it.mapped } } }
                val rect = server.onServer { d -> d.screenRect(d.allWindows.single())!! }
                assertEquals(200 to 100, rect.width to rect.height, "its sides traded")
                val picture = server.screenshot(0)
                fun at(x: Int, y: Int) = picture[(rect.y + y) * ScreenTestServer.WIDTH + rect.x + x]
                assertEquals(RED, at(50, 25), "the top left quarter")
                assertEquals(BLUE, at(150, 25), "not the top right")
                assertEquals(BLUE, at(50, 75), "not the bottom left")
            }
        }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
