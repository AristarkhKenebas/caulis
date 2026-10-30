package dev.caulis.core

import dev.caulis.protocol.client.Connection
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shaders and pictures from files, changed while the compositor runs. */
class ShaderFileTest {

    private val width = ScreenTestServer.WIDTH
    private val folder: Path = Files.createTempDirectory("caulis-shaders")

    @Test
    fun `screen shaders of one file share one watch, however often made (each reload makes one)`() {
        val file = folder.resolve("screen.glsl")
        file.writeText("vec4 effect(vec4 c, vec2 at) { return c; }")
        fun watchOf(shader: ScreenShader) = ScreenShader::class.java.getDeclaredField("watched").apply { isAccessible = true }.get(shader)
        val first = watchOf(ScreenShader.file(file))
        repeat(5) { assertTrue(watchOf(ScreenShader.file(file)) === first, "the same watched file, not one more each time") }
    }

    @Test
    fun `a saved shader is used at once, a mistake is shown with its line and goes when fixed`() = withServer { server ->
        val file = folder.resolve("sky.glsl")
        file.writeText(shader("1.0, 0.0, 0.0"))
        server.onServer { d ->
            d.backgroundChangeMillis = 0
            d.background = Background.shader(WallpaperShader.file(file))
        }
        waitFor { server.screenshot().at(10, 400) == RED }

        file.writeText(shader("0.0, 0.0, 1.0"))
        waitFor { server.screenshot().at(10, 400) == BLUE }

        // A mistake on line 2: said with that line; the usual background meanwhile.
        file.writeText("vec4 wallpaper(vec2 at) {\n    return nope;\n}\n")
        waitFor { server.onServer { d -> d.notices.any { it.title == "sky.glsl does not compile" } } }
        val notice = server.onServer { d -> d.notices.single { it.title == "sky.glsl does not compile" } }
        assertTrue(notice.text.startsWith("line 2:"), notice.text)
        assertEquals(USUAL, server.screenshot().at(10, 400))

        file.writeText(shader("0.0, 1.0, 0.0"))
        waitFor { server.screenshot().at(10, 400) == GREEN }
        assertTrue(server.onServer { d -> d.notices.none { it.title == "sky.glsl does not compile" } }, "the notice went")
    }

    @Test
    fun `a missing file is said plainly, and used as soon as it is there`() = withServer { server ->
        val file = folder.resolve("later.glsl")
        server.onServer { d ->
            d.backgroundChangeMillis = 0
            d.background = Background.shader(WallpaperShader.file(file))
        }
        waitFor { server.onServer { d -> d.notices.any { "cannot be read" in it.text } } }
        file.writeText(shader("1.0, 0.0, 0.0"))
        waitFor { server.screenshot().at(10, 400) == RED }
        assertTrue(server.onServer { d -> d.notices.none { "cannot be read" in it.text } })
    }

    @Test
    fun `a background picture saved again is shown anew`() = withServer { server ->
        val file = folder.resolve("wall.png")
        fun paint(rgb: Int) {
            val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 64) for (y in 0 until 64) image.setRGB(x, y, rgb)
            val temp = folder.resolve("wall.tmp.png")
            ImageIO.write(image, "png", temp.toFile())
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        paint(0xFF0000)
        server.onServer { d ->
            d.backgroundChangeMillis = 0
            d.background = Background.image(file, Background.Fit.STRETCH)
        }
        waitFor { server.screenshot().at(10, 400) == RED }
        paint(0x0000FF)
        waitFor { server.screenshot().at(10, 400) == BLUE }
    }

    private fun shader(rgb: String) = "vec4 wallpaper(vec2 at) {\n    return vec4($rgb, 1.0);\n}\n"

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(50)
        }
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * width + x]

    private fun withServer(test: (ScreenTestServer) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        try {
            ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server) } }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val USUAL = 0xFF292E38.toInt()
    }
}
