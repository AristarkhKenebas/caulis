package dev.caulis.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real `wayland-info` tool (built on libwayland, like every Wayland program)
 * connects to Caulis and reads what it offers. Skipped if wayland-utils is not installed.
 */
class WaylandInfoTest {

    @Test
    fun `wayland-info understands the server`() {
        val tool = File("/usr/bin/wayland-info")
        assumeTrue(tool.canExecute(), "wayland-info is not installed (package wayland-utils)")

        TestServer().use { server ->
            val process = ProcessBuilder(tool.path)
                .redirectErrorStream(true)
                .apply {
                    environment()["XDG_RUNTIME_DIR"] = server.runtimeDir.toString()
                    environment()["WAYLAND_DISPLAY"] = server.socketName
                }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "wayland-info did not finish")
            assertEquals(0, process.exitValue(), output)

            assertTrue(Regex("""interface: 'wl_compositor',\s+version:\s+6""").containsMatchIn(output), output)
            assertTrue(Regex("""interface: 'wl_shm',\s+version:\s+2""").containsMatchIn(output), output)
            assertTrue(output.contains("'AR24'") && output.contains("'XR24'"), output) // ARGB8888, XRGB8888
            assertTrue(output.contains("HEADLESS-1"), output)
            assertTrue(output.contains("1920") && output.contains("1080"), output)
        }
    }
}
