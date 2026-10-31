package dev.caulis.core.input

import dev.caulis.core.input.KeyBindings.Modifier
import dev.caulis.core.input.PointerBindings.Direction
import dev.caulis.core.input.PointerBindings.GestureOptions
import dev.caulis.core.input.PointerBindings.Options
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointerBindingsTest {
    private val side = 0x113
    private val superOnly = setOf(Modifier.SUPER)
    private val nothing = emptySet<Modifier>()

    @Test
    fun `button names and codes`() {
        assertEquals(0x117, KeyBindings.parse("BTN_TASK").second)
        assertEquals(0x113, KeyBindings.parse("Super+ThumbBack").second)
        assertEquals(0x114, KeyBindings.parse("thumb-forward").second)
        assertEquals(0x112, KeyBindings.parse("WheelClick").second)
        assertEquals(0x115, KeyBindings.parse("mouse:0x115").second)
        assertEquals(0x105, KeyBindings.parse("mouse:261").second)
        assertEquals("MouseSide (BTN_SIDE, 275)", PointerBindings.describe(0x113))
        assertEquals("mouse:262 (code 262)", PointerBindings.describe(262))
    }

    @Test
    fun `a press is taken with its release, and only with exactly its modifiers`() {
        val m = PointerBindings()
        var runs = 0
        m.press("Super+MouseSide") { runs++ }
        assertFalse(m.button(side, true, nothing))
        assertFalse(m.button(side, false, nothing))
        assertFalse(m.button(side, true, setOf(Modifier.SUPER, Modifier.SHIFT)))
        assertFalse(m.button(side, false, setOf(Modifier.SUPER, Modifier.SHIFT)))
        assertEquals(0, runs)
        assertTrue(m.button(side, true, superOnly))
        assertEquals(1, runs)
        // (Super let go first: the release still belongs to the press.)
        assertTrue(m.button(side, false, nothing))
        assertFalse(m.button(side, false, nothing))
    }

    @Test
    fun `passed on it runs and the program gets press and release`() {
        val m = PointerBindings()
        var runs = 0
        m.press("MouseSide", Options(pass = true)) { runs++ }
        assertFalse(m.button(side, true, nothing))
        assertFalse(m.button(side, false, nothing))
        assertEquals(1, runs)
    }

    @Test
    fun `locked only what says so`() {
        val m = PointerBindings()
        var runs = 0
        m.press("MouseSide") { runs++ }
        m.press("MouseExtra", Options(whileLocked = true)) { runs += 10 }
        assertFalse(m.button(side, true, nothing, locked = true))
        assertTrue(m.button(0x114, true, nothing, locked = true))
        assertEquals(10, runs)
    }

    @Test
    fun `the wheel, in parts, with a cooldown, and scrolling when not bound`() {
        val m = PointerBindings()
        val ways = ArrayList<String>()
        m.press("Super+WheelLeft") { ways += "left" }
        m.press("Super+WheelRight", Options(cooldownMillis = 150)) { ways += "right" }
        assertFalse(m.scroll(false, 120, nothing)) // alone it scrolls
        assertFalse(m.scroll(true, 120, superOnly)) // and so does the other wheel
        assertTrue(m.scroll(false, -60, superOnly))
        assertEquals(emptyList(), ways)
        assertTrue(m.scroll(false, -60, superOnly))
        assertEquals(listOf("left"), ways)
        ways.clear()
        assertTrue(m.scroll(false, 120, superOnly, nowMillis = 1000))
        assertTrue(m.scroll(false, 120, superOnly, nowMillis = 1100)) // taken, but too soon
        assertTrue(m.scroll(false, 120, superOnly, nowMillis = 1200))
        assertEquals(listOf("right", "right"), ways)
    }

    @Test
    fun `turned back, the parts of the other way are forgotten`() {
        val m = PointerBindings()
        var left = 0
        m.press("WheelLeft") { left++ }
        m.press("WheelRight") { }
        m.scroll(false, 100, nothing)
        m.scroll(false, -40, nothing)
        assertEquals(0, left)
        m.scroll(false, -80, nothing)
        assertEquals(1, left)
    }

    @Test
    fun `a gesture has a click, each way once, the pointer stays`() {
        val m = PointerBindings()
        val done = ArrayList<String>()
        m.click("MouseTask") { done += "click" }
        m.gesture("MouseTask", Direction.entries.associateWith { d -> { done += d.name } })
        assertTrue(m.button(0x117, true, nothing))
        assertTrue(m.frozen)
        assertTrue(m.motion(1.0, 2.0))
        assertTrue(m.button(0x117, false, nothing))
        assertEquals(listOf("click"), done)
        assertFalse(m.frozen)
        assertFalse(m.motion(100.0, 0.0))
        done.clear()
        m.button(0x117, true, nothing)
        m.motion(-30.0, 2.0)
        assertEquals(emptyList(), done)
        m.motion(-30.0, 2.0)
        m.motion(-300.0, 0.0)
        m.motion(0.0, 500.0) // (the way was chosen)
        m.button(0x117, false, nothing)
        assertEquals(listOf("LEFT"), done) // and no click
    }

    @Test
    fun `a gesture repeats when asked, and can leave the pointer free`() {
        val m = PointerBindings()
        var downs = 0
        m.gesture("MouseTask", mapOf(Direction.DOWN to { downs++ }), GestureOptions(threshold = 10.0, repeatEvery = 20.0, freezePointer = false))
        m.button(0x117, true, nothing)
        assertFalse(m.motion(0.0, 15.0))
        assertEquals(1, downs)
        m.motion(0.0, 45.0)
        assertEquals(3, downs)
    }

    @Test
    fun `a long press, not after the release or a gesture`() {
        val m = PointerBindings()
        var timer: () -> Unit = { }
        m.schedule = { _, task ->
            timer = task
            AutoCloseable { }
        }
        val done = ArrayList<String>()
        m.click("MouseMiddle") { done += "click" }
        m.longPress("MouseMiddle", 500) { done += "long" }
        m.button(0x112, true, nothing)
        timer()
        m.button(0x112, false, nothing)
        assertEquals(listOf("long"), done)
        done.clear()
        m.button(0x112, true, nothing)
        m.button(0x112, false, nothing)
        timer()
        assertEquals(listOf("click"), done)
    }

    @Test
    fun `a hold hears everything, and a lost release does not stick`() {
        val m = PointerBindings()
        val done = ArrayList<String>()
        m.hold("MouseTask", start = { done += "start" }, moved = { x, _ -> done += "moved $x"; true }, end = { done += "end" })
        m.button(0x117, true, nothing)
        assertTrue(m.motion(5.0, 0.0))
        m.button(0x117, true, nothing) // (the release was lost)
        m.button(0x117, false, nothing)
        assertEquals(listOf("start", "moved 5.0", "start", "end"), done)
        done.clear()
        m.button(0x117, true, nothing)
        m.releaseAll()
        assertFalse(m.button(0x117, false, nothing))
        assertFalse(m.motion(5.0, 0.0))
        assertEquals(listOf("start", "end"), done)
    }

    @Test
    fun `an action that fails does not stop input`() {
        val m = PointerBindings()
        var told = 0
        m.onFailure = { told++ }
        m.press("MouseSide") { error("no") }
        assertTrue(m.button(side, true, nothing))
        assertEquals(1, told)
        assertTrue(m.button(side, false, nothing))
    }

    @Test
    fun `unbound and cleared`() {
        val m = PointerBindings()
        m.press("MouseSide") { }
        assertTrue(m.has(side, nothing))
        m.unbind("MouseSide")
        assertFalse(m.has(side, nothing))
        m.press("MouseSide", Options(description = "Back")) { }
        assertEquals(listOf("MouseSide" to "Back"), m.described())
        m.clear()
        assertEquals(emptyList(), m.described())
    }
}
