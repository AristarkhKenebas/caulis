package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wlrlayershellunstablev1.ZwlrLayerSurfaceV1
import dev.caulis.render.Color
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The desktop's background: colours, gradients, pictures, shaders — and wallpaper programs above it. */
class BackgroundTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    @Test
    fun `a colour, a gradient and a picture from a file`() = withServer { server, _ ->
        server.onServer {
            it.backgroundChangeMillis = 0 // (each new one at once)
            it.background = Background.color(Color.hex("#336699"))
        }
        assertNear(0xFF336699.toInt(), server.screenshot().at(10, 10), "the colour")

        server.onServer { it.background = Background.gradient(Color.hex("#000000"), Color.hex("#ffffff"), degrees = 0.0) }
        val gradient = server.screenshot()
        assertTrue(level(gradient.at(20, 200)) < level(gradient.at(width - 20, 200)), "dark on the left, light on the right")

        // A picture: red on the left, blue on the right.
        val file = Files.createTempFile("caulis-background", ".png")
        try {
            val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 200) for (y in 0 until 100) image.setRGB(x, y, if (x < 100) 0xFF0000 else 0x0000FF)
            ImageIO.write(image, "png", file.toFile())
            server.onServer { it.background = Background.image(file, Background.Fit.STRETCH) }
            waitFor { server.screenshot().at(20, 200) == RED }
            assertEquals(BLUE, server.screenshot().at(width - 20, 200))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a shader that moves, and one that does not compile`() = withServer { server, _ ->
        server.onServer {
            it.backgroundChangeMillis = 0
            it.background = Background.shader(WallpaperShader("vec4 wallpaper(vec2 at) { return vec4(fract(time), 0.0, 0.0, 1.0); }", framesPerSecond = 60))
        }
        val first = server.screenshot().at(10, 10)
        Thread.sleep(300)
        assertNotEquals(first, server.screenshot().at(10, 10), "a new picture as time goes on")

        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        dev.caulis.log.Log.listener = { lines += it }
        try {
            server.onServer { it.background = Background.shader(WallpaperShader("not GLSL at all")) }
            server.screenshot()
            assertNear(USUAL, server.screenshot().at(10, 400), "the usual background instead (below the notice)")
            assertEquals(1, lines.count { "a wallpaper shader does not compile" in it })
            assertEquals(1, server.onServer { d -> d.notices.count { it.title == "a wallpaper shader does not compile" } }, "and a notice says so")
        } finally {
            dev.caulis.log.Log.listener = null
        }
    }

    @Test
    fun `the guide's shader and the aurora compile`() = withServer { server, _ ->
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        dev.caulis.log.Log.listener = { lines += it }
        try {
            val guide = WallpaperShader(
                """
                vec4 wallpaper(vec2 at) {
                    float glow = 0.15 / (distance(at, pointer) + 0.15);
                    float wave = 0.5 + 0.5 * sin(at.x * 8.0 + time);
                    return vec4(0.05 + 0.2 * glow, 0.1 + 0.1 * wave, 0.25 + 0.3 * glow, 1.0);
                }
                """,
                framesPerSecond = 30,
            )
            val spanning = WallpaperShader(
                """
                vec4 wallpaper(vec2 at) {
                    vec2 p = desktopAt(at);
                    float rings = 0.5 + 0.5 * sin(length(unitsAt(at) - desktop.xy - desktop.zw * 0.5) / 40.0 - time);
                    return vec4(0.1 + 0.3 * p.x, 0.15 * rings, 0.3 + 0.2 * p.y, 1.0);
                }
                """,
            )
            for (shader in listOf(guide, spanning, WallpaperShader.AURORA)) {
                server.onServer {
                    it.backgroundChangeMillis = 0
                    it.background = Background.shader(shader)
                }
                server.screenshot()
            }
            assertTrue(lines.none { "does not compile" in it }, lines.filter { "shader" in it }.joinToString("\n"))
        } finally {
            dev.caulis.log.Log.listener = null
        }
    }

    @Test
    fun `each workspace its own, melting into the next`() = withServer { server, _ ->
        server.onServer { d ->
            d.backgroundChangeMillis = 0
            d.backgroundFor = { _, workspace -> Background.color(if (workspace == 1) Color.hex("#ff0000") else Color.hex("#0000ff")) }
        }
        assertEquals(RED, server.screenshot().at(10, 10))
        server.onServer { d -> d.backgroundChangeMillis = 1500; d.animations = Animations.ON }
        server.onServer { d -> d.switchTo(2) }
        Thread.sleep(400)
        val between = server.screenshot().at(10, 10)
        assertTrue(between != RED && between != BLUE, "on its way: ${Integer.toHexString(between)}")
        Thread.sleep(1500)
        assertEquals(BLUE, server.screenshot().at(10, 10))
    }

    @Test
    fun `a wallpaper program draws on top, and a moving shader below it rests`() = withServer { server, client ->
        server.onServer { it.background = Background.shader(WallpaperShader.AURORA) }
        val surface = client.compositor.createSurface()
        val layer = client.layerShell!!.getLayerSurface(surface, null, 0, "wallpaper") // the background layer
        var size = 0 to 0
        layer.events = object : ZwlrLayerSurfaceV1.Events {
            override fun configure(proxy: ZwlrLayerSurfaceV1, serial: Int, width: Int, height: Int) {
                size = width to height
                proxy.ackConfigure(serial)
            }
        }
        layer.setAnchor(15)
        layer.setExclusiveZone(-1)
        surface.commit()
        client.waitFor { size.first > 0 }
        client.draw(surface, size.first, size.second)
        client.waitFor { server.screenshot().at(10, 10) == BLACK }
        Thread.sleep(300)
        val frames = server.framesDrawn
        Thread.sleep(700)
        assertTrue(server.framesDrawn - frames <= 2, "no frames for a background nobody sees: ${server.framesDrawn - frames}")
    }

    @Test
    fun `a shader gets its colours and values, and shows its fallback when it does not compile`() = withServer { server, _ ->
        val code = "vec4 wallpaper(vec2 at) { return vec4(colors[0].rgb * strength, 1.0); }"
        server.onServer {
            it.backgroundChangeMillis = 0
            it.background = Background.shader(WallpaperShader(code).with(colors = listOf(Color.hex("#ff0000")), values = mapOf("strength" to 0.5f)))
        }
        assertNear(0xFF800000.toInt(), server.screenshot().at(10, 400), "colors[0] times the value strength")
        server.onServer { it.background = Background.shader(WallpaperShader("not GLSL").with(fallback = Color.hex("#00ff00"))) }
        server.screenshot()
        assertNear(0xFF00FF00.toInt(), server.screenshot().at(10, 400), "its fallback colour")
        val wrong = runCatching { WallpaperShader(code).with(values = mapOf("time" to 1f)) }
        assertTrue(wrong.isFailure, "a value cannot take one of Caulis's own names")
    }

    @Test
    fun `clicks and the pointer reach a shader, and it rests while the pointer does`() = withServer { server, _ ->
        // Red while a left click is in the list, green otherwise; blue with the pointer's x.
        val code = """
            vec4 wallpaper(vec2 at) {
                float clicked = clickCount > 0 && clicks[0].w == 1.0 ? 1.0 : 0.0;
                return vec4(clicked, 1.0 - clicked, pointer.x, 1.0);
            }
        """.trimIndent()
        server.onServer {
            it.backgroundChangeMillis = 0
            it.background = Background.shader(WallpaperShader(code, framesPerSecond = 60))
            it.pointerMoved(0, width / 2.0, height / 2.0)
        }
        val before = server.screenshot().at(10, 400)
        assertTrue((before shr 8 and 0xff) > 200 && (before and 0xff) in 120..135, "no click yet, the pointer in the middle: ${Integer.toHexString(before)}")
        server.onServer { it.pointerButton(0, 272, true); it.pointerButton(0, 272, false) }
        waitFor { (server.screenshot().at(10, 400) shr 16 and 0xff) > 200 } // (the click is there, at the shader's next frame)
        // Resting: no frames for a shader that only follows the pointer.
        Thread.sleep(800)
        val frames = server.framesDrawn
        Thread.sleep(600)
        assertTrue(server.framesDrawn - frames <= 2, "no frames while the pointer rests: ${server.framesDrawn - frames}")
    }

    @Test
    fun `paused shaders stand still, and a limit slows them`() = withServer { server, _ ->
        server.onServer {
            it.backgroundChangeMillis = 0
            it.background = Background.shader(WallpaperShader("vec4 wallpaper(vec2 at) { return vec4(fract(time), 0.0, 0.0, 1.0); }", framesPerSecond = 60))
            it.wallpaperShadersPaused = true
        }
        Thread.sleep(300)
        val still = server.screenshot().at(10, 400)
        val frames = server.framesDrawn
        Thread.sleep(500)
        assertEquals(still, server.screenshot().at(10, 400), "the same picture while paused")
        assertTrue(server.framesDrawn - frames <= 2, "no frames while paused: ${server.framesDrawn - frames}")
        server.onServer { it.wallpaperShadersPaused = false; it.wallpaperFpsLimit = 5 }
        Thread.sleep(300)
        val start = server.framesDrawn
        Thread.sleep(1000)
        val drawn = server.framesDrawn - start
        assertTrue(drawn in 2..8, "about 5 frames a second with the limit: $drawn")
        server.onServer { it.wallpaperFpsLimit = 0 }
    }

    @Test
    fun `a drawing shows a wallpaper shader over a part bigger than the monitor`() = withServer { server, _ ->
        server.onServer { d ->
            d.addDrawing(DrawingLevel.WALLPAPER, object : Drawing {
                override fun draw(monitor: Monitor, canvas: OverlayCanvas) {
                    // Twice the monitor, its left half on screen: at.x goes 0..0.5 over the monitor.
                    val a = monitor.area
                    canvas.wallpaper(WallpaperShader("vec4 wallpaper(vec2 at) { return vec4(at.x, 0.0, 0.0, 1.0); }"), dev.caulis.geometry.Rect(a.x, a.y, a.width * 2, a.height))
                }
            })
        }
        val right = server.screenshot().at(width - 2, 400) shr 16 and 0xff
        assertTrue(right in 120..135, "half way along the picture at the monitor's right edge: $right")
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(30)
        }
    }

    private fun level(p: Int) = (p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff)

    private fun assertNear(expected: Int, actual: Int, what: String) {
        val close = (0..2).all { s -> abs((expected shr (s * 8) and 0xff) - (actual shr (s * 8) and 0xff)) <= 2 }
        assertTrue(close, "$what: ${Integer.toHexString(actual)}, not ${Integer.toHexString(expected)}")
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * width + x]

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val USUAL = 0xFF292E38.toInt()
    }
}
