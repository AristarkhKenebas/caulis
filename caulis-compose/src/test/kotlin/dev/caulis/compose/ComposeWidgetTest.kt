package dev.caulis.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.caulis.core.ScreenTestServer
import dev.caulis.geometry.Rect
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/** A Compose scene inside the compositor: drawn, clicked, typed into. */
class ComposeWidgetTest {

    private val width = ScreenTestServer.WIDTH

    @Test
    fun `a Compose bar is drawn, clicks reach it, and its text field gets typed text`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            var clicks by mutableIntStateOf(0)
            var text by mutableStateOf("")
            server.onServer { d ->
                d.addWidget(ComposeWidget(d, post = { server.loop.post(it::run) }, place = { m -> Rect(m.area.x, m.area.y, 300, 80) }, takesKeyboard = true) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(40.dp).background(if (clicks == 0) Color.Red else Color.Blue).clickable { clicks++ })
                        BasicTextField(text, { text = it }, Modifier.fillMaxWidth().height(40.dp).background(Color.White))
                    }
                })
            }
            waitFor(server, 10, 10, RED)
            server.onServer { d ->
                d.pointerMoved(0, 50.0, 20.0)
                d.pointerButton(0, BTN_LEFT, true)
                d.pointerButton(0, BTN_LEFT, false)
                d.pointerMoved(0, 400.0, 300.0) // (away: no hover light over it)
            }
            waitFor(server, 10, 10, BLUE)
            assertEquals(1, clicks)
            // Into the text field: click it, then type.
            server.onServer { d ->
                d.pointerMoved(0, 50.0, 60.0)
                d.pointerButton(0, BTN_LEFT, true)
                d.pointerButton(0, BTN_LEFT, false)
            }
            Thread.sleep(200)
            server.onServer { d -> for (code in listOf(35, 23)) { d.key(0, code, true); d.key(0, code, false) } } // h, i
            val deadline = System.nanoTime() + 3_000_000_000L
            while (text != "hi" && System.nanoTime() < deadline) Thread.sleep(30)
            assertEquals("hi", text)
        }
    }

    private fun waitFor(server: ScreenTestServer, x: Int, y: Int, color: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var seen = 0
        while (System.nanoTime() < deadline) {
            seen = server.screenshot()[y * width + x]
            if ((0..2).all { s -> abs((seen shr (s * 8) and 0xff) - (color shr (s * 8) and 0xff)) <= 3 }) return
            Thread.sleep(30)
        }
        error("at $x,$y: ${Integer.toHexString(seen)}, not ${Integer.toHexString(color)}")
    }

    private companion object {
        const val RED = 0xFF0000
        const val BLUE = 0x0000FF
        const val BTN_LEFT = 0x110
    }
}
