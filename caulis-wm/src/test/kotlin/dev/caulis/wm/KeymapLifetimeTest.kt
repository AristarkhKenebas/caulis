package dev.caulis.wm

import dev.caulis.protocol.client.wayland.WlKeyboard
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The keymap programs get is never one that was closed: as on a friend's NixOS (layouts and
 * options from XKB_DEFAULT_*, two layouts, a reload, a layout switch), a terminal started
 * afterwards still gets its keyboard (foot failed with "File descriptor is already closed").
 */
class KeymapLifetimeTest {

    private val harness = CaulisTest()

    // Binds a keyboard and returns the sizes of the keymaps it got (each one read: it must be a real file).
    private fun dev.caulis.core.TestClient.keyboard(): CopyOnWriteArrayList<Int> {
        val sizes = CopyOnWriteArrayList<Int>()
        seat.getKeyboard().events = object : WlKeyboard.Events {
            override fun keymap(proxy: WlKeyboard, format: Int, fd: dev.caulis.ffi.Fd, size: Int) {
                fd.use { sizes += (it.sizeOrNull() ?: -1L).toInt().coerceAtMost(size) }
            }
        }
        return sizes
    }

    @Test
    fun `a keyboard bound after reloads, layout switches and settings still gets a keymap`() = with(harness) {
        val problems = CopyOnWriteArrayList<String>()
        val watch = dev.caulis.log.Log.watch { if (it.isError) problems += it.message }
        try {
            withCaulis({
                keyboardLayouts = "us,ru"
                keyboardOptions = "grp:alt_shift_toggle,caps:escape"
                bindingModifiers = BindingModifiers.XKB
            }, headless = true) { caulis, client ->
                val first = client.keyboard()
                client.waitFor { first.isNotEmpty() }

                // A reload with other options, then the same once more (newm applies its settings again).
                val next = caulis.reloaded {
                    keyboardLayouts = "us,ru"
                    keyboardOptions = "grp:win_space_toggle"
                    bindingModifiers = BindingModifiers.XKB
                }
                caulis.onLoop { it.reconfigure(next) }
                next.onLoop { r ->
                    next.keyboardLayouts = "us,ru"
                    next.keyboardOptions = "grp:alt_shift_toggle"
                    next.switchKeyboardLayout(1)
                    r.applySettingsNow()
                    // Alt+Shift switches the layout (as the friend does).
                    r.key(0, KEY_LEFTALT, true); r.key(0, KEY_LEFTSHIFT, true)
                    r.key(0, KEY_LEFTSHIFT, false); r.key(0, KEY_LEFTALT, false)
                }
                client.waitFor { first.size >= 2 }
                assertTrue(first.all { it > 0 }, "every keymap sent is a real file: $first")

                // A terminal started now.
                val late = client.keyboard()
                client.waitFor { late.isNotEmpty() }
                assertTrue(late.single() > 0)
                assertEquals(emptyList(), problems.toList(), "no errors while handling requests")
            }
        } finally {
            watch.close()
        }
    }

    companion object {
        const val KEY_LEFTALT = 56
        const val KEY_LEFTSHIFT = 42
    }
}
