package dev.caulis.wm

import dev.caulis.core.TestClipboard
import dev.caulis.screenshot.ShotKind
import dev.caulis.screenshot.Screenshots
import dev.caulis.screenshot.screenshots
import dev.caulis.wm.config.Actions
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The screenshot chooser: the screen frozen, a part dragged, saved as PNG and copied. */
class ScreenshotTest {

    private val harness = CaulisTest()

    @Test
    fun `a part dragged with the mouse is saved and copied, and the programs get their input back`() = with(harness) {
        val folder = Files.createTempDirectory("screenshots")
        try {
            withCaulis({ screenshots { this.folder = folder; notify = false } }, headless = true) { caulis, client ->
                val clipboard = TestClipboard(client)
                client.window().draw()
                client.waitFor { caulis.onLoop { it.desktop.windows.size } == 1 }

                caulis.onLoop { r ->
                    Screenshots.of(caulis).choose(ShotKind.REGION)
                    assertEquals("the screenshot chooser", r.desktop.overlay?.toString(), "the chooser is open")
                    assertEquals("crosshair", r.desktop.cursorShape)
                    r.desktop.pointerMoved(0, 100.0, 120.0)
                    r.pointerButton(0, BTN_LEFT, true)
                    r.desktop.pointerMoved(0, 300.0, 270.0)
                    r.pointerButton(0, BTN_LEFT, false)
                    r.key(0, KEY_RIGHT, true) // moved by one
                    r.key(0, KEY_RIGHT, false)
                    r.key(0, KEY_ENTER, true)
                    r.key(0, KEY_ENTER, false)
                    assertNull(r.desktop.overlay, "closed when taken")
                }
                client.waitFor { Files.list(folder).use { it.count() } == 1L && PNG in clipboard.offered }
                val file = Files.list(folder).use { it.findFirst().get() }
                Thread.sleep(200) // (written whole)
                val image = ImageIO.read(file.toFile())
                assertEquals(200, image.width)
                assertEquals(150, image.height)
                val pasted = clipboard.paste(PNG)
                assertEquals(200, ImageIO.read(pasted.inputStream()).width, "the same picture on the clipboard")

                // A key the chooser does not know goes nowhere; Escape cancels.
                caulis.onLoop { r ->
                    Actions.parse("screenshot window")(caulis)
                    assertEquals("the screenshot chooser", r.desktop.overlay?.toString())
                    r.key(0, KEY_ESC, true)
                    r.key(0, KEY_ESC, false)
                    assertNull(r.desktop.overlay)
                }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val PNG = "image/png"
        const val BTN_LEFT = 272
        const val KEY_ESC = 1
        const val KEY_ENTER = 28
        const val KEY_RIGHT = 106
    }
}
