package dev.caulis.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
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
import kotlin.test.assertTrue

/** A Compose scene inside the compositor: drawn, clicked, typed into. */
class ComposeWidgetTest {

    private val width = ScreenTestServer.WIDTH

    @Test
    fun `a Compose bar is drawn, clicks reach it, and its text field gets typed text`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            var clicks by mutableIntStateOf(0)
            var field by mutableStateOf(TextFieldValue(""))
            server.onServer { d ->
                d.addWidget(ComposeWidget(d, post = { server.loop.post(it::run) }, place = { m -> Rect(m.area.x, m.area.y, 300, 80) }, takesKeyboard = true) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(40.dp).background(if (clicks == 0) Color.Red else Color.Blue).clickable { clicks++ })
                        BasicTextField(field, { field = it }, Modifier.fillMaxWidth().height(40.dp).background(Color.White))
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
            while (field.text != "hi" && System.nanoTime() < deadline) Thread.sleep(30)
            assertEquals("hi", field.text)
            waitFrames(server)
            // Ctrl+A selects all of it (the key by its place, though Ctrl types nothing), "x" replaces it.
            server.onServer { d ->
                d.key(0, 29, true); d.key(0, 30, true); d.key(0, 30, false); d.key(0, 29, false)
            }
            val selected = System.nanoTime() + 3_000_000_000L
            while (field.selection.length != 2 && System.nanoTime() < selected) Thread.sleep(10)
            assertEquals(2, field.selection.length, "all selected")
            waitFrames(server)
            server.onServer { d -> d.key(0, 45, true); d.key(0, 45, false) }
            val again = System.nanoTime() + 3_000_000_000L
            while (field.text != "x" && System.nanoTime() < again) Thread.sleep(30)
            assertEquals("x", field.text)
            waitFrames(server)
            // Backspace (a key that types nothing) works too.
            server.onServer { d -> d.key(0, 14, true); d.key(0, 14, false) }
            val erased = System.nanoTime() + 3_000_000_000L
            while (field.text != "" && System.nanoTime() < erased) Thread.sleep(30)
            assertEquals("", field.text)
            waitFrames(server)
            server.onServer { d -> d.key(0, 45, true); d.key(0, 45, false) }
            while (field.text != "x" && System.nanoTime() < erased) Thread.sleep(30)
            // The keyboard taken away: the field lets go of it, typing goes nowhere.
            server.onServer { d -> d.focusWidget(null) }
            Thread.sleep(100)
            server.onServer { d -> d.key(0, 35, true); d.key(0, 35, false) }
            Thread.sleep(200)
            assertEquals("x", field.text)
        }
    }

    @Test
    fun `content that throws is logged and starts over instead of staying dead`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            var boom by mutableStateOf(false)
            var effectBoom by mutableStateOf(false)
            var notYet by mutableStateOf(false)
            var color by mutableStateOf(Color.Red)
            val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val recovery = ComposeRecovery(delayMillis = 100, notice = null, onError = { errors += it })
            server.onServer { d ->
                d.addWidget(ComposeWidget(d, post = { server.loop.post(it::run) }, place = { m -> Rect(m.area.x, m.area.y, 100, 40) }, recovery = recovery) {
                    if (boom) error("a mistake in a composable")
                    if (notYet) TODO("not yet")
                    LaunchedEffect(effectBoom) { if (effectBoom) error("a mistake in an effect") }
                    Box(Modifier.fillMaxWidth().height(40.dp).background(color))
                })
            }
            waitFor(server, 10, 10, RED)
            // Thrown while composed again: told, then (fixed meanwhile) it shows again.
            server.onServer { boom = true }
            waitUntil { errors.size == 1 }
            server.onServer { boom = false; color = Color.Blue }
            waitFor(server, 10, 10, BLUE)
            // Thrown in an effect: the same.
            server.onServer { effectBoom = true }
            waitUntil { errors.size == 2 }
            server.onServer { effectBoom = false; color = Color.Red }
            waitFor(server, 10, 10, RED)
            // An Error, not an Exception (TODO()): the same — not past us into the desktop on every frame.
            server.onServer { notYet = true }
            waitUntil { errors.size == 3 }
            Thread.sleep(50)
            server.onServer { notYet = false; color = Color.Blue }
            waitFor(server, 10, 10, BLUE)
            assertEquals(
                listOf("a mistake in a composable", "a mistake in an effect", "An operation is not implemented: not yet"),
                errors.map { it.message },
                "each failure told once",
            )
        }
    }

    @Test
    fun `a widget shown fully see-through is not drawn and keeps no frames coming`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            var shown by mutableStateOf(false)
            var ticks = 0
            server.onServer { d ->
                d.addWidget(ComposeWidget(d, post = { server.loop.post(it::run) }, place = { m -> Rect(m.area.x, m.area.y, 100, 40) },
                    appearance = { if (shown) WidgetLook.SHOWN else WidgetLook(alpha = 0f) }) {
                    // A spinner: wants a frame all the time.
                    LaunchedEffect(Unit) { while (true) withFrameNanos { ticks++ } }
                    Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Red))
                })
            }
            Thread.sleep(300)
            val frames = server.framesDrawn
            Thread.sleep(500)
            assertTrue(server.framesDrawn - frames <= 2, "frames while hidden: ${server.framesDrawn - frames}")
            server.onServer { d -> shown = true; d.requestRedraw() }
            waitFor(server, 10, 10, RED)
            val shownFrames = server.framesDrawn
            Thread.sleep(300)
            assertTrue(server.framesDrawn - shownFrames > 2, "the spinner moves once it shows")
        }
    }

    @Test
    fun `a lock screen in Compose gets the keys while locked, another widget no clicks`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            var field by mutableStateOf(TextFieldValue(""))
            var clicks = 0
            server.onServer { d ->
                d.addWidget(ComposeWidget(d, post = { server.loop.post(it::run) }, place = { m -> Rect(m.area.x, m.area.y + 100, 100, 40) }) {
                    Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Red).clickable { clicks++ })
                })
                val lock = ComposeWidget(d, post = { server.loop.post(it::run) }, place = { m -> Rect(m.area.x, m.area.y, 300, 40) }, takesKeyboard = true) {
                    val focus = remember { FocusRequester() }
                    BasicTextField(field, { field = it }, Modifier.fillMaxWidth().height(40.dp).background(Color.White).focusRequester(focus))
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
                d.lockInternally(lock)
            }
            waitFor(server, 10, 10, WHITE)
            waitFrames(server)
            server.onServer { d -> for (code in listOf(35, 23)) { d.key(0, code, true); d.key(0, code, false) } } // h, i
            waitUntil { field.text == "hi" }
            // The other widget is behind the lock: a click on it does nothing.
            server.onServer { d ->
                d.pointerMoved(0, 50.0, 120.0)
                d.pointerButton(0, BTN_LEFT, true)
                d.pointerButton(0, BTN_LEFT, false)
            }
            Thread.sleep(300)
            assertEquals(0, clicks)
            server.onServer { d -> d.unlockInternally() }
        }
    }

    @Test
    fun `a widget placed but hidden by its look keeps its scene, not made anew again and again`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            val made = java.util.concurrent.atomic.AtomicInteger()
            server.onServer { d ->
                val widget = ComposeWidget(
                    d, post = { server.loop.post(it::run) }, place = { m -> Rect(m.area.x, m.area.y, 100, 40) },
                    appearance = { WidgetLook(alpha = 0f) },
                ) {
                    remember { made.incrementAndGet() }
                    Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Red))
                }
                widget.keepHiddenSeconds = 1
                d.addWidget(widget)
            }
            waitUntil { made.get() >= 1 }
            // Frames for 3 s (something else changing on the screen).
            val until = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < until) {
                server.onServer { d -> d.requestRedraw() }
                Thread.sleep(100)
            }
            assertEquals(1, made.get(), "composed once")
        }
    }

    private fun waitUntil(what: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!what() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(what(), "not in 5 s")
    }

    // The field sees what it was changed to once it is composed again: a frame or two later.
    // (Faster than any hand types: keys sent at once would find it as it was.)
    private fun waitFrames(server: ScreenTestServer) {
        val frames = server.framesDrawn
        val deadline = System.nanoTime() + 3_000_000_000L
        while (server.framesDrawn < frames + 2 && System.nanoTime() < deadline) Thread.sleep(10)
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
        const val WHITE = 0xFFFFFF
        const val BTN_LEFT = 0x110
    }
}
