package dev.caulis.wm

import dev.caulis.core.Desktop
import dev.caulis.core.Window
import java.util.IdentityHashMap

/**
 * Numbers for windows, for programs (the IPC socket) and scripts (Lua): the same while a
 * window is open, never used again for another one.
 */
internal class WindowIds(desktop: Desktop) {

    private val ids = IdentityHashMap<Window, Long>()
    private val byId = HashMap<Long, Window>()
    private var next = 1L

    init {
        desktop.onWindowClosed { window -> ids.remove(window)?.let(byId::remove) }
    }

    fun of(window: Window): Long = ids.getOrPut(window) { (next++).also { byId[it] = window } }

    fun window(id: Long): Window? = byId[id]
}
