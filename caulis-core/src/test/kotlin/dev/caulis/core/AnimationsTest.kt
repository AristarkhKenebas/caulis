package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.tiling.Floating
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Windows appearing and going away, workspaces changing, own animations and shaders. */
class AnimationsTest {

    private val width = ScreenTestServer.WIDTH

    @Test
    fun `a window fades in, and its last look fades out when it closes`() = withServer { server, client ->
        server.onServer { d ->
            d.layout = Floating
            d.animations = Animations(enabled = true, windowOpen = WindowAnimation.fade().lasting(1500), windowClose = WindowAnimation.fade().lasting(1500))
        }
        val window = client.window()
        window.draw(200, 150)
        client.waitFor { server.onServer { d -> d.allWindows.singleOrNull()?.mapped } == true }
        val middle = server.onServer { d -> d.screenRect(d.allWindows.single())!!.let { it.x + it.width / 2 to it.y + it.height / 2 } }
        val background = server.screenshot().at(5, 5)
        Thread.sleep(300)
        assertBetween(server.screenshot().at(middle.first, middle.second), BLACK, background, "on its way in")
        Thread.sleep(1500)
        assertEquals(BLACK, server.screenshot().at(middle.first, middle.second), "all there")

        window.toplevel.destroy()
        window.surface.destroy()
        client.connection.roundtrip()
        Thread.sleep(300)
        assertBetween(server.screenshot().at(middle.first, middle.second), BLACK, background, "its last look on its way out")
        Thread.sleep(1500)
        assertEquals(background, server.screenshot().at(middle.first, middle.second), "gone")
    }

    @Test
    fun `workspaces slide, and an own animation and an own shader are used`() = withServer { server, client ->
        val shader = WindowShader("vec4 effect(vec2 at) { return vec4(1.0, 0.0, 0.0, 1.0) * window(at).a; }")
        server.onServer { d ->
            d.layout = Floating
            d.animations = Animations(
                enabled = true,
                workspaceSwitch = WorkspaceAnimation.SLIDE.lasting(2000).curved(Curve.LINEAR),
                // Own: it appears red (through the shader), where it will be.
                windowOpen = WindowAnimation { m -> WindowFrame(m.to, shader = shader, progress = m.progress) }.lasting(1500),
            )
        }
        client.window().draw(200, 150)
        client.waitFor { server.onServer { d -> d.allWindows.singleOrNull()?.mapped } == true }
        val rect = server.onServer { d -> d.screenRect(d.allWindows.single())!! }
        Thread.sleep(200)
        assertEquals(RED, server.screenshot().at(rect.x + rect.width / 2, rect.y + rect.height / 2), "drawn through the shader")
        Thread.sleep(1500)

        server.onServer { d -> d.switchTo(2) }
        Thread.sleep(1000) // halfway: the window went half a monitor to the left
        val shown = server.onServer { d -> d.screenRect(d.allWindows.single()) }!!
        assertTrue(shown.x in rect.x - width * 3 / 4 until rect.x - width / 4, "on its way out to the left: $shown (from $rect)")
    }

    @Test
    fun `a shader that does not compile is said once, and the window is drawn without it`() = withServer { server, client ->
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        dev.caulis.log.Log.listener = { lines += it }
        try {
            server.onServer { d ->
                d.layout = Floating
                d.animations = Animations(enabled = true, windowOpen = WindowAnimation { m -> WindowFrame(m.to, shader = WindowShader("this is not GLSL")) }.lasting(1500))
            }
            client.window().draw(200, 150)
            client.waitFor { server.onServer { d -> d.allWindows.singleOrNull()?.mapped } == true }
            val rect = server.onServer { d -> d.screenRect(d.allWindows.single())!! }
            Thread.sleep(300)
            assertEquals(BLACK, server.screenshot().at(rect.x + rect.width / 2, rect.y + rect.height / 2), "drawn plainly")
            server.screenshot()
            assertEquals(1, lines.count { "does not compile" in it }, lines.joinToString("\n"))
            assertEquals(1, server.onServer { d -> d.notices.count { it.title == "a window shader does not compile" } })
        } finally {
            dev.caulis.log.Log.listener = null
        }
    }

    @Test
    fun `the genie and the guide's dissolve shader compile`() = withServer { server, client ->
        val dissolve = WindowShader(
            """
            float noise(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
            vec4 effect(vec2 at) {
                return noise(floor(at * size / 4.0)) < progress ? window(at) : vec4(0.0);
            }
            """,
        )
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        dev.caulis.log.Log.listener = { lines += it }
        try {
            server.onServer { d ->
                d.layout = Floating
                d.animations = Animations(
                enabled = true,
                    windowOpen = WindowAnimation { m -> WindowFrame(m.to, shader = dissolve, progress = m.progress) }.lasting(1500),
                    minimize = WindowAnimation.genie().lasting(1500),
                )
            }
            client.window().draw(200, 150)
            client.waitFor { server.onServer { d -> d.allWindows.singleOrNull()?.mapped } == true }
            Thread.sleep(200)
            server.screenshot()
            server.onServer { d -> d.minimize(d.allWindows.single()) }
            Thread.sleep(200)
            server.screenshot()
            assertTrue(lines.none { "does not compile" in it }, lines.filter { "shader" in it }.joinToString("\n"))
        } finally {
            dev.caulis.log.Log.listener = null
        }
    }

    @Test
    fun `animations are off by default, and named settings fall back along the tree`() {
        val usual = Animations().settings(220)
        for (name in AnimationTree.CAULIS.names) assertEquals(false, usual.resolve(name).on, "$name is off by default")
        val on = Animations.ON.settings(220)
        assertEquals(220L, on.resolve("windowsIn").millis)
        assertEquals(150L, on.resolve("border").millis, "its own usual time")
        assertEquals(330L, on.resolve("overview", 330).millis, "the code's time")
        val some = Animations()
            .with("windows", enabled = true, millis = 300, curve = Curve.LINEAR)
            .with("windowsIn", style = "popin 80%")
            .with("minimize", enabled = false)
        val s = some.settings(220)
        assertEquals(true, s.resolve("windowsOut").on, "on through its parent")
        assertEquals(300L, s.resolve("windowsMove").millis)
        assertEquals(Curve.LINEAR, s.resolve("windowsIn").curve)
        assertEquals("popin 80%", s.resolve("windowsIn").style)
        assertEquals(false, s.resolve("minimize").on, "its own setting wins")
        assertEquals(false, s.resolve("workspaces").on, "not under windows: off as all")
        assertEquals(1.0, s.resolve("minimize").at(0.1), "off: there at once")
        // Hyprland's speed: tenths of a second.
        val built = AnimationsBuilder(Animations()).apply { animation("workspaces", enabled = true, speed = 3.5, style = "slidevert") }.animations
        assertEquals(350L, built.settings(220).resolve("workspaces").millis)
        assertEquals("slidevert", built.settings(220).resolve("workspaces").style)
        // Looks that do not fit are refused at once (not on every frame).
        assertEquals(null, Animations.styleProblem("windowsIn", "slide left 120"))
        assertNotEquals(null, Animations.styleProblem("windowsIn", "wobble"))
        assertNotEquals(null, Animations.styleProblem("border", "fade"))
        assertTrue(runCatching { Animations().with("workspaces", style = "popin") }.isFailure)
        assertTrue(runCatching { Animations().with("nothing") }.isFailure)
    }

    @Test
    fun `curves and putting animations together`() {
        assertEquals(1.0, Curve.EASE_OUT.at(1.0), 1e-9)
        assertTrue(Curve.OVERSHOOT.at(0.8) > 1.0, "past the end for a moment")
        val motion = Motion(null, Rect(0, 0, 100, 100), Rect(0, 0, 100, 100), 0.5, 0.5, Rect(0, 0, 1000, 1000))
        val both = (WindowAnimation.fade() + WindowAnimation.slide(dy = 40)).frame(motion)
        assertEquals(0.5f, both.alpha)
        assertEquals(Rect(0, 20, 100, 100), both.rect)
        assertEquals(300L, WindowAnimation.pop().lasting(300).millis)
        assertNotEquals(null, (WindowAnimation.fade().lasting(200) + WindowAnimation.pop()).millis)
    }

    private fun assertBetween(pixel: Int, a: Int, b: Int, what: String) {
        fun level(p: Int) = (p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff)
        val (low, high) = listOf(level(a), level(b)).sorted()
        assertTrue(level(pixel) in (low + 1) until high, "$what: ${Integer.toHexString(pixel)} is not between ${Integer.toHexString(a)} and ${Integer.toHexString(b)}")
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * width + x]

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
