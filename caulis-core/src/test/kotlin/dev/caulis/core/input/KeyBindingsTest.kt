package dev.caulis.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyBindingsTest {

    private val events = ArrayList<String>()
    private val keys = KeyBindings().apply {
        bind("Ctrl+Alt+BackSpace") { events += "quit" }
        bind("Super+Return") { events += "terminal" }
        bind("Ctrl+Alt+F3") { events += "console 3" }
        bind("Super+Shift+Q") { events += "force quit" }
    }

    @Test
    fun `a combination runs and is not passed on, its parts are`() {
        assertFalse(keys.key(29, true)) // Ctrl goes to programs as usual
        assertFalse(keys.key(56, true)) // Alt too
        assertTrue(keys.key(14, true))
        assertTrue(keys.key(14, false)) // its release is swallowed as well
        assertFalse(keys.key(56, false))
        assertEquals(listOf("quit"), events)
    }

    @Test
    fun `right-hand modifiers count too`() {
        keys.key(97, true) // right Ctrl
        keys.key(100, true) // right Alt
        assertTrue(keys.key(61, true)) // F3
        assertEquals(listOf("console 3"), events)
    }

    @Test
    fun `modifiers must match exactly`() {
        keys.key(125, true) // Super
        keys.key(42, true) // Shift
        assertFalse(keys.key(28, true), "Super+Shift+Return is not Super+Return")
        assertTrue(keys.key(16, true)) // Q
        assertEquals(listOf("force quit"), events)
    }

    @Test
    fun `plain keys are passed on`() {
        assertFalse(keys.key(30, true))
        assertFalse(keys.key(30, false))
        assertTrue(events.isEmpty())
    }


    @Test
    fun `combinations written any way people write them`() {
        val superQ = setOf(KeyBindings.Modifier.SUPER) to 16
        for (written in listOf("Super+Q", "super+q", "sUpEr+Q", "wIn+q", "Mod4 + Q", "super q", "Q+Super", "Meta+Q", "Super+Й", "LOGO+q")) {
            assertEquals(superQ, KeyBindings.parse(written), written)
        }
        assertEquals(superQ, KeyBindings.parse(listOf("super", "q")))
        assertEquals(setOf(KeyBindings.Modifier.CTRL, KeyBindings.Modifier.SHIFT) to 104, KeyBindings.parse("control shift page_up"))
        assertEquals(emptySet<KeyBindings.Modifier>() to 115, KeyBindings.parse("XF86AudioRaiseVolume"))
        assertEquals(setOf(KeyBindings.Modifier.ALT) to 12, KeyBindings.parse("Alt+-"))
    }

    @Test
    fun `typos give readable errors`() {
        assertFailsWith<IllegalArgumentException> { KeyBindings().bind("Hyper+Q") {} }
        assertFailsWith<IllegalArgumentException> { KeyBindings().bind("Super+Banana") {} }
        assertEquals("Unknown key \"Retrun\" in \"Super+Retrun\" (did you mean Return?)", assertFailsWith<IllegalArgumentException> { KeyBindings.parse("Super+Retrun") }.message)
        assertEquals("Unknown key \"Supr\" in \"Supr+Q\" (did you mean Super?)", assertFailsWith<IllegalArgumentException> { KeyBindings.parse("Supr+Q") }.message)
        assertTrue("one key" in assertFailsWith<IllegalArgumentException> { KeyBindings.parse("Super+Q+W") }.message!!)
        assertTrue("no key" in assertFailsWith<IllegalArgumentException> { KeyBindings.parse("Super+Shift") }.message!!)
    }

    @Test
    fun `a combination can repeat while held`() {
        val pending = ArrayList<Pair<Long, () -> Unit>>()
        val keys = KeyBindings().apply {
            schedule = { delay, task -> pending += delay to task; AutoCloseable { pending.removeIf { it.second === task } } }
            repeatDelay = 400
            repeatRate = 20
            bind("XF86AudioRaiseVolume", KeyBindings.Options(repeat = true)) { events += "louder" }
        }
        keys.key(115, true)
        assertEquals(listOf("louder"), events, "once at once")
        assertEquals(400L, pending.single().first, "then after the delay")
        pending.removeFirst().second()
        assertEquals(50L, pending.single().first, "then 20 times a second")
        pending.removeFirst().second()
        assertEquals(3, events.size)
        keys.key(115, false)
        assertTrue(pending.isEmpty(), "let go: it stops")
    }

    @Test
    fun `a combination can run when let go`() {
        val keys = KeyBindings().apply { bind("Super+P", KeyBindings.Options(release = true)) { events += "on release" } }
        keys.key(125, true)
        assertTrue(keys.key(25, true))
        assertTrue(events.isEmpty())
        assertTrue(keys.key(25, false))
        assertEquals(listOf("on release"), events)
    }

    @Test
    fun `a modifier alone is a tap, but not when something else came in between`() {
        val keys = KeyBindings().apply {
            bind("Super") { events += "tap" }
            bind("Super+Q") { events += "super q" }
        }
        assertFalse(keys.key(125, true))
        assertFalse(keys.key(125, false), "Super still goes to programs")
        assertEquals(listOf("tap"), events)
        keys.key(125, true)
        keys.key(16, true)
        keys.key(16, false)
        keys.key(125, false)
        assertEquals(listOf("tap", "super q"), events, "no tap after Super+Q")
        keys.key(125, true)
        keys.interrupted() // a click or a scroll with Super held
        keys.key(125, false)
        assertEquals(2, events.size)
    }

    @Test
    fun `mouse buttons and the wheel`() {
        val keys = KeyBindings().apply {
            bind("Super+MouseSide") { events += "side" }
            bind("mouse:277") { events += "forward" }
            bind("Super+WheelDown") { events += "down" }
        }
        keys.key(125, true)
        assertTrue(keys.key(0x113, true))
        assertTrue(keys.key(0x113, false))
        assertTrue(keys.has(KeyBindings.WHEEL_DOWN))
        assertTrue(keys.wheel(KeyBindings.WHEEL_DOWN))
        assertFalse(keys.wheel(KeyBindings.WHEEL_UP))
        keys.key(125, false)
        assertTrue(keys.key(0x115, true))
        assertEquals(listOf("side", "down", "forward"), events)
        assertTrue("not a mouse button" in assertFailsWith<IllegalArgumentException> { KeyBindings.parse("mouse:5") }.message!!)
    }

    @Test
    fun `device settings - all pointers, touchpads, then a device by name`() {
        val input = InputSettings().apply {
            pointers.speed = 0.2
            pointers.naturalScroll = false
            touchpads.naturalScroll = true
            devices["Logitech*"] = DeviceSettings().apply { speed = -0.5 }
        }
        val pad = input.forDevice("SynPS/2 Synaptics TouchPad", touchpad = true)
        assertEquals(0.2 to true, pad.speed to pad.naturalScroll)
        val mouse = input.forDevice("Logitech G Pro", touchpad = false)
        assertEquals(-0.5 to false, mouse.speed to mouse.naturalScroll)
        assertFailsWith<IllegalArgumentException> { DeviceSettings().speed = 2.0 }
    }
}
