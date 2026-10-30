package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wayland.WlShm
import dev.caulis.render.Color
import dev.caulis.render.assumeGpu
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The background is not drawn under opaque windows: fewer pixels by the render log, and to the
 * pixel the same picture as with everything drawn (Desktop.fullRedraw).
 */
class BackgroundCoveredTest {

    private val width = ScreenTestServer.WIDTH

    // The pixels of the frame drawn as usual, and how many background pixels it drew of how many.
    private fun ScreenTestServer.usualFrame(): Triple<IntArray, Long, Long> {
        val lines = CopyOnWriteArrayList<String>()
        dev.caulis.log.Log.setLevel("render", dev.caulis.log.Log.EVERYTHING)
        dev.caulis.log.Log.listener = { lines += it }
        try {
            val shot = screenshot()
            val (drawn, asked) = Regex("""background (\d+) of (\d+) pixels""").findAll(lines.joinToString("\n")).last().destructured
            return Triple(shot, drawn.toLong(), asked.toLong())
        } finally {
            dev.caulis.log.Log.listener = null
            dev.caulis.log.Log.clearLevel("render")
        }
    }

    // The same picture with everything drawn, and with the covered background left out; its counts.
    private fun ScreenTestServer.compare(what: String): Pair<Long, Long> {
        onServer { it.fullRedraw = true }
        val whole = screenshot()
        onServer { it.fullRedraw = false }
        val (usual, drawn, asked) = usualFrame()
        assertContentEquals(whole, usual, "$what: the same picture")
        println("$what: background $drawn of $asked pixels drawn")
        return drawn to asked
    }

    private fun scene(server: ScreenTestServer, effects: Effects = Effects()) = server.onServer { d ->
        d.backgroundChangeMillis = 0
        d.background = Background.gradient(Color.hex("#102030"), Color.hex("#f0e0d0"), degrees = 30.0)
        d.layout = Dwindle(Gaps(16, 24))
        d.titleBars = TitleBars.NONE
        d.effects = effects
    }

    // A window in its place: all of [argb] (see-through formats [format]).
    private fun drawn(client: TestClient, window: TestClient.TestWindow, argb: Int, format: Int) {
        val w = window.width.takeIf { it > 0 } ?: 100
        val h = window.height.takeIf { it > 0 } ?: 100
        val (buffer, memory) = client.buffer(w, h, format)
        memory.write { segment -> for (i in 0 until w * h) segment.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i.toLong(), argb) }
        window.surface.attach(buffer, 0, 0)
        window.surface.damageBuffer(0, 0, w, h)
        window.surface.commit()
    }

    private fun windows(client: TestClient, server: ScreenTestServer, vararg looks: Pair<Int, Int>) {
        val list = looks.map { client.window() }
        list.forEachIndexed { k, w -> drawn(client, w, looks[k].first, looks[k].second) }
        client.waitFor { server.onServer { it.windows.size } == looks.size }
        client.connection.roundtrip()
        list.forEachIndexed { k, w -> drawn(client, w, looks[k].first, looks[k].second) } // (in the sizes they have now)
        client.connection.roundtrip()
        Thread.sleep(200)
    }

    @Test
    fun `opaque windows - only the rest of the background is drawn`() = withServer { server, client ->
        scene(server)
        windows(client, server, RED to WlShm.Format.XRGB8888, BLUE to WlShm.Format.XRGB8888)
        val (drawn, asked) = server.compare("opaque windows")
        assertTrue(drawn < asked / 2, "the gaps only: $drawn of $asked")
        // (A gap still shows the wallpaper, not the colour under it.)
        val shot = server.screenshot()
        assertTrue(shot[8 * width + 8] != RED && shot[8 * width + 8] != BLUE)
    }

    @Test
    fun `see-through windows - all of the background is drawn`() = withServer { server, client ->
        scene(server)
        windows(client, server, HALF_RED to WlShm.Format.ARGB8888, HALF_BLUE to WlShm.Format.ARGB8888)
        val (drawn, asked) = server.compare("see-through windows")
        assertEquals(asked, drawn)
    }

    @Test
    fun `faded by the compositor - all of it, though the windows are opaque`() = withServer { server, client ->
        scene(server, Effects(opacity = 0.8f))
        windows(client, server, RED to WlShm.Format.XRGB8888)
        val (drawn, asked) = server.compare("faded windows")
        assertEquals(asked, drawn)
    }

    @Test
    fun `round corners - the corners stay right`() = withServer { server, client ->
        scene(server, Effects(cornerRadius = 24))
        windows(client, server, RED to WlShm.Format.XRGB8888, BLUE to WlShm.Format.XRGB8888)
        val (drawn, asked) = server.compare("round corners")
        assertTrue(drawn < asked / 2, "$drawn of $asked")
    }

    @Test
    fun `a blurred see-through window beside an opaque one`() = withServer { server, client ->
        scene(server, Effects(blur = Blur(size = 8, passes = 3)))
        windows(client, server, HALF_RED to WlShm.Format.ARGB8888, RED to WlShm.Format.XRGB8888)
        server.compare("blur")
    }

    @Test
    fun `a fractional scale leaves no edge undrawn`() = withServer { server, client ->
        scene(server, Effects(cornerRadius = 10))
        server.setScale(1.5)
        windows(client, server, RED to WlShm.Format.XRGB8888, BLUE to WlShm.Format.XRGB8888, RED to WlShm.Format.XRGB8888)
        val (drawn, asked) = server.compare("scale 1.5")
        assertTrue(drawn < asked, "$drawn of $asked")
    }

    @Test
    fun `a shader wallpaper too`() = withServer { server, client ->
        scene(server)
        server.onServer { it.background = Background.shader(WallpaperShader("vec4 wallpaper(vec2 at) { return vec4(at, 0.5, 1.0); }")) }
        windows(client, server, RED to WlShm.Format.XRGB8888, BLUE to WlShm.Format.XRGB8888)
        val (drawn, asked) = server.compare("shader")
        assertTrue(drawn < asked / 2, "$drawn of $asked")
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val HALF_RED = 0x80800000.toInt()
        const val HALF_BLUE = 0x80000080.toInt()
    }
}
