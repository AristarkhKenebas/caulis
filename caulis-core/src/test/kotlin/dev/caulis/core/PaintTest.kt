package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.render.Color
import dev.caulis.render.Paint
import dev.caulis.tiling.Floating
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Borders and shadows filled with gradients, turning gradients and shaders; their change with the focus. */
class PaintTest {

    private val width = ScreenTestServer.WIDTH

    @Test
    fun `a border from red to blue, a turning one, one of your own`() = withServer { server, client ->
        server.onServer { d ->
            d.layout = Floating
            d.effects = Effects(border = Border(6, focused = Paint.gradient(Color.hex("#ff0000"), Color.hex("#0000ff"), degrees = 0.0)))
        }
        client.window().draw(200, 150)
        val rect = rectOf(server, client)
        var screen = server.screenshot()
        val left = screen.at(rect.x + 10, rect.y - 3)
        val right = screen.at(rect.right - 10, rect.y - 3)
        assertTrue(red(left) > blue(left) && blue(right) > red(right), "red on the left (${hex(left)}), blue on the right (${hex(right)})")

        // Turning: the same place changes colour as time goes on.
        server.onServer { d -> d.effects = Effects(border = Border(6, focused = Paint.conic(Color.hex("#ff0000"), Color.hex("#00ff00"), Color.hex("#0000ff"), turnsPerSecond = 1.0))) }
        val before = server.screenshot().at(rect.x + rect.width / 2, rect.y - 3)
        Thread.sleep(250)
        assertNotEquals(before, server.screenshot().at(rect.x + rect.width / 2, rect.y - 3), "turned on")

        // Your own: green, and a broken one said in a notice (drawn plainly meanwhile).
        server.onServer { d -> d.effects = Effects(border = Border(6, focused = Paint.shader("vec4 paint(vec2 at) { return vec4(0.0, 1.0, 0.0, 1.0); }"))) }
        assertEquals(GREEN, server.screenshot().at(rect.x + rect.width / 2, rect.y - 3))
        server.onServer { d -> d.effects = Effects(border = Border(6, focused = Paint.Shader({ "vec4 paint(vec2 at) {\n  return nope;\n}" }, "glow.glsl"))) }
        server.screenshot()
        val notice = server.onServer { d -> d.notices.single { it.title == "glow.glsl does not compile" } }
        assertTrue(notice.text.startsWith("line 2:"), notice.text)
    }

    @Test
    fun `a turning border is drawn at most movingPaintsFps times a second`() = withServer { server, client ->
        server.onServer { d ->
            d.layout = Floating
            d.effects = Effects(border = Border(6, focused = Paint.conic(Color.hex("#ff0000"), Color.hex("#0000ff"), turnsPerSecond = 0.5)))
        }
        client.window().draw(200, 150)
        rectOf(server, client)
        Thread.sleep(300)
        var start = server.framesDrawn
        Thread.sleep(1000)
        val everyFrame = server.framesDrawn - start
        server.onServer { d -> d.movingPaintsFps = 10 }
        Thread.sleep(300)
        start = server.framesDrawn
        Thread.sleep(1000)
        val limited = server.framesDrawn - start
        assertTrue(everyFrame > 40, "every frame of the 60 Hz screen: $everyFrame")
        assertTrue(limited in 5..15, "about 10 a second: $limited")
        server.onServer { d -> d.movingPaintsFps = 0 }
    }

    @Test
    fun `the border changes smoothly when the focus moves`() = withServer { server, client ->
        server.onServer { d ->
            d.layout = Floating
            d.effects = Effects(border = Border(6, Color.hex("#ff0000"), Color.hex("#0000ff")), focusChangeMillis = 2000)
            d.animations = Animations().with("border", enabled = true) // (off by default; only this one)
        }
        client.window().draw(200, 150)
        val first = rectOf(server, client)
        client.window().draw(200, 150)
        client.waitFor { server.onServer { d -> d.windows.size } == 2 }
        // The first window just lost the focus: on its way from red to blue.
        Thread.sleep(700)
        val between = server.screenshot().at(first.x - 3, first.y + first.height / 2)
        assertTrue(red(between) > 30 && blue(between) > 30, "between red and blue: ${hex(between)}")
        Thread.sleep(1600)
        val after = server.screenshot().at(first.x - 3, first.y + first.height / 2)
        assertTrue(blue(after) > 200 && red(after) < 30, "blue: ${hex(after)}")
    }

    @Test
    fun `a coloured glow, a shadow spread out, and none for windows without the focus`() = withServer { server, client ->
        server.onServer { d ->
            d.layout = Floating
            d.effects = Effects(shadow = Shadow(softness = 12, offsetX = 0, offsetY = 0, color = Color.hex("#00ff00"), spread = 20))
        }
        client.window().draw(200, 150)
        val rect = rectOf(server, client)
        val background = server.screenshot().at(5, 5)
        val glow = server.screenshot().at(rect.x + rect.width / 2, rect.y - 15)
        assertTrue(green(glow) > 150 && red(glow) < 80, "green 15 pixels above it (spread 20): ${hex(glow)}")

        server.onServer { d -> d.effects = d.effects.copy(inactiveShadow = Shadow.NONE, focusChangeMillis = 0) }
        client.window().draw(100, 80) // takes the focus
        client.waitFor { server.onServer { d -> d.windows.size } == 2 }
        Thread.sleep(100)
        val unfocused = server.screenshot().at(rect.x + rect.width / 2, rect.y - 15)
        assertTrue(green(unfocused) < green(glow) / 2, "no glow without the focus: ${hex(unfocused)} (background ${hex(background)})")
    }

    private fun rectOf(server: ScreenTestServer, client: TestClient): Rect {
        client.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        return server.onServer { d -> d.screenRect(d.windows.first())!! }
    }

    private fun red(p: Int) = p shr 16 and 0xff
    private fun green(p: Int) = p shr 8 and 0xff
    private fun blue(p: Int) = p and 0xff
    private fun hex(p: Int) = Integer.toHexString(p)
    private fun IntArray.at(x: Int, y: Int) = this[y * width + x]

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val GREEN = 0xFF00FF00.toInt()
    }
}
