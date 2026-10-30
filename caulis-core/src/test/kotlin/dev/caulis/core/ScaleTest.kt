package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.ConnectionException
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.fractionalscalev1.WpFractionalScaleManagerV1
import dev.caulis.protocol.client.fractionalscalev1.WpFractionalScaleV1
import dev.caulis.protocol.client.viewporter.WpViewporter
import dev.caulis.protocol.client.wayland.WlOutput
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.protocol.client.xdgoutputunstablev1.ZxdgOutputManagerV1
import dev.caulis.protocol.client.xdgoutputunstablev1.ZxdgOutputV1
import dev.caulis.tiling.Columns
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Gaps
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Scaled screens (1.25, 1.5, 2, ...): programs are told, draw sharp, nothing gaps or overlaps. */
class ScaleTest {

    private val pixelsWide = ScreenTestServer.WIDTH
    private val pixelsHigh = ScreenTestServer.HEIGHT

    private fun <T : Proxy> TestClient.global(wanted: String, type: ProxyType<T>, version: Int): T {
        var found = -1
        val registry = connection.display.getRegistry()
        registry.events = object : WlRegistry.Events {
            override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                if (`interface` == wanted) found = name
            }
        }
        connection.roundtrip()
        check(found >= 0) { "no $wanted" }
        return registry.bind(found, type, version)
    }

    @Test
    fun `programs are told the scale, fractional and whole`() = withServer { server, client ->
        server.setScale(1.5)
        var outputScale = 0
        var logical = 0 to 0
        client.output!!.events = object : WlOutput.Events {
            override fun scale(proxy: WlOutput, factor: Int) { outputScale = factor }
        }
        client.global("zxdg_output_manager_v1", ZxdgOutputManagerV1, 3).getXdgOutput(client.output).events = object : ZxdgOutputV1.Events {
            override fun logicalSize(proxy: ZxdgOutputV1, width: Int, height: Int) { logical = width to height }
        }
        val window = client.window()
        var fractional = 0
        client.global("wp_fractional_scale_manager_v1", WpFractionalScaleManagerV1, 1).getFractionalScale(window.surface).events =
            object : WpFractionalScaleV1.Events {
                override fun preferredScale(proxy: WpFractionalScaleV1, scale: Int) { fractional = scale }
            }
        window.draw()
        // A new surface hears which monitor it is on and its whole-number scale.
        var entered = false
        var bufferScale = 0
        val surface = client.compositor.createSurface()
        surface.events = object : WlSurface.Events {
            override fun enter(proxy: WlSurface, output: WlOutput) { entered = true }
            override fun preferredBufferScale(proxy: WlSurface, factor: Int) { bufferScale = factor }
        }
        surface.commit()
        client.waitFor { fractional != 0 && entered && bufferScale != 0 }
        assertEquals(180, fractional, "1.5 in 120ths")
        assertEquals(2, bufferScale, "the next whole scale for programs without fractions")
        assertEquals(2, outputScale)
        assertEquals(Math.round(pixelsWide / 1.5).toInt() to Math.round(pixelsHigh / 1.5).toInt(), logical)
        assertEquals(Math.round(pixelsWide / 1.5).toInt(), server.onServer { it.width }, "the desktop in its own units")
    }

    @Test
    fun `a picture drawn at the exact scale is shown pixel for pixel`() = withServer { server, client ->
        server.onServer {
            it.layout = Floating
            it.animationMillis = 0 // the picture itself, not the change of scale melting in
        }
        server.setScale(1.5)
        // As a program with fractional scaling does: a 300 x 150 pixel buffer for a 200 x 100 window.
        val window = client.window()
        val viewport = client.global("wp_viewporter", WpViewporter, 1).getViewport(window.surface)
        viewport.setDestination(200, 100)
        client.draw(window.surface, 300, 150)
        client.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        val logical = server.onServer { d -> d.screenRect(d.windows.first())!! }
        assertEquals(200 to 100, logical.width to logical.height, "the window's size in desktop units")
        val pixels = server.onServer { it.toPixels(logical) }
        assertEquals(300 to 150, pixels.width to pixels.height, "on screen exactly the buffer's pixels")

        val screen = server.screenshot()
        fun at(x: Int, y: Int) = screen[y * pixelsWide + x]
        assertEquals(BLACK, at(pixels.x + 1, pixels.y + 1), "the window's first pixels")
        assertEquals(BLACK, at(pixels.right - 2, pixels.bottom - 2), "and its last")
        assertTrue(at(pixels.right + 2, pixels.bottom + 2) != BLACK, "nothing beyond")
    }

    @Test
    fun `tiled windows at 1_2 neither gap nor overlap`() = withServer { server, client ->
        server.onServer {
            it.layout = Columns(Gaps(0, 0))
            it.animationMillis = 0 // look at where windows are, not on their way
        }
        server.setScale(1.2)
        // Windows join the layout once they drew something: open them one after the other.
        val windows = (1..3).map { n ->
            client.window().also { window ->
                window.draw()
                client.waitFor { server.onServer { d -> d.windows.size == n } }
            }
        }
        // Each new window narrowed the others (533 units in three: 178 + 178 + 177, none lost).
        client.waitFor { windows.all { it.width in 1 until 200 } }
        windows.forEach { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.mapNotNull(d::screenRect).sumOf { it.width } == d.width } }
        val rects = server.onServer { d -> d.windows.map { d.toPixels(d.screenRect(it)!!) }.sortedBy { it.x } }
        for ((a, b) in rects.zipWithNext()) assertEquals(a.right, b.x, "next to each other in pixels: $a, $b")
        val screen = server.screenshot()
        val y = pixelsHigh / 2
        val background = (rects.first().x until rects.last().right).filter { screen[y * pixelsWide + it] != BLACK }
        assertEquals(emptyList(), background, "no background showing between the windows")
    }

    @Test
    fun `the cursor is as big as the scale makes it, and sharp`() = withServer { server, _ ->
        server.onServer { it.pointerMoved(0, 50.0, 50.0) }
        val normal = cursorHeight(server, 50, 50)
        server.setScale(2.0)
        server.onServer { it.pointerMoved(0, 50.0, 50.0) }
        val doubled = cursorHeight(server, 100, 100)
        assertTrue(doubled in normal * 2 - 3..normal * 2 + 3, "twice as many pixels: $normal, then $doubled")
    }

    // How many rows below (x, y) in pixels the cursor reaches (it is drawn there over the background).
    private fun cursorHeight(server: ScreenTestServer, x: Int, y: Int): Int {
        val screen = server.screenshot()
        val background = screen[(pixelsHigh - 1) * pixelsWide + pixelsWide - 1]
        return (y until pixelsHigh).lastOrNull { row -> (x until minOf(x + 60, pixelsWide)).any { screen[row * pixelsWide + it] != background } }?.let { it - y + 1 } ?: 0
    }

    @Test
    fun `a synchronized subsurface keeps its viewport, input and opaque region`() = withServer { server, client ->
        server.setScale(1.5)
        val window = client.window()
        window.draw(300, 200)
        // A title bar as foot draws it: a synchronized subsurface, its buffer 1.5 times its size.
        val title = client.compositor.createSurface()
        client.connection.display.getRegistry() // (keeps the registry alive)
        val sub = client.global("wl_subcompositor", dev.caulis.protocol.client.wayland.WlSubcompositor, 1).getSubsurface(title, window.surface)
        sub.setPosition(0, -26)
        client.global("wp_viewporter", WpViewporter, 1).getViewport(title).setDestination(200, 26)
        val region = client.compositor.createRegion().also { it.add(0, 0, 10, 10) }
        title.setInputRegion(region)
        client.draw(title, 300, 39) // commits the subsurface: cached until the parent commits
        window.surface.commit()
        client.connection.roundtrip()
        val (size, input) = server.onServer { d ->
            val surface = d.allWindows.first().surface.stack.first { it !== d.allWindows.first().surface }
            (surface.width to surface.height) to (surface.acceptsInput(5.0, 5.0) to surface.acceptsInput(50.0, 15.0))
        }
        assertEquals(200 to 26, size, "the viewport's size, not the buffer's")
        assertEquals(true to false, input, "the input region came along too")
    }

    @Test
    fun `a scale change melts the old picture into the new one`() = withServer { server, client ->
        server.onServer {
            it.layout = Floating
            it.animationMillis = 200 // (fading needs animations on)
            it.animations = Animations.ON
        }
        val window = client.window()
        window.draw(100, 100)
        client.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        val place = server.onServer { d -> d.toPixels(d.screenRect(d.windows.first())!!) }
        // A point just outside the window now, inside it at scale 2.
        val x = place.right + place.width / 2
        val y = place.y + place.height / 2
        val before = server.screenshot()[y * pixelsWide + x]
        server.setScale(2.0)
        window.draw()
        client.connection.roundtrip()
        // Looked at again and again while it melts (a quarter of a second): somewhere in between, a mix.
        val samples = (1..12).map { Thread.sleep(25); server.screenshot()[y * pixelsWide + x] }
        Thread.sleep(300)
        val after = server.screenshot()[y * pixelsWide + x]
        assertEquals(BLACK, after, "the window is there now")
        assertTrue(samples.any { it != before && it != after }, "a mix in between: ${Integer.toHexString(before)} ${samples.map(Integer::toHexString)} ${Integer.toHexString(after)}")
    }

    @Test
    fun `a viewport source outside the buffer is a protocol error`() = withServer { _, client ->
        val window = client.window()
        val viewport = client.global("wp_viewporter", WpViewporter, 1).getViewport(window.surface)
        viewport.setSource(0.0, 0.0, 500.0, 500.0)
        viewport.setDestination(100, 100)
        client.draw(window.surface, 64, 64)
        val error = assertFailsWith<ConnectionException> { client.connection.roundtrip() }
        assertTrue(error.message!!.contains("outside"), error.message)
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}
